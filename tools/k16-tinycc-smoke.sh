#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIXTURES="$ROOT/tools/fixtures/k16-tinycc"
TINYCC="${K16_TINYCC:?K16_TINYCC must point to the pinned tcc-k16 executable}"
CLANG="${K16_CLANG:?K16_CLANG must point to the K16 Clang executable}"
LLVM_READOBJ="${K16_LLVM_READOBJ:?K16_LLVM_READOBJ must point to llvm-readobj}"
K16="${K16_TOOL:?K16_TOOL must point to the K16 CLI executable}"
: "${K16_RUSTC:?K16_RUSTC must point to the custom K16 rustc}"
: "${K16_LLVM_BIN_DIR:?K16_LLVM_BIN_DIR must point to the K16 LLVM bin directory}"
LLVM_OBJCOPY="$K16_LLVM_BIN_DIR/llvm-objcopy"

require_executable() {
    local path="$1"
    if [[ ! -f "$path" || ! -x "$path" ]]; then
        echo "required executable is missing: $path" >&2
        exit 1
    fi
}

require_regular_executable() {
    local path="$1"
    require_executable "$path"
    if [[ -L "$path" ]]; then
        echo "required executable must not be a symlink: $path" >&2
        exit 1
    fi
}

require_contains() {
    local path="$1"
    local expected="$2"
    if ! grep -Fq "$expected" "$path"; then
        echo "expected '$expected' in $path" >&2
        echo "----- $path -----" >&2
        sed -n '1,200p' "$path" >&2
        exit 1
    fi
}

require_not_contains() {
    local path="$1"
    local unexpected="$2"
    if grep -Fq "$unexpected" "$path"; then
        echo "did not expect '$unexpected' in $path" >&2
        echo "----- $path -----" >&2
        sed -n '1,200p' "$path" >&2
        exit 1
    fi
}

compile_tinycc() {
    local source="$1"
    local output="$2"
    "$TINYCC" -ffreestanding -nostdlib -c "$source" -o "$output"
}

compile_clang() {
    local source="$1"
    local output="$2"
    "$CLANG" --target=k16 -ffreestanding -fno-builtin -fno-stack-protector \
        -nostdlib -Oz -c "$source" -o "$output"
}

inspect_object() {
    local object="$1"
    local report="$2"
    "$LLVM_READOBJ" -h -S -s -r -x .text.k16 "$object" > "$report"
    require_contains "$report" "Type: Relocatable (0x1)"
    require_contains "$report" "Machine: 0x5258"
    require_contains "$report" "Name: .text.k16"
    require_contains "$report" "AddressAlignment: 2"
}

link_and_run() {
    local label="$1"
    local expected="$2"
    shift 2
    local startup="$WORK_DIR/$label-startup.o"
    local image="$WORK_DIR/$label.kx"
    local inspect="$WORK_DIR/$label-inspect.txt"
    local disasm="$WORK_DIR/$label-disasm.txt"
    local run="$WORK_DIR/$label-run.txt"

    "$K16" runtime k16-startup -o "$startup"
    "$K16" link --target program "$startup" "$@" "$MEMORY_HELPERS" -o "$image"
    "$K16" inspect "$image" > "$inspect"
    require_contains "$inspect" "kind=K16E"
    "$K16" disasm --target program --start 0x15000 --count 8 "$image" > "$disasm"
    require_contains "$disasm" "call r14"
    "$K16" run "$image" > "$run"
    require_contains "$run" "signal=halt exit_status=$expected debug_bytes="
}

require_tinycc_failure() {
    local fixture="$1"
    local diagnostic="$2"
    local object="$WORK_DIR/$fixture-rejected.o"
    local stderr="$WORK_DIR/$fixture-rejected.stderr"

    if "$TINYCC" -ffreestanding -nostdlib -c "$FIXTURES/$fixture.c" -o "$object" \
        > /dev/null 2> "$stderr"; then
        echo "expected tcc-k16 to reject $fixture.c" >&2
        exit 1
    fi
    if [[ -e "$object" ]]; then
        echo "tcc-k16 produced an object for rejected input: $object" >&2
        exit 1
    fi
    require_contains "$stderr" "$diagnostic"
}

