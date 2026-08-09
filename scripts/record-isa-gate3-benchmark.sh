#!/usr/bin/env bash
set -euo pipefail

ITERATIONS="${1:-100000}"
SAMPLES="${2:-9}"
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
ROOT="$(git -C "$SCRIPT_DIR/.." rev-parse --show-toplevel)"
SNAPSHOT="$ROOT/docs/benchmarks/isa-gate3-current.txt"
SOURCE_ROOT="$ROOT/tools/fixtures/isa-gate2-c"
: "${K16_LLVM_BIN_DIR:?K16_LLVM_BIN_DIR must name the existing K16 LLVM bin directory}"
: "${ISA_GATE3_CLANG:=clang}"
: "${ISA_GATE3_OPT:=opt}"
: "${ISA_GATE3_LLC:=llc}"
: "${ISA_GATE3_LLD:=ld.lld}"

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

for tool in "$ISA_GATE3_CLANG" "$ISA_GATE3_OPT" "$ISA_GATE3_LLC" "$ISA_GATE3_LLD" \
    "$K16_LLVM_BIN_DIR/llc"; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "required ISA Gate 3 tool is unavailable: $tool" >&2
        exit 2
    fi
    if ! "$tool" --version | grep -Eq '(^|[^0-9])22\.[0-9]'; then
        echo "ISA Gate 3 requires LLVM major version 22: $tool" >&2
        exit 2
    fi
done

mkdir -p "$ROOT/docs/benchmarks"
ARTIFACTS="$(mktemp -d "${TMPDIR:-/tmp}/isa-gate3-artifacts.XXXXXX")"
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
        echo "ISA Gate 3 failed; artifacts preserved at $ARTIFACTS" >&2
    fi
    exit "$status"
}
trap cleanup EXIT HUP INT TERM

recorded_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
commit="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || printf unknown)"
dirty_paths="$(git -C "$ROOT" status --porcelain -- . ':(exclude)docs/benchmarks/isa-gate3-current.txt' 2>/dev/null || true)"
if [[ -n "$dirty_paths" ]]; then
    echo "ISA Gate 3 recording requires a clean worktree except its snapshot:" >&2
    printf '%s\n' "$dirty_paths" >&2
    exit 1
fi
dirty="clean"
TEMPORARY="$(mktemp "$SNAPSHOT.tmp.XXXXXX")"
host="$(uname -srmo)"
rustc_version="$(rustc --version)"
cargo_version="$(cargo --version)"
clang_version="$($ISA_GATE3_CLANG --version | head -n 1)"
opt_version="$($ISA_GATE3_OPT --version | sed -n '2s/^  //p')"
llc_version="$($ISA_GATE3_LLC --version | sed -n '2s/^  //p')"
lld_version="$($ISA_GATE3_LLD --version | head -n 1)"
k16_llc_version="$($K16_LLVM_BIN_DIR/llc --version | sed -n '2s/^  //p')"
cpu="unknown"
if [[ -r /proc/cpuinfo ]]; then
    cpu="$(awk -F: '/model name/ { value=$2; sub(/^[ \t]+/, "", value); print value; exit }' /proc/cpuinfo)"
    [[ -n "$cpu" ]] || cpu="unknown"
fi

K16_LLVM_BIN_DIR="$K16_LLVM_BIN_DIR" \
ISA_GATE3_CLANG="$ISA_GATE3_CLANG" ISA_GATE3_OPT="$ISA_GATE3_OPT" \
ISA_GATE3_LLC="$ISA_GATE3_LLC" ISA_GATE3_LLD="$ISA_GATE3_LLD" \
    "$ROOT/scripts/compile-isa-gate3-corpus.sh" "$ARTIFACTS" "$ITERATIONS"

benchmark_output="$(
    cargo run --quiet --locked --offline --release \
        --manifest-path "$ROOT/host/k16-vm/Cargo.toml" \
        --example isa_gate3_benchmarks -- "$ARTIFACTS" "$ITERATIONS" "$SAMPLES"
)"

timing_rows="$(printf '%s\n' "$benchmark_output" | awk -F '\t' '$2 == "k16-f32r32-lr" || $2 == "rv32im" || $2 == "native-rust" { count += 1; if ($16 != 0 || $17 != 0) bad = 1 } END { if (bad) exit 1; print count + 0 }')"
if [[ "$timing_rows" -ne 18 ]]; then
    echo "ISA Gate 3 expected 18 allocation-free timing rows, got $timing_rows" >&2
    exit 1
fi
printf '%s\n' "$benchmark_output" | grep -q '^k16-f32r32-lr[[:space:]]'
printf '%s\n' "$benchmark_output" | grep -q '^rv32im[[:space:]]'
if [[ "$(printf '%s\n' "$benchmark_output" | grep -Ec $'^decision\t(select-k16-f32r32-lr|select-rv32im|inconclusive-expanded-run)$')" -ne 1 ]]; then
    echo "ISA Gate 3 benchmark did not produce exactly one valid decision" >&2
    exit 1
fi

{
    echo "ISA Gate 3 compiled-C benchmark current snapshot"
    echo
    echo "Issue: #482"
    echo "Recorded at: $recorded_at"
    echo "Commit: $commit ($dirty)"
    echo "Host: $host"
    echo "CPU: $cpu"
    echo "Rust: $rustc_version"
    echo "Cargo: $cargo_version"
    echo "Clang: $clang_version"
    echo "LLVM opt: $opt_version"
    echo "LLVM llc: $llc_version"
    echo "LLD: $lld_version"
    echo "K16 LLVM llc: $k16_llc_version"
    echo "Command: K16_LLVM_BIN_DIR=\"$K16_LLVM_BIN_DIR\" bash scripts/record-isa-gate3-benchmark.sh $ITERATIONS $SAMPLES"
    echo
    echo "Source corpus SHA-256:"
    for source in "$SOURCE_ROOT"/*; do
        printf '%s  %s\n' "$(sha256sum "$source" | awk '{print $1}')" "$(basename "$source")"
    done
    echo
    echo "Canonical IR SHA-256:"
    for workload in compute32 branch-mix call-stack memory-sequential memory-random copy-checksum; do
        printf '%s  %s\n' \
            "$(sed -n 's/^canonical_ir_sha256=//p' "$ARTIFACTS/$workload/rv32im.manifest")" \
            "$workload"
    done
    echo
    printf '%s\n' "$benchmark_output"
} > "$TEMPORARY"

mv "$TEMPORARY" "$SNAPSHOT"
completed=1
printf '%s\n' "$benchmark_output"
