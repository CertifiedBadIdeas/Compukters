use rux_vm::low_image::{
    decode_image, encode_image, Function, Image, ImageEncodeError, ImageError, Instruction,
};

#[test]
fn decodes_low_level_image_with_linear_memory_layout() {
    let image = decode_image(&representative_image_bytes()).expect("low image decodes");

    assert_eq!(image.memory_size, 4096);
    assert_eq!(image.rodata, vec![1, 2, 3]);
    assert_eq!(image.data, vec![4, 5]);
    assert_eq!(image.bss_size, 16);
    assert_eq!(image.entry_function_index, 0);
    assert_eq!(image.functions.len(), 1);
    assert_eq!(image.functions[0].register_count, 3u16);
    assert_eq!(
        image.functions[0].instructions,
        vec![
            Instruction::AddrConst { dst: 2, value: 128 },
            Instruction::I32Const { dst: 0, value: 7 },
            Instruction::Store32 { addr: 2, src: 0 },
            Instruction::Load32 { dst: 1, addr: 2 },
            Instruction::ReturnI32 { src: 1 },
        ],
    );
}

#[test]
fn encodes_low_level_image_with_linear_memory_layout() {
    let bytes = representative_image_bytes();
    let image = decode_image(&bytes).expect("low image decodes");

    assert_eq!(encode_image(&image).expect("low image encodes"), bytes);
}

#[test]
fn roundtrips_all_instruction_variants() {
    let image = all_instruction_variants_image();
    let encoded = encode_image(&image).expect("low image encodes");

    assert_eq!(
        decode_image(&encoded).expect("encoded image decodes"),
        image
    );
}

#[test]
fn rejects_entry_function_index_that_does_not_fit_the_abi() {
    let image = Image {
        memory_size: 1024,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: i32::MAX as usize + 1,
        functions: vec![Function {
            name: "main".to_string(),
            register_count: 0,
            parameters: Vec::new(),
            instructions: vec![Instruction::ReturnUnit],
        }],
    };

    assert_eq!(
        encode_image(&image),
        Err(ImageEncodeError::IndexTooLarge {
            name: "entry function",
            value: i32::MAX as usize + 1,
        }),
    );
}

#[test]
fn rejects_legacy_image_versions() {
    let mut bytes = representative_image_bytes();
    bytes[4] = 4;

    assert_eq!(decode_image(&bytes), Err(ImageError::UnsupportedVersion(4)),);
}

#[test]
fn decodes_kotlin_generated_low_fixture() {
    let image =
        decode_image(include_bytes!("fixtures/low-representative.ruxi")).expect("fixture decodes");

    assert_eq!(image.memory_size, 4096);
    assert_eq!(image.functions[0].instructions.len(), 5);
}

