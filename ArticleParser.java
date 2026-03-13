import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.io.*;
import java.nio.file.*;
import com.fasterxml.jackson.databind.*;

public class ArticleParser {
    private static final ObjectMapper mapper = new ObjectMapper();

    // shared Resources 
    private static final AtomicInteger sharedFileIndex = new AtomicInteger(0);
    
    // global maps populated ONLY at the barrier
    private static Map<String, Integer> GLOBAL_UUID_COUNTS;
    private static Map<String, Integer> GLOBAL_TITLE_COUNTS;

    // config
    private static Set<String> GLOBAL_STOP_WORDS;
    private static Set<String> VALID_CATEGORIES;
    private static Set<String> VALID_LANGUAGES;

    // Article class
    private static class Article {
        String uuid;
        String title;
        String author;
        String url;
        String published;
        String language;
        List<String> categories;
        // keywords populated only if needed
        Set<String> keywords; 
    }

    // worker 
    private static class ParserWorker extends Thread {
        private final List<String> files;
        private final CyclicBarrier barrier;

        // local data Phase 1 (temporarily stored)
        List<Article> localArticles = new ArrayList<>();
        Map<String, Integer> localTitleCounts = new HashMap<>();
        Map<String, Integer> localUuidCounts = new HashMap<>();

        // final local results (after filtering)
        List<Article> validArticles = new ArrayList<>();
        Map<String, Integer> localAuthorCounts = new HashMap<>();
        Map<String, Integer> localKeywordCounts = new HashMap<>();
        Map<String, Set<String>> localLangToUuids = new HashMap<>();
        Map<String, Set<String>> localCatToUuids = new HashMap<>();

        // reusable buffer (Zero-Allocation per word)
        private final StringBuilder sb = new StringBuilder(512);

        public ParserWorker(List<String> files, CyclicBarrier barrier) {
            this.files = files;
            this.barrier = barrier;
        }

        @Override
        public void run() {
        
            // PHASE 1:
        
            int totalFiles = files.size();
            while (true) {
                int i = sharedFileIndex.getAndIncrement();
                if (i >= totalFiles) break;
                try {
                    processFile(files.get(i));
                } catch (Exception e) {}
            }

            
            // BARRIER
          
            try {
                barrier.await();
            } catch (Exception e) { return; }

            
            // PHASE 2
            
            // now GLOBAL_COUNTS are complete. we can filter in parallel.
            
            for (Article a : localArticles) {
                
                if (GLOBAL_UUID_COUNTS.getOrDefault(a.uuid, 0) > 1) continue;
                if (GLOBAL_TITLE_COUNTS.getOrDefault(a.title, 0) > 1) continue;

            
                validArticles.add(a);
                if (!a.author.isEmpty()) 
                    localAuthorCounts.merge(a.author, 1, Integer::sum);
                
                if (VALID_LANGUAGES.contains(a.language))
                    localLangToUuids.computeIfAbsent(a.language, k -> new HashSet<>()).add(a.uuid);

                if (a.categories != null) {
                    for (String c : a.categories) {
                        String norm = normalizeCat(c);
                        if (VALID_CATEGORIES.contains(norm))
                            localCatToUuids.computeIfAbsent(norm, k -> new HashSet<>()).add(a.uuid);
                    }
                }

                if (a.keywords != null) {
                    for (String kw : a.keywords)
                        localKeywordCounts.merge(kw, 1, Integer::sum);
                }
            }
            
            // free memory
            localArticles = null;
            localUuidCounts = null;
            localTitleCounts = null;
        }

        private void processFile(String path) throws IOException {
            JsonNode root = mapper.readTree(new File(path));
            if (root == null) return;
            if (root.isArray()) {
                for (JsonNode node : root) parseAndAdd(node);
            } else {
                parseAndAdd(root);
            }
        }

