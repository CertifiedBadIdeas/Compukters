use k16_tools::artifact::K16ArtifactTarget;
use k16_tools::compile_k16_artifact;
use k16_tools::k16e;
use k16_tools::k16fs;
use k16_tools::volume;
use k16_vm::k16::K16Signal;
use k16_vm::k16_computer::{K16ComputerHandle, K16ComputerTextDisplaySnapshot};
use std::fs;
use std::path::Path;
use std::path::PathBuf;
use std::process::Command;

#[test]
fn k16_volume_create_writes_empty_k16vol_header() {
    let path = temp_file("create-storage0.kv");
    let output = Command::new(k16_binary())
        .args(["volume", "create", path.to_str().unwrap(), "--size", "4096"])
        .output()
        .expect("k16 runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let bytes = fs::read(&path).expect("volume exists");
    assert_eq!(&bytes[..6], b"K16VOL");
    assert_eq!(u16::from_le_bytes(bytes[6..8].try_into().unwrap()), 1);
    assert_eq!(u64::from_le_bytes(bytes[8..16].try_into().unwrap()), 4096);
    assert_eq!(bytes.len(), 16 + 4096);
    assert!(bytes[16..].iter().all(|byte| *byte == 0));
}

#[test]
fn k16_volume_init_writes_k16pt_boot_and_root_partitions() {
    let path = temp_file("init-storage0.kv");
    let output = Command::new(k16_binary())
        .args(["volume", "init", path.to_str().unwrap(), "--size", "65536"])
        .output()
        .expect("k16 runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let bytes = fs::read(&path).expect("volume exists");
    assert_eq!(&bytes[..6], b"K16VOL");
    assert_eq!(u64::from_le_bytes(bytes[8..16].try_into().unwrap()), 65536);

    let payload = &bytes[16..];
    assert_eq!(&payload[0..5], b"K16PT");
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
fn k16_volume_extracts_and_replaces_partition_bytes_by_name() {
    let volume_path = temp_file("partition-bridge-storage0.kv");
    let root_path = temp_file("root-partition.bin");
    let extracted_path = temp_file("root-partition-extracted.bin");
    let mut root_bytes = vec![0_u8; 95 * 512];
    root_bytes[0..6].copy_from_slice(b"ROOTFS");
    root_bytes[4096..4101].copy_from_slice(b"hello");
    fs::write(&root_path, &root_bytes).expect("root partition writes");

    assert!(Command::new(k16_binary())
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

    let replace_output = Command::new(k16_binary())
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

    let extract_output = Command::new(k16_binary())
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
fn k16_volume_inspect_prints_k16pt_partition_layout() {
    let volume_path = temp_file("inspect-storage0.kv");
    assert!(Command::new(k16_binary())
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

    let output = Command::new(k16_binary())
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
        "K16VOL v1 payload=65536\nK16PT v1 entries=2\nBOOT start_lba=1 blocks=32 bytes=16384 name=boot\nROOT start_lba=33 blocks=95 bytes=48640 name=root\n"
    );
}

#[test]
fn k16_volume_inspect_boot_prints_boot_chain_metadata() {
    let volume_path = temp_file("inspect-boot-storage0.kv");
    let root_path = temp_file("inspect-boot-root.kfs");
    let boot_path = temp_file("inspect-boot-loader.kb");
    let kernel_path = temp_file("inspect-boot-kernel.kx");
    let boot_bytes =
        k16e::encode_k16_executable(&[0x10, 0x20], k16e::K16eAbiKind::Bootloader, 0x2000, 0x2000)
            .expect("boot K16E encodes");
    let kernel_bytes =
        k16e::encode_k16_executable(&[0x30, 0x40], k16e::K16eAbiKind::Kernel, 0x3000, 0x3000)
            .expect("kernel K16E encodes");
    fs::write(&boot_path, &boot_bytes).expect("boot writes");
    fs::write(&kernel_path, &kernel_bytes).expect("kernel writes");

    assert!(Command::new(k16_binary())
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
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "put-boot",
            volume_path.to_str().unwrap(),
            boot_path.to_str().unwrap(),
        ])
        .status()
        .expect("put-boot runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "fs",
            "kfs",
            "format",
            root_path.to_str().unwrap(),
            "--blocks",
            "95",
        ])
        .status()
        .expect("k16fs format runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "replace-partition",
            volume_path.to_str().unwrap(),
            "ROOT",
            root_path.to_str().unwrap(),
        ])
        .status()
        .expect("replace ROOT runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "put-kernel",
            volume_path.to_str().unwrap(),
            kernel_path.to_str().unwrap(),
        ])
        .status()
        .expect("put-kernel runs")
        .success());

    let output = Command::new(k16_binary())
        .args(["volume", "inspect-boot", volume_path.to_str().unwrap()])
        .output()
        .expect("inspect-boot runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_eq!(
        String::from_utf8(output.stdout).expect("inspect-boot stdout is UTF-8"),
        "K16VOL boot-chain\nBOOT partition start_lba=1 blocks=32 bytes=16384 name=boot\nBOOT K16FS /boot/loader.kb file_bytes=54\nBOOTLOADER K16E abi=bootloader entry_pc=0x00002000 load_addr=0x00002000 payload_bytes=2\nROOT partition start_lba=33 blocks=95 bytes=48640 name=root\nROOT K16FS /boot/kernel.kx file_bytes=54\nKERNEL K16E abi=kernel entry_pc=0x00003000 load_addr=0x00003000 payload_bytes=2\n"
    );
}

#[test]
fn k16_volume_inspect_boot_rejects_missing_root_kernel() {
    let volume_path = temp_file("inspect-boot-missing-kernel-storage0.kv");
    let root_path = temp_file("inspect-boot-missing-kernel-root.kfs");
    let boot_path = temp_file("inspect-boot-missing-kernel-loader.kb");
    let boot_bytes =
        k16e::encode_k16_executable(&[0x10, 0x20], k16e::K16eAbiKind::Bootloader, 0x2000, 0x2000)
            .expect("boot K16E encodes");
    fs::write(&boot_path, &boot_bytes).expect("boot writes");

    assert!(Command::new(k16_binary())
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
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "put-boot",
            volume_path.to_str().unwrap(),
            boot_path.to_str().unwrap(),
        ])
        .status()
        .expect("put-boot runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "fs",
            "kfs",
            "format",
            root_path.to_str().unwrap(),
            "--blocks",
            "95",
        ])
        .status()
        .expect("k16fs format runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "replace-partition",
            volume_path.to_str().unwrap(),
            "ROOT",
            root_path.to_str().unwrap(),
        ])
        .status()
        .expect("replace ROOT runs")
        .success());

    let output = Command::new(k16_binary())
        .args(["volume", "inspect-boot", volume_path.to_str().unwrap()])
        .output()
        .expect("inspect-boot runs");

    assert!(!output.status.success());
    assert!(String::from_utf8_lossy(&output.stderr)
        .contains("ROOT/K16FS /boot/kernel.kx is not readable"));
}

