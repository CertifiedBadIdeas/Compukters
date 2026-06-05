#!/usr/bin/env sh
set -eu

ITERATIONS="${1:-100000}"
SAMPLES="${2:-5}"
ROOT="$(git rev-parse --show-toplevel)"
SNAPSHOT="$ROOT/docs/benchmarks/k16-vm-current.txt"
COMMAND="cargo run --release --example vm_microbenchmarks -- $ITERATIONS $SAMPLES"

case "$ITERATIONS" in
    ''|*[!0-9]*)
        echo "iterations must be a positive integer" >&2
        exit 2
        ;;
esac

case "$SAMPLES" in
    ''|*[!0-9]*)
        echo "samples must be a positive integer" >&2
        exit 2
        ;;
esac

if [ "$ITERATIONS" -eq 0 ]; then
    echo "iterations must be a positive integer" >&2
    exit 2
fi

if [ "$SAMPLES" -eq 0 ]; then
    echo "samples must be a positive integer" >&2
    exit 2
fi

recorded_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
host="$(uname -srmo)"
rustc_version="$(rustc --version)"
cargo_version="$(cargo --version)"
cpu="unknown"
if [ -r /proc/cpuinfo ]; then
    cpu="$(awk -F: '/model name/ { value=$2; sub(/^[ \t]+/, "", value); print value; exit }' /proc/cpuinfo)"
    if [ -z "$cpu" ]; then
        cpu="unknown"
    fi
fi

benchmark_output="$(
    cd "$ROOT/rust/host/k16-vm"
    cargo run --release --example vm_microbenchmarks -- "$ITERATIONS" "$SAMPLES"
)"

mkdir -p "$ROOT/docs/benchmarks"
{
    echo "K16 VM benchmark current snapshot"
    echo
    echo "Recorded at: $recorded_at"
    echo "Command: $COMMAND"
    echo "Host: $host"
    echo "CPU: $cpu"
    echo "Rust: $rustc_version"
    echo "Cargo: $cargo_version"
    echo
    echo "This file is the current repo-tracked benchmark snapshot. Git history is"
    echo "the benchmark history; use git diff or git show <commit>:docs/benchmarks/k16-vm-current.txt."
    echo
    printf '%s\n' "$benchmark_output"
} >"$SNAPSHOT"

printf '%s\n' "$benchmark_output"
