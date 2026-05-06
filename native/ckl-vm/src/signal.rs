use crate::value::VmValue;
use crate::vm::VmSignal;

const SIGNAL_HALT: u8 = 0;
const SIGNAL_PAUSE: u8 = 1;
const SIGNAL_YIELD: u8 = 2;
const SIGNAL_SLEEP: u8 = 3;
const SIGNAL_HOST_CALL: u8 = 4;
const SIGNAL_ERROR: u8 = 255;

const VALUE_UNIT: u8 = 0;
const VALUE_NULL: u8 = 1;
const VALUE_BOOL: u8 = 2;
const VALUE_INT: u8 = 3;
const VALUE_LONG: u8 = 4;
const VALUE_STRING: u8 = 5;

pub fn encode_signal(signal: &VmSignal) -> Vec<u8> {
	let mut writer = Writer::default();
	match signal {
		VmSignal::Halt(value) => {
			writer.u8(SIGNAL_HALT);
			writer.value(value);
		}
		VmSignal::Pause => writer.u8(SIGNAL_PAUSE),
		VmSignal::Yield => writer.u8(SIGNAL_YIELD),
		VmSignal::Sleep(ticks) => {
			writer.u8(SIGNAL_SLEEP);
			writer.i64(*ticks);
		}
		VmSignal::HostCall { module_name, function_name, arguments } => {
			writer.u8(SIGNAL_HOST_CALL);
			writer.string(module_name);
			writer.string(function_name);
			writer.i32(arguments.len() as i32);
			for argument in arguments {
				writer.value(argument);
			}
		}
	}
	writer.finish()
}

pub fn encode_error(message: impl AsRef<str>) -> Vec<u8> {
	let mut writer = Writer::default();
	writer.u8(SIGNAL_ERROR);
	writer.string(message.as_ref());
	writer.finish()
}

#[derive(Default)]
struct Writer {
	bytes: Vec<u8>,
}

impl Writer {
	fn finish(self) -> Vec<u8> {
		self.bytes
	}

	fn u8(&mut self, value: u8) {
		self.bytes.push(value);
	}

	fn i32(&mut self, value: i32) {
		self.bytes.extend_from_slice(&value.to_le_bytes());
	}

	fn i64(&mut self, value: i64) {
		self.bytes.extend_from_slice(&value.to_le_bytes());
	}

	fn string(&mut self, value: &str) {
		self.i32(value.len() as i32);
		self.bytes.extend_from_slice(value.as_bytes());
	}

	fn value(&mut self, value: &VmValue) {
		match value {
			VmValue::Unit => self.u8(VALUE_UNIT),
			VmValue::Null => self.u8(VALUE_NULL),
			VmValue::Bool(value) => {
				self.u8(VALUE_BOOL);
				self.u8(u8::from(*value));
			}
			VmValue::Int(value) => {
				self.u8(VALUE_INT);
				self.i32(*value);
			}
			VmValue::Long(value) => {
				self.u8(VALUE_LONG);
				self.i64(*value);
			}
			VmValue::String(value) => {
				self.u8(VALUE_STRING);
				self.string(value);
			}
			VmValue::ObjectRef(value) => {
				self.u8(VALUE_STRING);
				self.string(&format!("object#{value}"));
			}
		}
	}
}
