use crate::low_image::{Function, Image, Instruction};

pub const LOW_OPCODE_COUNT: usize = low_opcode::RETURN_BOOL + 1;

pub mod low_opcode {
    pub const I32_CONST: usize = 1;
    pub const I64_CONST: usize = 2;
    pub const ADDR_CONST: usize = 3;
    pub const I32_MOVE: usize = 4;
    pub const ADDR_MOVE: usize = 5;
    pub const I32_ADD: usize = 6;
    pub const I32_SUB: usize = 7;
    pub const I32_MUL: usize = 8;
    pub const I32_DIV: usize = 9;
    pub const I32_BIT_XOR: usize = 10;
    pub const I32_SHL: usize = 11;
    pub const I32_SHR: usize = 12;
    pub const I32_LT: usize = 13;
    pub const LOAD32: usize = 14;
    pub const STORE32: usize = 15;
    pub const ADDR_ADD: usize = 16;
    pub const JUMP: usize = 17;
    pub const JUMP_IF_FALSE: usize = 18;
    pub const CALL_STATIC: usize = 19;
    pub const RETURN_I32: usize = 20;
    pub const RETURN_UNIT: usize = 21;
    pub const RETURN_I64: usize = 22;
    pub const RETURN_ADDR: usize = 23;
    pub const RETURN_BOOL: usize = 24;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LowImageSignal {
    HaltUnit,
    HaltI32(i32),
    HaltI64(i64),
    HaltAddr(u32),
    HaltBool(bool),
    Pause,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LowImageVmMetrics {
    pub executed_instructions: u64,
    pub function_calls: u64,
    pub function_returns: u64,
    pub pause_signals: u64,
    pub memory_loads: u64,
    pub memory_stores: u64,
    pub opcode_counts: [u64; LOW_OPCODE_COUNT],
}

impl Default for LowImageVmMetrics {
    fn default() -> Self {
        Self {
            executed_instructions: 0,
            function_calls: 0,
            function_returns: 0,
            pause_signals: 0,
            memory_loads: 0,
            memory_stores: 0,
            opcode_counts: [0; LOW_OPCODE_COUNT],
        }
    }
}

struct LowFrame {
    function_index: usize,
    instruction_pointer: usize,
    return_register: Option<usize>,
    register_base: usize,
}

struct LowFunction {
    register_count: usize,
    parameters: Vec<usize>,
    instructions: Vec<ExecutableInstruction>,
}

struct ExecutableInstruction {
    opcode: usize,
    operation: ExecutableOperation,
}

enum ExecutableOperation {
    I32Const {
        dst: usize,
        value: i32,
    },
    I64Const {
        dst: usize,
        value: i64,
    },
    AddrConst {
        dst: usize,
        value: u32,
    },
    I32Move {
        dst: usize,
        src: usize,
    },
    AddrMove {
        dst: usize,
        src: usize,
    },
    I32Add {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32Sub {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32Mul {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32Div {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32BitXor {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32Shl {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32Shr {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32Lt {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    Load32 {
        dst: usize,
        addr: usize,
    },
    Store32 {
        addr: usize,
        src: usize,
    },
    AddrAdd {
        dst: usize,
        base: usize,
        offset: usize,
    },
    Jump {
        target: usize,
    },
    JumpIfFalse {
        cond: usize,
        target: usize,
    },
    CallStatic {
        return_register: Option<usize>,
        function_index: usize,
        arguments: Vec<usize>,
    },
    ReturnI32 {
        src: usize,
    },
    ReturnI64 {
        src: usize,
    },
    ReturnAddr {
        src: usize,
    },
    ReturnBool {
        src: usize,
    },
    ReturnUnit,
}

struct LowProgram {
    functions: Vec<LowFunction>,
}

impl LowProgram {
    fn create(image: Image) -> Result<(Self, LowState), String> {
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
        Self::validate(&image)?;
        let mut memory = vec![0_u8; memory_size];
        memory[..image.rodata.len()].copy_from_slice(&image.rodata);
        let data_start = image.rodata.len();
        memory[data_start..data_start + image.data.len()].copy_from_slice(&image.data);
        let entry_function_index = image.entry_function_index;
        let entry_register_count = image.functions[entry_function_index].register_count;
        let entry_frame = LowFrame::create(entry_function_index, None, 0);
        let functions = image.functions.iter().map(LowFunction::compile).collect();
        Ok((
            Self { functions },
            LowState {
                frames: vec![entry_frame],
                registers: vec![0; entry_register_count],
                memory,
                instructions_since_pause: 0,
                metrics: LowImageVmMetrics::default(),
            },
        ))
    }

    fn function(&self, function_index: usize) -> &LowFunction {
        // Function indices are image-validated before execution.
        &self.functions[function_index]
    }

    fn validate(image: &Image) -> Result<(), String> {
        for (function_index, function) in image.functions.iter().enumerate() {
            Self::validate_function(image, function_index, function)?;
        }
        Ok(())
    }

    fn validate_function(
        image: &Image,
        _function_index: usize,
        function: &Function,
    ) -> Result<(), String> {
        if function.instructions.is_empty() {
            return Err(format!("function {} has no instructions", function.name));
        }
        for (parameter_index, register) in function.parameters.iter().copied().enumerate() {
            if register as usize >= function.register_count {
                return Err(format!(
                    "function {} parameter {parameter_index} register {register} outside register count {}",
                    function.name,
                    function.register_count,
                ));
            }
        }
        let last_instruction = function
            .instructions
            .last()
            .expect("empty functions return before this point");
        if !instruction_terminates_linear_flow(last_instruction) {
            return Err(format!(
                "function {} must end with Jump or Return* instruction",
                function.name,
            ));
        }
        for (instruction_index, instruction) in function.instructions.iter().enumerate() {
            Self::validate_instruction(image, function, instruction_index, instruction)?;
        }
        Ok(())
    }

    fn validate_instruction(
        image: &Image,
        function: &Function,
        instruction_index: usize,
        instruction: &Instruction,
    ) -> Result<(), String> {
        match instruction {
            Instruction::I32Const { dst, .. }
            | Instruction::I64Const { dst, .. }
            | Instruction::AddrConst { dst, .. } => {
                validate_register(function, instruction_index, "writes", *dst)?;
            }
            Instruction::I32Move { dst, src } | Instruction::AddrMove { dst, src } => {
                validate_register(function, instruction_index, "writes", *dst)?;
                validate_register(function, instruction_index, "reads", *src)?;
            }
            Instruction::I32Add { dst, lhs, rhs }
            | Instruction::I32Sub { dst, lhs, rhs }
            | Instruction::I32Mul { dst, lhs, rhs }
            | Instruction::I32Div { dst, lhs, rhs }
            | Instruction::I32BitXor { dst, lhs, rhs }
            | Instruction::I32Shl { dst, lhs, rhs }
            | Instruction::I32Shr { dst, lhs, rhs }
            | Instruction::I32Lt { dst, lhs, rhs } => {
                validate_register(function, instruction_index, "writes", *dst)?;
                validate_register(function, instruction_index, "reads", *lhs)?;
                validate_register(function, instruction_index, "reads", *rhs)?;
            }
            Instruction::Load32 { dst, addr } => {
                validate_register(function, instruction_index, "writes", *dst)?;
                validate_register(function, instruction_index, "reads", *addr)?;
            }
            Instruction::Store32 { addr, src } => {
                validate_register(function, instruction_index, "reads", *addr)?;
                validate_register(function, instruction_index, "reads", *src)?;
            }
            Instruction::AddrAdd { dst, base, offset } => {
                validate_register(function, instruction_index, "writes", *dst)?;
                validate_register(function, instruction_index, "reads", *base)?;
                validate_register(function, instruction_index, "reads", *offset)?;
            }
            Instruction::Jump { target } => {
                validate_jump_target(function, instruction_index, *target)?;
            }
            Instruction::JumpIfFalse { cond, target } => {
                validate_register(function, instruction_index, "reads", *cond)?;
                validate_jump_target(function, instruction_index, *target)?;
            }
            Instruction::CallStatic {
                return_register,
                function_index,
                arguments,
            } => {
                if let Some(return_register) = return_register {
                    validate_register(function, instruction_index, "return", *return_register)?;
                }
                for argument in arguments {
                    validate_register(function, instruction_index, "argument", *argument)?;
                }
                let callee = image.functions.get(*function_index).ok_or_else(|| {
                    format!(
                        "function {} instruction {instruction_index} calls function {function_index} outside function count {}",
                        function.name,
                        image.functions.len(),
                    )
                })?;
                if arguments.len() != callee.parameters.len() {
                    return Err(format!(
                        "function {} instruction {instruction_index} calls function {} with {} arguments but callee expects {}",
                        function.name,
                        callee.name,
                        arguments.len(),
                        callee.parameters.len(),
                    ));
                }
            }
            Instruction::ReturnI32 { src }
            | Instruction::ReturnI64 { src }
            | Instruction::ReturnAddr { src }
            | Instruction::ReturnBool { src } => {
                validate_register(function, instruction_index, "reads", *src)?;
            }
            Instruction::ReturnUnit => {}
        }
        Ok(())
    }
}

impl LowFrame {
    fn create(function_index: usize, return_register: Option<usize>, register_base: usize) -> Self {
        Self {
            function_index,
            instruction_pointer: 0,
            return_register,
            register_base,
        }
    }
}

impl LowFunction {
    fn compile(function: &Function) -> Self {
        Self {
            register_count: function.register_count,
            parameters: function
                .parameters
                .iter()
                .copied()
                .map(usize::from)
                .collect(),
            instructions: function
                .instructions
                .iter()
                .map(ExecutableInstruction::compile)
                .collect(),
        }
    }
}

impl ExecutableInstruction {
    fn compile(instruction: &Instruction) -> Self {
        Self {
            opcode: opcode_index(instruction),
            operation: ExecutableOperation::compile(instruction),
        }
    }
}

impl ExecutableOperation {
    fn compile(instruction: &Instruction) -> Self {
        match instruction {
            Instruction::I32Const { dst, value } => Self::I32Const {
                dst: usize::from(*dst),
                value: *value,
            },
            Instruction::I64Const { dst, value } => Self::I64Const {
                dst: usize::from(*dst),
                value: *value,
            },
            Instruction::AddrConst { dst, value } => Self::AddrConst {
                dst: usize::from(*dst),
                value: *value,
            },
            Instruction::I32Move { dst, src } => Self::I32Move {
                dst: usize::from(*dst),
                src: usize::from(*src),
            },
            Instruction::AddrMove { dst, src } => Self::AddrMove {
                dst: usize::from(*dst),
                src: usize::from(*src),
            },
            Instruction::I32Add { dst, lhs, rhs } => Self::I32Add {
                dst: usize::from(*dst),
                lhs: usize::from(*lhs),
                rhs: usize::from(*rhs),
            },
            Instruction::I32Sub { dst, lhs, rhs } => Self::I32Sub {
                dst: usize::from(*dst),
                lhs: usize::from(*lhs),
                rhs: usize::from(*rhs),
            },
            Instruction::I32Mul { dst, lhs, rhs } => Self::I32Mul {
                dst: usize::from(*dst),
                lhs: usize::from(*lhs),
                rhs: usize::from(*rhs),
            },
            Instruction::I32Div { dst, lhs, rhs } => Self::I32Div {
                dst: usize::from(*dst),
                lhs: usize::from(*lhs),
                rhs: usize::from(*rhs),
            },
            Instruction::I32BitXor { dst, lhs, rhs } => Self::I32BitXor {
                dst: usize::from(*dst),
                lhs: usize::from(*lhs),
                rhs: usize::from(*rhs),
            },
            Instruction::I32Shl { dst, lhs, rhs } => Self::I32Shl {
                dst: usize::from(*dst),
                lhs: usize::from(*lhs),
                rhs: usize::from(*rhs),
            },
            Instruction::I32Shr { dst, lhs, rhs } => Self::I32Shr {
                dst: usize::from(*dst),
                lhs: usize::from(*lhs),
                rhs: usize::from(*rhs),
            },
            Instruction::I32Lt { dst, lhs, rhs } => Self::I32Lt {
                dst: usize::from(*dst),
                lhs: usize::from(*lhs),
                rhs: usize::from(*rhs),
            },
            Instruction::Load32 { dst, addr } => Self::Load32 {
                dst: usize::from(*dst),
                addr: usize::from(*addr),
            },
            Instruction::Store32 { addr, src } => Self::Store32 {
                addr: usize::from(*addr),
                src: usize::from(*src),
            },
            Instruction::AddrAdd { dst, base, offset } => Self::AddrAdd {
                dst: usize::from(*dst),
                base: usize::from(*base),
                offset: usize::from(*offset),
            },
            Instruction::Jump { target } => Self::Jump { target: *target },
            Instruction::JumpIfFalse { cond, target } => Self::JumpIfFalse {
                cond: usize::from(*cond),
                target: *target,
            },
            Instruction::CallStatic {
                return_register,
                function_index,
                arguments,
            } => Self::CallStatic {
                return_register: return_register.map(usize::from),
                function_index: *function_index,
                arguments: arguments.iter().copied().map(usize::from).collect(),
            },
            Instruction::ReturnI32 { src } => Self::ReturnI32 {
                src: usize::from(*src),
            },
            Instruction::ReturnI64 { src } => Self::ReturnI64 {
                src: usize::from(*src),
            },
            Instruction::ReturnAddr { src } => Self::ReturnAddr {
                src: usize::from(*src),
            },
            Instruction::ReturnBool { src } => Self::ReturnBool {
                src: usize::from(*src),
            },
            Instruction::ReturnUnit => Self::ReturnUnit,
        }
    }
}

struct LowState {
    frames: Vec<LowFrame>,
    registers: Vec<u64>,
    memory: Vec<u8>,
    instructions_since_pause: usize,
    metrics: LowImageVmMetrics,
}

pub struct LowImageVm {
    program: LowProgram,
    state: LowState,
    instruction_budget: usize,
}

impl LowImageVm {
    pub fn create(image: Image, instruction_budget: usize) -> Result<Self, String> {
        let (program, state) = LowProgram::create(image)?;
        Ok(Self {
            program,
            state,
            instruction_budget: instruction_budget.max(1),
        })
    }

    pub fn memory_bytes(&self) -> &[u8] {
        &self.state.memory
    }

    pub fn metrics_snapshot(&self) -> LowImageVmMetrics {
        self.state.metrics.clone()
    }

    pub fn run_until_signal(&mut self) -> Result<LowImageSignal, String> {
        loop {
            let (function_index, instruction_pointer) = {
                let frame = self.state.current_frame_mut();
                let instruction_pointer = frame.instruction_pointer;
                frame.instruction_pointer += 1;
                (frame.function_index, instruction_pointer)
            };
            let function = self.program.function(function_index);
            // Instruction pointers and jump targets are image-validated before execution.
            let instruction = &function.instructions[instruction_pointer];
            self.state.instructions_since_pause += 1;
            self.state.record_opcode(instruction.opcode);
            match &instruction.operation {
                ExecutableOperation::I32Const { dst, value } => self.state.write_i32(*dst, *value),
                ExecutableOperation::I64Const { dst, value } => self.state.write_i64(*dst, *value),
                ExecutableOperation::AddrConst { dst, value } => {
                    self.state.write_addr(*dst, *value)
                }
                ExecutableOperation::I32Move { dst, src } => {
                    let value = self.state.read_i32(*src);
                    self.state.write_i32(*dst, value);
                }
                ExecutableOperation::AddrMove { dst, src } => {
                    let value = self.state.read_addr(*src);
                    self.state.write_addr(*dst, value);
                }
                ExecutableOperation::I32Add { dst, lhs, rhs } => {
                    let value = self
                        .state
                        .read_i32(*lhs)
                        .wrapping_add(self.state.read_i32(*rhs));
                    self.state.write_i32(*dst, value);
                }
                ExecutableOperation::I32Sub { dst, lhs, rhs } => {
                    let value = self
                        .state
                        .read_i32(*lhs)
                        .wrapping_sub(self.state.read_i32(*rhs));
                    self.state.write_i32(*dst, value);
                }
                ExecutableOperation::I32Mul { dst, lhs, rhs } => {
                    let value = self
                        .state
                        .read_i32(*lhs)
                        .wrapping_mul(self.state.read_i32(*rhs));
                    self.state.write_i32(*dst, value);
                }
                ExecutableOperation::I32Div { dst, lhs, rhs } => {
                    let rhs = self.state.read_i32(*rhs);
                    if rhs == 0 {
                        return Err("division by zero".to_string());
                    }
                    let value = self.state.read_i32(*lhs).wrapping_div(rhs);
                    self.state.write_i32(*dst, value);
                }
                ExecutableOperation::I32BitXor { dst, lhs, rhs } => {
                    let value = self.state.read_i32(*lhs) ^ self.state.read_i32(*rhs);
                    self.state.write_i32(*dst, value);
                }
                ExecutableOperation::I32Shl { dst, lhs, rhs } => {
                    let value = self
                        .state
                        .read_i32(*lhs)
                        .wrapping_shl(self.state.read_i32(*rhs) as u32);
                    self.state.write_i32(*dst, value);
                }
                ExecutableOperation::I32Shr { dst, lhs, rhs } => {
                    let value = self
                        .state
                        .read_i32(*lhs)
                        .wrapping_shr(self.state.read_i32(*rhs) as u32);
                    self.state.write_i32(*dst, value);
                }
                ExecutableOperation::I32Lt { dst, lhs, rhs } => {
                    let value = self.state.read_i32(*lhs) < self.state.read_i32(*rhs);
                    self.state.write_bool(*dst, value);
                }
                ExecutableOperation::Load32 { dst, addr } => {
                    let address = self.state.read_addr(*addr);
                    let bytes = self.state.memory_range(address, 4)?;
                    let mut raw = [0_u8; 4];
                    raw.copy_from_slice(bytes);
                    self.state.write_i32(*dst, i32::from_le_bytes(raw));
                    self.state.metrics.memory_loads =
                        self.state.metrics.memory_loads.saturating_add(1);
                }
                ExecutableOperation::Store32 { addr, src } => {
                    let address = self.state.read_addr(*addr);
                    let value = self.state.read_i32(*src).to_le_bytes();
                    self.state
                        .memory_range_mut(address, 4)?
                        .copy_from_slice(&value);
                    self.state.metrics.memory_stores =
                        self.state.metrics.memory_stores.saturating_add(1);
                }
                ExecutableOperation::AddrAdd { dst, base, offset } => {
                    let base = self.state.read_addr(*base);
                    let offset = self.state.read_i32(*offset);
                    let value = if offset >= 0 {
                        base.wrapping_add(offset as u32)
                    } else {
                        base.wrapping_sub(offset.wrapping_abs() as u32)
                    };
                    self.state.write_addr(*dst, value);
                }
                ExecutableOperation::Jump { target } => self.state.jump(*target),
                ExecutableOperation::JumpIfFalse { cond, target } => {
                    if !self.state.read_bool(*cond) {
                        self.state.jump(*target);
                    }
                }
                ExecutableOperation::ReturnI32 { src } => {
                    if let Some(signal) = self.state.return_i32(*src)? {
                        return Ok(signal);
                    }
                }
                ExecutableOperation::ReturnI64 { src } => {
                    if let Some(signal) = self.state.return_i64(*src)? {
                        return Ok(signal);
                    }
                }
                ExecutableOperation::ReturnAddr { src } => {
                    if let Some(signal) = self.state.return_addr(*src)? {
                        return Ok(signal);
                    }
                }
                ExecutableOperation::ReturnBool { src } => {
                    if let Some(signal) = self.state.return_bool(*src)? {
                        return Ok(signal);
                    }
                }
                ExecutableOperation::ReturnUnit => {
                    if let Some(signal) = self.state.return_unit()? {
                        return Ok(signal);
                    }
                }
                ExecutableOperation::CallStatic {
                    return_register,
                    function_index,
                    arguments,
                } => self.state.call_static(
                    &self.program,
                    *return_register,
                    *function_index,
                    arguments,
                ),
            }
            if self.state.instructions_since_pause >= self.instruction_budget {
                self.state.instructions_since_pause = 0;
                self.state.metrics.pause_signals =
                    self.state.metrics.pause_signals.saturating_add(1);
                return Ok(LowImageSignal::Pause);
            }
        }
    }
}

impl LowState {
    fn record_opcode(&mut self, opcode: usize) {
        self.metrics.executed_instructions = self.metrics.executed_instructions.saturating_add(1);
        self.metrics.opcode_counts[opcode] = self.metrics.opcode_counts[opcode].saturating_add(1);
    }

    fn current_frame(&self) -> &LowFrame {
        self.frames.last().expect("low VM call stack is empty")
    }

    fn current_frame_mut(&mut self) -> &mut LowFrame {
        self.frames.last_mut().expect("low VM call stack is empty")
    }

    fn return_i32(&mut self, register: usize) -> Result<Option<LowImageSignal>, String> {
        let value = self.read_i32(register);
        self.return_raw(
            register,
            value as u32 as u64,
            LowImageSignal::HaltI32(value),
        )
    }

    fn return_i64(&mut self, register: usize) -> Result<Option<LowImageSignal>, String> {
        let value = self.read_i64(register);
        self.return_raw(register, value as u64, LowImageSignal::HaltI64(value))
    }

    fn return_addr(&mut self, register: usize) -> Result<Option<LowImageSignal>, String> {
        let value = self.read_addr(register);
        self.return_raw(register, value as u64, LowImageSignal::HaltAddr(value))
    }

    fn return_bool(&mut self, register: usize) -> Result<Option<LowImageSignal>, String> {
        let value = self.read_bool(register);
        self.return_raw(register, u64::from(value), LowImageSignal::HaltBool(value))
    }

    fn return_raw(
        &mut self,
        register: usize,
        value: u64,
        root_signal: LowImageSignal,
    ) -> Result<Option<LowImageSignal>, String> {
        self.metrics.function_returns = self.metrics.function_returns.saturating_add(1);
        if self.frames.len() == 1 {
            return Ok(Some(root_signal));
        }
        let frame = self.frames.pop().expect("low VM call stack is empty");
        self.registers.truncate(frame.register_base);
        if let Some(return_register) = frame.return_register {
            self.write_raw(return_register, value);
        } else {
            return Err(format!(
                "callee returned r{register} but caller did not provide return register",
            ));
        }
        Ok(None)
    }

    fn return_unit(&mut self) -> Result<Option<LowImageSignal>, String> {
        self.metrics.function_returns = self.metrics.function_returns.saturating_add(1);
        if self.frames.len() == 1 {
            return Ok(Some(LowImageSignal::HaltUnit));
        }
        let frame = self.frames.pop().expect("low VM call stack is empty");
        self.registers.truncate(frame.register_base);
        if let Some(return_register) = frame.return_register {
            return Err(format!(
                "callee returned unit but caller expected r{return_register}",
            ));
        }
        Ok(None)
    }

    fn call_static(
        &mut self,
        program: &LowProgram,
        return_register: Option<usize>,
        function_index: usize,
        arguments: &[usize],
    ) {
        let caller_register_base = self.current_frame().register_base;
        let function = program.function(function_index);
        let register_base = self.registers.len();
        self.registers
            .resize(register_base + function.register_count, 0);
        let frame = LowFrame::create(function_index, return_register, register_base);
        for (parameter, argument) in function.parameters.iter().copied().zip(arguments) {
            let value = self.registers[caller_register_base + *argument];
            self.registers[frame.register_base + parameter as usize] = value;
        }
        self.metrics.function_calls = self.metrics.function_calls.saturating_add(1);
        self.frames.push(frame);
    }

    fn read_i32(&self, register: usize) -> i32 {
        self.read_raw(register) as u32 as i32
    }

    fn write_i32(&mut self, register: usize, value: i32) {
        self.write_raw(register, value as u32 as u64)
    }

    fn read_i64(&self, register: usize) -> i64 {
        self.read_raw(register) as i64
    }

    fn write_i64(&mut self, register: usize, value: i64) {
        self.write_raw(register, value as u64)
    }

    fn read_addr(&self, register: usize) -> u32 {
        self.read_raw(register) as u32
    }

    fn write_addr(&mut self, register: usize, value: u32) {
        self.write_raw(register, value as u64)
    }

    fn read_bool(&self, register: usize) -> bool {
        self.read_raw(register) != 0
    }

    fn write_bool(&mut self, register: usize, value: bool) {
        self.write_raw(register, u64::from(value))
    }

    fn read_raw(&self, register: usize) -> u64 {
        // Register indices are image-validated before execution; keep the hot path direct.
        let frame = self.frames.last().expect("low VM call stack is empty");
        self.registers[frame.register_base + register]
    }

    fn write_raw(&mut self, register: usize, value: u64) {
        // Register indices are image-validated before execution; keep the hot path direct.
        let frame = self.frames.last().expect("low VM call stack is empty");
        let index = frame.register_base + register;
        self.registers[index] = value;
    }

    fn jump(&mut self, target: usize) {
        self.current_frame_mut().instruction_pointer = target;
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

fn opcode_index(instruction: &Instruction) -> usize {
    match instruction {
        Instruction::I32Const { .. } => low_opcode::I32_CONST,
        Instruction::I64Const { .. } => low_opcode::I64_CONST,
        Instruction::AddrConst { .. } => low_opcode::ADDR_CONST,
        Instruction::I32Move { .. } => low_opcode::I32_MOVE,
        Instruction::AddrMove { .. } => low_opcode::ADDR_MOVE,
        Instruction::I32Add { .. } => low_opcode::I32_ADD,
        Instruction::I32Sub { .. } => low_opcode::I32_SUB,
        Instruction::I32Mul { .. } => low_opcode::I32_MUL,
        Instruction::I32Div { .. } => low_opcode::I32_DIV,
        Instruction::I32BitXor { .. } => low_opcode::I32_BIT_XOR,
        Instruction::I32Shl { .. } => low_opcode::I32_SHL,
        Instruction::I32Shr { .. } => low_opcode::I32_SHR,
        Instruction::I32Lt { .. } => low_opcode::I32_LT,
        Instruction::Load32 { .. } => low_opcode::LOAD32,
        Instruction::Store32 { .. } => low_opcode::STORE32,
        Instruction::AddrAdd { .. } => low_opcode::ADDR_ADD,
        Instruction::Jump { .. } => low_opcode::JUMP,
        Instruction::JumpIfFalse { .. } => low_opcode::JUMP_IF_FALSE,
        Instruction::CallStatic { .. } => low_opcode::CALL_STATIC,
        Instruction::ReturnI32 { .. } => low_opcode::RETURN_I32,
        Instruction::ReturnUnit => low_opcode::RETURN_UNIT,
        Instruction::ReturnI64 { .. } => low_opcode::RETURN_I64,
        Instruction::ReturnAddr { .. } => low_opcode::RETURN_ADDR,
        Instruction::ReturnBool { .. } => low_opcode::RETURN_BOOL,
    }
}

fn validate_register(
    function: &Function,
    instruction_index: usize,
    role: &str,
    register: u16,
) -> Result<(), String> {
    if register as usize >= function.register_count {
        return Err(format!(
            "function {} instruction {instruction_index} {role} register {register} outside register count {}",
            function.name,
            function.register_count,
        ));
    }
    Ok(())
}

fn validate_jump_target(
    function: &Function,
    instruction_index: usize,
    target: usize,
) -> Result<(), String> {
    if target >= function.instructions.len() {
        return Err(format!(
            "function {} instruction {instruction_index} jump target {target} is outside instruction count {}",
            function.name,
            function.instructions.len(),
        ));
    }
    Ok(())
}

fn instruction_terminates_linear_flow(instruction: &Instruction) -> bool {
    matches!(
        instruction,
        Instruction::Jump { .. }
            | Instruction::ReturnI32 { .. }
            | Instruction::ReturnI64 { .. }
            | Instruction::ReturnAddr { .. }
            | Instruction::ReturnBool { .. }
            | Instruction::ReturnUnit
    )
}
