use crate::abi::{Instruction, Module};
use crate::value::VmValue;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum VmError {
	#[error("native VM is waiting for resume")]
	WaitingForResume,
	#[error("native VM is not waiting for resume")]
	NotWaitingForResume,
	#[error("native VM is halted")]
	Halted,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VmSignal {
	Halt(VmValue),
	Pause,
	Yield,
	Sleep(i64),
	HostCall {
		module_name: String,
		function_name: String,
		arguments: Vec<VmValue>,
	},
}

pub struct VmInstance {
	module: Module,
	frames: Vec<Frame>,
	instruction_budget: usize,
	instructions_since_pause: usize,
	state: VmState,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum VmState {
	Ready,
	WaitingForResume,
	Halted,
}

struct Frame {
	function_index: usize,
	instruction_pointer: usize,
	locals: Vec<VmValue>,
	stack: Vec<VmValue>,
}

impl VmInstance {
	pub fn new(module: Module, instruction_budget: usize) -> Self {
		let entry = module.entry_function_index as usize;
		let entry_frame = create_frame(&module, entry, vec![]);
		Self {
			module,
			frames: vec![entry_frame],
			instruction_budget: instruction_budget.max(1),
			instructions_since_pause: 0,
			state: VmState::Ready,
		}
	}

	pub fn run_until_signal(&mut self) -> Result<VmSignal, VmError> {
		match self.state {
			VmState::Ready => {}
			VmState::WaitingForResume => return Err(VmError::WaitingForResume),
			VmState::Halted => return Err(VmError::Halted),
		}

		loop {
			let instruction = match self.next_instruction() {
				Some(instruction) => instruction,
				None => return self.handle_return(VmValue::Unit),
			};
			self.instructions_since_pause += 1;

			match instruction {
				Instruction::PushInt(value) => self.current_frame_mut().stack.push(VmValue::Int(value)),
				Instruction::PushLong(value) => self.current_frame_mut().stack.push(VmValue::Long(value)),
				Instruction::PushString(value) => self.current_frame_mut().stack.push(VmValue::String(value)),
				Instruction::PushBool(value) => self.current_frame_mut().stack.push(VmValue::Bool(value)),
				Instruction::PushUnit => self.current_frame_mut().stack.push(VmValue::Unit),
				Instruction::PushNull => self.current_frame_mut().stack.push(VmValue::Null),
				Instruction::LoadLocal(slot) => {
					let value = self.current_frame_mut().locals[slot as usize].clone();
					self.current_frame_mut().stack.push(value);
				}
				Instruction::StoreLocal(slot) => {
					let value = self.pop();
					let slot = slot as usize;
					let frame = self.current_frame_mut();
					while frame.locals.len() <= slot {
						frame.locals.push(VmValue::Unit);
					}
					frame.locals[slot] = value;
				}
				Instruction::Pop => {
					self.pop();
				}
				Instruction::Binary(operator) => {
					let right = self.pop();
					let left = self.pop();
					self.current_frame_mut().stack.push(apply_binary(operator, left, right));
				}
				Instruction::CallFunction { function_index, argument_count } => {
					let arguments = self.pop_many(argument_count as usize);
					let frame = create_frame(&self.module, function_index as usize, arguments);
					self.frames.push(frame);
				}
				Instruction::Return => {
					let result = self.current_frame_mut().stack.pop().unwrap_or(VmValue::Unit);
					return self.handle_return(result);
				}
				Instruction::CallBuiltin { module_name, function_name, argument_count } => {
					let mut arguments = Vec::with_capacity(argument_count as usize);
					for _ in 0..argument_count {
						arguments.push(self.pop());
					}
					arguments.reverse();
					if module_name.is_empty() && function_name == "yield" {
						self.state = VmState::WaitingForResume;
						return Ok(VmSignal::Yield);
					}
					if module_name.is_empty() && function_name == "sleep" {
						self.state = VmState::WaitingForResume;
						return Ok(VmSignal::Sleep(as_i64(arguments.first().unwrap_or(&VmValue::Long(0)))));
					}
					self.state = VmState::WaitingForResume;
					return Ok(VmSignal::HostCall { module_name, function_name, arguments });
				}
				other => panic!("instruction not implemented in pure VM prototype: {other:?}"),
			}

			if self.instructions_since_pause >= self.instruction_budget {
				self.instructions_since_pause = 0;
				return Ok(VmSignal::Pause);
			}
		}
	}

	pub fn resume_with(&mut self, value: VmValue) -> Result<(), VmError> {
		if self.state != VmState::WaitingForResume {
			return Err(VmError::NotWaitingForResume);
		}
		self.current_frame_mut().stack.push(value);
		self.state = VmState::Ready;
		Ok(())
	}

	fn next_instruction(&mut self) -> Option<Instruction> {
		let frame = self.frames.last_mut()?;
		let function = &self.module.functions[frame.function_index];
		let instruction = function.instructions.get(frame.instruction_pointer).cloned();
		if instruction.is_some() {
			frame.instruction_pointer += 1;
		}
		instruction
	}

	fn current_frame_mut(&mut self) -> &mut Frame {
		self.frames.last_mut().expect("VM has an active frame")
	}

	fn pop(&mut self) -> VmValue {
		self.current_frame_mut().stack.pop().expect("VM stack value")
	}

	fn pop_many(&mut self, count: usize) -> Vec<VmValue> {
		let mut values = Vec::with_capacity(count);
		for _ in 0..count {
			values.push(self.pop());
		}
		values.reverse();
		values
	}

	fn handle_return(&mut self, result: VmValue) -> Result<VmSignal, VmError> {
		self.frames.pop();
		if let Some(caller) = self.frames.last_mut() {
			caller.stack.push(result);
			self.run_until_signal()
		} else {
			self.state = VmState::Halted;
			Ok(VmSignal::Halt(result))
		}
	}
}

fn create_frame(module: &Module, function_index: usize, arguments: Vec<VmValue>) -> Frame {
	let function = &module.functions[function_index];
	let local_count = function.locals.len().max(function.parameters.len()).max(arguments.len());
	let mut locals = vec![VmValue::Unit; local_count];
	for (index, argument) in arguments.into_iter().enumerate() {
		locals[index] = argument;
	}
	Frame { function_index, instruction_pointer: 0, locals, stack: vec![] }
}

fn apply_binary(operator: u8, left: VmValue, right: VmValue) -> VmValue {
	match operator {
		0 => add_values(left, right),
		1 => numeric_values(left, right, |left, right| left - right, |left, right| left - right),
		2 => numeric_values(left, right, |left, right| left * right, |left, right| left * right),
		3 => numeric_values(left, right, |left, right| left / right, |left, right| left / right),
		4 => VmValue::Bool(left == right),
		5 => VmValue::Bool(left != right),
		6 => VmValue::Bool(as_i64(&left) < as_i64(&right)),
		7 => VmValue::Bool(as_i64(&left) <= as_i64(&right)),
		8 => VmValue::Bool(as_i64(&left) > as_i64(&right)),
		9 => VmValue::Bool(as_i64(&left) >= as_i64(&right)),
		10 => VmValue::Bool(as_bool(&left) && as_bool(&right)),
		11 => VmValue::Bool(as_bool(&left) || as_bool(&right)),
		12 => numeric_values(left, right, |left, right| left & right, |left, right| left & right),
		13 => numeric_values(left, right, |left, right| left | right, |left, right| left | right),
		14 => numeric_values(left, right, |left, right| left ^ right, |left, right| left ^ right),
		15 => shift_value(left, right, |left, right| left << right, |left, right| left << right),
		16 => shift_value(left, right, |left, right| left >> right, |left, right| left >> right),
		other => panic!("unknown binary operator tag {other}"),
	}
}

fn add_values(left: VmValue, right: VmValue) -> VmValue {
	match (&left, &right) {
		(VmValue::String(left), right) => VmValue::String(format!("{left}{}", render_value(right))),
		(left, VmValue::String(right)) => VmValue::String(format!("{}{right}", render_value(left))),
		_ => numeric_values(left, right, |left, right| left + right, |left, right| left + right),
	}
}

fn numeric_values(
	left: VmValue,
	right: VmValue,
	int_op: impl FnOnce(i32, i32) -> i32,
	long_op: impl FnOnce(i64, i64) -> i64,
) -> VmValue {
	match (left, right) {
		(VmValue::Int(left), VmValue::Int(right)) => VmValue::Int(int_op(left, right)),
		(left, right) => VmValue::Long(long_op(as_i64(&left), as_i64(&right))),
	}
}

fn shift_value(
	left: VmValue,
	right: VmValue,
	int_op: impl FnOnce(i32, i32) -> i32,
	long_op: impl FnOnce(i64, i32) -> i64,
) -> VmValue {
	let amount = as_i64(&right) as i32;
	match left {
		VmValue::Int(left) => VmValue::Int(int_op(left, amount)),
		left => VmValue::Long(long_op(as_i64(&left), amount)),
	}
}

fn as_bool(value: &VmValue) -> bool {
	match value {
		VmValue::Bool(value) => *value,
		_ => panic!("expected Bool value"),
	}
}

fn as_i64(value: &VmValue) -> i64 {
	match value {
		VmValue::Int(value) => *value as i64,
		VmValue::Long(value) => *value,
		_ => panic!("expected numeric value"),
	}
}

fn render_value(value: &VmValue) -> String {
	match value {
		VmValue::Unit => "unit".to_string(),
		VmValue::Null => "null".to_string(),
		VmValue::Bool(value) => value.to_string(),
		VmValue::Int(value) => value.to_string(),
		VmValue::Long(value) => value.to_string(),
		VmValue::String(value) => value.clone(),
		VmValue::ObjectRef(value) => format!("object#{value}"),
	}
}
