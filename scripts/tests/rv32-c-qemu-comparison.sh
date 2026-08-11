#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
BUILD_DIR="${RV32_C_COMPARISON_BUILD_DIR:-$ROOT/host/compukter-vm/target/rv32-c-comparison}"
: "${RV32_C_QEMU:=qemu-system-riscv32}"
: "${RV32_C_OBJDUMP:=llvm-objdump}"

for tool in "$RV32_C_QEMU" "$RV32_C_OBJDUMP" cargo awk grep tee; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "required focused C/QEMU comparison tool is unavailable: $tool" >&2
        exit 2
    fi
done

mkdir -p "$BUILD_DIR"
bash "$ROOT/scripts/compile-rv32-c-comparison.sh" "$BUILD_DIR"

native_output="$("$BUILD_DIR/native-kernel" 1000 0x12345678 1)"
if [[ "$native_output" != $'CK_RESULT\tee053d58' ]]; then
    echo "native C oracle mismatch: $native_output" >&2
    exit 1
fi

qemu_output="$("$RV32_C_QEMU" -M virt -bios none -accel tcg -nographic -monitor none \
    -kernel "$BUILD_DIR/qemu.elf" | tr -d '\r')"
if [[ "$qemu_output" != $'CK_RESULT\tee053d58' ]]; then
    echo "QEMU C oracle mismatch: $qemu_output" >&2
    exit 1
fi

RV32_C_PRODUCT_ELF="$BUILD_DIR/product.elf" \
    cargo test --manifest-path "$ROOT/host/compukter-vm/Cargo.toml" \
    --test rv32_c_comparison_contract \
    product_c_artifact_matches_the_fixed_native_and_qemu_oracle \
    --locked --offline -- --ignored --exact

cargo run --manifest-path "$ROOT/host/compukter-vm/Cargo.toml" --release \
    --example rv32_c_comparison --locked --offline -- "$BUILD_DIR" 21 \
    | tee "$BUILD_DIR/report.tsv"

awk -F '\t' '
    $1 ~ /^(native-clang|qemu-rv32-tcg|rv32-cached|rv32-predecoded|rv32-block-cached)$/ && $6 == "ee053d58" { count++ }
    END { exit count == 5 ? 0 : 1 }
' "$BUILD_DIR/report.tsv"

startup_ns="$(awk -F '\t' '$1 == "qemu_startup_median_ns" { print $2 }' "$BUILD_DIR/report.tsv")"
target_ns="$(awk -F '\t' '$1 == "qemu_target_ns" { print $2 }' "$BUILD_DIR/report.tsv")"
if [[ -z "$startup_ns" || -z "$target_ns" || "$target_ns" -lt 250000000 || "$target_ns" -lt $((startup_ns * 50)) ]]; then
    echo "QEMU calibration did not bound startup contamination" >&2
    exit 1
fi

qemu_batch="$(awk -F '\t' '$1 == "qemu-rv32-tcg" { print $5 }' "$BUILD_DIR/report.tsv")"
cached_batch="$(awk -F '\t' '$1 == "rv32-cached" { print $5 }' "$BUILD_DIR/report.tsv")"
predecoded_batch="$(awk -F '\t' '$1 == "rv32-predecoded" { print $5 }' "$BUILD_DIR/report.tsv")"
block_cached_batch="$(awk -F '\t' '$1 == "rv32-block-cached" { print $5 }' "$BUILD_DIR/report.tsv")"

"$RV32_C_OBJDUMP" -d "$BUILD_DIR/qemu-batch-$qemu_batch.elf" \
    >"$BUILD_DIR/qemu-calibrated-disassembly.txt"
"$RV32_C_OBJDUMP" -d "$BUILD_DIR/product-batch-$cached_batch.elf" \
    >"$BUILD_DIR/product-cached-calibrated-disassembly.txt"
"$RV32_C_OBJDUMP" -d "$BUILD_DIR/product-batch-$predecoded_batch.elf" \
    >"$BUILD_DIR/product-predecoded-calibrated-disassembly.txt"
"$RV32_C_OBJDUMP" -d "$BUILD_DIR/product-batch-$block_cached_batch.elf" \
    >"$BUILD_DIR/product-block-cached-calibrated-disassembly.txt"

echo "Focused RV32 C/QEMU comparison passed; artifacts: $BUILD_DIR"