        private void parseAndAdd(JsonNode node) {
            Article art = new Article();
            art.uuid = getSafeText(node, "uuid"); 
            art.title = getSafeText(node, "title");
            
            // optimization: trim only where necessary for statistics
            art.author = getSafeText(node, "author").trim();
            art.url = getSafeText(node, "url").trim();
            art.published = getSafeText(node, "published").trim();
            art.language = getSafeText(node, "language").trim();

            // count local
            if (!art.uuid.isEmpty()) localUuidCounts.merge(art.uuid.trim(), 1, Integer::sum);
            localTitleCounts.merge(art.title, 1, Integer::sum);
            
            art.uuid = art.uuid.trim();

            // categories
            JsonNode catsNode = node.get("categories");
            if (catsNode != null && catsNode.isArray()) {
                art.categories = new ArrayList<>(catsNode.size());
                for (JsonNode cat : catsNode) {
                    if (!cat.isNull()) art.categories.add(cat.asText());
                }
            } else {
                art.categories = Collections.emptyList();
            }

            // keywords
            if ("english".equalsIgnoreCase(art.language)) {
                JsonNode txtNode = node.get("text");
                if (txtNode != null && !txtNode.isNull()) {
                    String text = txtNode.asText();
                    if (!text.isEmpty()) {
                        art.keywords = new HashSet<>();
                        fastProcessKeywords(text, art.keywords);
                    }
                }
            }
            
            localArticles.add(art);
        }

        
        private void fastProcessKeywords(String text, Set<String> target) {

            String[] w = text.toLowerCase().split("\\s+");
            for (String x : w) {
                if (x.isEmpty()) continue;
                String cleaned = x.replaceAll("[^a-z]", "");
                if (!cleaned.isEmpty() && !GLOBAL_STOP_WORDS.contains(cleaned)) {
                    target.add(cleaned);
                }
            }
        } 

        private void addWordIfValid(Set<String> targetSet) {
            String word = sb.toString();
            if (!GLOBAL_STOP_WORDS.contains(word)) {
                targetSet.add(word);
            }
        }

        private String getSafeText(JsonNode node, String field) {
            JsonNode f = node.get(field);
            return (f != null && !f.isNull()) ? f.asText() : "";
        }
    }

    // helpers 
    private static String normalizeCat(String cat) {
        return cat.replace(",", "").replaceAll("\\s+", "_").trim();
    }

    private static List<String> readLines(String path, String base) throws IOException {
        return Files.readAllLines(Paths.get(base).toAbsolutePath().getParent().resolve(path).normalize());
    }
    
