use ckl_vm::signal::VmSignal;
use ckl_vm::signal::{decode_value, encode_error, encode_signal, encode_value};
use ckl_vm::value::VmValue;

#[test]
fn encodes_halt_int_signal() {
    let bytes = encode_signal(&VmSignal::Halt(VmValue::Int(42)));

    assert_eq!(bytes, vec![0, 3, 42, 0, 0, 0]);
}

#[test]
fn decodes_primitive_values_for_resume() {
    assert_eq!(decode_value(&[0]).unwrap(), VmValue::Unit);
    assert_eq!(decode_value(&[1]).unwrap(), VmValue::Null);
    assert_eq!(decode_value(&[2, 1]).unwrap(), VmValue::Bool(true));
    assert_eq!(decode_value(&[3, 7, 0, 0, 0]).unwrap(), VmValue::Int(7));
    assert_eq!(
        decode_value(&[4, 9, 0, 0, 0, 0, 0, 0, 0]).unwrap(),
        VmValue::Long(9)
    );
    assert_eq!(
        decode_value(&[5, 2, 0, 0, 0, b'o', b'k']).unwrap(),
        VmValue::String("ok".to_string())
    );
}

#[test]
fn encodes_primitive_values_for_resume() {
    assert_eq!(encode_value(&VmValue::Unit), vec![0]);
    assert_eq!(encode_value(&VmValue::Null), vec![1]);
    assert_eq!(encode_value(&VmValue::Bool(false)), vec![2, 0]);
    assert_eq!(encode_value(&VmValue::Int(42)), vec![3, 42, 0, 0, 0]);
    assert_eq!(
        encode_value(&VmValue::Long(42)),
        vec![4, 42, 0, 0, 0, 0, 0, 0, 0]
    );
    assert_eq!(
        encode_value(&VmValue::String("x".to_string())),
        vec![5, 1, 0, 0, 0, b'x']
    );
}

#[test]
fn encodes_and_decodes_record_values_for_event_resume() {
    let value = VmValue::Record {
        type_name: "Event".to_string(),
        fields: vec![
            ("name".to_string(), VmValue::String("boot".to_string())),
            ("id".to_string(), VmValue::Int(7)),
        ],
    };

    let bytes = encode_value(&value);

    assert_eq!(decode_value(&bytes).unwrap(), value);
}

#[test]
fn rejects_invalid_resume_value_bytes() {
    assert!(decode_value(&[]).unwrap_err().contains("unexpected end"));
    assert!(decode_value(&[99])
        .unwrap_err()
        .contains("unknown native VM value tag"));
}

#[test]
fn encodes_halt_string_signal() {
    let bytes = encode_signal(&VmSignal::Halt(VmValue::String("ok".to_string())));

    assert_eq!(bytes, vec![0, 5, 2, 0, 0, 0, b'o', b'k']);
}

#[test]
fn encodes_host_call_signal() {
    let bytes = encode_signal(&VmSignal::HostCall {
        module_name: "system".to_string(),
        function_name: "log".to_string(),
        arguments: vec![VmValue::String("hello".to_string())],
    });

    assert_eq!(
        bytes,
        vec![
            4, 6, 0, 0, 0, b's', b'y', b's', b't', b'e', b'm', 3, 0, 0, 0, b'l', b'o', b'g', 1, 0,
            0, 0, 5, 5, 0, 0, 0, b'h', b'e', b'l', b'l', b'o',
        ],
    );
}

#[test]
fn encodes_wait_event_signal() {
    let bytes = encode_signal(&VmSignal::WaitEvent(Some("boot".to_string())));

    assert_eq!(bytes, [5, 1, 4, 0, 0, 0, b'b', b'o', b'o', b't']);
}

#[test]
fn encodes_error_signal() {
    let bytes = encode_error("bad bytecode");

    assert_eq!(
        bytes,
        vec![
            255, 12, 0, 0, 0, b'b', b'a', b'd', b' ', b'b', b'y', b't', b'e', b'c', b'o', b'd',
            b'e'
        ],
    );
}
