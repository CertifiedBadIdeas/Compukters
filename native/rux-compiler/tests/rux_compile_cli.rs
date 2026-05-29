use std::fs;
use std::path::PathBuf;
use std::process::Command;

#[test]
fn rux_compile_defaults_to_user_space_program_target() {
    let source_path = temp_file("default-program.rx");
    let default_output_path = temp_file("default-program.ruxe");
    let explicit_output_path = temp_file("explicit-program.ruxe");
    fs::write(&source_path, "fn main() { }").expect("source writes");

    let default_output = Command::new(rux_binary())
        .args([
            "compile",
            source_path.to_str().unwrap(),
            "-o",
            default_output_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux compile runs");
    assert!(
        default_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&default_output.stderr)
    );

    let explicit_output = Command::new(rux_binary())
        .args([
            "compile",
            "--target",
            "program",
            source_path.to_str().unwrap(),
            "-o",
            explicit_output_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux compile runs");
    assert!(
        explicit_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&explicit_output.stderr)
    );

    for output_path in [default_output_path, explicit_output_path] {
        let bytes = fs::read(output_path).expect("output reads");
        assert_eq!(&bytes[0..4], b"RUXE");
        assert_eq!(u32_at(&bytes, 24), 3);
        assert_eq!(u32_at(&bytes, 12), 0x8000);
        assert_eq!(u32_at(&bytes, 36), 0x8000);
        assert_eq!(&bytes[52..], &[0x01, 0x00]);
    }
}

#[test]
fn rux_compile_writes_explicit_bios_artifact() {
    let source_path = temp_file("bios.rx");
    let output_path = temp_file("bios.flash");
    fs::write(&source_path, "fn main() { }").expect("source writes");

    let output = Command::new(rux_binary())
        .args([
            "compile",
            "--target",
            "bios",
            source_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux compile runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_eq!(
        fs::read(output_path).expect("output reads"),
        vec![0x01, 0x00]
    );
}

#[test]
fn rux_compile_writes_explicit_boot_artifact_as_ruxe_executable() {
    let source_path = temp_file("boot.rx");
    let output_path = temp_file("boot.ruxe");
    fs::write(&source_path, "fn main() { }").expect("source writes");

    let output = Command::new(rux_binary())
        .args([
            "compile",
            "--target",
            "boot",
            source_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux compile runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let bytes = fs::read(output_path).expect("output reads");
    assert_eq!(&bytes[0..4], b"RUXE");
    assert_eq!(u32_at(&bytes, 24), 1);
    assert_eq!(u32_at(&bytes, 12), 2048);
    assert_eq!(u32_at(&bytes, 36), 2048);
    assert_eq!(&bytes[52..], &[0x01, 0x00]);
}

#[test]
fn rux_compile_writes_explicit_kernel_artifact_as_ruxe_executable() {
    let source_path = temp_file("kernel.rx");
    let output_path = temp_file("kernel.ruxe");
    fs::write(&source_path, "fn main() { }").expect("source writes");

    let output = Command::new(rux_binary())
        .args([
            "compile",
            "--target",
            "kernel",
            source_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux compile runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let bytes = fs::read(output_path).expect("output reads");
    assert_eq!(&bytes[0..4], b"RUXE");
    assert_eq!(u32_at(&bytes, 24), 2);
    assert_eq!(u32_at(&bytes, 12), 0x4000);
    assert_eq!(u32_at(&bytes, 36), 0x4000);
    assert_eq!(&bytes[52..], &[0x01, 0x00]);
}

#[test]
fn rux_compile_rejects_unsupported_rux16_program_without_lowimage_fallback() {
    let source_path = temp_file("unsupported.rx");
    let output_path = temp_file("unsupported.bin");
    fs::write(&source_path, "fn main() -> i32 { return 7; }").expect("source writes");

    let output = Command::new(rux_binary())
        .args([
            "compile",
            "--target",
            "boot",
            source_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux compile runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        stderr.contains("Rux16 backend does not support"),
        "stderr: {stderr}"
    );
    assert!(!output_path.exists());
}

fn temp_file(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("rux-compile-cli-{}-{name}", std::process::id()));
    let _ = fs::remove_file(&path);
    path
}

fn rux_binary() -> String {
    std::env::var("CARGO_BIN_EXE_rux").expect("Cargo exposes rux binary path")
}

fn u32_at(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
}
