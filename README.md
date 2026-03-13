# Homework: Article Parser

## Overview

This project implements a **multi-threaded article parser** in Java. It processes large sets of JSON article files and generates summary statistics, including counts of unique authors, keywords, categories, and languages. The parser is designed to run efficiently in parallel using multiple threads and ensures correctness through a sequential and parallel verification process.

## Features

* **Multi-threaded processing:** Uses `Thread` and `CyclicBarrier` to parallelize file parsing while ensuring correct aggregation of global statistics.
* **Duplicate detection:** Filters articles by unique UUID and title across all files.
* **Keyword extraction:** Extracts English keywords while ignoring stop words.
* **Category and language mapping:** Generates mappings from categories and languages to article UUIDs.
* **Comprehensive reports:** Generates `all_articles.txt`, category and language files, `keywords_count.txt`, and a `reports.txt` summary.
* **Configurable threads:** Sequential or parallel execution with configurable thread counts.

## Project Structure

```
.
├── src/ArticleParser.java          # Main parser implementation
├── checker/                       # Scripts and test setup
│   ├── checker.sh                 # Bash script to run tests and evaluate correctness
│   ├── input/tests/               # Sample input files for testing
│   │   └── test_small/
│   │       ├── articles.txt
│   │       └── inputs.txt
│   └── solution_output/           # Generated outputs from test runs
├── README.md                      # Project description
├── Makefile                       # Build and run commands
└── pom.xml                        # Maven build dependencies
```

## Implementation Highlights

* **Phase 1 (Parsing):** Each worker thread parses assigned files and collects local statistics.
* **Barrier synchronization:** Ensures all local UUID and title counts are merged before filtering duplicates.
* **Phase 2 (Filtering & aggregation):** Each thread filters articles based on duplicates, categories, and languages, then aggregates global statistics.
* **Optimized memory usage:** Uses zero-allocation buffers and local maps to reduce memory overhead during parsing.
* **Sorting & reporting:** Final outputs are sorted by publication date and UUID for consistent output.


## Usage

### Build the Project

```bash
make clean
make build
```

### Run the Parser
Sequential execution with 1 thread:

```bash
java -jar target/tema1-1.0-jar-with-dependencies.jar 1 <articles_file> <aux_file>
```

Parallel execution with N threads:

```bash
java -jar target/tema1-1.0-jar-with-dependencies.jar N <articles_file> <aux_file>
```

**Outputs generated in current directory:**

* `all_articles.txt` – list of all valid articles
* `<category>.txt` – articles grouped by category
* `<language>.txt` – articles grouped by language
* `keywords_count.txt` – counts of English keywords
* `reports.txt` – summary report (duplicates, top author, top language/category, most recent article, top keyword)

## Testing

The `checker/checker.sh` script provides automated testing a test set.

Run small test:

```bash
checker/checker.sh test_small

```
### Note on Test Cases
Due to size limitations, **not all test cases were included** in this repository. Only a **small sample** of input files and test cases is provided for demonstration purposes.

To fully test the project, you will need a larger set of input files and auxiliary data.

## Requirements

* Java 11+
* Maven (for building)
