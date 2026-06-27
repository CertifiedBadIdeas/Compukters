#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CARGO="${K16_CARGO:-cargo}"
RUSTC="${K16_RUSTC:-rustc}"
TARGET_SPEC="${K16_RUST_TARGET_JSON:-$ROOT/tools/k16-unknown-kraftos.json}"
K16_LD="${K16_LD:-$ROOT/host/k16-tools/target/debug/k16-ld}"
K16_MANIFEST="$ROOT/host/k16-tools/Cargo.toml"

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

CARGO="$(resolve_command "$CARGO")"
RUSTC="$(resolve_command "$RUSTC")"
require_file "$K16_LD"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

mkdir -p "$WORK_DIR/src"
cat > "$WORK_DIR/Cargo.toml" <<'TOML'
[package]
name = "k16-bin-link-smoke"
version = "0.1.0"
edition = "2021"
TOML

cat > "$WORK_DIR/src/main.rs" <<'RS'
#![no_std]
#![no_main]

use core::panic::PanicInfo;

#[no_mangle]
pub extern "C" fn _start() -> ! {
    unsafe {
        *(0x1000_0000usize as *mut i32) = 3;
    }
    loop {
        core::hint::spin_loop();
    }
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    loop {
        core::hint::spin_loop();
    }
}
RS

RUSTC="$RUSTC" \
    RUSTFLAGS="-C linker=$K16_LD -C link-arg=--k16-target=bios -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no" \
    "$CARGO" \
        rustc \
        -Zbuild-std=core \
        -Zjson-target-spec \
        --manifest-path "$WORK_DIR/Cargo.toml" \
        --target "$TARGET_SPEC" \
        --target-dir "$WORK_DIR/target" \
        -- \
        -C panic=abort \
        -C relocation-model=static \
        -Cjump-tables=no \
        -Cdebuginfo=0 \
        -Cdebug-assertions=off \
        -Coverflow-checks=off \
        -Zub-checks=no

BIOS_FLASH="$(
    find "$WORK_DIR/target/k16-unknown-kraftos/debug/deps" \
        -maxdepth 1 \
        -type f \
        ! -name '*.d' \
        ! -name '*.o' \
        ! -name '*.rlib' \
        ! -name '*.rmeta' \
        -print \
        -quit
)"

if [[ -z "$BIOS_FLASH" ]]; then
    echo "linked Rust bin BIOS artifact was not produced" >&2
    exit 1
fi

cargo run --quiet --manifest-path "$K16_MANIFEST" --bin k16 -- run-bios "$BIOS_FLASH" > "$WORK_DIR/run-bios.txt"
if ! grep -Fq "status=3" "$WORK_DIR/run-bios.txt"; then
    echo "linked Rust bin BIOS did not halt through control MMIO" >&2
    cat "$WORK_DIR/run-bios.txt" >&2
    exit 1
fi

echo "K16 Rust bin linker smoke passed"
