use k16_tools::artifact::K16ArtifactTarget;
use k16_tools::k16_runtime;
use k16_tools::k16e;
use k16_vm::computer_machine::{decode_snapshot_v1, ComputerCpuSnapshotRecord};
use k16_vm::k16::{
    K16Cpu, K16Signal, K16_CSR_TRAP_FRAME_INDEX, K16_CSR_TRAP_FRAME_REGISTER,
    K16_CSR_TRAP_INTERRUPT_ENABLE, K16_CSR_TRAP_RESUME_PC, K16_CSR_TRAP_STACK_POINTER,
    K16_CSR_TRAP_VECTOR,
};
use k16_vm::k16_computer::K16ComputerHandle;
use k16_vm::low_machine::MachineMemory;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

#[test]
fn k16_runtime_startup_accepts_dynamic_program_target_without_fixed_stack_top() {
    let startup_path = temp_file("startup-program-dynamic.o");

    let runtime_output = Command::new(k16_binary())
        .args([
            "runtime",
            "k16-startup",
            "--target",
            "program-dynamic",
            "-o",
            startup_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 runtime runs");
    assert!(
        runtime_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&runtime_output.stderr)
    );

    let startup_object = fs::read(startup_path).expect("startup object reads");
    let fixed_stack_top_words = const32(15, K16ArtifactTarget::PROGRAM_INITIAL_STACK_POINTER);
    let fixed_stack_top_bytes = words_to_bytes(&fixed_stack_top_words);

    assert!(
        !startup_object
            .windows(fixed_stack_top_bytes.len())
            .any(|window| window == fixed_stack_top_bytes.as_slice()),
        "program-dynamic startup must use the kernel-provided r15 stack top"
    );
}

#[test]
fn k16_runtime_startup_preserves_program_argv_registers_for_lang_start() {
    let startup_path = temp_file("startup-preserve-argv.o");

    let runtime_output = Command::new(k16_binary())
        .args([
            "runtime",
            "k16-startup",
            "--target",
            "program-dynamic",
            "-o",
            startup_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 runtime runs");
    assert!(
        runtime_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&runtime_output.stderr)
    );

    let startup_object = fs::read(startup_path).expect("startup object reads");
    assert!(
        !startup_object
            .windows(words_to_bytes(&const32(1, 0)).len())
            .any(|window| window == words_to_bytes(&const32(1, 0)).as_slice()),
        "program startup must preserve r1 argc for Rust lang_start"
    );
    assert!(
        !startup_object
            .windows(words_to_bytes(&const32(2, 0)).len())
            .any(|window| window == words_to_bytes(&const32(2, 0)).as_slice()),
        "program startup must preserve r2 argv table for Rust lang_start"
    );
}

#[test]
fn k16_runtime_startup_links_returning_main_and_requires_exit_syscall_handler() {
    let startup_path = temp_file("startup.o");
    let main_path = temp_file("main.o");
    let output_path = temp_file("program.k16e");
    fs::write(&main_path, k16_main_returning_42_object()).expect("main object writes");

    let runtime_output = Command::new(k16_binary())
        .args([
            "runtime",
            "k16-startup",
            "-o",
            startup_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 runtime runs");
    assert!(
        runtime_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&runtime_output.stderr)
    );

    let link_output = Command::new(k16_binary())
        .args([
            "link",
            "--target",
            "program",
            startup_path.to_str().unwrap(),
            main_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 link runs");
    assert!(
        link_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&link_output.stderr)
    );

    let program = fs::read(output_path).expect("linked program reads");
    let executable = k16e::decode_k16_executable(&program).expect("linked K16E decodes");
    assert_eq!(executable.entry_pc, 0x1_5000);

    let mut handle = K16ComputerHandle::create_k16_bios_flash(
        &[0x01, 0x00],
        K16ArtifactTarget::DEFAULT_MEMORY_SIZE,
        1_000_000,
    )
    .expect("K16 computer creates");
    handle
        .exec_k16e_program_from_bytes(&program, 1_000_000)
        .expect("program installs");
    let error = handle
        .run_k16_until_signal()
        .expect_err("startup EXIT syscall requires a kernel trap handler");

    assert!(
        error.contains("unhandled exception cause 5") && error.contains("syscall 6"),
        "error: {error}"
    );
    assert_eq!(handle.debug_output_bytes(), &[]);
}

#[test]
fn k16_run_reports_startup_exit_status_for_standalone_program() {
    let startup_path = temp_file("run-startup.o");
    let main_path = temp_file("run-main.o");
    let output_path = temp_file("run-program.k16e");
    fs::write(&main_path, k16_main_returning_42_object()).expect("main object writes");

    let runtime_output = Command::new(k16_binary())
        .args([
            "runtime",
            "k16-startup",
            "-o",
            startup_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 runtime runs");
    assert!(
        runtime_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&runtime_output.stderr)
    );

    let link_output = Command::new(k16_binary())
        .args([
            "link",
            "--target",
            "program",
            startup_path.to_str().unwrap(),
            main_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 link runs");
    assert!(
        link_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&link_output.stderr)
    );

    let run_output = Command::new(k16_binary())
        .args(["run", output_path.to_str().unwrap()])
        .output()
        .expect("k16 run runs");

    assert!(
        run_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&run_output.stderr)
    );
    assert_eq!(
        String::from_utf8_lossy(&run_output.stdout),
        "signal=halt exit_status=42 debug_bytes=\n"
    );
    let stderr = String::from_utf8_lossy(&run_output.stderr);
    assert!(stderr.is_empty(), "stderr: {stderr}");
}

#[test]
fn k16_runtime_startup_does_not_hide_missing_helper_symbols() {
    let startup_path = temp_file("startup-helper-missing.o");
    let main_path = temp_file("main-needs-helper.o");
    let output_path = temp_file("missing-helper.k16e");
    fs::write(
        &main_path,
        k16_main_calling_undefined_helper("__k16_memcpy"),
    )
    .expect("main object writes");

    let runtime_output = Command::new(k16_binary())
        .args([
            "runtime",
            "k16-startup",
            "-o",
            startup_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 runtime runs");
    assert!(
        runtime_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&runtime_output.stderr)
    );

    let link_output = Command::new(k16_binary())
        .args([
            "link",
            "--target",
            "program",
            startup_path.to_str().unwrap(),
            main_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 link runs");

    assert!(!link_output.status.success());
    let stderr = String::from_utf8_lossy(&link_output.stderr);
    assert!(
        stderr.contains("unresolved K16 symbol `__k16_memcpy`"),
        "stderr: {stderr}"
    );
    assert!(!output_path.exists());
}

#[test]
fn k16_runtime_cpu_helpers_resolve_k16_cpu_symbols() {
    let startup_path = temp_file("cpu-helper-startup.o");
    let helper_path = temp_file("cpu-helpers.o");
    let main_path = temp_file("cpu-helper-main.o");
    let output_path = temp_file("cpu-helper-program.k16e");
    fs::write(
        &main_path,
        k16_main_calling_undefined_helper_with_arg("__k16_write_trap_vector", 0x1234),
    )
    .expect("main object writes");

    let startup_output = Command::new(k16_binary())
        .args([
            "runtime",
            "k16-startup",
            "-o",
            startup_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 runtime startup runs");
    assert!(
        startup_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&startup_output.stderr)
    );

    let helper_output = Command::new(k16_binary())
        .args([
            "runtime",
            "k16-cpu-helpers",
            "-o",
            helper_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 runtime cpu helpers runs");
    assert!(
        helper_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&helper_output.stderr)
    );

    let link_output = Command::new(k16_binary())
        .args([
            "link",
            "--target",
            "program",
            startup_path.to_str().unwrap(),
            main_path.to_str().unwrap(),
            helper_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 link runs");
    assert!(
        link_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&link_output.stderr)
    );

    let program = fs::read(output_path).expect("linked program reads");
    let mut handle = K16ComputerHandle::create_k16_bios_flash(
        &[0x01, 0x00],
        K16ArtifactTarget::DEFAULT_MEMORY_SIZE,
        1_000_000,
    )
    .expect("K16 computer creates");
    handle
        .exec_k16e_program_from_bytes(&program, 1_000_000)
        .expect("program installs");

    assert_eq!(
        handle.run_k16_until_signal().unwrap(),
        K16Signal::StepLimitExceeded
    );
    assert_eq!(handle.debug_output_bytes(), &[]);
    let snapshot_bytes = handle.snapshot_v1().expect("snapshot encodes");
    let snapshot = decode_snapshot_v1(&snapshot_bytes).expect("snapshot decodes");
    let ComputerCpuSnapshotRecord::K16 { cpu, .. } = &snapshot.cpus[0];
    assert_eq!(cpu.trap_vector, 0x1234);
}

#[test]
fn k16_runtime_syscall3_helper_loads_fourth_argument_from_stack() {
    let helper_object = k16_runtime::k16_cpu_helpers_object();
    let expected_words = [
        const4(14, 4),
        add(14, 15, 14)[0],
        add(14, 15, 14)[1],
        load32(4, 14),
        syscall(1),
        ret(),
    ];
    let expected_bytes = words_to_bytes(&expected_words);

    assert!(
        helper_object
            .windows(expected_bytes.len())
            .any(|window| window == expected_bytes.as_slice()),
        "syscall3 helper must load the current Rust backend's stack argument slot into r4 before executing syscall r1",
    );
}

#[test]
fn k16_runtime_trap_frame_helpers_use_saved_frame_csrs() {
    let helper_object = k16_runtime::k16_cpu_helpers_object();
    for symbol in [
        b"__k16_save_trap_frame".as_slice(),
        b"__k16_restore_trap_frame".as_slice(),
    ] {
        assert!(
            helper_object
                .windows(symbol.len())
                .any(|window| window == symbol),
            "trap frame helper object must export {}",
            String::from_utf8_lossy(symbol),
        );
    }
    let save_prefix = [
        const4(14, 0),
        write_csr(K16_CSR_TRAP_FRAME_INDEX, 14),
        read_csr(0, K16_CSR_TRAP_FRAME_REGISTER),
        const32(14, 0)[0],
        const32(14, 0)[1],
        const32(14, 0)[2],
        add(14, 1, 14)[0],
        add(14, 1, 14)[1],
        store32(14, 0),
        const4(14, 1),
        write_csr(K16_CSR_TRAP_FRAME_INDEX, 14),
        read_csr(0, K16_CSR_TRAP_FRAME_REGISTER),
    ];
    let restore_resume_pc = [
        const32(14, 64)[0],
        const32(14, 64)[1],
        const32(14, 64)[2],
        add(14, 1, 14)[0],
        add(14, 1, 14)[1],
        load32(0, 14),
        write_csr(K16_CSR_TRAP_RESUME_PC, 0),
    ];
    let restore_stack_pointer = [
        const32(14, 68)[0],
        const32(14, 68)[1],
        const32(14, 68)[2],
        add(14, 1, 14)[0],
        add(14, 1, 14)[1],
        load32(0, 14),
        write_csr(K16_CSR_TRAP_STACK_POINTER, 0),
    ];
    let restore_interrupt_enable = [
        const32(14, 72)[0],
        const32(14, 72)[1],
        const32(14, 72)[2],
        add(14, 1, 14)[0],
        add(14, 1, 14)[1],
        load32(0, 14),
        write_csr(K16_CSR_TRAP_INTERRUPT_ENABLE, 0),
    ];
    let restore_return = [
        const32(14, 0)[0],
        const32(14, 0)[1],
        const32(14, 0)[2],
        add(14, 1, 14)[0],
        add(14, 1, 14)[1],
        load32(0, 14),
        ret(),
    ];

    for (name, words) in [
        ("save prefix", save_prefix.as_slice()),
        ("restore resume pc", restore_resume_pc.as_slice()),
        ("restore stack pointer", restore_stack_pointer.as_slice()),
        (
            "restore interrupt enable",
            restore_interrupt_enable.as_slice(),
        ),
        ("restore return r0", restore_return.as_slice()),
    ] {
        let expected_bytes = words_to_bytes(words);
        assert!(
            helper_object
                .windows(expected_bytes.len())
                .any(|window| window == expected_bytes.as_slice()),
            "trap frame helper object must contain {name} sequence",
        );
    }
}

#[test]
fn k16_runtime_syscall3_helper_captures_stack_argument_at_runtime() {
    const ENTRY_PC: u32 = 0x8000;
    const TRAP_VECTOR: u32 = 0x8080;
    const HELPER_PC: u32 = 0x80a0;
    const STACK_TOP: u32 = K16ArtifactTarget::PROGRAM_STACK_TOP;

    let mut memory =
        MachineMemory::zeroed(K16ArtifactTarget::DEFAULT_MEMORY_SIZE).expect("memory creates");
    let mut caller = Vec::new();
    caller.extend(const32(14, TRAP_VECTOR));
    caller.push(write_csr(K16_CSR_TRAP_VECTOR, 14));
    caller.extend(const32(1, 0x40));
    caller.extend(const32(2, 0x11));
    caller.extend(const32(3, 0x22));
    caller.extend(const32(14, u32::MAX - 3));
    caller.extend(add(15, 15, 14));
    caller.extend(const32(14, 0x33));
    caller.push(store32(15, 14));
    caller.extend(const32(14, HELPER_PC));
    caller.push(call(14));
    caller.push(const4(14, 4));
    caller.extend(add(15, 15, 14));
    caller.push(halt());
    write_words(&mut memory, ENTRY_PC, &caller);
    write_words(
        &mut memory,
        HELPER_PC,
        &[
            const4(14, 4),
            add(14, 15, 14)[0],
            add(14, 15, 14)[1],
            load32(4, 14),
            syscall(1),
            ret(),
        ],
    );
    write_words(&mut memory, TRAP_VECTOR, &[const4(0, 7), iret()]);

    let mut cpu = K16Cpu::new_with_stack(ENTRY_PC, STACK_TOP);

    assert_eq!(
        cpu.run_until_signal(&mut memory, 64).expect("cpu runs"),
        K16Signal::Halt,
    );

    let snapshot = cpu.snapshot();
    assert_eq!(snapshot.trap_value, 0x40);
    assert_eq!(snapshot.trap_arg0, 0x11);
    assert_eq!(snapshot.trap_arg1, 0x22);
    assert_eq!(snapshot.trap_arg2, 0x33);
    assert_eq!(snapshot.registers[0], 7);
    assert_eq!(snapshot.registers[15], STACK_TOP);
}

#[test]
fn k16_runtime_fd_syscall_helpers_do_not_require_stack_arguments() {
    let helper_object = k16_runtime::k16_cpu_helpers_object();

    for (number, name) in [(7_u32, "write"), (8_u32, "read"), (10_u32, "open")] {
        let mut expected_words = Vec::new();
        expected_words.extend(push_register(1));
        expected_words.extend(push_register(2));
        expected_words.extend(push_register(3));
        expected_words.extend(push_register(4));
        expected_words.extend(push_scratch_register());
        expected_words.extend(const32(14, 0));
        expected_words.extend(add(4, 3, 14));
        expected_words.extend(add(3, 2, 14));
        expected_words.extend(add(2, 1, 14));
        expected_words.extend(const32(1, number));
        expected_words.push(syscall(1));
        expected_words.extend(pop_scratch_register());
        expected_words.extend(pop_register(4));
        expected_words.extend(pop_register(3));
        expected_words.extend(pop_register(2));
        expected_words.extend(pop_register(1));
        expected_words.push(ret());
        let expected_bytes = words_to_bytes(&expected_words);

        assert!(
            helper_object
                .windows(expected_bytes.len())
                .any(|window| window == expected_bytes.as_slice()),
            "{name} syscall helper must pass fd/ptr/len without a stack-passed fourth argument",
        );
    }
}

#[test]
fn k16_runtime_close_syscall_helper_uses_fixed_number_and_fd_argument() {
    let helper_object = k16_runtime::k16_cpu_helpers_object();

    assert_fixed_syscall1_helper(&helper_object, 11, "close");
}

#[test]
fn k16_runtime_heap_syscall_helpers_use_fixed_numbers_and_single_argument() {
    let helper_object = k16_runtime::k16_cpu_helpers_object();

    assert_fixed_syscall1_helper(&helper_object, 12, "brk");
    assert_fixed_syscall1_helper(&helper_object, 13, "sbrk");
}

fn assert_fixed_syscall1_helper(helper_object: &[u8], number: u32, name: &str) {
    let mut expected_words = Vec::new();
    expected_words.extend(push_register(1));
    expected_words.extend(push_register(2));
    expected_words.extend(push_scratch_register());
    expected_words.extend(const32(14, 0));
    expected_words.extend(add(2, 1, 14));
    expected_words.extend(const32(1, number));
    expected_words.push(syscall(1));
    expected_words.extend(pop_scratch_register());
    expected_words.extend(pop_register(2));
    expected_words.extend(pop_register(1));
    expected_words.push(ret());
    let expected_bytes = words_to_bytes(&expected_words);

    assert!(
        helper_object
            .windows(expected_bytes.len())
            .any(|window| window == expected_bytes.as_slice()),
        "{name} syscall helper must pass one argument with its fixed syscall number",
    );
}

#[test]
fn k16_runtime_wait_helper_returns_to_caller_after_resuming() {
    let startup_path = temp_file("wait-helper-startup.o");
    let helper_path = temp_file("wait-helpers.o");
    let main_path = temp_file("wait-helper-main.o");
    let output_path = temp_file("wait-helper-program.k16e");
    fs::write(&main_path, k16_main_waiting_once_then_returning_7()).expect("main object writes");

    let startup_output = Command::new(k16_binary())
        .args([
            "runtime",
            "k16-startup",
            "-o",
            startup_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 runtime startup runs");
    assert!(
        startup_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&startup_output.stderr)
    );

    let helper_output = Command::new(k16_binary())
        .args([
            "runtime",
            "k16-cpu-helpers",
            "-o",
            helper_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 runtime cpu helpers runs");
    assert!(
        helper_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&helper_output.stderr)
    );

    let link_output = Command::new(k16_binary())
        .args([
            "link",
            "--target",
            "program",
            startup_path.to_str().unwrap(),
            main_path.to_str().unwrap(),
            helper_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 link runs");
    assert!(
        link_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&link_output.stderr)
    );

    let program = fs::read(output_path).expect("linked program reads");
    let mut handle = K16ComputerHandle::create_k16_bios_flash(
        &[0x01, 0x00],
        K16ArtifactTarget::DEFAULT_MEMORY_SIZE,
        1_000_000,
    )
    .expect("K16 computer creates");
    handle
        .exec_k16e_program_from_bytes(&program, 1_000_000)
        .expect("program installs");

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Wait);
    let error = handle
        .run_k16_until_signal()
        .expect_err("startup EXIT syscall requires a kernel trap handler");
    assert!(
        error.contains("unhandled exception cause 5") && error.contains("syscall 6"),
        "error: {error}"
    );
    assert_eq!(handle.debug_output_bytes(), &[]);
}

#[test]
fn k16_runtime_memory_helpers_require_custom_k16_rustc() {
    let helper_path = temp_file("memory-helpers.o");

    let helper_output = Command::new(k16_binary())
        .args([
            "runtime",
            "k16-memory-helpers",
            "-o",
            helper_path.to_str().unwrap(),
        ])
        .env_remove("K16_RUSTC")
        .env_remove("K16_RUST_TARGET_JSON")
        .output()
        .expect("k16 runtime helpers runs");

    assert!(!helper_output.status.success());
    let stderr = String::from_utf8_lossy(&helper_output.stderr);
    assert!(
        stderr.contains("K16_RUSTC must point to a custom K16 rustc"),
        "stderr: {stderr}"
    );
    assert!(!helper_path.exists());
}

#[test]
fn k16_rust_wide_integer_libcalls_return_expected_exit_status() {
    let work_dir = temp_dir("u64-libcall");
    let src_dir = work_dir.join("src");
    fs::create_dir_all(&src_dir).expect("source directory creates");
    fs::write(
        work_dir.join("Cargo.toml"),
        r#"[package]
name = "k16-u64-libcall-repro"
version = "0.1.0"
edition = "2021"

[lib]
name = "k16_u64_libcall_repro"
path = "src/main.rs"
test = false
"#,
    )
    .expect("Cargo.toml writes");
    fs::write(
        src_dir.join("main.rs"),
        r#"#![no_std]

use core::panic::PanicInfo;

static U64_DIVIDEND: u64 = 0x0000_0002_0000_0000;
static U64_DIVISOR: u64 = 2;
static U64_REMAINDER_DIVIDEND: u64 = 0x0000_0001_0000_0007;
static U64_REMAINDER_DIVISOR: u64 = 3;
static I64_DIVIDEND: i64 = -9_000_000_000;
static I64_DIVISOR: i64 = 3;
static I64_REMAINDER_DIVIDEND: i64 = -4_294_967_300;
static I64_REMAINDER_DIVISOR: i64 = 7;
static SHIFT_VALUE: u64 = 0x0000_0001_0000_0001;
static SHIFT_HIGH_BIT: u64 = 0x8000_0000_0000_0000;
static SAR_VALUE: i64 = -4;
static SHIFT_33: u32 = 33;
static SHIFT_63: u32 = 63;
static SHIFT_1: u32 = 1;

#[no_mangle]
pub extern "C" fn main() -> i32 {
    let u64_divisor = read_u64(&U64_DIVISOR);
    if u64_divisor == 0 {
        return 7;
    }
    if read_u64(&U64_DIVIDEND) / u64_divisor != 0x0000_0001_0000_0000 {
        return 10;
    }
    let u64_remainder_divisor = read_u64(&U64_REMAINDER_DIVISOR);
    if u64_remainder_divisor == 0 {
        return 8;
    }
    if read_u64(&U64_REMAINDER_DIVIDEND) % u64_remainder_divisor != 2 {
        return 11;
    }
    let i64_divisor = read_i64(&I64_DIVISOR);
    if i64_divisor == 0 {
        return 9;
    }
    let i64_dividend = read_i64(&I64_DIVIDEND);
    if i64_dividend == i64::MIN && i64_divisor == -1 {
        return 18;
    }
    if i64_dividend / i64_divisor != -3_000_000_000 {
        return 12;
    }
    let i64_remainder_divisor = read_i64(&I64_REMAINDER_DIVISOR);
    if i64_remainder_divisor == 0 {
        return 17;
    }
    let i64_remainder_dividend = read_i64(&I64_REMAINDER_DIVIDEND);
    if i64_remainder_dividend == i64::MIN && i64_remainder_divisor == -1 {
        return 19;
    }
    if i64_remainder_dividend % i64_remainder_divisor != -1 {
        return 13;
    }
    if read_u64(&SHIFT_VALUE) << read_u32(&SHIFT_33) != 0x0000_0002_0000_0000 {
        return 14;
    }
    if read_u64(&SHIFT_HIGH_BIT) >> read_u32(&SHIFT_63) != 1 {
        return 15;
    }
    if read_i64(&SAR_VALUE) >> read_u32(&SHIFT_1) != -2 {
        return 16;
    }

    42
}

#[inline(never)]
fn read_u64(value: &u64) -> u64 {
    unsafe { core::ptr::read_volatile(value) }
}

#[inline(never)]
fn read_i64(value: &i64) -> i64 {
    unsafe { core::ptr::read_volatile(value) }
}

#[inline(never)]
fn read_u32(value: &u32) -> u32 {
    unsafe { core::ptr::read_volatile(value) }
}

#[panic_handler]
fn panic(_info: &PanicInfo<'_>) -> ! {
    loop {
        core::hint::spin_loop();
    }
}
"#,
    )
    .expect("main.rs writes");

    let cargo_output = Command::new(k16_cargo())
        .args([
            "rustc",
            "-Z",
            "build-std=core",
            "-Z",
            "json-target-spec",
            "--manifest-path",
            work_dir.join("Cargo.toml").to_str().unwrap(),
            "--target",
            k16_target_spec().to_str().unwrap(),
            "--target-dir",
            work_dir.join("target").to_str().unwrap(),
            "--lib",
            "--",
            "-C",
            "panic=abort",
            "-Copt-level=z",
            "-C",
            "relocation-model=static",
            "-Cjump-tables=no",
            "-Cdebuginfo=0",
            &format!("--emit=obj={}", work_dir.join("main.o").display()),
        ])
        .env("RUSTC", k16_rustc())
        .env("RUSTC_BOOTSTRAP", "1")
        .env("RUSTFLAGS", "-Copt-level=z -Cjump-tables=no -Cdebuginfo=0")
        .output()
        .expect("cargo rustc runs");
    assert!(
        cargo_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&cargo_output.stderr)
    );

    write_runtime_object("k16-startup", &work_dir.join("startup.o"));
    write_runtime_object("k16-memory-helpers", &work_dir.join("helpers.o"));

    let link_output = Command::new(k16_binary())
        .args([
            "link",
            "--target",
            "program",
            work_dir.join("startup.o").to_str().unwrap(),
            work_dir.join("main.o").to_str().unwrap(),
            work_dir.join("helpers.o").to_str().unwrap(),
            "-o",
            work_dir.join("main.kx").to_str().unwrap(),
        ])
        .output()
        .expect("k16 link runs");
    assert!(
        link_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&link_output.stderr)
    );

    let run_output = Command::new(k16_binary())
        .args(["run", work_dir.join("main.kx").to_str().unwrap()])
        .output()
        .expect("k16 run runs");
    assert!(
        run_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&run_output.stderr)
    );
    assert_eq!(
        String::from_utf8_lossy(&run_output.stdout),
        "signal=halt exit_status=42 debug_bytes=\n"
    );
}

#[test]
fn k16_rust_aggregate_smoke_returns_expected_exit_status() {
    let work_dir = temp_dir("aggregate-smoke");
    let src_dir = work_dir.join("src");
    fs::create_dir_all(&src_dir).expect("source directory creates");
    fs::write(
        work_dir.join("Cargo.toml"),
        r#"[package]
name = "k16-aggregate-smoke"
version = "0.1.0"
edition = "2021"

[lib]
name = "k16_aggregate_smoke"
path = "src/main.rs"
test = false
"#,
    )
    .expect("Cargo.toml writes");
    fs::write(
        src_dir.join("main.rs"),
        r#"#![no_std]

use core::panic::PanicInfo;

#[repr(C)]
#[derive(Clone, Copy)]
struct Pair {
    lo: u32,
    hi: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct Frame {
    regs: [u32; 16],
    resume_pc: u32,
    stack_pointer: u32,
    interrupt_enable: u32,
}

impl Frame {
    const fn zeroed() -> Self {
        Self {
            regs: [0; 16],
            resume_pc: 0,
            stack_pointer: 0,
            interrupt_enable: 0,
        }
    }
}

static PAIR: Pair = Pair { lo: 19, hi: 23 };
static FRAME: Frame = Frame {
    regs: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
    resume_pc: 0x1234_5678,
    stack_pointer: 0x0001_ffc0,
    interrupt_enable: 1,
};
static REG_INDEX: u32 = 14;

#[no_mangle]
pub extern "C" fn main() -> i32 {
    let pair = make_pair(read_u32(&PAIR.lo), read_u32(&PAIR.hi));
    if sum_pair(pair) != 42 {
        return 10;
    }

    let frame = read_frame(&FRAME);
    if check_frame_by_value(frame) != 42 {
        return 11;
    }

    let mut copied = Frame::zeroed();
    copy_frame(&mut copied, &frame);
    if copied.regs[14] != 14 {
        return 12;
    }
    if copied.stack_pointer != 0x0001_ffc0 {
        return 13;
    }

    if read_stack_pointer(&FRAME) != 0x0001_ffc0 {
        return 14;
    }
    if read_register(&FRAME, read_u32(&REG_INDEX) as usize) != 14 {
        return 15;
    }

    42
}

#[inline(never)]
fn make_pair(lo: u32, hi: u32) -> Pair {
    Pair { lo, hi }
}

#[inline(never)]
fn sum_pair(pair: Pair) -> u32 {
    pair.lo + pair.hi
}

#[inline(never)]
fn read_frame(frame: &Frame) -> Frame {
    unsafe { core::ptr::read_volatile(frame) }
}

#[inline(never)]
fn check_frame_by_value(frame: Frame) -> i32 {
    if frame.regs[14] != 14 {
        return 20;
    }
    if frame.resume_pc != 0x1234_5678 {
        return 21;
    }
    if frame.stack_pointer != 0x0001_ffc0 {
        return 22;
    }
    if frame.interrupt_enable != 1 {
        return 23;
    }
    42
}

#[inline(never)]
fn copy_frame(out: &mut Frame, frame: &Frame) {
    *out = read_frame(frame);
}

#[inline(never)]
fn read_stack_pointer(frame: &Frame) -> u32 {
    unsafe { core::ptr::read_volatile(&frame.stack_pointer) }
}

#[inline(never)]
fn read_register(frame: &Frame, index: usize) -> u32 {
    unsafe { core::ptr::read_volatile(frame.regs.as_ptr().add(index)) }
}

#[inline(never)]
fn read_u32(value: &u32) -> u32 {
    unsafe { core::ptr::read_volatile(value) }
}

#[panic_handler]
fn panic(_info: &PanicInfo<'_>) -> ! {
    loop {
        core::hint::spin_loop();
    }
}
"#,
    )
    .expect("main.rs writes");

    let cargo_output = Command::new(k16_cargo())
        .args([
            "rustc",
            "-Z",
            "build-std=core",
            "-Z",
            "json-target-spec",
            "--manifest-path",
            work_dir.join("Cargo.toml").to_str().unwrap(),
            "--target",
            k16_target_spec().to_str().unwrap(),
            "--target-dir",
            work_dir.join("target").to_str().unwrap(),
            "--lib",
            "--",
            "-C",
            "panic=abort",
            "-Copt-level=z",
            "-C",
            "relocation-model=static",
            "-Cjump-tables=no",
            "-Cdebuginfo=0",
            &format!("--emit=obj={}", work_dir.join("main.o").display()),
        ])
        .env("RUSTC", k16_rustc())
        .env("RUSTC_BOOTSTRAP", "1")
        .env("RUSTFLAGS", "-Copt-level=z -Cjump-tables=no -Cdebuginfo=0")
        .output()
        .expect("cargo rustc runs");
    assert!(
        cargo_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&cargo_output.stderr)
    );

    write_runtime_object("k16-startup", &work_dir.join("startup.o"));
    write_runtime_object("k16-memory-helpers", &work_dir.join("helpers.o"));

    let link_output = Command::new(k16_binary())
        .args([
            "link",
            "--target",
            "program",
            work_dir.join("startup.o").to_str().unwrap(),
            work_dir.join("main.o").to_str().unwrap(),
            work_dir.join("helpers.o").to_str().unwrap(),
            "-o",
            work_dir.join("main.kx").to_str().unwrap(),
        ])
        .output()
        .expect("k16 link runs");
    assert!(
        link_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&link_output.stderr)
    );

    let run_output = Command::new(k16_binary())
        .args(["run", work_dir.join("main.kx").to_str().unwrap()])
        .output()
        .expect("k16 run runs");
    assert!(
        run_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&run_output.stderr)
    );
    assert_eq!(
        String::from_utf8_lossy(&run_output.stdout),
        "signal=halt exit_status=42 debug_bytes=\n"
    );
}

#[test]
fn k16_rust_kraft_std_fd_cross_crate_smoke_reports_success_status() {
    let work_dir = temp_dir("kraft-std-fd-smoke");
    let src_dir = work_dir.join("src");
    fs::create_dir_all(&src_dir).expect("source directory creates");
    fs::write(
        work_dir.join("Cargo.toml"),
        format!(
            r#"[package]
name = "kraft-std-fd-smoke"
version = "0.1.0"
edition = "2021"

[dependencies]
kraft-std = {{ path = "{}" }}
"#,
            repo_root().join("rust/guest/kraft-std").display()
        ),
    )
    .expect("Cargo.toml writes");
    fs::write(
        src_dir.join("main.rs"),
        r#"#![no_std]
#![no_main]

use core::panic::PanicInfo;
use kraft_std::prelude::*;

const CONTROL_STATUS: *mut u32 = 0x1000_0000 as *mut u32;

#[no_mangle]
pub extern "C" fn _start() -> ! {
    let status = smoke_status();
    unsafe {
        core::ptr::write_volatile(CONTROL_STATUS, status);
    }
    loop {
        core::hint::spin_loop();
    }
}

#[inline(never)]
fn smoke_status() -> u32 {
    if io::stdout().write_all(b"hello").is_err() {
        return 10;
    }
    if io::stderr().write_all(b"!").is_err() {
        return 11;
    }

    let mut buffer = [0_u8; 4];
    match io::stdin().read(&mut buffer) {
        Ok(2) => {}
        Ok(_) => return 12,
        Err(_) => return 13,
    }

    42
}

#[panic_handler]
fn panic(_info: &PanicInfo<'_>) -> ! {
    loop {
        core::hint::spin_loop();
    }
}
"#,
    )
    .expect("main.rs writes");

    write_runtime_object("k16-memory-helpers", &work_dir.join("helpers.o"));
    fs::write(
        &work_dir.join("write-stub.o"),
        k16_write_syscall_stub_object(),
    )
    .expect("write syscall stub writes");
    fs::write(
        &work_dir.join("read-stub.o"),
        k16_read_syscall_stub_object(),
    )
    .expect("read syscall stub writes");

    let rustflags = format!(
        "-C linker={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg=--k16-target=bios -Copt-level=z -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no",
        k16_ld_binary(),
        work_dir.join("helpers.o").display(),
        work_dir.join("write-stub.o").display(),
        work_dir.join("read-stub.o").display(),
    );
    let cargo_output = Command::new(k16_cargo())
        .args([
            "rustc",
            "-Z",
            "build-std=core",
            "-Z",
            "json-target-spec",
            "--manifest-path",
            work_dir.join("Cargo.toml").to_str().unwrap(),
            "--target",
            k16_target_spec().to_str().unwrap(),
            "--target-dir",
            work_dir.join("target").to_str().unwrap(),
            "--bin",
            "kraft-std-fd-smoke",
            "--",
            "-C",
            "panic=abort",
            "-C",
            "relocation-model=static",
            "-Cjump-tables=no",
            "-Cdebuginfo=0",
            "-Cdebug-assertions=off",
            "-Coverflow-checks=off",
            "-Zub-checks=no",
        ])
        .env("RUSTC", k16_rustc())
        .env("RUSTC_BOOTSTRAP", "1")
        .env("RUSTFLAGS", rustflags)
        .output()
        .expect("cargo rustc runs");
    assert!(
        cargo_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&cargo_output.stderr)
    );

    let linked_bios = find_linked_rust_bin(&work_dir);
    let run_output = Command::new(k16_binary())
        .args(["run-bios", linked_bios.to_str().unwrap()])
        .output()
        .expect("k16 run-bios runs");
    assert!(
        run_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&run_output.stderr)
    );
    assert_eq!(
        String::from_utf8_lossy(&run_output.stdout),
        "signal=step-limit-exceeded status=42 panic_code=0 debug_text=\n"
    );
}

#[test]
fn k16_rust_hosted_std_main_println_exits_successfully() {
    let work_dir = temp_dir("hosted-std-main");
    let src_dir = work_dir.join("src");
    fs::create_dir_all(&src_dir).expect("source directory creates");
    fs::write(
        work_dir.join("Cargo.toml"),
        r#"[package]
name = "k16-hosted-std-main"
version = "0.1.0"
edition = "2021"

[[bin]]
name = "k16-hosted-std-main"
path = "src/main.rs"
test = false
"#,
    )
    .expect("Cargo.toml writes");
    fs::write(
        src_dir.join("main.rs"),
        r#"use std::io::Write;

fn main() {
    println!("hosted std hello");
    std::io::stdout().flush().unwrap();
}
"#,
    )
    .expect("main.rs writes");

    write_runtime_object_for_target("k16-startup", "program", &work_dir.join("startup.o"));
    write_k16_debug_write_syscall_stub(&work_dir);
    write_k16_sbrk_syscall_stub(&work_dir);

    let rustflags = format!(
        "-C linker={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg=--k16-target=program -Cpasses=lower-atomic -Copt-level=z -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no",
        k16_ld_binary(),
        work_dir.join("startup.o").display(),
        work_dir.join("write-stub.o").display(),
        work_dir.join("sbrk-stub.o").display(),
        work_dir.join("abort.o").display(),
    );
    let cargo_output = Command::new(k16_cargo())
        .args([
            "rustc",
            "-Z",
            "build-std=std,panic_abort",
            "-Z",
            "build-std-features=compiler-builtins-mem",
            "-Z",
            "json-target-spec",
            "--manifest-path",
            work_dir.join("Cargo.toml").to_str().unwrap(),
            "--target",
            k16_target_spec().to_str().unwrap(),
            "--target-dir",
            work_dir.join("target").to_str().unwrap(),
            "--bin",
            "k16-hosted-std-main",
            "--",
            "-C",
            "panic=abort",
            "-C",
            "relocation-model=static",
            "-Cjump-tables=no",
            "-Cdebuginfo=0",
            "-Cdebug-assertions=off",
            "-Coverflow-checks=off",
            "-Zub-checks=no",
        ])
        .env("RUSTC", k16_rustc())
        .env("RUSTC_BOOTSTRAP", "1")
        .env("RUSTFLAGS", rustflags)
        .output()
        .expect("cargo rustc runs");
    assert!(
        cargo_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&cargo_output.stderr)
    );

    let linked_program = find_linked_rust_bin(&work_dir);
    let run_output = Command::new(k16_binary())
        .args(["run", linked_program.to_str().unwrap()])
        .output()
        .expect("k16 run runs");
    assert!(
        run_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&run_output.stderr)
    );
    let stdout = String::from_utf8_lossy(&run_output.stdout);
    assert!(
        stdout.starts_with("signal=halt exit_status=0 debug_bytes="),
        "stdout: {stdout}"
    );
    assert!(
        stdout.ends_with("686f73746564207374642068656c6c6f0a\n"),
        "stdout: {stdout}"
    );
}

#[test]
fn k16_rust_hosted_std_heap_uses_sbrk_syscall() {
    let work_dir = temp_dir("hosted-std-sbrk-heap");
    let src_dir = work_dir.join("src");
    fs::create_dir_all(&src_dir).expect("source directory creates");
    fs::write(
        work_dir.join("Cargo.toml"),
        r#"[package]
name = "k16-hosted-std-sbrk-heap"
version = "0.1.0"
edition = "2021"

[[bin]]
name = "k16-hosted-std-sbrk-heap"
path = "src/main.rs"
test = false
"#,
    )
    .expect("Cargo.toml writes");
    fs::write(
        src_dir.join("main.rs"),
        r#"use std::io::Write;

fn main() {
    let mut values = Vec::new();
    for value in 0..5 {
        values.push(value * 2);
    }
    let message = format!("sbrk heap {} {}\n", values.len(), values.iter().sum::<i32>());
    print!("{message}");
    std::io::stdout().flush().unwrap();
}
"#,
    )
    .expect("main.rs writes");

    write_runtime_object_for_target("k16-startup", "program", &work_dir.join("startup.o"));
    write_k16_debug_write_syscall_stub(&work_dir);
    write_k16_sbrk_syscall_stub(&work_dir);

    let rustflags = format!(
        "-C linker={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg=--k16-target=program -Cpasses=lower-atomic -Copt-level=z -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no",
        k16_ld_binary(),
        work_dir.join("startup.o").display(),
        work_dir.join("write-stub.o").display(),
        work_dir.join("sbrk-stub.o").display(),
        work_dir.join("abort.o").display(),
    );
    let cargo_output = Command::new(k16_cargo())
        .args([
            "rustc",
            "-Z",
            "build-std=std,panic_abort",
            "-Z",
            "build-std-features=compiler-builtins-mem",
            "-Z",
            "json-target-spec",
            "--manifest-path",
            work_dir.join("Cargo.toml").to_str().unwrap(),
            "--target",
            k16_target_spec().to_str().unwrap(),
            "--target-dir",
            work_dir.join("target").to_str().unwrap(),
            "--bin",
            "k16-hosted-std-sbrk-heap",
            "--",
            "-C",
            "panic=abort",
            "-C",
            "relocation-model=static",
            "-Cjump-tables=no",
            "-Cdebuginfo=0",
            "-Cdebug-assertions=off",
            "-Coverflow-checks=off",
            "-Zub-checks=no",
        ])
        .env("RUSTC", k16_rustc())
        .env("RUSTC_BOOTSTRAP", "1")
        .env("RUSTFLAGS", rustflags)
        .output()
        .expect("cargo rustc runs");
    assert!(
        cargo_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&cargo_output.stderr)
    );

    let linked_program = find_linked_rust_bin(&work_dir);
    let run_output = Command::new(k16_binary())
        .args(["run", linked_program.to_str().unwrap()])
        .output()
        .expect("k16 run runs");
    assert!(
        run_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&run_output.stderr)
    );
    let stdout = String::from_utf8_lossy(&run_output.stdout);
    assert!(
        stdout.starts_with("signal=halt exit_status=0 debug_bytes=53"),
        "stdout: {stdout}"
    );
    assert!(
        stdout.ends_with("7362726b206865617020352032300a\n"),
        "stdout: {stdout}"
    );
}

#[test]
fn k16_rust_hosted_std_fs_reads_file_through_syscalls() {
    let work_dir = temp_dir("hosted-std-fs-read");
    let src_dir = work_dir.join("src");
    fs::create_dir_all(&src_dir).expect("source directory creates");
    fs::write(
        work_dir.join("Cargo.toml"),
        r#"[package]
name = "k16-hosted-std-fs-read"
version = "0.1.0"
edition = "2021"

[[bin]]
name = "k16-hosted-std-fs-read"
path = "src/main.rs"
test = false
"#,
    )
    .expect("Cargo.toml writes");
    fs::write(
        src_dir.join("main.rs"),
        r#"use std::io::Write;

fn main() {
    let text = std::fs::read_to_string("/etc/motd").unwrap();
    print!("fs {text}");
    std::io::stdout().flush().unwrap();
}
"#,
    )
    .expect("main.rs writes");

    write_runtime_object_for_target("k16-startup", "program", &work_dir.join("startup.o"));
    write_k16_debug_write_syscall_stub(&work_dir);
    write_k16_sbrk_syscall_stub(&work_dir);
    write_k16_fs_read_syscall_stub(&work_dir);

    let rustflags = format!(
        "-C linker={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg=--k16-target=program -Cpasses=lower-atomic -Copt-level=z -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no",
        k16_ld_binary(),
        work_dir.join("startup.o").display(),
        work_dir.join("write-stub.o").display(),
        work_dir.join("sbrk-stub.o").display(),
        work_dir.join("fs-open-close-stub.o").display(),
        work_dir.join("fs-read-stub.o").display(),
        work_dir.join("abort.o").display(),
    );
    let cargo_output = Command::new(k16_cargo())
        .args([
            "rustc",
            "-Z",
            "build-std=std,panic_abort",
            "-Z",
            "build-std-features=compiler-builtins-mem",
            "-Z",
            "json-target-spec",
            "--manifest-path",
            work_dir.join("Cargo.toml").to_str().unwrap(),
            "--target",
            k16_target_spec().to_str().unwrap(),
            "--target-dir",
            work_dir.join("target").to_str().unwrap(),
            "--bin",
            "k16-hosted-std-fs-read",
            "--",
            "-C",
            "panic=abort",
            "-C",
            "relocation-model=static",
            "-Cjump-tables=no",
            "-Cdebuginfo=0",
            "-Cdebug-assertions=off",
            "-Coverflow-checks=off",
            "-Zub-checks=no",
        ])
        .env("RUSTC", k16_rustc())
        .env("RUSTC_BOOTSTRAP", "1")
        .env("RUSTFLAGS", rustflags)
        .output()
        .expect("cargo rustc runs");
    assert!(
        cargo_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&cargo_output.stderr)
    );

    let linked_program = find_linked_rust_bin(&work_dir);
    let run_output = Command::new(k16_binary())
        .args(["run", linked_program.to_str().unwrap()])
        .output()
        .expect("k16 run runs");
    assert!(
        run_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&run_output.stderr)
    );
    let stdout = String::from_utf8_lossy(&run_output.stdout);
    assert!(
        stdout.starts_with("signal=halt exit_status=0 debug_bytes="),
        "stdout: {stdout}"
    );
    assert!(stdout.contains("4f"), "stdout: {stdout}");
    assert!(stdout.contains("52"), "stdout: {stdout}");
    assert!(stdout.contains("43"), "stdout: {stdout}");
    assert!(
        stdout.ends_with("6673204b726166744f53204d4f54440a\n"),
        "stdout: {stdout}"
    );
}

