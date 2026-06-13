#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST_CARGO="${HOST_CARGO:-cargo}"
TARGET_SPEC="${K16_RUST_TARGET_JSON:-$ROOT/tools/k16-unknown-kraftos.json}"
LLVM_BIN_DIR="${K16_LLVM_BIN_DIR:-$ROOT/.toolchain/build/llvm/k16-min/bin}"
LLC="$LLVM_BIN_DIR/llc"
PIN="$(grep -m1 '"pin"' "$ROOT/config/k16-toolchain.json" | sed -E 's/.*"pin": "([^"]+)".*/\1/')"

host_id() {
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"
    case "$os:$arch" in
        Linux:x86_64) printf '%s\n' "linux-x86_64" ;;
        Linux:aarch64) printf '%s\n' "linux-aarch64" ;;
        Darwin:arm64) printf '%s\n' "macos-aarch64" ;;
        MINGW*:x86_64 | MSYS*:x86_64 | CYGWIN*:x86_64) printf '%s\n' "windows-x86_64" ;;
        *)
            echo "unsupported K16 toolchain host: $os $arch" >&2
            exit 1
            ;;
    esac
}

DEFAULT_TOOLCHAIN="$ROOT/.toolchain/k16/$PIN/$(host_id)/bin"
CARGO="${K16_CARGO:-$DEFAULT_TOOLCHAIN/cargo}"
RUSTC="${K16_RUSTC:-$DEFAULT_TOOLCHAIN/rustc}"
K16_TOOL="${K16_TOOL:-$DEFAULT_TOOLCHAIN/k16}"

require_executable() {
    local path="$1"
    if [[ ! -x "$path" ]]; then
        echo "required executable is missing: $path" >&2
        exit 1
    fi
}

