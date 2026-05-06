use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::abi::decode_module;
use crate::signal::{encode_error, encode_signal};
use crate::vm::VmInstance;

pub fn run_bytecode_until_signal(bytecode: &[u8], instruction_budget: usize) -> Vec<u8> {
	let module = match decode_module(bytecode) {
		Ok(module) => module,
		Err(error) => return encode_error(error.to_string()),
	};

	let result = catch_unwind(AssertUnwindSafe(|| {
		let mut vm = VmInstance::new(module, instruction_budget.max(1));
		vm.run_until_signal()
	}));

	match result {
		Ok(Ok(signal)) => encode_signal(&signal),
		Ok(Err(error)) => encode_error(error.to_string()),
		Err(payload) => encode_error(panic_message(payload)),
	}
}

fn panic_message(payload: Box<dyn std::any::Any + Send>) -> String {
	if let Some(message) = payload.downcast_ref::<String>() {
		return message.clone();
	}
	if let Some(message) = payload.downcast_ref::<&'static str>() {
		return (*message).to_string();
	}
	"native VM runtime panic".to_string()
}
