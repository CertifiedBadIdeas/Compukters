use rux_vm::low_image::{encode_image, Function, Image, Instruction};
use std::fs;
use std::path::{Path, PathBuf};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let root = repo_root();
    let fixture_dir = root.join("docs/abi/fixtures");
    fs::create_dir_all(&fixture_dir)?;

    write_golden(
        &fixture_dir,
        "minimal_return_i32",
        minimal_return_i32(),
        r#"{"type":"HaltI32","value":42}"#,
    )?;
    write_golden(
        &fixture_dir,
        "memory_load_store",
        memory_load_store(),
        r#"{"type":"HaltI32","value":287454020}"#,
    )?;
    write_golden(
        &fixture_dir,
        "calls",
        calls(),
        r#"{"type":"HaltI32","value":12}"#,
    )?;
    write_golden(
        &fixture_dir,
        "branches",
        branches(),
        r#"{"type":"HaltI32","value":99}"#,
    )?;
    write_golden(
        &fixture_dir,
        "i32_u32_i64_u64_arithmetic",
        i32_u32_i64_u64_arithmetic(),
        r#"{"type":"HaltI32","value":1}"#,
    )?;

    write_negative(
        &fixture_dir,
        "bad_magic",
        bad_magic(),
        "decode",
        r#"{"type":"InvalidMagic"}"#,
    )?;
    write_negative(
        &fixture_dir,
        "bad_version",
        bad_version(),
        "decode",
        r#"{"type":"UnsupportedVersion","value":2}"#,
    )?;
    write_negative(
        &fixture_dir,
        "unknown_opcode",
        unknown_opcode(),
        "decode",
        r#"{"type":"UnknownInstructionTag","value":255}"#,
    )?;
    write_negative(
        &fixture_dir,
        "register_out_of_bounds",
        encode_image(&register_out_of_bounds())?,
        "validation",
        r#"{"contains":"writes register 1 outside register count 1"}"#,
    )?;
    write_negative(
        &fixture_dir,
        "entry_function_has_parameters",
        encode_image(&entry_function_has_parameters())?,
        "validation",
        r#"{"contains":"entry function main must not declare parameters"}"#,
    )?;
    write_negative(
        &fixture_dir,
        "bad_jump_target",
        encode_image(&bad_jump_target())?,
        "validation",
        r#"{"contains":"jump target 1 is outside instruction count 1"}"#,
    )?;
    write_negative(
        &fixture_dir,
        "memory_sections_overflow",
        encode_image(&memory_sections_overflow())?,
        "validation",
        r#"{"contains":"memory sections require 5 bytes but memory size is 4"}"#,
    )?;
    write_runtime_error(
        &fixture_dir,
        "runtime_divide_by_zero",
        runtime_divide_by_zero(),
        r#"{"contains":"division by zero"}"#,
    )?;
    write_runtime_error(
        &fixture_dir,
        "runtime_memory_out_of_bounds",
        runtime_memory_out_of_bounds(),
        r#"{"contains":"memory access 1022..1026 is outside 1024 bytes"}"#,
    )?;
    write_runtime_error(
        &fixture_dir,
        "runtime_scalar_return_without_register",
        runtime_scalar_return_without_register(),
        r#"{"contains":"callee returned r0 but caller did not provide return register"}"#,
    )?;
    write_runtime_error(
        &fixture_dir,
        "runtime_unit_return_with_register",
        runtime_unit_return_with_register(),
        r#"{"contains":"callee returned unit but caller expected r0"}"#,
    )?;

    Ok(())
}

fn write_golden(
    fixture_dir: &Path,
    name: &str,
    image: Image,
    expected_signal: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    fs::write(
        fixture_dir.join(format!("{name}.ruxi")),
        encode_image(&image)?,
    )?;
    fs::write(
        fixture_dir.join(format!("{name}.json")),
        format!(
            "{{\n  \"name\": \"{name}\",\n  \"kind\": \"golden\",\n  \"format\": \"RUXI\",\n  \"image_format_version\": 1,\n  \"expected_signal\": {expected_signal}\n}}\n",
        ),
    )?;
    Ok(())
}

