#!/usr/bin/env bash
set -euo pipefail

ITERATIONS="${1:-100000}"
SAMPLES="${2:-9}"
CORPUS="${3:-scalar32}"
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
ROOT="$(git -C "$SCRIPT_DIR/.." rev-parse --show-toplevel)"
case "$CORPUS" in
    scalar32)
        SNAPSHOT="$ROOT/docs/benchmarks/riscv-xlen-current.txt"
        SOURCE_ROOT="$ROOT/tools/fixtures/isa-gate2-c"
        COMPILE_SCRIPT="$ROOT/scripts/compile-riscv-xlen-gate-corpus.sh"
        WORKLOADS=(compute32 branch-mix call-stack memory-sequential memory-random copy-checksum)
        EXPECTED_ROWS=18
        ISSUE=483
        TITLE="RISC-V XLEN scalar32 compiled-C benchmark current snapshot"
        BENCHMARK_CORPUS_ARGS=()
        ;;
    u64)
        SNAPSHOT="$ROOT/docs/benchmarks/riscv-xlen-u64-current.txt"
        SOURCE_ROOT="$ROOT/tools/fixtures/riscv-xlen-u64"
        COMPILE_SCRIPT="$ROOT/scripts/compile-riscv-xlen-u64-corpus.sh"
        WORKLOADS=(u64-mix fixed64-geometry u64-memory)
        EXPECTED_ROWS=9
        ISSUE=484
        TITLE="RISC-V XLEN u64-heavy compiled-C benchmark current snapshot"
        BENCHMARK_CORPUS_ARGS=(u64)
        ;;
    *)
        echo "corpus must be scalar32 or u64" >&2
        exit 2
        ;;
esac
: "${RISCV_XLEN_CLANG:=clang}"
: "${RISCV_XLEN_LLD:=ld.lld}"

for value_name in ITERATIONS SAMPLES; do
    value="${!value_name}"
    if ! [[ "$value" =~ ^[1-9][0-9]*$ ]] || (( value > 4294967295 )); then
        echo "${value_name,,} must be an integer in 1..4294967295" >&2
        exit 2
    fi
done
if (( SAMPLES % 2 == 0 )); then
    echo "samples must be odd" >&2
    exit 2
fi

for tool in "$RISCV_XLEN_CLANG" "$RISCV_XLEN_LLD"; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "required RISC-V XLEN tool is unavailable: $tool" >&2
        exit 2
    fi
    if ! "$tool" --version | grep -Eq '(^|[^0-9])22\.[0-9]'; then
        echo "RISC-V XLEN benchmark requires LLVM major version 22: $tool" >&2
        exit 2
    fi
done

mkdir -p "$ROOT/docs/benchmarks"
ARTIFACTS="$(mktemp -d "${TMPDIR:-/tmp}/riscv-xlen-artifacts.XXXXXX")"
TEMPORARY=""
completed=0
cleanup() {
    status=$?
    trap - EXIT HUP INT TERM
    if [[ -n "$TEMPORARY" ]]; then
        rm -f "$TEMPORARY"
    fi
    if (( completed )); then
        rm -rf "$ARTIFACTS"
    else
        echo "RISC-V XLEN recording failed; artifacts preserved at $ARTIFACTS" >&2
    fi
    exit "$status"
}
trap cleanup EXIT HUP INT TERM

dirty_paths="$(git -C "$ROOT" status --porcelain -- . ":(exclude)${SNAPSHOT#"$ROOT/"}" 2>/dev/null || true)"
if [[ -n "$dirty_paths" ]]; then
    echo "RISC-V XLEN recording requires a clean worktree except its snapshot:" >&2
    printf '%s\n' "$dirty_paths" >&2
    exit 1
fi

recorded_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
commit="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || printf unknown)"
TEMPORARY="$(mktemp "$SNAPSHOT.tmp.XXXXXX")"
host="$(uname -srmo)"
rustc_version="$(rustc --version)"
cargo_version="$(cargo --version)"
clang_version="$($RISCV_XLEN_CLANG --version | head -n 1)"
lld_version="$($RISCV_XLEN_LLD --version | head -n 1)"
cpu="unknown"
if [[ -r /proc/cpuinfo ]]; then
    cpu="$(awk -F: '/model name/ { value=$2; sub(/^[ \t]+/, "", value); print value; exit }' /proc/cpuinfo)"
    [[ -n "$cpu" ]] || cpu="unknown"
fi

RISCV_XLEN_CLANG="$RISCV_XLEN_CLANG" RISCV_XLEN_LLD="$RISCV_XLEN_LLD" \
    "$COMPILE_SCRIPT" "$ARTIFACTS" "$ITERATIONS"
benchmark_output="$(
    cargo run --quiet --locked --offline --release \
        --manifest-path "$ROOT/host/k16-vm/Cargo.toml" \
        --example riscv_xlen_benchmarks -- "$ARTIFACTS" "$ITERATIONS" "$SAMPLES" \
        "${BENCHMARK_CORPUS_ARGS[@]}"
)"

timing_rows="$(printf '%s\n' "$benchmark_output" | awk -F '\t' '$2 == "rv32im" || $2 == "rv64im" || $2 == "native-rust" || $2 == "native-c" { count += 1; if ($16 != 0 || $17 != 0) bad = 1 } END { if (bad) exit 1; print count + 0 }')"
if [[ "$timing_rows" -ne "$EXPECTED_ROWS" ]]; then
    echo "RISC-V XLEN benchmark expected $EXPECTED_ROWS allocation-free timing rows, got $timing_rows" >&2
    exit 1
fi
printf '%s\n' "$benchmark_output" | grep -q '^rv32im[[:space:]]'
printf '%s\n' "$benchmark_output" | grep -q '^rv64im[[:space:]]'
printf '%s\n' "$benchmark_output" | grep -q '^rv64_to_rv32_warm_geomean[[:space:]]'
if printf '%s\n' "$benchmark_output" | grep -q '^decision[[:space:]]'; then
    echo "RISC-V XLEN benchmark must not make an architecture decision" >&2
    exit 1
fi

{
    echo "$TITLE"
    echo
    echo "Issue: #$ISSUE"
    echo "Recorded at: $recorded_at"
    echo "Commit: $commit (clean)"
    echo "Host: $host"
    echo "CPU: $cpu"
    echo "Rust: $rustc_version"
    echo "Cargo: $cargo_version"
    echo "Clang: $clang_version"
    echo "LLD: $lld_version"
    echo "Command: bash scripts/record-riscv-xlen-gate-benchmark.sh $ITERATIONS $SAMPLES $CORPUS"
    echo
    echo "Source corpus SHA-256:"
    for source in "$SOURCE_ROOT"/*; do
        printf '%s  %s\n' "$(sha256sum "$source" | awk '{print $1}')" "$(basename "$source")"
    done
    echo
    echo "Target LLVM IR SHA-256:"
    for workload in "${WORKLOADS[@]}"; do
        for candidate in rv32im rv64im; do
            printf '%s  %s/%s\n' \
                "$(sed -n 's/^canonical_ir_sha256=//p' "$ARTIFACTS/$workload/$candidate.manifest")" \
                "$workload" "$candidate"
        done
    done
    echo
    printf '%s\n' "$benchmark_output"
} > "$TEMPORARY"

mv "$TEMPORARY" "$SNAPSHOT"
completed=1
printf '%s\n' "$benchmark_output"