#[test]
fn k16_volume_replace_partition_rejects_wrong_size_without_truncation() {
    let volume_path = temp_file("partition-size-storage0.kv");
    let root_path = temp_file("oversized-root-partition.bin");
    fs::write(&root_path, vec![0x7f_u8; 95 * 512 + 1]).expect("root partition writes");

    assert!(Command::new(k16_binary())
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

    let output = Command::new(k16_binary())
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
fn k16_volume_put_boot_rejects_non_partitioned_volume_without_fixed_record_fallback() {
    let volume_path = temp_file("boot-storage0.kv");
    let boot_path = temp_file("boot.k16e");
    fs::write(
        &boot_path,
        k16e::encode_k16_executable(
            &[0x01, 0x02, 0x03, 0x04],
            k16e::K16eAbiKind::Bootloader,
            0x900,
            0x900,
        )
        .expect("K16E encodes"),
    )
    .expect("boot writes");

    assert!(Command::new(k16_binary())
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
    let output = Command::new(k16_binary())
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
        stderr.contains("put-boot requires a K16PT partitioned volume"),
        "stderr: {stderr}"
    );
}

#[test]
fn k16_volume_put_boot_installs_loader_kb_in_boot_k16fs_partition() {
    let volume_path = temp_file("partitioned-boot-storage0.kv");
    let boot_path = temp_file("partitioned-boot.k16e");
    fs::write(
        &boot_path,
        k16e::encode_k16_executable(
            &[0x01, 0x02, 0x03, 0x04],
            k16e::K16eAbiKind::Bootloader,
            0x900,
            0x900,
        )
        .expect("K16E encodes"),
    )
    .expect("boot writes");

    assert!(Command::new(k16_binary())
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
    let output = Command::new(k16_binary())
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
    assert_eq!(&payload[0..5], b"K16PT");
    let boot = volume::extract_partition(&bytes, "BOOT").expect("BOOT extracts");
    assert_eq!(
        k16fs::read_file(&boot, "/boot/loader.kb").expect("loader reads from BOOT"),
        k16e::encode_k16_executable(
            &[0x01, 0x02, 0x03, 0x04],
            k16e::K16eAbiKind::Bootloader,
            0x900,
            0x900,
        )
        .expect("K16E encodes")
    );
    assert!(
        !boot.windows(4).any(|window| window == b"K16B"),
        "partitioned put-boot should not write the fixed K16B record"
    );
}

#[test]
fn k16_volume_put_kernel_installs_kernel_kx_in_root_k16fs() {
    let volume_path = temp_file("kernel-rootfs-storage0.kv");
    let root_path = temp_file("kernel-rootfs-root.kfs");
    let kernel_path = temp_file("kernel-rootfs-kernel.kx");
    let kernel_bytes = k16e::encode_k16_executable(
        &[0x01, 0x02, 0x03, 0x04],
        k16e::K16eAbiKind::Kernel,
        0x4000,
        0x4000,
    )
    .expect("K16E encodes");
    fs::write(&kernel_path, &kernel_bytes).expect("kernel writes");

    assert!(Command::new(k16_binary())
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
    assert!(Command::new(k16_binary())
        .args([
            "fs",
            "kfs",
            "format",
            root_path.to_str().unwrap(),
            "--blocks",
            "95",
        ])
        .status()
        .expect("k16fs format runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "replace-partition",
            volume_path.to_str().unwrap(),
            "ROOT",
            root_path.to_str().unwrap(),
        ])
        .status()
        .expect("replace ROOT runs")
        .success());
    let output = Command::new(k16_binary())
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
    let root = volume::extract_partition(&bytes, "ROOT").expect("ROOT extracts");
    assert_eq!(
        k16fs::read_file(&root, "/boot/kernel.kx").expect("kernel reads from ROOT"),
        kernel_bytes
    );
    assert!(
        !bytes.windows(4).any(|window| window == b"K16K"),
        "put-kernel should not write the fixed K16K record"
    );
}

#[test]
fn k16_volume_init_creates_root_k16fs_for_put_kernel() {
    let volume_path = temp_file("init-rootfs-storage0.kv");
    let kernel_path = temp_file("init-rootfs-kernel.kx");
    let kernel_bytes =
        k16e::encode_k16_executable(&[0x10, 0x20], k16e::K16eAbiKind::Kernel, 0x3000, 0x3000)
            .expect("K16E encodes");
    fs::write(&kernel_path, &kernel_bytes).expect("kernel writes");

    assert!(Command::new(k16_binary())
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
    let output = Command::new(k16_binary())
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
    let root = volume::extract_partition(&bytes, "ROOT").expect("ROOT extracts");
    assert_eq!(
        k16fs::read_file(&root, "/boot/kernel.kx").expect("kernel reads from ROOT"),
        kernel_bytes
    );
}

#[test]
fn k16_volume_put_kernel_rejects_boot_artifact_without_profile_fallback() {
    let volume_path = temp_file("boot-as-kernel-storage0.kv");
    let kernel_path = temp_file("boot-as-kernel.kx");
    fs::write(
        &kernel_path,
        k16e::encode_k16_executable(&[0x01, 0x00], k16e::K16eAbiKind::Bootloader, 0x800, 0x800)
            .expect("K16E encodes"),
    )
    .expect("boot writes");

    assert!(Command::new(k16_binary())
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
    let output = Command::new(k16_binary())
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
        stderr.contains("kernel media requires K16E kernel ABI kind"),
        "stderr: {stderr}"
    );
}

#[test]
fn k16_volume_put_boot_rejects_kernel_artifact_without_profile_fallback() {
    let volume_path = temp_file("kernel-storage0.kv");
    let boot_path = temp_file("kernel.kb");
    fs::write(
        &boot_path,
        k16e::encode_k16_executable(&[0x01, 0x00], k16e::K16eAbiKind::Kernel, 0x4000, 0x4000)
            .expect("K16E encodes"),
    )
    .expect("kernel writes");

    assert!(Command::new(k16_binary())
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
    let output = Command::new(k16_binary())
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
        stderr.contains("boot media requires K16E bootloader ABI kind"),
        "stderr: {stderr}"
    );
}

#[test]
fn k16_volume_put_boot_rejects_raw_boot_bytes_without_k16e_fallback() {
    let volume_path = temp_file("raw-boot-storage0.kv");
    let boot_path = temp_file("raw-boot.bin");
    fs::write(&boot_path, [0x01, 0x00]).expect("boot writes");

    assert!(Command::new(k16_binary())
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
    let output = Command::new(k16_binary())
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
    assert!(stderr.contains("invalid K16E magic"), "stderr: {stderr}");
}

#[test]
fn k16_kernel_loader_source_uses_guest_storage0_read_helper() {
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
fn k16_kernel_loader_source_probes_k16pt_header() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/kernel_loader.rx"),
    )
    .expect("kernel loader source should exist");

    assert!(
        source.contains("fn probe_k16pt_header("),
        "kernel loader should expose a guest-side K16PT header probe"
    );
    assert!(
        source.contains("read_storage0_blocks(0, 1, 0x3000)"),
        "K16PT probe should read LBA0 through the storage0 helper"
    );
    assert!(
        source.contains("0x5036314b"),
        "K16PT probe should check the little-endian `K16P` prefix"
    );
    assert!(
        source.contains("84u8"),
        "K16PT probe should check the trailing `T` byte"
    );
    assert!(source.contains("1u8"), "K16PT probe should check version 1");
}

#[test]
fn k16_kernel_loader_source_resolves_root_partition() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/kernel_loader.rx"),
    )
    .expect("kernel loader source should exist");

    assert!(
        source.contains("fn find_root_partition_start_lba("),
        "kernel loader should expose a guest-side ROOT partition resolver"
    );
    assert!(
        source.contains("ptr<u8>(0x3006u32).load()"),
        "ROOT resolver should read the K16PT entry count"
    );
    assert!(
        source.contains("if probe_k16pt_header() == 1"),
        "ROOT resolver should validate the K16PT header before scanning entries"
    );
    assert!(
        source.contains("let mut entry_addr: u32 = 0x3010u32"),
        "ROOT resolver should start scanning K16PT entries after the 16-byte header"
    );
    assert!(
        source.contains("entry_addr = entry_addr + 32u32"),
        "ROOT resolver should advance by the 32-byte K16PT entry size"
    );
    assert!(
        source.contains("0x544f4f52"),
        "ROOT resolver should match the little-endian `ROOT` partition type"
    );
    assert!(
        source.contains("let mut start_lba_addr: u32 = entry_addr + 8u32"),
        "ROOT resolver should compute the ROOT entry start_lba field address"
    );
    assert!(
        source.contains("ptr<i32>(start_lba_addr).load()"),
        "ROOT resolver should return the ROOT entry start_lba field"
    );
}

#[test]
fn k16_kernel_loader_source_probes_root_k16fs_superblock() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/kernel_loader.rx"),
    )
    .expect("kernel loader source should exist");

    assert!(
        source.contains("fn probe_root_k16fs_superblock("),
        "kernel loader should expose a guest-side ROOT K16FS superblock probe"
    );
    assert!(
        source.contains("read_storage0_blocks(root_start_lba, 1, 0x3000)"),
        "K16FS probe should read the ROOT partition first block"
    );
    assert!(
        source.contains("0x4636314b"),
        "K16FS probe should check the little-endian `K16F` prefix"
    );
    assert!(
        source.contains("83u8"),
        "K16FS probe should check the trailing `S` byte"
    );
    assert!(
        source.contains("ptr<u8>(0x3005u32).load()"),
        "K16FS probe should read the superblock version byte"
    );
}

