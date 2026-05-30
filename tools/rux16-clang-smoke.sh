#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LLVM_BIN_DIR="${RUX16_LLVM_BIN_DIR:-$ROOT/toolchains/Compukter-Kraft-llvm/build-rux/bin}"
CLANG="$LLVM_BIN_DIR/clang"
LLVM_READOBJ="$LLVM_BIN_DIR/llvm-readobj"
RUX_CARGO_MANIFEST="$ROOT/native/rux-compiler/Cargo.toml"

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

require_clang_failure() {
    local input="$1"
    local expected="$2"
    local object="$WORK_DIR/$(basename "$input" .c).o"
    local stderr="$WORK_DIR/$(basename "$input" .c).stderr"

    if "$CLANG" --target=rux16 -ffreestanding -fno-builtin -nostdlib \
        -c "$input" -o "$object" > /dev/null 2> "$stderr"; then
        echo "expected clang to reject $input" >&2
        exit 1
    fi
    if [[ -e "$object" ]]; then
        echo "clang produced unexpected object for rejected input: $object" >&2
        exit 1
    fi
    require_contains "$stderr" "$expected"
}

run_k16() {
    cargo run --quiet --manifest-path "$RUX_CARGO_MANIFEST" --bin k16 -- "$@"
}

require_file "$CLANG"
require_file "$LLVM_READOBJ"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

cat > "$WORK_DIR/main.c" <<'C'
int main(void) {
  return 42;
}
C

cat > "$WORK_DIR/i64-return.c" <<'C'
long long wide_return(void) {
  return 42;
}
C

cat > "$WORK_DIR/varargs.c" <<'C'
int sum(int first, ...) {
  return first;
}
C

cat > "$WORK_DIR/four-args.c" <<'C'
int many(int a, int b, int c, int d) {
  return a + b + c + d;
}
C

cat > "$WORK_DIR/indirect-call.c" <<'C'
typedef int (*callback_t)(void);

int call_ptr(callback_t callback) {
  return callback();
}
C

"$CLANG" --target=rux16 -ffreestanding -fno-builtin -nostdlib \
    -c "$WORK_DIR/main.c" -o "$WORK_DIR/main.o"

"$LLVM_READOBJ" -h -S -s "$WORK_DIR/main.o" > "$WORK_DIR/main-object.txt"
require_contains "$WORK_DIR/main-object.txt" "Machine: 0x5258"
require_contains "$WORK_DIR/main-object.txt" "Name: .text.rux16"
require_contains "$WORK_DIR/main-object.txt" "Name: main"

run_k16 runtime rux16-startup -o "$WORK_DIR/startup.o"
run_k16 link --target program "$WORK_DIR/startup.o" "$WORK_DIR/main.o" -o "$WORK_DIR/main.ruxe"

run_k16 inspect "$WORK_DIR/main.ruxe" > "$WORK_DIR/main-ruxe.txt"
require_contains "$WORK_DIR/main-ruxe.txt" "kind=RUXE"
require_contains "$WORK_DIR/main-ruxe.txt" "RUXE abi=program entry_pc=0x00008000 load_addr=0x00008000"

run_k16 disasm --target program "$WORK_DIR/main.ruxe" > "$WORK_DIR/main-ruxe.disasm"
require_contains "$WORK_DIR/main-ruxe.disasm" "call r14"
require_contains "$WORK_DIR/main-ruxe.disasm" "const32 r0, 0x0000002a"
require_contains "$WORK_DIR/main-ruxe.disasm" "ret"

run_k16 run "$WORK_DIR/main.ruxe" > "$WORK_DIR/main-run.txt"
require_contains "$WORK_DIR/main-run.txt" "signal=halt debug_bytes=2a"

require_clang_failure "$WORK_DIR/i64-return.c" "Rux16 multi-value returns are not implemented"
require_clang_failure "$WORK_DIR/varargs.c" "Rux16 varargs are not implemented"
require_clang_failure "$WORK_DIR/four-args.c" "Rux16 stack arguments are not implemented"
require_clang_failure "$WORK_DIR/indirect-call.c" "Rux16 only supports direct calls"

echo "freestanding C compile checks passed"
echo "RUXE link and execution checks passed"
echo "unsupported C feature checks passed"
echo "Rux16 clang smoke passed"
