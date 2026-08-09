#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
ARTIFACTS="$(mktemp -d)"
REPRO_ARTIFACTS="$(mktemp -d)"
trap 'rm -rf "$ARTIFACTS" "$REPRO_ARTIFACTS"' EXIT HUP INT TERM

if "$ROOT/scripts/compile-riscv-xlen-u64-corpus.sh" "$ARTIFACTS/invalid" 0 >/dev/null 2>&1; then
    echo "u64 compiler accepted a zero validation-iteration count" >&2
    exit 1
fi
"$ROOT/scripts/compile-riscv-xlen-u64-corpus.sh" "$ARTIFACTS" 10
"$ROOT/scripts/compile-riscv-xlen-u64-corpus.sh" "$REPRO_ARTIFACTS" 10

manifest_value() {
    local key="$1"
    local manifest="$2"
    sed -n "s/^${key}=//p" "$manifest"
}

workloads=(u64-mix fixed64-geometry u64-memory)
for workload in "${workloads[@]}"; do
    directory="$ARTIFACTS/$workload"
    repro_directory="$REPRO_ARTIFACTS/$workload"
    for candidate in rv32im rv64im; do
        manifest="$directory/$candidate.manifest"
        image="$directory/$candidate.bin"
        test -s "$directory/$candidate.ll"
        test -s "$directory/$candidate.o"
        test -s "$directory/$candidate.elf"
        test -s "$image"
        test -s "$manifest"
        cmp "$directory/$candidate.ll" "$repro_directory/$candidate.ll"
        cmp "$image" "$repro_directory/$candidate.bin"
        test "$(manifest_value candidate "$manifest")" = "$candidate"
        test "$(manifest_value workload "$manifest")" = "$workload"
        test "$(manifest_value validation_iterations "$manifest")" = 10
        test -z "$(llvm-nm --undefined-only "$directory/$candidate.elf")"
        if llvm-readobj --sections "$directory/$candidate.elf" | grep -Eq 'Name: \.((s)?rodata|data|bss)'; then
            echo "$candidate artifact retained a non-text allocated payload for $workload" >&2
            exit 1
        fi
        if llvm-readobj --relocations "$directory/$candidate.elf" | grep -Eq '0x[0-9A-Fa-f]+ R_'; then
            echo "$candidate artifact retained relocations for $workload" >&2
            exit 1
        fi
    done
    test "$(manifest_value source_sha256 "$directory/rv32im.manifest")" = \
        "$(manifest_value source_sha256 "$directory/rv64im.manifest")"
    test "$(manifest_value expected_checksum "$directory/rv32im.manifest")" = \
        "$(manifest_value expected_checksum "$directory/rv64im.manifest")"
done

echo "RISC-V XLEN u64 compilation contract passed"
