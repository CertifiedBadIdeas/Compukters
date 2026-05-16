use rux_compiler::compile;
use rux_vm::low_image::encode_image;
use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

#[test]
fn rux_run_cli_runs_encoded_ruxi_image() {
    let path = write_ruxi(
        "fn main() -> i32 {
            unsafe {
                mmio<i32>(DEBUG_WRITE).store(79);
                mmio<i32>(DEBUG_WRITE).store(75);
            }
            return 0;
        }",
    );

    let output = run_rux_run([path.to_string_lossy().into_owned()]);
    let _ = fs::remove_file(path);

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout = String::from_utf8(output.stdout).expect("stdout is utf-8");
    assert!(stdout.contains("| OK"));
    assert!(stdout.contains("signal: HaltI32(0)"));
    assert!(stdout.contains("panic: 0"));
}

#[test]
fn rux_run_cli_runs_encoded_ruxi_image_with_serial_input() {
    let path = write_ruxi(include_str!("../examples/firmware/echo.rx"));

    let output = run_rux_run([
        path.to_string_lossy().into_owned(),
        "--serial".to_string(),
        "Rux!".to_string(),
    ]);
    let _ = fs::remove_file(path);

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout = String::from_utf8(output.stdout).expect("stdout is utf-8");
    assert!(stdout.contains("| Rux!"));
    assert!(stdout.contains("signal: HaltI32(0)"));
    assert!(stdout.contains("panic: 0"));
}

#[test]
fn rux_run_cli_runs_encoded_ruxi_with_declared_memory_size() {
    let fixture = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../docs/abi/fixtures/runtime_memory_out_of_bounds.ruxi");
    let output = run_rux_run([
        fixture.to_string_lossy().into_owned(),
        "--memory".to_string(),
        "1024".to_string(),
    ]);

    assert!(
        !output.status.success(),
        "stdout: {}",
        String::from_utf8_lossy(&output.stdout)
    );
    let stderr = String::from_utf8(output.stderr).expect("stderr is utf-8");
    assert!(
        stderr.contains("memory access 1022..1026 is outside 1024 bytes"),
        "{stderr}",
    );
}

fn write_ruxi(source: &str) -> PathBuf {
    let image = compile(source).expect("test source compiles");
    let bytes = encode_image(&image).expect("test image encodes");
    let path = std::env::temp_dir().join(format!(
        "rux-run-cli-{}.ruxi",
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system time is after epoch")
            .as_nanos()
    ));
    fs::write(&path, bytes).expect("test image is written");
    path
}

fn run_rux_run<const N: usize>(args: [String; N]) -> std::process::Output {
    let binary_path =
        std::env::var("CARGO_BIN_EXE_rux-run").expect("rux-run binary is built by Cargo");
    Command::new(binary_path)
        .args(args)
        .output()
        .expect("rux-run runs")
}