#[test]
fn k16_rust_hosted_std_env_args_reads_k16_argv_table() {
    let work_dir = temp_dir("hosted-std-env-args");
    let src_dir = work_dir.join("src");
    fs::create_dir_all(&src_dir).expect("source directory creates");
    fs::write(
        work_dir.join("Cargo.toml"),
        r#"[package]
name = "k16-hosted-std-env-args"
version = "0.1.0"
edition = "2021"

[[bin]]
name = "k16-hosted-std-env-args"
path = "src/main.rs"
test = false
"#,
    )
    .expect("Cargo.toml writes");
    fs::write(
        src_dir.join("main.rs"),
        r#"fn main() {
    for arg in std::env::args() {
        print!("[{arg}]");
    }
    println!();
}
"#,
    )
    .expect("main.rs writes");

    fs::write(
        work_dir.join("startup.o"),
        k16_startup_with_argv_object(&["alpha", "beta"]),
    )
    .expect("argv startup object writes");
    write_k16_debug_write_syscall_stub(&work_dir);
    write_k16_sbrk_syscall_stub(&work_dir);

    let rustflags = format!(
        "-C linker={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg=--k16-target=program -Cpasses=lower-atomic -Copt-level=z -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no",
        k16_ld_binary(),
        work_dir.join("startup.o").display(),
        work_dir.join("write-stub.o").display(),
        work_dir.join("sbrk-stub.o").display(),
        work_dir.join("abort.o").display(),
    );
    let cargo_output = Command::new(k16_cargo())
        .args([
            "rustc",
            "-Z",
            "build-std=std,panic_abort",
            "-Z",
            "build-std-features=compiler-builtins-mem",
            "-Z",
            "json-target-spec",
            "--manifest-path",
            work_dir.join("Cargo.toml").to_str().unwrap(),
            "--target",
            k16_target_spec().to_str().unwrap(),
            "--target-dir",
            work_dir.join("target").to_str().unwrap(),
            "--bin",
            "k16-hosted-std-env-args",
            "--",
            "-C",
            "panic=abort",
            "-C",
            "relocation-model=static",
            "-Cjump-tables=no",
            "-Cdebuginfo=0",
            "-Cdebug-assertions=off",
            "-Coverflow-checks=off",
            "-Zub-checks=no",
        ])
        .env("RUSTC", k16_rustc())
        .env("RUSTC_BOOTSTRAP", "1")
        .env("RUSTFLAGS", rustflags)
        .output()
        .expect("cargo rustc runs");
    assert!(
        cargo_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&cargo_output.stderr)
    );

    let linked_program = find_linked_rust_bin(&work_dir);
    let run_output = Command::new(k16_binary())
        .args(["run", linked_program.to_str().unwrap()])
        .output()
        .expect("k16 run runs");
    assert!(
        run_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&run_output.stderr)
    );
    let stdout = String::from_utf8_lossy(&run_output.stdout);
    assert!(
        stdout.starts_with("signal=halt exit_status=0 debug_bytes="),
        "stdout: {stdout}"
    );
    assert!(
        stdout.ends_with("5b616c7068615d5b626574615d0a\n"),
        "stdout: {stdout}"
    );
}

