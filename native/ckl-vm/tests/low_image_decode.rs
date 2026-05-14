use ckl_vm::low_image::{decode_image, ImageError, Instruction};

#[test]
fn decodes_low_level_image_with_linear_memory_layout() {
    let image = decode_image(&representative_image_bytes()).expect("low image decodes");

    assert_eq!(image.language_version, "ckl-low-1");
    assert_eq!(image.memory_size, 4096);
    assert_eq!(image.rodata, vec![1, 2, 3]);
    assert_eq!(image.data, vec![4, 5]);
    assert_eq!(image.bss_size, 16);
    assert_eq!(image.entry_function_index, 0);
    assert_eq!(image.functions.len(), 1);
    assert_eq!(image.functions[0].register_count, 3);
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
fn rejects_legacy_image_versions() {
    let mut bytes = representative_image_bytes();
    bytes[4] = 4;

    assert_eq!(decode_image(&bytes), Err(ImageError::UnsupportedVersion(4)),);
}

#[test]
fn decodes_kotlin_generated_low_fixture() {
    let image =
        decode_image(include_bytes!("fixtures/low-representative.ckim")).expect("fixture decodes");

    assert_eq!(image.language_version, "ckl-low-1");
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

fn i32_equality_image_bytes() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"CKIM");
    out.push(5);
    string(&mut out, "ckl-low-1");
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
    out.extend_from_slice(b"CKIM");
    out.push(5);
    string(&mut out, "ckl-low-1");
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
    out.extend_from_slice(b"CKIM");
    out.push(5);
    string(&mut out, "ckl-low-1");
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
    out.extend_from_slice(b"CKIM");
    out.push(5);
    string(&mut out, "ckl-low-1");
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
