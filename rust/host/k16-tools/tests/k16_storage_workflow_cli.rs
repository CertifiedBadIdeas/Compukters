use k16_tools::k16e;
use std::fs;
use std::path::PathBuf;
use std::process::Command;

const TEST_VOLUME_SIZE: &str = "1048576";
const TEST_ROOT_BLOCKS: &str = "1791";

#[test]
fn k16_cli_builds_partitioned_storage0_with_k16fs_root_kernel_file() {
    let volume_path = temp_file("storage0.kv");
    let root_path = temp_file("root.kfs");
    let extracted_root_path = temp_file("extracted-root.kfs");
    let kernel_path = temp_file("kernel.kx");
    let extracted_kernel_path = temp_file("extracted-kernel.kx");
    let kernel_bytes =
        k16e::encode_k16_executable(&[0x01, 0x00], k16e::K16eAbiKind::Kernel, 0x4000, 0x4000)
            .expect("kernel K16E encodes");
    fs::write(&kernel_path, &kernel_bytes).expect("kernel writes");

    assert_success(
        Command::new(k16_binary())
            .args([
                "volume",
                "init",
                volume_path.to_str().unwrap(),
                "--size",
                TEST_VOLUME_SIZE,
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
        "K16VOL v1 payload=1048576\nK16PT v1 entries=2\nBOOT start_lba=1 blocks=256 bytes=131072 name=boot\nROOT start_lba=257 blocks=1791 bytes=916992 name=root\n"
    );

    assert_success(
        Command::new(k16_binary())
            .args([
                "fs",
                "kfs",
                "format",
                root_path.to_str().unwrap(),
                "--blocks",
                TEST_ROOT_BLOCKS,
            ])
            .output()
            .expect("k16fs format runs"),
    );
    assert_success(
        Command::new(k16_binary())
            .args(["fs", "kfs", "mkdir", root_path.to_str().unwrap(), "/boot"])
            .output()
            .expect("k16fs mkdir runs"),
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
            .expect("k16fs put runs"),
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
            .expect("k16fs get runs"),
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
        "k16-storage-workflow-cli-{}-{name}",
        std::process::id()
    ));
    let _ = fs::remove_file(&path);
    path
}

fn k16_binary() -> String {
    std::env::var("CARGO_BIN_EXE_k16").expect("Cargo exposes k16 binary path")
}
