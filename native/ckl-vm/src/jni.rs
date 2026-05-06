use std::ptr::null_mut;

use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jint};
use jni::JNIEnv;

use crate::runner::run_bytecode_until_signal;

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_native_NativeVmBindings_runUntilSignalNative(
	mut env: JNIEnv<'_>,
	_class: JClass<'_>,
	bytecode: JByteArray<'_>,
	instruction_budget: jint,
) -> jbyteArray {
	let bytecode = match env.convert_byte_array(&bytecode) {
		Ok(bytecode) => bytecode,
		Err(error) => {
			let _ = env.throw_new("java/lang/IllegalArgumentException", format!("Cannot read CKVM bytecode: {error}"));
			return null_mut();
		}
	};

	let signal = run_bytecode_until_signal(&bytecode, instruction_budget.max(1) as usize);
	match env.byte_array_from_slice(&signal) {
		Ok(array) => array.into_raw(),
		Err(error) => {
			let _ = env.throw_new("java/lang/IllegalStateException", format!("Cannot allocate native VM signal: {error}"));
			null_mut()
		}
	}
}
