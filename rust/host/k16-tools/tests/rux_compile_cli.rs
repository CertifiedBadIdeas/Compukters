use std::path::PathBuf;
use std::process::Command;

#[test]
fn rux_compile_command_is_retired_without_writing_output() {
    let output_path = temp_file("retired.kx");
    let output = Command::new(rux_binary())
        .args(["compile", "main.rx", "-o", output_path.to_str().unwrap()])
        .output()
        .expect("rux runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(stderr.contains("rux check"), "stderr: {stderr}");
    assert!(!stderr.contains("rux compile"), "stderr: {stderr}");
    assert!(!output_path.exists());
}

fn temp_file(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("rux-compile-cli-{}-{name}", std::process::id()));
    let _ = std::fs::remove_file(&path);
    path
}

fn rux_binary() -> String {
    std::env::var("CARGO_BIN_EXE_rux").expect("Cargo exposes rux binary path")
}
