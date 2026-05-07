use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::image::{decode_image, Constant, Function, HostImport, Image};
use crate::signal::{decode_value, encode_error, encode_signal};
use crate::value::VmValue;
use crate::vm::VmSignal;

const OP_PUSH_UNIT: u8 = 1;
const OP_RETURN: u8 = 2;
const OP_PUSH_CONSTANT: u8 = 3;
const OP_CALL_HOST: u8 = 4;
const OP_POP: u8 = 5;

pub struct ImageVmHandle {
    image: Image,
    function_index: usize,
    instruction_pointer: usize,
    stack: Vec<VmValue>,
    instruction_budget: usize,
    instructions_since_pause: usize,
    state: ImageVmState,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ImageVmState {
    Ready,
    WaitingForResume,
    Halted,
}

impl ImageVmHandle {
    pub fn create(image: &[u8], instruction_budget: usize) -> Result<Self, String> {
        let image = decode_image(image).map_err(|error| error.to_string())?;
        let function_index = checked_entry_function_index(&image)?;
        Ok(Self {
            image,
            function_index,
            instruction_pointer: 0,
            stack: Vec::new(),
            instruction_budget: instruction_budget.max(1),
            instructions_since_pause: 0,
            state: ImageVmState::Ready,
        })
    }

    pub fn run_until_signal(&mut self) -> Vec<u8> {
        match catch_unwind(AssertUnwindSafe(|| self.run_until_signal_inner())) {
            Ok(Ok(signal)) => encode_signal(&signal),
            Ok(Err(error)) => encode_error(error),
            Err(payload) => encode_error(panic_message(payload)),
        }
    }

    pub fn resume_with_value_bytes(&mut self, value: &[u8]) -> Result<(), String> {
        if self.state != ImageVmState::WaitingForResume {
            return Err("native image VM is not waiting for resume".to_string());
        }
        let value = decode_value(value)?;
        self.stack.push(value);
        self.state = ImageVmState::Ready;
        Ok(())
    }

    fn run_until_signal_inner(&mut self) -> Result<VmSignal, String> {
        match self.state {
            ImageVmState::Ready => {}
            ImageVmState::WaitingForResume => {
                return Err("native image VM is waiting for resume".to_string())
            }
            ImageVmState::Halted => return Err("native image VM is halted".to_string()),
        }

        loop {
            let opcode = match self.read_u8()? {
                Some(opcode) => opcode,
                None => return self.halt(VmValue::Unit),
            };
            self.instructions_since_pause += 1;

            match opcode {
                OP_PUSH_UNIT => self.stack.push(VmValue::Unit),
                OP_RETURN => {
                    let result = self.stack.pop().unwrap_or(VmValue::Unit);
                    return self.halt(result);
                }
                OP_PUSH_CONSTANT => {
                    let constant_index = self.read_i32()?;
                    let value = self.constant_value(constant_index)?;
                    self.stack.push(value);
                }
                OP_CALL_HOST => {
                    let import_id = self.read_i32()?;
                    let argument_count = self.read_i32()?;
                    let arguments = self.pop_many(argument_count)?;
                    let import = self.host_import(import_id)?;
                    let module_name = import.module_name.clone();
                    let function_name = import.function_name.clone();
                    self.state = ImageVmState::WaitingForResume;
                    return Ok(VmSignal::HostCall {
                        module_name,
                        function_name,
                        arguments,
                    });
                }
                OP_POP => {
                    let _ = self.stack.pop();
                }
                other => return Err(format!("unknown CkVmImage opcode {other}")),
            }

            if self.instructions_since_pause >= self.instruction_budget {
                self.instructions_since_pause = 0;
                return Ok(VmSignal::Pause);
            }
        }
    }

    fn halt(&mut self, value: VmValue) -> Result<VmSignal, String> {
        self.state = ImageVmState::Halted;
        Ok(VmSignal::Halt(value))
    }

    fn read_u8(&mut self) -> Result<Option<u8>, String> {
        let code = &self.current_function()?.code;
        if self.instruction_pointer >= code.len() {
            return Ok(None);
        }
        let value = code[self.instruction_pointer];
        self.instruction_pointer += 1;
        Ok(Some(value))
    }

    fn read_i32(&mut self) -> Result<i32, String> {
        let code = &self.current_function()?.code;
        let end = self
            .instruction_pointer
            .checked_add(4)
            .ok_or_else(|| "CkVmImage instruction offset overflow".to_string())?;
        let bytes = code
            .get(self.instruction_pointer..end)
            .ok_or_else(|| "unexpected end of CkVmImage instruction stream".to_string())?;
        let mut buffer = [0u8; 4];
        buffer.copy_from_slice(bytes);
        self.instruction_pointer = end;
        Ok(i32::from_le_bytes(buffer))
    }

    fn constant_value(&self, constant_index: i32) -> Result<VmValue, String> {
        if constant_index < 0 {
            return Err(format!(
                "negative CkVmImage constant index {constant_index}"
            ));
        }
        match self.image.constants.get(constant_index as usize) {
            Some(Constant::String(value)) => Ok(VmValue::String(value.clone())),
            Some(Constant::Int(value)) => Ok(VmValue::Int(*value)),
            Some(Constant::Long(value)) => Ok(VmValue::Long(*value)),
            None => Err(format!(
                "CkVmImage constant index {constant_index} is out of bounds"
            )),
        }
    }

    fn host_import(&self, import_id: i32) -> Result<&HostImport, String> {
        self.image
            .host_imports
            .iter()
            .find(|import| import.id == import_id)
            .ok_or_else(|| format!("CkVmImage host import id {import_id} is not declared"))
    }

    fn pop_many(&mut self, argument_count: i32) -> Result<Vec<VmValue>, String> {
        if argument_count < 0 {
            return Err(format!(
                "negative CkVmImage argument count {argument_count}"
            ));
        }
        let argument_count = argument_count as usize;
        if self.stack.len() < argument_count {
            return Err(format!(
                "CkVmImage stack underflow: need {argument_count} arguments but stack has {}",
                self.stack.len()
            ));
        }
        let start = self.stack.len() - argument_count;
        Ok(self.stack.split_off(start))
    }

    fn current_function(&self) -> Result<&Function, String> {
        self.image
            .functions
            .get(self.function_index)
            .ok_or_else(|| {
                format!(
                    "CkVmImage function index {} is out of bounds",
                    self.function_index
                )
            })
    }
}

fn checked_entry_function_index(image: &Image) -> Result<usize, String> {
    if image.entry_function_index < 0 {
        return Err(format!(
            "negative CkVmImage entry function index {}",
            image.entry_function_index
        ));
    }
    let index = image.entry_function_index as usize;
    if index >= image.functions.len() {
        return Err(format!(
            "CkVmImage entry function index {} is out of bounds for {} functions",
            image.entry_function_index,
            image.functions.len()
        ));
    }
    Ok(index)
}

fn panic_message(payload: Box<dyn std::any::Any + Send>) -> String {
    if let Some(message) = payload.downcast_ref::<&str>() {
        (*message).to_string()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "native image VM panic".to_string()
    }
}
