use crate::low_image::{Image, Instruction, Register};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LowImageSignal {
    HaltUnit,
    HaltI32(i32),
    HaltI64(i64),
    HaltAddr(u32),
    HaltBool(bool),
    Pause,
}

#[derive(Clone, Copy)]
enum LowRegisterValue {
    I32(i32),
    I64(i64),
    Addr(u32),
    Bool(bool),
}

struct LowFrame {
    function_index: usize,
    instruction_pointer: usize,
    return_register: Option<Register>,
    i32_registers: Vec<i32>,
    i64_registers: Vec<i64>,
    addr_registers: Vec<u32>,
    bool_registers: Vec<bool>,
}

impl LowFrame {
    fn create(
        function_index: usize,
        function: &crate::low_image::Function,
        return_register: Option<Register>,
    ) -> Self {
        Self {
            function_index,
            instruction_pointer: 0,
            return_register,
            i32_registers: vec![0; function.i32_register_count],
            i64_registers: vec![0; function.i64_register_count],
            addr_registers: vec![0; function.addr_register_count],
            bool_registers: vec![false; function.bool_register_count],
        }
    }
}

pub struct LowImageVm {
    image: Image,
    frames: Vec<LowFrame>,
    memory: Vec<u8>,
    instruction_budget: usize,
    instructions_since_pause: usize,
}

impl LowImageVm {
    pub fn create(image: Image, instruction_budget: usize) -> Result<Self, String> {
        if image.entry_function_index >= image.functions.len() {
            return Err(format!(
                "entry function index {} is out of bounds",
                image.entry_function_index,
            ));
        }
        let memory_size = usize::try_from(image.memory_size)
            .map_err(|_| "memory size does not fit usize".to_string())?;
        let initialized = image
            .rodata
            .len()
            .checked_add(image.data.len())
            .and_then(|value| value.checked_add(image.bss_size as usize))
            .ok_or_else(|| "memory sections overflow".to_string())?;
        if initialized > memory_size {
            return Err(format!(
                "memory sections require {initialized} bytes but memory size is {memory_size}",
            ));
        }
        let mut memory = vec![0_u8; memory_size];
        memory[..image.rodata.len()].copy_from_slice(&image.rodata);
        let data_start = image.rodata.len();
        memory[data_start..data_start + image.data.len()].copy_from_slice(&image.data);
        let entry_function_index = image.entry_function_index;
        let entry_frame = LowFrame::create(
            entry_function_index,
            &image.functions[entry_function_index],
            None,
        );
        Ok(Self {
            frames: vec![entry_frame],
            image,
            memory,
            instruction_budget: instruction_budget.max(1),
            instructions_since_pause: 0,
        })
    }

    pub fn memory_bytes(&self) -> &[u8] {
        &self.memory
    }

