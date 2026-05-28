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
fn rux_volume_init_writes_ruxpt_boot_and_root_partitions() {
    let path = temp_file("init-storage0.ruxvol");
    let output = Command::new(rux_binary())
        .args(["volume", "init", path.to_str().unwrap(), "--size", "65536"])
        .output()
        .expect("rux runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let bytes = fs::read(&path).expect("volume exists");
    assert_eq!(&bytes[..6], b"RUXVOL");
    assert_eq!(u64::from_le_bytes(bytes[8..16].try_into().unwrap()), 65536);

    let payload = &bytes[16..];
    assert_eq!(&payload[0..5], b"RUXPT");
    assert_eq!(payload[5], 1);
    assert_eq!(payload[6], 2);

    assert_eq!(&payload[16..20], b"BOOT");
    assert_eq!(u32::from_le_bytes(payload[24..28].try_into().unwrap()), 1);
    assert_eq!(u32::from_le_bytes(payload[28..32].try_into().unwrap()), 32);
    assert_eq!(&payload[32..36], b"boot");

    assert_eq!(&payload[48..52], b"ROOT");
    assert_eq!(u32::from_le_bytes(payload[56..60].try_into().unwrap()), 33);
    assert_eq!(u32::from_le_bytes(payload[60..64].try_into().unwrap()), 95);
    assert_eq!(&payload[64..68], b"root");
}

#[test]
fn rux_volume_extracts_and_replaces_partition_bytes_by_name() {
    let volume_path = temp_file("partition-bridge-storage0.ruxvol");
    let root_path = temp_file("root-partition.bin");
    let extracted_path = temp_file("root-partition-extracted.bin");
    let mut root_bytes = vec![0_u8; 95 * 512];
    root_bytes[0..6].copy_from_slice(b"ROOTFS");
    root_bytes[4096..4101].copy_from_slice(b"hello");
    fs::write(&root_path, &root_bytes).expect("root partition writes");

    assert!(Command::new(rux_binary())
        .args([
            "volume",
            "init",
            volume_path.to_str().unwrap(),
            "--size",
            "65536",
        ])
        .status()
        .expect("init runs")
        .success());

    let replace_output = Command::new(rux_binary())
        .args([
            "volume",
            "replace-partition",
            volume_path.to_str().unwrap(),
            "ROOT",
            root_path.to_str().unwrap(),
        ])
        .output()
        .expect("replace-partition runs");
    assert!(
        replace_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&replace_output.stderr)
    );

    let extract_output = Command::new(rux_binary())
        .args([
            "volume",
            "extract-partition",
            volume_path.to_str().unwrap(),
            "ROOT",
            extracted_path.to_str().unwrap(),
        ])
        .output()
        .expect("extract-partition runs");
    assert!(
        extract_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&extract_output.stderr)
    );
    assert_eq!(fs::read(extracted_path).expect("extract reads"), root_bytes);

    let volume_bytes = fs::read(&volume_path).expect("volume reads");
    let payload = &volume_bytes[16..];
    let root_offset = 33 * 512;
    assert_eq!(&payload[root_offset..root_offset + 6], b"ROOTFS");
    assert_eq!(&payload[root_offset + 4096..root_offset + 4101], b"hello");
}

#[test]
fn rux_volume_inspect_prints_ruxpt_partition_layout() {
    let volume_path = temp_file("inspect-storage0.ruxvol");
    assert!(Command::new(rux_binary())
        .args([
            "volume",
            "init",
            volume_path.to_str().unwrap(),
            "--size",
            "65536",
        ])
        .status()
        .expect("init runs")
        .success());

    let output = Command::new(rux_binary())
        .args(["volume", "inspect", volume_path.to_str().unwrap()])
        .output()
        .expect("inspect runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_eq!(
        String::from_utf8(output.stdout).expect("inspect stdout is UTF-8"),
        "RUXVOL v1 payload=65536\nRUXPT v1 entries=2\nBOOT start_lba=1 blocks=32 bytes=16384 name=boot\nROOT start_lba=33 blocks=95 bytes=48640 name=root\n"
    );
}

#[test]
fn rux_volume_replace_partition_rejects_wrong_size_without_truncation() {
    let volume_path = temp_file("partition-size-storage0.ruxvol");
    let root_path = temp_file("oversized-root-partition.bin");
    fs::write(&root_path, vec![0x7f_u8; 95 * 512 + 1]).expect("root partition writes");

    assert!(Command::new(rux_binary())
        .args([
            "volume",
            "init",
            volume_path.to_str().unwrap(),
            "--size",
            "65536",
        ])
        .status()
        .expect("init runs")
        .success());

    let output = Command::new(rux_binary())
        .args([
            "volume",
            "replace-partition",
            volume_path.to_str().unwrap(),
            "ROOT",
            root_path.to_str().unwrap(),
        ])
        .output()
        .expect("replace-partition runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        stderr.contains("partition `ROOT` is 48640 bytes but input has 48641 bytes"),
        "stderr: {stderr}"
    );
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
fn rux16_kernel_loader_source_uses_guest_storage0_read_helper() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/kernel_loader.rx"),
    )
    .expect("kernel loader source should exist");

    assert!(
        source.contains("fn read_storage0_blocks("),
        "kernel loader should expose one guest helper for storage0 block reads"
    );
    assert_eq!(
        source
            .matches("mmio<i32>(storage0::COMMAND).store(storage0::COMMAND_READ_BLOCKS)")
            .count(),
        1,
        "storage0 read command setup should live in the helper only"
    );
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
        "use rux::abi::computer::{control, display0};

        fn main() {
            unsafe {
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_CLEAR);
                mmio<i32>(display0::CURSOR_X).store(0);
                mmio<i32>(display0::CURSOR_Y).store(0);
                mmio<i32>(display0::DATA).store(75);
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(display0::DATA).store(69);
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(display0::DATA).store(82);
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(display0::DATA).store(78);
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(display0::DATA).store(69);
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(display0::DATA).store(76);
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(display0::DATA).store(32);
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(display0::DATA).store(79);
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(display0::DATA).store(75);
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(control::PANIC_CODE).store(75);
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
