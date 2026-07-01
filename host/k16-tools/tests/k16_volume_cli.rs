use k16_tools::k16e;
use k16_tools::kfs;
use k16_tools::volume;
use std::fs;
use std::path::PathBuf;
use std::process::Command;

const TEST_VOLUME_SIZE: &str = "1048576";
const TEST_VOLUME_SIZE_BYTES: u64 = 1_048_576;
const TEST_BOOT_BLOCKS: u32 = 256;
const TEST_ROOT_START_LBA: u32 = 257;
const TEST_ROOT_BLOCKS: u32 = 1_791;
const TEST_BOOT_BYTES: u32 = TEST_BOOT_BLOCKS * 512;
const TEST_ROOT_BYTES: u32 = TEST_ROOT_BLOCKS * 512;

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
        .args([
            "volume",
            "init",
            path.to_str().unwrap(),
            "--size",
            TEST_VOLUME_SIZE,
        ])
        .output()
        .expect("k16 runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let bytes = fs::read(&path).expect("volume exists");
    assert_eq!(&bytes[..6], b"K16VOL");
    assert_eq!(
        u64::from_le_bytes(bytes[8..16].try_into().unwrap()),
        TEST_VOLUME_SIZE_BYTES
    );

    let payload = &bytes[16..];
    assert_eq!(&payload[0..5], b"K16PT");
    assert_eq!(payload[5], 1);
    assert_eq!(payload[6], 2);

    assert_eq!(&payload[16..20], b"BOOT");
    assert_eq!(u32::from_le_bytes(payload[24..28].try_into().unwrap()), 1);
    assert_eq!(
        u32::from_le_bytes(payload[28..32].try_into().unwrap()),
        TEST_BOOT_BLOCKS
    );
    assert_eq!(&payload[32..36], b"boot");

    assert_eq!(&payload[48..52], b"ROOT");
    assert_eq!(
        u32::from_le_bytes(payload[56..60].try_into().unwrap()),
        TEST_ROOT_START_LBA
    );
    assert_eq!(
        u32::from_le_bytes(payload[60..64].try_into().unwrap()),
        TEST_ROOT_BLOCKS
    );
    assert_eq!(&payload[64..68], b"root");
}

#[test]
fn k16_volume_extracts_and_replaces_partition_bytes_by_name() {
    let volume_path = temp_file("partition-bridge-storage0.kv");
    let root_path = temp_file("root-partition.bin");
    let extracted_path = temp_file("root-partition-extracted.bin");
    let mut root_bytes = vec![0_u8; TEST_ROOT_BYTES as usize];
    root_bytes[0..6].copy_from_slice(b"ROOTFS");
    root_bytes[4096..4101].copy_from_slice(b"hello");
    fs::write(&root_path, &root_bytes).expect("root partition writes");

    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "init",
            volume_path.to_str().unwrap(),
            "--size",
            TEST_VOLUME_SIZE,
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
    let root_offset = TEST_ROOT_START_LBA as usize * 512;
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
            TEST_VOLUME_SIZE,
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
        format!(
            "K16VOL v1 payload={TEST_VOLUME_SIZE}\nK16PT v1 entries=2\nBOOT start_lba=1 blocks={TEST_BOOT_BLOCKS} bytes={TEST_BOOT_BYTES} name=boot\nROOT start_lba={TEST_ROOT_START_LBA} blocks={TEST_ROOT_BLOCKS} bytes={TEST_ROOT_BYTES} name=root\n"
        )
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
            TEST_VOLUME_SIZE,
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
            &TEST_ROOT_BLOCKS.to_string(),
        ])
        .status()
        .expect("kfs format runs")
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
        format!(
            "K16VOL boot-chain\nBOOT partition start_lba=1 blocks={TEST_BOOT_BLOCKS} bytes={TEST_BOOT_BYTES} name=boot\nBOOT KFS /boot/loader.kb file_bytes=54\nBOOTLOADER K16E abi=bootloader entry_pc=0x00002000 load_addr=0x00002000 payload_bytes=2\nROOT partition start_lba={TEST_ROOT_START_LBA} blocks={TEST_ROOT_BLOCKS} bytes={TEST_ROOT_BYTES} name=root\nROOT KFS /boot/kernel.kx file_bytes=54\nKERNEL K16E abi=kernel entry_pc=0x00003000 load_addr=0x00003000 payload_bytes=2\n"
        )
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
            TEST_VOLUME_SIZE,
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
            &TEST_ROOT_BLOCKS.to_string(),
        ])
        .status()
        .expect("kfs format runs")
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
        .contains("ROOT/KFS /boot/kernel.kx is not readable"));
}

#[test]
fn k16_volume_replace_partition_rejects_wrong_size_without_truncation() {
    let volume_path = temp_file("partition-size-storage0.kv");
    let root_path = temp_file("oversized-root-partition.bin");
    fs::write(&root_path, vec![0x7f_u8; TEST_ROOT_BYTES as usize + 1])
        .expect("root partition writes");

    assert!(Command::new(k16_binary())
        .args([
            "volume",
            "init",
            volume_path.to_str().unwrap(),
            "--size",
            TEST_VOLUME_SIZE,
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
        stderr.contains(&format!(
            "partition `ROOT` is {TEST_ROOT_BYTES} bytes but input has {} bytes",
            TEST_ROOT_BYTES + 1
        )),
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
fn k16_volume_put_boot_installs_loader_kb_in_boot_kfs_partition() {
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
            TEST_VOLUME_SIZE,
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
        kfs::read_file(&boot, "/boot/loader.kb").expect("loader reads from BOOT"),
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
fn k16_volume_put_kernel_installs_kernel_kx_in_root_kfs() {
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
            TEST_VOLUME_SIZE,
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
            &TEST_ROOT_BLOCKS.to_string(),
        ])
        .status()
        .expect("kfs format runs")
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
        kfs::read_file(&root, "/boot/kernel.kx").expect("kernel reads from ROOT"),
        kernel_bytes
    );
    assert!(
        !bytes.windows(4).any(|window| window == b"K16K"),
        "put-kernel should not write the fixed K16K record"
    );
}

#[test]
fn k16_volume_init_creates_root_kfs_for_put_kernel() {
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
            TEST_VOLUME_SIZE,
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
        kfs::read_file(&root, "/boot/kernel.kx").expect("kernel reads from ROOT"),
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

fn temp_file(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("k16-volume-cli-{}-{name}", std::process::id()));
    let _ = fs::remove_file(&path);
    path
}

fn k16_binary() -> String {
    std::env::var("CARGO_BIN_EXE_k16").expect("Cargo exposes k16 binary path")
}