    pub fn run_until_signal(&mut self) -> Result<LowImageSignal, String> {
        loop {
            let (function_index, instruction_pointer) = {
                let frame = self.current_frame_mut()?;
                let instruction_pointer = frame.instruction_pointer;
                frame.instruction_pointer += 1;
                (frame.function_index, instruction_pointer)
            };
            let instruction = self
                .image
                .functions
                .get(function_index)
                .ok_or_else(|| format!("function index {function_index} is out of bounds"))?
                .instructions
                .get(instruction_pointer)
                .cloned()
                .unwrap_or(Instruction::ReturnUnit);
            self.instructions_since_pause += 1;
            match instruction {
                Instruction::I32Const { dst, value } => self.write_i32(dst, value)?,
                Instruction::I64Const { dst, value } => self.write_i64(dst, value)?,
                Instruction::AddrConst { dst, value } => self.write_addr(dst, value)?,
                Instruction::I32Move { dst, src } => {
                    let value = self.read_i32(src)?;
                    self.write_i32(dst, value)?;
                }
                Instruction::AddrMove { dst, src } => {
                    let value = self.read_addr(src)?;
                    self.write_addr(dst, value)?;
                }
                Instruction::I32Add { dst, lhs, rhs } => {
                    let value = self.read_i32(lhs)?.wrapping_add(self.read_i32(rhs)?);
                    self.write_i32(dst, value)?;
                }
                Instruction::I32Sub { dst, lhs, rhs } => {
                    let value = self.read_i32(lhs)?.wrapping_sub(self.read_i32(rhs)?);
                    self.write_i32(dst, value)?;
                }
                Instruction::I32Mul { dst, lhs, rhs } => {
                    let value = self.read_i32(lhs)?.wrapping_mul(self.read_i32(rhs)?);
                    self.write_i32(dst, value)?;
                }
                Instruction::I32Div { dst, lhs, rhs } => {
                    let rhs = self.read_i32(rhs)?;
                    if rhs == 0 {
                        return Err("division by zero".to_string());
                    }
                    let value = self.read_i32(lhs)?.wrapping_div(rhs);
                    self.write_i32(dst, value)?;
                }
                Instruction::I32BitXor { dst, lhs, rhs } => {
                    let value = self.read_i32(lhs)? ^ self.read_i32(rhs)?;
                    self.write_i32(dst, value)?;
                }
                Instruction::I32Shl { dst, lhs, rhs } => {
                    let value = self.read_i32(lhs)?.wrapping_shl(self.read_i32(rhs)? as u32);
                    self.write_i32(dst, value)?;
                }
                Instruction::I32Shr { dst, lhs, rhs } => {
                    let value = self.read_i32(lhs)?.wrapping_shr(self.read_i32(rhs)? as u32);
                    self.write_i32(dst, value)?;
                }
                Instruction::I32Lt { dst, lhs, rhs } => {
                    let value = self.read_i32(lhs)? < self.read_i32(rhs)?;
                    self.write_bool(dst, value)?;
                }
                Instruction::Load32 { dst, addr } => {
                    let address = self.read_addr(addr)?;
                    let bytes = self.memory_range(address, 4)?;
                    let mut raw = [0_u8; 4];
                    raw.copy_from_slice(bytes);
                    self.write_i32(dst, i32::from_le_bytes(raw))?;
                }
                Instruction::Store32 { addr, src } => {
                    let address = self.read_addr(addr)?;
                    let value = self.read_i32(src)?.to_le_bytes();
                    self.memory_range_mut(address, 4)?.copy_from_slice(&value);
                }
                Instruction::AddrAdd { dst, base, offset } => {
                    let base = self.read_addr(base)?;
                    let offset = self.read_i32(offset)?;
                    let value = if offset >= 0 {
                        base.wrapping_add(offset as u32)
                    } else {
                        base.wrapping_sub(offset.wrapping_abs() as u32)
                    };
                    self.write_addr(dst, value)?;
                }
                Instruction::Jump { target } => self.jump(target)?,
                Instruction::JumpIfFalse { cond, target } => {
                    if !self.read_bool(cond)? {
                        self.jump(target)?;
                    }
                }
                Instruction::Return { src } => {
                    if let Some(signal) = self.return_value(src)? {
                        return Ok(signal);
                    }
                }
                Instruction::ReturnUnit => {
                    if let Some(signal) = self.return_unit()? {
                        return Ok(signal);
                    }
                }
                Instruction::CallStatic {
                    return_register,
                    function_index,
                    arguments,
                } => self.call_static(return_register, function_index, arguments)?,
            }
            if self.instructions_since_pause >= self.instruction_budget {
                self.instructions_since_pause = 0;
                return Ok(LowImageSignal::Pause);
            }
        }
    }

    fn current_function(&self) -> Result<&crate::low_image::Function, String> {
        let function_index = self.current_frame()?.function_index;
        self.image
            .functions
            .get(function_index)
            .ok_or_else(|| format!("function index {function_index} is out of bounds"))
    }

    fn current_frame(&self) -> Result<&LowFrame, String> {
        self.frames
            .last()
            .ok_or_else(|| "low VM call stack is empty".to_string())
    }

    fn current_frame_mut(&mut self) -> Result<&mut LowFrame, String> {
        self.frames
            .last_mut()
            .ok_or_else(|| "low VM call stack is empty".to_string())
    }

