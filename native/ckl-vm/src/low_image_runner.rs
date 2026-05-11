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

pub struct LowImageVm {
    image: Image,
    function_index: usize,
    instruction_pointer: usize,
    i32_registers: Vec<i32>,
    i64_registers: Vec<i64>,
    addr_registers: Vec<u32>,
    bool_registers: Vec<bool>,
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
        let function = &image.functions[image.entry_function_index];
        let mut memory = vec![0_u8; memory_size];
        memory[..image.rodata.len()].copy_from_slice(&image.rodata);
        let data_start = image.rodata.len();
        memory[data_start..data_start + image.data.len()].copy_from_slice(&image.data);
        Ok(Self {
            i32_registers: vec![0; function.i32_register_count],
            i64_registers: vec![0; function.i64_register_count],
            addr_registers: vec![0; function.addr_register_count],
            bool_registers: vec![false; function.bool_register_count],
            image,
            function_index: 0,
            instruction_pointer: 0,
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
            let instruction = self
                .current_function()?
                .instructions
                .get(self.instruction_pointer)
                .cloned()
                .unwrap_or(Instruction::ReturnUnit);
            self.instruction_pointer += 1;
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
                Instruction::Return { src } => return self.halt_register(src),
                Instruction::ReturnUnit => return Ok(LowImageSignal::HaltUnit),
                Instruction::CallStatic { .. } => {
                    return Err(
                        "low VM CallStatic is not implemented in the first runner slice"
                            .to_string(),
                    )
                }
            }
            if self.instructions_since_pause >= self.instruction_budget {
                self.instructions_since_pause = 0;
                return Ok(LowImageSignal::Pause);
            }
        }
    }

    fn current_function(&self) -> Result<&crate::low_image::Function, String> {
        self.image
            .functions
            .get(self.function_index)
            .ok_or_else(|| format!("function index {} is out of bounds", self.function_index))
    }

    fn halt_register(&self, register: Register) -> Result<LowImageSignal, String> {
        match register {
            Register::I32(index) => Ok(LowImageSignal::HaltI32(
                *self
                    .i32_registers
                    .get(index as usize)
                    .ok_or_else(|| format!("i32 register {index} is out of bounds"))?,
            )),
            Register::I64(index) => Ok(LowImageSignal::HaltI64(
                *self
                    .i64_registers
                    .get(index as usize)
                    .ok_or_else(|| format!("i64 register {index} is out of bounds"))?,
            )),
            Register::Addr(index) => Ok(LowImageSignal::HaltAddr(
                *self
                    .addr_registers
                    .get(index as usize)
                    .ok_or_else(|| format!("addr register {index} is out of bounds"))?,
            )),
            Register::Bool(index) => Ok(LowImageSignal::HaltBool(
                *self
                    .bool_registers
                    .get(index as usize)
                    .ok_or_else(|| format!("bool register {index} is out of bounds"))?,
            )),
        }
    }

    fn read_i32(&self, register: u16) -> Result<i32, String> {
        self.i32_registers
            .get(register as usize)
            .copied()
            .ok_or_else(|| format!("i32 register {register} is out of bounds"))
    }

    fn write_i32(&mut self, register: u16, value: i32) -> Result<(), String> {
        *self
            .i32_registers
            .get_mut(register as usize)
            .ok_or_else(|| format!("i32 register {register} is out of bounds"))? = value;
        Ok(())
    }

    fn write_i64(&mut self, register: u16, value: i64) -> Result<(), String> {
        *self
            .i64_registers
            .get_mut(register as usize)
            .ok_or_else(|| format!("i64 register {register} is out of bounds"))? = value;
        Ok(())
    }

    fn read_addr(&self, register: u16) -> Result<u32, String> {
        self.addr_registers
            .get(register as usize)
            .copied()
            .ok_or_else(|| format!("addr register {register} is out of bounds"))
    }

    fn write_addr(&mut self, register: u16, value: u32) -> Result<(), String> {
        *self
            .addr_registers
            .get_mut(register as usize)
            .ok_or_else(|| format!("addr register {register} is out of bounds"))? = value;
        Ok(())
    }

    fn read_bool(&self, register: u16) -> Result<bool, String> {
        self.bool_registers
            .get(register as usize)
            .copied()
            .ok_or_else(|| format!("bool register {register} is out of bounds"))
    }

    fn write_bool(&mut self, register: u16, value: bool) -> Result<(), String> {
        *self
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
        self.instruction_pointer = target;
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
