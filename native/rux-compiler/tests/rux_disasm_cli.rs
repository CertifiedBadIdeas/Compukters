use rux_vm::low_image::{encode_image, Function, Image, Instruction};
use std::fs;
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

#[test]
fn rux_disasm_cli_prints_decoded_image() {
    let image = Image {
        memory_size: 1024,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![Function {
            name: "main".to_string(),
            register_count: 1,
            parameters: Vec::new(),
            instructions: vec![
                Instruction::I32Const { dst: 0, value: 7 },
                Instruction::ReturnI32 { src: 0 },
            ],
        }],
    };
    let bytes = encode_image(&image).expect("test image encodes");
    let path = std::env::temp_dir().join(format!(
        "rux-disasm-cli-{}.ruxi",
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system time is after epoch")
            .as_nanos()
    ));
    fs::write(&path, bytes).expect("test image is written");

    let binary_path =
        std::env::var("CARGO_BIN_EXE_rux-disasm").expect("rux-disasm binary is built by Cargo");
    let output = Command::new(binary_path)
        .arg(&path)
        .output()
        .expect("rux-disasm runs");
    let _ = fs::remove_file(&path);

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout = String::from_utf8(output.stdout).expect("stdout is utf-8");
    assert!(stdout.contains("; RUXI v1"));
    assert!(stdout.contains("fn 0 main(regs=1, params=[]) {"));
    assert!(stdout.contains("  0000: I32Const r0, 7"));
    assert!(stdout.contains("  0001: ReturnI32 r0"));
}
