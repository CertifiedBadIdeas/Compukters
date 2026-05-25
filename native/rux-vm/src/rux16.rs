use crate::low_machine::{MemoryBus, MemoryFault};
use std::fmt::{Display, Formatter};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Rux16Signal {
    Halt,
    Trap,
    StepLimitExceeded,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rux16Metrics {
    pub steps: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rux16Trap {
    message: String,
}

impl Rux16Trap {
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }
}

impl Display for Rux16Trap {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for Rux16Trap {}

impl From<MemoryFault> for Rux16Trap {
    fn from(value: MemoryFault) -> Self {
        Self::new(value.to_string())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodeResult {
    pub instruction: DecodedInstruction,
    pub next_pc: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DecodedInstruction {
    Nop,
    Halt,
    Const4 { dst: usize, value: u32 },
    Add { dst: usize, lhs: usize, rhs: usize },
    Load32 { dst: usize, addr: usize },
    Store32 { addr: usize, src: usize },
    Jump { target: usize },
}

pub trait InstructionDecoder {
    fn decode(&mut self, bus: &mut dyn MemoryBus, pc: u32) -> Result<DecodeResult, Rux16Trap>;
}

#[derive(Debug, Clone, Default)]
pub struct Rux16Decoder;

impl Rux16Decoder {
    pub fn new() -> Self {
        Self
    }
}

impl InstructionDecoder for Rux16Decoder {
    fn decode(&mut self, bus: &mut dyn MemoryBus, pc: u32) -> Result<DecodeResult, Rux16Trap> {
        let word = bus.load_u16(pc)?;
        let next_pc = pc.checked_add(2).ok_or_else(|| {
            Rux16Trap::new(format!(
                "pc {pc:#010x} overflows while advancing instruction"
            ))
        })?;
        let op = (word >> 12) & 0x0f;
        let a = usize::from((word >> 8) & 0x0f);
        let b = usize::from((word >> 4) & 0x0f);
        let c = usize::from(word & 0x0f);
        let instruction = match op {
            0x0 => match word & 0x0fff {
                0x000 => DecodedInstruction::Nop,
                0x001 => DecodedInstruction::Halt,
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
            0x2 => DecodedInstruction::Add {
                dst: a,
                lhs: b,
                rhs: c,
            },
            0x4 => match c {
                0x2 => DecodedInstruction::Load32 { dst: a, addr: b },
                _ => return Err(illegal_instruction(pc, word)),
            },
            0x5 => match c {
                0x2 => DecodedInstruction::Store32 { addr: a, src: b },
                _ => return Err(illegal_instruction(pc, word)),
            },
            0x7 => {
                if b != 0 || c != 0 {
                    return Err(illegal_instruction(pc, word));
                }
                DecodedInstruction::Jump { target: a }
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
enum Rux16State {
    Running,
    Halted,
    Trapped(String),
}

#[derive(Debug, Clone)]
pub struct Rux16Cpu {
    pc: u32,
    registers: [u32; 16],
    state: Rux16State,
    metrics: Rux16Metrics,
}

impl Rux16Cpu {
    pub fn new(entry_pc: u32) -> Self {
        Self {
            pc: entry_pc,
            registers: [0; 16],
            state: Rux16State::Running,
            metrics: Rux16Metrics { steps: 0 },
        }
    }

    pub fn pc(&self) -> u32 {
        self.pc
    }

    pub fn register(&self, index: usize) -> u32 {
        self.registers[index]
    }

    pub fn metrics(&self) -> &Rux16Metrics {
        &self.metrics
    }

    pub fn trap(&self) -> Option<&str> {
        match &self.state {
            Rux16State::Trapped(message) => Some(message.as_str()),
            _ => None,
        }
    }

    pub fn step(&mut self, bus: &mut dyn MemoryBus) -> Result<Option<Rux16Signal>, Rux16Trap> {
        self.step_with_decoder(bus, &mut Rux16Decoder::new())
    }

    pub fn step_with_decoder(
        &mut self,
        bus: &mut dyn MemoryBus,
        decoder: &mut dyn InstructionDecoder,
    ) -> Result<Option<Rux16Signal>, Rux16Trap> {
        match &self.state {
            Rux16State::Running => {}
            Rux16State::Halted => return Ok(Some(Rux16Signal::Halt)),
            Rux16State::Trapped(_) => return Ok(Some(Rux16Signal::Trap)),
        }

        let decode = match decoder.decode(bus, self.pc) {
            Ok(decode) => decode,
            Err(error) => {
                self.state = Rux16State::Trapped(error.to_string());
                return Ok(Some(Rux16Signal::Trap));
            }
        };
        self.metrics.steps += 1;
        self.pc = decode.next_pc;

        match decode.instruction {
            DecodedInstruction::Nop => Ok(None),
            DecodedInstruction::Halt => {
                self.state = Rux16State::Halted;
                Ok(Some(Rux16Signal::Halt))
            }
            DecodedInstruction::Const4 { dst, value } => {
                self.registers[dst] = value;
                Ok(None)
            }
            DecodedInstruction::Add { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs].wrapping_add(self.registers[rhs]);
                Ok(None)
            }
            DecodedInstruction::Load32 { dst, addr } => {
                self.registers[dst] = bus.load_i32(self.registers[addr])? as u32;
                Ok(None)
            }
            DecodedInstruction::Store32 { addr, src } => {
                bus.store_i32(self.registers[addr], self.registers[src] as i32)?;
                Ok(None)
            }
            DecodedInstruction::Jump { target } => {
                self.pc = self.registers[target];
                Ok(None)
            }
        }
    }

    pub fn run_until_signal(
        &mut self,
        bus: &mut dyn MemoryBus,
        max_steps: u64,
    ) -> Result<Rux16Signal, Rux16Trap> {
        self.run_until_signal_with_decoder(bus, &mut Rux16Decoder::new(), max_steps)
    }

    pub fn run_until_signal_with_decoder(
        &mut self,
        bus: &mut dyn MemoryBus,
        decoder: &mut dyn InstructionDecoder,
        max_steps: u64,
    ) -> Result<Rux16Signal, Rux16Trap> {
        for _ in 0..max_steps {
            if let Some(signal) = self.step_with_decoder(bus, decoder)? {
                return Ok(signal);
            }
        }
        Ok(Rux16Signal::StepLimitExceeded)
    }
}

fn illegal_instruction(pc: u32, word: u16) -> Rux16Trap {
    Rux16Trap::new(format!("illegal instruction {word:#06x} at pc {pc:#010x}"))
}
