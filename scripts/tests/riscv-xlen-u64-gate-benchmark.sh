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
    test -x "$directory/native-c-runner"
    native_output="$("$directory/native-c-runner" 10 3)"
    test "$(printf '%s\n' "$native_output" | sed -n 's/^checksum=//p')" = \
        "$(manifest_value expected_checksum "$directory/rv32im.manifest")"
    test -n "$(printf '%s\n' "$native_output" | sed -n 's/^warm_median_ns=//p')"
    test -n "$(printf '%s\n' "$native_output" | sed -n 's/^warm_p95_ns=//p')"
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

benchmark_output="$(
    cargo run --quiet --locked --offline --release \
        --manifest-path "$ROOT/host/k16-vm/Cargo.toml" \
        --example riscv_xlen_benchmarks -- "$ARTIFACTS" 10 3 u64
)"
timing_rows="$(printf '%s\n' "$benchmark_output" | awk -F '\t' '$2 == "rv32im" || $2 == "rv64im" || $2 == "native-c" { count += 1; if ($16 != 0 || $17 != 0) bad = 1 } END { if (bad) exit 1; print count + 0 }')"
test "$timing_rows" -eq 9
printf '%s\n' "$benchmark_output" | grep -q '^u64-mix[[:space:]]'
printf '%s\n' "$benchmark_output" | grep -q '^fixed64-geometry[[:space:]]'
printf '%s\n' "$benchmark_output" | grep -q '^u64-memory[[:space:]]'
printf '%s\n' "$benchmark_output" | grep -q $'\tnative-c\t'
printf '%s\n' "$benchmark_output" | grep -q '^rv64_to_rv32_warm_geomean[[:space:]]'
if printf '%s\n' "$benchmark_output" | grep -q '^decision[[:space:]]'; then
    echo "RISC-V XLEN u64 benchmark produced an architecture decision" >&2
    exit 1
fi

echo "RISC-V XLEN u64 compilation contract passed"