#[test]
fn k16_kernel_loader_source_probes_root_k16fs_inode() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/kernel_loader.rx"),
    )
    .expect("kernel loader source should exist");

    assert!(
        source.contains("fn probe_root_k16fs_inode("),
        "kernel loader should expose a guest-side ROOT K16FS inode probe"
    );
    assert!(
        source.contains("ptr<i32>(0x3018u32).load()"),
        "root inode probe should read inode_table_start_block from the superblock"
    );
    assert!(
        source.contains("ptr<i32>(0x3020u32).load()"),
        "root inode probe should read root_inode_id from the superblock"
    );
    assert!(
        source.contains("read_storage0_blocks(inode_table_lba, 1, 0x3000)"),
        "root inode probe should read the inode table block"
    );
    assert!(
        source.contains("ptr<u8>(0x3040u32).load()"),
        "root inode probe should read root inode 1 state from the inode table"
    );
    assert!(
        source.contains("2u8"),
        "root inode probe should require the root inode to be a directory"
    );
}

#[test]
fn k16_kernel_loader_source_finds_boot_directory_entry() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/kernel_loader.rx"),
    )
    .expect("kernel loader source should exist");

    assert!(
        source.contains("fn find_boot_directory_inode("),
        "kernel loader should expose a guest-side boot directory lookup"
    );
    assert!(
        source.contains("ptr<i32>(0x3060u32).load()"),
        "boot lookup should read the root directory first extent start block"
    );
    assert!(
        source.contains("read_storage0_blocks(root_dir_lba, 1, 0x3000)"),
        "boot lookup should read the root directory block"
    );
    assert!(
        source.contains("let mut entry_addr: u32 = 0x3000u32"),
        "boot lookup should scan directory entries from the loaded block"
    );
    assert!(
        source.contains("entry_addr = entry_addr + 64u32"),
        "boot lookup should advance by the 64-byte K16FS directory entry size"
    );
    assert!(
        source.contains("0x746f6f62"),
        "boot lookup should match the little-endian `boot` directory name"
    );
    assert!(
        source.contains("ptr<i32>(inode_id_addr).load()"),
        "boot lookup should return the matched directory inode id"
    );
}

#[test]
fn k16_kernel_loader_source_finds_kernel_kx_entry() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/kernel_loader.rx"),
    )
    .expect("kernel loader source should exist");

    assert!(
        source.contains("fn find_kernel_kx_inode("),
        "kernel loader should expose a guest-side kernel.kx lookup"
    );
    assert!(
        source.contains("boot_directory_inode"),
        "kernel.kx lookup should take the boot directory inode id"
    );
    assert!(
        source.contains("read_storage0_blocks(inode_table_lba, 1, 0x3000)"),
        "kernel.kx lookup should read the inode table block"
    );
    assert!(
        source.contains("read_storage0_blocks(boot_dir_lba, 1, 0x3000)"),
        "kernel.kx lookup should read the boot directory block"
    );
    assert!(
        source.contains("0x6e72656b"),
        "kernel.kx lookup should match the little-endian `kern` prefix"
    );
    assert!(
        source.contains("0x6b2e6c65"),
        "kernel.kx lookup should match the little-endian `el.k` middle"
    );
    assert!(
        source.contains("0x00000078"),
        "kernel.kx lookup should match the little-endian `x` suffix"
    );
    assert!(
        source.contains("ptr<i32>(inode_id_addr).load()"),
        "kernel.kx lookup should return the matched file inode id"
    );
}