#[test]
fn decodes_i32_equality_instruction() {
    let image = decode_image(&i32_equality_image_bytes()).expect("image decodes");

    assert_eq!(
        image.functions[0].instructions,
        vec![
            Instruction::I32Eq {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::ReturnBool { src: 2 },
        ],
    );
}

#[test]
fn decodes_u32_less_than_instruction() {
    let image = decode_image(&u32_less_than_image_bytes()).expect("image decodes");

    assert_eq!(
        image.functions[0].instructions,
        vec![
            Instruction::U32Lt {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::ReturnBool { src: 2 },
        ],
    );
}

#[test]
fn decodes_u32_shift_instructions() {
    let image = decode_image(&u32_shift_image_bytes()).expect("image decodes");

    assert_eq!(
        image.functions[0].instructions,
        vec![
            Instruction::U32Shl {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::U32Shr {
                dst: 3,
                lhs: 2,
                rhs: 1,
            },
            Instruction::ReturnI32 { src: 3 },
        ],
    );
}

#[test]
fn decodes_division_remainder_instructions() {
    let image = decode_image(&division_remainder_image_bytes()).expect("image decodes");

    assert_eq!(
        image.functions[0].instructions,
        vec![
            Instruction::I32Rem {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::U32Div {
                dst: 3,
                lhs: 0,
                rhs: 1,
            },
            Instruction::U32Rem {
                dst: 4,
                lhs: 0,
                rhs: 1,
            },
            Instruction::ReturnI32 { src: 4 },
        ],
    );
}

fn division_remainder_image_bytes() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"RUXI");
    out.push(1);
    u32(&mut out, 1024);
    bytes(&mut out, &[]);
    bytes(&mut out, &[]);
    u32(&mut out, 0);
    i32(&mut out, 0);
    i32(&mut out, 1);
    string(&mut out, "main");
    u16(&mut out, 5);
    i32(&mut out, 0);
    i32(&mut out, 4);
    out.push(33);
    u16(&mut out, 2);
    u16(&mut out, 0);
    u16(&mut out, 1);
    out.push(34);
    u16(&mut out, 3);
    u16(&mut out, 0);
    u16(&mut out, 1);
    out.push(35);
    u16(&mut out, 4);
    u16(&mut out, 0);
    u16(&mut out, 1);
    out.push(20);
    u16(&mut out, 4);
    out
}

fn all_instruction_variants_image() -> Image {
    Image {
        memory_size: 1024,
        rodata: vec![0xaa, 0xbb],
        data: vec![0xcc],
        bss_size: 4,
        entry_function_index: 0,
        functions: vec![
            Function {
                name: "main".to_string(),
                register_count: 8,
                parameters: vec![0, 1],
                instructions: vec![
                    Instruction::I32Const { dst: 0, value: -7 },
                    Instruction::I64Const {
                        dst: 1,
                        value: 0x1122_3344_5566_7788,
                    },
                    Instruction::AddrConst { dst: 2, value: 128 },
                    Instruction::I32Move { dst: 3, src: 0 },
                    Instruction::AddrMove { dst: 4, src: 2 },
                    Instruction::I32Add {
                        dst: 5,
                        lhs: 0,
                        rhs: 3,
                    },
                    Instruction::I32Sub {
                        dst: 5,
                        lhs: 5,
                        rhs: 3,
                    },
                    Instruction::I32Mul {
                        dst: 5,
                        lhs: 5,
                        rhs: 3,
                    },
                    Instruction::I32Div {
                        dst: 5,
                        lhs: 5,
                        rhs: 3,
                    },
                    Instruction::I32BitXor {
                        dst: 5,
                        lhs: 5,
                        rhs: 3,
                    },
                    Instruction::I32Shl {
                        dst: 5,
                        lhs: 5,
                        rhs: 3,
                    },
                    Instruction::I32Shr {
                        dst: 5,
                        lhs: 5,
                        rhs: 3,
                    },
                    Instruction::I32Lt {
                        dst: 6,
                        lhs: 0,
                        rhs: 3,
                    },
                    Instruction::Load32 { dst: 5, addr: 2 },
                    Instruction::Store32 { addr: 2, src: 5 },
                    Instruction::AddrAdd {
                        dst: 4,
                        base: 2,
                        offset: 3,
                    },
                    Instruction::Jump { target: 0 },
                    Instruction::JumpIfFalse { cond: 6, target: 0 },
                    Instruction::CallStatic {
                        return_register: Some(5),
                        function_index: 1,
                        arguments: vec![0],
                    },
                    Instruction::CallStatic {
                        return_register: None,
                        function_index: 1,
                        arguments: vec![0],
                    },
                    Instruction::ReturnI32 { src: 5 },
                    Instruction::ReturnUnit,
                    Instruction::ReturnI64 { src: 1 },
                    Instruction::ReturnAddr { src: 2 },
                    Instruction::ReturnBool { src: 6 },
                    Instruction::I32Eq {
                        dst: 6,
                        lhs: 0,
                        rhs: 3,
                    },
                    Instruction::I32BitAnd {
                        dst: 5,
                        lhs: 5,
                        rhs: 3,
                    },
                    Instruction::I32BitOr {
                        dst: 5,
                        lhs: 5,
                        rhs: 3,
                    },
                    Instruction::U32Lt {
                        dst: 6,
                        lhs: 0,
                        rhs: 3,
                    },
                    Instruction::U32Shl {
                        dst: 5,
                        lhs: 5,
                        rhs: 3,
                    },
                    Instruction::U32Shr {
                        dst: 5,
                        lhs: 5,
                        rhs: 3,
                    },
                    Instruction::Load8 { dst: 5, addr: 2 },
                    Instruction::Store8 { addr: 2, src: 5 },
                    Instruction::Load16 { dst: 5, addr: 2 },
                    Instruction::Store16 { addr: 2, src: 5 },
                    Instruction::I32Rem {
                        dst: 5,
                        lhs: 5,
                        rhs: 3,
                    },
                    Instruction::U32Div {
                        dst: 5,
                        lhs: 5,
                        rhs: 3,
                    },
                    Instruction::U32Rem {
                        dst: 5,
                        lhs: 5,
                        rhs: 3,
                    },
                ],
            },
            Function {
                name: "callee".to_string(),
                register_count: 1,
                parameters: vec![0],
                instructions: vec![Instruction::ReturnUnit],
            },
        ],
    }
}

fn i32_equality_image_bytes() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"RUXI");
    out.push(1);
    u32(&mut out, 1024);
    bytes(&mut out, &[]);
    bytes(&mut out, &[]);
    u32(&mut out, 0);
    i32(&mut out, 0);
    i32(&mut out, 1);
    string(&mut out, "main");
    u16(&mut out, 3);
    i32(&mut out, 0);
    i32(&mut out, 2);
    out.push(25);
    u16(&mut out, 2);
    u16(&mut out, 0);
    u16(&mut out, 1);
    out.push(24);
    u16(&mut out, 2);
    out
}

