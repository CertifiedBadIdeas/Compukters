#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
ARTIFACTS="$(mktemp -d)"
REPRO_ARTIFACTS="$(mktemp -d)"
trap 'rm -rf "$ARTIFACTS" "$REPRO_ARTIFACTS"' EXIT HUP INT TERM

export K16_LLVM_BIN_DIR="${K16_LLVM_BIN_DIR:-$ROOT/.toolchain/build/llvm/k16-min/bin}"
if "$ROOT/scripts/compile-isa-gate2-corpus.sh" "$ARTIFACTS/invalid" 0 >/dev/null 2>&1; then
    echo "compiler accepted a zero validation-iteration count" >&2
    exit 1
fi
"$ROOT/scripts/compile-isa-gate2-corpus.sh" "$ARTIFACTS" 10
"$ROOT/scripts/compile-isa-gate2-corpus.sh" "$REPRO_ARTIFACTS" 10
if "$ROOT/host/k16-vm/target/debug/k16_f32_assemble" >/dev/null 2>&1; then
    echo "K16-F32 assembler accepted missing arguments" >&2
    exit 1
fi

manifest_value() {
    local key="$1"
    local manifest="$2"
    sed -n "s/^${key}=//p" "$manifest"
}

workloads=(compute32 branch-mix call-stack memory-sequential memory-random copy-checksum)
for workload in "${workloads[@]}"; do
    directory="$ARTIFACTS/$workload"
    manifest="$directory/rv32im.manifest"
    image="$directory/rv32im.bin"
    k16_manifest="$directory/k16-f32.manifest"
    k16_image="$directory/k16-f32.bin"

    test -s "$directory/canonical.ll"
    test -s "$directory/k16.s"
    test -s "$image"
    test -s "$manifest"
    test -s "$k16_image"
    test -s "$k16_manifest"
    cmp "$directory/canonical.ll" "$REPRO_ARTIFACTS/$workload/canonical.ll"
    cmp "$image" "$REPRO_ARTIFACTS/$workload/rv32im.bin"
    cmp "$k16_image" "$REPRO_ARTIFACTS/$workload/k16-f32.bin"
    test "$(manifest_value schema "$manifest")" = 1
    test "$(manifest_value candidate "$manifest")" = rv32im
    test "$(manifest_value workload "$manifest")" = "$workload"
    test "$(manifest_value validation_iterations "$manifest")" = 10
    test -n "$(manifest_value expected_checksum "$manifest")"
    test "$(manifest_value schema "$k16_manifest")" = 1
    test "$(manifest_value candidate "$k16_manifest")" = k16-f32
    test "$(manifest_value workload "$k16_manifest")" = "$workload"
    test "$(manifest_value validation_iterations "$k16_manifest")" = 10
    test "$(manifest_value expected_checksum "$k16_manifest")" = \
        "$(manifest_value expected_checksum "$manifest")"
    test "$(manifest_value canonical_ir_sha256 "$k16_manifest")" = \
        "$(manifest_value canonical_ir_sha256 "$manifest")"

    image_bytes="$(wc -c < "$image")"
    test "$image_bytes" -gt 0
    test $((image_bytes % 4)) -eq 0
    test $(( $(wc -c < "$k16_image") % 4 )) -eq 0

    if grep -Eq '^target (datalayout|triple) =|"target-(cpu|features)"=' "$directory/canonical.ll"; then
        echo "canonical IR retained target-specific metadata for $workload" >&2
        exit 1
    fi

    test -z "$(llvm-nm --undefined-only "$directory/rv32im.elf")"
    if llvm-readobj --relocations "$directory/rv32im.elf" | grep -Eq '0x[0-9A-Fa-f]+ R_'; then
        echo "linked RV32IM artifact retained relocations for $workload" >&2
        exit 1
    fi
done

call_stack_signature="$(grep -E '^define .*@call_stack_inner\(' "$ARTIFACTS/call-stack/canonical.ll")"
test "$(grep -o 'i32' <<< "$call_stack_signature" | wc -l)" -eq 7

RECORDER_ROOT="$(mktemp -d)"
trap 'rm -rf "$ARTIFACTS" "$REPRO_ARTIFACTS" "$RECORDER_ROOT"' EXIT HUP INT TERM
mkdir -p "$RECORDER_ROOT/host/k16-vm" "$RECORDER_ROOT/scripts" \
    "$RECORDER_ROOT/tools/fixtures" "$RECORDER_ROOT/docs/benchmarks"
cp "$ROOT/host/k16-vm/Cargo.toml" "$ROOT/host/k16-vm/Cargo.lock" "$RECORDER_ROOT/host/k16-vm/"
cp -R "$ROOT/host/k16-vm/src" "$ROOT/host/k16-vm/examples" "$RECORDER_ROOT/host/k16-vm/"
cp "$ROOT/scripts/compile-isa-gate2-corpus.sh" \
    "$ROOT/scripts/record-isa-gate2-benchmark.sh" "$RECORDER_ROOT/scripts/"
cp -R "$ROOT/tools/fixtures/isa-gate2-c" "$RECORDER_ROOT/tools/fixtures/"
git -C "$RECORDER_ROOT" init -q
git -C "$RECORDER_ROOT" add .
git -C "$RECORDER_ROOT" -c user.name=test -c user.email=test@example.invalid commit -qm baseline

K16_LLVM_BIN_DIR="$K16_LLVM_BIN_DIR" \
    "$RECORDER_ROOT/scripts/record-isa-gate2-benchmark.sh" 10 3 >/dev/null
SNAPSHOT="$RECORDER_ROOT/docs/benchmarks/isa-gate2-current.txt"
for expected in \
    'ISA Gate 2 compiled-C benchmark current snapshot' \
    'Issue: #481' \
    'Recorded at:' \
    'Commit:' \
    'Host:' \
    'CPU:' \
    'Rust:' \
    'Cargo:' \
    'Clang:' \
    'LLVM opt:' \
    'LLVM llc:' \
    'LLD:' \
    'K16 LLVM llc:' \
    'Source corpus SHA-256:' \
    'Canonical IR SHA-256:' \
    'ISA Gate 2 decision'; do
    grep -q "$expected" "$SNAPSHOT"
done
test "$(awk -F '\t' '$2 == "k16-f32" || $2 == "rv32im" || $2 == "native-rust" { count += 1; if ($16 != 0 || $17 != 0) bad = 1 } END { if (bad) exit 1; print count + 0 }' "$SNAPSHOT")" -eq 18
grep -q '^k16-f32[[:space:]]' "$SNAPSHOT"
grep -q '^rv32im[[:space:]]' "$SNAPSHOT"
test "$(grep -Ec $'^decision\t(select-k16-f32|select-rv32im|inconclusive-expanded-run)$' "$SNAPSHOT")" -eq 1

echo "ISA Gate 2 compilation contract passed"
