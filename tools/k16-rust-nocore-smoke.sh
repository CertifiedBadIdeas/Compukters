#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUSTC="${K16_RUSTC:-rustc}"
LLVM_BIN_DIR="${K16_LLVM_BIN_DIR:-$ROOT/toolchains/Compukter-Kraft-llvm/build-rux/bin}"
LLVM_READOBJ="$LLVM_BIN_DIR/llvm-readobj"
RUX_CARGO_MANIFEST="$ROOT/native/k16-tools/Cargo.toml"
TARGET_SPEC="${K16_RUST_TARGET_JSON:-$ROOT/tools/k16-unknown-kraftos.json}"

require_file() {
    local path="$1"
    if [[ ! -x "$path" ]]; then
        echo "required executable is missing: $path" >&2
        exit 1
    fi
}

resolve_command() {
    local command_name="$1"
    if [[ "$command_name" == */* ]]; then
        require_file "$command_name"
        printf '%s\n' "$command_name"
        return
    fi

    if ! command -v -- "$command_name"; then
        echo "required command is missing: $command_name" >&2
        exit 1
    fi
}

require_readable() {
    local path="$1"
    if [[ ! -r "$path" ]]; then
        echo "required file is missing: $path" >&2
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
    cargo run --quiet --manifest-path "$RUX_CARGO_MANIFEST" --bin k16 -- "$@"
}

RUSTC="$(resolve_command "$RUSTC")"
require_file "$LLVM_READOBJ"
require_readable "$TARGET_SPEC"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

"$RUSTC" --version --verbose > "$WORK_DIR/rustc-version.txt"
if ! "$RUSTC" -Z help > "$WORK_DIR/rustc-z-help.txt" 2> "$WORK_DIR/rustc-z-help.stderr"; then
    echo "K16 Rust no_core smoke requires a custom nightly rustc built with the K16 LLVM target." >&2
    echo "Set K16_RUSTC=/path/to/rustc and rerun tools/k16-rust-nocore-smoke.sh." >&2
    echo "----- rustc -Z help stderr -----" >&2
    sed -n '1,80p' "$WORK_DIR/rustc-z-help.stderr" >&2
    exit 1
fi

cat > "$WORK_DIR/main.rs" <<'RS'
#![feature(no_core, lang_items)]
#![no_core]
#![no_main]

#[lang = "sized"]
pub trait Sized: MetaSized {}

#[lang = "meta_sized"]
pub trait MetaSized: PointeeSized {}

#[lang = "pointee_sized"]
pub trait PointeeSized {}

#[no_mangle]
pub extern "C" fn main() -> i32 {
    42
}
RS

"$RUSTC" \
    -Z unstable-options \
    --edition=2021 \
    --target "$TARGET_SPEC" \
    -C panic=abort \
    -C relocation-model=static \
    --emit=obj \
    "$WORK_DIR/main.rs" \
    -o "$WORK_DIR/main.o" \
    2> "$WORK_DIR/rustc.stderr"

"$LLVM_READOBJ" -h -S -s "$WORK_DIR/main.o" > "$WORK_DIR/main-object.txt"
require_contains "$WORK_DIR/main-object.txt" "Machine: 0x5258"
require_contains "$WORK_DIR/main-object.txt" "Name: .text.k16"
require_contains "$WORK_DIR/main-object.txt" "Name: main"

run_k16 runtime k16-startup -o "$WORK_DIR/startup.o"
run_k16 runtime k16-memory-helpers -o "$WORK_DIR/helpers.o"
run_k16 link --target program "$WORK_DIR/startup.o" "$WORK_DIR/main.o" "$WORK_DIR/helpers.o" -o "$WORK_DIR/main.kx"
run_k16 inspect "$WORK_DIR/main.kx" > "$WORK_DIR/main-kx.txt"
require_contains "$WORK_DIR/main-kx.txt" "kind=K16E"
require_contains "$WORK_DIR/main-kx.txt" "K16E abi=program entry_pc=0x00008000 load_addr=0x00008000"

run_k16 run "$WORK_DIR/main.kx" > "$WORK_DIR/main-run.txt"
require_contains "$WORK_DIR/main-run.txt" "signal=halt debug_bytes=2a"

echo "Rust no_core object checks passed"
echo "KX link and execution checks passed"
echo "K16 Rust no_core smoke passed"
