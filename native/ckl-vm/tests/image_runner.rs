use ckl_vm::image_runner::ImageVmHandle;

const OP_PUSH_UNIT: u8 = 1;
const OP_RETURN: u8 = 2;
const OP_PUSH_BOOL: u8 = 6;
const OP_PUSH_NULL: u8 = 7;
const OP_LOAD_LOCAL: u8 = 8;
const OP_STORE_LOCAL: u8 = 9;
const OP_JUMP: u8 = 10;
const OP_JUMP_IF_FALSE: u8 = 11;
const OP_JUMP_IF_TRUE: u8 = 12;

#[test]
fn stores_and_loads_local_value() {
    let code = vec![
        OP_PUSH_BOOL,
        1,
        OP_STORE_LOCAL,
        0,
        0,
        0,
        0,
        OP_LOAD_LOCAL,
        0,
        0,
        0,
        0,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(&image_with_code(1, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 2, 1]);
}

#[test]
fn supports_null_values() {
    let code = vec![OP_PUSH_NULL, OP_RETURN];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 1]);
}

#[test]
fn jumps_over_unreachable_code() {
    let code = vec![
        OP_JUMP,
        7,
        0,
        0,
        0,
        OP_PUSH_UNIT,
        OP_RETURN,
        OP_PUSH_BOOL,
        1,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 2, 1]);
}

#[test]
fn conditional_false_jump_takes_branch() {
    let code = vec![
        OP_PUSH_BOOL,
        0,
        OP_JUMP_IF_FALSE,
        10,
        0,
        0,
        0,
        OP_PUSH_BOOL,
        1,
        OP_RETURN,
        OP_PUSH_NULL,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 1]);
}

#[test]
fn conditional_true_jump_takes_branch() {
    let code = vec![
        OP_PUSH_BOOL,
        1,
        OP_JUMP_IF_TRUE,
        9,
        0,
        0,
        0,
        OP_PUSH_NULL,
        OP_RETURN,
        OP_PUSH_BOOL,
        1,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 2, 1]);
}

#[test]
fn rejects_non_bool_condition() {
    let code = vec![OP_PUSH_UNIT, OP_JUMP_IF_FALSE, 0, 0, 0, 0];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("requires Bool condition"));
}

#[test]
fn rejects_out_of_range_jump_target() {
    let code = vec![OP_JUMP, 99, 0, 0, 0];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("outside function code"));
}

#[test]
fn rejects_out_of_range_local_slot() {
    let code = vec![OP_LOAD_LOCAL, 1, 0, 0, 0, OP_RETURN];
    let mut vm = ImageVmHandle::create(&image_with_code(1, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("local slot 1 is out of bounds"));
}

#[test]
fn rejects_store_local_stack_underflow() {
    let code = vec![OP_STORE_LOCAL, 0, 0, 0, 0, OP_RETURN];
    let mut vm = ImageVmHandle::create(&image_with_code(1, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("stack underflow"));
}

fn image_with_code(frame_size: i32, code: Vec<u8>) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"CKIM");
    out.push(1);
    string(&mut out, "ckl-1");
    u16(&mut out, 1);
    list_len(&mut out, 0);
    list_len(&mut out, 0);
    list_len(&mut out, 0);
    i32(&mut out, 0);
    list_len(&mut out, 1);
    string(&mut out, "main");
    i32(&mut out, frame_size);
    list_len(&mut out, code.len() as i32);
    out.extend_from_slice(&code);
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
