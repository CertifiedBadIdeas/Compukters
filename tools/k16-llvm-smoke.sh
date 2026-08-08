#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LLVM_BIN_DIR="${K16_LLVM_BIN_DIR:-$ROOT/.toolchain/build/llvm/k16-min/bin}"
LLC="$LLVM_BIN_DIR/llc"
LLVM_READOBJ="$LLVM_BIN_DIR/llvm-readobj"
K16_CARGO_MANIFEST="$ROOT/host/k16-tools/Cargo.toml"
K16_HOST_CARGO_TARGET_DIR="${K16_HOST_CARGO_TARGET_DIR:-${CARGO_TARGET_DIR:-$ROOT/.toolchain/build/cargo/k16-tools}}"
export CARGO_TARGET_DIR="$K16_HOST_CARGO_TARGET_DIR"

require_file() {
    local path="$1"
    if [[ ! -x "$path" ]]; then
        echo "required executable is missing: $path" >&2
        exit 1
    fi
}

require_contains() {
    local path="$1"
    local expected="$2"
    if ! grep -Fq "$expected" "$path"; then
        echo "expected '$expected' in $path" >&2
        echo "----- $path -----" >&2
        sed -n '1,160p' "$path" >&2
        exit 1
    fi
}

run_k16() {
    cargo run --quiet --manifest-path "$K16_CARGO_MANIFEST" --bin k16 -- "$@"
}

require_file "$LLC"
require_file "$LLVM_READOBJ"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

cat > "$WORK_DIR/add.ll" <<'IR'
target triple = "k16"

define i32 @add(i32 %a, i32 %b) {
entry:
  %sum = add i32 %a, %b
  ret i32 %sum
}
IR

cat > "$WORK_DIR/main.ll" <<'IR'
target triple = "k16"

define i32 @main() {
entry:
  ret i32 42
}
IR

cat > "$WORK_DIR/main-calls-helper.ll" <<'IR'
target triple = "k16"

declare i32 @helper(i32)

define i32 @main() {
entry:
  %value = call i32 @helper(i32 40)
  %result = add i32 %value, 2
  ret i32 %result
}
IR

cat > "$WORK_DIR/helper.ll" <<'IR'
target triple = "k16"

define i32 @helper(i32 %value) {
entry:
  ret i32 %value
}
IR

cat > "$WORK_DIR/stack-local-main.ll" <<'IR'
target triple = "k16"

define i32 @main() {
entry:
  %slot = alloca i32
  store volatile i32 42, ptr %slot
  %loaded = load volatile i32, ptr %slot
  ret i32 %loaded
}
IR

cat > "$WORK_DIR/i64-return.ll" <<'IR'
target triple = "k16"

define i64 @wide_return() {
entry:
  ret i64 42
}
IR

cat > "$WORK_DIR/varargs.ll" <<'IR'
target triple = "k16"

declare void @llvm.va_start(ptr)
declare void @llvm.va_end(ptr)

define i32 @sum(i32 %fixed, ...) {
entry:
  %args = alloca ptr
  call void @llvm.va_start(ptr %args)
  %first = va_arg ptr %args, i32
  %second = va_arg ptr %args, i32
  call void @llvm.va_end(ptr %args)
  %partial = add i32 %fixed, %first
  %result = add i32 %partial, %second
  ret i32 %result
}

define i32 @main() {
entry:
  %result = call i32 (i32, ...) @sum(i32 10, i32 11, i32 21)
  ret i32 %result
}
IR

cat > "$WORK_DIR/four-args.ll" <<'IR'
target triple = "k16"

define i32 @many(i32 %a, i32 %b, i32 %c, i32 %d) {
entry:
  %ab = add i32 %a, %b
  %cd = add i32 %c, %d
  %sum = add i32 %ab, %cd
  ret i32 %sum
}
IR

cat > "$WORK_DIR/indirect-call.ll" <<'IR'
target triple = "k16"

define i32 @call_ptr(ptr %callee) {
entry:
  %value = call i32 %callee()
  ret i32 %value
}
IR

"$LLC" --version > "$WORK_DIR/llc-version.txt"
require_contains "$WORK_DIR/llc-version.txt" "K16 32-bit"

"$LLC" -mtriple=k16 -filetype=asm "$WORK_DIR/add.ll" -o "$WORK_DIR/add.s"
require_contains "$WORK_DIR/add.s" "add r0, r1, r2"
require_contains "$WORK_DIR/add.s" "ret"

"$LLC" -mtriple=k16 -filetype=obj "$WORK_DIR/main.ll" -o "$WORK_DIR/main.o"
"$LLVM_READOBJ" -h -S -s "$WORK_DIR/main.o" > "$WORK_DIR/main-object.txt"
require_contains "$WORK_DIR/main-object.txt" "Machine: 0x5258"
require_contains "$WORK_DIR/main-object.txt" "Name: .text.k16"
require_contains "$WORK_DIR/main-object.txt" "Name: main"

