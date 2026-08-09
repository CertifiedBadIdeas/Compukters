/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

use crate::low_machine::MemoryBus;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rv64imStop {
    Ecall,
    Ebreak,
    StepLimit,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rv64imCpu {
    pc: u64,
    registers: [u64; 32],
    retired_instructions: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Branch {
    Eq,
    Ne,
    Lt,
    Ge,
    Ltu,
    Geu,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Load {
    Byte,
    Half,
    Word,
    Double,
    ByteU,
    HalfU,
    WordU,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Store {
    Byte,
    Half,
    Word,
    Double,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ImmOp {
    Add,
    Slt,
    Sltu,
    Xor,
    Or,
    And,
    Sll,
    Srl,
    Sra,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Op {
    Add,
    Sub,
    Sll,
    Slt,
    Sltu,
    Xor,
    Srl,
    Sra,
    Or,
    And,
    Mul,
    Mulh,
    Mulhsu,
    Mulhu,
    Div,
    Divu,
    Rem,
    Remu,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum WordOp {
    Add,
    Sub,
    Sll,
    Srl,
    Sra,
    Mul,
    Div,
    Divu,
    Rem,
    Remu,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum DecodedInstruction {
    Lui {
        rd: usize,
        value: u64,
    },
    Auipc {
        rd: usize,
        value: u64,
    },
    Jal {
        rd: usize,
        offset: i32,
    },
    Jalr {
        rd: usize,
        rs1: usize,
        immediate: i32,
    },
    Branch {
        kind: Branch,
        rs1: usize,
        rs2: usize,
        offset: i32,
    },
    Load {
        kind: Load,
        rd: usize,
        rs1: usize,
        immediate: i32,
    },
    Store {
        kind: Store,
        rs1: usize,
        rs2: usize,
        immediate: i32,
    },
    Immediate {
        op: ImmOp,
        rd: usize,
        rs1: usize,
        immediate: i32,
    },
    ImmediateWord {
        op: WordOp,
        rd: usize,
        rs1: usize,
        immediate: i32,
    },
    Register {
        op: Op,
        rd: usize,
        rs1: usize,
        rs2: usize,
    },
    RegisterWord {
        op: WordOp,
        rd: usize,
        rs1: usize,
        rs2: usize,
    },
    Ecall,
    Ebreak,
}

impl Rv64imCpu {
    pub fn new(pc: u64) -> Self {
        Self {
            pc,
            registers: [0; 32],
            retired_instructions: 0,
        }
    }

    pub const fn cpu_state_bytes() -> usize {
        std::mem::size_of::<Self>()
    }

    pub fn pc(&self) -> u64 {
        self.pc
    }

    pub fn register(&self, register: usize) -> u64 {
        self.registers[register]
    }

    pub fn set_register(&mut self, register: usize, value: u64) -> Result<(), String> {
        if register >= self.registers.len() {
            return Err(format!("RV64IM register index {register} is outside 0..32"));
        }
        if register != 0 {
            self.registers[register] = value;
        }
        Ok(())
    }

    pub fn retired_instructions(&self) -> u64 {
        self.retired_instructions
    }

    pub fn run_until_stop(
        &mut self,
        bus: &mut dyn MemoryBus,
        max_steps: u64,
    ) -> Result<Rv64imStop, String> {
        for _ in 0..max_steps {
            if let Some(stop) = self.step(bus)? {
                return Ok(stop);
            }
        }
        Ok(Rv64imStop::StepLimit)
    }

    pub fn step(&mut self, bus: &mut dyn MemoryBus) -> Result<Option<Rv64imStop>, String> {
        require_alignment(self.pc, 4, "instruction")?;
        let instruction_pc = self.pc;
        let address = physical_address(self.pc)?;
        let word = bus.load_i32(address).map_err(|error| error.to_string())? as u32;
        self.retire_decoded(bus, instruction_pc, decode(word)?)
    }

    fn retire_decoded(
        &mut self,
        bus: &mut dyn MemoryBus,
        instruction_pc: u64,
        instruction: DecodedInstruction,
    ) -> Result<Option<Rv64imStop>, String> {
        let previous_pc = self.pc;
        let stop = match self.execute_decoded(bus, instruction_pc, instruction) {
            Ok(stop) => stop,
            Err(error) => {
                self.pc = previous_pc;
                return Err(error);
            }
        };
        self.registers[0] = 0;
        self.retired_instructions = self.retired_instructions.saturating_add(1);
        Ok(stop)
    }

    fn execute_decoded(
        &mut self,
        bus: &mut dyn MemoryBus,
        instruction_pc: u64,
        instruction: DecodedInstruction,
    ) -> Result<Option<Rv64imStop>, String> {
        let next_pc = instruction_pc.wrapping_add(4);
        self.pc = next_pc;
        match instruction {
            DecodedInstruction::Lui { rd, value } => self.registers[rd] = value,
            DecodedInstruction::Auipc { rd, value } => {
                self.registers[rd] = instruction_pc.wrapping_add(value)
            }
            DecodedInstruction::Jal { rd, offset } => {
                self.pc = checked_target(instruction_pc.wrapping_add_signed(i64::from(offset)))?;
                self.registers[rd] = next_pc;
            }
            DecodedInstruction::Jalr { rd, rs1, immediate } => {
                let target = self.registers[rs1].wrapping_add_signed(i64::from(immediate)) & !1;
                self.pc = checked_target(target)?;
                self.registers[rd] = next_pc;
            }
            DecodedInstruction::Branch {
                kind,
                rs1,
                rs2,
                offset,
            } => {
                let lhs = self.registers[rs1];
                let rhs = self.registers[rs2];
                let take = match kind {
                    Branch::Eq => lhs == rhs,
                    Branch::Ne => lhs != rhs,
                    Branch::Lt => (lhs as i64) < (rhs as i64),
                    Branch::Ge => (lhs as i64) >= (rhs as i64),
                    Branch::Ltu => lhs < rhs,
                    Branch::Geu => lhs >= rhs,
                };
                if take {
                    self.pc =
                        checked_target(instruction_pc.wrapping_add_signed(i64::from(offset)))?;
                }
            }
            DecodedInstruction::Load {
                kind,
                rd,
                rs1,
                immediate,
            } => {
                let address = self.registers[rs1].wrapping_add_signed(i64::from(immediate));
                require_data_alignment(address, load_alignment(kind), "load")?;
                let address = physical_address(address)?;
                self.registers[rd] = match kind {
                    Load::Byte => {
                        bus.load_u8(address).map_err(|e| e.to_string())? as i8 as i64 as u64
                    }
                    Load::Half => {
                        bus.load_u16(address).map_err(|e| e.to_string())? as i16 as i64 as u64
                    }
                    Load::Word => bus.load_i32(address).map_err(|e| e.to_string())? as i64 as u64,
                    Load::Double => bus.load_u64(address).map_err(|e| e.to_string())?,
                    Load::ByteU => u64::from(bus.load_u8(address).map_err(|e| e.to_string())?),
                    Load::HalfU => u64::from(bus.load_u16(address).map_err(|e| e.to_string())?),
                    Load::WordU => {
                        u64::from(bus.load_i32(address).map_err(|e| e.to_string())? as u32)
                    }
                };
            }
            DecodedInstruction::Store {
                kind,
                rs1,
                rs2,
                immediate,
            } => {
                let address = self.registers[rs1].wrapping_add_signed(i64::from(immediate));
                require_data_alignment(address, store_alignment(kind), "store")?;
                let address = physical_address(address)?;
                let value = self.registers[rs2];
                match kind {
                    Store::Byte => bus.store_u8(address, value as u8),
                    Store::Half => bus.store_u16(address, value as u16),
                    Store::Word => bus.store_i32(address, value as i32),
                    Store::Double => bus.store_u64(address, value),
                }
                .map_err(|e| e.to_string())?;
            }
            DecodedInstruction::Immediate {
                op,
                rd,
                rs1,
                immediate,
            } => {
                let lhs = self.registers[rs1];
                self.registers[rd] = execute_immediate(op, lhs, immediate);
            }
            DecodedInstruction::ImmediateWord {
                op,
                rd,
                rs1,
                immediate,
            } => {
                self.registers[rd] = execute_word(op, self.registers[rs1], immediate as u64);
            }
            DecodedInstruction::Register { op, rd, rs1, rs2 } => {
                self.registers[rd] = execute_op(op, self.registers[rs1], self.registers[rs2]);
            }
            DecodedInstruction::RegisterWord { op, rd, rs1, rs2 } => {
                self.registers[rd] = execute_word(op, self.registers[rs1], self.registers[rs2]);
            }
            DecodedInstruction::Ecall => return Ok(Some(Rv64imStop::Ecall)),
            DecodedInstruction::Ebreak => return Ok(Some(Rv64imStop::Ebreak)),
        }
        Ok(None)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PredecodedRv64imProgram {
    base: u64,
    instructions: Vec<DecodedInstruction>,
}

impl PredecodedRv64imProgram {
    pub fn new(base: u64, code: &[u8]) -> Result<Self, String> {
        require_alignment(base, 4, "predecode base")?;
        if !code.len().is_multiple_of(4) {
            return Err(format!(
                "RV64IM predecode image length {} is not a multiple of four",
                code.len()
            ));
        }
        let instructions = code
            .chunks_exact(4)
            .enumerate()
            .map(|(index, bytes)| {
                decode(u32::from_le_bytes(bytes.try_into().unwrap())).map_err(|error| {
                    format!(
                        "RV64IM predecode failed at {:#018x}: {error}",
                        base.wrapping_add(index as u64 * 4)
                    )
                })
            })
            .collect::<Result<Vec<_>, _>>()?;
        Ok(Self { base, instructions })
    }

    pub fn retained_bytes(&self) -> usize {
        self.instructions.capacity() * std::mem::size_of::<DecodedInstruction>()
    }

    pub fn run_until_stop(
        &self,
        cpu: &mut Rv64imCpu,
        bus: &mut dyn MemoryBus,
        max_steps: u64,
    ) -> Result<Rv64imStop, String> {
        for _ in 0..max_steps {
            if let Some(stop) = self.step(cpu, bus)? {
                return Ok(stop);
            }
        }
        Ok(Rv64imStop::StepLimit)
    }

    pub fn step(
        &self,
        cpu: &mut Rv64imCpu,
        bus: &mut dyn MemoryBus,
    ) -> Result<Option<Rv64imStop>, String> {
        let offset = cpu
            .pc
            .checked_sub(self.base)
            .ok_or_else(|| format!("RV64IM predecoded PC {:#018x} precedes image", cpu.pc))?;
        require_alignment(offset, 4, "predecoded PC")?;
        let instruction = self
            .instructions
            .get(offset as usize / 4)
            .copied()
            .ok_or_else(|| format!("RV64IM predecoded PC {:#018x} is outside image", cpu.pc))?;
        cpu.retire_decoded(bus, cpu.pc, instruction)
    }
}

fn decode(word: u32) -> Result<DecodedInstruction, String> {
    let opcode = word & 0x7f;
    let rd = ((word >> 7) & 31) as usize;
    let funct3 = (word >> 12) & 7;
    let rs1 = ((word >> 15) & 31) as usize;
    let rs2 = ((word >> 20) & 31) as usize;
    let funct7 = (word >> 25) & 0x7f;
    let funct6 = (word >> 26) & 0x3f;
    let i_imm = (word as i32) >> 20;
    let s_raw = ((word >> 7) & 0x1f) | ((word >> 25) << 5);
    let s_imm = ((s_raw << 20) as i32) >> 20;
    let b_raw = ((word >> 31) << 12)
        | (((word >> 7) & 1) << 11)
        | (((word >> 25) & 0x3f) << 5)
        | (((word >> 8) & 0x0f) << 1);
    let b_imm = ((b_raw << 19) as i32) >> 19;
    let j_raw = ((word >> 31) << 20)
        | (((word >> 12) & 0xff) << 12)
        | (((word >> 20) & 1) << 11)
        | (((word >> 21) & 0x3ff) << 1);
    let j_imm = ((j_raw << 11) as i32) >> 11;
    let illegal = || Err(format!("illegal RV64IM instruction {word:#010x}"));
    match opcode {
        0x37 => Ok(DecodedInstruction::Lui {
            rd,
            value: sign_extend_word(word & 0xffff_f000),
        }),
        0x17 => Ok(DecodedInstruction::Auipc {
            rd,
            value: sign_extend_word(word & 0xffff_f000),
        }),
        0x6f => Ok(DecodedInstruction::Jal { rd, offset: j_imm }),
        0x67 if funct3 == 0 => Ok(DecodedInstruction::Jalr {
            rd,
            rs1,
            immediate: i_imm,
        }),
        0x63 => {
            let kind = match funct3 {
                0 => Branch::Eq,
                1 => Branch::Ne,
                4 => Branch::Lt,
                5 => Branch::Ge,
                6 => Branch::Ltu,
                7 => Branch::Geu,
                _ => return illegal(),
            };
            Ok(DecodedInstruction::Branch {
                kind,
                rs1,
                rs2,
                offset: b_imm,
            })
        }
        0x03 => {
            let kind = match funct3 {
                0 => Load::Byte,
                1 => Load::Half,
                2 => Load::Word,
                3 => Load::Double,
                4 => Load::ByteU,
                5 => Load::HalfU,
                6 => Load::WordU,
                _ => return illegal(),
            };
            Ok(DecodedInstruction::Load {
                kind,
                rd,
                rs1,
                immediate: i_imm,
            })
        }
        0x23 => {
            let kind = match funct3 {
                0 => Store::Byte,
                1 => Store::Half,
                2 => Store::Word,
                3 => Store::Double,
                _ => return illegal(),
            };
            Ok(DecodedInstruction::Store {
                kind,
                rs1,
                rs2,
                immediate: s_imm,
            })
        }
        0x13 => {
            let (op, immediate) = match funct3 {
                0 => (ImmOp::Add, i_imm),
                2 => (ImmOp::Slt, i_imm),
                3 => (ImmOp::Sltu, i_imm),
                4 => (ImmOp::Xor, i_imm),
                6 => (ImmOp::Or, i_imm),
                7 => (ImmOp::And, i_imm),
                1 if funct6 == 0 => (ImmOp::Sll, ((word >> 20) & 63) as i32),
                5 if funct6 == 0 => (ImmOp::Srl, ((word >> 20) & 63) as i32),
                5 if funct6 == 0x10 => (ImmOp::Sra, ((word >> 20) & 63) as i32),
                _ => return illegal(),
            };
            Ok(DecodedInstruction::Immediate {
                op,
                rd,
                rs1,
                immediate,
            })
        }
        0x1b => {
            let (op, immediate) = match funct3 {
                0 => (WordOp::Add, i_imm),
                1 if funct7 == 0 => (WordOp::Sll, rs2 as i32),
                5 if funct7 == 0 => (WordOp::Srl, rs2 as i32),
                5 if funct7 == 0x20 => (WordOp::Sra, rs2 as i32),
                _ => return illegal(),
            };
            Ok(DecodedInstruction::ImmediateWord {
                op,
                rd,
                rs1,
                immediate,
            })
        }
        0x33 => {
            let op = match (funct7, funct3) {
                (0, 0) => Op::Add,
                (0x20, 0) => Op::Sub,
                (0, 1) => Op::Sll,
                (0, 2) => Op::Slt,
                (0, 3) => Op::Sltu,
                (0, 4) => Op::Xor,
                (0, 5) => Op::Srl,
                (0x20, 5) => Op::Sra,
                (0, 6) => Op::Or,
                (0, 7) => Op::And,
                (1, 0) => Op::Mul,
                (1, 1) => Op::Mulh,
                (1, 2) => Op::Mulhsu,
                (1, 3) => Op::Mulhu,
                (1, 4) => Op::Div,
                (1, 5) => Op::Divu,
                (1, 6) => Op::Rem,
                (1, 7) => Op::Remu,
                _ => return illegal(),
            };
            Ok(DecodedInstruction::Register { op, rd, rs1, rs2 })
        }
        0x3b => {
            let op = match (funct7, funct3) {
                (0, 0) => WordOp::Add,
                (0x20, 0) => WordOp::Sub,
                (0, 1) => WordOp::Sll,
                (0, 5) => WordOp::Srl,
                (0x20, 5) => WordOp::Sra,
                (1, 0) => WordOp::Mul,
                (1, 4) => WordOp::Div,
                (1, 5) => WordOp::Divu,
                (1, 6) => WordOp::Rem,
                (1, 7) => WordOp::Remu,
                _ => return illegal(),
            };
            Ok(DecodedInstruction::RegisterWord { op, rd, rs1, rs2 })
        }
        0x73 if word == 0x0000_0073 => Ok(DecodedInstruction::Ecall),
        0x73 if word == 0x0010_0073 => Ok(DecodedInstruction::Ebreak),
        _ => illegal(),
    }
}

fn execute_immediate(op: ImmOp, lhs: u64, immediate: i32) -> u64 {
    match op {
        ImmOp::Add => lhs.wrapping_add_signed(i64::from(immediate)),
        ImmOp::Slt => u64::from((lhs as i64) < i64::from(immediate)),
        ImmOp::Sltu => u64::from(lhs < i64::from(immediate) as u64),
        ImmOp::Xor => lhs ^ i64::from(immediate) as u64,
        ImmOp::Or => lhs | i64::from(immediate) as u64,
        ImmOp::And => lhs & i64::from(immediate) as u64,
        ImmOp::Sll => lhs.wrapping_shl(immediate as u32 & 63),
        ImmOp::Srl => lhs.wrapping_shr(immediate as u32 & 63),
        ImmOp::Sra => ((lhs as i64) >> (immediate as u32 & 63)) as u64,
    }
}

fn execute_op(op: Op, lhs: u64, rhs: u64) -> u64 {
    match op {
        Op::Add => lhs.wrapping_add(rhs),
        Op::Sub => lhs.wrapping_sub(rhs),
        Op::Sll => lhs.wrapping_shl(rhs as u32 & 63),
        Op::Slt => u64::from((lhs as i64) < (rhs as i64)),
        Op::Sltu => u64::from(lhs < rhs),
        Op::Xor => lhs ^ rhs,
        Op::Srl => lhs.wrapping_shr(rhs as u32 & 63),
        Op::Sra => ((lhs as i64) >> (rhs as u32 & 63)) as u64,
        Op::Or => lhs | rhs,
        Op::And => lhs & rhs,
        Op::Mul => lhs.wrapping_mul(rhs),
        Op::Mulh => (((lhs as i64 as i128) * (rhs as i64 as i128)) >> 64) as u64,
        Op::Mulhsu => (((lhs as i64 as i128) * (rhs as i128)) >> 64) as u64,
        Op::Mulhu => (((lhs as u128) * (rhs as u128)) >> 64) as u64,
        Op::Div => signed_div64(lhs, rhs),
        Op::Divu => {
            if rhs == 0 {
                u64::MAX
            } else {
                lhs / rhs
            }
        }
        Op::Rem => signed_rem64(lhs, rhs),
        Op::Remu => {
            if rhs == 0 {
                lhs
            } else {
                lhs % rhs
            }
        }
    }
}

fn execute_word(op: WordOp, lhs: u64, rhs: u64) -> u64 {
    let lhs_u32 = lhs as u32;
    let rhs_u32 = rhs as u32;
    let result = match op {
        WordOp::Add => lhs_u32.wrapping_add(rhs_u32),
        WordOp::Sub => lhs_u32.wrapping_sub(rhs_u32),
        WordOp::Sll => lhs_u32.wrapping_shl(rhs_u32 & 31),
        WordOp::Srl => lhs_u32.wrapping_shr(rhs_u32 & 31),
        WordOp::Sra => ((lhs_u32 as i32) >> (rhs_u32 & 31)) as u32,
        WordOp::Mul => lhs_u32.wrapping_mul(rhs_u32),
        WordOp::Div => signed_div32(lhs_u32, rhs_u32),
        WordOp::Divu => {
            if rhs_u32 == 0 {
                u32::MAX
            } else {
                lhs_u32 / rhs_u32
            }
        }
        WordOp::Rem => signed_rem32(lhs_u32, rhs_u32),
        WordOp::Remu => {
            if rhs_u32 == 0 {
                lhs_u32
            } else {
                lhs_u32 % rhs_u32
            }
        }
    };
    sign_extend_word(result)
}

fn signed_div64(lhs: u64, rhs: u64) -> u64 {
    let lhs = lhs as i64;
    let rhs = rhs as i64;
    if rhs == 0 {
        u64::MAX
    } else if lhs == i64::MIN && rhs == -1 {
        lhs as u64
    } else {
        (lhs / rhs) as u64
    }
}

fn signed_rem64(lhs: u64, rhs: u64) -> u64 {
    let lhs = lhs as i64;
    let rhs = rhs as i64;
    if rhs == 0 {
        lhs as u64
    } else if lhs == i64::MIN && rhs == -1 {
        0
    } else {
        (lhs % rhs) as u64
    }
}

fn signed_div32(lhs: u32, rhs: u32) -> u32 {
    let lhs = lhs as i32;
    let rhs = rhs as i32;
    if rhs == 0 {
        u32::MAX
    } else if lhs == i32::MIN && rhs == -1 {
        lhs as u32
    } else {
        (lhs / rhs) as u32
    }
}

fn signed_rem32(lhs: u32, rhs: u32) -> u32 {
    let lhs = lhs as i32;
    let rhs = rhs as i32;
    if rhs == 0 {
        lhs as u32
    } else if lhs == i32::MIN && rhs == -1 {
        0
    } else {
        (lhs % rhs) as u32
    }
}

fn sign_extend_word(value: u32) -> u64 {
    value as i32 as i64 as u64
}

fn physical_address(address: u64) -> Result<u32, String> {
    u32::try_from(address)
        .map_err(|_| format!("RV64IM address {address:#018x} is outside the 32-bit benchmark bus"))
}

fn checked_target(target: u64) -> Result<u64, String> {
    require_alignment(target, 4, "control-flow target")?;
    physical_address(target)?;
    Ok(target)
}

fn require_alignment(address: u64, alignment: u64, access: &str) -> Result<(), String> {
    if !address.is_multiple_of(alignment) {
        return Err(format!(
            "misaligned RV64IM {access} address {address:#018x}; required alignment {alignment}"
        ));
    }
    Ok(())
}

fn require_data_alignment(address: u64, alignment: u64, access: &str) -> Result<(), String> {
    if alignment > 1 {
        require_alignment(address, alignment, access)
    } else {
        Ok(())
    }
}

fn load_alignment(kind: Load) -> u64 {
    match kind {
        Load::Byte | Load::ByteU => 1,
        Load::Half | Load::HalfU => 2,
        Load::Word | Load::WordU => 4,
        Load::Double => 8,
    }
}

fn store_alignment(kind: Store) -> u64 {
    match kind {
        Store::Byte => 1,
        Store::Half => 2,
        Store::Word => 4,
        Store::Double => 8,
    }
}