#[test]
fn k16_rust_hosted_std_cat_reads_argv_file() {
    let work_dir = temp_dir("hosted-std-cat");
    let src_dir = work_dir.join("src");
    fs::create_dir_all(&src_dir).expect("source directory creates");
    fs::write(
        work_dir.join("Cargo.toml"),
        r#"[package]
name = "k16-hosted-std-cat"
version = "0.1.0"
edition = "2021"

[[bin]]
name = "k16-hosted-std-cat"
path = "src/main.rs"
test = false
"#,
    )
    .expect("Cargo.toml writes");
    fs::write(
        src_dir.join("main.rs"),
        r#"fn main() {
    for path in std::env::args().skip(1) {
        let text = std::fs::read_to_string(path).unwrap();
        print!("{text}");
    }
}
"#,
    )
    .expect("main.rs writes");

    fs::write(
        work_dir.join("startup.o"),
        k16_startup_with_argv_object(&["hosted-cat", "/etc/motd"]),
    )
    .expect("argv startup object writes");
    write_k16_debug_write_syscall_stub(&work_dir);
    write_k16_sbrk_syscall_stub(&work_dir);
    write_k16_fs_read_syscall_stub(&work_dir);

    let rustflags = format!(
        "-C linker={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg=--k16-target=program -Cpasses=lower-atomic -Copt-level=z -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no",
        k16_ld_binary(),
        work_dir.join("startup.o").display(),
        work_dir.join("write-stub.o").display(),
        work_dir.join("sbrk-stub.o").display(),
        work_dir.join("fs-open-close-stub.o").display(),
        work_dir.join("fs-read-stub.o").display(),
        work_dir.join("abort.o").display(),
    );
    let cargo_output = Command::new(k16_cargo())
        .args([
            "rustc",
            "-Z",
            "build-std=std,panic_abort",
            "-Z",
            "build-std-features=compiler-builtins-mem",
            "-Z",
            "json-target-spec",
            "--manifest-path",
            work_dir.join("Cargo.toml").to_str().unwrap(),
            "--target",
            k16_target_spec().to_str().unwrap(),
            "--target-dir",
            work_dir.join("target").to_str().unwrap(),
            "--bin",
            "k16-hosted-std-cat",
            "--",
            "-C",
            "panic=abort",
            "-C",
            "relocation-model=static",
            "-Cjump-tables=no",
            "-Cdebuginfo=0",
            "-Cdebug-assertions=off",
            "-Coverflow-checks=off",
            "-Zub-checks=no",
        ])
        .env("RUSTC", k16_rustc())
        .env("RUSTC_BOOTSTRAP", "1")
        .env("RUSTFLAGS", rustflags)
        .output()
        .expect("cargo rustc runs");
    assert!(
        cargo_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&cargo_output.stderr)
    );

    let linked_program = find_linked_rust_bin(&work_dir);
    let run_output = Command::new(k16_binary())
        .args(["run", linked_program.to_str().unwrap()])
        .output()
        .expect("k16 run runs");
    assert!(
        run_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&run_output.stderr)
    );
    let stdout = String::from_utf8_lossy(&run_output.stdout);
    assert!(
        stdout.starts_with("signal=halt exit_status=0 debug_bytes="),
        "stdout: {stdout}"
    );
    assert!(
        stdout.ends_with("4b726166744f53204d4f54440a\n"),
        "stdout: {stdout}"
    );
}

