#!/usr/bin/env sh
set -eu

ROOT="$(git rev-parse --show-toplevel)"
TEMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEMP_ROOT"' EXIT HUP INT TERM

mkdir -p "$TEMP_ROOT/host/k16-vm" "$TEMP_ROOT/scripts" "$TEMP_ROOT/docs/benchmarks"
cp "$ROOT/host/k16-vm/Cargo.toml" "$ROOT/host/k16-vm/Cargo.lock" "$TEMP_ROOT/host/k16-vm/"
cp -R "$ROOT/host/k16-vm/src" "$ROOT/host/k16-vm/examples" "$TEMP_ROOT/host/k16-vm/"
cp "$ROOT/scripts/record-isa-gate1-benchmark.sh" "$TEMP_ROOT/scripts/"
git -C "$TEMP_ROOT" init -q
git -C "$TEMP_ROOT" add .
git -C "$TEMP_ROOT" -c user.name=test -c user.email=test@example.invalid commit -qm baseline

"$TEMP_ROOT/scripts/record-isa-gate1-benchmark.sh" 10 3 >/dev/null
SNAPSHOT="$TEMP_ROOT/docs/benchmarks/isa-gate1-current.txt"

for expected in \
    'ISA Gate 1 benchmark current snapshot' \
    'Issue: #479' \
    'Commit:' \
    'Host:' \
    'CPU:' \
    'Rust:' \
    'Candidate dependency: rvsim 0.2.2 default-features=false' \
    'workload.candidate.iterations.checksum' \
    'Gate 1 recommendation'; do
    grep -q "$expected" "$SNAPSHOT"
done

echo "ISA Gate 1 recorder contract passed"
