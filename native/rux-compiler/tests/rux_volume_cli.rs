use rux_compiler::artifact::Rux16ArtifactTarget;
use rux_compiler::compile_rux16_artifact;
use rux_compiler::ruxe;
use rux_vm::rux16::Rux16Signal;
use rux_vm::rux_computer::{RuxComputerHandle, RuxComputerTextDisplaySnapshot};
use std::fs;
use std::path::Path;
use std::path::PathBuf;
use std::process::Command;

#[test]
fn rux_volume_create_writes_empty_ruxvol_header() {
    let path = temp_file("create-storage0.ruxvol");
    let output = Command::new(rux_binary())
        .args(["volume", "create", path.to_str().unwrap(), "--size", "4096"])
        .output()
        .expect("rux runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let bytes = fs::read(&path).expect("volume exists");
    assert_eq!(&bytes[..6], b"RUXVOL");
    assert_eq!(u16::from_le_bytes(bytes[6..8].try_into().unwrap()), 1);
    assert_eq!(u64::from_le_bytes(bytes[8..16].try_into().unwrap()), 4096);
    assert_eq!(bytes.len(), 16 + 4096);
    assert!(bytes[16..].iter().all(|byte| *byte == 0));
}

#[test]
fn rux_volume_put_boot_records_boot_artifact() {
    let volume_path = temp_file("boot-storage0.ruxvol");
    let boot_path = temp_file("boot.ruxe");
    fs::write(
        &boot_path,
        ruxe::encode_rux16_executable(
            &[0x01, 0x02, 0x03, 0x04],
            ruxe::RuxeAbiKind::Bootloader,
            0x900,
            0x900,
        )
        .expect("RUXE encodes"),
    )
    .expect("boot writes");

    assert!(Command::new(rux_binary())
        .args([
            "volume",
            "create",
            volume_path.to_str().unwrap(),
            "--size",
            "4096",
        ])
        .status()
        .expect("create runs")
        .success());
    let output = Command::new(rux_binary())
        .args([
            "volume",
            "put-boot",
            volume_path.to_str().unwrap(),
            boot_path.to_str().unwrap(),
        ])
        .output()
        .expect("put-boot runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let bytes = fs::read(&volume_path).expect("volume reads");
    let payload = &bytes[16..];
    assert_eq!(&payload[0..4], b"RUXB");
    assert_eq!(u32::from_le_bytes(payload[4..8].try_into().unwrap()), 0x900);
    assert_eq!(
        u32::from_le_bytes(payload[8..12].try_into().unwrap()),
        0x900
    );
    assert_eq!(u32::from_le_bytes(payload[12..16].try_into().unwrap()), 1);
    assert_eq!(u32::from_le_bytes(payload[16..20].try_into().unwrap()), 1);
    assert_eq!(&payload[512..516], &[0x01, 0x02, 0x03, 0x04]);
    assert!(payload[516..1024].iter().all(|byte| *byte == 0));
}

#[test]
fn rux_volume_put_kernel_records_kernel_artifact() {
    let volume_path = temp_file("kernel-record-storage0.ruxvol");
    let kernel_path = temp_file("kernel-record.ruxe");
    fs::write(
        &kernel_path,
        ruxe::encode_rux16_executable(
            &[0x01, 0x02, 0x03, 0x04],
            ruxe::RuxeAbiKind::Kernel,
            0x4000,
            0x4000,
        )
        .expect("RUXE encodes"),
    )
    .expect("kernel writes");

    assert!(Command::new(rux_binary())
        .args([
            "volume",
            "create",
            volume_path.to_str().unwrap(),
            "--size",
            "16384",
        ])
        .status()
        .expect("create runs")
        .success());
    let output = Command::new(rux_binary())
        .args([
            "volume",
            "put-kernel",
            volume_path.to_str().unwrap(),
            kernel_path.to_str().unwrap(),
        ])
        .output()
        .expect("put-kernel runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let bytes = fs::read(&volume_path).expect("volume reads");
    let payload = &bytes[16..];
    assert_eq!(&payload[8192..8196], b"RUXK");
    assert_eq!(
        u32::from_le_bytes(payload[8196..8200].try_into().unwrap()),
        0x4000
    );
    assert_eq!(
        u32::from_le_bytes(payload[8200..8204].try_into().unwrap()),
        0x4000
    );
    assert_eq!(
        u32::from_le_bytes(payload[8204..8208].try_into().unwrap()),
        1
    );
    assert_eq!(
        u32::from_le_bytes(payload[8208..8212].try_into().unwrap()),
        17
    );
    assert_eq!(&payload[8704..8708], &[0x01, 0x02, 0x03, 0x04]);
}

#[test]
fn rux_volume_put_kernel_rejects_boot_artifact_without_profile_fallback() {
    let volume_path = temp_file("boot-as-kernel-storage0.ruxvol");
    let kernel_path = temp_file("boot-as-kernel.ruxe");
    fs::write(
        &kernel_path,
        ruxe::encode_rux16_executable(&[0x01, 0x00], ruxe::RuxeAbiKind::Bootloader, 0x800, 0x800)
            .expect("RUXE encodes"),
    )
    .expect("boot writes");

    assert!(Command::new(rux_binary())
        .args([
            "volume",
            "create",
            volume_path.to_str().unwrap(),
            "--size",
            "16384",
        ])
        .status()
        .expect("create runs")
        .success());
    let output = Command::new(rux_binary())
        .args([
            "volume",
            "put-kernel",
            volume_path.to_str().unwrap(),
            kernel_path.to_str().unwrap(),
        ])
        .output()
        .expect("put-kernel runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        stderr.contains("kernel media requires RUXE kernel ABI kind"),
        "stderr: {stderr}"
    );
}

#[test]
fn rux_volume_put_boot_rejects_kernel_artifact_without_profile_fallback() {
    let volume_path = temp_file("kernel-storage0.ruxvol");
    let boot_path = temp_file("kernel.ruxe");
    fs::write(
        &boot_path,
        ruxe::encode_rux16_executable(&[0x01, 0x00], ruxe::RuxeAbiKind::Kernel, 0x4000, 0x4000)
            .expect("RUXE encodes"),
    )
    .expect("kernel writes");

    assert!(Command::new(rux_binary())
        .args([
            "volume",
            "create",
            volume_path.to_str().unwrap(),
            "--size",
            "4096",
        ])
        .status()
        .expect("create runs")
        .success());
    let output = Command::new(rux_binary())
        .args([
            "volume",
            "put-boot",
            volume_path.to_str().unwrap(),
            boot_path.to_str().unwrap(),
        ])
        .output()
        .expect("put-boot runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        stderr.contains("boot media requires RUXE bootloader ABI kind"),
        "stderr: {stderr}"
    );
}

#[test]
fn rux_volume_put_boot_rejects_raw_boot_bytes_without_ruxe_fallback() {
    let volume_path = temp_file("raw-boot-storage0.ruxvol");
    let boot_path = temp_file("raw-boot.bin");
    fs::write(&boot_path, [0x01, 0x00]).expect("boot writes");

    assert!(Command::new(rux_binary())
        .args([
            "volume",
            "create",
            volume_path.to_str().unwrap(),
            "--size",
            "4096",
        ])
        .status()
        .expect("create runs")
        .success());
    let output = Command::new(rux_binary())
        .args([
            "volume",
            "put-boot",
            volume_path.to_str().unwrap(),
            boot_path.to_str().unwrap(),
        ])
        .output()
        .expect("put-boot runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(stderr.contains("invalid RUXE magic"), "stderr: {stderr}");
}

#[test]
fn rux_volume_put_boot_and_kernel_creates_storage0_that_bundled_bios_executes() {
    let volume_path = temp_file("boot-kernel-storage0.ruxvol");
    let boot_path = temp_file("kernel-loader.boot");
    let kernel_source_path = temp_file("kernel.rx");
    let kernel_path = temp_file("kernel.ruxe");
    let boot_source_path =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/kernel_loader.rx");
    fs::write(
        &kernel_source_path,
        "fn main() {
            unsafe {
                mmio<i32>(DISPLAY0_COMMAND).store(DISPLAY0_COMMAND_CLEAR);
                mmio<i32>(DISPLAY0_CURSOR_X).store(0);
                mmio<i32>(DISPLAY0_CURSOR_Y).store(0);
                mmio<i32>(DISPLAY0_DATA).store(75);
                mmio<i32>(DISPLAY0_COMMAND).store(DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(DISPLAY0_DATA).store(69);
                mmio<i32>(DISPLAY0_COMMAND).store(DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(DISPLAY0_DATA).store(82);
                mmio<i32>(DISPLAY0_COMMAND).store(DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(DISPLAY0_DATA).store(78);
                mmio<i32>(DISPLAY0_COMMAND).store(DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(DISPLAY0_DATA).store(69);
                mmio<i32>(DISPLAY0_COMMAND).store(DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(DISPLAY0_DATA).store(76);
                mmio<i32>(DISPLAY0_COMMAND).store(DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(DISPLAY0_DATA).store(32);
                mmio<i32>(DISPLAY0_COMMAND).store(DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(DISPLAY0_DATA).store(79);
                mmio<i32>(DISPLAY0_COMMAND).store(DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(DISPLAY0_DATA).store(75);
                mmio<i32>(DISPLAY0_COMMAND).store(DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(CONTROL_PANIC_CODE).store(75);
            }
        }",
    )
    .expect("kernel source writes");

    let boot_compile_output = Command::new(rux_binary())
        .args([
            "compile",
            "--target",
            "boot",
            boot_source_path.to_str().unwrap(),
            "-o",
            boot_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux compile boot runs");
    assert!(
        boot_compile_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&boot_compile_output.stderr)
    );
    let kernel_compile_output = Command::new(rux_binary())
        .args([
            "compile",
            "--target",
            "kernel",
            kernel_source_path.to_str().unwrap(),
            "-o",
            kernel_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux compile kernel runs");
    assert!(
        kernel_compile_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&kernel_compile_output.stderr)
    );
    assert!(Command::new(rux_binary())
        .args([
            "volume",
            "create",
            volume_path.to_str().unwrap(),
            "--size",
            "16384",
        ])
        .status()
        .expect("create runs")
        .success());
    assert!(Command::new(rux_binary())
        .args([
            "volume",
            "put-boot",
            volume_path.to_str().unwrap(),
            boot_path.to_str().unwrap(),
        ])
        .status()
        .expect("put-boot runs")
        .success());
    assert!(Command::new(rux_binary())
        .args([
            "volume",
            "put-kernel",
            volume_path.to_str().unwrap(),
            kernel_path.to_str().unwrap(),
        ])
        .status()
        .expect("put-kernel runs")
        .success());

    let bios = compile_bundled_rux16_bios();
    let mut handle = RuxComputerHandle::create_rux16_bios_flash_with_storage0_path(
        &bios,
        64 * 1024,
        4096,
        &volume_path,
    )
    .expect("Rux16 BIOS flash computer creates with CLI boot/kernel volume path");

    assert_eq!(handle.run_rux16_until_signal().unwrap(), Rux16Signal::Halt);
    let snapshot = handle
        .display0_snapshot()
        .expect("computer profile maps display0");
    assert_eq!(display_row(&snapshot, 0), "KERNEL OK");
    assert_eq!(handle.control().panic_code, 75);
}

#[test]
fn rux_volume_put_boot_creates_storage0_that_bundled_bios_executes() {
    let volume_path = temp_file("bootable-storage0.ruxvol");
    let boot_path = temp_file("stage2.boot");
    let boot_source_path =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/stage2_display.rx");

    let compile_output = Command::new(rux_binary())
        .args([
            "compile",
            "--target",
            "boot",
            boot_source_path.to_str().unwrap(),
            "-o",
            boot_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux compile runs");
    assert!(
        compile_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&compile_output.stderr)
    );
    assert!(Command::new(rux_binary())
        .args([
            "volume",
            "create",
            volume_path.to_str().unwrap(),
            "--size",
            "4096",
        ])
        .status()
        .expect("create runs")
        .success());
    assert!(Command::new(rux_binary())
        .args([
            "volume",
            "put-boot",
            volume_path.to_str().unwrap(),
            boot_path.to_str().unwrap(),
        ])
        .status()
        .expect("put-boot runs")
        .success());

    let bios = compile_bundled_rux16_bios();
    let mut handle = RuxComputerHandle::create_rux16_bios_flash_with_storage0_path(
        &bios,
        64 * 1024,
        1024,
        &volume_path,
    )
    .expect("Rux16 BIOS flash computer creates with CLI boot volume path");

    assert_eq!(handle.run_rux16_until_signal().unwrap(), Rux16Signal::Halt);
    let snapshot = handle
        .display0_snapshot()
        .expect("computer profile maps display0");
    assert_eq!(display_row(&snapshot, 0), "STAGE2 OK");
    assert_eq!(handle.control().panic_code, 83);
}

fn compile_bundled_rux16_bios() -> Vec<u8> {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/firmware/rux16_bios.rx"),
    )
    .expect("Rux16 BIOS firmware source should exist");
    compile_rux16_artifact(&source, Rux16ArtifactTarget::Bios)
        .expect("Rux16 BIOS source compiles to BIOS flash")
        .bytes
}

fn display_row(snapshot: &RuxComputerTextDisplaySnapshot, row: u32) -> String {
    let start = (row * snapshot.columns) as usize;
    let end = start + snapshot.columns as usize;
    let row = &snapshot.cells[start..end];
    let visible_end = row
        .iter()
        .rposition(|byte| *byte != b' ' && *byte != 0)
        .map_or(0, |index| index + 1);
    String::from_utf8_lossy(&row[..visible_end]).to_string()
}

fn temp_file(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("rux-volume-cli-{}-{name}", std::process::id()));
    let _ = fs::remove_file(&path);
    path
}

fn rux_binary() -> String {
    std::env::var("CARGO_BIN_EXE_rux").expect("Cargo exposes rux binary path")
}