#[test]
fn k16_rust_hosted_std_fs_writes_file_through_syscalls() {
    let work_dir = temp_dir("hosted-std-fs-write");
    let src_dir = work_dir.join("src");
    fs::create_dir_all(&src_dir).expect("source directory creates");
    fs::write(
        work_dir.join("Cargo.toml"),
        r#"[package]
name = "k16-hosted-std-fs-write"
version = "0.1.0"
edition = "2021"

[[bin]]
name = "k16-hosted-std-fs-write"
path = "src/main.rs"
test = false
"#,
    )
    .expect("Cargo.toml writes");
    fs::write(
        src_dir.join("main.rs"),
        r#"use std::io::Write;

fn main() {
    std::fs::write("/tmp/out", b"written from std\n").unwrap();
    print!("done\n");
    std::io::stdout().flush().unwrap();
}
"#,
    )
    .expect("main.rs writes");

    write_runtime_object_for_target("k16-startup", "program", &work_dir.join("startup.o"));
    write_k16_debug_and_fs_write_syscall_stub(&work_dir);
    write_k16_sbrk_syscall_stub(&work_dir);

    let rustflags = format!(
        "-C linker={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg=--k16-target=program -Cpasses=lower-atomic -Copt-level=z -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no",
        k16_ld_binary(),
        work_dir.join("startup.o").display(),
        work_dir.join("write-stub.o").display(),
        work_dir.join("sbrk-stub.o").display(),
        work_dir.join("abort.o").display(),
    );
    let cargo_output = Command::new(k16_cargo())
        .args([
            "rustc",
            "-Z",
            "build-std=std,panic_abort",
            "-Z",
            "build-std-features=compiler-builtins-mem",
            "-Z",
            "json-target-spec",
            "--manifest-path",
            work_dir.join("Cargo.toml").to_str().unwrap(),
            "--target",
            k16_target_spec().to_str().unwrap(),
            "--target-dir",
            work_dir.join("target").to_str().unwrap(),
            "--bin",
            "k16-hosted-std-fs-write",
            "--",
            "-C",
            "panic=abort",
            "-C",
            "relocation-model=static",
            "-Cjump-tables=no",
            "-Cdebuginfo=0",
            "-Cdebug-assertions=off",
            "-Coverflow-checks=off",
            "-Zub-checks=no",
        ])
        .env("RUSTC", k16_rustc())
        .env("RUSTC_BOOTSTRAP", "1")
        .env("RUSTFLAGS", rustflags)
        .output()
        .expect("cargo rustc runs");
    assert!(
        cargo_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&cargo_output.stderr)
    );

    let linked_program = find_linked_rust_bin(&work_dir);
    let run_output = Command::new(k16_binary())
        .args(["run", linked_program.to_str().unwrap()])
        .output()
        .expect("k16 run runs");
    assert!(
        run_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&run_output.stderr)
    );
    let stdout = String::from_utf8_lossy(&run_output.stdout);
    assert!(
        stdout.starts_with("signal=halt exit_status=0 debug_bytes="),
        "stdout: {stdout}"
    );
    assert!(
        stdout.ends_with("4f467772697474656e2066726f6d207374640a4353646f6e650a\n"),
        "stdout: {stdout}"
    );
}