#[test]
fn k16_kernel_loader_source_reads_kernel_kx_inode_metadata() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/kernel_loader.rx"),
    )
    .expect("kernel loader source should exist");

    assert!(
        source.contains("fn probe_kernel_kx_inode_metadata("),
        "kernel loader should expose a guest-side kernel.kx inode metadata probe"
    );
    assert!(
        source.contains("kernel_kx_inode"),
        "kernel.kx metadata probe should take the kernel.kx inode id"
    );
    assert!(
        source.contains("read_storage0_blocks(inode_table_lba, 1, 0x3000)"),
        "kernel.kx metadata probe should read the inode table block"
    );
    assert!(
        source.contains("ptr<u8>(inode_addr).load()"),
        "kernel.kx metadata probe should read the file inode state"
    );
    assert!(
        source.contains("1u8"),
        "kernel.kx metadata probe should require a file inode"
    );
    assert!(
        source.contains("inode_addr + 8u32"),
        "kernel.kx metadata probe should read the file size field"
    );
    assert!(
        source.contains("inode_addr + 32u32"),
        "kernel.kx metadata probe should read the first extent start block"
    );
    assert!(
        source.contains("inode_addr + 36u32"),
        "kernel.kx metadata probe should read the first extent block count"
    );
}

#[test]
fn k16_kernel_loader_source_loads_kernel_kx_from_root_k16fs() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/kernel_loader.rx"),
    )
    .expect("kernel loader source should exist");

    assert!(
        source.contains("fn load_kernel_kx_from_root_k16fs("),
        "kernel loader should expose a guest-side kernel.kx file load helper"
    );
    assert!(
        source.contains("let mut kernel_lba: i32 = root_start_lba + kernel_start_block"),
        "kernel.kx loader should translate the file extent block into an absolute storage LBA"
    );
    assert!(
        source.contains("read_storage0_blocks(kernel_lba, kernel_block_count, 0x6000)"),
        "kernel.kx loader should read file extent bytes into RAM"
    );
    assert!(
        source.contains("ptr<i32>(0x6000u32).load()"),
        "kernel.kx loader should inspect the loaded file header from RAM"
    );
    assert!(
        source.contains("0x4536314b"),
        "kernel.kx loader should validate the loaded K16E magic"
    );
}

#[test]
fn k16_kernel_loader_source_executes_kernel_kx_from_root_k16fs() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/kernel_loader.rx"),
    )
    .expect("kernel loader source should exist");

    assert!(
        source.contains("fn execute_loaded_kernel_kx("),
        "kernel loader should expose a guest-side K16E execution helper"
    );
    assert!(
        source.contains("ptr<i32>(0x600cu32).load()"),
        "K16E execution should read the entry_pc field"
    );
    assert!(
        source.contains("ptr<u32>(0x6024u32).load()"),
        "K16E execution should read the load_addr field from the first section"
    );
    assert!(
        source.contains("ptr<u32>(0x6028u32).load()"),
        "K16E execution should read the payload file offset"
    );
    assert!(
        source.contains("ptr<u32>(0x602cu32).load()"),
        "K16E execution should read the payload file size"
    );
    assert!(
        source.contains("ptr<u8>(source_addr).load()"),
        "K16E execution should copy payload bytes from the loaded K16E file"
    );
    assert!(
        source.contains("ptr<u8>(dest_addr).store(byte)"),
        "K16E execution should copy payload bytes to the load address"
    );
    assert!(
        source.contains("k16_jump(entry_pc)"),
        "K16E execution should jump to the K16E entry point"
    );
    assert!(
        !source.contains("0x4b585552"),
        "kernel loader should not keep the fixed K16K record execution path"
    );
}

#[test]
fn k16_kernel_loader_source_writes_kernel_boot_info() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/kernel_loader.rx"),
    )
    .expect("kernel loader source should exist");

    assert!(
        source.contains("fn write_kernel_boot_info("),
        "kernel loader should expose a guest-side RKBI writer"
    );
    assert!(
        source.contains("ptr<i32>(0x3f00u32).store(0x49424b52)"),
        "RKBI writer should store the little-endian `RKBI` magic"
    );
    assert!(
        source.contains("ptr<i32>(0x3f04u32).store(0x00140001)"),
        "RKBI writer should store version 1 and 20-byte header size"
    );
    assert!(
        source.contains("ptr<i32>(0x3f08u32).store(root_start_lba)"),
        "RKBI writer should pass the ROOT partition start LBA to the kernel"
    );
    assert!(
        source.contains("ptr<i32>(0x3f0cu32).store(kernel_kx_size_bytes)"),
        "RKBI writer should pass the loaded kernel K16E size"
    );
    assert!(
        source.contains("write_kernel_boot_info(root_start_lba, kernel_kx_size_bytes)"),
        "kernel loader should write RKBI immediately before executing the loaded kernel"
    );
}

#[test]
fn k16_init_loader_source_reads_kernel_boot_info() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/kernel/init_loader.rx"),
    )
    .expect("init loader source should exist");

    assert!(
        source.contains("fn read_kernel_boot_info_root_start_lba("),
        "kernel should expose a guest-side RKBI reader"
    );
    assert!(
        source.contains("ptr<i32>(0x3f00u32).load()"),
        "RKBI reader should read the boot info magic from RAM"
    );
    assert!(
        source.contains("0x49424b52"),
        "RKBI reader should validate the little-endian `RKBI` magic"
    );
    assert!(
        source.contains("ptr<i32>(0x3f04u32).load()"),
        "RKBI reader should validate the version and header size word"
    );
    assert!(
        source.contains("ptr<i32>(0x3f08u32).load()"),
        "RKBI reader should read the ROOT partition start LBA field"
    );
    assert!(
        source.contains("let mut root_start_lba: i32 = read_kernel_boot_info_root_start_lba()"),
        "kernel should use RKBI root_start_lba before loading init"
    );
    assert!(
        !source.contains("fn find_root_partition_start_lba("),
        "kernel should not keep a ROOT partition rediscovery path"
    );
    assert!(
        source.contains("write_kernel_boot_info_failed()"),
        "kernel should fail explicitly when RKBI is absent or invalid"
    );
}

#[test]
fn k16_init_loader_source_writes_init_handoff_info() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/kernel/init_loader.rx"),
    )
    .expect("init loader source should exist");

    assert!(
        source.contains("fn write_init_handoff_info("),
        "kernel should expose a guest-side RINI writer"
    );
    assert!(
        source.contains("ptr<i32>(0x3f20u32).store(0x494e4952)"),
        "RINI writer should store the little-endian `RINI` magic"
    );
    assert!(
        source.contains("ptr<i32>(0x3f24u32).store(0x00180001)"),
        "RINI writer should store version 1 and 24-byte header size"
    );
    assert!(
        source.contains("ptr<i32>(0x3f28u32).store(root_start_lba)"),
        "RINI writer should pass the ROOT partition start LBA to init"
    );
    assert!(
        source.contains("ptr<i32>(0x3f2cu32).store(init_kx_size_bytes)"),
        "RINI writer should pass the loaded init K16E size"
    );
    assert!(
        source.contains("ptr<i32>(0x3f30u32).store(init_entry_pc)"),
        "RINI writer should pass the init entry point"
    );
    assert!(
        source.contains("write_init_handoff_info(root_start_lba, init_kx_size_bytes, entry_pc)"),
        "kernel should write RINI immediately before entering init"
    );
    let write_index = source
        .find("write_init_handoff_info(root_start_lba, init_kx_size_bytes, entry_pc)")
        .expect("RINI writer call should exist");
    let jump_index = source
        .find("k16_jump(entry_pc)")
        .expect("init jump should exist");
    assert!(
        write_index < jump_index,
        "kernel should write RINI before jumping to init"
    );
}

