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

: "${ISA_GATE2_CLANG:=clang}"
: "${ISA_GATE2_OPT:=opt}"
: "${ISA_GATE2_LLC:=llc}"
: "${ISA_GATE2_LLD:=ld.lld}"
: "${ISA_GATE2_OBJCOPY:=llvm-objcopy}"
: "${ISA_GATE2_READOBJ:=llvm-readobj}"
: "${ISA_GATE2_NM:=llvm-nm}"
: "${K16_LLVM_BIN_DIR:?K16_LLVM_BIN_DIR must name the existing K16 LLVM bin directory}"

for tool in "$ISA_GATE2_CLANG" "$ISA_GATE2_OPT" "$ISA_GATE2_LLC" "$ISA_GATE2_LLD" \
    "$ISA_GATE2_OBJCOPY" "$ISA_GATE2_READOBJ" "$ISA_GATE2_NM" "$K16_LLVM_BIN_DIR/llc"; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "required ISA Gate 2 tool is unavailable: $tool" >&2
        exit 2
    fi
done

if [[ -z "${ISA_GATE2_CHECKSUM_BIN:-}" || -z "${ISA_GATE2_K16_ASSEMBLER_BIN:-}" ]]; then
    cargo build --quiet --locked --offline --manifest-path "$CRATE_MANIFEST" \
        --bin isa_gate2_checksum --bin k16_f32_assemble
fi
CHECKSUM_BIN="${ISA_GATE2_CHECKSUM_BIN:-$ROOT/host/k16-vm/target/debug/isa_gate2_checksum}"
K16_ASSEMBLER_BIN="${ISA_GATE2_K16_ASSEMBLER_BIN:-$ROOT/host/k16-vm/target/debug/k16_f32_assemble}"
for tool in "$CHECKSUM_BIN" "$K16_ASSEMBLER_BIN"; do
    if [[ ! -x "$tool" ]]; then
        echo "required ISA Gate 2 host tool is not executable: $tool" >&2
        exit 2
    fi
done
workloads=(compute32 branch-mix call-stack memory-sequential memory-random copy-checksum)

for workload in "${workloads[@]}"; do
    directory="$ARTIFACT_ROOT/$workload"
    source="$SOURCE_ROOT/$workload.c"
    raw_ir="$directory/raw.ll"
    neutral_ir="$directory/neutral.ll"
    canonical_ir="$directory/canonical.ll"
    k16_asm="$directory/k16.s"
    k16_image="$directory/k16-f32.bin"
    k16_manifest="$directory/k16-f32.manifest"
    rv_object="$directory/rv32im.o"
    rv_elf="$directory/rv32im.elf"
    rv_image="$directory/rv32im.bin"
    manifest="$directory/rv32im.manifest"
    mkdir -p "$directory"

    (
        cd "$SOURCE_ROOT"
        "$ISA_GATE2_CLANG" --target=i386-unknown-none-elf -std=c11 -O0 \
            -Xclang -disable-O0-optnone -ffreestanding -fno-builtin \
            -fno-stack-protector -fno-vectorize -fno-slp-vectorize \
            -S -emit-llvm "$workload.c" -o "$raw_ir"
    )
    sed -E '/^target datalayout =/d; /^target triple =/d; s/ "target-cpu"="[^"]*"//g; s/ "target-features"="[^"]*"//g' \
        "$raw_ir" > "$neutral_ir"
    "$ISA_GATE2_OPT" -passes='default<O2>' -vectorize-loops=false -vectorize-slp=false \
        -S -o "$canonical_ir" < "$neutral_ir"

    if grep -E '^declare ' "$canonical_ir" | grep -Ev '@llvm\.' >/dev/null; then
        echo "canonical IR contains a non-intrinsic declaration for $workload" >&2
        exit 1
    fi

    "$K16_LLVM_BIN_DIR/llc" -mtriple=k16 -filetype=asm "$canonical_ir" -o "$k16_asm"
    "$K16_ASSEMBLER_BIN" --base 4096 --entry kernel --input "$k16_asm" \
        --output "$k16_image" --manifest "$k16_manifest"
    "$ISA_GATE2_LLC" -mtriple=riscv32-unknown-elf -mattr=+m,-a,-c \
        -function-sections -filetype=obj "$canonical_ir" -o "$rv_object"
    "$ISA_GATE2_LLD" -m elf32lriscv -T "$SOURCE_ROOT/rv32im.ld" \
        --no-relax --fatal-warnings "$rv_object" -o "$rv_elf"

    if [[ -n "$("$ISA_GATE2_NM" --undefined-only "$rv_elf")" ]]; then
        echo "linked RV32IM artifact contains undefined symbols for $workload" >&2
        exit 1
    fi
    if "$ISA_GATE2_READOBJ" --relocations "$rv_elf" | grep -Eq '0x[0-9A-Fa-f]+ R_'; then
        echo "linked RV32IM artifact contains relocations for $workload" >&2
        exit 1
    fi

    "$ISA_GATE2_OBJCOPY" -O binary --only-section=.text "$rv_elf" "$rv_image"
    code_bytes="$(wc -c < "$rv_image")"
    if (( code_bytes == 0 || code_bytes % 4 != 0 )); then
        echo "RV32IM text size is not a positive multiple of four for $workload" >&2
        exit 1
    fi
    printf '\x73\x00\x10\x00' >> "$rv_image"

    kernel_address_hex="$("$ISA_GATE2_NM" --defined-only "$rv_elf" | awk '$3 == "kernel" { print $1 }')"
    if [[ -z "$kernel_address_hex" ]]; then
        echo "linked RV32IM artifact has no kernel symbol for $workload" >&2
        exit 1
    fi
    kernel_address=$((16#$kernel_address_hex))
    entry_offset=$((kernel_address - 0x1000))
    expected_checksum="$("$CHECKSUM_BIN" "$workload" "$VALIDATION_ITERATIONS")"
    image_bytes=$((code_bytes + 4))

    {
        echo "schema=1"
        echo "workload=$workload"
        echo "candidate=rv32im"
        echo "source_sha256=$(sha256sum "$source" | awk '{print $1}')"
        echo "canonical_ir_sha256=$(sha256sum "$canonical_ir" | awk '{print $1}')"
        echo "image_sha256=$(sha256sum "$rv_image" | awk '{print $1}')"
        echo "image_path=rv32im.bin"
        echo "image_base=4096"
        echo "entry_offset=$entry_offset"
        echo "stop_offset=$code_bytes"
        echo "code_bytes=$code_bytes"
        echo "image_bytes=$image_bytes"
        echo "instruction_count=$((code_bytes / 4))"
        echo "validation_iterations=$VALIDATION_ITERATIONS"
        echo "expected_checksum=$expected_checksum"
        echo "clang_version=$($ISA_GATE2_CLANG --version | head -n 1)"
        echo "opt_version=$($ISA_GATE2_OPT --version | sed -n '2s/^  //p')"
        echo "llc_version=$($ISA_GATE2_LLC --version | sed -n '2s/^  //p')"
    } > "$manifest"

    {
        echo "workload=$workload"
        echo "source_sha256=$(sha256sum "$source" | awk '{print $1}')"
        echo "canonical_ir_sha256=$(sha256sum "$canonical_ir" | awk '{print $1}')"
        echo "image_sha256=$(sha256sum "$k16_image" | awk '{print $1}')"
        echo "validation_iterations=$VALIDATION_ITERATIONS"
        echo "expected_checksum=$expected_checksum"
        echo "k16_llc_version=$($K16_LLVM_BIN_DIR/llc --version | sed -n '2s/^  //p')"
    } >> "$k16_manifest"
done
