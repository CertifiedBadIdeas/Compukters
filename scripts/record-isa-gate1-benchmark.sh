#!/usr/bin/env sh
set -eu

ITERATIONS="${1:-100000}"
SAMPLES="${2:-9}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ROOT="$(git -C "$SCRIPT_DIR/.." rev-parse --show-toplevel)"
SNAPSHOT="$ROOT/docs/benchmarks/isa-gate1-current.txt"
COMMAND="cargo run --locked --release --example isa_gate1_benchmarks -- $ITERATIONS $SAMPLES"

for value_name in ITERATIONS SAMPLES; do
    eval "value=\$$value_name"
    case "$value" in
        ''|*[!0-9]*|0)
            echo "$(printf '%s' "$value_name" | tr '[:upper:]' '[:lower:]') must be a positive integer" >&2
            exit 2
            ;;
    esac
done

recorded_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
commit="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || printf unknown)"
dirty="clean"
if [ -n "$(git -C "$ROOT" status --porcelain -- . ':(exclude)docs/benchmarks/isa-gate1-current.txt' 2>/dev/null || true)" ]; then
    dirty="dirty"
fi
host="$(uname -srmo)"
rustc_version="$(rustc --version)"
cargo_version="$(cargo --version)"
cpu="unknown"
if [ -r /proc/cpuinfo ]; then
    cpu="$(awk -F: '/model name/ { value=$2; sub(/^[ \t]+/, "", value); print value; exit }' /proc/cpuinfo)"
    [ -n "$cpu" ] || cpu="unknown"
fi

benchmark_output="$(
    cd "$ROOT/host/k16-vm"
    cargo run --locked --release --example isa_gate1_benchmarks -- "$ITERATIONS" "$SAMPLES"
)"
printf '%s\n' "$benchmark_output" | grep -q '^Gate 1 recommendation$'
for candidate in k16 k16-cached k16-predecoded k16-f32 rvsim-rv32im rv32im rv32im-cached rv32im-predecoded native-rust; do
    printf '%s\n' "$benchmark_output" | grep -q "^$candidate[[:space:]]"
done

mkdir -p "$ROOT/docs/benchmarks"
temporary="$(mktemp "$SNAPSHOT.tmp.XXXXXX")"
trap 'rm -f "$temporary"' EXIT HUP INT TERM
{
    echo "ISA Gate 1 benchmark current snapshot"
    echo
    echo "Issue: #479"
    echo "Recorded at: $recorded_at"
    echo "Commit: $commit ($dirty)"
    echo "Host: $host"
    echo "CPU: $cpu"
    echo "Rust: $rustc_version"
    echo "Cargo: $cargo_version"
    echo "Candidate dependency: rvsim 0.2.2 default-features=false"
    echo "Command: $COMMAND"
    echo
    printf '%s\n' "$benchmark_output"
} >"$temporary"
mv "$temporary" "$SNAPSHOT"
trap - EXIT HUP INT TERM

printf '%s\n' "$benchmark_output"
