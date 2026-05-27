use std::process::Command;

#[test]
fn old_ruxi_public_binaries_are_not_exposed() {
    assert!(std::env::var("CARGO_BIN_EXE_rux-emit").is_err());
    assert!(std::env::var("CARGO_BIN_EXE_rux-run").is_err());
    assert!(std::env::var("CARGO_BIN_EXE_rux-disasm").is_err());
}

#[test]
fn rux_emit_is_not_a_legacy_alias() {
    let output = Command::new(rux_binary())
        .arg("emit")
        .output()
        .expect("rux runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(stderr.contains("rux compile"), "stderr: {stderr}");
    assert!(!stderr.contains("ruxi"), "stderr: {stderr}");
}

#[test]
fn rux_compile_bios_is_the_public_firmware_path() {
    let output = Command::new(rux_binary())
        .args(["compile", "--target", "bios"])
        .output()
        .expect("rux runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(stderr.contains("rux compile"), "stderr: {stderr}");
    assert!(
        stderr.contains("--target <bios|boot|program>"),
        "stderr: {stderr}"
    );
}

fn rux_binary() -> String {
    std::env::var("CARGO_BIN_EXE_rux").expect("Cargo exposes rux binary path")
}