#[test]
fn k16_rust_hosted_std_fs_metadata_and_read_dir_use_syscalls() {
    let work_dir = temp_dir("hosted-std-fs-metadata-read-dir");
    let src_dir = work_dir.join("src");
    fs::create_dir_all(&src_dir).expect("source directory creates");
    fs::write(
        work_dir.join("Cargo.toml"),
        r#"[package]
name = "k16-hosted-std-fs-metadata-read-dir"
version = "0.1.0"
edition = "2021"

[[bin]]
name = "k16-hosted-std-fs-metadata-read-dir"
path = "src/main.rs"
test = false
"#,
    )
    .expect("Cargo.toml writes");
    fs::write(
        src_dir.join("main.rs"),
        r#"use std::fmt::Write as _;
use std::io::Write as _;

fn main() {
    let metadata = std::fs::metadata("/bin/cat.kx").unwrap();
    let mut entries: Vec<_> = std::fs::read_dir("/bin")
        .unwrap()
        .map(|entry| {
            let entry = entry.unwrap();
            let file_type = entry.file_type().unwrap();
            let kind = if file_type.is_file() {
                "f"
            } else if file_type.is_dir() {
                "d"
            } else {
                "?"
            };
            format!("{}:{kind}", entry.file_name().to_string_lossy())
        })
        .collect();
    entries.sort();

    let mut line = String::new();
    write!(&mut line, "meta {} ", metadata.is_file()).unwrap();
    for entry in entries {
        write!(&mut line, "{entry};").unwrap();
    }
    line.push('\n');
    print!("{line}");
    std::io::stdout().flush().unwrap();
}
"#,
    )
    .expect("main.rs writes");

    write_runtime_object_for_target("k16-startup", "program", &work_dir.join("startup.o"));
    write_k16_debug_write_syscall_stub(&work_dir);
    write_k16_sbrk_syscall_stub(&work_dir);
    write_k16_fs_metadata_read_dir_syscall_stub(&work_dir);

    let rustflags = format!(
        "-C linker={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg={} -C link-arg=--k16-target=program -Cpasses=lower-atomic -Copt-level=z -Cjump-tables=no -Cdebuginfo=0 -Cdebug-assertions=off -Coverflow-checks=off -Zub-checks=no",
        k16_ld_binary(),
        work_dir.join("startup.o").display(),
        work_dir.join("write-stub.o").display(),
        work_dir.join("sbrk-stub.o").display(),
        work_dir.join("fs-read-dir-stub.o").display(),
        work_dir.join("fs-stat-stub.o").display(),
        work_dir.join("abort.o").display(),
    );
    let cargo_output = Command::new(k16_cargo())
        .args([
            "rustc",
            "-Z",
            "build-std=std,panic_abort",
            "-Z",
            "build-std-features=compiler-builtins-mem",
            "-Z",
            "json-target-spec",
            "--manifest-path",
            work_dir.join("Cargo.toml").to_str().unwrap(),
            "--target",
            k16_target_spec().to_str().unwrap(),
            "--target-dir",
            work_dir.join("target").to_str().unwrap(),
            "--bin",
            "k16-hosted-std-fs-metadata-read-dir",
            "--",
            "-C",
            "panic=abort",
            "-C",
            "relocation-model=static",
            "-Cjump-tables=no",
            "-Cdebuginfo=0",
            "-Cdebug-assertions=off",
            "-Coverflow-checks=off",
            "-Zub-checks=no",
        ])
        .env("RUSTC", k16_rustc())
        .env("RUSTC_BOOTSTRAP", "1")
        .env("RUSTFLAGS", rustflags)
        .output()
        .expect("cargo rustc runs");
    assert!(
        cargo_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&cargo_output.stderr)
    );

    let linked_program = find_linked_rust_bin(&work_dir);
    let run_output = Command::new(k16_binary())
        .args(["run", linked_program.to_str().unwrap()])
        .output()
        .expect("k16 run runs");
    assert!(
        run_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&run_output.stderr)
    );
    let stdout = String::from_utf8_lossy(&run_output.stdout);
    assert!(
        stdout.starts_with("signal=halt exit_status=0 debug_bytes="),
        "stdout: {stdout}"
    );
    assert!(
        stdout.contains("6d6574612074727565206361742e6b783a663b73682e6b783a663b0a"),
        "stdout: {stdout}"
    );
}