    fn return_value(&mut self, register: Register) -> Result<Option<LowImageSignal>, String> {
        let value = self.read_register_value(register)?;
        if self.frames.len() == 1 {
            return Ok(Some(self.signal_from_value(value)?));
        }
        let return_register = self.current_frame()?.return_register;
        self.frames.pop();
        if let Some(return_register) = return_register {
            self.write_register_value(return_register, value)?;
        }
        Ok(None)
    }

    fn return_unit(&mut self) -> Result<Option<LowImageSignal>, String> {
        if self.frames.len() == 1 {
            return Ok(Some(LowImageSignal::HaltUnit));
        }
        let return_register = self.current_frame()?.return_register;
        self.frames.pop();
        if let Some(return_register) = return_register {
            return Err(format!(
                "callee returned unit but caller expected {return_register:?}",
            ));
        }
        Ok(None)
    }

    fn call_static(
        &mut self,
        return_register: Option<Register>,
        function_index: usize,
        arguments: Vec<Register>,
    ) -> Result<(), String> {
        let values = arguments
            .into_iter()
            .map(|argument| self.read_register_value(argument))
            .collect::<Result<Vec<_>, _>>()?;
        let function = self
            .image
            .functions
            .get(function_index)
            .ok_or_else(|| format!("function index {function_index} is out of bounds"))?;
        if values.len() != function.parameters.len() {
            return Err(format!(
                "function {function_index} expects {} arguments but call provided {}",
                function.parameters.len(),
                values.len(),
            ));
        }
        let parameters = function.parameters.clone();
        let mut frame = LowFrame::create(function_index, function, return_register);
        for (parameter, value) in parameters.into_iter().zip(values) {
            Self::write_register_value_to_frame(&mut frame, parameter, value)?;
        }
        self.frames.push(frame);
        Ok(())
    }

    fn signal_from_value(&self, value: LowRegisterValue) -> Result<LowImageSignal, String> {
        match value {
            LowRegisterValue::I32(value) => Ok(LowImageSignal::HaltI32(value)),
            LowRegisterValue::I64(value) => Ok(LowImageSignal::HaltI64(value)),
            LowRegisterValue::Addr(value) => Ok(LowImageSignal::HaltAddr(value)),
            LowRegisterValue::Bool(value) => Ok(LowImageSignal::HaltBool(value)),
        }
    }

    fn read_register_value(&self, register: Register) -> Result<LowRegisterValue, String> {
        match register {
            Register::I32(index) => Ok(LowRegisterValue::I32(self.read_i32(index)?)),
            Register::I64(index) => Ok(LowRegisterValue::I64(self.read_i64(index)?)),
            Register::Addr(index) => Ok(LowRegisterValue::Addr(self.read_addr(index)?)),
            Register::Bool(index) => Ok(LowRegisterValue::Bool(self.read_bool(index)?)),
        }
    }

    fn write_register_value(
        &mut self,
        register: Register,
        value: LowRegisterValue,
    ) -> Result<(), String> {
        match (register, value) {
            (Register::I32(index), LowRegisterValue::I32(value)) => self.write_i32(index, value),
            (Register::I64(index), LowRegisterValue::I64(value)) => self.write_i64(index, value),
            (Register::Addr(index), LowRegisterValue::Addr(value)) => self.write_addr(index, value),
            (Register::Bool(index), LowRegisterValue::Bool(value)) => self.write_bool(index, value),
            (register, _) => Err(format!("return value type does not match {register:?}")),
        }
    }

