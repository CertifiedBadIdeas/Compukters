use crate::low_machine::{MemoryBus, MemoryFault};
use crate::mmu::{
    MmuAccess, MmuAddressSpace, MmuAddressSpaceId, MmuAddressSpaces, MmuFault, MmuPrivilege,
};
use std::fmt::{Display, Formatter};

pub const K16_CSR_TRAP_VECTOR: u32 = 1;
pub const K16_CSR_TRAP_CAUSE: u32 = 2;
pub const K16_CSR_TRAP_PC: u32 = 3;
pub const K16_CSR_TRAP_VALUE: u32 = 4;
pub const K16_CSR_INTERRUPT_ENABLE: u32 = 5;
pub const K16_CSR_INTERRUPT_MASK: u32 = 6;
pub const K16_CSR_INTERRUPT_PENDING: u32 = 7;
pub const K16_CSR_TRAP_ARG0: u32 = 8;
pub const K16_CSR_TRAP_ARG1: u32 = 9;
pub const K16_CSR_TRAP_ARG2: u32 = 10;
pub const K16_CSR_TRAP_FRAME_INDEX: u32 = 11;
pub const K16_CSR_TRAP_FRAME_REGISTER: u32 = 12;
pub const K16_CSR_TRAP_RESUME_PC: u32 = 13;
pub const K16_CSR_TRAP_STACK_POINTER: u32 = 14;
pub const K16_CSR_TRAP_INTERRUPT_ENABLE: u32 = 15;

pub const K16_INTERRUPT_SOURCE_TIMER0: u32 = 1;
pub const K16_INTERRUPT_SOURCE_KEYBOARD0: u32 = 2;

/// K16 ABI register reserved as the stack pointer. The stack lives in guest RAM,
/// uses 4-byte slots, and grows toward lower addresses.
pub const K16_STACK_POINTER_REGISTER: u8 = 15;

