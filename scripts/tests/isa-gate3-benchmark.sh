#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
ARTIFACTS="$(mktemp -d)"
REPRO_ARTIFACTS="$(mktemp -d)"
trap 'rm -rf "$ARTIFACTS" "$REPRO_ARTIFACTS"' EXIT HUP INT TERM

export K16_LLVM_BIN_DIR="${K16_LLVM_BIN_DIR:-$ROOT/.toolchain/build/llvm/k16-min/bin}"
if "$ROOT/scripts/compile-isa-gate3-corpus.sh" "$ARTIFACTS/invalid" 0 >/dev/null 2>&1; then
    echo "compiler accepted a zero validation-iteration count" >&2
    exit 1
fi
"$ROOT/scripts/compile-isa-gate3-corpus.sh" "$ARTIFACTS" 10
"$ROOT/scripts/compile-isa-gate3-corpus.sh" "$REPRO_ARTIFACTS" 10
if "$ROOT/host/k16-vm/target/debug/k16_f32r32_assemble" >/dev/null 2>&1; then
    echo "K16-F32R32 assembler accepted missing arguments" >&2
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
    repro_directory="$REPRO_ARTIFACTS/$workload"
    rv_manifest="$directory/rv32im.manifest"
    rv_image="$directory/rv32im.bin"
    candidate_manifest="$directory/k16-f32r32-lr.manifest"
    candidate_image="$directory/k16-f32r32-lr.bin"

    for path in "$directory/canonical.ll" "$directory/k16-f32r32-lr.s" \
        "$rv_image" "$rv_manifest" "$candidate_image" "$candidate_manifest"; do
        test -s "$path"
    done
    cmp "$directory/canonical.ll" "$repro_directory/canonical.ll"
    cmp "$rv_image" "$repro_directory/rv32im.bin"
    cmp "$candidate_image" "$repro_directory/k16-f32r32-lr.bin"

    test "$(manifest_value schema "$rv_manifest")" = 1
    test "$(manifest_value candidate "$rv_manifest")" = rv32im
    test "$(manifest_value workload "$rv_manifest")" = "$workload"
    test "$(manifest_value validation_iterations "$rv_manifest")" = 10
    test -n "$(manifest_value expected_checksum "$rv_manifest")"
    test "$(manifest_value schema "$candidate_manifest")" = 1
    test "$(manifest_value candidate "$candidate_manifest")" = k16-f32r32-lr
    test "$(manifest_value workload "$candidate_manifest")" = "$workload"
    test "$(manifest_value validation_iterations "$candidate_manifest")" = 10
    test "$(manifest_value expected_checksum "$candidate_manifest")" = \
        "$(manifest_value expected_checksum "$rv_manifest")"
    test "$(manifest_value source_sha256 "$candidate_manifest")" = \
        "$(manifest_value source_sha256 "$rv_manifest")"
    test "$(manifest_value canonical_ir_sha256 "$candidate_manifest")" = \
        "$(manifest_value canonical_ir_sha256 "$rv_manifest")"

    for image in "$rv_image" "$candidate_image"; do
        image_bytes="$(wc -c < "$image")"
        test "$image_bytes" -gt 0
        test $((image_bytes % 4)) -eq 0
    done
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

echo "ISA Gate 3 compilation contract passed"
