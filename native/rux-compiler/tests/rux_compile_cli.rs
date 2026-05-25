use std::fs;
use std::path::PathBuf;
use std::process::Command;

#[test]
fn rux_compile_defaults_to_program_target() {
    let source_path = temp_file("default-program.rx");
    let default_output_path = temp_file("default-program.bin");
    let explicit_output_path = temp_file("explicit-program.bin");
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

    let default_bytes = fs::read(default_output_path).expect("default output reads");
    let explicit_bytes = fs::read(explicit_output_path).expect("explicit output reads");
    assert_eq!(default_bytes, explicit_bytes);
    assert_eq!(default_bytes, vec![0x01, 0x00]);
    assert_ne!(&default_bytes[..], b"RUXI");
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
fn rux_compile_rejects_unsupported_rux16_program_without_lowimage_fallback() {
    let source_path = temp_file("unsupported.rx");
    let output_path = temp_file("unsupported.bin");
    fs::write(&source_path, "fn main() -> i32 { return 7; }").expect("source writes");

    let output = Command::new(rux_binary())
        .args([
            "compile",
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
