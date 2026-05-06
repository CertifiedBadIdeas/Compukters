use ckl_vm::signal::{encode_error, encode_signal};
use ckl_vm::value::VmValue;
use ckl_vm::vm::VmSignal;

#[test]
fn encodes_halt_int_signal() {
	let bytes = encode_signal(&VmSignal::Halt(VmValue::Int(42)));

	assert_eq!(bytes, vec![0, 3, 42, 0, 0, 0]);
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
			4, 6, 0, 0, 0, b's', b'y', b's', b't', b'e', b'm', 3, 0, 0, 0, b'l', b'o', b'g', 1, 0, 0,
			0, 5, 5, 0, 0, 0, b'h', b'e', b'l', b'l', b'o',
		],
	);
}

#[test]
fn encodes_error_signal() {
	let bytes = encode_error("bad bytecode");

	assert_eq!(
		bytes,
		vec![255, 12, 0, 0, 0, b'b', b'a', b'd', b' ', b'b', b'y', b't', b'e', b'c', b'o', b'd', b'e'],
	);
}