require_regular_executable "$TINYCC"
require_executable "$CLANG"
require_executable "$LLVM_READOBJ"
require_executable "$LLVM_OBJCOPY"
require_executable "$K16"
require_executable "$K16_RUSTC"
require_executable "$K16_LLVM_BIN_DIR/llc"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

"$K16" runtime k16-memory-helpers -o "$WORK_DIR/memory-helpers.o"
MEMORY_HELPERS="$WORK_DIR/memory-helpers.o"

compile_tinycc "$FIXTURES/compiler-runtime.c" "$WORK_DIR/compiler-runtime-tinycc.o"
compile_clang "$FIXTURES/compiler-runtime.c" "$WORK_DIR/compiler-runtime-clang.o"
inspect_object "$WORK_DIR/compiler-runtime-tinycc.o" "$WORK_DIR/compiler-runtime-tinycc.txt"
inspect_object "$WORK_DIR/compiler-runtime-clang.o" "$WORK_DIR/compiler-runtime-clang.txt"

compile_tinycc "$FIXTURES/type-layout.c" "$WORK_DIR/type-layout-tinycc.o"
compile_clang "$FIXTURES/type-layout.c" "$WORK_DIR/type-layout-clang.o"
inspect_object "$WORK_DIR/type-layout-tinycc.o" "$WORK_DIR/type-layout-tinycc.txt"
inspect_object "$WORK_DIR/type-layout-clang.o" "$WORK_DIR/type-layout-clang.txt"

compile_tinycc "$FIXTURES/return-42.c" "$WORK_DIR/return-42-tinycc.o"
inspect_object "$WORK_DIR/return-42-tinycc.o" "$WORK_DIR/return-42-tinycc.txt"
"$LLVM_OBJCOPY" --dump-section .text.k16="$WORK_DIR/return-42-tinycc.bin" \
    "$WORK_DIR/return-42-tinycc.o"
od -An -tx1 -v "$WORK_DIR/return-42-tinycc.bin" | tr -d '[:space:]' \
    > "$WORK_DIR/return-42-tinycc.compact"
require_contains "$WORK_DIR/return-42-tinycc.compact" "01e02a000000"
require_contains "$WORK_DIR/return-42-tinycc.compact" "0090"

positive_fixtures=(
    "arithmetic:42"
    "narrow-memory:91"
    "pointers-locals:42"
    "rodata:42"
    "control-flow:42"
    "calls:42"
    "wide-direct:42"
    "aggregate-args:42"
    "varargs-basic:42"
)

for fixture_spec in "${positive_fixtures[@]}"; do
    fixture="${fixture_spec%%:*}"
    expected="${fixture_spec##*:}"
    tinycc_object="$WORK_DIR/$fixture-tinycc.o"
    clang_object="$WORK_DIR/$fixture-clang.o"

    compile_tinycc "$FIXTURES/$fixture.c" "$tinycc_object"
    compile_clang "$FIXTURES/$fixture.c" "$clang_object"
    inspect_object "$tinycc_object" "$WORK_DIR/$fixture-tinycc.txt"
    inspect_object "$clang_object" "$WORK_DIR/$fixture-clang.txt"
    link_and_run "$fixture-tinycc" "$expected" \
        "$tinycc_object" "$WORK_DIR/compiler-runtime-tinycc.o"
    link_and_run "$fixture-clang" "$expected" \
        "$clang_object" "$WORK_DIR/compiler-runtime-clang.o"
done

"$LLVM_OBJCOPY" --dump-section .text.k16="$WORK_DIR/calls-tinycc.bin" \
    "$WORK_DIR/calls-tinycc.o"
od -An -tx1 -v "$WORK_DIR/calls-tinycc.bin" | tr -d '[:space:]' \
    > "$WORK_DIR/calls-tinycc.compact"
require_contains "$WORK_DIR/calls-tinycc.compact" "01ee00000000008e"
require_contains "$WORK_DIR/calls-tinycc.txt" "R_K16_CALL32"