fn write_k16_debug_write_syscall_stub(work_dir: &Path) {
    let stub_dir = work_dir.join("write-stub");
    let src_dir = stub_dir.join("src");
    fs::create_dir_all(&src_dir).expect("write stub source directory creates");
    fs::write(
        stub_dir.join("Cargo.toml"),
        r#"[package]
name = "k16-debug-write-syscall-stub"
version = "0.1.0"
edition = "2021"

[lib]
name = "k16_debug_write_syscall_stub"
path = "src/lib.rs"
test = false
"#,
    )
    .expect("write stub Cargo.toml writes");
    fs::write(
        src_dir.join("lib.rs"),
        r#"#![no_std]

const DEBUG_WRITE: *mut u8 = 0x1000_0100 as *mut u8;
const FD_STDOUT: u32 = 1;
const FD_STDERR: u32 = 2;
const ERROR_BAD_FD: u32 = 0xffff_fff7;

#[no_mangle]
pub extern "C" fn __k16_write_syscall(fd: u32, ptr: *const u8, len: u32) -> u32 {
    if fd != FD_STDOUT && fd != FD_STDERR {
        return ERROR_BAD_FD;
    }
    let mut index = 0;
    while index < len {
        unsafe {
            let byte = core::ptr::read_volatile(ptr.add(index as usize));
            core::ptr::write_volatile(DEBUG_WRITE, byte);
        }
        index += 1;
    }
    len
}

"#,
    )
    .expect("write stub lib.rs writes");
    fs::write(work_dir.join("abort.o"), k16_abort_object()).expect("abort object writes");
    let cargo_output = Command::new(k16_cargo())
        .args([
            "rustc",
            "-Z",
            "build-std=core",
            "-Z",
            "json-target-spec",
            "--manifest-path",
            stub_dir.join("Cargo.toml").to_str().unwrap(),
            "--target",
            k16_target_spec().to_str().unwrap(),
            "--target-dir",
            stub_dir.join("target").to_str().unwrap(),
            "--lib",
            "--",
            "-C",
            "panic=abort",
            "-Copt-level=z",
            "-C",
            "relocation-model=static",
            "-Cjump-tables=no",
            "-Cdebuginfo=0",
            &format!("--emit=obj={}", work_dir.join("write-stub.o").display()),
        ])
        .env("RUSTC", k16_rustc())
        .env("RUSTC_BOOTSTRAP", "1")
        .env(
            "RUSTFLAGS",
            "-Cpasses=lower-atomic -Copt-level=z -Cjump-tables=no -Cdebuginfo=0",
        )
        .output()
        .expect("write stub cargo rustc runs");
    assert!(
        cargo_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&cargo_output.stderr)
    );
}

fn write_k16_debug_and_fs_write_syscall_stub(work_dir: &Path) {
    let stub_dir = work_dir.join("write-stub");
    let src_dir = stub_dir.join("src");
    fs::create_dir_all(&src_dir).expect("write stub source directory creates");
    fs::write(
        stub_dir.join("Cargo.toml"),
        r#"[package]
name = "k16-debug-and-fs-write-syscall-stub"
version = "0.1.0"
edition = "2021"

[lib]
name = "k16_debug_and_fs_write_syscall_stub"
path = "src/lib.rs"
test = false
"#,
    )
    .expect("write stub Cargo.toml writes");
    fs::write(
        src_dir.join("lib.rs"),
        r#"#![no_std]

const DEBUG_WRITE: *mut u8 = 0x1000_0100 as *mut u8;
const FD_STDOUT: u32 = 1;
const FD_STDERR: u32 = 2;
const FILE_FD: u32 = 3;
const OPEN_WRITE_CREATE_TRUNCATE: u32 = 7;
const ERROR_BAD_FD: u32 = 0xffff_fff7;
const ERROR_NOT_FOUND: u32 = 0xffff_fffb;
const ERROR_UNSUPPORTED: u32 = 0xffff_fffc;
const OUT_PATH_LEN: u32 = 8;

fn debug(byte: u8) {
    unsafe {
        core::ptr::write_volatile(DEBUG_WRITE, byte);
    }
}

fn out_path_byte(index: u32) -> u8 {
    match index {
        0 => b'/',
        1 => b't',
        2 => b'm',
        3 => b'p',
        4 => b'/',
        5 => b'o',
        6 => b'u',
        7 => b't',
        _ => 0,
    }
}

#[no_mangle]
pub extern "C" fn __k16_open_syscall(ptr: *const u8, len: u32, flags: u32) -> u32 {
    debug(b'O');
    if flags != OPEN_WRITE_CREATE_TRUNCATE {
        return ERROR_UNSUPPORTED;
    }
    if len != OUT_PATH_LEN {
        return ERROR_NOT_FOUND;
    }
    let mut index = 0;
    while index < len {
        let byte = unsafe { core::ptr::read_volatile(ptr.add(index as usize)) };
        if byte != out_path_byte(index) {
            return ERROR_NOT_FOUND;
        }
        index += 1;
    }
    FILE_FD
}

#[no_mangle]
pub extern "C" fn __k16_write_syscall(fd: u32, ptr: *const u8, len: u32) -> u32 {
    if fd != FD_STDOUT && fd != FD_STDERR && fd != FILE_FD {
        return ERROR_BAD_FD;
    }
    if fd == FILE_FD {
        debug(b'F');
    }
    let mut index = 0;
    while index < len {
        let byte = unsafe { core::ptr::read_volatile(ptr.add(index as usize)) };
        debug(byte);
        index += 1;
    }
    len
}

#[no_mangle]
pub extern "C" fn __k16_close_syscall(fd: u32) -> u32 {
    debug(b'C');
    if fd == FILE_FD { 0 } else { ERROR_BAD_FD }
}

"#,
    )
    .expect("write stub lib.rs writes");
    fs::write(work_dir.join("abort.o"), k16_abort_object()).expect("abort object writes");
    let cargo_output = Command::new(k16_cargo())
        .args([
            "rustc",
            "-Z",
            "build-std=core",
            "-Z",
            "json-target-spec",
            "--manifest-path",
            stub_dir.join("Cargo.toml").to_str().unwrap(),
            "--target",
            k16_target_spec().to_str().unwrap(),
            "--target-dir",
            stub_dir.join("target").to_str().unwrap(),
            "--lib",
            "--",
            "-C",
            "panic=abort",
            "-Copt-level=z",
            "-C",
            "relocation-model=static",
            "-Cjump-tables=no",
            "-Cdebuginfo=0",
            &format!("--emit=obj={}", work_dir.join("write-stub.o").display()),
        ])
        .env("RUSTC", k16_rustc())
        .env("RUSTC_BOOTSTRAP", "1")
        .env(
            "RUSTFLAGS",
            "-Cpasses=lower-atomic -Copt-level=z -Cjump-tables=no -Cdebuginfo=0",
        )
        .output()
        .expect("write stub cargo rustc runs");
    assert!(
        cargo_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&cargo_output.stderr)
    );
}

fn write_k16_fs_metadata_read_dir_syscall_stub(work_dir: &Path) {
    let stub_dir = work_dir.join("fs-metadata-read-dir-stub");
    let src_dir = stub_dir.join("src");
    fs::create_dir_all(&src_dir).expect("fs metadata/read_dir stub source directory creates");
    fs::write(
        stub_dir.join("Cargo.toml"),
        r#"[package]
name = "k16-fs-metadata-read-dir-syscall-stub"
version = "0.1.0"
edition = "2021"

[lib]
name = "k16_fs_metadata_read_dir_syscall_stub"
path = "src/lib.rs"
test = false
"#,
    )
    .expect("fs metadata/read_dir stub Cargo.toml writes");
    fs::write(
        src_dir.join("lib.rs"),
        r#"#![no_std]

const READ_DIR_REQUEST_MAGIC: u32 = 0x5249_4452;
const ERROR_NOT_FOUND: u32 = 0xffff_fffb;
const ERROR_INVALID: u32 = 0xffff_fffd;
const BIN_PATH_LEN: u32 = 4;
const LISTING: &[u8] = b"cat.kx\nsh.kx\n";

fn read_u32(ptr: *const u8, offset: usize) -> u32 {
    let b0 = unsafe { core::ptr::read_volatile(ptr.add(offset)) };
    let b1 = unsafe { core::ptr::read_volatile(ptr.add(offset + 1)) };
    let b2 = unsafe { core::ptr::read_volatile(ptr.add(offset + 2)) };
    let b3 = unsafe { core::ptr::read_volatile(ptr.add(offset + 3)) };
    u32::from_le_bytes([b0, b1, b2, b3])
}

fn path_byte(path: u32, index: u32) -> u8 {
    match (path, index) {
        (0, 0) => b'/',
        (0, 1) => b'b',
        (0, 2) => b'i',
        (0, 3) => b'n',
        _ => 0,
    }
}

fn path_matches(ptr: *const u8, len: u32, path: u32) -> bool {
    let expected_len = match path {
        0 => BIN_PATH_LEN,
        _ => return false,
    };
    if len != expected_len {
        return false;
    }
    let mut index = 0;
    while index < len {
        let byte = unsafe { core::ptr::read_volatile(ptr.add(index as usize)) };
        if byte != path_byte(path, index) {
            return false;
        }
        index += 1;
    }
    true
}

#[no_mangle]
pub extern "C" fn __k16_read_dir_syscall(ptr: *const u8, len: u32) -> u32 {
    if len < 16 || read_u32(ptr, 0) != READ_DIR_REQUEST_MAGIC {
        return ERROR_INVALID;
    }
    let path_len = read_u32(ptr, 4);
    if len != 16 + path_len || !path_matches(unsafe { ptr.add(16) }, path_len, 0) {
        return ERROR_NOT_FOUND;
    }
    let out_ptr = read_u32(ptr, 8) as *mut u8;
    let out_len = read_u32(ptr, 12) as usize;
    if out_len < LISTING.len() {
        return ERROR_INVALID;
    }
    let mut index = 0;
    while index < LISTING.len() {
        unsafe {
            core::ptr::write_volatile(out_ptr.add(index), LISTING[index]);
        }
        index += 1;
    }
    LISTING.len() as u32
}

"#,
    )
    .expect("fs metadata/read_dir stub lib.rs writes");
    let cargo_output = Command::new(k16_cargo())
        .args([
            "rustc",
            "-Z",
            "build-std=core",
            "-Z",
            "json-target-spec",
            "--manifest-path",
            stub_dir.join("Cargo.toml").to_str().unwrap(),
            "--target",
            k16_target_spec().to_str().unwrap(),
            "--target-dir",
            stub_dir.join("target").to_str().unwrap(),
            "--lib",
            "--",
            "-C",
            "panic=abort",
            "-Copt-level=z",
            "-C",
            "relocation-model=static",
            "-Cjump-tables=no",
            "-Cdebuginfo=0",
            &format!(
                "--emit=obj={}",
                work_dir.join("fs-read-dir-stub.o").display()
            ),
        ])
        .env("RUSTC", k16_rustc())
        .env("RUSTC_BOOTSTRAP", "1")
        .env(
            "RUSTFLAGS",
            "-Cpasses=lower-atomic -Copt-level=z -Cjump-tables=no -Cdebuginfo=0",
        )
        .output()
        .expect("fs metadata/read_dir stub cargo rustc runs");
    assert!(
        cargo_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&cargo_output.stderr)
    );
    fs::write(
        work_dir.join("fs-stat-stub.o"),
        k16_fs_stat_syscall_stub_object(),
    )
    .expect("fs stat stub object writes");
}

