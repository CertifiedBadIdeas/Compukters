#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "usage: $0 BUILD_DIR" >&2
    exit 2
fi

ROOT="$(git rev-parse --show-toplevel)"
SOURCE_ROOT="$ROOT/tools/benchmarks/rv32-c-comparison"
BUILD_DIR="$1"
: "${RV32_C_CLANG:=clang}"
: "${RV32_C_LLD:=ld.lld}"
: "${RV32_C_READOBJ:=llvm-readobj}"
: "${RV32_C_OBJDUMP:=llvm-objdump}"
: "${RV32_C_SIZE:=llvm-size}"

for tool in "$RV32_C_CLANG" "$RV32_C_LLD" "$RV32_C_READOBJ" "$RV32_C_OBJDUMP" "$RV32_C_SIZE" sha256sum; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "required RV32 C comparison tool is unavailable: $tool" >&2
        exit 2
    fi
done

mkdir -p "$BUILD_DIR"

native_flags=(-O3 -march=native -flto -Wall -Wextra -Werror)
rv32_flags=(
    --target=riscv32-unknown-elf
    -O3
    -march=rv32im_zicsr
    -mabi=ilp32
    -ffreestanding
    -fno-builtin
    -fno-stack-protector
    -fno-pic
    -mno-relax
    -msmall-data-limit=0
    -Wall
    -Wextra
    -Werror
)

"$RV32_C_CLANG" "${native_flags[@]}" -Rpass=loop-vectorize -Rpass=slp-vectorize \
    -Rpass-missed=loop-vectorize "$SOURCE_ROOT/kernel.c" "$SOURCE_ROOT/native-wrapper.c" \
    -o "$BUILD_DIR/native-kernel" 2>"$BUILD_DIR/native-optimization-remarks.txt"

"$RV32_C_CLANG" "${rv32_flags[@]}" -c "$SOURCE_ROOT/kernel.c" -o "$BUILD_DIR/kernel-rv32.o"
"$RV32_C_CLANG" "${rv32_flags[@]}" -c "$SOURCE_ROOT/product-start.S" -o "$BUILD_DIR/product-start.o"
"$RV32_C_CLANG" "${rv32_flags[@]}" -c "$SOURCE_ROOT/product-wrapper.c" -o "$BUILD_DIR/product-wrapper.o"
"$RV32_C_CLANG" "${rv32_flags[@]}" -c "$SOURCE_ROOT/qemu-start.S" -o "$BUILD_DIR/qemu-start.o"
"$RV32_C_CLANG" "${rv32_flags[@]}" -c "$SOURCE_ROOT/qemu-wrapper.c" -o "$BUILD_DIR/qemu-wrapper.o"

"$RV32_C_LLD" -m elf32lriscv --no-relax --fatal-warnings --defsym=__ck_batch=1 \
    -T "$SOURCE_ROOT/product.ld" "$BUILD_DIR/product-start.o" \
    "$BUILD_DIR/product-wrapper.o" "$BUILD_DIR/kernel-rv32.o" -o "$BUILD_DIR/product.elf"
"$RV32_C_LLD" -m elf32lriscv --no-relax --fatal-warnings --defsym=__ck_batch=1 \
    -T "$SOURCE_ROOT/qemu.ld" "$BUILD_DIR/qemu-start.o" \
    "$BUILD_DIR/qemu-wrapper.o" "$BUILD_DIR/kernel-rv32.o" -o "$BUILD_DIR/qemu.elf"

"$RV32_C_OBJDUMP" -d "$BUILD_DIR/native-kernel" >"$BUILD_DIR/native-disassembly.txt"
"$RV32_C_OBJDUMP" -d "$BUILD_DIR/product.elf" >"$BUILD_DIR/product-disassembly.txt"
"$RV32_C_OBJDUMP" -d "$BUILD_DIR/qemu.elf" >"$BUILD_DIR/qemu-disassembly.txt"
"$RV32_C_READOBJ" --file-headers --program-headers --arch-specific \
    "$BUILD_DIR/product.elf" >"$BUILD_DIR/product-readobj.txt"
"$RV32_C_READOBJ" --file-headers --program-headers --arch-specific \
    "$BUILD_DIR/qemu.elf" >"$BUILD_DIR/qemu-readobj.txt"
"$RV32_C_READOBJ" --file-headers --arch-specific \
    "$BUILD_DIR/kernel-rv32.o" >"$BUILD_DIR/kernel-readobj.txt"

kernel_hash="$(sha256sum "$BUILD_DIR/kernel-rv32.o" | cut -d' ' -f1)"
native_hash="$(sha256sum "$BUILD_DIR/native-kernel" | cut -d' ' -f1)"
product_hash="$(sha256sum "$BUILD_DIR/product.elf" | cut -d' ' -f1)"
qemu_hash="$(sha256sum "$BUILD_DIR/qemu.elf" | cut -d' ' -f1)"
native_text_bytes="$("$RV32_C_SIZE" --format=berkeley "$BUILD_DIR/native-kernel" | tail -n 1 | awk '{print $1}')"
product_text_bytes="$("$RV32_C_SIZE" --format=berkeley "$BUILD_DIR/product.elf" | tail -n 1 | awk '{print $1}')"
qemu_text_bytes="$("$RV32_C_SIZE" --format=berkeley "$BUILD_DIR/qemu.elf" | tail -n 1 | awk '{print $1}')"

{
    echo -e "key\tvalue"
    echo -e "native-flags\t-O3 -march=native -flto"
    echo -e "rv32-flags\t-O3 -march=rv32im_zicsr -mabi=ilp32 -ffreestanding -fno-builtin"
    echo -e "kernel-object-sha256\t$kernel_hash"
    echo -e "native-sha256\t$native_hash"
    echo -e "product-sha256\t$product_hash"
    echo -e "qemu-sha256\t$qemu_hash"
    echo -e "native-text-bytes\t$native_text_bytes"
    echo -e "product-text-bytes\t$product_text_bytes"
    echo -e "qemu-text-bytes\t$qemu_text_bytes"
    echo -e "product-elf\t$BUILD_DIR/product.elf"
    echo -e "qemu-elf\t$BUILD_DIR/qemu.elf"
} >"$BUILD_DIR/manifest.tsv"

grep -Eq 'Class:[[:space:]]+32-bit' "$BUILD_DIR/product-readobj.txt"
grep -Eq 'Machine:[[:space:]]+EM_RISCV' "$BUILD_DIR/product-readobj.txt"
product_loads="$(grep -Ec 'Type:[[:space:]]+PT_LOAD' "$BUILD_DIR/product-readobj.txt")"
if [[ "$product_loads" -lt 2 || "$product_loads" -gt 3 ]]; then
    echo "product ELF must contain RX, optional R, and RW load segments" >&2
    exit 1
fi
test "$(grep -Ec 'Flags \[ \(0x5\)' "$BUILD_DIR/product-readobj.txt")" -eq 1
test "$(grep -Ec 'Flags \[ \(0x6\)' "$BUILD_DIR/product-readobj.txt")" -eq 1
if grep -Eq 'Flags \[ \(0x7\)' "$BUILD_DIR/product-readobj.txt"; then
    echo "product ELF contains a writable executable segment" >&2
    exit 1
fi
grep -Eq 'Vendor:[[:space:]]+riscv' "$BUILD_DIR/kernel-readobj.txt"
grep -Eq 'rv32.*i.*m.*zicsr' "$BUILD_DIR/kernel-readobj.txt"

echo "RV32 C comparison artifacts built in $BUILD_DIR"
