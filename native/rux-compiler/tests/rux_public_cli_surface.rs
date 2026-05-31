use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
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
    assert!(stderr.contains("--target bios"), "stderr: {stderr}");
    assert!(stderr.contains("--target boot"), "stderr: {stderr}");
    assert!(
        stderr.contains("--target <kernel|program>"),
        "stderr: {stderr}"
    );
    assert!(stderr.contains("<bios.kflash>"), "stderr: {stderr}");
    assert!(stderr.contains("<boot.kb>"), "stderr: {stderr}");
    assert!(stderr.contains("<program.kx>"), "stderr: {stderr}");
}

#[test]
fn rux_does_not_expose_machine_artifact_commands() {
    for command in [
        "link", "runtime", "run", "disasm", "inspect", "volume", "fs",
    ] {
        let output = Command::new(rux_binary())
            .arg(command)
            .output()
            .expect("rux runs");

        assert!(!output.status.success(), "command: {command}");
        let stderr = String::from_utf8_lossy(&output.stderr);
        assert!(
            stderr.contains("rux compile"),
            "command: {command}, stderr: {stderr}"
        );
        assert!(
            stderr.contains("rux check"),
            "command: {command}, stderr: {stderr}"
        );
        assert!(
            !stderr.contains("rux link"),
            "command: {command}, stderr: {stderr}"
        );
        assert!(
            !stderr.contains("rux volume"),
            "command: {command}, stderr: {stderr}"
        );
        assert!(
            !stderr.contains("k16"),
            "command: {command}, stderr: {stderr}"
        );
    }
}

#[test]
fn k16_is_the_public_machine_artifact_cli() {
    let output = Command::new(k16_binary())
        .arg("link")
        .output()
        .expect("k16 runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(stderr.contains("k16 link"), "stderr: {stderr}");
    assert!(!stderr.contains("rux compile"), "stderr: {stderr}");
}

#[test]
fn k16_does_not_expose_rux_language_commands() {
    let output = Command::new(k16_binary())
        .arg("compile")
        .output()
        .expect("k16 runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(stderr.contains("k16"), "stderr: {stderr}");
    assert!(!stderr.contains("rux compile"), "stderr: {stderr}");
    assert!(!stderr.contains("rux check"), "stderr: {stderr}");
}

#[test]
fn root_shell_helpers_split_rux_language_and_k16_machine_tooling() {
    let repo_root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("compiler crate is under native/rux-compiler");
    let rux_helper = repo_root.join("rux");
    let k16_helper = repo_root.join("k16");

    assert!(rux_helper.exists(), "root rux helper exists");
    assert!(k16_helper.exists(), "root k16 helper exists");
    assert!(
        fs::metadata(&k16_helper)
            .expect("k16 helper metadata reads")
            .permissions()
            .mode()
            & 0o111
            != 0,
        "root k16 helper is executable"
    );

    let rux_source = fs::read_to_string(&rux_helper).expect("root rux helper reads");
    let k16_source = fs::read_to_string(&k16_helper).expect("root k16 helper reads");

    assert!(rux_source.contains("--bin rux"));
    assert!(k16_source.contains("--bin k16"));
    assert!(rux_source.contains("rux compile"));
    assert!(rux_source.contains("rux check"));
    assert!(!rux_source.contains("rux volume"));
    assert!(!rux_source.contains("rux disasm"));
    assert!(!rux_source.contains("rux fs"));
    assert!(k16_source.contains("k16 volume"));
    assert!(k16_source.contains("k16 disasm"));
    assert!(k16_source.contains("k16 fs"));
}

fn rux_binary() -> String {
    std::env::var("CARGO_BIN_EXE_rux").expect("Cargo exposes rux binary path")
}

fn k16_binary() -> String {
    std::env::var("CARGO_BIN_EXE_k16").expect("Cargo exposes k16 binary path")
}
