#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST_CARGO="${HOST_CARGO:-cargo}"
TARGET_SPEC="${K16_RUST_TARGET_JSON:-$ROOT/tools/k16-unknown-kraftos.json}"
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
K16_LINKER="${K16_LINKER:-$DEFAULT_TOOLCHAIN/k16-ld}"
K16_TOOL="${K16_TOOL:-}"

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
require_executable "$K16_LINKER"
if [[ -n "$K16_TOOL" ]]; then
    require_executable "$K16_TOOL"
fi
require_file "$TARGET_SPEC"

run_k16_tool() {
    if [[ -n "$K16_TOOL" ]]; then
        "$K16_TOOL" "$@"
    else
        "$HOST_CARGO" run --quiet --offline --manifest-path "$ROOT/rust/host/k16-tools/Cargo.toml" --bin k16 -- "$@"
    fi
}

export K16_RUSTC="$RUSTC"
export K16_RUST_TARGET_JSON="$TARGET_SPEC"

WORK_DIR="$(mktemp -d)"
if [[ "${K16_KEEP_SMOKE_WORK_DIR:-0}" == "1" ]]; then
    echo "keeping smoke work dir: $WORK_DIR" >&2
else
    trap 'rm -rf "$WORK_DIR"' EXIT
fi

KERNEL_TARGET_DIR="$WORK_DIR/kernel-target"
run_k16_tool runtime k16-cpu-helpers -o "$WORK_DIR/cpu-helpers.o"
RUSTC="$RUSTC" \
RUSTC_BOOTSTRAP=1 \
RUSTFLAGS="-C linker=$K16_LINKER -C link-arg=$WORK_DIR/cpu-helpers.o -C link-arg=--k16-target=kernel -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no" \
"$CARGO" \
    rustc \
    -Z build-std=core \
    -Z json-target-spec \
    --manifest-path "$ROOT/rust/guest/k16-kernel/Cargo.toml" \
    --features k16-target \
    --bin k16-kernel \
    --target "$TARGET_SPEC" \
    --target-dir "$KERNEL_TARGET_DIR" \
    -- \
    -C panic=abort \
    -C relocation-model=static \
    -Cjump-tables=no \
    -Cdebuginfo=0 \
    -Cdebug-assertions=off \
    -Coverflow-checks=off \
    -Zub-checks=no \
    2> "$WORK_DIR/kernel-rustc.stderr" || {
        echo "K16 kernel timer Rust build failed." >&2
        echo "----- cargo rustc stderr -----" >&2
        sed -n '1,220p' "$WORK_DIR/kernel-rustc.stderr" >&2
        exit 1
    }

mapfile -t kernel_matches < <(
    find "$KERNEL_TARGET_DIR/k16-unknown-kraftos/debug/deps" \
        -maxdepth 1 \
        -type f \
        -name 'k16_kernel-*' \
        ! -name '*.d' \
        | sort
)
KERNEL_KX="$(find_one "linked K16 kernel artifact" "${kernel_matches[@]}")"
run_k16_tool inspect "$KERNEL_KX" > "$WORK_DIR/kernel.inspect"
require_contains "$WORK_DIR/kernel.inspect" "kind=K16E"
require_contains "$WORK_DIR/kernel.inspect" "K16E abi=kernel"

mkdir -p "$WORK_DIR/runner/src"
cat > "$WORK_DIR/runner/Cargo.toml" <<TOML
[package]
name = "k16-kernel-timer-smoke-runner"
version = "0.1.0"
edition = "2021"

[dependencies]
k16-vm = { path = "$ROOT/rust/host/k16-vm" }
TOML

cat > "$WORK_DIR/runner/src/main.rs" <<'RS'
use k16_vm::computer_abi;
use k16_vm::computer_machine::{decode_snapshot_v1, ComputerCpuSnapshotRecord, ComputerMachine};
use k16_vm::k16::K16Signal;
use k16_vm::k16_computer::K16ComputerHandle;
use k16_vm::k16e::{self, K16eAbiKind};
use std::{env, fs, process};

