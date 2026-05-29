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

#[test]
fn rux_disasm_prints_complete_instruction_surface_multiword_raw_words_and_branch_labels() {
    let artifact_path = temp_file("bios-labels.flash");
    fs::write(
        &artifact_path,
        words_to_bytes(&[
            nop(),
            read_csr(1, 2),
            write_csr(3, 1),
            const32(1, 0x0000_1234)[0],
            const32(1, 0x0000_1234)[1],
            const32(1, 0x0000_1234)[2],
            add(2, 1, 1),
            eq(3, 1, 2)[0],
            eq(3, 1, 2)[1],
            test_bits(4, 3, 0x00f0)[0],
            test_bits(4, 3, 0x00f0)[1],
            ltu(5, 1, 2)[0],
            ltu(5, 1, 2)[1],
            load8(6, 7),
            load32(6, 7),
            store8(7, 6),
            store32(7, 6),
            branch_if_zero(1, 1),
            branch_if_nonzero(1, 0),
            halt(),
            const4(2, 9),
            jump(2),
            call(2),
            ret(),
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
    assert!(stdout.contains("fff00000: 0000  nop"), "stdout: {stdout}");
    assert!(
        stdout.contains("fff00002: 0122  read_csr r1, 2"),
        "stdout: {stdout}"
    );
    assert!(
        stdout.contains("fff00004: 0313  write_csr 3, r1"),
        "stdout: {stdout}"
    );
    assert!(
        stdout.contains("fff00006: e101 1234 0000  const32 r1, 0x00001234"),
        "stdout: {stdout}"
    );
    assert!(
        stdout.contains("fff0000c: 2211  add r2, r1, r1"),
        "stdout: {stdout}"
    );
    assert!(
        stdout.contains("fff0000e: 3300 0012  eq r3, r1, r2"),
        "stdout: {stdout}"
    );
    assert!(
        stdout.contains("fff00012: 3431 00f0  test_bits r4, r3, 0x00f0"),
        "stdout: {stdout}"
    );
    assert!(
        stdout.contains("fff00016: 3502 0012  ltu r5, r1, r2"),
        "stdout: {stdout}"
    );
    assert!(
        stdout.contains("fff0001a: 4670  load8 r6, [r7]"),
        "stdout: {stdout}"
    );
    assert!(
        stdout.contains("fff0001c: 4672  load32 r6, [r7]"),
        "stdout: {stdout}"
    );
    assert!(
        stdout.contains("fff0001e: 5760  store8 [r7], r6"),
        "stdout: {stdout}"
    );
    assert!(
        stdout.contains("fff00020: 5762  store32 [r7], r6"),
        "stdout: {stdout}"
    );
    assert!(
        stdout.contains("fff00022: 6101  branch_if_zero r1, L_fff00026"),
        "stdout: {stdout}"
    );
    assert!(
        stdout.contains("fff00024: 6110  branch_if_nonzero r1, L_fff00026"),
        "stdout: {stdout}"
    );
    assert!(stdout.contains("L_fff00026:"), "stdout: {stdout}");
    assert!(
        stdout.contains("fff00028: 1209  const4 r2, 9"),
        "stdout: {stdout}"
    );
    assert!(
        stdout.contains("fff0002a: 7200  jump r2"),
        "stdout: {stdout}"
    );
    assert!(
        stdout.contains("fff0002c: 8200  call r2"),
        "stdout: {stdout}"
    );
    assert!(stdout.contains("fff0002e: 9000  ret"), "stdout: {stdout}");
    assert!(stdout.contains("fff00030: 0001  halt"), "stdout: {stdout}");
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

fn nop() -> u16 {
    0x0000
}

fn read_csr(register: u8, csr: u8) -> u16 {
    0x0002 | (u16::from(register) << 8) | (u16::from(csr) << 4)
}

fn write_csr(csr: u8, register: u8) -> u16 {
    0x0003 | (u16::from(csr) << 8) | (u16::from(register) << 4)
}

fn const4(register: u8, value: u8) -> u16 {
    0x1000 | (u16::from(register) << 8) | u16::from(value & 0x0f)
}

fn add(dst: u8, lhs: u8, rhs: u8) -> u16 {
    0x2000 | (u16::from(dst) << 8) | (u16::from(lhs) << 4) | u16::from(rhs)
}

fn eq(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x3000 | (u16::from(dst) << 8),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

fn ltu(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x3002 | (u16::from(dst) << 8),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

fn test_bits(dst: u8, src: u8, mask: u16) -> [u16; 2] {
    [0x3001 | (u16::from(dst) << 8) | (u16::from(src) << 4), mask]
}

fn load8(dst: u8, addr: u8) -> u16 {
    0x4000 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
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

fn const32(register: u8, value: u32) -> [u16; 3] {
    [
        0xe001 | (u16::from(register) << 8),
        (value & 0xffff) as u16,
        (value >> 16) as u16,
    ]
}

fn branch_if_zero(register: u8, offset_words: i8) -> u16 {
    0x6000 | (u16::from(register) << 8) | encode_signed_nibble(offset_words)
}

fn branch_if_nonzero(register: u8, offset_words: i8) -> u16 {
    0x6000 | (u16::from(register) << 8) | 0x0010 | encode_signed_nibble(offset_words)
}

fn encode_signed_nibble(value: i8) -> u16 {
    (value as i16 & 0x000f) as u16
}

fn jump(register: u8) -> u16 {
    0x7000 | (u16::from(register) << 8)
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
