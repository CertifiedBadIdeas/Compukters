use crate::abi::{Instruction, Module};
use crate::value::VmValue;

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
		}
	}

	pub fn run_until_signal(&mut self) -> VmSignal {
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
				Instruction::Binary(0) => {
					let right = self.pop();
					let left = self.pop();
					self.current_frame_mut().stack.push(add_values(left, right));
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
						return VmSignal::Yield;
					}
					if module_name.is_empty() && function_name == "sleep" {
						return VmSignal::Sleep(as_i64(arguments.first().unwrap_or(&VmValue::Long(0))));
					}
					return VmSignal::HostCall { module_name, function_name, arguments };
				}
				other => panic!("instruction not implemented in pure VM prototype: {other:?}"),
			}

			if self.instructions_since_pause >= self.instruction_budget {
				self.instructions_since_pause = 0;
				return VmSignal::Pause;
			}
		}
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

	fn handle_return(&mut self, result: VmValue) -> VmSignal {
		self.frames.pop();
		if let Some(caller) = self.frames.last_mut() {
			caller.stack.push(result);
			self.run_until_signal()
		} else {
			VmSignal::Halt(result)
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

fn add_values(left: VmValue, right: VmValue) -> VmValue {
	match (left, right) {
		(VmValue::String(left), right) => VmValue::String(format!("{left}{}", render_value(&right))),
		(left, VmValue::String(right)) => VmValue::String(format!("{}{right}", render_value(&left))),
		(VmValue::Int(left), VmValue::Int(right)) => VmValue::Int(left + right),
		(VmValue::Long(left), VmValue::Long(right)) => VmValue::Long(left + right),
		(VmValue::Int(left), VmValue::Long(right)) => VmValue::Long(left as i64 + right),
		(VmValue::Long(left), VmValue::Int(right)) => VmValue::Long(left + right as i64),
		_ => panic!("invalid add operands"),
	}
}

fn as_i64(value: &VmValue) -> i64 {
	match value {
		VmValue::Int(value) => *value as i64,
		VmValue::Long(value) => *value,
		_ => 0,
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