    // main 
    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 3) return;
        
        int numThreads = Integer.parseInt(args[0]);
        String articlesFile = args[1];
        String auxFile = args[2];

        // sequential config
        List<String> auxLines = Files.readAllLines(Paths.get(auxFile));
        
        List<String> langData = readLines(auxLines.get(1).trim(), auxFile);
        VALID_LANGUAGES = new HashSet<>(langData.subList(1, Integer.parseInt(langData.get(0).trim()) + 1));
        
        List<String> catData = readLines(auxLines.get(2).trim(), auxFile);
        int catCount = Integer.parseInt(catData.get(0).trim());
        VALID_CATEGORIES = new HashSet<>();
        for(int i=1; i<=catCount; i++) VALID_CATEGORIES.add(normalizeCat(catData.get(i)));

        List<String> stopData = readLines(auxLines.get(3).trim(), auxFile);
        int stopCount = Integer.parseInt(stopData.get(0).trim());
        GLOBAL_STOP_WORDS = new HashSet<>();
        for(int i=1; i<=stopCount; i++) GLOBAL_STOP_WORDS.add(stopData.get(i).trim().toLowerCase());

        // sort files
        List<String> fileListLines = Files.readAllLines(Paths.get(articlesFile));
        int nFiles = Integer.parseInt(fileListLines.get(0).trim());
        Path artBaseDir = Paths.get(articlesFile).toAbsolutePath().getParent();
        
        class FileEntry { String p; long s; FileEntry(String p, long s){this.p=p;this.s=s;} }
        List<FileEntry> entries = new ArrayList<>(nFiles);
        for(int i=1; i<=nFiles; i++) {
            String p = artBaseDir.resolve(fileListLines.get(i).trim()).normalize().toString();
            entries.add(new FileEntry(p, new File(p).length()));
        }
        // large files first
        entries.sort((a, b) -> Long.compare(b.s, a.s));
        
        List<String> sortedFiles = new ArrayList<>();
        for(FileEntry e : entries) sortedFiles.add(e.p);

        // workers barriers
        ParserWorker[] workers = new ParserWorker[numThreads];
        
        Runnable barrierAction = () -> {
            GLOBAL_UUID_COUNTS = new HashMap<>();
            GLOBAL_TITLE_COUNTS = new HashMap<>();
            // merge counts from all workers
            for (ParserWorker w : workers) {
                w.localUuidCounts.forEach((k, v) -> GLOBAL_UUID_COUNTS.merge(k, v, Integer::sum));
                w.localTitleCounts.forEach((k, v) -> GLOBAL_TITLE_COUNTS.merge(k, v, Integer::sum));
            }
        };
        
        CyclicBarrier barrier = new CyclicBarrier(numThreads, barrierAction);

        for (int i = 0; i < numThreads; i++) {
            workers[i] = new ParserWorker(sortedFiles, barrier);
            workers[i].start();
        }

        for (ParserWorker w : workers) w.join();

        // final Merge (statistics are already filtered and calculated in workers)
        List<Article> finalArticles = new ArrayList<>();
        Map<String, Integer> authorCounts = new HashMap<>();
        Map<String, Integer> keywordCounts = new HashMap<>();
        Map<String, Set<String>> catToUuids = new HashMap<>();
        Map<String, Set<String>> langToUuids = new HashMap<>();

        for (ParserWorker w : workers) {
            finalArticles.addAll(w.validArticles);
            
            w.localAuthorCounts.forEach((k, v) -> authorCounts.merge(k, v, Integer::sum));
            w.localKeywordCounts.forEach((k, v) -> keywordCounts.merge(k, v, Integer::sum));
            
            for(var e : w.localCatToUuids.entrySet()) 
                catToUuids.computeIfAbsent(e.getKey(), k->new HashSet<>()).addAll(e.getValue());
                
            for(var e : w.localLangToUuids.entrySet()) 
                langToUuids.computeIfAbsent(e.getKey(), k->new HashSet<>()).addAll(e.getValue());
        }

        // calculate duplicates
        // safe approximation: sum of global counts
        int totalInputs = GLOBAL_UUID_COUNTS.values().stream().mapToInt(Integer::intValue).sum();

        // sorting and output
        finalArticles.sort((a, b) -> {
            int cmp = b.published.compareTo(a.published);
            return cmp != 0 ? cmp : a.uuid.compareTo(b.uuid);
        });

        try (PrintWriter pw = new PrintWriter("all_articles.txt", "UTF-8")) {
            for (Article a : finalArticles) pw.println(a.uuid + " " + a.published);
        }

        writeMapToFile(catToUuids);
        writeMapToFile(langToUuids);

        List<Map.Entry<String, Integer>> sortedKw = new ArrayList<>(keywordCounts.entrySet());
        sortedKw.sort((a, b) -> {
            int cmp = b.getValue().compareTo(a.getValue());
            return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
        });
        
        try (PrintWriter pw = new PrintWriter("keywords_count.txt", "UTF-8")) {
            for (var e : sortedKw) pw.println(e.getKey() + " " + e.getValue());
        }

        try (PrintWriter pw = new PrintWriter("reports.txt", "UTF-8")) {
            pw.println("duplicates_found - " + (totalInputs - finalArticles.size()));
            pw.println("unique_articles - " + finalArticles.size());
            
            String bestAuth = authorCounts.entrySet().stream()
                .max((a, b) -> a.getValue().equals(b.getValue()) ? b.getKey().compareTo(a.getKey()) : a.getValue().compareTo(b.getValue()))
                .map(e -> e.getKey() + " " + e.getValue()).orElse(" 0");
            pw.println("best_author - " + bestAuth);

            String topLang = langToUuids.entrySet().stream()
                .max((a, b) -> Integer.compare(a.getValue().size(), b.getValue().size()))
                .map(e -> e.getKey() + " " + e.getValue().size()).orElse(" 0");
            pw.println("top_language - " + topLang);

            String topCat = catToUuids.entrySet().stream()
                .max((a, b) -> Integer.compare(a.getValue().size(), b.getValue().size()))
                .map(e -> e.getKey() + " " + e.getValue().size()).orElse(" 0");
            pw.println("top_category - " + topCat);

            String recent = finalArticles.isEmpty() ? " " : finalArticles.get(0).published + " " + finalArticles.get(0).url;
            pw.println("most_recent_article - " + recent);

            String topKw = sortedKw.isEmpty() ? " 0" : sortedKw.get(0).getKey() + " " + sortedKw.get(0).getValue();
            pw.println("top_keyword_en - " + topKw);
        }
    }

    private static void writeMapToFile(Map<String, Set<String>> map) throws IOException {
        for(var e : map.entrySet()) {
            List<String> list = new ArrayList<>(e.getValue());
            Collections.sort(list);
            try (PrintWriter pw = new PrintWriter(e.getKey() + ".txt", "UTF-8")) {
                for(String s : list) pw.println(s);
            }
        }
    }
}