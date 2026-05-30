use std::fs;
use std::path::PathBuf;
use std::process::Command;

#[test]
fn rux_fs_ruxfs_formats_writes_lists_and_reads_file() {
    let fs_path = temp_file("root.ruxfs");
    let input_path = temp_file("loader-input.kb");
    let output_path = temp_file("loader-output.kb");
    fs::write(&input_path, b"BOOTLOADER").expect("input writes");

    assert_success(
        Command::new(k16_binary())
            .args([
                "fs",
                "ruxfs",
                "format",
                fs_path.to_str().unwrap(),
                "--blocks",
                "128",
            ])
            .output()
            .expect("format runs"),
    );
    assert_success(
        Command::new(k16_binary())
            .args(["fs", "ruxfs", "mkdir", fs_path.to_str().unwrap(), "/boot"])
            .output()
            .expect("mkdir runs"),
    );
    assert_success(
        Command::new(k16_binary())
            .args([
                "fs",
                "ruxfs",
                "put",
                fs_path.to_str().unwrap(),
                "/boot/loader.kb",
                input_path.to_str().unwrap(),
            ])
            .output()
            .expect("put runs"),
    );

    let ls_output = Command::new(k16_binary())
        .args(["fs", "ruxfs", "ls", fs_path.to_str().unwrap(), "/boot"])
        .output()
        .expect("ls runs");
    assert_success(ls_output.clone());
    assert_eq!(
        String::from_utf8(ls_output.stdout).expect("stdout is UTF-8"),
        "loader.kb\n"
    );

    assert_success(
        Command::new(k16_binary())
            .args([
                "fs",
                "ruxfs",
                "get",
                fs_path.to_str().unwrap(),
                "/boot/loader.kb",
                output_path.to_str().unwrap(),
            ])
            .output()
            .expect("get runs"),
    );
    assert_eq!(fs::read(output_path).expect("output reads"), b"BOOTLOADER");
}

#[test]
fn rux_fs_ruxfs_removes_file() {
    let fs_path = temp_file("delete.ruxfs");
    let input_path = temp_file("delete-input.kb");
    fs::write(&input_path, b"BOOTLOADER").expect("input writes");

    assert_success(
        Command::new(k16_binary())
            .args([
                "fs",
                "ruxfs",
                "format",
                fs_path.to_str().unwrap(),
                "--blocks",
                "128",
            ])
            .output()
            .expect("format runs"),
    );
    assert_success(
        Command::new(k16_binary())
            .args(["fs", "ruxfs", "mkdir", fs_path.to_str().unwrap(), "/boot"])
            .output()
            .expect("mkdir runs"),
    );
    assert_success(
        Command::new(k16_binary())
            .args([
                "fs",
                "ruxfs",
                "put",
                fs_path.to_str().unwrap(),
                "/boot/loader.kb",
                input_path.to_str().unwrap(),
            ])
            .output()
            .expect("put runs"),
    );
    assert_success(
        Command::new(k16_binary())
            .args([
                "fs",
                "ruxfs",
                "rm",
                fs_path.to_str().unwrap(),
                "/boot/loader.kb",
            ])
            .output()
            .expect("rm runs"),
    );

    let ls_output = Command::new(k16_binary())
        .args(["fs", "ruxfs", "ls", fs_path.to_str().unwrap(), "/boot"])
        .output()
        .expect("ls runs");
    assert_success(ls_output.clone());
    assert_eq!(
        String::from_utf8(ls_output.stdout).expect("stdout is UTF-8"),
        ""
    );
}

#[test]
fn rux_fs_rejects_unknown_filesystem_type_without_volume_fallback() {
    let output = Command::new(k16_binary())
        .args(["fs", "fat32", "format", "ignored.img", "--blocks", "128"])
        .output()
        .expect("k16 runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        stderr.contains("unsupported filesystem `fat32`"),
        "stderr: {stderr}"
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
    let path = std::env::temp_dir().join(format!("rux-fs-cli-{}-{name}", std::process::id()));
    let _ = fs::remove_file(&path);
    path
}

fn k16_binary() -> String {
    std::env::var("CARGO_BIN_EXE_k16").expect("Cargo exposes k16 binary path")
}