fn write_negative(
    fixture_dir: &Path,
    name: &str,
    bytes: Vec<u8>,
    phase: &str,
    expected_error: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    fs::write(fixture_dir.join(format!("{name}.ruxi")), bytes)?;
    fs::write(
        fixture_dir.join(format!("{name}.json")),
        format!(
            "{{\n  \"name\": \"{name}\",\n  \"kind\": \"negative\",\n  \"format\": \"RUXI\",\n  \"target_image_format_version\": 1,\n  \"phase\": \"{phase}\",\n  \"expected_error\": {expected_error}\n}}\n",
        ),
    )?;
    Ok(())
}

fn write_runtime_error(
    fixture_dir: &Path,
    name: &str,
    image: Image,
    expected_error: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    fs::write(
        fixture_dir.join(format!("{name}.ruxi")),
        encode_image(&image)?,
    )?;
    fs::write(
        fixture_dir.join(format!("{name}.json")),
        format!(
            "{{\n  \"name\": \"{name}\",\n  \"kind\": \"runtime_error\",\n  \"format\": \"RUXI\",\n  \"image_format_version\": 1,\n  \"phase\": \"runtime\",\n  \"expected_error\": {expected_error}\n}}\n",
        ),
    )?;
    Ok(())
}

fn minimal_return_i32() -> Image {
    main_image(
        64,
        Vec::new(),
        Vec::new(),
        0,
        1,
        vec![
            Instruction::I32Const { dst: 0, value: 42 },
            Instruction::ReturnI32 { src: 0 },
        ],
    )
}

fn memory_load_store() -> Image {
    main_image(
        512,
        Vec::new(),
        Vec::new(),
        0,
        3,
        vec![
            Instruction::AddrConst { dst: 0, value: 128 },
            Instruction::I32Const {
                dst: 1,
                value: 0x1122_3344,
            },
            Instruction::Store32 { addr: 0, src: 1 },
            Instruction::Load32 { dst: 2, addr: 0 },
            Instruction::ReturnI32 { src: 2 },
        ],
    )
}

fn calls() -> Image {
    Image {
        memory_size: 64,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![
            Function {
                name: "main".to_string(),
                register_count: 3,
                parameters: Vec::new(),
                instructions: vec![
                    Instruction::I32Const { dst: 0, value: 7 },
                    Instruction::I32Const { dst: 1, value: 5 },
                    Instruction::CallStatic {
                        return_register: Some(2),
                        function_index: 1,
                        arguments: vec![0, 1],
                    },
                    Instruction::ReturnI32 { src: 2 },
                ],
            },
            Function {
                name: "add".to_string(),
                register_count: 3,
                parameters: vec![0, 1],
                instructions: vec![
                    Instruction::I32Add {
                        dst: 2,
                        lhs: 0,
                        rhs: 1,
                    },
                    Instruction::ReturnI32 { src: 2 },
                ],
            },
        ],
    }
}

fn branches() -> Image {
    main_image(
        64,
        Vec::new(),
        Vec::new(),
        0,
        3,
        vec![
            Instruction::I32Const { dst: 0, value: 0 },
            Instruction::JumpIfFalse { cond: 0, target: 4 },
            Instruction::I32Const { dst: 1, value: 7 },
            Instruction::Jump { target: 5 },
            Instruction::I32Const { dst: 1, value: 99 },
            Instruction::ReturnI32 { src: 1 },
        ],
    )
}

fn i32_u32_i64_u64_arithmetic() -> Image {
    main_image(
        128,
        Vec::new(),
        Vec::new(),
        0,
        11,
        vec![
            Instruction::I32Const { dst: 0, value: -1 },
            Instruction::I32ToI64 { dst: 1, src: 0 },
            Instruction::U32ToU64 { dst: 2, src: 0 },
            Instruction::I64Const { dst: 3, value: 63 },
            Instruction::I64Shr {
                dst: 4,
                lhs: 1,
                rhs: 3,
            },
            Instruction::U64Shr {
                dst: 5,
                lhs: 1,
                rhs: 3,
            },
            Instruction::I64Const { dst: 8, value: 1 },
            Instruction::U64Shl {
                dst: 9,
                lhs: 8,
                rhs: 3,
            },
            Instruction::U64Shr {
                dst: 10,
                lhs: 9,
                rhs: 3,
            },
            Instruction::I64Add {
                dst: 6,
                lhs: 2,
                rhs: 5,
            },
            Instruction::I64Add {
                dst: 6,
                lhs: 6,
                rhs: 10,
            },
            Instruction::I64ToI32 { dst: 7, src: 6 },
            Instruction::ReturnI32 { src: 7 },
        ],
    )
}