fn write_k16_sbrk_syscall_stub(work_dir: &Path) {
    let stub_dir = work_dir.join("sbrk-stub");
    let src_dir = stub_dir.join("src");
    fs::create_dir_all(&src_dir).expect("sbrk stub source directory creates");
    fs::write(
        stub_dir.join("Cargo.toml"),
        r#"[package]
name = "k16-sbrk-syscall-stub"
version = "0.1.0"
edition = "2021"

[lib]
name = "k16_sbrk_syscall_stub"
path = "src/lib.rs"
test = false
"#,
    )
    .expect("sbrk stub Cargo.toml writes");
    fs::write(
        src_dir.join("lib.rs"),
        r#"#![no_std]

const DEBUG_WRITE: *mut u8 = 0x1000_0100 as *mut u8;
const HEAP_SIZE: usize = 64 * 1024;

static mut HEAP: [u8; HEAP_SIZE] = [0; HEAP_SIZE];
static mut NEXT: u32 = 0;

#[no_mangle]
pub extern "C" fn __k16_sbrk_syscall(delta: u32) -> u32 {
    unsafe {
        core::ptr::write_volatile(DEBUG_WRITE, b'S');
        let old = NEXT;
        let Some(next) = old.checked_add(delta) else {
            return 0xffff_fffe;
        };
        if next as usize > HEAP_SIZE {
            return 0xffff_fffe;
        }
        NEXT = next;
        HEAP.as_mut_ptr().add(old as usize) as u32
    }
}

"#,
    )
    .expect("sbrk stub lib.rs writes");
    let cargo_output = Command::new(k16_cargo())
        .args([
            "rustc",
            "-Z",
            "build-std=core",
            "-Z",
            "json-target-spec",
            "--manifest-path",
            stub_dir.join("Cargo.toml").to_str().unwrap(),
            "--target",
            k16_target_spec().to_str().unwrap(),
            "--target-dir",
            stub_dir.join("target").to_str().unwrap(),
            "--lib",
            "--",
            "-C",
            "panic=abort",
            "-Copt-level=z",
            "-C",
            "relocation-model=static",
            "-Cjump-tables=no",
            "-Cdebuginfo=0",
            &format!("--emit=obj={}", work_dir.join("sbrk-stub.o").display()),
        ])
        .env("RUSTC", k16_rustc())
        .env("RUSTC_BOOTSTRAP", "1")
        .env(
            "RUSTFLAGS",
            "-Cpasses=lower-atomic -Copt-level=z -Cjump-tables=no -Cdebuginfo=0",
        )
        .output()
        .expect("sbrk stub cargo rustc runs");
    assert!(
        cargo_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&cargo_output.stderr)
    );
}

fn write_k16_fs_read_syscall_stub(work_dir: &Path) {
    let stub_dir = work_dir.join("fs-stub");
    let src_dir = stub_dir.join("src");
    fs::create_dir_all(&src_dir).expect("fs stub source directory creates");
    fs::write(
        stub_dir.join("Cargo.toml"),
        r#"[package]
name = "k16-fs-read-syscall-stub"
version = "0.1.0"
edition = "2021"

[lib]
name = "k16_fs_read_syscall_stub"
path = "src/lib.rs"
test = false
"#,
    )
    .expect("fs stub Cargo.toml writes");
    fs::write(
        src_dir.join("lib.rs"),
        r#"#![no_std]

const DEBUG_WRITE: *mut u8 = 0x1000_0100 as *mut u8;
const FILE_FD: u32 = 3;
const OPEN_READ_ONLY: u32 = 0;
const ERROR_BAD_FD: u32 = 0xffff_fff7;
const ERROR_NOT_FOUND: u32 = 0xffff_fffb;
const ERROR_UNSUPPORTED: u32 = 0xffff_fffc;
const MOTD_PATH_LEN: u32 = 9;

fn debug(byte: u8) {
    unsafe {
        core::ptr::write_volatile(DEBUG_WRITE, byte);
    }
}

fn motd_path_byte(index: u32) -> u8 {
    match index {
        0 => b'/',
        1 => b'e',
        2 => b't',
        3 => b'c',
        4 => b'/',
        5 => b'm',
        6 => b'o',
        7 => b't',
        8 => b'd',
        _ => 0,
    }
}

#[no_mangle]
pub extern "C" fn __k16_open_syscall(ptr: *const u8, len: u32, flags: u32) -> u32 {
    debug(b'O');
    if flags != OPEN_READ_ONLY {
        return ERROR_UNSUPPORTED;
    }
    if len != MOTD_PATH_LEN {
        return ERROR_NOT_FOUND;
    }
    let mut index = 0;
    while index < len {
        let byte = unsafe { core::ptr::read_volatile(ptr.add(index as usize)) };
        if byte != motd_path_byte(index) {
            return ERROR_NOT_FOUND;
        }
        index += 1;
    }
    FILE_FD
}

#[no_mangle]
pub extern "C" fn __k16_close_syscall(fd: u32) -> u32 {
    debug(b'C');
    if fd == FILE_FD { 0 } else { ERROR_BAD_FD }
}

"#,
    )
    .expect("fs stub lib.rs writes");
    let cargo_output = Command::new(k16_cargo())
        .args([
            "rustc",
            "-Z",
            "build-std=core",
            "-Z",
            "json-target-spec",
            "--manifest-path",
            stub_dir.join("Cargo.toml").to_str().unwrap(),
            "--target",
            k16_target_spec().to_str().unwrap(),
            "--target-dir",
            stub_dir.join("target").to_str().unwrap(),
            "--lib",
            "--",
            "-C",
            "panic=abort",
            "-Copt-level=z",
            "-C",
            "relocation-model=static",
            "-Cjump-tables=no",
            "-Cdebuginfo=0",
            &format!(
                "--emit=obj={}",
                work_dir.join("fs-open-close-stub.o").display()
            ),
        ])
        .env("RUSTC", k16_rustc())
        .env("RUSTC_BOOTSTRAP", "1")
        .env(
            "RUSTFLAGS",
            "-Cpasses=lower-atomic -Copt-level=z -Cjump-tables=no -Cdebuginfo=0",
        )
        .output()
        .expect("fs stub cargo rustc runs");
    assert!(
        cargo_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&cargo_output.stderr)
    );
    fs::write(
        work_dir.join("fs-read-stub.o"),
        k16_fs_read_syscall_stub_object(),
    )
    .expect("fs read stub object writes");
}

fn k16_main_returning_42_object() -> Vec<u8> {
    k16_object("main", &[0x01, 0xe0, 42, 0, 0, 0, 0x00, 0x90], None)
}

fn k16_main_calling_undefined_helper(helper: &str) -> Vec<u8> {
    k16_object(
        "main",
        &[0x01, 0xee, 0, 0, 0, 0, 0x00, 0x8e, 0x00, 0x90],
        Some((2, 2, helper)),
    )
}

fn k16_main_calling_undefined_helper_with_arg(helper: &str, arg: u32) -> Vec<u8> {
    let mut text = Vec::new();
    text.extend([0x01, 0xe1]);
    text.extend(arg.to_le_bytes());
    text.extend([0x01, 0xee, 0, 0, 0, 0, 0x00, 0x8e, 0x00, 0x90]);
    k16_object("main", &text, Some((8, 2, helper)))
}

fn k16_main_waiting_once_then_returning_7() -> Vec<u8> {
    k16_object(
        "main",
        &[0x01, 0xee, 0, 0, 0, 0, 0x00, 0x8e, 0x07, 0x10, 0x00, 0x90],
        Some((2, 2, "__k16_wait_once")),
    )
}

fn k16_abort_object() -> Vec<u8> {
    let mut words = Vec::new();
    words.extend(const32(1, k16_abi::syscall::EXIT));
    words.push(const4(2, 1));
    words.push(syscall(1));
    words.push(halt());
    let text = words_to_bytes(&words);
    k16_object("abort", &text, None)
}

fn k16_write_syscall_stub_object() -> Vec<u8> {
    let mut words = Vec::new();
    words.extend(const32(14, 0));
    words.extend(add(0, 3, 14));
    words.push(ret());
    k16_object("__k16_write_syscall", &words_to_bytes(&words), None)
}

fn k16_read_syscall_stub_object() -> Vec<u8> {
    let mut words = Vec::new();
    words.extend(const32(0, 2));
    words.push(ret());
    k16_object("__k16_read_syscall", &words_to_bytes(&words), None)
}

fn k16_startup_with_argv_object(args: &[&str]) -> Vec<u8> {
    let mut words = Vec::new();
    words.extend(const32(
        15,
        K16ArtifactTarget::PROGRAM_INITIAL_STACK_POINTER,
    ));

    words.extend(const32(1, args.len() as u32));
    let argv_table_relocation_offset = words.len() as u32 * 2 + 2;
    words.extend(const32(2, 0));
    words.extend(const32(3, 0));
    let main_relocation_offset = words.len() as u32 * 2 + 2;
    words.extend(const32(14, 0));
    words.push(call(14));
    words.extend(const32(1, k16_abi::syscall::EXIT));
    words.extend(const32(14, 0));
    words.extend(add(2, 0, 14));
    words.push(syscall(1));
    words.push(halt());

    let mut text = words_to_bytes(&words);
    let argv_table_offset = text.len() as u32;
    let mut argv_data_offsets = Vec::new();
    for arg in args {
        write_u32(&mut text, 0);
        write_u32(&mut text, arg.len() as u32);
    }
    for arg in args {
        argv_data_offsets.push(text.len() as u32);
        text.extend_from_slice(arg.as_bytes());
    }
    if text.len() % 2 != 0 {
        text.push(0);
    }

    let mut symbols = vec![
        ("_start".to_string(), 0, words.len() as u32 * 2, 0x12, 1),
        (
            "__k16_test_argv_table".to_string(),
            argv_table_offset,
            args.len() as u32 * 8,
            0x11,
            1,
        ),
    ];
    for (index, (arg, offset)) in args.iter().zip(argv_data_offsets.iter()).enumerate() {
        symbols.push((
            format!("__k16_test_argv_data_{index}"),
            *offset,
            arg.len() as u32,
            0x11,
            1,
        ));
    }
    symbols.push(("main".to_string(), 0, 0, 0x12, 0));

    let main_symbol_index = symbols.len() as u32;
    let argv_table_symbol_index = 2;
    let mut relocations = vec![
        (argv_table_relocation_offset, 1, argv_table_symbol_index),
        (main_relocation_offset, 2, main_symbol_index),
    ];
    for index in 0..args.len() {
        let data_symbol_index = 3 + index as u32;
        relocations.push((argv_table_offset + index as u32 * 8, 1, data_symbol_index));
    }

    k16_text_object_with_symbols(&text, &symbols, &relocations)
}

fn k16_fs_read_syscall_stub_object() -> Vec<u8> {
    let mut words = Vec::new();
    words.extend(const32(5, 0x1000_0100));
    words.extend(const32(4, u32::from(b'R')));
    words.push(store8(5, 4));
    for (offset, byte) in b"KraftOS MOTD\n".iter().enumerate() {
        words.extend(const32(4, u32::from(*byte)));
        words.extend(const32(14, offset as u32));
        words.extend(add(5, 2, 14));
        words.push(store8(5, 4));
    }
    words.extend(const32(0, 13));
    words.push(ret());
    k16_object("__k16_read_syscall", &words_to_bytes(&words), None)
}

fn k16_fs_stat_syscall_stub_object() -> Vec<u8> {
    let mut words = Vec::new();
    words.extend(const32(4, 1));
    words.push(store32(3, 4));
    words.extend(const32(4, 42));
    words.extend(const32(14, 4));
    words.extend(add(5, 3, 14));
    words.push(store32(5, 4));
    words.extend(const32(0, 0));
    words.push(ret());
    k16_object("__k16_stat_syscall", &words_to_bytes(&words), None)
}

