#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "usage: $0 OUTPUT_ELF" >&2
    exit 2
fi

ROOT="$(git rev-parse --show-toplevel)"
SOURCE_ROOT="$ROOT/tools/fixtures/rv32-elf-boot"
OUTPUT_ELF="$1"
: "${RV32_ELF_CLANG:=clang}"
: "${RV32_ELF_LLD:=ld.lld}"
: "${RV32_ELF_READOBJ:=llvm-readobj}"
: "${RV32_ELF_NM:=llvm-nm}"

for tool in "$RV32_ELF_CLANG" "$RV32_ELF_LLD" "$RV32_ELF_READOBJ" "$RV32_ELF_NM"; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "required RV32 ELF boot tool is unavailable: $tool" >&2
        exit 2
    fi
done

BUILD_ROOT="$(mktemp -d)"
trap 'rm -rf "$BUILD_ROOT"' EXIT HUP INT TERM
mkdir -p "$(dirname "$OUTPUT_ELF")"

common_flags=(
    --target=riscv32-unknown-elf
    -march=rv32im
    -mabi=ilp32
    -ffreestanding
    -fno-builtin
    -fno-stack-protector
    -fno-pic
    -mno-relax
)

"$RV32_ELF_CLANG" "${common_flags[@]}" -c "$SOURCE_ROOT/start.S" -o "$BUILD_ROOT/start.o"
"$RV32_ELF_CLANG" "${common_flags[@]}" -std=c11 -O2 -Wall -Wextra -Werror \
    -c "$SOURCE_ROOT/boot.c" -o "$BUILD_ROOT/boot.o"
"$RV32_ELF_LLD" -m elf32lriscv --no-relax --fatal-warnings \
    -T "$SOURCE_ROOT/link.ld" "$BUILD_ROOT/start.o" "$BUILD_ROOT/boot.o" -o "$OUTPUT_ELF"

if [[ -n "$("$RV32_ELF_NM" --undefined-only "$OUTPUT_ELF")" ]]; then
    echo "RV32 ELF boot fixture contains undefined symbols" >&2
    exit 1
fi
if "$RV32_ELF_READOBJ" --relocations "$OUTPUT_ELF" | grep -Eq '0x[0-9A-Fa-f]+ R_'; then
    echo "RV32 ELF boot fixture contains unresolved relocations" >&2
    exit 1
fi

headers="$BUILD_ROOT/headers.txt"
"$RV32_ELF_READOBJ" --file-headers --program-headers "$OUTPUT_ELF" > "$headers"
grep -Eq 'Class:[[:space:]]+32-bit' "$headers"
grep -Eq 'DataEncoding:[[:space:]]+LittleEndian' "$headers"
grep -Eq 'Machine:[[:space:]]+EM_RISCV' "$headers"
grep -Eq 'Entry:[[:space:]]+0x1000' "$headers"
test "$(grep -Ec 'Type:[[:space:]]+PT_LOAD' "$headers")" -eq 3
test "$(grep -Ec 'Flags \[ \(0x5\)' "$headers")" -eq 1
test "$(grep -Ec 'Flags \[ \(0x4\)' "$headers")" -eq 1
test "$(grep -Ec 'Flags \[ \(0x6\)' "$headers")" -eq 1