compile_tinycc "$FIXTURES/relocations.c" "$WORK_DIR/relocations-tinycc.o"
compile_tinycc "$FIXTURES/external-add.c" "$WORK_DIR/external-add-tinycc.o"
compile_clang "$FIXTURES/relocations.c" "$WORK_DIR/relocations-clang.o"
compile_clang "$FIXTURES/external-add.c" "$WORK_DIR/external-add-clang.o"
inspect_object "$WORK_DIR/relocations-tinycc.o" "$WORK_DIR/relocations-tinycc.txt"
inspect_object "$WORK_DIR/relocations-clang.o" "$WORK_DIR/relocations-clang.txt"
require_contains "$WORK_DIR/relocations-tinycc.txt" "Type: SHT_RELA"
require_contains "$WORK_DIR/relocations-tinycc.txt" ".rela.data"
require_contains "$WORK_DIR/relocations-tinycc.txt" "R_K16_ABS32 writable"
require_contains "$WORK_DIR/relocations-tinycc.txt" "R_K16_CALL32 external_add"
link_and_run "relocations-tinycc-tinycc" 42 \
    "$WORK_DIR/relocations-tinycc.o" "$WORK_DIR/external-add-tinycc.o"
link_and_run "relocations-tinycc-clang" 42 \
    "$WORK_DIR/relocations-tinycc.o" "$WORK_DIR/external-add-clang.o"
link_and_run "relocations-clang-tinycc" 42 \
    "$WORK_DIR/relocations-clang.o" "$WORK_DIR/external-add-tinycc.o"

compile_tinycc "$FIXTURES/varargs-caller.c" "$WORK_DIR/varargs-caller-tinycc.o"
compile_tinycc "$FIXTURES/varargs-callee.c" "$WORK_DIR/varargs-callee-tinycc.o"
compile_clang "$FIXTURES/varargs-caller.c" "$WORK_DIR/varargs-caller-clang.o"
compile_clang "$FIXTURES/varargs-callee.c" "$WORK_DIR/varargs-callee-clang.o"
inspect_object "$WORK_DIR/varargs-caller-tinycc.o" \
    "$WORK_DIR/varargs-caller-tinycc.txt"
inspect_object "$WORK_DIR/varargs-callee-tinycc.o" \
    "$WORK_DIR/varargs-callee-tinycc.txt"
inspect_object "$WORK_DIR/varargs-caller-clang.o" \
    "$WORK_DIR/varargs-caller-clang.txt"
inspect_object "$WORK_DIR/varargs-callee-clang.o" \
    "$WORK_DIR/varargs-callee-clang.txt"

for caller in tinycc clang; do
    for callee in tinycc clang; do
        link_and_run "varargs-$caller-$callee" 42 \
            "$WORK_DIR/varargs-caller-$caller.o" \
            "$WORK_DIR/varargs-callee-$callee.o"
    done
done

compile_tinycc "$FIXTURES/asm-label.c" "$WORK_DIR/asm-label.o"
"$LLVM_READOBJ" -s "$WORK_DIR/asm-label.o" > "$WORK_DIR/asm-label.txt"
require_contains "$WORK_DIR/asm-label.txt" "Name: kraft_sys_write"
require_not_contains "$WORK_DIR/asm-label.txt" "Name: write ("

"$TINYCC" -dM -E "$FIXTURES/return-42.c" > "$WORK_DIR/predefines.txt"
require_contains "$WORK_DIR/predefines.txt" "#define __k16__ 1"
require_contains "$WORK_DIR/predefines.txt" "#define __K16__ 1"
require_contains "$WORK_DIR/predefines.txt" "#define __SIZEOF_POINTER__ 4"
require_contains "$WORK_DIR/predefines.txt" "#define __SIZEOF_LONG__ 4"
require_contains "$WORK_DIR/predefines.txt" "#define __CHAR_BIT__ 8"
require_not_contains "$WORK_DIR/predefines.txt" "__CHAR_UNSIGNED__"

require_tinycc_failure "reject-float" \
    "K16 TinyCC does not support floating-point code yet"
require_tinycc_failure "reject-asm" \
    "K16 TinyCC does not support integrated assembly"
if "$TINYCC" -run "$FIXTURES/return-42.c" > /dev/null 2> "$WORK_DIR/run.stderr"; then
    echo "expected tcc-k16 to reject -run" >&2
    exit 1
fi
require_contains "$WORK_DIR/run.stderr" "K16 TinyCC is a cross-compiler; -run is unavailable"

echo "K16 TinyCC ELF metadata checks passed"
echo "K16 TinyCC differential ABI corpus passed"
echo "K16 TinyCC unsupported-feature diagnostics passed"
echo "K16 TinyCC host backend smoke passed"