resolve_command() {
    local command_name="$1"
    if [[ "$command_name" == */* ]]; then
        require_executable "$command_name"
        printf '%s\n' "$command_name"
        return
    fi

    if ! command -v -- "$command_name"; then
        echo "required command is missing: $command_name" >&2
        exit 1
    fi
}

require_file() {
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
        sed -n '1,180p' "$path" >&2
        exit 1
    fi
}

find_one() {
    local description="$1"
    shift
    local matches=("$@")
    if [[ "${#matches[@]}" -ne 1 ]]; then
        echo "expected exactly one $description, found ${#matches[@]}" >&2
        printf '  %s\n' "${matches[@]}" >&2
        exit 1
    fi
    printf '%s\n' "${matches[0]}"
}

HOST_CARGO="$(resolve_command "$HOST_CARGO")"
require_executable "$CARGO"
require_executable "$RUSTC"
require_executable "$K16_TOOL"
require_executable "$LLC"
require_file "$TARGET_SPEC"

export K16_LLVM_BIN_DIR="$LLVM_BIN_DIR"
export K16_RUSTC="$RUSTC"
export K16_RUST_TARGET_JSON="$TARGET_SPEC"

WORK_DIR="$(mktemp -d)"
if [[ "${K16_KEEP_SMOKE_WORK_DIR:-0}" == "1" ]]; then
    echo "keeping smoke work dir: $WORK_DIR" >&2
else
    trap 'rm -rf "$WORK_DIR"' EXIT
fi

mkdir -p "$WORK_DIR/guest/src" "$WORK_DIR/runner/src"

cat > "$WORK_DIR/guest/Cargo.toml" <<TOML
[package]
name = "k16-guest-interrupt-smoke"
version = "0.1.0"
edition = "2021"

[lib]
name = "k16_guest_interrupt_smoke"
path = "src/main.rs"
test = false

[dependencies]
k16-abi = { path = "$ROOT/rust/guest/k16-abi" }
k16-rt = { path = "$ROOT/rust/guest/k16-rt" }
TOML

cat > "$WORK_DIR/guest/src/main.rs" <<'RS'
#![no_std]
extern crate k16_rt;

use core::panic::PanicInfo;
use k16_abi::computer::{control, debug};
use k16_rt::cpu;

static mut HANDLED: u32 = 0;
static mut CAUSE: u32 = 0;
static mut PC: u32 = 0;
static mut VALUE: u32 = 0;
static mut PENDING: u32 = 0;

#[no_mangle]
pub extern "C" fn main() -> i32 {
    unsafe {
        k16_rt::install_trap_vector(timer0_handler as *const () as usize as u32);
        k16_rt::set_interrupt_mask(cpu::interrupt_source::TIMER0);
        k16_rt::enable_interrupts();
    }

    while unsafe { HANDLED } == 0 {
        yield_to_host();
    }

    let valid = unsafe {
        CAUSE == cpu::trap_cause::TIMER0_INTERRUPT
            && PC != 0
            && VALUE == cpu::interrupt_source::TIMER0
            && PENDING == 0
    };
    if valid { 42 } else { 7 }
}

fn yield_to_host() {
    unsafe {
        *(control::YIELD as usize as *mut i32) = 1;
    }
}

#[no_mangle]
pub extern "C" fn timer0_handler() -> ! {
    unsafe {
        CAUSE = k16_rt::trap_cause();
        PC = k16_rt::trap_pc();
        VALUE = k16_rt::trap_value();
        PENDING = k16_rt::interrupt_pending();
        *(debug::WRITE as usize as *mut u8) = b'I';
        HANDLED = 1;
        k16_rt::iret_once()
    }
}

#[panic_handler]
fn panic(_info: &PanicInfo<'_>) -> ! {
    loop {
        k16_rt::halt_once();
    }
}
RS

RUSTC="$RUSTC" \
RUSTC_BOOTSTRAP=1 \
RUSTFLAGS="-Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off" \
"$CARGO" \
    rustc \
    -Z build-std=core \
    -Z json-target-spec \
    --manifest-path "$WORK_DIR/guest/Cargo.toml" \
    --target "$TARGET_SPEC" \
    --target-dir "$WORK_DIR/guest-target" \
    --lib \
    -- \
    -C panic=abort \
    -C relocation-model=static \
    -Cjump-tables=no \
    -Cdebuginfo=0 \
    -Cdebug-assertions=off \
    -Coverflow-checks=off \
    -Zub-checks=no \
    --emit=llvm-ir \
    2> "$WORK_DIR/guest-rustc.stderr" || {
        echo "K16 guest interrupt Rust build failed." >&2
        echo "----- cargo rustc stderr -----" >&2
        sed -n '1,220p' "$WORK_DIR/guest-rustc.stderr" >&2
        exit 1
    }

mapfile -t guest_ir_matches < <(
    find "$WORK_DIR/guest-target/k16-unknown-kraftos/debug/deps" \
        -maxdepth 1 \
        -type f \
        -name 'k16_guest_interrupt_smoke-*.ll' \
        | sort
)
GUEST_IR="$(find_one "guest interrupt smoke LLVM IR" "${guest_ir_matches[@]}")"

"$LLC" -mtriple=k16 -filetype=obj "$GUEST_IR" -o "$WORK_DIR/guest.o"
"$K16_TOOL" runtime k16-startup -o "$WORK_DIR/startup.o"
"$K16_TOOL" runtime k16-memory-helpers -o "$WORK_DIR/helpers.o"
"$K16_TOOL" link --target program "$WORK_DIR/startup.o" "$WORK_DIR/guest.o" "$WORK_DIR/helpers.o" -o "$WORK_DIR/interrupt-smoke.kx"
"$K16_TOOL" inspect "$WORK_DIR/interrupt-smoke.kx" > "$WORK_DIR/interrupt-smoke.inspect"
require_contains "$WORK_DIR/interrupt-smoke.inspect" "kind=K16E"
require_contains "$WORK_DIR/interrupt-smoke.inspect" "K16E abi=program"

cat > "$WORK_DIR/runner/Cargo.toml" <<TOML
[package]
name = "k16-guest-interrupt-smoke-runner"
version = "0.1.0"
edition = "2021"

[dependencies]
k16-vm = { path = "$ROOT/rust/host/k16-vm" }
TOML

cat > "$WORK_DIR/runner/src/main.rs" <<'RS'
use k16_vm::k16::K16Signal;
use k16_vm::k16_computer::K16ComputerHandle;
use std::{env, fs, process};

fn main() {
    let program_path = env::args().nth(1).expect("program path argument");
    let program = fs::read(&program_path).expect("K16E program reads");
    let mut handle = K16ComputerHandle::create_k16_bios_flash(&[0x01, 0x00], 128 * 1024, 1_000_000)
        .expect("K16 computer creates");
    handle
        .exec_k16e_program_from_bytes(&program, 1_000_000)
        .expect("program installs");

    let first = handle.run_k16_until_signal().expect("initial run succeeds");
    if first != K16Signal::Yield {
        eprintln!("expected initial yield while waiting for timer0, got {first:?}");
        process::exit(1);
    }
    if !handle.debug_output_bytes().is_empty() {
        eprintln!(
            "expected no debug output before timer0, got {}",
            hex_bytes(handle.debug_output_bytes())
        );
        process::exit(1);
    }

    handle.advance_game_tick();

    let second = handle.run_k16_until_signal().expect("resumed run succeeds");
    if second != K16Signal::Halt {
        eprintln!("expected final halt after timer0 handler, got {second:?}");
        process::exit(1);
    }
    if handle.debug_output_bytes() != [b'I', 42] {
        eprintln!(
            "expected debug_bytes=492a, got {}",
            hex_bytes(handle.debug_output_bytes())
        );
        process::exit(1);
    }

    println!("first_signal=yield second_signal=halt debug_bytes=492a");
}

fn hex_bytes(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}
RS

"$HOST_CARGO" run --quiet --offline --manifest-path "$WORK_DIR/runner/Cargo.toml" -- "$WORK_DIR/interrupt-smoke.kx" \
    > "$WORK_DIR/runner.stdout"
require_contains "$WORK_DIR/runner.stdout" "debug_bytes=492a"

cat "$WORK_DIR/runner.stdout"
echo "K16 guest interrupt smoke passed"