#[test]
fn k16_init_loader_source_installs_kernel_trap_handler() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/kernel/init_loader.rx"),
    )
    .expect("kernel init loader source should exist");

    assert!(
        source.contains("const TRAP_HANDLER_ADDR: u32 = 0x7000u32;"),
        "kernel should reserve an explicit trap handler address"
    );
    assert!(
        source.contains("fn install_trap_handler()"),
        "kernel should install a trap handler before userspace starts"
    );
    assert!(
        source.contains("k16_write_csr(1u32, TRAP_HANDLER_ADDR)"),
        "kernel should set the K16 trap vector CSR"
    );
    assert!(
        source.find("install_trap_handler();").unwrap()
            < source.find("k16_jump(entry_pc)").unwrap(),
        "kernel should install the trap handler before jumping to init"
    );
}

#[test]
fn k16_rini_init_source_reads_and_validates_handoff() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/init/rini_init.rx"),
    )
    .expect("RINI init source should exist");

    assert!(
        source.contains("fn read_rini_handoff_valid("),
        "init should expose a RINI validation helper"
    );
    assert!(
        source.contains("ptr<i32>(0x3f20u32).load()"),
        "init should read the RINI magic from guest RAM"
    );
    assert!(
        source.contains("0x494e4952"),
        "init should validate the little-endian `RINI` magic"
    );
    assert!(
        source.contains("ptr<i32>(0x3f24u32).load()"),
        "init should read the RINI version and size word"
    );
    assert!(
        source.contains("0x00180001"),
        "init should require RINI version 1 and 24-byte header size"
    );
    assert!(
        source.contains("ptr<i32>(0x3f28u32).load()"),
        "init should read root_start_lba"
    );
    assert!(
        source.contains("root_start_lba == 33"),
        "init should validate the current ROOT partition LBA"
    );
    assert!(
        source.contains("ptr<i32>(0x3f30u32).load()"),
        "init should read init_entry_pc"
    );
    assert!(
        source.contains("init_entry_pc == 0x8000"),
        "init should validate its entry point"
    );
    assert!(
        source.contains("ptr<i32>(0x3f34u32).load()"),
        "init should read RINI flags"
    );
    assert!(
        source.contains("flags == 0"),
        "init should reject unsupported RINI flags"
    );
    assert!(
        source.contains("write_init_ok()"),
        "init should report success only after validation"
    );
    assert!(
        source.contains("write_init_failed()"),
        "init should fail explicitly when RINI is invalid"
    );
}

#[test]
fn k16_trap_init_source_requests_kernel_console_output() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/init/trap_init.rx"),
    )
    .expect("trap init source should exist");

    assert!(
        source.contains("fn read_rini_handoff_valid("),
        "trap init should validate RINI before making a kernel request"
    );
    assert!(
        source.contains("k16_write_csr(2u32, 1u32)"),
        "trap init should trigger the first kernel trap syscall"
    );
    assert!(
        !source.contains("display0::"),
        "trap init should not write display0 directly"
    );
}

#[test]
fn k16_init_loader_source_rejects_protected_init_load_address() {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/kernel/init_loader.rx"),
    )
    .expect("init loader source should exist");

    assert!(
        source.contains("if load_addr < 0x8000u32"),
        "kernel should reject init load addresses below the user-space program region"
    );
}

#[test]
fn k16_volume_put_boot_and_kernel_creates_storage0_that_bundled_bios_executes() {
    let volume_path = temp_file("boot-kernel-storage0.kv");
    let root_path = temp_file("boot-kernel-root.kfs");
    let boot_path = temp_file("kernel-loader.boot");
    let kernel_source_path = temp_file("kernel.rx");
    let kernel_path = temp_file("boot-kernel-kernel.kx");
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

    compile_source_file_to_path(&boot_source_path, K16ArtifactTarget::Boot, &boot_path);
    compile_source_file_to_path(&kernel_source_path, K16ArtifactTarget::Kernel, &kernel_path);
    assert!(Command::new(k16_binary())
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
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "put-boot",
            volume_path.to_str().unwrap(),
            boot_path.to_str().unwrap(),
        ])
        .status()
        .expect("put-boot runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "fs",
            "kfs",
            "format",
            root_path.to_str().unwrap(),
            "--blocks",
            "95",
        ])
        .status()
        .expect("k16fs format runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "replace-partition",
            volume_path.to_str().unwrap(),
            "ROOT",
            root_path.to_str().unwrap(),
        ])
        .status()
        .expect("replace ROOT runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "put-kernel",
            volume_path.to_str().unwrap(),
            kernel_path.to_str().unwrap(),
        ])
        .status()
        .expect("put-kernel runs")
        .success());

    let bios = compile_bundled_k16_bios();
    let mut handle = K16ComputerHandle::create_k16_bios_flash_with_storage0_path(
        &bios,
        64 * 1024,
        1_000_000,
        &volume_path,
    )
    .expect("K16 BIOS flash computer creates with CLI boot/kernel volume path");

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    let snapshot = handle
        .display0_snapshot()
        .expect("computer profile maps display0");
    assert_eq!(display_row(&snapshot, 0), "KERNEL OK");
    assert_eq!(handle.control().panic_code, 75);
}

