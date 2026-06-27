use std::fs;
use std::path::PathBuf;
use std::process::Command;

#[test]
fn k16_run_bios_runs_raw_bios_flash() {
    let bios_path = temp_file("halt-bios.kflash");
    fs::write(&bios_path, [0x01, 0x00]).expect("BIOS flash writes");

    let output = Command::new(k16_binary())
        .args(["run-bios", bios_path.to_str().unwrap()])
        .output()
        .expect("k16 run-bios runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains("signal=halt"), "stdout: {stdout}");
}

fn temp_file(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("k16-run-bios-cli-{}-{name}", std::process::id()));
    let _ = fs::remove_file(&path);
    path
}

fn k16_binary() -> String {
    std::env::var("CARGO_BIN_EXE_k16").expect("Cargo exposes k16 binary path")
}
