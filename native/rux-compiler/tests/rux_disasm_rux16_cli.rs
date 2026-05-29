use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::sync::atomic::{AtomicUsize, Ordering};

static TEMP_FILE_COUNTER: AtomicUsize = AtomicUsize::new(0);

#[test]
fn rux_disasm_requires_explicit_target() {
    let artifact_path = temp_file("program.bin");
    fs::write(&artifact_path, halt().to_le_bytes()).expect("artifact writes");

    let output = Command::new(rux_binary())
        .args(["disasm", artifact_path.to_str().unwrap()])
        .output()
        .expect("rux disasm runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(stderr.contains("--target"), "stderr: {stderr}");
}

#[test]
fn rux_disasm_prints_program_artifact_from_program_load_base() {
    let artifact_path = temp_file("program.ruxe");
    fs::write(
        &artifact_path,
        rux_compiler::ruxe::encode_rux16_executable(
            &words_to_bytes(&[const4(1, 7), halt()]),
            rux_compiler::ruxe::RuxeAbiKind::Program,
            0x8000,
            0x8000,
        )
        .expect("RUXE encodes"),
    )
    .expect("artifact writes");

    let output = Command::new(rux_binary())
        .args([
            "disasm",
            "--target",
            "program",
            artifact_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux disasm runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(
        stdout.contains("00008000: 1107  const4 r1, 7"),
        "stdout: {stdout}"
    );
    assert!(stdout.contains("00008002: 0001  halt"), "stdout: {stdout}");
}

#[test]
fn rux_disasm_prints_boot_artifact_from_boot_load_base() {
    let artifact_path = temp_file("boot.ruxe");
    fs::write(
        &artifact_path,
        rux_compiler::ruxe::encode_rux16_executable(
            &words_to_bytes(&[const4(1, 7), halt()]),
            rux_compiler::ruxe::RuxeAbiKind::Bootloader,
            2048,
            2048,
        )
        .expect("RUXE encodes"),
    )
    .expect("artifact writes");

    let output = Command::new(rux_binary())
        .args([
            "disasm",
            "--target",
            "boot",
            artifact_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux disasm runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(
        stdout.contains("00000800: 1107  const4 r1, 7"),
        "stdout: {stdout}"
    );
    assert!(stdout.contains("00000802: 0001  halt"), "stdout: {stdout}");
}

#[test]
fn rux_disasm_prints_kernel_artifact_from_kernel_load_base() {
    let artifact_path = temp_file("kernel.ruxe");
    fs::write(
        &artifact_path,
        rux_compiler::ruxe::encode_rux16_executable(
            &words_to_bytes(&[const4(1, 7), halt()]),
            rux_compiler::ruxe::RuxeAbiKind::Kernel,
            0x4000,
            0x4000,
        )
        .expect("RUXE encodes"),
    )
    .expect("artifact writes");

    let output = Command::new(rux_binary())
        .args([
            "disasm",
            "--target",
            "kernel",
            artifact_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux disasm runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(
        stdout.contains("00004000: 1107  const4 r1, 7"),
        "stdout: {stdout}"
    );
    assert!(stdout.contains("00004002: 0001  halt"), "stdout: {stdout}");
}

#[test]
fn rux_disasm_rejects_raw_boot_bytes_without_ruxe_fallback() {
    let artifact_path = temp_file("raw-boot.bin");
    fs::write(&artifact_path, words_to_bytes(&[halt()])).expect("artifact writes");

    let output = Command::new(rux_binary())
        .args([
            "disasm",
            "--target",
            "boot",
            artifact_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux disasm runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(stderr.contains("invalid RUXE magic"), "stderr: {stderr}");
}

#[test]
fn rux_disasm_prints_bios_artifact_from_bios_flash_base() {
    let artifact_path = temp_file("bios.flash");
    fs::write(&artifact_path, words_to_bytes(&[const4(1, 7), halt()])).expect("artifact writes");

    let output = Command::new(rux_binary())
        .args([
            "disasm",
            "--target",
            "bios",
            artifact_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux disasm runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(
        stdout.contains("fff00000: 1107  const4 r1, 7"),
        "stdout: {stdout}"
    );
    assert!(stdout.contains("fff00002: 0001  halt"), "stdout: {stdout}");
}

#[test]
fn rux_disasm_prints_ltu_extended_instruction() {
    let artifact_path = temp_file("bios-ltu.flash");
    fs::write(
        &artifact_path,
        words_to_bytes(&[
            const4(1, 2),
            const4(2, 5),
            ltu(3, 1, 2)[0],
            ltu(3, 1, 2)[1],
            halt(),
        ]),
    )
    .expect("artifact writes");

    let output = Command::new(rux_binary())
        .args([
            "disasm",
            "--target",
            "bios",
            artifact_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux disasm runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains("ltu r3, r1, r2"), "stdout: {stdout}");
}

#[test]
fn rux_disasm_prints_call_and_ret_instructions() {
    let artifact_path = temp_file("bios-call-ret.flash");
    fs::write(&artifact_path, words_to_bytes(&[call(1), ret(), halt()])).expect("artifact writes");

    let output = Command::new(rux_binary())
        .args([
            "disasm",
            "--target",
            "bios",
            artifact_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux disasm runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(
        stdout.contains("fff00000: 8100  call r1"),
        "stdout: {stdout}"
    );
    assert!(stdout.contains("fff00002: 9000  ret"), "stdout: {stdout}");
}

fn temp_file(name: &str) -> PathBuf {
    let counter = TEMP_FILE_COUNTER.fetch_add(1, Ordering::Relaxed);
    let path = std::env::temp_dir().join(format!(
        "rux16-disasm-{}-{counter}-{name}",
        std::process::id()
    ));
    let _ = fs::remove_file(&path);
    path
}

fn rux_binary() -> String {
    std::env::var("CARGO_BIN_EXE_rux").expect("Cargo exposes rux binary path")
}

fn words_to_bytes(words: &[u16]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(words.len() * 2);
    for word in words {
        bytes.extend_from_slice(&word.to_le_bytes());
    }
    bytes
}

fn const4(register: u8, value: u8) -> u16 {
    0x1000 | (u16::from(register) << 8) | u16::from(value & 0x0f)
}

fn ltu(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x3002 | (u16::from(dst) << 8),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

fn call(register: u8) -> u16 {
    0x8000 | (u16::from(register) << 8)
}

fn ret() -> u16 {
    0x9000
}

fn halt() -> u16 {
    0x0001
}