fn main() {
    const KERNEL_STACK_TOP: u32 = 0x0001_0000;
    const SYSCALL_PROBE_ADDR: u32 = 0x0000_9000;

    let kernel_path = env::args().nth(1).expect("kernel path argument");
    let kernel = fs::read(&kernel_path).expect("kernel K16E reads");
    let executable = k16e::decode_k16_executable(&kernel).expect("kernel K16E decodes");
    if executable.abi_kind != K16eAbiKind::Kernel {
        eprintln!("expected K16eAbiKind::Kernel, got {:?}", executable.abi_kind);
        process::exit(1);
    }

    let mut handle = K16ComputerHandle::create_k16_bios_flash(&[0x01, 0x00], 64 * 1024, 1_000_000)
        .expect("K16 computer creates");
    handle
        .write_guest_ram_bytes(executable.load_addr, &executable.payload)
        .expect("kernel payload writes to guest RAM");
    handle
        .boot_handoff_k16_from_guest_ram_with_stack(
            executable.entry_pc,
            executable.payload.len() as u32,
            1_000_000,
            KERNEL_STACK_TOP,
        )
        .expect("kernel boot handoff succeeds");

    let first = handle.run_k16_until_signal().expect("initial kernel run succeeds");
    if first != K16Signal::Yield {
        eprintln!("expected signal=yield from live kernel idle loop, got {first:?}");
        process::exit(1);
    }
    let control = handle.control();
    if control.status != ComputerMachine::STATUS_READY {
        eprintln!("expected READY kernel status, got {}", control.status);
        process::exit(1);
    }
    if handle.debug_output_bytes() != b"KERNEL OK\n" {
        eprintln!(
            "expected initial kernel debug banner, got {}",
            hex_bytes(handle.debug_output_bytes())
        );
        process::exit(1);
    }

    handle.advance_game_tick();
    let second = handle
        .run_k16_until_signal()
        .expect("first timer0 resume succeeds");
    if second != K16Signal::Yield {
        dump_cpu_snapshot(&mut handle);
        eprintln!("debug_bytes={}", hex_bytes(handle.debug_output_bytes()));
        eprintln!("expected signal=yield after timer0 heartbeat, got {second:?}");
        process::exit(1);
    }
    if !handle.debug_output_bytes().ends_with(&[b'|']) {
        dump_cpu_snapshot(&mut handle);
        eprintln!(
            "expected debug_suffix=7c after timer0 heartbeat, got {}",
            hex_bytes(handle.debug_output_bytes())
        );
        process::exit(1);
    }

    handle.advance_game_tick();
    let third = handle
        .run_k16_until_signal()
        .expect("second timer0 resume succeeds");
    if third != K16Signal::Yield {
        dump_cpu_snapshot(&mut handle);
        eprintln!("debug_bytes={}", hex_bytes(handle.debug_output_bytes()));
        eprintln!("expected signal=yield after second timer0 heartbeat, got {third:?}");
        process::exit(1);
    }
    if !handle.debug_output_bytes().ends_with(b"||") {
        dump_cpu_snapshot(&mut handle);
        eprintln!(
            "expected debug_suffix=7c7c after repeated timer0 heartbeat, got {}",
            hex_bytes(handle.debug_output_bytes())
        );
        process::exit(1);
    }

    let syscall_pc = boot_cpu_pc(&mut handle);
    let trampoline = jump_to_syscall_probe(SYSCALL_PROBE_ADDR);
    let original_bytes = original_guest_bytes(&executable, syscall_pc, trampoline.len());
    handle
        .write_guest_ram_bytes(
            SYSCALL_PROBE_ADDR,
            &returning_syscall_probe(syscall_pc, &original_bytes),
        )
        .expect("returning syscall probe writes to guest RAM");
    handle
        .write_guest_ram_bytes(syscall_pc, &trampoline)
        .expect("returning syscall probe writes to guest RAM");
    let fourth = handle
        .run_k16_until_signal()
        .expect("returning syscall probe runs");
    if fourth != K16Signal::Yield {
        dump_cpu_snapshot(&mut handle);
        eprintln!("debug_bytes={}", hex_bytes(handle.debug_output_bytes()));
        eprintln!("expected signal=yield after returning syscall continuation, got {fourth:?}");
        process::exit(1);
    }
    let continuation_r2 = boot_cpu_register(&mut handle, 2);
    if continuation_r2 != 7 {
        dump_cpu_snapshot(&mut handle);
        eprintln!(
            "expected continuation_r2=7 after returning syscall proof, got {continuation_r2}"
        );
        process::exit(1);
    }

    println!(
        "first_signal=yield timer_signals=yield,yield syscall_signal=yield status=READY debug_suffix=7c7c continuation_r2=7"
    );
}

