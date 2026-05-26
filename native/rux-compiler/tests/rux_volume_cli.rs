use rux_compiler::artifact::Rux16ArtifactTarget;
use rux_compiler::compile_rux16_artifact;
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
    let boot_path = temp_file("boot.bin");
    fs::write(&boot_path, [0x01, 0x02, 0x03, 0x04]).expect("boot writes");

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
    assert_eq!(u32::from_le_bytes(payload[4..8].try_into().unwrap()), 2048);
    assert_eq!(u32::from_le_bytes(payload[8..12].try_into().unwrap()), 2048);
    assert_eq!(u32::from_le_bytes(payload[12..16].try_into().unwrap()), 1);
    assert_eq!(u32::from_le_bytes(payload[16..20].try_into().unwrap()), 1);
    assert_eq!(&payload[512..516], &[0x01, 0x02, 0x03, 0x04]);
    assert!(payload[516..1024].iter().all(|byte| *byte == 0));
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
