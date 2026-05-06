use ckl_vm::abi::{decode_module, Instruction};

#[test]
fn decodes_minimal_module_header() {
    let bytes = minimal_module_bytes();
    let module = decode_module(&bytes).expect("module decodes");

    assert_eq!(module.name, "main");
    assert_eq!(module.entry_function_index, 0);
    assert_eq!(module.functions.len(), 1);
    assert_eq!(module.functions[0].name, "main");
    assert_eq!(module.functions[0].instructions, vec![Instruction::Return]);
}

fn minimal_module_bytes() -> Vec<u8> {
    let mut bytes = Vec::new();
    bytes.extend_from_slice(b"CKVM");
    bytes.push(1);
    write_string(&mut bytes, "main");
    write_i32(&mut bytes, 0);
    write_i32(&mut bytes, 0);
    write_i32(&mut bytes, 0);
    write_i32(&mut bytes, 1);
    write_string(&mut bytes, "main");
    write_i32(&mut bytes, 0);
    write_i32(&mut bytes, 0);
    write_string(&mut bytes, "Unit");
    write_i32(&mut bytes, 1);
    bytes.push(29);
    bytes
}

fn write_i32(bytes: &mut Vec<u8>, value: i32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_string(bytes: &mut Vec<u8>, value: &str) {
    write_i32(bytes, value.len() as i32);
    bytes.extend_from_slice(value.as_bytes());
}