pub const K16_TRAP_CAUSE_ILLEGAL_INSTRUCTION: u32 = 1;
pub const K16_TRAP_CAUSE_INSTRUCTION_FETCH_FAULT: u32 = 2;
pub const K16_TRAP_CAUSE_LOAD_FAULT: u32 = 3;
pub const K16_TRAP_CAUSE_STORE_FAULT: u32 = 4;
pub const K16_TRAP_CAUSE_EXPLICIT_TRAP: u32 = 5;
pub const K16_TRAP_CAUSE_TIMER0_INTERRUPT: u32 = 0x8000_0001;
pub const K16_TRAP_CAUSE_KEYBOARD0_INTERRUPT: u32 = 0x8000_0002;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum K16Signal {
    Halt,
    Wait,
    Yield,
    StepLimitExceeded,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct K16Metrics {
    pub steps: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum K16AddressMode {
    Physical,
    Translated { address_space: MmuAddressSpaceId },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum K16PrivilegeMode {
    Kernel,
    User,
}

impl K16PrivilegeMode {
    fn mmu_privilege(self) -> MmuPrivilege {
        match self {
            Self::Kernel => MmuPrivilege::Kernel,
            Self::User => MmuPrivilege::User,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum K16CpuSnapshotState {
    Running,
    Halted,
    Trapped,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct K16CpuSnapshot {
    pub pc: u32,
    pub registers: [u32; 16],
    pub trap_registers: [u32; 16],
    pub trap_vector: u32,
    pub trap_cause: u32,
    pub trap_pc: u32,
    pub trap_value: u32,
    pub trap_arg0: u32,
    pub trap_arg1: u32,
    pub trap_arg2: u32,
    pub trap_stack_pointer: u32,
    pub interrupt_enable: bool,
    pub interrupt_mask: u32,
    pub interrupt_pending: u32,
    pub timer0_interrupt_value: u32,
    pub state: K16CpuSnapshotState,
    pub metrics_steps: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct K16Trap {
    cause: u32,
    pc: u32,
    value: u32,
    message: String,
}

impl K16Trap {
    fn new(message: impl Into<String>) -> Self {
        Self {
            cause: 0,
            pc: 0,
            value: 0,
            message: message.into(),
        }
    }

    fn exception(cause: u32, pc: u32, value: u32, message: impl Into<String>) -> Self {
        Self {
            cause,
            pc,
            value,
            message: message.into(),
        }
    }

    pub fn cause(&self) -> u32 {
        self.cause
    }

    pub fn pc(&self) -> u32 {
        self.pc
    }

    pub fn value(&self) -> u32 {
        self.value
    }
}

impl Display for K16Trap {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for K16Trap {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodeResult {
    pub instruction: DecodedInstruction,
    pub next_pc: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DecodedInstruction {
    Nop,
    Halt,
    Wait,
    Const4 { dst: usize, value: u32 },
    Const32 { dst: usize, value: u32 },
    Add { dst: usize, lhs: usize, rhs: usize },
    Sub { dst: usize, lhs: usize, rhs: usize },
    Mul { dst: usize, lhs: usize, rhs: usize },
    MulHU { dst: usize, lhs: usize, rhs: usize },
    MulHS { dst: usize, lhs: usize, rhs: usize },
    And { dst: usize, lhs: usize, rhs: usize },
    Or { dst: usize, lhs: usize, rhs: usize },
    Xor { dst: usize, lhs: usize, rhs: usize },
    Shl { dst: usize, lhs: usize, rhs: usize },
    Shr { dst: usize, lhs: usize, rhs: usize },
    Sar { dst: usize, lhs: usize, rhs: usize },
    Eq { dst: usize, lhs: usize, rhs: usize },
    Ne { dst: usize, lhs: usize, rhs: usize },
    Ltu { dst: usize, lhs: usize, rhs: usize },
    LtS { dst: usize, lhs: usize, rhs: usize },
    TestBits { dst: usize, src: usize, mask: u32 },
    Load8 { dst: usize, addr: usize },
    Load16 { dst: usize, addr: usize },
    Load32 { dst: usize, addr: usize },
    Store8 { addr: usize, src: usize },
    Store16 { addr: usize, src: usize },
    Store32 { addr: usize, src: usize },
    BranchIfZero { src: usize, target_pc: u32 },
    BranchIfNonZero { src: usize, target_pc: u32 },
    Jump { target: usize },
    Call { target: usize },
    Ret,
    Iret,
    Syscall { number: usize },
    ReadCsr { dst: usize, csr: u32 },
    WriteCsr { csr: u32, src: usize },
}

pub trait InstructionDecoder {
    fn decode(&mut self, bus: &mut dyn MemoryBus, pc: u32) -> Result<DecodeResult, K16Trap>;
}

#[derive(Debug, Clone, Default)]
pub struct K16Decoder;

impl K16Decoder {
    pub fn new() -> Self {
        Self
    }
}

impl InstructionDecoder for K16Decoder {
    fn decode(&mut self, bus: &mut dyn MemoryBus, pc: u32) -> Result<DecodeResult, K16Trap> {
        // Decoder owns the binary instruction format. It may fetch extension
        // words, but it does not mutate CPU registers or device state.
        let word = bus
            .load_u16(pc)
            .map_err(|error| instruction_fetch_fault(pc, error))?;
        let next_pc = checked_pc_add(pc, 2)?;
        let op = (word >> 12) & 0x0f;
        let a = usize::from((word >> 8) & 0x0f);
        let b = usize::from((word >> 4) & 0x0f);
        let c = usize::from(word & 0x0f);
        let instruction = match op {
            0x0 => match word & 0x0fff {
                0x000 => DecodedInstruction::Nop,
                0x001 => DecodedInstruction::Halt,
                0x004 => DecodedInstruction::Iret,
                0x006 => DecodedInstruction::Wait,
                _ if b == 0 && c == 0x5 => DecodedInstruction::Syscall { number: a },
                _ if c == 0x2 => DecodedInstruction::ReadCsr {
                    dst: a,
                    csr: b as u32,
                },
                _ if c == 0x3 => DecodedInstruction::WriteCsr {
                    csr: a as u32,
                    src: b,
                },
                _ => return Err(illegal_instruction(pc, word)),
            },
            0x1 => {
                if b != 0 {
                    return Err(illegal_instruction(pc, word));
                }
                DecodedInstruction::Const4 {
                    dst: a,
                    value: c as u32,
                }
            }
            0x2 => {
                if b != 0 {
                    return Err(illegal_instruction(pc, word));
                }
                let extension_addr = checked_pc_add(pc, 2)?;
                let extension = bus
                    .load_u16(extension_addr)
                    .map_err(|error| instruction_fetch_fault(extension_addr, error))?;
                if extension & 0xff00 != 0 {
                    return Err(illegal_instruction(pc, word));
                }
                let lhs = usize::from((extension >> 4) & 0x0f);
                let rhs = usize::from(extension & 0x0f);
                let instruction = match c {
                    0x0 => DecodedInstruction::Add { dst: a, lhs, rhs },
                    0x1 => DecodedInstruction::Sub { dst: a, lhs, rhs },
                    0x2 => DecodedInstruction::And { dst: a, lhs, rhs },
                    0x3 => DecodedInstruction::Or { dst: a, lhs, rhs },
                    0x4 => DecodedInstruction::Xor { dst: a, lhs, rhs },
                    0x5 => DecodedInstruction::Shl { dst: a, lhs, rhs },
                    0x6 => DecodedInstruction::Shr { dst: a, lhs, rhs },
                    0x7 => DecodedInstruction::Sar { dst: a, lhs, rhs },
                    0x8 => DecodedInstruction::Eq { dst: a, lhs, rhs },
                    0x9 => DecodedInstruction::Ne { dst: a, lhs, rhs },
                    0xa => DecodedInstruction::Ltu { dst: a, lhs, rhs },
                    0xb => DecodedInstruction::LtS { dst: a, lhs, rhs },
                    0xc => DecodedInstruction::Mul { dst: a, lhs, rhs },
                    0xd => DecodedInstruction::MulHU { dst: a, lhs, rhs },
                    0xe => DecodedInstruction::MulHS { dst: a, lhs, rhs },
                    _ => return Err(illegal_instruction(pc, word)),
                };
                return Ok(DecodeResult {
                    instruction,
                    next_pc: checked_pc_add(pc, 4)?,
                });
            }
            0x3 => {
                let extension_addr = checked_pc_add(pc, 2)?;
                let extension = bus
                    .load_u16(extension_addr)
                    .map_err(|error| instruction_fetch_fault(extension_addr, error))?;
                let instruction = match c {
                    0x1 => DecodedInstruction::TestBits {
                        dst: a,
                        src: b,
                        mask: u32::from(extension),
                    },
                    _ => return Err(illegal_instruction(pc, word)),
                };
                return Ok(DecodeResult {
                    instruction,
                    next_pc: checked_pc_add(pc, 4)?,
                });
            }
            0x4 => match c {
                0x0 => DecodedInstruction::Load8 { dst: a, addr: b },
                0x1 => DecodedInstruction::Load16 { dst: a, addr: b },
                0x2 => DecodedInstruction::Load32 { dst: a, addr: b },
                _ => return Err(illegal_instruction(pc, word)),
            },
            0x5 => match c {
                0x0 => DecodedInstruction::Store8 { addr: a, src: b },
                0x1 => DecodedInstruction::Store16 { addr: a, src: b },
                0x2 => DecodedInstruction::Store32 { addr: a, src: b },
                _ => return Err(illegal_instruction(pc, word)),
            },
            0x6 => {
                let target_pc = relative_branch_target(next_pc, c)?;
                match b {
                    0x0 => DecodedInstruction::BranchIfZero { src: a, target_pc },
                    0x1 => DecodedInstruction::BranchIfNonZero { src: a, target_pc },
                    _ => return Err(illegal_instruction(pc, word)),
                }
            }
            0x7 => {
                if b != 0 || c != 0 {
                    return Err(illegal_instruction(pc, word));
                }
                DecodedInstruction::Jump { target: a }
            }
            0x8 => {
                if b != 0 || c != 0 {
                    return Err(illegal_instruction(pc, word));
                }
                DecodedInstruction::Call { target: a }
            }
            0x9 => {
                if a != 0 || b != 0 || c != 0 {
                    return Err(illegal_instruction(pc, word));
                }
                DecodedInstruction::Ret
            }
            0xe => {
                if b != 0 || c != 1 {
                    return Err(illegal_instruction(pc, word));
                }
                let low_addr = checked_pc_add(pc, 2)?;
                let high_addr = checked_pc_add(pc, 4)?;
                let low = u32::from(
                    bus.load_u16(low_addr)
                        .map_err(|error| instruction_fetch_fault(low_addr, error))?,
                );
                let high = u32::from(
                    bus.load_u16(high_addr)
                        .map_err(|error| instruction_fetch_fault(high_addr, error))?,
                );
                return Ok(DecodeResult {
                    instruction: DecodedInstruction::Const32 {
                        dst: a,
                        value: (high << 16) | low,
                    },
                    next_pc: checked_pc_add(pc, 6)?,
                });
            }
            _ => return Err(illegal_instruction(pc, word)),
        };
        Ok(DecodeResult {
            instruction,
            next_pc,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum K16State {
    Running,
    Halted,
    Trapped(String),
}

#[derive(Debug, Clone)]
pub struct K16Cpu {
    pc: u32,
    registers: [u32; 16],
    trap_registers: [u32; 16],
    trap_vector: u32,
    trap_cause: u32,
    trap_pc: u32,
    trap_value: u32,
    trap_arg0: u32,
    trap_arg1: u32,
    trap_arg2: u32,
    trap_frame_index: usize,
    trap_stack_pointer: u32,
    trap_interrupt_enable: bool,
    interrupt_enable: bool,
    interrupt_delivery_inhibited_steps: u8,
    interrupt_mask: u32,
    interrupt_pending: u32,
    timer0_interrupt_value: u32,
    address_mode: K16AddressMode,
    privilege_mode: K16PrivilegeMode,
    state: K16State,
    metrics: K16Metrics,
}

impl K16Cpu {
    pub fn new(entry_pc: u32) -> Self {
        Self {
            pc: entry_pc,
            registers: [0; 16],
            trap_registers: [0; 16],
            trap_vector: 0,
            trap_cause: 0,
            trap_pc: 0,
            trap_value: 0,
            trap_arg0: 0,
            trap_arg1: 0,
            trap_arg2: 0,
            trap_frame_index: 0,
            trap_stack_pointer: 0,
            trap_interrupt_enable: false,
            interrupt_enable: false,
            interrupt_delivery_inhibited_steps: 0,
            interrupt_mask: 0,
            interrupt_pending: 0,
            timer0_interrupt_value: 0,
            address_mode: K16AddressMode::Physical,
            privilege_mode: K16PrivilegeMode::Kernel,
            state: K16State::Running,
            metrics: K16Metrics { steps: 0 },
        }
    }

    pub fn new_with_stack(entry_pc: u32, stack_top: u32) -> Self {
        let mut cpu = Self::new(entry_pc);
        cpu.registers[usize::from(K16_STACK_POINTER_REGISTER)] = stack_top;
        cpu
    }

    pub fn pc(&self) -> u32 {
        self.pc
    }

    pub fn register(&self, index: usize) -> u32 {
        self.registers[index]
    }

    pub fn metrics(&self) -> &K16Metrics {
        &self.metrics
    }

    pub fn address_mode(&self) -> K16AddressMode {
        self.address_mode
    }

    pub fn set_address_mode(&mut self, address_mode: K16AddressMode) {
        self.address_mode = address_mode;
    }

    pub fn privilege_mode(&self) -> K16PrivilegeMode {
        self.privilege_mode
    }

    pub fn set_privilege_mode(&mut self, privilege_mode: K16PrivilegeMode) {
        self.privilege_mode = privilege_mode;
    }

    pub fn enter_user_address_space(
        &mut self,
        address_space: MmuAddressSpaceId,
        entry_pc: u32,
        stack_pointer: u32,
    ) {
        self.address_mode = K16AddressMode::Translated { address_space };
        self.privilege_mode = K16PrivilegeMode::User;
        self.pc = entry_pc;
        self.registers[usize::from(K16_STACK_POINTER_REGISTER)] = stack_pointer;
    }

    pub fn snapshot(&self) -> K16CpuSnapshot {
        K16CpuSnapshot {
            pc: self.pc,
            registers: self.registers,
            trap_registers: self.trap_registers,
            trap_vector: self.trap_vector,
            trap_cause: self.trap_cause,
            trap_pc: self.trap_pc,
            trap_value: self.trap_value,
            trap_arg0: self.trap_arg0,
            trap_arg1: self.trap_arg1,
            trap_arg2: self.trap_arg2,
            trap_stack_pointer: self.trap_stack_pointer,
            interrupt_enable: self.interrupt_enable,
            interrupt_mask: self.interrupt_mask,
            interrupt_pending: self.interrupt_pending,
            timer0_interrupt_value: self.timer0_interrupt_value,
            state: match &self.state {
                K16State::Running => K16CpuSnapshotState::Running,
                K16State::Halted => K16CpuSnapshotState::Halted,
                K16State::Trapped(_) => K16CpuSnapshotState::Trapped,
            },
            metrics_steps: self.metrics.steps,
        }
    }

    pub fn from_snapshot(snapshot: K16CpuSnapshot) -> Self {
        Self {
            pc: snapshot.pc,
            registers: snapshot.registers,
            trap_registers: snapshot.trap_registers,
            trap_vector: snapshot.trap_vector,
            trap_cause: snapshot.trap_cause,
            trap_pc: snapshot.trap_pc,
            trap_value: snapshot.trap_value,
            trap_arg0: snapshot.trap_arg0,
            trap_arg1: snapshot.trap_arg1,
            trap_arg2: snapshot.trap_arg2,
            trap_frame_index: 0,
            trap_stack_pointer: snapshot.trap_stack_pointer,
            trap_interrupt_enable: false,
            interrupt_enable: snapshot.interrupt_enable,
            interrupt_delivery_inhibited_steps: 0,
            interrupt_mask: snapshot.interrupt_mask,
            interrupt_pending: snapshot.interrupt_pending,
            timer0_interrupt_value: snapshot.timer0_interrupt_value,
            address_mode: K16AddressMode::Physical,
            privilege_mode: K16PrivilegeMode::Kernel,
            state: match snapshot.state {
                K16CpuSnapshotState::Running => K16State::Running,
                K16CpuSnapshotState::Halted => K16State::Halted,
                K16CpuSnapshotState::Trapped => {
                    K16State::Trapped("restored trapped K16 CPU".to_string())
                }
            },
            metrics: K16Metrics {
                steps: snapshot.metrics_steps,
            },
        }
    }

    pub fn trap(&self) -> Option<&str> {
        match &self.state {
            K16State::Trapped(message) => Some(message.as_str()),
            _ => None,
        }
    }

    pub fn csr(&self, csr: u32) -> Option<u32> {
        match csr {
            K16_CSR_TRAP_VECTOR => Some(self.trap_vector),
            K16_CSR_TRAP_CAUSE => Some(self.trap_cause),
            K16_CSR_TRAP_PC => Some(self.trap_pc),
            K16_CSR_TRAP_VALUE => Some(self.trap_value),
            K16_CSR_TRAP_ARG0 => Some(self.trap_arg0),
            K16_CSR_TRAP_ARG1 => Some(self.trap_arg1),
            K16_CSR_TRAP_ARG2 => Some(self.trap_arg2),
            K16_CSR_TRAP_FRAME_INDEX => Some(self.trap_frame_index as u32),
            K16_CSR_TRAP_FRAME_REGISTER => Some(self.trap_registers[self.trap_frame_index]),
            K16_CSR_TRAP_RESUME_PC => Some(self.trap_pc),
            K16_CSR_TRAP_STACK_POINTER => Some(self.trap_stack_pointer),
            K16_CSR_TRAP_INTERRUPT_ENABLE => Some(u32::from(self.trap_interrupt_enable)),
            K16_CSR_INTERRUPT_ENABLE => Some(u32::from(self.interrupt_enable)),
            K16_CSR_INTERRUPT_MASK => Some(self.interrupt_mask),
            K16_CSR_INTERRUPT_PENDING => Some(self.interrupt_pending),
            _ => None,
        }
    }

    pub fn request_interrupt(&mut self, source: u32, value: u32) {
        self.interrupt_pending |= source;
        if source & K16_INTERRUPT_SOURCE_TIMER0 != 0 {
            self.timer0_interrupt_value = value;
        }
    }

    pub fn step(&mut self, bus: &mut dyn MemoryBus) -> Result<Option<K16Signal>, K16Trap> {
        self.step_with_decoder_and_mmu(bus, &mut K16Decoder::new(), None)
    }

    pub fn step_with_mmu(
        &mut self,
        bus: &mut dyn MemoryBus,
        address_spaces: &MmuAddressSpaces,
    ) -> Result<Option<K16Signal>, K16Trap> {
        self.step_with_decoder_and_mmu(bus, &mut K16Decoder::new(), Some(address_spaces))
    }

    pub fn step_with_decoder(
        &mut self,
        bus: &mut dyn MemoryBus,
        decoder: &mut dyn InstructionDecoder,
    ) -> Result<Option<K16Signal>, K16Trap> {
        self.step_with_decoder_and_mmu(bus, decoder, None)
    }

    fn step_with_decoder_and_mmu(
        &mut self,
        bus: &mut dyn MemoryBus,
        decoder: &mut dyn InstructionDecoder,
        address_spaces: Option<&MmuAddressSpaces>,
    ) -> Result<Option<K16Signal>, K16Trap> {
        match &self.state {
            K16State::Running => {}
            K16State::Halted => return Ok(Some(K16Signal::Halt)),
            K16State::Trapped(message) => {
                return Err(K16Trap::new(format!(
                    "cpu is trapped and cannot continue: {message}"
                )))
            }
        }

        if self.interrupt_delivery_inhibited_steps > 0 {
            self.interrupt_delivery_inhibited_steps -= 1;
        } else if self.try_deliver_interrupt()? {
            return Ok(None);
        }

        // Instruction order is intentionally simple: decode at the old PC,
        // count the instruction, advance to the decoder-provided next PC, then
        // let control flow instructions override PC during execution.
        let fault_pc = self.pc;
        let decode = {
            let mut fetch_bus = self.cpu_bus(bus, address_spaces, MmuAccess::Fetch)?;
            decoder.decode(&mut fetch_bus, self.pc)
        };
        let decode = match decode {
            Ok(decode) => decode,
            Err(error) => {
                return self.raise_exception(error.cause, fault_pc, error.value, error.to_string());
            }
        };
        self.metrics.steps += 1;
        self.pc = decode.next_pc;

        let mut data_bus = self.cpu_bus(bus, address_spaces, MmuAccess::Load)?;
        let signal = match decode.instruction {
            DecodedInstruction::Nop => Ok(None),
            DecodedInstruction::Halt => {
                self.state = K16State::Halted;
                Ok(Some(K16Signal::Halt))
            }
            DecodedInstruction::Wait => Ok(Some(K16Signal::Wait)),
            DecodedInstruction::Const4 { dst, value } => {
                self.registers[dst] = value;
                Ok(None)
            }
            DecodedInstruction::Const32 { dst, value } => {
                self.registers[dst] = value;
                Ok(None)
            }
            DecodedInstruction::Add { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs].wrapping_add(self.registers[rhs]);
                Ok(None)
            }
            DecodedInstruction::Sub { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs].wrapping_sub(self.registers[rhs]);
                Ok(None)
            }
            DecodedInstruction::Mul { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs].wrapping_mul(self.registers[rhs]);
                Ok(None)
            }
            DecodedInstruction::MulHU { dst, lhs, rhs } => {
                let product = u64::from(self.registers[lhs]) * u64::from(self.registers[rhs]);
                self.registers[dst] = (product >> 32) as u32;
                Ok(None)
            }
            DecodedInstruction::MulHS { dst, lhs, rhs } => {
                let lhs = i64::from(self.registers[lhs] as i32);
                let rhs = i64::from(self.registers[rhs] as i32);
                self.registers[dst] = ((lhs * rhs) >> 32) as u32;
                Ok(None)
            }
            DecodedInstruction::And { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs] & self.registers[rhs];
                Ok(None)
            }
            DecodedInstruction::Or { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs] | self.registers[rhs];
                Ok(None)
            }
            DecodedInstruction::Xor { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs] ^ self.registers[rhs];
                Ok(None)
            }
            DecodedInstruction::Shl { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs].wrapping_shl(self.registers[rhs] & 31);
                Ok(None)
            }
            DecodedInstruction::Shr { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs].wrapping_shr(self.registers[rhs] & 31);
                Ok(None)
            }
            DecodedInstruction::Sar { dst, lhs, rhs } => {
                self.registers[dst] =
                    ((self.registers[lhs] as i32) >> (self.registers[rhs] & 31)) as u32;
                Ok(None)
            }
            DecodedInstruction::Eq { dst, lhs, rhs } => {
                self.registers[dst] = u32::from(self.registers[lhs] == self.registers[rhs]);
                Ok(None)
            }
            DecodedInstruction::Ne { dst, lhs, rhs } => {
                self.registers[dst] = u32::from(self.registers[lhs] != self.registers[rhs]);
                Ok(None)
            }
            DecodedInstruction::Ltu { dst, lhs, rhs } => {
                self.registers[dst] = u32::from(self.registers[lhs] < self.registers[rhs]);
                Ok(None)
            }
            DecodedInstruction::LtS { dst, lhs, rhs } => {
                self.registers[dst] =
                    u32::from((self.registers[lhs] as i32) < (self.registers[rhs] as i32));
                Ok(None)
            }
            DecodedInstruction::TestBits { dst, src, mask } => {
                self.registers[dst] = u32::from((self.registers[src] & mask) != 0);
                Ok(None)
            }
            DecodedInstruction::Load8 { dst, addr } => {
                self.load_u8_into_register(&mut data_bus, fault_pc, dst, addr)?;
                Ok(None)
            }
            DecodedInstruction::Load16 { dst, addr } => {
                self.load_u16_into_register(&mut data_bus, fault_pc, dst, addr)?;
                Ok(None)
            }
            DecodedInstruction::Load32 { dst, addr } => {
                self.load_i32_into_register(&mut data_bus, fault_pc, dst, addr)?;
                Ok(None)
            }
            DecodedInstruction::Store8 { addr, src } => {
                self.store_u8_from_register(&mut data_bus, fault_pc, addr, src)?;
                Ok(None)
            }
            DecodedInstruction::Store16 { addr, src } => {
                self.store_u16_from_register(&mut data_bus, fault_pc, addr, src)?;
                Ok(None)
            }
            DecodedInstruction::Store32 { addr, src } => {
                self.store_i32_from_register(&mut data_bus, fault_pc, addr, src)?;
                Ok(None)
            }
            DecodedInstruction::BranchIfZero { src, target_pc } => {
                if self.registers[src] == 0 {
                    self.pc = target_pc;
                }
                Ok(None)
            }
            DecodedInstruction::BranchIfNonZero { src, target_pc } => {
                if self.registers[src] != 0 {
                    self.pc = target_pc;
                }
                Ok(None)
            }
            DecodedInstruction::Jump { target } => {
                self.pc = self.registers[target];
                Ok(None)
            }
            DecodedInstruction::Call { target } => {
                self.call_register_target(&mut data_bus, fault_pc, target)?;
                Ok(None)
            }
            DecodedInstruction::Ret => {
                self.return_from_stack(&mut data_bus, fault_pc)?;
                Ok(None)
            }
            DecodedInstruction::Iret => {
                self.return_from_interrupt();
                Ok(None)
            }
            DecodedInstruction::Syscall { number } => {
                self.raise_syscall(
                    self.pc,
                    self.registers[number],
                    self.registers[2],
                    self.registers[3],
                    self.registers[4],
                )?;
                Ok(None)
            }
            DecodedInstruction::ReadCsr { dst, csr } => {
                self.read_csr_into_register(fault_pc, dst, csr)?;
                Ok(None)
            }
            DecodedInstruction::WriteCsr { csr, src } => {
                self.write_csr_from_register(fault_pc, csr, src)?;
                Ok(None)
            }
        }?;
        drop(data_bus);
        if signal.is_none() && bus.take_yield_signal() {
            return Ok(Some(K16Signal::Yield));
        }
        Ok(signal)
    }

    fn cpu_bus<'a>(
        &self,
        bus: &'a mut dyn MemoryBus,
        address_spaces: Option<&'a MmuAddressSpaces>,
        default_load_access: MmuAccess,
    ) -> Result<CpuMemoryBus<'a>, K16Trap> {
        let address_space = match self.address_mode {
            K16AddressMode::Physical => None,
            K16AddressMode::Translated { address_space } => {
                Some(
                    address_spaces
                        .and_then(|spaces| spaces.get(address_space))
                        .ok_or_else(|| {
                            K16Trap::new(format!(
                                "translated address mode requires active MMU address space {address_space:?}"
                            ))
                        })?,
                )
            }
        };
        Ok(CpuMemoryBus {
            bus,
            address_space,
            default_load_access,
            privilege: self.privilege_mode.mmu_privilege(),
        })
    }

    fn load_u8_into_register(
        &mut self,
        bus: &mut dyn MemoryBus,
        fault_pc: u32,
        dst: usize,
        addr: usize,
    ) -> Result<(), K16Trap> {
        let address = self.registers[addr];
        let value = match bus.load_u8(address) {
            Ok(value) => value,
            Err(error) => {
                return self.raise_load_fault(fault_pc, address, error);
            }
        };
        self.registers[dst] = u32::from(value);
        Ok(())
    }

    fn load_u16_into_register(
        &mut self,
        bus: &mut dyn MemoryBus,
        fault_pc: u32,
        dst: usize,
        addr: usize,
    ) -> Result<(), K16Trap> {
        let address = self.registers[addr];
        let value = match bus.load_u16(address) {
            Ok(value) => value,
            Err(error) => {
                return self.raise_load_fault(fault_pc, address, error);
            }
        };
        self.registers[dst] = u32::from(value);
        Ok(())
    }

    fn load_i32_into_register(
        &mut self,
        bus: &mut dyn MemoryBus,
        fault_pc: u32,
        dst: usize,
        addr: usize,
    ) -> Result<(), K16Trap> {
        let address = self.registers[addr];
        let value = match bus.load_i32(address) {
            Ok(value) => value,
            Err(error) => {
                return self.raise_load_fault(fault_pc, address, error);
            }
        };
        self.registers[dst] = value as u32;
        Ok(())
    }

    fn store_u8_from_register(
        &mut self,
        bus: &mut dyn MemoryBus,
        fault_pc: u32,
        addr: usize,
        src: usize,
    ) -> Result<(), K16Trap> {
        let address = self.registers[addr];
        if let Err(error) = bus.store_u8(address, self.registers[src] as u8) {
            return self.raise_store_fault(fault_pc, address, error);
        }
        Ok(())
    }

    fn store_u16_from_register(
        &mut self,
        bus: &mut dyn MemoryBus,
        fault_pc: u32,
        addr: usize,
        src: usize,
    ) -> Result<(), K16Trap> {
        let address = self.registers[addr];
        if let Err(error) = bus.store_u16(address, self.registers[src] as u16) {
            return self.raise_store_fault(fault_pc, address, error);
        }
        Ok(())
    }

    fn store_i32_from_register(
        &mut self,
        bus: &mut dyn MemoryBus,
        fault_pc: u32,
        addr: usize,
        src: usize,
    ) -> Result<(), K16Trap> {
        let address = self.registers[addr];
        if let Err(error) = bus.store_i32(address, self.registers[src] as i32) {
            return self.raise_store_fault(fault_pc, address, error);
        }
        Ok(())
    }

    fn call_register_target(
        &mut self,
        bus: &mut dyn MemoryBus,
        fault_pc: u32,
        target: usize,
    ) -> Result<(), K16Trap> {
        let return_pc = self.pc;
        let stack_pointer = usize::from(K16_STACK_POINTER_REGISTER);
        let new_stack_pointer = self.registers[stack_pointer].wrapping_sub(4);
        if let Err(error) = bus.store_i32(new_stack_pointer, return_pc as i32) {
            return self.raise_store_fault(fault_pc, new_stack_pointer, error);
        }
        self.registers[stack_pointer] = new_stack_pointer;
        self.pc = self.registers[target];
        Ok(())
    }

    fn return_from_stack(&mut self, bus: &mut dyn MemoryBus, fault_pc: u32) -> Result<(), K16Trap> {
        let stack_pointer = usize::from(K16_STACK_POINTER_REGISTER);
        let return_pc = match bus.load_i32(self.registers[stack_pointer]) {
            Ok(value) => value as u32,
            Err(error) => {
                return self.raise_load_fault(fault_pc, self.registers[stack_pointer], error);
            }
        };
        self.registers[stack_pointer] = self.registers[stack_pointer].wrapping_add(4);
        self.pc = return_pc;
        Ok(())
    }

    fn read_csr_into_register(
        &mut self,
        fault_pc: u32,
        dst: usize,
        csr: u32,
    ) -> Result<(), K16Trap> {
        let value = match self.csr(csr) {
            Some(value) => value,
            None => {
                return self.raise_explicit_trap(fault_pc, csr, format!("unknown csr {csr}"));
            }
        };
        self.registers[dst] = value;
        Ok(())
    }

    fn write_csr_from_register(
        &mut self,
        fault_pc: u32,
        csr: u32,
        src: usize,
    ) -> Result<(), K16Trap> {
        match csr {
            K16_CSR_TRAP_VECTOR => self.trap_vector = self.registers[src],
            K16_CSR_TRAP_CAUSE | K16_CSR_TRAP_PC | K16_CSR_TRAP_VALUE => {
                return self.raise_explicit_trap(fault_pc, csr, format!("csr {csr} is read-only"));
            }
            K16_CSR_TRAP_FRAME_INDEX => {
                let index = self.registers[src];
                if index >= 16 {
                    return self.raise_explicit_trap(
                        fault_pc,
                        index,
                        format!("trap frame register index {index} is out of range"),
                    );
                }
                self.trap_frame_index = index as usize;
            }
            K16_CSR_TRAP_FRAME_REGISTER => {
                self.trap_registers[self.trap_frame_index] = self.registers[src];
            }
            K16_CSR_TRAP_RESUME_PC => self.trap_pc = self.registers[src],
            K16_CSR_TRAP_STACK_POINTER => self.trap_stack_pointer = self.registers[src],
            K16_CSR_TRAP_INTERRUPT_ENABLE => {
                self.trap_interrupt_enable = self.registers[src] != 0;
            }
            K16_CSR_INTERRUPT_ENABLE => self.interrupt_enable = self.registers[src] != 0,
            K16_CSR_INTERRUPT_MASK => self.interrupt_mask = self.registers[src],
            K16_CSR_INTERRUPT_PENDING => {
                return self.raise_explicit_trap(fault_pc, csr, format!("csr {csr} is read-only"));
            }
            _ => {
                return self.raise_explicit_trap(fault_pc, csr, format!("unknown csr {csr}"));
            }
        }
        Ok(())
    }

    fn try_deliver_interrupt(&mut self) -> Result<bool, K16Trap> {
        if !self.interrupt_enable {
            return Ok(false);
        }
        let eligible = self.interrupt_pending & self.interrupt_mask;
        if eligible == 0 {
            return Ok(false);
        }
        let source = eligible & eligible.wrapping_neg();
        let (cause, value) = match source {
            K16_INTERRUPT_SOURCE_TIMER0 => {
                (K16_TRAP_CAUSE_TIMER0_INTERRUPT, self.timer0_interrupt_value)
            }
            K16_INTERRUPT_SOURCE_KEYBOARD0 => (K16_TRAP_CAUSE_KEYBOARD0_INTERRUPT, 0),
            _ => return Ok(false),
        };
        if self.trap_vector == 0 {
            let trap = K16Trap::exception(
                cause,
                self.pc,
                value,
                format!("unhandled interrupt cause {cause} at pc {:#010x}", self.pc),
            );
            self.state = K16State::Trapped(trap.to_string());
            return Err(trap);
        }
        self.interrupt_pending &= !source;
        self.trap_registers = self.registers;
        self.trap_cause = cause;
        self.trap_pc = self.pc;
        self.trap_value = value;
        self.trap_arg0 = 0;
        self.trap_arg1 = 0;
        self.trap_arg2 = 0;
        self.trap_stack_pointer = self.registers[usize::from(K16_STACK_POINTER_REGISTER)];
        self.trap_interrupt_enable = self.interrupt_enable;
        self.pc = self.trap_vector;
        self.interrupt_enable = false;
        Ok(true)
    }

    fn return_from_interrupt(&mut self) {
        let return_value = self.registers[0];
        self.registers = self.trap_registers;
        self.registers[0] = return_value;
        self.registers[usize::from(K16_STACK_POINTER_REGISTER)] = self.trap_stack_pointer;
        self.pc = self.trap_pc;
        self.interrupt_enable = self.trap_interrupt_enable;
        self.interrupt_delivery_inhibited_steps = 2;
    }

    fn raise_load_fault(
        &mut self,
        fault_pc: u32,
        address: u32,
        error: MemoryFault,
    ) -> Result<(), K16Trap> {
        self.raise_exception(
            K16_TRAP_CAUSE_LOAD_FAULT,
            fault_pc,
            address,
            error.to_string(),
        )
        .map(|_| ())
    }

    fn raise_store_fault(
        &mut self,
        fault_pc: u32,
        address: u32,
        error: MemoryFault,
    ) -> Result<(), K16Trap> {
        self.raise_exception(
            K16_TRAP_CAUSE_STORE_FAULT,
            fault_pc,
            address,
            error.to_string(),
        )
        .map(|_| ())
    }

    fn raise_explicit_trap(
        &mut self,
        fault_pc: u32,
        value: u32,
        message: impl Into<String>,
    ) -> Result<(), K16Trap> {
        self.raise_exception(K16_TRAP_CAUSE_EXPLICIT_TRAP, fault_pc, value, message)
            .map(|_| ())
    }

    fn raise_syscall(
        &mut self,
        continuation_pc: u32,
        number: u32,
        arg0: u32,
        arg1: u32,
        arg2: u32,
    ) -> Result<(), K16Trap> {
        self.raise_exception(
            K16_TRAP_CAUSE_EXPLICIT_TRAP,
            continuation_pc,
            number,
            format!("syscall {number}"),
        )?;
        self.trap_arg0 = arg0;
        self.trap_arg1 = arg1;
        self.trap_arg2 = arg2;
        self.interrupt_enable = false;
        Ok(())
    }

    fn raise_exception(
        &mut self,
        cause: u32,
        fault_pc: u32,
        value: u32,
        message: impl Into<String>,
    ) -> Result<Option<K16Signal>, K16Trap> {
        let message = message.into();
        if self.trap_vector == 0 {
            let trap = K16Trap::exception(
                cause,
                fault_pc,
                value,
                format!("unhandled exception cause {cause} at pc {fault_pc:#010x}: {message}"),
            );
            self.state = K16State::Trapped(trap.to_string());
            return Err(trap);
        }
        self.trap_registers = self.registers;
        self.trap_cause = cause;
        self.trap_pc = fault_pc;
        self.trap_value = value;
        self.trap_arg0 = 0;
        self.trap_arg1 = 0;
        self.trap_arg2 = 0;
        self.trap_stack_pointer = self.registers[usize::from(K16_STACK_POINTER_REGISTER)];
        self.trap_interrupt_enable = self.interrupt_enable;
        self.pc = self.trap_vector;
        Ok(None)
    }

    pub fn run_until_signal(
        &mut self,
        bus: &mut dyn MemoryBus,
        max_steps: u64,
    ) -> Result<K16Signal, K16Trap> {
        self.run_until_signal_with_decoder(bus, &mut K16Decoder::new(), max_steps)
    }

    pub fn run_until_signal_with_mmu(
        &mut self,
        bus: &mut dyn MemoryBus,
        address_spaces: &MmuAddressSpaces,
        max_steps: u64,
    ) -> Result<K16Signal, K16Trap> {
        for _ in 0..max_steps {
            if let Some(signal) = self.step_with_mmu(bus, address_spaces)? {
                return Ok(signal);
            }
        }
        Ok(K16Signal::StepLimitExceeded)
    }

    pub fn run_until_signal_with_decoder(
        &mut self,
        bus: &mut dyn MemoryBus,
        decoder: &mut dyn InstructionDecoder,
        max_steps: u64,
    ) -> Result<K16Signal, K16Trap> {
        for _ in 0..max_steps {
            if let Some(signal) = self.step_with_decoder(bus, decoder)? {
                return Ok(signal);
            }
        }
        Ok(K16Signal::StepLimitExceeded)
    }
}

struct CpuMemoryBus<'a> {
    bus: &'a mut dyn MemoryBus,
    address_space: Option<&'a MmuAddressSpace>,
    default_load_access: MmuAccess,
    privilege: MmuPrivilege,
}

impl CpuMemoryBus<'_> {
    fn translate_contiguous(
        &self,
        address: u32,
        size: u32,
        access: MmuAccess,
    ) -> Result<u32, MemoryFault> {
        let physical_start = self.translate(address, access)?;
        for offset in 1..size {
            let virtual_address = address.checked_add(offset).ok_or_else(|| {
                MemoryFault::new(format!(
                    "translated memory access starts at {address:#010x} and overflows u32"
                ))
            })?;
            let expected_physical = physical_start.checked_add(offset).ok_or_else(|| {
                MemoryFault::new(format!(
                    "translated physical access starts at {physical_start:#010x} and overflows u32"
                ))
            })?;
            let actual_physical = self.translate(virtual_address, access)?;
            if actual_physical != expected_physical {
                return Err(MemoryFault::new(format!(
                    "translated memory access {address:#010x}..{:#010x} is not physically contiguous",
                    address + size
                )));
            }
        }
        Ok(physical_start)
    }

    fn translate(&self, address: u32, access: MmuAccess) -> Result<u32, MemoryFault> {
        match self.address_space {
            Some(address_space) => address_space
                .translate(address, access, self.privilege)
                .map_err(mmu_memory_fault),
            None => Ok(address),
        }
    }
}

impl MemoryBus for CpuMemoryBus<'_> {
    fn len(&self) -> usize {
        self.bus.len()
    }

    fn take_yield_signal(&mut self) -> bool {
        self.bus.take_yield_signal()
    }

    fn load_i32(&self, address: u32) -> Result<i32, MemoryFault> {
        let physical = self.translate_contiguous(address, 4, self.default_load_access)?;
        let bus: &dyn MemoryBus = &*self.bus;
        bus.load_i32(physical)
    }

    fn store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault> {
        let physical = self.translate_contiguous(address, 4, MmuAccess::Store)?;
        self.bus.store_i32(physical, value)
    }

    fn load_u8(&self, address: u32) -> Result<u8, MemoryFault> {
        let physical = self.translate(address, self.default_load_access)?;
        let bus: &dyn MemoryBus = &*self.bus;
        bus.load_u8(physical)
    }

    fn store_u8(&mut self, address: u32, value: u8) -> Result<(), MemoryFault> {
        let physical = self.translate(address, MmuAccess::Store)?;
        self.bus.store_u8(physical, value)
    }

    fn load_u16(&self, address: u32) -> Result<u16, MemoryFault> {
        let physical = self.translate_contiguous(address, 2, self.default_load_access)?;
        let bus: &dyn MemoryBus = &*self.bus;
        bus.load_u16(physical)
    }

    fn store_u16(&mut self, address: u32, value: u16) -> Result<(), MemoryFault> {
        let physical = self.translate_contiguous(address, 2, MmuAccess::Store)?;
        self.bus.store_u16(physical, value)
    }

    fn load_u64(&self, address: u32) -> Result<u64, MemoryFault> {
        let physical = self.translate_contiguous(address, 8, self.default_load_access)?;
        let bus: &dyn MemoryBus = &*self.bus;
        bus.load_u64(physical)
    }

    fn store_u64(&mut self, address: u32, value: u64) -> Result<(), MemoryFault> {
        let physical = self.translate_contiguous(address, 8, MmuAccess::Store)?;
        self.bus.store_u64(physical, value)
    }
}

fn mmu_memory_fault(fault: MmuFault) -> MemoryFault {
    let kind = match fault.kind {
        crate::mmu::MmuFaultKind::NotPresent => "not-present",
        crate::mmu::MmuFaultKind::Permission => "permission",
        crate::mmu::MmuFaultKind::InvalidMapping => "invalid-mapping",
    };
    let access = match fault.access {
        MmuAccess::Fetch => "fetch",
        MmuAccess::Load => "load",
        MmuAccess::Store => "store",
    };
    MemoryFault::new(format!(
        "MMU {kind} fault for {access} at virtual address {:#010x}",
        fault.address
    ))
}

fn illegal_instruction(pc: u32, word: u16) -> K16Trap {
    K16Trap::exception(
        K16_TRAP_CAUSE_ILLEGAL_INSTRUCTION,
        pc,
        u32::from(word),
        format!("illegal instruction {word:#06x} at pc {pc:#010x}"),
    )
}

fn instruction_fetch_fault(pc: u32, error: MemoryFault) -> K16Trap {
    K16Trap::exception(
        K16_TRAP_CAUSE_INSTRUCTION_FETCH_FAULT,
        pc,
        pc,
        format!("instruction fetch fault at pc {pc:#010x}: {error}"),
    )
}

fn checked_pc_add(pc: u32, offset: u32) -> Result<u32, K16Trap> {
    pc.checked_add(offset).ok_or_else(|| {
        K16Trap::exception(
            K16_TRAP_CAUSE_INSTRUCTION_FETCH_FAULT,
            pc,
            pc,
            format!("pc {pc:#010x} overflows by {offset} bytes"),
        )
    })
}

fn relative_branch_target(next_pc: u32, offset_nibble: usize) -> Result<u32, K16Trap> {
    let offset_words = sign_extend_nibble(offset_nibble);
    let offset_bytes = offset_words * 2;
    let target = i64::from(next_pc) + i64::from(offset_bytes);
    if !(0..=i64::from(u32::MAX)).contains(&target) {
        return Err(K16Trap::exception(
            K16_TRAP_CAUSE_EXPLICIT_TRAP,
            next_pc,
            next_pc,
            format!(
                "branch from next pc {next_pc:#010x} with offset {offset_words} words leaves address space",
            ),
        ));
    }
    Ok(target as u32)
}

fn sign_extend_nibble(value: usize) -> i32 {
    let raw = (value & 0x0f) as i32;
    if raw & 0x08 == 0 {
        raw
    } else {
        raw - 16
    }
}
