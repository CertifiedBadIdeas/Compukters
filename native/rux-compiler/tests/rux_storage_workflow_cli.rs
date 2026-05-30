use rux_compiler::ruxe;
use std::fs;
use std::path::PathBuf;
use std::process::Command;

#[test]
fn rux_cli_builds_partitioned_storage0_with_ruxfs_root_kernel_file() {
    let volume_path = temp_file("storage0.ruxvol");
    let root_path = temp_file("root.kfs");
    let extracted_root_path = temp_file("extracted-root.kfs");
    let kernel_path = temp_file("kernel.kx");
    let extracted_kernel_path = temp_file("extracted-kernel.kx");
    let kernel_bytes =
        ruxe::encode_rux16_executable(&[0x01, 0x00], ruxe::RuxeAbiKind::Kernel, 0x4000, 0x4000)
            .expect("kernel RUXE encodes");
    fs::write(&kernel_path, &kernel_bytes).expect("kernel writes");

    assert_success(
        Command::new(k16_binary())
            .args([
                "volume",
                "init",
                volume_path.to_str().unwrap(),
                "--size",
                "65536",
            ])
            .output()
            .expect("volume init runs"),
    );

    let inspect_output = Command::new(k16_binary())
        .args(["volume", "inspect", volume_path.to_str().unwrap()])
        .output()
        .expect("volume inspect runs");
    assert_success(inspect_output.clone());
    assert_eq!(
        String::from_utf8(inspect_output.stdout).expect("inspect stdout is UTF-8"),
        "RUXVOL v1 payload=65536\nRUXPT v1 entries=2\nBOOT start_lba=1 blocks=32 bytes=16384 name=boot\nROOT start_lba=33 blocks=95 bytes=48640 name=root\n"
    );

    assert_success(
        Command::new(k16_binary())
            .args([
                "fs",
                "kfs",
                "format",
                root_path.to_str().unwrap(),
                "--blocks",
                "95",
            ])
            .output()
            .expect("ruxfs format runs"),
    );
    assert_success(
        Command::new(k16_binary())
            .args(["fs", "kfs", "mkdir", root_path.to_str().unwrap(), "/boot"])
            .output()
            .expect("ruxfs mkdir runs"),
    );
    assert_success(
        Command::new(k16_binary())
            .args([
                "fs",
                "kfs",
                "put",
                root_path.to_str().unwrap(),
                "/boot/kernel.kx",
                kernel_path.to_str().unwrap(),
            ])
            .output()
            .expect("ruxfs put runs"),
    );
    assert_success(
        Command::new(k16_binary())
            .args([
                "volume",
                "replace-partition",
                volume_path.to_str().unwrap(),
                "ROOT",
                root_path.to_str().unwrap(),
            ])
            .output()
            .expect("replace partition runs"),
    );
    assert_success(
        Command::new(k16_binary())
            .args([
                "volume",
                "extract-partition",
                volume_path.to_str().unwrap(),
                "ROOT",
                extracted_root_path.to_str().unwrap(),
            ])
            .output()
            .expect("extract partition runs"),
    );
    assert_success(
        Command::new(k16_binary())
            .args([
                "fs",
                "kfs",
                "get",
                extracted_root_path.to_str().unwrap(),
                "/boot/kernel.kx",
                extracted_kernel_path.to_str().unwrap(),
            ])
            .output()
            .expect("ruxfs get runs"),
    );

    assert_eq!(
        fs::read(extracted_kernel_path).expect("extracted kernel reads"),
        kernel_bytes
    );
}

fn assert_success(output: std::process::Output) {
    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
}

fn temp_file(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!(
        "rux-storage-workflow-cli-{}-{name}",
        std::process::id()
    ));
    let _ = fs::remove_file(&path);
    path
}

fn k16_binary() -> String {
    std::env::var("CARGO_BIN_EXE_k16").expect("Cargo exposes k16 binary path")
}
