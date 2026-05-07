use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::image::{decode_image, Constant, Function, HostImport, Image};
use crate::signal::{decode_value, encode_error, encode_signal, VmSignal};
use crate::value::VmValue;

const OP_PUSH_UNIT: u8 = 1;
const OP_RETURN: u8 = 2;
const OP_PUSH_CONSTANT: u8 = 3;
const OP_CALL_HOST: u8 = 4;
const OP_POP: u8 = 5;
const OP_PUSH_BOOL: u8 = 6;
const OP_PUSH_NULL: u8 = 7;
const OP_LOAD_LOCAL: u8 = 8;
const OP_STORE_LOCAL: u8 = 9;
const OP_JUMP: u8 = 10;
const OP_JUMP_IF_FALSE: u8 = 11;
const OP_JUMP_IF_TRUE: u8 = 12;
const OP_BINARY: u8 = 13;
const OP_UNARY: u8 = 14;

pub struct ImageVmHandle {
    image: Image,
    function_index: usize,
    instruction_pointer: usize,
    stack: Vec<VmValue>,
    locals: Vec<VmValue>,
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
        let frame_size = checked_frame_size(&image, function_index)?;
        Ok(Self {
            image,
            function_index,
            instruction_pointer: 0,
            stack: Vec::new(),
            locals: vec![VmValue::Unit; frame_size],
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
                OP_PUSH_BOOL => {
                    let value = self.read_u8()?.ok_or_else(|| {
                        "unexpected end of CkVmImage instruction stream".to_string()
                    })?;
                    match value {
                        0 => self.stack.push(VmValue::Bool(false)),
                        1 => self.stack.push(VmValue::Bool(true)),
                        other => return Err(format!("invalid CkVmImage bool byte {other}")),
                    }
                }
                OP_PUSH_NULL => self.stack.push(VmValue::Null),
                OP_LOAD_LOCAL => {
                    let slot = self.read_i32()?;
                    let value = self.local(slot)?.clone();
                    self.stack.push(value);
                }
                OP_STORE_LOCAL => {
                    let slot = self.read_i32()?;
                    let value = self.pop_one("store local")?;
                    *self.local_mut(slot)? = value;
                }
                OP_JUMP => {
                    let target = self.read_i32()?;
                    self.jump(target)?;
                }
                OP_JUMP_IF_FALSE => {
                    let target = self.read_i32()?;
                    if !self.pop_bool_condition("JUMP_IF_FALSE")? {
                        self.jump(target)?;
                    }
                }
                OP_JUMP_IF_TRUE => {
                    let target = self.read_i32()?;
                    if self.pop_bool_condition("JUMP_IF_TRUE")? {
                        self.jump(target)?;
                    }
                }
                OP_BINARY => {
                    let operator = self.read_u8()?.ok_or_else(|| {
                        "unexpected end of CkVmImage instruction stream".to_string()
                    })?;
                    let right = self.pop_one("binary right operand")?;
                    let left = self.pop_one("binary left operand")?;
                    self.stack
                        .push(apply_binary_operator(operator, left, right)?);
                }
                OP_UNARY => {
                    let operator = self.read_u8()?.ok_or_else(|| {
                        "unexpected end of CkVmImage instruction stream".to_string()
                    })?;
                    let operand = self.pop_one("unary operand")?;
                    self.stack.push(apply_unary_operator(operator, operand)?);
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

    fn pop_one(&mut self, operation: &str) -> Result<VmValue, String> {
        self.stack
            .pop()
            .ok_or_else(|| format!("CkVmImage stack underflow during {operation}"))
    }

    fn pop_bool_condition(&mut self, opcode_name: &str) -> Result<bool, String> {
        match self.pop_one(opcode_name)? {
            VmValue::Bool(value) => Ok(value),
            other => Err(format!(
                "CkVmImage {opcode_name} requires Bool condition but found {other:?}"
            )),
        }
    }

    fn local(&self, slot: i32) -> Result<&VmValue, String> {
        if slot < 0 {
            return Err(format!("CkVmImage local slot {slot} is negative"));
        }
        self.locals.get(slot as usize).ok_or_else(|| {
            format!(
                "CkVmImage local slot {slot} is out of bounds for {} locals",
                self.locals.len()
            )
        })
    }

    fn local_mut(&mut self, slot: i32) -> Result<&mut VmValue, String> {
        if slot < 0 {
            return Err(format!("CkVmImage local slot {slot} is negative"));
        }
        let local_count = self.locals.len();
        self.locals.get_mut(slot as usize).ok_or_else(|| {
            format!("CkVmImage local slot {slot} is out of bounds for {local_count} locals")
        })
    }

    fn jump(&mut self, target: i32) -> Result<(), String> {
        if target < 0 {
            return Err(format!("CkVmImage jump target {target} is negative"));
        }
        let target = target as usize;
        let code_len = self.current_function()?.code.len();
        if target > code_len {
            return Err(format!(
                "CkVmImage jump target {target} is outside function code length {code_len}"
            ));
        }
        self.instruction_pointer = target;
        Ok(())
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

fn checked_frame_size(image: &Image, function_index: usize) -> Result<usize, String> {
    let frame_size = image.functions[function_index].frame_size;
    if frame_size < 0 {
        return Err(format!("negative CkVmImage frame size {frame_size}"));
    }
    Ok(frame_size as usize)
}

fn apply_binary_operator(operator: u8, left: VmValue, right: VmValue) -> Result<VmValue, String> {
    match operator {
        0 => binary_add(left, right),
        1 => numeric_binary(
            left,
            right,
            "-",
            |a, b| a.wrapping_sub(b),
            |a, b| a.wrapping_sub(b),
        ),
        2 => numeric_binary(
            left,
            right,
            "*",
            |a, b| a.wrapping_mul(b),
            |a, b| a.wrapping_mul(b),
        ),
        3 => binary_divide(left, right),
        4 => Ok(VmValue::Bool(value_equals(&left, &right))),
        5 => Ok(VmValue::Bool(!value_equals(&left, &right))),
        6 => compare_values(left, right, "<", |ordering| ordering.is_lt()),
        7 => compare_values(left, right, "<=", |ordering| !ordering.is_gt()),
        8 => compare_values(left, right, ">", |ordering| ordering.is_gt()),
        9 => compare_values(left, right, ">=", |ordering| !ordering.is_lt()),
        10 => bool_binary(left, right, "&&", |a, b| a && b),
        11 => bool_binary(left, right, "||", |a, b| a || b),
        12 => numeric_binary(left, right, "&", |a, b| a & b, |a, b| a & b),
        13 => numeric_binary(left, right, "|", |a, b| a | b, |a, b| a | b),
        14 => numeric_binary(left, right, "^", |a, b| a ^ b, |a, b| a ^ b),
        15 => shift_binary(
            left,
            right,
            "<<",
            |a, b| a.wrapping_shl(b),
            |a, b| a.wrapping_shl(b),
        ),
        16 => shift_binary(
            left,
            right,
            ">>",
            |a, b| a.wrapping_shr(b),
            |a, b| a.wrapping_shr(b),
        ),
        other => Err(format!("unknown CkVmImage binary operator tag {other}")),
    }
}

fn apply_unary_operator(operator: u8, operand: VmValue) -> Result<VmValue, String> {
    match operator {
        0 => match operand {
            VmValue::Int(value) => Ok(VmValue::Int(value.wrapping_neg())),
            VmValue::Long(value) => Ok(VmValue::Long(value.wrapping_neg())),
            other => Err(format!(
                "CkVmImage unary - requires Int or Long but found {other:?}"
            )),
        },
        1 => match operand {
            VmValue::Bool(value) => Ok(VmValue::Bool(!value)),
            other => Err(format!(
                "CkVmImage unary ! requires Bool but found {other:?}"
            )),
        },
        2 => match operand {
            VmValue::Int(value) => Ok(VmValue::Int(!value)),
            VmValue::Long(value) => Ok(VmValue::Long(!value)),
            other => Err(format!(
                "CkVmImage unary ~ requires Int or Long but found {other:?}"
            )),
        },
        other => Err(format!("unknown CkVmImage unary operator tag {other}")),
    }
}

fn binary_add(left: VmValue, right: VmValue) -> Result<VmValue, String> {
    match (left, right) {
        (VmValue::String(left), right) => Ok(VmValue::String(left + &value_to_string(&right))),
        (left, VmValue::String(right)) => Ok(VmValue::String(value_to_string(&left) + &right)),
        (VmValue::Int(left), VmValue::Int(right)) => Ok(VmValue::Int(left.wrapping_add(right))),
        (VmValue::Long(left), VmValue::Long(right)) => Ok(VmValue::Long(left.wrapping_add(right))),
        (VmValue::Int(left), VmValue::Long(right)) => {
            Ok(VmValue::Long((left as i64).wrapping_add(right)))
        }
        (VmValue::Long(left), VmValue::Int(right)) => {
            Ok(VmValue::Long(left.wrapping_add(right as i64)))
        }
        (left, right) => Err(format!(
            "CkVmImage binary + requires numbers or strings but found {left:?} and {right:?}"
        )),
    }
}

fn binary_divide(left: VmValue, right: VmValue) -> Result<VmValue, String> {
    match (left, right) {
        (_, VmValue::Int(0)) | (_, VmValue::Long(0)) => {
            Err("CkVmImage division by zero".to_string())
        }
        (VmValue::Int(left), VmValue::Int(right)) => Ok(VmValue::Int(left.wrapping_div(right))),
        (VmValue::Long(left), VmValue::Long(right)) => Ok(VmValue::Long(left.wrapping_div(right))),
        (VmValue::Int(left), VmValue::Long(right)) => {
            Ok(VmValue::Long((left as i64).wrapping_div(right)))
        }
        (VmValue::Long(left), VmValue::Int(right)) => {
            Ok(VmValue::Long(left.wrapping_div(right as i64)))
        }
        (left, right) => Err(format!(
            "CkVmImage binary / requires Int or Long but found {left:?} and {right:?}"
        )),
    }
}

fn numeric_binary(
    left: VmValue,
    right: VmValue,
    symbol: &str,
    int_op: fn(i32, i32) -> i32,
    long_op: fn(i64, i64) -> i64,
) -> Result<VmValue, String> {
    match (left, right) {
        (VmValue::Int(left), VmValue::Int(right)) => Ok(VmValue::Int(int_op(left, right))),
        (VmValue::Long(left), VmValue::Long(right)) => Ok(VmValue::Long(long_op(left, right))),
        (VmValue::Int(left), VmValue::Long(right)) => {
            Ok(VmValue::Long(long_op(left as i64, right)))
        }
        (VmValue::Long(left), VmValue::Int(right)) => {
            Ok(VmValue::Long(long_op(left, right as i64)))
        }
        (left, right) => Err(format!(
            "CkVmImage binary {symbol} requires Int or Long but found {left:?} and {right:?}"
        )),
    }
}

fn shift_binary(
    left: VmValue,
    right: VmValue,
    symbol: &str,
    int_op: fn(i32, u32) -> i32,
    long_op: fn(i64, u32) -> i64,
) -> Result<VmValue, String> {
    let shift = match right {
        VmValue::Int(value) => value as u32,
        VmValue::Long(value) => value as u32,
        other => {
            return Err(format!(
                "CkVmImage binary {symbol} shift count requires Int or Long but found {other:?}"
            ));
        }
    };
    match left {
        VmValue::Int(value) => Ok(VmValue::Int(int_op(value, shift))),
        VmValue::Long(value) => Ok(VmValue::Long(long_op(value, shift))),
        other => Err(format!(
            "CkVmImage binary {symbol} requires Int or Long left operand but found {other:?}"
        )),
    }
}

fn bool_binary(
    left: VmValue,
    right: VmValue,
    symbol: &str,
    op: fn(bool, bool) -> bool,
) -> Result<VmValue, String> {
    match (left, right) {
        (VmValue::Bool(left), VmValue::Bool(right)) => Ok(VmValue::Bool(op(left, right))),
        (left, right) => Err(format!(
            "CkVmImage binary {symbol} requires Bool but found {left:?} and {right:?}"
        )),
    }
}

fn compare_values(
    left: VmValue,
    right: VmValue,
    symbol: &str,
    predicate: fn(std::cmp::Ordering) -> bool,
) -> Result<VmValue, String> {
    let ordering = match (left, right) {
        (VmValue::Int(left), VmValue::Int(right)) => left.cmp(&right),
        (VmValue::Long(left), VmValue::Long(right)) => left.cmp(&right),
        (VmValue::Int(left), VmValue::Long(right)) => (left as i64).cmp(&right),
        (VmValue::Long(left), VmValue::Int(right)) => left.cmp(&(right as i64)),
        (VmValue::String(left), VmValue::String(right)) => left.cmp(&right),
        (left, right) => {
            return Err(format!(
                "CkVmImage binary {symbol} requires comparable values but found {left:?} and {right:?}"
            ));
        }
    };
    Ok(VmValue::Bool(predicate(ordering)))
}

fn value_equals(left: &VmValue, right: &VmValue) -> bool {
    match (left, right) {
        (VmValue::Int(left), VmValue::Long(right)) => i64::from(*left) == *right,
        (VmValue::Long(left), VmValue::Int(right)) => *left == i64::from(*right),
        _ => left == right,
    }
}

fn value_to_string(value: &VmValue) -> String {
    match value {
        VmValue::Unit => "unit".to_string(),
        VmValue::Null => "null".to_string(),
        VmValue::Bool(value) => value.to_string(),
        VmValue::Int(value) => value.to_string(),
        VmValue::Long(value) => value.to_string(),
        VmValue::String(value) => value.clone(),
        VmValue::Record { type_name, .. } => format!("{type_name}(...)"),
        VmValue::ObjectRef(value) => format!("object#{value}"),
    }
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