run_k16 runtime k16-startup -o "$WORK_DIR/startup.o"
run_k16 link --target program "$WORK_DIR/startup.o" "$WORK_DIR/main.o" -o "$WORK_DIR/main.kx"
run_k16 inspect "$WORK_DIR/main.kx" > "$WORK_DIR/main-kx.txt"
require_contains "$WORK_DIR/main-kx.txt" "kind=K16E"
require_contains "$WORK_DIR/main-kx.txt" "K16E abi=program entry_pc=0x00015000 load_addr=0x00015000"

run_k16 disasm --target program --start 0x15000 --count 96 "$WORK_DIR/main.kx" > "$WORK_DIR/main-kx.disasm"
require_contains "$WORK_DIR/main-kx.disasm" "call r14"
require_contains "$WORK_DIR/main-kx.disasm" "const32 r0, 0x0000002a"
require_contains "$WORK_DIR/main-kx.disasm" "ret"

"$LLC" -mtriple=k16 -filetype=obj "$WORK_DIR/main-calls-helper.ll" -o "$WORK_DIR/main-calls-helper.o"
"$LLC" -mtriple=k16 -filetype=obj "$WORK_DIR/helper.ll" -o "$WORK_DIR/helper.o"
"$LLVM_READOBJ" -r -s "$WORK_DIR/main-calls-helper.o" > "$WORK_DIR/main-calls-helper-object.txt"
require_contains "$WORK_DIR/main-calls-helper-object.txt" "R_K16_CALL32 helper"
require_contains "$WORK_DIR/main-calls-helper-object.txt" "Name: helper"

run_k16 link --target program "$WORK_DIR/startup.o" "$WORK_DIR/main-calls-helper.o" "$WORK_DIR/helper.o" -o "$WORK_DIR/call-helper.kx"
run_k16 disasm --target program --start 0x15000 --count 128 "$WORK_DIR/call-helper.kx" > "$WORK_DIR/call-helper-kx.disasm"
require_contains "$WORK_DIR/call-helper-kx.disasm" "const32 r1, 0x00000028"
require_contains "$WORK_DIR/call-helper-kx.disasm" "call r14"
require_contains "$WORK_DIR/call-helper-kx.disasm" "addi r0, r0, 2"
require_contains "$WORK_DIR/call-helper-kx.disasm" "addi r0, r1, 0"

"$LLC" -mtriple=k16 -filetype=obj "$WORK_DIR/stack-local-main.ll" -o "$WORK_DIR/stack-local-main.o"
run_k16 link --target program "$WORK_DIR/startup.o" "$WORK_DIR/stack-local-main.o" -o "$WORK_DIR/stack-local-main.kx"
run_k16 disasm --target program --start 0x15000 --count 96 "$WORK_DIR/stack-local-main.kx" > "$WORK_DIR/stack-local-main-kx.disasm"
require_contains "$WORK_DIR/stack-local-main-kx.disasm" "addi r15, r15, -8"
require_contains "$WORK_DIR/stack-local-main-kx.disasm" "store32 [r15 + 4], r0"
require_contains "$WORK_DIR/stack-local-main-kx.disasm" "load32 r0, [r15 + 4]"
require_contains "$WORK_DIR/stack-local-main-kx.disasm" "addi r15, r15, 8"

"$LLC" -mtriple=k16 -filetype=asm "$WORK_DIR/four-args.ll" -o "$WORK_DIR/four-args.s"
require_contains "$WORK_DIR/four-args.s" "load32 r0, [r15 + 4]"
require_contains "$WORK_DIR/four-args.s" "add r0, r3, r0"

"$LLC" -mtriple=k16 -filetype=asm "$WORK_DIR/indirect-call.ll" -o "$WORK_DIR/indirect-call.s"
require_contains "$WORK_DIR/indirect-call.s" "call r1"

"$LLC" -mtriple=k16 -filetype=asm "$WORK_DIR/i64-return.ll" -o "$WORK_DIR/i64-return.s"
require_contains "$WORK_DIR/i64-return.s" "const32 r0, 42"
require_contains "$WORK_DIR/i64-return.s" "const32 r1, 0"

"$LLC" -mtriple=k16 -filetype=obj "$WORK_DIR/varargs.ll" -o "$WORK_DIR/varargs.o"
run_k16 link --target program "$WORK_DIR/startup.o" "$WORK_DIR/varargs.o" -o "$WORK_DIR/varargs.kx"
run_k16 run "$WORK_DIR/varargs.kx" > "$WORK_DIR/varargs-run.txt"
require_contains "$WORK_DIR/varargs-run.txt" "signal=halt exit_status=42"

echo "direct LLVM call relocation checks passed"
echo "stack-local LLVM lowering checks passed"
echo "LLVM variadic execution checks passed"
echo "K16 LLVM smoke passed"
