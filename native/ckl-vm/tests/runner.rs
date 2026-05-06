use ckl_vm::runner::run_bytecode_until_signal;

#[test]
fn runs_minimal_integer_addition_module() {
	let bytes = addition_module_bytes();

	let signal = run_bytecode_until_signal(&bytes, 64);

	assert_eq!(signal, vec![0, 3, 3, 0, 0, 0]);
}

#[test]
fn returns_encoded_error_for_invalid_bytecode() {
	let signal = run_bytecode_until_signal(b"bad", 64);

	assert_eq!(signal[0], 255);
	assert_eq!(&signal[5..], b"unexpected end of input");
}

fn addition_module_bytes() -> Vec<u8> {
	let mut bytes = Vec::new();
	bytes.extend_from_slice(b"CKVM");
	bytes.push(1);
	write_string(&mut bytes, "test");
	write_i32(&mut bytes, 0);
	write_i32(&mut bytes, 0); // records
	write_i32(&mut bytes, 0); // classes
	write_i32(&mut bytes, 1); // functions
	write_string(&mut bytes, "main");
	write_i32(&mut bytes, 0); // parameters
	write_i32(&mut bytes, 0); // locals
	write_string(&mut bytes, "Int");
	write_i32(&mut bytes, 4); // instructions
	bytes.push(1); // PushInt
	write_i32(&mut bytes, 1);
	bytes.push(1); // PushInt
	write_i32(&mut bytes, 2);
	bytes.push(27); // Binary
	bytes.push(0); // ADD
	bytes.push(29); // Return
	bytes
}

fn write_i32(bytes: &mut Vec<u8>, value: i32) {
	bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_string(bytes: &mut Vec<u8>, value: &str) {
	write_i32(bytes, value.len() as i32);
	bytes.extend_from_slice(value.as_bytes());
}