#[test]
fn k16_volume_boot_kernel_and_init_executes_init_from_root_k16fs() {
    let volume_path = temp_file("boot-kernel-init-storage0.kv");
    let root_path = temp_file("boot-kernel-init-root.kfs");
    let boot_path = temp_file("boot-kernel-init-loader.boot");
    let kernel_path = temp_file("boot-kernel-init-kernel.kx");
    let init_source_path = temp_file("init.rx");
    let init_path = temp_file("init.kx");
    let boot_source_path =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/kernel_loader.rx");
    let kernel_source_path =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/kernel/init_loader.rx");
    fs::write(
        &init_source_path,
        "use rux::abi::computer::{display0};

        fn main() {
            unsafe {
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_CLEAR);
                mmio<i32>(display0::CURSOR_X).store(0);
                mmio<i32>(display0::CURSOR_Y).store(0);
                mmio<i32>(display0::DATA).store(73);
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(display0::DATA).store(78);
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(display0::DATA).store(73);
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(display0::DATA).store(84);
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(display0::DATA).store(32);
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(display0::DATA).store(79);
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_PUT_BYTE_AT_CURSOR);
                mmio<i32>(display0::DATA).store(75);
                mmio<i32>(display0::COMMAND).store(display0::COMMAND_PUT_BYTE_AT_CURSOR);
            }
        }",
    )
    .expect("init source writes");

    compile_source_file_to_path(&boot_source_path, K16ArtifactTarget::Boot, &boot_path);
    compile_source_file_to_path(&kernel_source_path, K16ArtifactTarget::Kernel, &kernel_path);
    compile_source_file_to_path(&init_source_path, K16ArtifactTarget::Program, &init_path);

    assert!(Command::new(k16_binary())
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
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "put-boot",
            volume_path.to_str().unwrap(),
            boot_path.to_str().unwrap(),
        ])
        .status()
        .expect("put-boot runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "fs",
            "kfs",
            "format",
            root_path.to_str().unwrap(),
            "--blocks",
            "95",
        ])
        .status()
        .expect("k16fs format runs")
        .success());
    assert!(Command::new(k16_binary())
        .args(["fs", "kfs", "mkdir", root_path.to_str().unwrap(), "/bin"])
        .status()
        .expect("k16fs mkdir /bin runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "fs",
            "kfs",
            "put",
            root_path.to_str().unwrap(),
            "/bin/init.kx",
            init_path.to_str().unwrap(),
        ])
        .status()
        .expect("k16fs put init runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "replace-partition",
            volume_path.to_str().unwrap(),
            "ROOT",
            root_path.to_str().unwrap(),
        ])
        .status()
        .expect("replace ROOT runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "put-kernel",
            volume_path.to_str().unwrap(),
            kernel_path.to_str().unwrap(),
        ])
        .status()
        .expect("put-kernel runs")
        .success());

    let bios = compile_bundled_k16_bios();
    let mut handle = K16ComputerHandle::create_k16_bios_flash_with_storage0_path(
        &bios,
        64 * 1024,
        1_000_000,
        &volume_path,
    )
    .expect("K16 BIOS flash computer creates with CLI boot/kernel/init volume path");

    let signal_result = handle.run_k16_until_signal();
    let snapshot = handle
        .display0_snapshot()
        .expect("computer profile maps display0");
    let signal = signal_result.unwrap_or_else(|error| {
        panic!(
            "run failed: {error}; debug={} panic={} row0={}",
            String::from_utf8_lossy(handle.debug_output_bytes()),
            handle.control().panic_code,
            display_row(&snapshot, 0)
        )
    });
    assert_eq!(
        signal,
        K16Signal::Halt,
        "debug={} panic={} row0={}",
        String::from_utf8_lossy(handle.debug_output_bytes()),
        handle.control().panic_code,
        display_row(&snapshot, 0)
    );
    assert_eq!(display_row(&snapshot, 0), "INIT OK");
}

#[test]
fn k16_volume_boot_kernel_and_rini_init_consumes_handoff() {
    let init_bytes = compile_rini_init_program("rini-init-consumes-handoff-init.kx");
    let volume_path =
        create_boot_kernel_init_volume("rini-init-consumes-handoff", Some(&init_bytes));
    let bios = compile_bundled_k16_bios();
    let mut handle = K16ComputerHandle::create_k16_bios_flash_with_storage0_path(
        &bios,
        64 * 1024,
        1_000_000,
        &volume_path,
    )
    .expect("K16 BIOS flash computer creates with RINI init volume path");

    let signal_result = handle.run_k16_until_signal();
    let snapshot = handle
        .display0_snapshot()
        .expect("computer profile maps display0");
    let signal = signal_result.unwrap_or_else(|error| {
        panic!(
            "run failed: {error}; debug={} panic={} row0={}",
            String::from_utf8_lossy(handle.debug_output_bytes()),
            handle.control().panic_code,
            display_row(&snapshot, 0)
        )
    });
    assert_eq!(signal, K16Signal::Halt);
    assert_eq!(display_row(&snapshot, 0), "INIT OK");
    assert_eq!(handle.control().panic_code, 0);
}

#[test]
fn k16_rini_init_fails_without_handoff() {
    let init_bytes = compile_rini_init_program("rini-init-missing-handoff-init.kx");
    let bios = vec![0x01, 0x00];
    let mut handle = K16ComputerHandle::create_k16_bios_flash(&bios, 64 * 1024, 1_000_000)
        .expect("K16 BIOS flash computer creates");

    handle
        .exec_k16e_program_from_bytes(&init_bytes, 1_000_000)
        .expect("RINI init transfers into K16 execution");
    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    let snapshot = handle
        .display0_snapshot()
        .expect("computer profile maps display0");
    assert_eq!(display_row(&snapshot, 0), "INIT FAILED");
    assert_eq!(handle.control().panic_code, 73);
}

#[test]
fn k16_rini_init_fails_with_invalid_handoff_magic() {
    let init_bytes = compile_rini_init_program("rini-init-invalid-handoff-init.kx");
    let bios = vec![0x01, 0x00];
    let mut handle = K16ComputerHandle::create_k16_bios_flash(&bios, 64 * 1024, 1_000_000)
        .expect("K16 BIOS flash computer creates");
    handle
        .write_guest_ram_bytes(0x3f20, b"BAD!")
        .expect("invalid RINI magic writes");
    handle
        .write_guest_ram_bytes(0x3f24, &0x00180001_u32.to_le_bytes())
        .expect("RINI version and size writes");
    handle
        .write_guest_ram_bytes(0x3f28, &33_u32.to_le_bytes())
        .expect("RINI root_start_lba writes");
    handle
        .write_guest_ram_bytes(0x3f30, &0x8000_u32.to_le_bytes())
        .expect("RINI init_entry_pc writes");

    handle
        .exec_k16e_program_from_bytes(&init_bytes, 1_000_000)
        .expect("RINI init transfers into K16 execution");
    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    let snapshot = handle
        .display0_snapshot()
        .expect("computer profile maps display0");
    assert_eq!(display_row(&snapshot, 0), "INIT FAILED");
    assert_eq!(handle.control().panic_code, 73);
}

#[test]
fn k16_volume_boot_kernel_and_trap_init_uses_kernel_handler() {
    let init_bytes = compile_init_program(
        "examples/init/trap_init.rx",
        "trap-init-kernel-handler-init.kx",
    );
    let volume_path = create_boot_kernel_init_volume("trap-init-kernel-handler", Some(&init_bytes));
    let bios = compile_bundled_k16_bios();
    let mut handle = K16ComputerHandle::create_k16_bios_flash_with_storage0_path(
        &bios,
        64 * 1024,
        1_000_000,
        &volume_path,
    )
    .expect("K16 BIOS flash computer creates with trap init volume path");

    let signal_result = handle.run_k16_until_signal();
    let snapshot = handle
        .display0_snapshot()
        .expect("computer profile maps display0");
    let signal = signal_result.unwrap_or_else(|error| {
        panic!(
            "run failed: {error}; debug={} panic={} row0={}",
            String::from_utf8_lossy(handle.debug_output_bytes()),
            handle.control().panic_code,
            display_row(&snapshot, 0)
        )
    });
    assert_eq!(signal, K16Signal::Halt);
    assert_eq!(display_row(&snapshot, 0), "INIT OK");
    assert_eq!(handle.control().panic_code, 0);
}