fn hex_bytes(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn jump_to_syscall_probe(address: u32) -> Vec<u8> {
    let mut bytes = Vec::new();
    emit_const32(&mut bytes, 14, address);
    emit_word(&mut bytes, jump(14));
    bytes
}

fn returning_syscall_probe(patch_pc: u32, original_bytes: &[u8]) -> Vec<u8> {
    let original_low = u32::from_le_bytes(
        original_bytes[0..4]
            .try_into()
            .expect("low original bytes decode"),
    );
    let original_high = u32::from_le_bytes(
        original_bytes[4..8]
            .try_into()
            .expect("high original bytes decode"),
    );
    let mut bytes = Vec::new();
    emit_const32(&mut bytes, 14, patch_pc);
    emit_const32(&mut bytes, 1, original_low);
    emit_word(&mut bytes, store32(14, 1));
    emit_const32(&mut bytes, 13, 4);
    emit_alu_rrr(&mut bytes, 14, 0x0, 14, 13);
    emit_const32(&mut bytes, 1, original_high);
    emit_word(&mut bytes, store32(14, 1));
    emit_word(&mut bytes, const4(0, 2));
    emit_word(&mut bytes, syscall(0));
    emit_word(&mut bytes, const4(2, 7));
    emit_const32(&mut bytes, 14, computer_abi::CONTROL_YIELD);
    emit_word(&mut bytes, const4(1, 1));
    emit_word(&mut bytes, store32(14, 1));
    bytes
}

fn emit_const32(bytes: &mut Vec<u8>, register: u8, value: u32) {
    emit_word(bytes, 0xe001 | (u16::from(register) << 8));
    emit_word(bytes, (value & 0xffff) as u16);
    emit_word(bytes, (value >> 16) as u16);
}

fn emit_word(bytes: &mut Vec<u8>, word: u16) {
    bytes.extend_from_slice(&word.to_le_bytes());
}

fn emit_alu_rrr(bytes: &mut Vec<u8>, dst: u8, subop: u8, lhs: u8, rhs: u8) {
    emit_word(bytes, 0x2000 | (u16::from(dst) << 8) | u16::from(subop));
    emit_word(bytes, (u16::from(lhs) << 4) | u16::from(rhs));
}

fn const4(register: u8, value: u8) -> u16 {
    0x1000 | (u16::from(register) << 8) | u16::from(value & 0x0f)
}

fn syscall(register: u8) -> u16 {
    0x0005 | (u16::from(register) << 8)
}

fn jump(register: u8) -> u16 {
    0x7000 | (u16::from(register) << 8)
}

fn store32(addr: u8, src: u8) -> u16 {
    0x5002 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn original_guest_bytes(executable: &k16e::K16eExecutable, address: u32, len: usize) -> Vec<u8> {
    let start = address
        .checked_sub(executable.load_addr)
        .expect("patch address is inside kernel payload") as usize;
    executable.payload[start..start + len].to_vec()
}

fn boot_cpu_pc(handle: &mut K16ComputerHandle) -> u32 {
    let snapshot_bytes = handle.snapshot_v1().expect("snapshot encodes");
    let snapshot = decode_snapshot_v1(&snapshot_bytes).expect("snapshot decodes");
    let record = snapshot.cpus.into_iter().next().expect("boot CPU exists");
    let ComputerCpuSnapshotRecord::K16 { cpu, .. } = record;
    cpu.pc
}

fn boot_cpu_register(handle: &mut K16ComputerHandle, register: usize) -> u32 {
    let snapshot_bytes = handle.snapshot_v1().expect("snapshot encodes");
    let snapshot = decode_snapshot_v1(&snapshot_bytes).expect("snapshot decodes");
    let record = snapshot.cpus.into_iter().next().expect("boot CPU exists");
    let ComputerCpuSnapshotRecord::K16 { cpu, .. } = record;
    cpu.registers[register]
}

fn dump_cpu_snapshot(handle: &mut K16ComputerHandle) {
    let Ok(snapshot_bytes) = handle.snapshot_v1() else {
        return;
    };
    let Ok(snapshot) = decode_snapshot_v1(&snapshot_bytes) else {
        return;
    };
    for record in snapshot.cpus {
        let ComputerCpuSnapshotRecord::K16 { cpu, .. } = record;
        eprintln!(
            "cpu pc={:#010x} sp={:#010x} r0={:#010x} r1={:#010x} r14={:#010x} trap_vector={:#010x} trap_cause={:#010x} trap_pc={:#010x} trap_value={:#010x} interrupt_enable={} interrupt_mask={:#010x} interrupt_pending={:#010x}",
            cpu.pc,
            cpu.registers[15],
            cpu.registers[0],
            cpu.registers[1],
            cpu.registers[14],
            cpu.trap_vector,
            cpu.trap_cause,
            cpu.trap_pc,
            cpu.trap_value,
            cpu.interrupt_enable,
            cpu.interrupt_mask,
            cpu.interrupt_pending
        );
    }
}
RS

"$HOST_CARGO" run --quiet --offline --manifest-path "$WORK_DIR/runner/Cargo.toml" -- "$KERNEL_KX" \
    > "$WORK_DIR/runner.stdout"
require_contains "$WORK_DIR/runner.stdout" "signal=yield"
require_contains "$WORK_DIR/runner.stdout" "debug_suffix=7c7c"
require_contains "$WORK_DIR/runner.stdout" "continuation_r2=7"

cat "$WORK_DIR/runner.stdout"
echo "K16 kernel timer smoke passed"
