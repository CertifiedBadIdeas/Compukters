#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 ARTIFACT_DIRECTORY VALIDATION_ITERATIONS" >&2
    exit 2
fi

ARTIFACT_ROOT="$1"
VALIDATION_ITERATIONS="$2"
if ! [[ "$VALIDATION_ITERATIONS" =~ ^[1-9][0-9]*$ ]] || (( VALIDATION_ITERATIONS > 4294967295 )); then
    echo "validation iterations must be an integer in 1..4294967295" >&2
    exit 2
fi
mkdir -p "$ARTIFACT_ROOT"
ARTIFACT_ROOT="$(cd "$ARTIFACT_ROOT" && pwd -P)"

ROOT="$(git rev-parse --show-toplevel)"
SOURCE_ROOT="$ROOT/tools/fixtures/isa-gate2-c"
CRATE_MANIFEST="$ROOT/host/k16-vm/Cargo.toml"

: "${RISCV_XLEN_CLANG:=clang}"
: "${RISCV_XLEN_LLD:=ld.lld}"
: "${RISCV_XLEN_OBJCOPY:=llvm-objcopy}"
: "${RISCV_XLEN_READOBJ:=llvm-readobj}"
: "${RISCV_XLEN_NM:=llvm-nm}"

for tool in "$RISCV_XLEN_CLANG" "$RISCV_XLEN_LLD" "$RISCV_XLEN_OBJCOPY" \
    "$RISCV_XLEN_READOBJ" "$RISCV_XLEN_NM"; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "required RISC-V XLEN tool is unavailable: $tool" >&2
        exit 2
    fi
done

if [[ -z "${RISCV_XLEN_CHECKSUM_BIN:-}" ]]; then
    cargo build --quiet --locked --offline --manifest-path "$CRATE_MANIFEST" \
        --bin isa_gate2_checksum
fi
CHECKSUM_BIN="${RISCV_XLEN_CHECKSUM_BIN:-$ROOT/host/k16-vm/target/debug/isa_gate2_checksum}"
if [[ ! -x "$CHECKSUM_BIN" ]]; then
    echo "RISC-V XLEN checksum tool is not executable: $CHECKSUM_BIN" >&2
    exit 2
fi

compile_candidate() {
    local workload="$1"
    local directory="$2"
    local candidate="$3"
    local target="$4"
    local march="$5"
    local abi="$6"
    local linker_emulation="$7"
    local source="$SOURCE_ROOT/$workload.c"
    local llvm_ir="$directory/$candidate.ll"
    local object="$directory/$candidate.o"
    local elf="$directory/$candidate.elf"
    local image="$directory/$candidate.bin"
    local manifest="$directory/$candidate.manifest"

    (
        cd "$SOURCE_ROOT"
        "$RISCV_XLEN_CLANG" --target="$target" -march="$march" -mabi="$abi" \
            -std=c11 -O2 -ffreestanding -fno-builtin -fno-stack-protector \
            -fomit-frame-pointer -fno-vectorize -fno-slp-vectorize \
            -ffunction-sections -S -emit-llvm "$workload.c" -o "$llvm_ir"
        "$RISCV_XLEN_CLANG" --target="$target" -march="$march" -mabi="$abi" \
            -std=c11 -O2 -ffreestanding -fno-builtin -fno-stack-protector \
            -fomit-frame-pointer -fno-vectorize -fno-slp-vectorize \
            -ffunction-sections -c "$workload.c" -o "$object"
    )
    "$RISCV_XLEN_LLD" -m "$linker_emulation" -T "$SOURCE_ROOT/rv32im.ld" \
        --fatal-warnings "$object" -o "$elf"
    if [[ -n "$("$RISCV_XLEN_NM" --undefined-only "$elf")" ]]; then
        echo "linked $candidate artifact contains undefined symbols for $workload" >&2
        exit 1
    fi
    if "$RISCV_XLEN_READOBJ" --relocations "$elf" | grep -Eq '0x[0-9A-Fa-f]+ R_'; then
        echo "linked $candidate artifact contains relocations for $workload" >&2
        exit 1
    fi

    "$RISCV_XLEN_OBJCOPY" -O binary --only-section=.text "$elf" "$image"
    local code_bytes
    code_bytes="$(wc -c < "$image")"
    if (( code_bytes == 0 || code_bytes % 4 != 0 )); then
        echo "$candidate text size is not a positive multiple of four for $workload" >&2
        exit 1
    fi
    printf '\x73\x00\x10\x00' >> "$image"

    local kernel_address_hex
    kernel_address_hex="$("$RISCV_XLEN_NM" --defined-only "$elf" | awk '$3 == "kernel" { print $1 }')"
    if [[ -z "$kernel_address_hex" ]]; then
        echo "linked $candidate artifact has no kernel symbol for $workload" >&2
        exit 1
    fi
    local kernel_address=$((16#$kernel_address_hex))
    local entry_offset=$((kernel_address - 0x1000))
    local expected_checksum
    expected_checksum="$("$CHECKSUM_BIN" "$workload" "$VALIDATION_ITERATIONS")"
    local image_bytes=$((code_bytes + 4))

    {
        echo "schema=1"
        echo "workload=$workload"
        echo "candidate=$candidate"
        echo "source_sha256=$(sha256sum "$source" | awk '{print $1}')"
        echo "canonical_ir_sha256=$(sha256sum "$llvm_ir" | awk '{print $1}')"
        echo "image_sha256=$(sha256sum "$image" | awk '{print $1}')"
        echo "image_path=$candidate.bin"
        echo "image_base=4096"
        echo "entry_offset=$entry_offset"
        echo "stop_offset=$code_bytes"
        echo "code_bytes=$code_bytes"
        echo "image_bytes=$image_bytes"
        echo "instruction_count=$((code_bytes / 4))"
        echo "validation_iterations=$VALIDATION_ITERATIONS"
        echo "expected_checksum=$expected_checksum"
        echo "clang_version=$($RISCV_XLEN_CLANG --version | head -n 1)"
    } > "$manifest"
}

workloads=(compute32 branch-mix call-stack memory-sequential memory-random copy-checksum)
for workload in "${workloads[@]}"; do
    directory="$ARTIFACT_ROOT/$workload"
    mkdir -p "$directory"
    compile_candidate "$workload" "$directory" rv32im riscv32-unknown-elf rv32im ilp32 elf32lriscv
    compile_candidate "$workload" "$directory" rv64im riscv64-unknown-elf rv64im lp64 elf64lriscv
done
