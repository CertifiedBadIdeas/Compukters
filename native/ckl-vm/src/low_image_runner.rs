use crate::low_image::{Function, Image, Instruction};
use crate::low_machine::{MachineMemory, MemoryBus};
use std::time::{Duration, Instant};

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
    pub run_invocations: u64,
    pub elapsed_nanos: u64,
    pub pause_signals: u64,
}

impl Default for LowImageVmMetrics {
    fn default() -> Self {
        Self {
            run_invocations: 0,
            elapsed_nanos: 0,
            pause_signals: 0,
        }
    }
}

struct LowFrame {
    function_index: usize,
    block_index: usize,
    return_register: Option<usize>,
    register_base: usize,
}

struct LowFunction {
    register_count: usize,
    blocks: Vec<ExecutableBlock>,
    instruction_to_block: Vec<BlockLocation>,
}

struct ExecutableBlock {
    original_start_ip: usize,
    operations: Vec<ExecutableOperation>,
    terminator: BlockTerminator,
}

struct BlockLocation {
    block_index: usize,
}

struct StaticCallBinding {
    callee_parameter: usize,
    caller_argument: usize,
}

#[derive(Clone)]
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
    I32AddImm {
        dst: usize,
        lhs: usize,
        rhs: i32,
    },
    I32Sub {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32SubImm {
        dst: usize,
        lhs: usize,
        rhs: i32,
    },
    I32Mul {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32MulImm {
        dst: usize,
        lhs: usize,
        rhs: i32,
    },
    I32MulAddImm {
        dst: usize,
        lhs: usize,
        mul: i32,
        add: i32,
    },
    I32Div {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32BitAnd {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32BitAndImm {
        dst: usize,
        lhs: usize,
        rhs: i32,
    },
    I32BitOr {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32BitOrImm {
        dst: usize,
        lhs: usize,
        rhs: i32,
    },
    I32BitXor {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32BitXorImm {
        dst: usize,
        lhs: usize,
        rhs: i32,
    },
    I32Shl {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32ShlImm {
        dst: usize,
        lhs: usize,
        rhs: i32,
    },
    I32Shr {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32ShrImm {
        dst: usize,
        lhs: usize,
        rhs: i32,
    },
    U32Shl {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    U32ShlImm {
        dst: usize,
        lhs: usize,
        rhs: i32,
    },
    U32Shr {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    U32ShrImm {
        dst: usize,
        lhs: usize,
        rhs: i32,
    },
    I32Lt {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32LtImm {
        dst: usize,
        lhs: usize,
        rhs: i32,
    },
    U32Lt {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    U32LtImm {
        dst: usize,
        lhs: usize,
        rhs: i32,
    },
    I32Eq {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    I32EqImm {
        dst: usize,
        lhs: usize,
        rhs: i32,
    },
    Load32 {
        dst: usize,
        addr: usize,
    },
    Store32 {
        addr: usize,
        src: usize,
    },
    Load8 {
        dst: usize,
        addr: usize,
    },
    Store8 {
        addr: usize,
        src: usize,
    },
    AddrAdd {
        dst: usize,
        base: usize,
        offset: usize,
    },
}

enum BlockTerminator {
    Fallthrough {
        target_block: usize,
    },
    Jump {
        target_block: usize,
    },
    JumpIfFalse {
        cond: usize,
        target_block: usize,
        fallthrough_block: usize,
    },
    CallStatic {
        return_register: Option<usize>,
        function_index: usize,
        bindings: Vec<StaticCallBinding>,
        continuation_block: usize,
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
        let entry_function_index = image.entry_function_index;
        let entry_register_count = image.functions[entry_function_index].register_count;
        let entry_frame = LowFrame::create(entry_function_index, None, 0);
        let functions: Vec<LowFunction> = image
            .functions
            .iter()
            .map(|function| LowFunction::compile(&image, function))
            .collect();
        debug_assert!(functions
            .iter()
            .all(LowFunction::instruction_mapping_is_consistent),);
        Ok((
            Self { functions },
            LowState {
                frames: vec![entry_frame],
                registers: vec![0; entry_register_count],
                instructions_since_time_check: 0,
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
            | Instruction::I32BitAnd { dst, lhs, rhs }
            | Instruction::I32BitOr { dst, lhs, rhs }
            | Instruction::I32BitXor { dst, lhs, rhs }
            | Instruction::I32Shl { dst, lhs, rhs }
            | Instruction::I32Shr { dst, lhs, rhs }
            | Instruction::U32Shl { dst, lhs, rhs }
            | Instruction::U32Shr { dst, lhs, rhs }
            | Instruction::I32Lt { dst, lhs, rhs }
            | Instruction::U32Lt { dst, lhs, rhs }
            | Instruction::I32Eq { dst, lhs, rhs } => {
                validate_register(function, instruction_index, "writes", *dst)?;
                validate_register(function, instruction_index, "reads", *lhs)?;
                validate_register(function, instruction_index, "reads", *rhs)?;
            }
            Instruction::Load32 { dst, addr } | Instruction::Load8 { dst, addr } => {
                validate_register(function, instruction_index, "writes", *dst)?;
                validate_register(function, instruction_index, "reads", *addr)?;
            }
            Instruction::Store32 { addr, src } | Instruction::Store8 { addr, src } => {
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
            block_index: 0,
            return_register,
            register_base,
        }
    }
}

impl LowFunction {
    fn compile(image: &Image, function: &Function) -> Self {
        let block_starts = block_starts(function);
        let instruction_to_block = instruction_to_block(function.instructions.len(), &block_starts);
        let function_constants = stable_i32_constants(function);
        let blocks = block_starts
            .iter()
            .enumerate()
            .map(|(block_index, start)| {
                let end = block_starts
                    .get(block_index + 1)
                    .copied()
                    .unwrap_or(function.instructions.len());
                ExecutableBlock::compile(
                    image,
                    function,
                    *start,
                    end,
                    block_index,
                    &instruction_to_block,
                    &function_constants,
                )
            })
            .collect();

        Self {
            register_count: function.register_count,
            blocks,
            instruction_to_block,
        }
    }

    fn block(&self, block_index: usize) -> &ExecutableBlock {
        // Block indices are produced from validated image control flow.
        &self.blocks[block_index]
    }

    fn instruction_mapping_is_consistent(&self) -> bool {
        self.instruction_to_block
            .iter()
            .enumerate()
            .all(|(instruction_index, location)| {
                self.blocks
                    .get(location.block_index)
                    .is_some_and(|block| instruction_index >= block.original_start_ip)
            })
    }
}

impl ExecutableBlock {
    fn compile(
        image: &Image,
        function: &Function,
        start: usize,
        end: usize,
        block_index: usize,
        instruction_to_block: &[BlockLocation],
        function_constants: &[Option<i32>],
    ) -> Self {
        let last_ip = end - 1;
        let last_instruction = &function.instructions[last_ip];
        let (operation_end, terminator) = match last_instruction {
            Instruction::Jump { target } => (
                last_ip,
                BlockTerminator::Jump {
                    target_block: instruction_to_block[*target].block_index,
                },
            ),
            Instruction::JumpIfFalse { cond, target } => (
                last_ip,
                BlockTerminator::JumpIfFalse {
                    cond: usize::from(*cond),
                    target_block: instruction_to_block[*target].block_index,
                    fallthrough_block: instruction_to_block[last_ip + 1].block_index,
                },
            ),
            Instruction::CallStatic {
                return_register,
                function_index,
                arguments,
            } => (
                last_ip,
                BlockTerminator::CallStatic {
                    return_register: return_register.map(usize::from),
                    function_index: *function_index,
                    bindings: static_call_bindings(image, *function_index, arguments),
                    continuation_block: instruction_to_block[last_ip + 1].block_index,
                },
            ),
            Instruction::ReturnI32 { src } => (
                last_ip,
                BlockTerminator::ReturnI32 {
                    src: usize::from(*src),
                },
            ),
            Instruction::ReturnI64 { src } => (
                last_ip,
                BlockTerminator::ReturnI64 {
                    src: usize::from(*src),
                },
            ),
            Instruction::ReturnAddr { src } => (
                last_ip,
                BlockTerminator::ReturnAddr {
                    src: usize::from(*src),
                },
            ),
            Instruction::ReturnBool { src } => (
                last_ip,
                BlockTerminator::ReturnBool {
                    src: usize::from(*src),
                },
            ),
            Instruction::ReturnUnit => (last_ip, BlockTerminator::ReturnUnit),
            _ => (
                end,
                BlockTerminator::Fallthrough {
                    target_block: instruction_to_block[end].block_index,
                },
            ),
        };
        let mut known_i32 = function_constants.to_vec();
        let operations = function.instructions[start..operation_end]
            .iter()
            .map(|instruction| ExecutableOperation::compile(instruction, &mut known_i32))
            .collect();
        let operations = fuse_block_operations(operations, &terminator);

        debug_assert!(instruction_to_block[start].block_index == block_index);
        Self {
            original_start_ip: start,
            operations,
            terminator,
        }
    }
}

impl ExecutableOperation {
    fn compile(instruction: &Instruction, known_i32: &mut [Option<i32>]) -> Self {
        match instruction {
            Instruction::I32Const { dst, value } => {
                let dst = usize::from(*dst);
                known_i32[dst] = Some(*value);
                Self::I32Const { dst, value: *value }
            }
            Instruction::I64Const { dst, value } => {
                let dst = usize::from(*dst);
                known_i32[dst] = None;
                Self::I64Const { dst, value: *value }
            }
            Instruction::AddrConst { dst, value } => {
                let dst = usize::from(*dst);
                known_i32[dst] = None;
                Self::AddrConst { dst, value: *value }
            }
            Instruction::I32Move { dst, src } => {
                let dst = usize::from(*dst);
                let src = usize::from(*src);
                known_i32[dst] = known_i32[src];
                Self::I32Move { dst, src }
            }
            Instruction::AddrMove { dst, src } => {
                let dst = usize::from(*dst);
                known_i32[dst] = None;
                Self::AddrMove {
                    dst,
                    src: usize::from(*src),
                }
            }
            Instruction::I32Add { dst, lhs, rhs } => compile_i32_binary_imm(
                known_i32,
                *dst,
                *lhs,
                *rhs,
                |dst, lhs, rhs| ExecutableOperation::I32Add { dst, lhs, rhs },
                |dst, lhs, rhs| ExecutableOperation::I32AddImm { dst, lhs, rhs },
            ),
            Instruction::I32Sub { dst, lhs, rhs } => compile_i32_binary_imm(
                known_i32,
                *dst,
                *lhs,
                *rhs,
                |dst, lhs, rhs| ExecutableOperation::I32Sub { dst, lhs, rhs },
                |dst, lhs, rhs| ExecutableOperation::I32SubImm { dst, lhs, rhs },
            ),
            Instruction::I32Mul { dst, lhs, rhs } => compile_i32_binary_imm(
                known_i32,
                *dst,
                *lhs,
                *rhs,
                |dst, lhs, rhs| ExecutableOperation::I32Mul { dst, lhs, rhs },
                |dst, lhs, rhs| ExecutableOperation::I32MulImm { dst, lhs, rhs },
            ),
            Instruction::I32Div { dst, lhs, rhs } => {
                let dst = usize::from(*dst);
                known_i32[dst] = None;
                Self::I32Div {
                    dst,
                    lhs: usize::from(*lhs),
                    rhs: usize::from(*rhs),
                }
            }
            Instruction::I32BitAnd { dst, lhs, rhs } => compile_i32_binary_imm(
                known_i32,
                *dst,
                *lhs,
                *rhs,
                |dst, lhs, rhs| ExecutableOperation::I32BitAnd { dst, lhs, rhs },
                |dst, lhs, rhs| ExecutableOperation::I32BitAndImm { dst, lhs, rhs },
            ),
            Instruction::I32BitOr { dst, lhs, rhs } => compile_i32_binary_imm(
                known_i32,
                *dst,
                *lhs,
                *rhs,
                |dst, lhs, rhs| ExecutableOperation::I32BitOr { dst, lhs, rhs },
                |dst, lhs, rhs| ExecutableOperation::I32BitOrImm { dst, lhs, rhs },
            ),
            Instruction::I32BitXor { dst, lhs, rhs } => compile_i32_binary_imm(
                known_i32,
                *dst,
                *lhs,
                *rhs,
                |dst, lhs, rhs| ExecutableOperation::I32BitXor { dst, lhs, rhs },
                |dst, lhs, rhs| ExecutableOperation::I32BitXorImm { dst, lhs, rhs },
            ),
            Instruction::I32Shl { dst, lhs, rhs } => compile_i32_binary_imm(
                known_i32,
                *dst,
                *lhs,
                *rhs,
                |dst, lhs, rhs| ExecutableOperation::I32Shl { dst, lhs, rhs },
                |dst, lhs, rhs| ExecutableOperation::I32ShlImm { dst, lhs, rhs },
            ),
            Instruction::I32Shr { dst, lhs, rhs } => compile_i32_binary_imm(
                known_i32,
                *dst,
                *lhs,
                *rhs,
                |dst, lhs, rhs| ExecutableOperation::I32Shr { dst, lhs, rhs },
                |dst, lhs, rhs| ExecutableOperation::I32ShrImm { dst, lhs, rhs },
            ),
            Instruction::U32Shl { dst, lhs, rhs } => compile_i32_binary_imm(
                known_i32,
                *dst,
                *lhs,
                *rhs,
                |dst, lhs, rhs| ExecutableOperation::U32Shl { dst, lhs, rhs },
                |dst, lhs, rhs| ExecutableOperation::U32ShlImm { dst, lhs, rhs },
            ),
            Instruction::U32Shr { dst, lhs, rhs } => compile_i32_binary_imm(
                known_i32,
                *dst,
                *lhs,
                *rhs,
                |dst, lhs, rhs| ExecutableOperation::U32Shr { dst, lhs, rhs },
                |dst, lhs, rhs| ExecutableOperation::U32ShrImm { dst, lhs, rhs },
            ),
            Instruction::I32Lt { dst, lhs, rhs } => compile_i32_binary_imm(
                known_i32,
                *dst,
                *lhs,
                *rhs,
                |dst, lhs, rhs| ExecutableOperation::I32Lt { dst, lhs, rhs },
                |dst, lhs, rhs| ExecutableOperation::I32LtImm { dst, lhs, rhs },
            ),
            Instruction::U32Lt { dst, lhs, rhs } => compile_i32_binary_imm(
                known_i32,
                *dst,
                *lhs,
                *rhs,
                |dst, lhs, rhs| ExecutableOperation::U32Lt { dst, lhs, rhs },
                |dst, lhs, rhs| ExecutableOperation::U32LtImm { dst, lhs, rhs },
            ),
            Instruction::I32Eq { dst, lhs, rhs } => compile_i32_binary_imm(
                known_i32,
                *dst,
                *lhs,
                *rhs,
                |dst, lhs, rhs| ExecutableOperation::I32Eq { dst, lhs, rhs },
                |dst, lhs, rhs| ExecutableOperation::I32EqImm { dst, lhs, rhs },
            ),
            Instruction::Load32 { dst, addr } => {
                let dst = usize::from(*dst);
                known_i32[dst] = None;
                Self::Load32 {
                    dst,
                    addr: usize::from(*addr),
                }
            }
            Instruction::Load8 { dst, addr } => {
                let dst = usize::from(*dst);
                known_i32[dst] = None;
                Self::Load8 {
                    dst,
                    addr: usize::from(*addr),
                }
            }
            Instruction::Store32 { addr, src } => Self::Store32 {
                addr: usize::from(*addr),
                src: usize::from(*src),
            },
            Instruction::Store8 { addr, src } => Self::Store8 {
                addr: usize::from(*addr),
                src: usize::from(*src),
            },
            Instruction::AddrAdd { dst, base, offset } => {
                let dst = usize::from(*dst);
                known_i32[dst] = None;
                Self::AddrAdd {
                    dst,
                    base: usize::from(*base),
                    offset: usize::from(*offset),
                }
            }
            Instruction::Jump { .. }
            | Instruction::JumpIfFalse { .. }
            | Instruction::CallStatic { .. }
            | Instruction::ReturnI32 { .. }
            | Instruction::ReturnI64 { .. }
            | Instruction::ReturnAddr { .. }
            | Instruction::ReturnBool { .. }
            | Instruction::ReturnUnit => unreachable!("control instructions are block terminators"),
        }
    }

    fn reads_register(&self, register: usize) -> bool {
        match self {
            ExecutableOperation::I32Const { .. }
            | ExecutableOperation::I64Const { .. }
            | ExecutableOperation::AddrConst { .. } => false,
            ExecutableOperation::I32Move { src, .. }
            | ExecutableOperation::AddrMove { src, .. }
            | ExecutableOperation::Load32 { addr: src, .. }
            | ExecutableOperation::Load8 { addr: src, .. } => *src == register,
            ExecutableOperation::I32Add { lhs, rhs, .. }
            | ExecutableOperation::I32Sub { lhs, rhs, .. }
            | ExecutableOperation::I32Mul { lhs, rhs, .. }
            | ExecutableOperation::I32Div { lhs, rhs, .. }
            | ExecutableOperation::I32BitAnd { lhs, rhs, .. }
            | ExecutableOperation::I32BitOr { lhs, rhs, .. }
            | ExecutableOperation::I32BitXor { lhs, rhs, .. }
            | ExecutableOperation::I32Shl { lhs, rhs, .. }
            | ExecutableOperation::I32Shr { lhs, rhs, .. }
            | ExecutableOperation::U32Shl { lhs, rhs, .. }
            | ExecutableOperation::U32Shr { lhs, rhs, .. }
            | ExecutableOperation::I32Lt { lhs, rhs, .. }
            | ExecutableOperation::U32Lt { lhs, rhs, .. }
            | ExecutableOperation::I32Eq { lhs, rhs, .. }
            | ExecutableOperation::Store32 {
                addr: lhs,
                src: rhs,
                ..
            }
            | ExecutableOperation::Store8 {
                addr: lhs,
                src: rhs,
                ..
            }
            | ExecutableOperation::AddrAdd {
                base: lhs,
                offset: rhs,
                ..
            } => *lhs == register || *rhs == register,
            ExecutableOperation::I32AddImm { lhs, .. }
            | ExecutableOperation::I32SubImm { lhs, .. }
            | ExecutableOperation::I32MulImm { lhs, .. }
            | ExecutableOperation::I32MulAddImm { lhs, .. }
            | ExecutableOperation::I32BitAndImm { lhs, .. }
            | ExecutableOperation::I32BitOrImm { lhs, .. }
            | ExecutableOperation::I32BitXorImm { lhs, .. }
            | ExecutableOperation::I32ShlImm { lhs, .. }
            | ExecutableOperation::I32ShrImm { lhs, .. }
            | ExecutableOperation::U32ShlImm { lhs, .. }
            | ExecutableOperation::U32ShrImm { lhs, .. }
            | ExecutableOperation::I32LtImm { lhs, .. }
            | ExecutableOperation::U32LtImm { lhs, .. }
            | ExecutableOperation::I32EqImm { lhs, .. } => *lhs == register,
        }
    }
}

impl BlockTerminator {
    fn reads_register(&self, register: usize) -> bool {
        match self {
            BlockTerminator::Fallthrough { .. } | BlockTerminator::Jump { .. } => false,
            BlockTerminator::JumpIfFalse { cond, .. } => *cond == register,
            BlockTerminator::CallStatic { bindings, .. } => bindings
                .iter()
                .any(|binding| binding.caller_argument == register),
            BlockTerminator::ReturnI32 { src }
            | BlockTerminator::ReturnI64 { src }
            | BlockTerminator::ReturnAddr { src }
            | BlockTerminator::ReturnBool { src } => *src == register,
            BlockTerminator::ReturnUnit => false,
        }
    }
}

fn compile_i32_binary_imm(
    known_i32: &mut [Option<i32>],
    dst: u16,
    lhs: u16,
    rhs: u16,
    register_operation: impl FnOnce(usize, usize, usize) -> ExecutableOperation,
    immediate_operation: impl FnOnce(usize, usize, i32) -> ExecutableOperation,
) -> ExecutableOperation {
    let dst = usize::from(dst);
    let lhs = usize::from(lhs);
    let rhs = usize::from(rhs);
    let rhs_value = known_i32[rhs];
    known_i32[dst] = None;
    match rhs_value {
        Some(value) => immediate_operation(dst, lhs, value),
        None => register_operation(dst, lhs, rhs),
    }
}

fn fuse_block_operations(
    operations: Vec<ExecutableOperation>,
    terminator: &BlockTerminator,
) -> Vec<ExecutableOperation> {
    let mut fused = Vec::with_capacity(operations.len());
    let mut index = 0;
    while index < operations.len() {
        if let Some(operation) = fuse_i32_mul_add_imm(&operations, terminator, index) {
            fused.push(operation);
            index += 2;
        } else {
            fused.push(operations[index].clone());
            index += 1;
        }
    }
    fused
}

fn fuse_i32_mul_add_imm(
    operations: &[ExecutableOperation],
    terminator: &BlockTerminator,
    index: usize,
) -> Option<ExecutableOperation> {
    let (
        ExecutableOperation::I32MulImm {
            dst: temporary,
            lhs,
            rhs: mul,
        },
        Some(ExecutableOperation::I32AddImm {
            dst,
            lhs: add_lhs,
            rhs: add,
        }),
    ) = (operations.get(index)?, operations.get(index + 1))
    else {
        return None;
    };
    if temporary != add_lhs || temporary == lhs || temporary == dst {
        return None;
    }
    if register_is_read_after(operations, terminator, index + 2, *temporary) {
        return None;
    }
    Some(ExecutableOperation::I32MulAddImm {
        dst: *dst,
        lhs: *lhs,
        mul: *mul,
        add: *add,
    })
}

fn register_is_read_after(
    operations: &[ExecutableOperation],
    terminator: &BlockTerminator,
    start: usize,
    register: usize,
) -> bool {
    operations[start..]
        .iter()
        .any(|operation| operation.reads_register(register))
        || terminator.reads_register(register)
}

struct LowState {
    frames: Vec<LowFrame>,
    registers: Vec<u64>,
    instructions_since_time_check: usize,
    metrics: LowImageVmMetrics,
}

pub struct LowImageVm {
    context: LowCpuContext,
    memory: MachineMemory,
}

pub struct LowCpuContext {
    program: LowProgram,
    state: LowState,
    slice_budget: Duration,
}

pub struct LowImageCpu<'memory> {
    context: LowCpuContext,
    memory: &'memory mut dyn MemoryBus,
}

impl LowImageVm {
    pub fn create(image: Image, slice_budget_nanos: u64) -> Result<Self, String> {
        let memory_size = usize::try_from(image.memory_size)
            .map_err(|_| "memory size does not fit usize".to_string())?;
        let memory =
            MachineMemory::from_sections(memory_size, &image.rodata, &image.data, image.bss_size)
                .map_err(|error| error.to_string())?;
        let context = Self::create_cpu_context(image, slice_budget_nanos)?;
        Ok(Self { context, memory })
    }

    pub fn create_cpu_context(
        image: Image,
        slice_budget_nanos: u64,
    ) -> Result<LowCpuContext, String> {
        let (program, state) = LowProgram::create(image)?;
        Ok(LowCpuContext {
            program,
            state,
            slice_budget: Duration::from_nanos(slice_budget_nanos.max(1)),
        })
    }

    pub fn create_cpu_with_memory<'memory>(
        image: Image,
        slice_budget_nanos: u64,
        memory: &'memory mut MachineMemory,
    ) -> Result<LowImageCpu<'memory>, String> {
        Self::create_cpu_with_bus(image, slice_budget_nanos, memory)
    }

    pub fn create_cpu_with_bus<'memory>(
        image: Image,
        slice_budget_nanos: u64,
        memory: &'memory mut dyn MemoryBus,
    ) -> Result<LowImageCpu<'memory>, String> {
        let memory_size = usize::try_from(image.memory_size)
            .map_err(|_| "memory size does not fit usize".to_string())?;
        if memory.len() < memory_size {
            return Err(format!(
                "image requires {memory_size} bytes but machine memory has {} bytes",
                memory.len(),
            ));
        }
        Ok(LowImageCpu {
            context: Self::create_cpu_context(image, slice_budget_nanos)?,
            memory,
        })
    }

    pub fn memory_bytes(&self) -> &[u8] {
        self.memory.bytes()
    }

    pub fn metrics_snapshot(&self) -> LowImageVmMetrics {
        self.context.metrics_snapshot()
    }

    pub fn run_until_signal(&mut self) -> Result<LowImageSignal, String> {
        self.context.run_until_signal(&mut self.memory)
    }
}

impl LowCpuContext {
    pub fn run_until_signal(
        &mut self,
        memory: &mut dyn MemoryBus,
    ) -> Result<LowImageSignal, String> {
        run_cpu_until_signal(&self.program, &mut self.state, memory, self.slice_budget)
    }

    pub fn metrics_snapshot(&self) -> LowImageVmMetrics {
        self.state.metrics.clone()
    }
}

impl LowImageCpu<'_> {
    pub fn run_until_signal(&mut self) -> Result<LowImageSignal, String> {
        self.context.run_until_signal(self.memory)
    }
}

const TIME_CHECK_INTERVAL: usize = 1024;

fn run_cpu_until_signal(
    program: &LowProgram,
    state: &mut LowState,
    memory: &mut dyn MemoryBus,
    slice_budget: Duration,
) -> Result<LowImageSignal, String> {
    state.metrics.run_invocations = state.metrics.run_invocations.saturating_add(1);
    let started_at = Instant::now();
    loop {
        let (function_index, block_index) = {
            let frame = state.current_frame();
            (frame.function_index, frame.block_index)
        };
        let block = program.function(function_index).block(block_index);
        for operation in &block.operations {
            state.execute_operation(memory, operation)?;
            if state.should_pause(started_at, slice_budget) {
                return Ok(LowImageSignal::Pause);
            }
        }
        if let Some(signal) = state.execute_terminator(program, &block.terminator, started_at)? {
            return Ok(signal);
        }
        if state.should_pause(started_at, slice_budget) {
            return Ok(LowImageSignal::Pause);
        }
    }
}

impl LowState {
    fn record_elapsed(&mut self, elapsed: Duration) {
        self.metrics.elapsed_nanos = self
            .metrics
            .elapsed_nanos
            .saturating_add(elapsed.as_nanos().min(u128::from(u64::MAX)) as u64);
    }

    fn should_pause(&mut self, started_at: Instant, slice_budget: Duration) -> bool {
        self.instructions_since_time_check += 1;
        if self.instructions_since_time_check < TIME_CHECK_INTERVAL {
            return false;
        }
        self.instructions_since_time_check = 0;
        let elapsed = started_at.elapsed();
        if elapsed < slice_budget {
            return false;
        }
        self.record_elapsed(elapsed);
        self.metrics.pause_signals = self.metrics.pause_signals.saturating_add(1);
        true
    }

    fn execute_operation(
        &mut self,
        memory: &mut dyn MemoryBus,
        operation: &ExecutableOperation,
    ) -> Result<(), String> {
        match operation {
            ExecutableOperation::I32Const { dst, value } => self.write_i32(*dst, *value),
            ExecutableOperation::I64Const { dst, value } => self.write_i64(*dst, *value),
            ExecutableOperation::AddrConst { dst, value } => self.write_addr(*dst, *value),
            ExecutableOperation::I32Move { dst, src } => {
                let value = self.read_i32(*src);
                self.write_i32(*dst, value);
            }
            ExecutableOperation::AddrMove { dst, src } => {
                let value = self.read_addr(*src);
                self.write_addr(*dst, value);
            }
            ExecutableOperation::I32Add { dst, lhs, rhs } => {
                let value = self.read_i32(*lhs).wrapping_add(self.read_i32(*rhs));
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32AddImm { dst, lhs, rhs } => {
                let value = self.read_i32(*lhs).wrapping_add(*rhs);
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32Sub { dst, lhs, rhs } => {
                let value = self.read_i32(*lhs).wrapping_sub(self.read_i32(*rhs));
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32SubImm { dst, lhs, rhs } => {
                let value = self.read_i32(*lhs).wrapping_sub(*rhs);
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32Mul { dst, lhs, rhs } => {
                let value = self.read_i32(*lhs).wrapping_mul(self.read_i32(*rhs));
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32MulImm { dst, lhs, rhs } => {
                let value = self.read_i32(*lhs).wrapping_mul(*rhs);
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32MulAddImm { dst, lhs, mul, add } => {
                let value = self.read_i32(*lhs).wrapping_mul(*mul).wrapping_add(*add);
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32Div { dst, lhs, rhs } => {
                let rhs = self.read_i32(*rhs);
                if rhs == 0 {
                    return Err("division by zero".to_string());
                }
                let value = self.read_i32(*lhs).wrapping_div(rhs);
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32BitAnd { dst, lhs, rhs } => {
                let value = self.read_i32(*lhs) & self.read_i32(*rhs);
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32BitAndImm { dst, lhs, rhs } => {
                let value = self.read_i32(*lhs) & *rhs;
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32BitOr { dst, lhs, rhs } => {
                let value = self.read_i32(*lhs) | self.read_i32(*rhs);
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32BitOrImm { dst, lhs, rhs } => {
                let value = self.read_i32(*lhs) | *rhs;
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32BitXor { dst, lhs, rhs } => {
                let value = self.read_i32(*lhs) ^ self.read_i32(*rhs);
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32BitXorImm { dst, lhs, rhs } => {
                let value = self.read_i32(*lhs) ^ *rhs;
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32Shl { dst, lhs, rhs } => {
                let value = i32_shl_unbounded(self.read_i32(*lhs), self.read_i32(*rhs));
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32ShlImm { dst, lhs, rhs } => {
                let value = i32_shl_unbounded(self.read_i32(*lhs), *rhs);
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32Shr { dst, lhs, rhs } => {
                let value = i32_shr_unbounded(self.read_i32(*lhs), self.read_i32(*rhs));
                self.write_i32(*dst, value);
            }
            ExecutableOperation::I32ShrImm { dst, lhs, rhs } => {
                let value = i32_shr_unbounded(self.read_i32(*lhs), *rhs);
                self.write_i32(*dst, value);
            }
            ExecutableOperation::U32Shl { dst, lhs, rhs } => {
                let value = u32_shl_unbounded(self.read_i32(*lhs) as u32, self.read_i32(*rhs));
                self.write_i32(*dst, value as i32);
            }
            ExecutableOperation::U32ShlImm { dst, lhs, rhs } => {
                let value = u32_shl_unbounded(self.read_i32(*lhs) as u32, *rhs);
                self.write_i32(*dst, value as i32);
            }
            ExecutableOperation::U32Shr { dst, lhs, rhs } => {
                let value = u32_shr_unbounded(self.read_i32(*lhs) as u32, self.read_i32(*rhs));
                self.write_i32(*dst, value as i32);
            }
            ExecutableOperation::U32ShrImm { dst, lhs, rhs } => {
                let value = u32_shr_unbounded(self.read_i32(*lhs) as u32, *rhs);
                self.write_i32(*dst, value as i32);
            }
            ExecutableOperation::I32Lt { dst, lhs, rhs } => {
                let value = self.read_i32(*lhs) < self.read_i32(*rhs);
                self.write_bool(*dst, value);
            }
            ExecutableOperation::I32LtImm { dst, lhs, rhs } => {
                let value = self.read_i32(*lhs) < *rhs;
                self.write_bool(*dst, value);
            }
            ExecutableOperation::U32Lt { dst, lhs, rhs } => {
                let value = (self.read_i32(*lhs) as u32) < (self.read_i32(*rhs) as u32);
                self.write_bool(*dst, value);
            }
            ExecutableOperation::U32LtImm { dst, lhs, rhs } => {
                let value = (self.read_i32(*lhs) as u32) < (*rhs as u32);
                self.write_bool(*dst, value);
            }
            ExecutableOperation::I32Eq { dst, lhs, rhs } => {
                let value = self.read_i32(*lhs) == self.read_i32(*rhs);
                self.write_bool(*dst, value);
            }
            ExecutableOperation::I32EqImm { dst, lhs, rhs } => {
                let value = self.read_i32(*lhs) == *rhs;
                self.write_bool(*dst, value);
            }
            ExecutableOperation::Load32 { dst, addr } => {
                let address = self.read_addr(*addr);
                let value = memory
                    .load_i32(address)
                    .map_err(|error| error.to_string())?;
                self.write_i32(*dst, value);
            }
            ExecutableOperation::Store32 { addr, src } => {
                let address = self.read_addr(*addr);
                memory
                    .store_i32(address, self.read_i32(*src))
                    .map_err(|error| error.to_string())?;
            }
            ExecutableOperation::Load8 { dst, addr } => {
                let address = self.read_addr(*addr);
                let value = memory.load_u8(address).map_err(|error| error.to_string())?;
                self.write_i32(*dst, i32::from(value));
            }
            ExecutableOperation::Store8 { addr, src } => {
                let address = self.read_addr(*addr);
                memory
                    .store_u8(address, self.read_i32(*src).to_le_bytes()[0])
                    .map_err(|error| error.to_string())?;
            }
            ExecutableOperation::AddrAdd { dst, base, offset } => {
                let base = self.read_addr(*base);
                let offset = self.read_i32(*offset);
                let value = if offset >= 0 {
                    base.wrapping_add(offset as u32)
                } else {
                    base.wrapping_sub(offset.wrapping_abs() as u32)
                };
                self.write_addr(*dst, value);
            }
        }
        Ok(())
    }

    fn execute_terminator(
        &mut self,
        program: &LowProgram,
        terminator: &BlockTerminator,
        started_at: Instant,
    ) -> Result<Option<LowImageSignal>, String> {
        match terminator {
            BlockTerminator::Fallthrough { target_block } => self.jump_block(*target_block),
            BlockTerminator::Jump { target_block } => self.jump_block(*target_block),
            BlockTerminator::JumpIfFalse {
                cond,
                target_block,
                fallthrough_block,
            } => {
                if self.read_bool(*cond) {
                    self.jump_block(*fallthrough_block);
                } else {
                    self.jump_block(*target_block);
                }
            }
            BlockTerminator::CallStatic {
                return_register,
                function_index,
                bindings,
                continuation_block,
            } => self.call_static(
                program,
                *return_register,
                *function_index,
                bindings,
                *continuation_block,
            ),
            BlockTerminator::ReturnI32 { src } => {
                if let Some(signal) = self.return_i32(*src)? {
                    self.record_elapsed(started_at.elapsed());
                    return Ok(Some(signal));
                }
            }
            BlockTerminator::ReturnI64 { src } => {
                if let Some(signal) = self.return_i64(*src)? {
                    self.record_elapsed(started_at.elapsed());
                    return Ok(Some(signal));
                }
            }
            BlockTerminator::ReturnAddr { src } => {
                if let Some(signal) = self.return_addr(*src)? {
                    self.record_elapsed(started_at.elapsed());
                    return Ok(Some(signal));
                }
            }
            BlockTerminator::ReturnBool { src } => {
                if let Some(signal) = self.return_bool(*src)? {
                    self.record_elapsed(started_at.elapsed());
                    return Ok(Some(signal));
                }
            }
            BlockTerminator::ReturnUnit => {
                if let Some(signal) = self.return_unit()? {
                    self.record_elapsed(started_at.elapsed());
                    return Ok(Some(signal));
                }
            }
        }
        Ok(None)
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
        bindings: &[StaticCallBinding],
        continuation_block: usize,
    ) {
        self.current_frame_mut().block_index = continuation_block;
        let caller_register_base = self.current_frame().register_base;
        let function = program.function(function_index);
        let register_base = self.registers.len();
        self.registers
            .resize(register_base + function.register_count, 0);
        let frame = LowFrame::create(function_index, return_register, register_base);
        for binding in bindings {
            let value = self.registers[caller_register_base + binding.caller_argument];
            self.registers[frame.register_base + binding.callee_parameter] = value;
        }
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

    fn jump_block(&mut self, target_block: usize) {
        self.current_frame_mut().block_index = target_block;
    }
}

fn i32_shl_unbounded(value: i32, amount: i32) -> i32 {
    if !(0..32).contains(&amount) {
        return 0;
    }
    value.wrapping_shl(amount as u32)
}

fn i32_shr_unbounded(value: i32, amount: i32) -> i32 {
    if !(0..32).contains(&amount) {
        return if value < 0 { -1 } else { 0 };
    }
    value.wrapping_shr(amount as u32)
}

fn u32_shl_unbounded(value: u32, amount: i32) -> u32 {
    if !(0..32).contains(&amount) {
        return 0;
    }
    value.wrapping_shl(amount as u32)
}

fn u32_shr_unbounded(value: u32, amount: i32) -> u32 {
    if !(0..32).contains(&amount) {
        return 0;
    }
    value.wrapping_shr(amount as u32)
}

fn block_starts(function: &Function) -> Vec<usize> {
    let mut leaders = vec![false; function.instructions.len()];
    leaders[0] = true;
    for (instruction_index, instruction) in function.instructions.iter().enumerate() {
        match instruction {
            Instruction::Jump { target } => {
                leaders[*target] = true;
            }
            Instruction::JumpIfFalse { target, .. } => {
                leaders[*target] = true;
                if instruction_index + 1 < leaders.len() {
                    leaders[instruction_index + 1] = true;
                }
            }
            Instruction::CallStatic { .. } => {
                if instruction_index + 1 < leaders.len() {
                    leaders[instruction_index + 1] = true;
                }
            }
            Instruction::ReturnI32 { .. }
            | Instruction::ReturnI64 { .. }
            | Instruction::ReturnAddr { .. }
            | Instruction::ReturnBool { .. }
            | Instruction::ReturnUnit => {
                if instruction_index + 1 < leaders.len() {
                    leaders[instruction_index + 1] = true;
                }
            }
            _ => {}
        }
    }
    leaders
        .into_iter()
        .enumerate()
        .filter_map(|(index, is_leader)| is_leader.then_some(index))
        .collect()
}

fn instruction_to_block(instruction_count: usize, block_starts: &[usize]) -> Vec<BlockLocation> {
    let mut locations = Vec::with_capacity(instruction_count);
    locations.resize_with(instruction_count, || BlockLocation { block_index: 0 });
    for (block_index, start) in block_starts.iter().copied().enumerate() {
        let end = block_starts
            .get(block_index + 1)
            .copied()
            .unwrap_or(instruction_count);
        for instruction_index in start..end {
            locations[instruction_index] = BlockLocation { block_index };
        }
    }
    locations
}

fn static_call_bindings(
    image: &Image,
    function_index: usize,
    arguments: &[u16],
) -> Vec<StaticCallBinding> {
    image.functions[function_index]
        .parameters
        .iter()
        .copied()
        .map(usize::from)
        .zip(arguments.iter().copied().map(usize::from))
        .map(|(callee_parameter, caller_argument)| StaticCallBinding {
            callee_parameter,
            caller_argument,
        })
        .collect()
}

fn stable_i32_constants(function: &Function) -> Vec<Option<i32>> {
    let mut candidates = vec![I32ConstantCandidate::default(); function.register_count];
    for parameter in &function.parameters {
        candidates[usize::from(*parameter)].invalidate();
    }
    for instruction in &function.instructions {
        for register in instruction_read_registers(instruction) {
            candidates[register].read();
        }
        match instruction_write_register(instruction) {
            Some((register, Some(value))) => candidates[register].write_const(value),
            Some((register, None)) => candidates[register].invalidate(),
            None => {}
        }
    }
    candidates
        .into_iter()
        .map(|candidate| {
            if candidate.valid {
                candidate.value
            } else {
                None
            }
        })
        .collect()
}

#[derive(Clone, Copy)]
struct I32ConstantCandidate {
    value: Option<i32>,
    valid: bool,
}

impl Default for I32ConstantCandidate {
    fn default() -> Self {
        Self {
            value: None,
            valid: true,
        }
    }
}

impl I32ConstantCandidate {
    fn read(&mut self) {
        if self.value.is_none() {
            self.valid = false;
        }
    }

    fn write_const(&mut self, value: i32) {
        if !self.valid {
            return;
        }
        match self.value {
            Some(existing) if existing != value => self.valid = false,
            Some(_) => {}
            None => self.value = Some(value),
        }
    }

    fn invalidate(&mut self) {
        self.valid = false;
    }
}

fn instruction_read_registers(instruction: &Instruction) -> Vec<usize> {
    match instruction {
        Instruction::I32Const { .. }
        | Instruction::I64Const { .. }
        | Instruction::AddrConst { .. }
        | Instruction::Jump { .. }
        | Instruction::ReturnUnit => Vec::new(),
        Instruction::I32Move { src, .. }
        | Instruction::AddrMove { src, .. }
        | Instruction::Load32 { addr: src, .. }
        | Instruction::Load8 { addr: src, .. }
        | Instruction::ReturnI32 { src }
        | Instruction::ReturnI64 { src }
        | Instruction::ReturnAddr { src }
        | Instruction::ReturnBool { src } => vec![usize::from(*src)],
        Instruction::I32Add { lhs, rhs, .. }
        | Instruction::I32Sub { lhs, rhs, .. }
        | Instruction::I32Mul { lhs, rhs, .. }
        | Instruction::I32Div { lhs, rhs, .. }
        | Instruction::I32BitAnd { lhs, rhs, .. }
        | Instruction::I32BitOr { lhs, rhs, .. }
        | Instruction::I32BitXor { lhs, rhs, .. }
        | Instruction::I32Shl { lhs, rhs, .. }
        | Instruction::I32Shr { lhs, rhs, .. }
        | Instruction::U32Shl { lhs, rhs, .. }
        | Instruction::U32Shr { lhs, rhs, .. }
        | Instruction::I32Lt { lhs, rhs, .. }
        | Instruction::U32Lt { lhs, rhs, .. }
        | Instruction::I32Eq { lhs, rhs, .. }
        | Instruction::Store32 {
            addr: lhs,
            src: rhs,
            ..
        }
        | Instruction::Store8 {
            addr: lhs,
            src: rhs,
            ..
        }
        | Instruction::AddrAdd {
            base: lhs,
            offset: rhs,
            ..
        } => vec![usize::from(*lhs), usize::from(*rhs)],
        Instruction::JumpIfFalse { cond, .. } => vec![usize::from(*cond)],
        Instruction::CallStatic { arguments, .. } => {
            arguments.iter().copied().map(usize::from).collect()
        }
    }
}

fn instruction_write_register(instruction: &Instruction) -> Option<(usize, Option<i32>)> {
    match instruction {
        Instruction::I32Const { dst, value } => Some((usize::from(*dst), Some(*value))),
        Instruction::I64Const { dst, .. }
        | Instruction::AddrConst { dst, .. }
        | Instruction::I32Move { dst, .. }
        | Instruction::AddrMove { dst, .. }
        | Instruction::I32Add { dst, .. }
        | Instruction::I32Sub { dst, .. }
        | Instruction::I32Mul { dst, .. }
        | Instruction::I32Div { dst, .. }
        | Instruction::I32BitAnd { dst, .. }
        | Instruction::I32BitOr { dst, .. }
        | Instruction::I32BitXor { dst, .. }
        | Instruction::I32Shl { dst, .. }
        | Instruction::I32Shr { dst, .. }
        | Instruction::U32Shl { dst, .. }
        | Instruction::U32Shr { dst, .. }
        | Instruction::I32Lt { dst, .. }
        | Instruction::U32Lt { dst, .. }
        | Instruction::I32Eq { dst, .. }
        | Instruction::Load32 { dst, .. }
        | Instruction::Load8 { dst, .. }
        | Instruction::AddrAdd { dst, .. } => Some((usize::from(*dst), None)),
        Instruction::CallStatic {
            return_register: Some(return_register),
            ..
        } => Some((usize::from(*return_register), None)),
        Instruction::Store32 { .. }
        | Instruction::Store8 { .. }
        | Instruction::Jump { .. }
        | Instruction::JumpIfFalse { .. }
        | Instruction::CallStatic {
            return_register: None,
            ..
        }
        | Instruction::ReturnI32 { .. }
        | Instruction::ReturnI64 { .. }
        | Instruction::ReturnAddr { .. }
        | Instruction::ReturnBool { .. }
        | Instruction::ReturnUnit => None,
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lowering_splits_loop_into_basic_blocks() {
        let image = Image {
            language_version: "ckl-low-1".to_string(),
            memory_size: 1024,
            rodata: Vec::new(),
            data: Vec::new(),
            bss_size: 0,
            entry_function_index: 0,
            functions: vec![Function {
                name: "main".to_string(),
                register_count: 4,
                parameters: Vec::new(),
                instructions: vec![
                    Instruction::I32Const { dst: 0, value: 0 },
                    Instruction::I32Const { dst: 1, value: 3 },
                    Instruction::I32Const { dst: 2, value: 1 },
                    Instruction::I32Lt {
                        dst: 3,
                        lhs: 0,
                        rhs: 1,
                    },
                    Instruction::JumpIfFalse { cond: 3, target: 7 },
                    Instruction::I32Add {
                        dst: 0,
                        lhs: 0,
                        rhs: 2,
                    },
                    Instruction::Jump { target: 3 },
                    Instruction::ReturnI32 { src: 0 },
                ],
            }],
        };

        let (program, _) = LowProgram::create(image).unwrap();
        let function = program.function(0);

        assert_eq!(function.blocks.len(), 4);
        assert_eq!(function.blocks[0].original_start_ip, 0);
        assert_eq!(function.blocks[1].original_start_ip, 3);
        assert_eq!(function.blocks[2].original_start_ip, 5);
        assert_eq!(function.blocks[3].original_start_ip, 7);
        assert_eq!(function.instruction_to_block[3].block_index, 1);
        assert_eq!(function.instruction_to_block[7].block_index, 3);
    }

    #[test]
    fn low_image_runner_executes_byte_memory_operations() {
        let image = Image {
            language_version: "ckl-low-1".to_string(),
            memory_size: 64,
            rodata: Vec::new(),
            data: Vec::new(),
            bss_size: 0,
            entry_function_index: 0,
            functions: vec![Function {
                name: "main".to_string(),
                register_count: 4,
                parameters: Vec::new(),
                instructions: vec![
                    Instruction::AddrConst { dst: 0, value: 8 },
                    Instruction::I32Const {
                        dst: 1,
                        value: 0x12,
                    },
                    Instruction::Store8 { addr: 0, src: 1 },
                    Instruction::Load8 { dst: 2, addr: 0 },
                    Instruction::ReturnI32 { src: 2 },
                ],
            }],
        };

        let mut vm = LowImageVm::create(image, 1_000_000).unwrap();

        assert_eq!(
            vm.run_until_signal().unwrap(),
            LowImageSignal::HaltI32(0x12)
        );
    }

    #[test]
    fn lowering_fuses_i32_binary_operations_with_known_rhs_constants() {
        let image = Image {
            language_version: "ckl-low-1".to_string(),
            memory_size: 1024,
            rodata: Vec::new(),
            data: Vec::new(),
            bss_size: 0,
            entry_function_index: 0,
            functions: vec![Function {
                name: "main".to_string(),
                register_count: 5,
                parameters: Vec::new(),
                instructions: vec![
                    Instruction::I32Const { dst: 0, value: 10 },
                    Instruction::I32Const { dst: 1, value: 3 },
                    Instruction::I32Add {
                        dst: 2,
                        lhs: 0,
                        rhs: 1,
                    },
                    Instruction::I32Mul {
                        dst: 3,
                        lhs: 2,
                        rhs: 1,
                    },
                    Instruction::I32Lt {
                        dst: 4,
                        lhs: 3,
                        rhs: 1,
                    },
                    Instruction::ReturnBool { src: 4 },
                ],
            }],
        };

        let (program, _) = LowProgram::create(image).unwrap();
        let operations = &program.function(0).blocks[0].operations;

        assert!(matches!(
            operations[2],
            ExecutableOperation::I32AddImm {
                dst: 2,
                lhs: 0,
                rhs: 3,
            },
        ));
        assert!(matches!(
            operations[3],
            ExecutableOperation::I32MulImm {
                dst: 3,
                lhs: 2,
                rhs: 3,
            },
        ));
        assert!(matches!(
            operations[4],
            ExecutableOperation::I32LtImm {
                dst: 4,
                lhs: 3,
                rhs: 3,
            },
        ));
    }

    #[test]
    fn fused_i32_immediate_operations_preserve_runtime_semantics() {
        let image = Image {
            language_version: "ckl-low-1".to_string(),
            memory_size: 1024,
            rodata: Vec::new(),
            data: Vec::new(),
            bss_size: 0,
            entry_function_index: 0,
            functions: vec![Function {
                name: "main".to_string(),
                register_count: 8,
                parameters: Vec::new(),
                instructions: vec![
                    Instruction::I32Const { dst: 0, value: 10 },
                    Instruction::I32Const { dst: 1, value: 3 },
                    Instruction::I32Sub {
                        dst: 2,
                        lhs: 0,
                        rhs: 1,
                    },
                    Instruction::I32BitXor {
                        dst: 3,
                        lhs: 2,
                        rhs: 1,
                    },
                    Instruction::I32Shl {
                        dst: 4,
                        lhs: 3,
                        rhs: 1,
                    },
                    Instruction::I32Shr {
                        dst: 5,
                        lhs: 4,
                        rhs: 1,
                    },
                    Instruction::I32Add {
                        dst: 6,
                        lhs: 5,
                        rhs: 1,
                    },
                    Instruction::I32Mul {
                        dst: 7,
                        lhs: 6,
                        rhs: 1,
                    },
                    Instruction::ReturnI32 { src: 7 },
                ],
            }],
        };
        let mut vm = LowImageVm::create(image, 128).unwrap();

        assert_eq!(vm.run_until_signal().unwrap(), LowImageSignal::HaltI32(21));
    }

    #[test]
    fn lowering_fuses_constants_loaded_before_loop_blocks() {
        let image = Image {
            language_version: "ckl-low-1".to_string(),
            memory_size: 1024,
            rodata: Vec::new(),
            data: Vec::new(),
            bss_size: 0,
            entry_function_index: 0,
            functions: vec![Function {
                name: "main".to_string(),
                register_count: 5,
                parameters: Vec::new(),
                instructions: vec![
                    Instruction::I32Const { dst: 0, value: 0 },
                    Instruction::I32Const { dst: 1, value: 2 },
                    Instruction::I32Const { dst: 2, value: 1 },
                    Instruction::I32Lt {
                        dst: 3,
                        lhs: 0,
                        rhs: 1,
                    },
                    Instruction::JumpIfFalse { cond: 3, target: 7 },
                    Instruction::I32Add {
                        dst: 0,
                        lhs: 0,
                        rhs: 2,
                    },
                    Instruction::Jump { target: 3 },
                    Instruction::ReturnI32 { src: 0 },
                ],
            }],
        };

        let (program, _) = LowProgram::create(image).unwrap();
        let loop_body = &program.function(0).blocks[2];

        assert!(matches!(
            loop_body.operations[0],
            ExecutableOperation::I32AddImm {
                dst: 0,
                lhs: 0,
                rhs: 1,
            },
        ));
    }

    #[test]
    fn lowering_fuses_dead_temporary_mul_add_immediate_pairs() {
        let image = Image {
            language_version: "ckl-low-1".to_string(),
            memory_size: 1024,
            rodata: Vec::new(),
            data: Vec::new(),
            bss_size: 0,
            entry_function_index: 0,
            functions: vec![Function {
                name: "main".to_string(),
                register_count: 5,
                parameters: Vec::new(),
                instructions: vec![
                    Instruction::I32Const { dst: 0, value: 7 },
                    Instruction::I32Const { dst: 1, value: 5 },
                    Instruction::I32Const { dst: 2, value: 11 },
                    Instruction::I32Mul {
                        dst: 3,
                        lhs: 0,
                        rhs: 1,
                    },
                    Instruction::I32Add {
                        dst: 4,
                        lhs: 3,
                        rhs: 2,
                    },
                    Instruction::ReturnI32 { src: 4 },
                ],
            }],
        };

        let (program, _) = LowProgram::create(image).unwrap();
        let operations = &program.function(0).blocks[0].operations;

        assert!(matches!(
            operations[3],
            ExecutableOperation::I32MulAddImm {
                dst: 4,
                lhs: 0,
                mul: 5,
                add: 11,
            },
        ));
        assert_eq!(operations.len(), 4);
    }
}