fn k16_object(defined_symbol: &str, text: &[u8], relocation: Option<(u32, u32, &str)>) -> Vec<u8> {
    let shstrtab = b"\0.text.k16\0.rela.text.k16\0.symtab\0.strtab\0.shstrtab\0";
    let mut strtab = Vec::from([0]);
    let defined_name_offset = strtab.len() as u32;
    strtab.extend_from_slice(defined_symbol.as_bytes());
    strtab.push(0);
    let undefined_name_offset = if let Some((_, _, name)) = relocation {
        let offset = strtab.len() as u32;
        strtab.extend_from_slice(name.as_bytes());
        strtab.push(0);
        Some(offset)
    } else {
        None
    };

    let mut symtab = Vec::new();
    symtab.extend([0u8; 16]);
    write_symbol(
        &mut symtab,
        defined_name_offset,
        0,
        text.len() as u32,
        0x12,
        1,
    );
    if let Some(name_offset) = undefined_name_offset {
        write_symbol(&mut symtab, name_offset, 0, 0, 0x12, 0);
    }

    let mut rela = Vec::new();
    if let Some((offset, relocation_type, _)) = relocation {
        write_u32(&mut rela, offset);
        write_u32(&mut rela, (2 << 8) | relocation_type);
        write_u32(&mut rela, 0);
    }

    elf_object(text, &rela, &symtab, &strtab, shstrtab)
}

fn k16_text_object_with_symbols(
    text: &[u8],
    symbols: &[(String, u32, u32, u8, u16)],
    relocations: &[(u32, u32, u32)],
) -> Vec<u8> {
    let shstrtab = b"\0.text.k16\0.rela.text.k16\0.symtab\0.strtab\0.shstrtab\0";
    let mut strtab = Vec::from([0]);
    let symbol_names = symbols
        .iter()
        .map(|(name, _, _, _, _)| push_string(&mut strtab, name))
        .collect::<Vec<_>>();

    let mut symtab = Vec::new();
    symtab.extend([0u8; 16]);
    for ((_, value, size, info, section), name) in symbols.iter().zip(symbol_names) {
        write_symbol(&mut symtab, name, *value, *size, *info, *section);
    }

    let mut rela = Vec::new();
    for (offset, relocation_type, symbol_index) in relocations {
        write_u32(&mut rela, *offset);
        write_u32(&mut rela, (*symbol_index << 8) | *relocation_type);
        write_u32(&mut rela, 0);
    }

    elf_object(text, &rela, &symtab, &strtab, shstrtab)
}

fn elf_object(text: &[u8], rela: &[u8], symtab: &[u8], strtab: &[u8], shstrtab: &[u8]) -> Vec<u8> {
    let text_offset = 52u32;
    let rela_offset = align(text_offset + text.len() as u32, 4);
    let symtab_offset = align(rela_offset + rela.len() as u32, 4);
    let strtab_offset = align(symtab_offset + symtab.len() as u32, 4);
    let shstrtab_offset = align(strtab_offset + strtab.len() as u32, 4);
    let shoff = align(shstrtab_offset + shstrtab.len() as u32, 4);
    let section_count = if rela.is_empty() { 5 } else { 6 };
    let shstrndx = section_count - 1;

    let mut bytes = Vec::new();
    bytes.extend([0x7f, b'E', b'L', b'F', 1, 1, 1, 0]);
    bytes.extend([0u8; 8]);
    write_u16(&mut bytes, 1);
    write_u16(&mut bytes, 0x5258);
    write_u32(&mut bytes, 1);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, shoff);
    write_u32(&mut bytes, 0);
    write_u16(&mut bytes, 52);
    write_u16(&mut bytes, 0);
    write_u16(&mut bytes, 0);
    write_u16(&mut bytes, 40);
    write_u16(&mut bytes, section_count as u16);
    write_u16(&mut bytes, shstrndx as u16);

    pad_to(&mut bytes, text_offset);
    bytes.extend(text);
    pad_to(&mut bytes, rela_offset);
    bytes.extend(rela);
    pad_to(&mut bytes, symtab_offset);
    bytes.extend(symtab);
    pad_to(&mut bytes, strtab_offset);
    bytes.extend(strtab);
    pad_to(&mut bytes, shstrtab_offset);
    bytes.extend(shstrtab);
    pad_to(&mut bytes, shoff);

    bytes.extend([0u8; 40]);
    section(
        &mut bytes,
        1,
        1,
        0x6,
        text_offset,
        text.len() as u32,
        0,
        0,
        2,
        0,
    );
    if !rela.is_empty() {
        section(
            &mut bytes,
            13,
            4,
            0,
            rela_offset,
            rela.len() as u32,
            3,
            1,
            4,
            12,
        );
    }
    let symtab_link = if rela.is_empty() { 3 } else { 4 };
    section(
        &mut bytes,
        31,
        2,
        0,
        symtab_offset,
        symtab.len() as u32,
        symtab_link,
        1,
        4,
        16,
    );
    section(
        &mut bytes,
        39,
        3,
        0,
        strtab_offset,
        strtab.len() as u32,
        0,
        0,
        1,
        0,
    );
    section(
        &mut bytes,
        47,
        3,
        0,
        shstrtab_offset,
        shstrtab.len() as u32,
        0,
        0,
        1,
        0,
    );
    bytes
}

fn write_symbol(bytes: &mut Vec<u8>, name: u32, value: u32, size: u32, info: u8, section: u16) {
    write_u32(bytes, name);
    write_u32(bytes, value);
    write_u32(bytes, size);
    bytes.push(info);
    bytes.push(0);
    write_u16(bytes, section);
}

fn push_string(bytes: &mut Vec<u8>, value: &str) -> u32 {
    let offset = bytes.len() as u32;
    bytes.extend_from_slice(value.as_bytes());
    bytes.push(0);
    offset
}

#[allow(clippy::too_many_arguments)]
fn section(
    bytes: &mut Vec<u8>,
    name: u32,
    kind: u32,
    flags: u32,
    offset: u32,
    size: u32,
    link: u32,
    info: u32,
    addralign: u32,
    entsize: u32,
) {
    write_u32(bytes, name);
    write_u32(bytes, kind);
    write_u32(bytes, flags);
    write_u32(bytes, 0);
    write_u32(bytes, offset);
    write_u32(bytes, size);
    write_u32(bytes, link);
    write_u32(bytes, info);
    write_u32(bytes, addralign);
    write_u32(bytes, entsize);
}

fn align(value: u32, alignment: u32) -> u32 {
    value.div_ceil(alignment) * alignment
}

fn pad_to(bytes: &mut Vec<u8>, offset: u32) {
    bytes.resize(offset as usize, 0);
}

fn temp_file(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("k16-runtime-cli-{}-{name}", std::process::id()));
    let _ = fs::remove_file(&path);
    path
}

fn temp_dir(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("k16-runtime-cli-{}-{name}", std::process::id()));
    let _ = fs::remove_dir_all(&path);
    path
}

fn find_linked_rust_bin(work_dir: &Path) -> PathBuf {
    let deps_dir = work_dir
        .join("target")
        .join("k16-unknown-kraftos")
        .join("debug")
        .join("deps");
    let candidates = fs::read_dir(&deps_dir)
        .expect("target deps dir reads")
        .map(|entry| entry.expect("target dep entry reads").path())
        .filter(|path| path.is_file())
        .filter(|path| {
            !matches!(
                path.extension().and_then(|extension| extension.to_str()),
                Some("d" | "o" | "rlib" | "rmeta")
            )
        })
        .collect::<Vec<_>>();
    assert_eq!(
        candidates.len(),
        1,
        "expected exactly one linked Rust bin artifact in {}: {candidates:?}",
        deps_dir.display()
    );
    candidates.into_iter().next().expect("one candidate exists")
}

fn k16_binary() -> String {
    std::env::var("CARGO_BIN_EXE_k16").expect("Cargo exposes k16 binary path")
}

fn k16_ld_binary() -> String {
    std::env::var("CARGO_BIN_EXE_k16-ld").expect("Cargo exposes k16-ld binary path")
}

fn k16_cargo() -> String {
    std::env::var("K16_CARGO").unwrap_or_else(|_| "cargo".to_string())
}

fn k16_rustc() -> String {
    std::env::var("K16_RUSTC").expect("K16_RUSTC points at the custom K16 rustc")
}

fn k16_target_spec() -> PathBuf {
    repo_root().join("tools/k16-unknown-kraftos.json")
}

fn k16_llvm_bin_dir() -> PathBuf {
    repo_root().join(".toolchain/build/llvm/k16-min/bin")
}

fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("k16-tools parent exists")
        .parent()
        .expect("host parent exists")
        .parent()
        .expect("rust parent exists")
        .to_path_buf()
}

fn write_runtime_object(name: &str, path: &std::path::Path) {
    write_runtime_object_args(name, &["-o", path.to_str().unwrap()]);
}

fn write_runtime_object_for_target(name: &str, target: &str, path: &std::path::Path) {
    write_runtime_object_args(name, &["--target", target, "-o", path.to_str().unwrap()]);
}

fn write_runtime_object_args(name: &str, args: &[&str]) {
    let mut command_args = vec!["runtime", name];
    command_args.extend_from_slice(args);
    let output = Command::new(k16_binary())
        .args(command_args)
        .env("K16_RUSTC", k16_rustc())
        .env("K16_RUST_TARGET_JSON", k16_target_spec())
        .env("K16_LLVM_BIN_DIR", k16_llvm_bin_dir())
        .output()
        .expect("k16 runtime runs");
    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
}

fn words_to_bytes(words: &[u16]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(words.len() * 2);
    for word in words {
        write_u16(&mut bytes, *word);
    }
    bytes
}

fn write_words(memory: &mut MachineMemory, address: u32, words: &[u16]) {
    for (index, word) in words.iter().enumerate() {
        memory
            .store_u8(address + (index as u32 * 2), (word & 0x00ff) as u8)
            .expect("low instruction byte stores");
        memory
            .store_u8(address + (index as u32 * 2) + 1, (word >> 8) as u8)
            .expect("high instruction byte stores");
    }
}

fn const32(register: u8, value: u32) -> [u16; 3] {
    [
        0xe001 | (u16::from(register) << 8),
        (value & 0xffff) as u16,
        (value >> 16) as u16,
    ]
}

fn const4(dst: u8, value: u8) -> u16 {
    0x1000 | (u16::from(dst) << 8) | u16::from(value & 0x0f)
}

fn push_register(register: u8) -> Vec<u16> {
    let mut words = Vec::new();
    words.extend(const32(14, 0xffff_fffc));
    words.extend(add(15, 15, 14));
    words.push(store32(15, register));
    words
}

fn pop_register(register: u8) -> Vec<u16> {
    let mut words = Vec::new();
    words.push(load32(register, 15));
    words.push(const4(14, 4));
    words.extend(add(15, 15, 14));
    words
}

fn push_scratch_register() -> Vec<u16> {
    let mut words = Vec::new();
    words.extend(const32(4, 0xffff_fffc));
    words.extend(add(15, 15, 4));
    words.push(store32(15, 14));
    words
}

fn pop_scratch_register() -> Vec<u16> {
    let mut words = Vec::new();
    words.push(load32(14, 15));
    words.push(const4(4, 4));
    words.extend(add(15, 15, 4));
    words
}

fn add(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x2000 | (u16::from(dst) << 8),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

fn load32(dst: u8, addr: u8) -> u16 {
    0x4002 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn store8(addr: u8, src: u8) -> u16 {
    0x5000 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn store32(addr: u8, src: u8) -> u16 {
    0x5002 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn call(register: u8) -> u16 {
    0x8000 | (u16::from(register) << 8)
}

fn syscall(register: u8) -> u16 {
    0x0005 | (u16::from(register) << 8)
}

fn iret() -> u16 {
    0x0004
}

fn ret() -> u16 {
    0x9000
}

fn read_csr(dst: u8, csr: u32) -> u16 {
    0x0002 | (u16::from(dst) << 8) | (((csr as u16) & 0x0f) << 4)
}

fn write_csr(csr: u32, src: u8) -> u16 {
    0x0003 | (((csr as u16) & 0xff) << 8) | (u16::from(src) << 4)
}

fn halt() -> u16 {
    0x0001
}

fn write_u16(bytes: &mut Vec<u8>, value: u16) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_u32(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}