fn u32_less_than_image_bytes() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"RUXI");
    out.push(1);
    u32(&mut out, 1024);
    bytes(&mut out, &[]);
    bytes(&mut out, &[]);
    u32(&mut out, 0);
    i32(&mut out, 0);
    i32(&mut out, 1);
    string(&mut out, "main");
    u16(&mut out, 3);
    i32(&mut out, 0);
    i32(&mut out, 2);
    out.push(28);
    u16(&mut out, 2);
    u16(&mut out, 0);
    u16(&mut out, 1);
    out.push(24);
    u16(&mut out, 2);
    out
}

fn u32_shift_image_bytes() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"RUXI");
    out.push(1);
    u32(&mut out, 1024);
    bytes(&mut out, &[]);
    bytes(&mut out, &[]);
    u32(&mut out, 0);
    i32(&mut out, 0);
    i32(&mut out, 1);
    string(&mut out, "main");
    u16(&mut out, 4);
    i32(&mut out, 0);
    i32(&mut out, 3);
    out.push(29);
    u16(&mut out, 2);
    u16(&mut out, 0);
    u16(&mut out, 1);
    out.push(30);
    u16(&mut out, 3);
    u16(&mut out, 2);
    u16(&mut out, 1);
    out.push(20);
    u16(&mut out, 3);
    out
}

fn representative_image_bytes() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"RUXI");
    out.push(1);
    u32(&mut out, 4096);
    bytes(&mut out, &[1, 2, 3]);
    bytes(&mut out, &[4, 5]);
    u32(&mut out, 16);
    i32(&mut out, 0);
    i32(&mut out, 1);
    string(&mut out, "main");
    u16(&mut out, 3);
    i32(&mut out, 0);
    i32(&mut out, 5);
    out.push(3);
    u16(&mut out, 2);
    u32(&mut out, 128);
    out.push(1);
    u16(&mut out, 0);
    i32(&mut out, 7);
    out.push(15);
    u16(&mut out, 2);
    u16(&mut out, 0);
    out.push(14);
    u16(&mut out, 1);
    u16(&mut out, 2);
    out.push(20);
    u16(&mut out, 1);
    out
}

fn string(out: &mut Vec<u8>, value: &str) {
    i32(out, value.len() as i32);
    out.extend_from_slice(value.as_bytes());
}

fn bytes(out: &mut Vec<u8>, value: &[u8]) {
    i32(out, value.len() as i32);
    out.extend_from_slice(value);
}

fn u16(out: &mut Vec<u8>, value: u16) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn i32(out: &mut Vec<u8>, value: i32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn u32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_le_bytes());
}
