#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
ARTIFACTS="$(mktemp -d)"
REPRO_ARTIFACTS="$(mktemp -d)"
trap 'rm -rf "$ARTIFACTS" "$REPRO_ARTIFACTS"' EXIT HUP INT TERM

if "$ROOT/scripts/compile-riscv-xlen-gate-corpus.sh" "$ARTIFACTS/invalid" 0 >/dev/null 2>&1; then
    echo "compiler accepted a zero validation-iteration count" >&2
    exit 1
fi
"$ROOT/scripts/compile-riscv-xlen-gate-corpus.sh" "$ARTIFACTS" 10
"$ROOT/scripts/compile-riscv-xlen-gate-corpus.sh" "$REPRO_ARTIFACTS" 10

manifest_value() {
    local key="$1"
    local manifest="$2"
    sed -n "s/^${key}=//p" "$manifest"
}

workloads=(compute32 branch-mix call-stack memory-sequential memory-random copy-checksum)
for workload in "${workloads[@]}"; do
    directory="$ARTIFACTS/$workload"
    repro_directory="$REPRO_ARTIFACTS/$workload"
    rv32_manifest="$directory/rv32im.manifest"
    rv64_manifest="$directory/rv64im.manifest"

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
        test "$(manifest_value schema "$manifest")" = 1
        test "$(manifest_value candidate "$manifest")" = "$candidate"
        test "$(manifest_value workload "$manifest")" = "$workload"
        test "$(manifest_value validation_iterations "$manifest")" = 10
        test -n "$(manifest_value expected_checksum "$manifest")"
        test $(( $(wc -c < "$image") % 4 )) -eq 0
        test -z "$(llvm-nm --undefined-only "$directory/$candidate.elf")"
        if llvm-readobj --relocations "$directory/$candidate.elf" | grep -Eq '0x[0-9A-Fa-f]+ R_'; then
            echo "$candidate artifact retained relocations for $workload" >&2
            exit 1
        fi
    done

    test "$(manifest_value source_sha256 "$rv32_manifest")" = \
        "$(manifest_value source_sha256 "$rv64_manifest")"
    test "$(manifest_value expected_checksum "$rv32_manifest")" = \
        "$(manifest_value expected_checksum "$rv64_manifest")"
done

echo "RISC-V XLEN compilation contract passed"