    fn write_register_value_to_frame(
        frame: &mut LowFrame,
        register: Register,
        value: LowRegisterValue,
    ) -> Result<(), String> {
        match (register, value) {
            (Register::I32(index), LowRegisterValue::I32(value)) => {
                *frame
                    .i32_registers
                    .get_mut(index as usize)
                    .ok_or_else(|| format!("i32 register {index} is out of bounds"))? = value;
                Ok(())
            }
            (Register::I64(index), LowRegisterValue::I64(value)) => {
                *frame
                    .i64_registers
                    .get_mut(index as usize)
                    .ok_or_else(|| format!("i64 register {index} is out of bounds"))? = value;
                Ok(())
            }
            (Register::Addr(index), LowRegisterValue::Addr(value)) => {
                *frame
                    .addr_registers
                    .get_mut(index as usize)
                    .ok_or_else(|| format!("addr register {index} is out of bounds"))? = value;
                Ok(())
            }
            (Register::Bool(index), LowRegisterValue::Bool(value)) => {
                *frame
                    .bool_registers
                    .get_mut(index as usize)
                    .ok_or_else(|| format!("bool register {index} is out of bounds"))? = value;
                Ok(())
            }
            (register, _) => Err(format!("argument value type does not match {register:?}")),
        }
    }

    fn read_i32(&self, register: u16) -> Result<i32, String> {
        self.current_frame()?
            .i32_registers
            .get(register as usize)
            .copied()
            .ok_or_else(|| format!("i32 register {register} is out of bounds"))
    }

    fn write_i32(&mut self, register: u16, value: i32) -> Result<(), String> {
        *self
            .current_frame_mut()?
            .i32_registers
            .get_mut(register as usize)
            .ok_or_else(|| format!("i32 register {register} is out of bounds"))? = value;
        Ok(())
    }

    fn read_i64(&self, register: u16) -> Result<i64, String> {
        self.current_frame()?
            .i64_registers
            .get(register as usize)
            .copied()
            .ok_or_else(|| format!("i64 register {register} is out of bounds"))
    }

    fn write_i64(&mut self, register: u16, value: i64) -> Result<(), String> {
        *self
            .current_frame_mut()?
            .i64_registers
            .get_mut(register as usize)
            .ok_or_else(|| format!("i64 register {register} is out of bounds"))? = value;
        Ok(())
    }

    fn read_addr(&self, register: u16) -> Result<u32, String> {
        self.current_frame()?
            .addr_registers
            .get(register as usize)
            .copied()
            .ok_or_else(|| format!("addr register {register} is out of bounds"))
    }

    fn write_addr(&mut self, register: u16, value: u32) -> Result<(), String> {
        *self
            .current_frame_mut()?
            .addr_registers
            .get_mut(register as usize)
            .ok_or_else(|| format!("addr register {register} is out of bounds"))? = value;
        Ok(())
    }

    fn read_bool(&self, register: u16) -> Result<bool, String> {
        self.current_frame()?
            .bool_registers
            .get(register as usize)
            .copied()
            .ok_or_else(|| format!("bool register {register} is out of bounds"))
    }

    fn write_bool(&mut self, register: u16, value: bool) -> Result<(), String> {
        *self
            .current_frame_mut()?
            .bool_registers
            .get_mut(register as usize)
            .ok_or_else(|| format!("bool register {register} is out of bounds"))? = value;
        Ok(())
    }

    fn jump(&mut self, target: usize) -> Result<(), String> {
        let instruction_count = self.current_function()?.instructions.len();
        if target > instruction_count {
            return Err(format!(
                "jump target {target} is outside function instruction count {instruction_count}",
            ));
        }
        self.current_frame_mut()?.instruction_pointer = target;
        Ok(())
    }

    fn memory_range(&self, address: u32, size: usize) -> Result<&[u8], String> {
        let start = address as usize;
        let end = start
            .checked_add(size)
            .ok_or_else(|| format!("memory access starts at {address} and overflows usize"))?;
        self.memory.get(start..end).ok_or_else(|| {
            format!(
                "memory access {start}..{end} is outside {} bytes",
                self.memory.len(),
            )
        })
    }

    fn memory_range_mut(&mut self, address: u32, size: usize) -> Result<&mut [u8], String> {
        let start = address as usize;
        let end = start
            .checked_add(size)
            .ok_or_else(|| format!("memory access starts at {address} and overflows usize"))?;
        let len = self.memory.len();
        self.memory
            .get_mut(start..end)
            .ok_or_else(|| format!("memory access {start}..{end} is outside {len} bytes"))
    }
}
