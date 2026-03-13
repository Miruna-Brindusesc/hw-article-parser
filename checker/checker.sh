#!/bin/bash
date
hostname

# Configuration for small test
THREADS_PAR=(2 4)
TIMEOUT_BASE=200

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
JAVA_DIR="${ROOT_DIR}"
TESTS_DIR="${ROOT_DIR}/checker/input/tests/test_small"
OUT_DIR="${ROOT_DIR}/checker/solution_output"
EXPECTED_DIR="${ROOT_DIR}/checker/output/test_small"

# Small test mode
SMALL_MODE=1

correctness_points=0
seq_time=0

# Helper functions
function format_number() {
    local n="$1"
    if [[ "$n" =~ ^-?[0-9]+\.0+$ ]]; then
        echo "${n%.*}"
    else
        echo "$n"
    fi
}

function show_score() {
    local f_corr=$(format_number "${correctness_points}")
    echo ""
    echo "Scalability: 0/0"
    echo "Correctness: ${f_corr}/6"
    echo "Total:       ${f_corr}/6"
}

function build_project() {
    echo "[BUILD] Building Java project..."
    pushd "$JAVA_DIR" > /dev/null || exit 1
    echo "[BUILD] make clean..."
    make clean
    echo "[BUILD] make build..."
    make build
    if [ $? -ne 0 ]; then
        echo "E: Could not compile Java project"
        popd > /dev/null
        show_score
        exit 1
    fi
    popd > /dev/null
    echo "[BUILD] Done"
}

function compare_outputs() {
    echo "[DEBUG] Comparing expected vs actual outputs..."
    echo "EXPECTED DIR: $1"
    echo "ACTUAL DIR:   $2"
    echo "[DEBUG] Listing expected files:"
    ls -l "$1"
    echo "[DEBUG] Listing actual files:"
    ls -l "$2"
    diff -rq -w "$1" "$2"
    return $?
}

function run_and_collect() {
    local threads=$1
    local outdir=$2
    local timeout=$3

    echo "[DEBUG] Running Java with $threads thread(s)"
    echo "[DEBUG] Output directory: $outdir"

    rm -rf "$outdir"
    mkdir -p "$outdir"

    local timefile=$(mktemp 2>/dev/null || echo "/tmp/checker_time_$$.txt")
    local timeout_cmd=""
    if command -v timeout >/dev/null 2>&1; then
        timeout_cmd="timeout ${timeout}"
    fi

    # Run Java in the outdir to generate output there
    pushd "$outdir" > /dev/null || exit 1
    echo "[DEBUG] Current working dir: $(pwd)"
    echo "[DEBUG] ARTICLES_FILE=$ARTICLES_FILE"
    echo "[DEBUG] INPUTS_FILE=$INPUTS_FILE"
    echo "[DEBUG] Executing: java -jar ${JAVA_DIR}/target/tema1-1.0-jar-with-dependencies.jar ${threads} ${ARTICLES_FILE} ${INPUTS_FILE}"
    
    { time -p java -jar "${JAVA_DIR}/target/tema1-1.0-jar-with-dependencies.jar" "${threads}" "${ARTICLES_FILE}" "${INPUTS_FILE}" > /dev/null; } &> "$timefile"
    local ret=$?

    echo "[DEBUG] Java return code: $ret"
    echo "[DEBUG] Listing files generated in outdir:"
    ls -l

    popd > /dev/null || exit 1

    # Normalize line endings for diff
    find "$outdir" -type f -name "*.txt" -exec sed -i 's/\r$//' {} \;

    local t=""
    if [ -f "$timefile" ]; then
        t=$(grep '^real' "$timefile" | awk '{print $2}')
    fi
    rm -f "$timefile"

    echo "$t"
    return $ret
}

# Run test_small
ARTICLES_FILE="${TESTS_DIR}/articles.txt"
INPUTS_FILE="${TESTS_DIR}/inputs.txt"

if [ ! -f "$ARTICLES_FILE" ] || [ ! -f "$INPUTS_FILE" ]; then
    echo "E: Missing test files for test_small"
    exit 1
fi

build_project
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

echo ""
echo "========== test_small =========="
echo "[RUN] Sequential run with 1 thread..."
seq_time=$(run_and_collect 1 "${OUT_DIR}/test_seq" ${TIMEOUT_BASE})
if [ $? -ne 0 ]; then
    echo "E: Sequential run failed for test_small"
else
    compare_outputs "${EXPECTED_DIR}" "${OUT_DIR}/test_seq"
    if [ $? -eq 0 ]; then
        printf "  \xE2\x9C\x93 Correct\n"
        correctness_points=6   # maximum points for small test
    else
        echo "E: Output differs from reference"
    fi
fi

echo ""
echo "=========================="
show_score