fn register_out_of_bounds() -> Image {
    main_image(
        64,
        Vec::new(),
        Vec::new(),
        0,
        1,
        vec![
            Instruction::I32Const { dst: 1, value: 7 },
            Instruction::ReturnUnit,
        ],
    )
}

fn entry_function_has_parameters() -> Image {
    Image {
        memory_size: 64,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![Function {
            name: "main".to_string(),
            register_count: 1,
            parameters: vec![0],
            instructions: vec![Instruction::ReturnUnit],
        }],
    }
}

fn bad_jump_target() -> Image {
    main_image(
        64,
        Vec::new(),
        Vec::new(),
        0,
        0,
        vec![Instruction::Jump { target: 1 }],
    )
}

fn memory_sections_overflow() -> Image {
    main_image(
        4,
        vec![1, 2],
        vec![3, 4],
        1,
        0,
        vec![Instruction::ReturnUnit],
    )
}

fn runtime_divide_by_zero() -> Image {
    main_image(
        64,
        Vec::new(),
        Vec::new(),
        0,
        3,
        vec![
            Instruction::I32Const { dst: 0, value: 10 },
            Instruction::I32Const { dst: 1, value: 0 },
            Instruction::I32Div {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::ReturnI32 { src: 2 },
        ],
    )
}

fn runtime_memory_out_of_bounds() -> Image {
    main_image(
        1024,
        Vec::new(),
        Vec::new(),
        0,
        2,
        vec![
            Instruction::AddrConst {
                dst: 0,
                value: 1022,
            },
            Instruction::Load32 { dst: 1, addr: 0 },
            Instruction::ReturnI32 { src: 1 },
        ],
    )
}

fn runtime_scalar_return_without_register() -> Image {
    Image {
        memory_size: 64,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![
            Function {
                name: "main".to_string(),
                register_count: 0,
                parameters: Vec::new(),
                instructions: vec![
                    Instruction::CallStatic {
                        return_register: None,
                        function_index: 1,
                        arguments: Vec::new(),
                    },
                    Instruction::ReturnUnit,
                ],
            },
            Function {
                name: "callee".to_string(),
                register_count: 1,
                parameters: Vec::new(),
                instructions: vec![
                    Instruction::I32Const { dst: 0, value: 7 },
                    Instruction::ReturnI32 { src: 0 },
                ],
            },
        ],
    }
}

fn runtime_unit_return_with_register() -> Image {
    Image {
        memory_size: 64,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![
            Function {
                name: "main".to_string(),
                register_count: 1,
                parameters: Vec::new(),
                instructions: vec![
                    Instruction::CallStatic {
                        return_register: Some(0),
                        function_index: 1,
                        arguments: Vec::new(),
                    },
                    Instruction::ReturnI32 { src: 0 },
                ],
            },
            Function {
                name: "callee".to_string(),
                register_count: 0,
                parameters: Vec::new(),
                instructions: vec![Instruction::ReturnUnit],
            },
        ],
    }
}

fn main_image(
    memory_size: u32,
    rodata: Vec<u8>,
    data: Vec<u8>,
    bss_size: u32,
    register_count: u16,
    instructions: Vec<Instruction>,
) -> Image {
    Image {
        memory_size,
        rodata,
        data,
        bss_size,
        entry_function_index: 0,
        functions: vec![Function {
            name: "main".to_string(),
            register_count,
            parameters: Vec::new(),
            instructions,
        }],
    }
}

fn bad_magic() -> Vec<u8> {
    let mut bytes = encode_image(&minimal_return_i32()).expect("reference image encodes");
    bytes[..4].copy_from_slice(b"NOPE");
    bytes
}

fn bad_version() -> Vec<u8> {
    let mut bytes = encode_image(&minimal_return_i32()).expect("reference image encodes");
    bytes[4] = 2;
    bytes
}

fn unknown_opcode() -> Vec<u8> {
    let mut bytes = encode_image(&minimal_return_i32()).expect("reference image encodes");
    let opcode_offset = bytes
        .iter()
        .rposition(|value| *value == 1)
        .expect("fixture contains I32Const opcode");
    bytes[opcode_offset] = 255;
    bytes
}

fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .canonicalize()
        .expect("repo root exists")
}