#[test]
fn k16_volume_boot_kernel_and_init_runtime_handoff_blocks_match_artifacts() {
    let volume_path = temp_file("runtime-handoff-blocks-storage0.kv");
    let root_path = temp_file("runtime-handoff-blocks-root.kfs");
    let boot_path = temp_file("runtime-handoff-blocks-loader.boot");
    let kernel_path = temp_file("runtime-handoff-blocks-kernel.kx");
    let init_path = temp_file("runtime-handoff-blocks-init.kx");
    let boot_source_path =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/kernel_loader.rx");
    let kernel_source_path =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/kernel/init_loader.rx");
    let init_bytes =
        k16e::encode_k16_executable(&[0x01, 0x00], k16e::K16eAbiKind::Program, 0x8000, 0x8000)
            .expect("init K16E encodes");
    fs::write(&init_path, &init_bytes).expect("init writes");

    compile_source_file_to_path(&boot_source_path, K16ArtifactTarget::Boot, &boot_path);
    compile_source_file_to_path(&kernel_source_path, K16ArtifactTarget::Kernel, &kernel_path);
    let kernel_bytes = fs::read(&kernel_path).expect("kernel K16E reads");

    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "init",
            volume_path.to_str().unwrap(),
            "--size",
            "65536",
        ])
        .status()
        .expect("volume init runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "put-boot",
            volume_path.to_str().unwrap(),
            boot_path.to_str().unwrap(),
        ])
        .status()
        .expect("put-boot runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "fs",
            "kfs",
            "format",
            root_path.to_str().unwrap(),
            "--blocks",
            "95",
        ])
        .status()
        .expect("k16fs format runs")
        .success());
    assert!(Command::new(k16_binary())
        .args(["fs", "kfs", "mkdir", root_path.to_str().unwrap(), "/bin"])
        .status()
        .expect("k16fs mkdir /bin runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "fs",
            "kfs",
            "put",
            root_path.to_str().unwrap(),
            "/bin/init.kx",
            init_path.to_str().unwrap(),
        ])
        .status()
        .expect("k16fs put init runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "replace-partition",
            volume_path.to_str().unwrap(),
            "ROOT",
            root_path.to_str().unwrap(),
        ])
        .status()
        .expect("replace ROOT runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "put-kernel",
            volume_path.to_str().unwrap(),
            kernel_path.to_str().unwrap(),
        ])
        .status()
        .expect("put-kernel runs")
        .success());

    let bios = compile_bundled_k16_bios();
    let mut handle = K16ComputerHandle::create_k16_bios_flash_with_storage0_path(
        &bios,
        64 * 1024,
        1_000_000,
        &volume_path,
    )
    .expect("K16 BIOS flash computer creates with runtime handoff volume path");

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);

    assert_eq!(read_guest_u32(&handle, 0x3f00), 0x49424b52);
    assert_eq!(read_guest_u32(&handle, 0x3f04), 0x00140001);
    assert_eq!(read_guest_u32(&handle, 0x3f08), 33);
    assert_eq!(read_guest_u32(&handle, 0x3f0c), kernel_bytes.len() as u32);
    assert_eq!(read_guest_u32(&handle, 0x3f10), 0);

    assert_eq!(read_guest_u32(&handle, 0x3f20), 0x494e4952);
    assert_eq!(read_guest_u32(&handle, 0x3f24), 0x00180001);
    assert_eq!(read_guest_u32(&handle, 0x3f28), 33);
    assert_eq!(read_guest_u32(&handle, 0x3f2c), init_bytes.len() as u32);
    assert_eq!(read_guest_u32(&handle, 0x3f30), 0x8000);
    assert_eq!(read_guest_u32(&handle, 0x3f34), 0);
}

#[test]
fn k16_volume_boot_kernel_rejects_protected_init_load_address() {
    let volume_path = temp_file("protected-init-load-storage0.kv");
    let root_path = temp_file("protected-init-load-root.kfs");
    let boot_path = temp_file("protected-init-load-loader.boot");
    let kernel_path = temp_file("protected-init-load-kernel.kx");
    let init_path = temp_file("protected-init-load-init.kx");
    let boot_source_path =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/kernel_loader.rx");
    let kernel_source_path =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/kernel/init_loader.rx");
    fs::write(
        &init_path,
        k16e::encode_k16_executable(&[0x01, 0x00], k16e::K16eAbiKind::Program, 0x7000, 0x7000)
            .expect("protected init K16E encodes"),
    )
    .expect("protected init writes");

    compile_source_file_to_path(&boot_source_path, K16ArtifactTarget::Boot, &boot_path);
    compile_source_file_to_path(&kernel_source_path, K16ArtifactTarget::Kernel, &kernel_path);

    assert!(Command::new(k16_binary())
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
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "put-boot",
            volume_path.to_str().unwrap(),
            boot_path.to_str().unwrap(),
        ])
        .status()
        .expect("put-boot runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "fs",
            "kfs",
            "format",
            root_path.to_str().unwrap(),
            "--blocks",
            "95",
        ])
        .status()
        .expect("k16fs format runs")
        .success());
    assert!(Command::new(k16_binary())
        .args(["fs", "kfs", "mkdir", root_path.to_str().unwrap(), "/bin"])
        .status()
        .expect("k16fs mkdir /bin runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "fs",
            "kfs",
            "put",
            root_path.to_str().unwrap(),
            "/bin/init.kx",
            init_path.to_str().unwrap(),
        ])
        .status()
        .expect("k16fs put protected init runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "replace-partition",
            volume_path.to_str().unwrap(),
            "ROOT",
            root_path.to_str().unwrap(),
        ])
        .status()
        .expect("replace ROOT runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "put-kernel",
            volume_path.to_str().unwrap(),
            kernel_path.to_str().unwrap(),
        ])
        .status()
        .expect("put-kernel runs")
        .success());

    let bios = compile_bundled_k16_bios();
    let mut handle = K16ComputerHandle::create_k16_bios_flash_with_storage0_path(
        &bios,
        64 * 1024,
        1_000_000,
        &volume_path,
    )
    .expect("K16 BIOS flash computer creates with protected init volume path");

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert!(
        String::from_utf8_lossy(handle.debug_output_bytes()).contains("INIT LOAD FAILED"),
        "debug output should describe the init load failure"
    );
    assert_eq!(handle.control().panic_code, 73);
}

