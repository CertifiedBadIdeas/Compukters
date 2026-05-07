use ckl_vm::image::{decode_image, Constant, ImageError};

#[test]
fn decodes_representative_image() {
    let bytes = representative_image_bytes();
    let image = decode_image(&bytes).expect("image decodes");

    assert_eq!(image.language_version, "ckl-1");
    assert_eq!(image.target_abi_version, 1);
    assert_eq!(image.capabilities, vec!["host-import-ids"]);
    assert_eq!(
        image.constants,
        vec![Constant::String("hello".to_string()), Constant::Int(7), Constant::Long(9)]
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
    assert_eq!(image.functions[0].frame_size, 8);
    assert_eq!(image.functions[0].code, vec![1, 2, 3]);
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

    assert_eq!(decode_image(&bytes), Err(ImageError::UnsupportedVersion(99)));
}

#[test]
fn rejects_unknown_constant_tag() {
    let mut bytes = representative_image_bytes();
    let first_constant_tag_offset = 43;
    bytes[first_constant_tag_offset] = 99;

    assert_eq!(decode_image(&bytes), Err(ImageError::UnknownConstantTag(99)));
}

#[test]
fn decodes_kotlin_generated_fixture() {
    let bytes = include_bytes!("fixtures/representative.ckim");
    let image = decode_image(bytes).expect("fixture decodes");

    assert_eq!(image.language_version, "ckl-1");
    assert_eq!(image.target_abi_version, 1);
    assert_eq!(image.capabilities, vec!["host-import-ids"]);
    assert_eq!(image.functions[0].code, vec![1, 2, 3]);
}

#[test]
fn decodes_backend_generated_system_log_fixture() {
    let bytes = include_bytes!("fixtures/backend-system-log.ckim");
    let image = decode_image(bytes).expect("backend fixture decodes");

    assert_eq!(image.language_version, "ckl-1");
    assert_eq!(image.capabilities, vec!["host-import-ids"]);
    assert_eq!(image.constants, vec![Constant::String("hi".to_string())]);
    assert_eq!(image.host_imports.len(), 1);
    assert_eq!(image.host_imports[0].module_name, "system");
    assert_eq!(image.host_imports[0].function_name, "log");
    assert_eq!(image.functions[0].code, vec![3, 0, 0, 0, 0, 4, 0, 0, 0, 0, 1, 0, 0, 0, 5, 1, 2]);
}

fn representative_image_bytes() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"CKIM");
    out.push(1);
    string(&mut out, "ckl-1");
    u16(&mut out, 1);
    list_len(&mut out, 1);
    string(&mut out, "host-import-ids");
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
    i32(&mut out, 8);
    list_len(&mut out, 3);
    out.extend_from_slice(&[1, 2, 3]);
    out
}

fn list_len(out: &mut Vec<u8>, value: i32) {
    i32(out, value);
}

fn string(out: &mut Vec<u8>, value: &str) {
    i32(out, value.len() as i32);
    out.extend_from_slice(value.as_bytes());
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