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
use std::path::PathBuf;
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
fn k16_runtime_startup_enters_main_with_eight_byte_aligned_stack() {
    let startup_path = temp_file("run-startup-aligned.o");
    let main_path = temp_file("run-main-stack-alignment.o");
    let output_path = temp_file("run-program-stack-alignment.k16e");
    fs::write(&main_path, k16_main_returning_stack_alignment_object()).expect("main object writes");

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
        "signal=halt exit_status=0 debug_bytes=\n"
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
fn kasm_builds_checked_in_cpu_helper_source() {
    let workspace = temp_dir("k16-asm-cpu-helpers");
    fs::create_dir_all(&workspace).expect("workspace created");
    let startup_path = workspace.join("startup.o");
    let main_path = workspace.join("main.o");
    let output_path = workspace.join("cpu-helpers.o");
    let source_path = repo_root().join("guest/platform/k16/cpu-helpers.kasm");

    let output = Command::new(k16_binary())
        .args([
            "asm",
            source_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 asm runs");
    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );

    let helper_object = fs::read(&output_path).expect("helper object written");
    assert!(
        helper_object.starts_with(&[0x7f, b'E', b'L', b'F']),
        "assembled helper should be an ELF relocatable object"
    );
    fs::write(
        &main_path,
        k16_main_calling_undefined_helper("__k16_halt_once"),
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

    let linked_path = workspace.join("helper-program.kx");
    let link_output = Command::new(k16_binary())
        .args([
            "link",
            "--target",
            "program",
            startup_path.to_str().unwrap(),
            main_path.to_str().unwrap(),
            output_path.to_str().unwrap(),
            "-o",
            linked_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 link runs");
    assert!(
        link_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&link_output.stderr)
    );
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
fn k16_runtime_cpu_helpers_do_not_export_userland_syscall_shims() {
    let helper_object = k16_runtime::k16_cpu_helpers_object();
    let helper_text = String::from_utf8_lossy(&helper_object);

    for symbol in [
        "__k16_write_syscall",
        "__k16_read_syscall",
        "__k16_open_syscall",
        "__k16_close_syscall",
        "__k16_brk_syscall",
        "__k16_sbrk_syscall",
    ] {
        assert!(
            !helper_text.contains(symbol),
            "host-generated K16 CPU helpers must not export removed Rust userland shim {symbol}",
        );
    }
    for symbol in ["__k16_syscall0", "__k16_syscall1", "__k16_syscall3"] {
        assert!(
            helper_text.contains(symbol),
            "host-generated K16 CPU helpers must keep generic syscall helper {symbol}",
        );
    }
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

fn k16_main_returning_42_object() -> Vec<u8> {
    k16_object("main", &[0x01, 0xe0, 42, 0, 0, 0, 0x00, 0x90], None)
}

fn k16_main_returning_stack_alignment_object() -> Vec<u8> {
    let mut words = Vec::new();
    words.push(const4(1, 7));
    words.extend(and(0, 15, 1));
    words.push(ret());
    k16_object("main", &words_to_bytes(&words), None)
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

fn k16_binary() -> String {
    std::env::var("CARGO_BIN_EXE_k16").expect("Cargo exposes k16 binary path")
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
        .to_path_buf()
}

fn write_runtime_object(name: &str, path: &std::path::Path) {
    write_runtime_object_args(name, &["-o", path.to_str().unwrap()]);
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

fn add(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x2000 | (u16::from(dst) << 8),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

fn and(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x2002 | (u16::from(dst) << 8),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

fn load32(dst: u8, addr: u8) -> u16 {
    0x4002 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
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