#[test]
fn k16_volume_boot_kernel_init_failure_missing_init_clears_stale_handoff() {
    let volume_path = create_boot_kernel_init_volume("missing-init-failure", None);
    let bios = compile_bundled_k16_bios();
    let mut handle = K16ComputerHandle::create_k16_bios_flash_with_storage0_path(
        &bios,
        64 * 1024,
        1_000_000,
        &volume_path,
    )
    .expect("K16 BIOS flash computer creates with missing init volume path");
    handle
        .write_guest_ram_bytes(0x3f20, b"RINI")
        .expect("stale RINI writes");

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert!(
        String::from_utf8_lossy(handle.debug_output_bytes()).contains("INIT LOAD FAILED"),
        "debug output should describe the init load failure"
    );
    assert_eq!(handle.control().panic_code, 73);
    assert_ne!(
        handle
            .read_guest_ram_bytes(0x3f20, 4)
            .expect("RINI magic reads"),
        b"RINI",
        "init failure should clear stale RINI magic"
    );
}

#[test]
fn k16_volume_boot_kernel_init_failure_wrong_abi_clears_stale_handoff() {
    let wrong_init =
        k16e::encode_k16_executable(&[0x01, 0x00], k16e::K16eAbiKind::Kernel, 0x8000, 0x8000)
            .expect("wrong init K16E encodes");
    let volume_path = create_boot_kernel_init_volume("wrong-init-abi-failure", Some(&wrong_init));
    let bios = compile_bundled_k16_bios();
    let mut handle = K16ComputerHandle::create_k16_bios_flash_with_storage0_path(
        &bios,
        64 * 1024,
        1_000_000,
        &volume_path,
    )
    .expect("K16 BIOS flash computer creates with wrong init ABI volume path");
    handle
        .write_guest_ram_bytes(0x3f20, b"RINI")
        .expect("stale RINI writes");

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert!(
        String::from_utf8_lossy(handle.debug_output_bytes()).contains("INIT LOAD FAILED"),
        "debug output should describe the init load failure"
    );
    assert_eq!(handle.control().panic_code, 73);
    assert_ne!(
        handle
            .read_guest_ram_bytes(0x3f20, 4)
            .expect("RINI magic reads"),
        b"RINI",
        "init failure should clear stale RINI magic"
    );
}

fn create_boot_kernel_init_volume(name: &str, init_bytes: Option<&[u8]>) -> PathBuf {
    let volume_path = temp_file(&format!("{name}-storage0.kv"));
    let root_path = temp_file(&format!("{name}-root.kfs"));
    let boot_path = temp_file(&format!("{name}-loader.boot"));
    let kernel_path = temp_file(&format!("{name}-kernel.kx"));
    let init_path = temp_file(&format!("{name}-init.kx"));
    let boot_source_path =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/boot/kernel_loader.rx");
    let kernel_source_path =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/kernel/init_loader.rx");

    compile_source_file_to_path(&boot_source_path, K16ArtifactTarget::Boot, &boot_path);
    compile_source_file_to_path(&kernel_source_path, K16ArtifactTarget::Kernel, &kernel_path);

    assert!(Command::new(k16_binary())
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
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "put-boot",
            volume_path.to_str().unwrap(),
            boot_path.to_str().unwrap(),
        ])
        .status()
        .expect("put-boot runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "fs",
            "kfs",
            "format",
            root_path.to_str().unwrap(),
            "--blocks",
            "95",
        ])
        .status()
        .expect("k16fs format runs")
        .success());
    if let Some(init_bytes) = init_bytes {
        fs::write(&init_path, init_bytes).expect("init K16E writes");
        assert!(Command::new(k16_binary())
            .args(["fs", "kfs", "mkdir", root_path.to_str().unwrap(), "/bin"])
            .status()
            .expect("k16fs mkdir /bin runs")
            .success());
        assert!(Command::new(k16_binary())
            .args([
                "fs",
                "kfs",
                "put",
                root_path.to_str().unwrap(),
                "/bin/init.kx",
                init_path.to_str().unwrap(),
            ])
            .status()
            .expect("k16fs put init runs")
            .success());
    }
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "replace-partition",
            volume_path.to_str().unwrap(),
            "ROOT",
            root_path.to_str().unwrap(),
        ])
        .status()
        .expect("replace ROOT runs")
        .success());
    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "put-kernel",
            volume_path.to_str().unwrap(),
            kernel_path.to_str().unwrap(),
        ])
        .status()
        .expect("put-kernel runs")
        .success());

    volume_path
}

fn compile_bundled_k16_bios() -> Vec<u8> {
    let source = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/firmware/k16_bios.rx"),
    )
    .expect("K16 BIOS firmware source should exist");
    compile_k16_artifact(&source, K16ArtifactTarget::Bios)
        .expect("K16 BIOS source compiles to BIOS flash")
        .bytes
}

fn compile_rini_init_program(name: &str) -> Vec<u8> {
    compile_init_program("examples/init/rini_init.rx", name)
}

fn compile_init_program(source: &str, name: &str) -> Vec<u8> {
    let init_source_path = Path::new(env!("CARGO_MANIFEST_DIR")).join(source);
    let init_path = temp_file(name);
    compile_source_file_to_path(&init_source_path, K16ArtifactTarget::Program, &init_path);
    fs::read(&init_path).expect("RINI init K16E reads")
}

fn compile_source_file_to_path(source_path: &Path, target: K16ArtifactTarget, output_path: &Path) {
    let source = fs::read_to_string(source_path)
        .unwrap_or_else(|error| panic!("source {} reads: {error}", source_path.display()));
    let artifact = compile_k16_artifact(&source, target).unwrap_or_else(|error| {
        panic!(
            "source {} compiles: {}",
            source_path.display(),
            error.message
        )
    });
    fs::write(output_path, artifact.bytes)
        .unwrap_or_else(|error| panic!("output {} writes: {error}", output_path.display()));
}

fn display_row(snapshot: &K16ComputerTextDisplaySnapshot, row: u32) -> String {
    let start = (row * snapshot.columns) as usize;
    let end = start + snapshot.columns as usize;
    let row = &snapshot.cells[start..end];
    let visible_end = row
        .iter()
        .rposition(|byte| *byte != b' ' && *byte != 0)
        .map_or(0, |index| index + 1);
    String::from_utf8_lossy(&row[..visible_end]).to_string()
}

fn read_guest_u32(handle: &K16ComputerHandle, address: u32) -> u32 {
    let bytes = handle
        .read_guest_ram_bytes(address, 4)
        .expect("guest RAM bytes read");
    u32::from_le_bytes(bytes.try_into().expect("guest RAM read returns 4 bytes"))
}

fn temp_file(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("k16-volume-cli-{}-{name}", std::process::id()));
    let _ = fs::remove_file(&path);
    path
}

fn k16_binary() -> String {
    std::env::var("CARGO_BIN_EXE_k16").expect("Cargo exposes k16 binary path")
}
