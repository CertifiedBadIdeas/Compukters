use ckl_vm::image::{decode_image, Constant, ImageError, Instruction, TypedRegister};

#[test]
fn decodes_representative_register_image() {
    let bytes = representative_image_bytes();
    let image = decode_image(&bytes).expect("image decodes");

    assert_eq!(image.language_version, "ckl-1");
    assert_eq!(
        image.constants,
        vec![
            Constant::String("hello".to_string()),
            Constant::Int(7),
            Constant::Long(9)
        ]
    );
    assert_eq!(image.host_imports.len(), 1);
    assert_eq!(image.host_imports[0].id, 42);
    assert_eq!(image.host_imports[0].module_name, "display");
    assert_eq!(image.host_imports[0].function_name, "present");
    assert_eq!(image.host_imports[0].parameter_types, vec!["Int"]);
    assert_eq!(image.host_imports[0].return_type, "Unit");
    assert_eq!(image.entry_function_index, 0);
    assert_eq!(image.functions.len(), 1);
    assert_eq!(image.functions[0].name, "main");
    assert_eq!(image.functions[0].i32_register_count, 3);
    assert_eq!(image.functions[0].i64_register_count, 0);
    assert_eq!(image.functions[0].bool_register_count, 0);
    assert_eq!(image.functions[0].ref_register_count, 1);
    assert_eq!(image.functions[0].parameters, vec![TypedRegister::I32(0)]);
    assert_eq!(
        image.functions[0].instructions,
        vec![
            Instruction::RefConst {
                dst: 0,
                constant_index: 0,
            },
            Instruction::I32Add {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::CallHost {
                return_register: Some(TypedRegister::Ref(0)),
                import_id: 42,
                arguments: vec![TypedRegister::I32(2)],
            },
            Instruction::ReturnUnit,
        ]
    );
}

#[test]
fn rejects_invalid_magic() {
    let mut bytes = representative_image_bytes();
    bytes[0] = b'X';

    assert_eq!(decode_image(&bytes), Err(ImageError::InvalidMagic));
}

#[test]
fn rejects_unknown_version() {
    let mut bytes = representative_image_bytes();
    bytes[4] = 99;

    assert_eq!(
        decode_image(&bytes),
        Err(ImageError::UnsupportedVersion(99))
    );
}

#[test]
fn rejects_unknown_constant_tag() {
    let mut bytes = representative_image_bytes();
    let first_constant_tag_offset = 18;
    bytes[first_constant_tag_offset] = 99;

    assert_eq!(
        decode_image(&bytes),
        Err(ImageError::UnknownConstantTag(99))
    );
}

#[test]
fn decodes_kotlin_generated_fixture() {
    let bytes = include_bytes!("fixtures/representative.ckim");
    let image = decode_image(bytes).expect("fixture decodes");

    assert_eq!(image.language_version, "ckl-1");
    assert_eq!(image.functions[0].i32_register_count, 3);
    assert_eq!(image.functions[0].parameters, vec![TypedRegister::I32(0)]);
    assert_eq!(
        image.functions[0].instructions[1],
        Instruction::I32Add {
            dst: 2,
            lhs: 0,
            rhs: 1,
        }
    );
}

#[test]
fn decodes_backend_generated_system_log_fixture() {
    let bytes = include_bytes!("fixtures/backend-system-log.ckim");
    let image = decode_image(bytes).expect("backend fixture decodes");

    assert_eq!(image.language_version, "ckl-1");
    assert_eq!(image.constants, vec![Constant::String("hi".to_string())]);
    assert_eq!(image.host_imports.len(), 1);
    assert_eq!(image.host_imports[0].module_name, "system");
    assert_eq!(image.host_imports[0].function_name, "log");
    assert_eq!(image.host_imports[0].id, 3004);
    assert!(image.functions[0]
        .instructions
        .iter()
        .any(|instruction| matches!(
            instruction,
            Instruction::CallHost {
                import_id: 3004,
                ..
            }
        )));
}

fn representative_image_bytes() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"CKIM");
    out.push(3);
    string(&mut out, "ckl-1");
    list_len(&mut out, 3);
    out.push(1);
    string(&mut out, "hello");
    out.push(2);
    i32(&mut out, 7);
    out.push(3);
    i64(&mut out, 9);
    list_len(&mut out, 1);
    i32(&mut out, 42);
    string(&mut out, "display");
    string(&mut out, "present");
    list_len(&mut out, 1);
    string(&mut out, "Int");
    string(&mut out, "Unit");
    i32(&mut out, 0);
    list_len(&mut out, 1);
    string(&mut out, "main");
    u16(&mut out, 3);
    u16(&mut out, 0);
    u16(&mut out, 0);
    u16(&mut out, 1);
    typed_register_list(&mut out, &[TypedRegister::I32(0)]);
    list_len(&mut out, 4);
    out.push(4);
    u16(&mut out, 0);
    i32(&mut out, 0);
    out.push(11);
    u16(&mut out, 2);
    u16(&mut out, 0);
    u16(&mut out, 1);
    out.push(37);
    optional_typed_register(&mut out, Some(TypedRegister::Ref(0)));
    i32(&mut out, 42);
    typed_register_list(&mut out, &[TypedRegister::I32(2)]);
    out.push(36);
    out
}

fn list_len(out: &mut Vec<u8>, value: i32) {
    i32(out, value);
}

fn string(out: &mut Vec<u8>, value: &str) {
    i32(out, value.len() as i32);
    out.extend_from_slice(value.as_bytes());
}

fn optional_typed_register(out: &mut Vec<u8>, value: Option<TypedRegister>) {
    match value {
        Some(value) => {
            out.push(1);
            typed_register(out, value);
        }
        None => out.push(0),
    }
}

fn typed_register_list(out: &mut Vec<u8>, values: &[TypedRegister]) {
    list_len(out, values.len() as i32);
    for value in values {
        typed_register(out, *value);
    }
}

fn typed_register(out: &mut Vec<u8>, value: TypedRegister) {
    match value {
        TypedRegister::I32(index) => {
            out.push(1);
            u16(out, index);
        }
        TypedRegister::I64(index) => {
            out.push(2);
            u16(out, index);
        }
        TypedRegister::Bool(index) => {
            out.push(3);
            u16(out, index);
        }
        TypedRegister::Ref(index) => {
            out.push(4);
            u16(out, index);
        }
    }
}

fn u16(out: &mut Vec<u8>, value: u16) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn i32(out: &mut Vec<u8>, value: i32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn i64(out: &mut Vec<u8>, value: i64) {
    out.extend_from_slice(&value.to_le_bytes());
}
