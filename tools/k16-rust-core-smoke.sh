#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CARGO="${K16_CARGO:-cargo}"
RUSTC="${K16_RUSTC:-rustc}"
LLVM_BIN_DIR="${K16_LLVM_BIN_DIR:-$ROOT/.toolchain/build/llvm/k16-min/bin}"
LLVM_READOBJ="$LLVM_BIN_DIR/llvm-readobj"
K16_CARGO_MANIFEST="$ROOT/rust/host/k16-tools/Cargo.toml"
TARGET_SPEC="${K16_RUST_TARGET_JSON:-$ROOT/tools/k16-unknown-kraftos.json}"
K16_HOST_CARGO_TARGET_DIR="${K16_HOST_CARGO_TARGET_DIR:-${CARGO_TARGET_DIR:-$ROOT/.toolchain/build/cargo/k16-tools}}"
export CARGO_TARGET_DIR="$K16_HOST_CARGO_TARGET_DIR"

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
    if ! grep -Fq -- "$expected" "$path"; then
        echo "expected '$expected' in $path" >&2
        echo "----- $path -----" >&2
        sed -n '1,160p' "$path" >&2
        exit 1
    fi
}

run_k16() {
    cargo run --quiet --manifest-path "$K16_CARGO_MANIFEST" --bin k16 -- "$@"
}

CARGO="$(resolve_command "$CARGO")"
RUSTC="$(resolve_command "$RUSTC")"
require_file "$LLVM_READOBJ"
require_readable "$TARGET_SPEC"
export K16_LLVM_BIN_DIR="$LLVM_BIN_DIR"
export RUSTC_BOOTSTRAP="${RUSTC_BOOTSTRAP:-1}"

WORK_DIR="$(mktemp -d)"
cleanup() {
    if [[ "${K16_KEEP_WORK_DIR:-0}" == "1" ]]; then
        echo "K16 Rust core smoke work dir kept at $WORK_DIR" >&2
    else
        rm -rf "$WORK_DIR"
    fi
}
trap cleanup EXIT

"$CARGO" -Z help > "$WORK_DIR/cargo-z-help.txt" 2> "$WORK_DIR/cargo-z-help.stderr" || {
    echo "K16 Rust core smoke requires nightly-capable cargo with -Z build-std support." >&2
    echo "Set K16_CARGO=/path/to/cargo if the default cargo is not suitable." >&2
    echo "----- cargo -Z help stderr -----" >&2
    sed -n '1,80p' "$WORK_DIR/cargo-z-help.stderr" >&2
    exit 1
}

"$RUSTC" --version --verbose > "$WORK_DIR/rustc-version.txt"
if ! "$RUSTC" -Z help > "$WORK_DIR/rustc-z-help.txt" 2> "$WORK_DIR/rustc-z-help.stderr"; then
    echo "K16 Rust core smoke requires a custom nightly rustc built with the K16 LLVM target." >&2
    echo "Set K16_RUSTC=/path/to/rustc and rerun tools/k16-rust-core-smoke.sh." >&2
    echo "----- rustc -Z help stderr -----" >&2
    sed -n '1,80p' "$WORK_DIR/rustc-z-help.stderr" >&2
    exit 1
fi

mkdir -p "$WORK_DIR/src"
cat > "$WORK_DIR/Cargo.toml" <<'TOML'
[package]
name = "k16-core-smoke"
version = "0.1.0"
edition = "2021"

[lib]
name = "k16_core_smoke"
path = "src/main.rs"
test = false
TOML

cat > "$WORK_DIR/src/main.rs" <<'RS'
#![no_std]

use core::panic::PanicInfo;

#[no_mangle]
pub extern "C" fn main() -> i32 {
    if core::mem::size_of::<usize>() == 4 {
        42
    } else {
        7
    }
}

#[panic_handler]
fn panic(_info: &PanicInfo<'_>) -> ! {
    loop {
        core::hint::spin_loop();
    }
}
RS

if ! RUSTC="$RUSTC" \
    RUSTFLAGS="-Copt-level=z -Cjump-tables=no -Cdebuginfo=0" \
    "$CARGO" \
        rustc \
        -Z build-std=core \
        -Z json-target-spec \
        --manifest-path "$WORK_DIR/Cargo.toml" \
        --target "$TARGET_SPEC" \
        --target-dir "$WORK_DIR/target" \
        --lib \
        -- \
        -C panic=abort \
        -Copt-level=z \
        -C relocation-model=static \
        -Cjump-tables=no \
        -Cdebuginfo=0 \
        "--emit=obj=$WORK_DIR/main.o" \
        2> "$WORK_DIR/cargo-rustc.stderr"; then
    echo "K16 Rust core build failed." >&2
    echo "----- cargo rustc stderr -----" >&2
    sed -n '1,160p' "$WORK_DIR/cargo-rustc.stderr" >&2
    exit 1
fi

"$LLVM_READOBJ" -h -S -s "$WORK_DIR/main.o" > "$WORK_DIR/main-object.txt"
require_contains "$WORK_DIR/main-object.txt" "Machine: 0x5258"
require_contains "$WORK_DIR/main-object.txt" "Name: .text.k16"
require_contains "$WORK_DIR/main-object.txt" "Name: main"

run_k16 runtime k16-startup -o "$WORK_DIR/startup.o"
run_k16 runtime k16-memory-helpers -o "$WORK_DIR/helpers.o"
run_k16 link --target program "$WORK_DIR/startup.o" "$WORK_DIR/main.o" "$WORK_DIR/helpers.o" -o "$WORK_DIR/main.kx"
run_k16 inspect "$WORK_DIR/main.kx" > "$WORK_DIR/main-kx.txt"
require_contains "$WORK_DIR/main-kx.txt" "kind=K16E"
require_contains "$WORK_DIR/main-kx.txt" "K16E abi=program entry_pc=0x00015000 load_addr=0x00015000"

run_k16 run "$WORK_DIR/main.kx" > "$WORK_DIR/main-run.txt"
require_contains "$WORK_DIR/main-run.txt" "signal=halt exit_status=42 debug_bytes="

echo "Rust core object checks passed"
echo "KX link and execution checks passed"
echo "K16 Rust core smoke passed"
