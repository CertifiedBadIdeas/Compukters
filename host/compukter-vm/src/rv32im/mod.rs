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

mod atomic;
mod bounded_cache;
mod cache;
mod decode;
pub mod encoding;
mod predecode;

pub use bounded_cache::{BoundedCachedRv32imProgram, Rv32imCacheStats};
pub use cache::CachedRv32imProgram;
pub use predecode::{PredecodedRv32imImage, PredecodedRv32imProgram};

use crate::memory::{AtomicWordAccess, MemoryBus};
use atomic::{apply_atomic, Rv32Reservation};
pub(crate) use decode::{
    decode as decode_product_word, decode_eager_reference, CsrOperation, CsrSource,
    DecodedInstruction,
};
use decode::{Branch, ImmOp, Load, Op, Store};
use std::fmt::{Display, Formatter};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rv32imStop {
    Ecall,
    Ebreak,
    StepLimit,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Rv32RegularFault {
    InstructionAddressMisaligned { address: u32 },
    LoadAddressMisaligned { address: u32 },
    LoadAccessFault { address: u32, detail: String },
    StoreAddressMisaligned { address: u32 },
    StoreAccessFault { address: u32, detail: String },
    MachineInstructionRequired,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Rv32ResolvedInstruction {
    Valid {
        word: u32,
        instruction: DecodedInstruction,
    },
    Invalid {
        word: u32,
    },
}

impl Display for Rv32RegularFault {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InstructionAddressMisaligned { address } => write!(
                formatter,
                "misaligned RV32IM control-flow target address {address:#010x}"
            ),
            Self::LoadAddressMisaligned { address } => {
                write!(formatter, "misaligned RV32IM load address {address:#010x}")
            }
            Self::LoadAccessFault { detail, .. } | Self::StoreAccessFault { detail, .. } => {
                formatter.write_str(detail)
            }
            Self::StoreAddressMisaligned { address } => {
                write!(formatter, "misaligned RV32IM store address {address:#010x}")
            }
            Self::MachineInstructionRequired => {
                formatter.write_str("RV32 machine instruction requires a machine hart")
            }
        }
    }
}

impl std::error::Error for Rv32RegularFault {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rv32imCpu {
    pc: u32,
    registers: [u32; 32],
    retired_instructions: u64,
    reservation: Option<Rv32Reservation>,
}

impl Rv32imCpu {
    pub fn new(pc: u32) -> Self {
        Self {
            pc,
            registers: [0; 32],
            retired_instructions: 0,
            reservation: None,
        }
    }
    pub const fn cpu_state_bytes() -> usize {
        std::mem::size_of::<Self>()
    }
    pub fn pc(&self) -> u32 {
        self.pc
    }
    pub fn register(&self, register: usize) -> u32 {
        self.registers[register]
    }
    pub fn set_register(&mut self, register: usize, value: u32) -> Result<(), String> {
        if register >= self.registers.len() {
            return Err(format!("RV32IM register index {register} is outside 0..32"));
        }
        if register != 0 {
            self.registers[register] = value;
        }
        Ok(())
    }
    pub fn retired_instructions(&self) -> u64 {
        self.retired_instructions
    }

    pub(crate) fn set_pc_internal(&mut self, pc: u32) {
        self.pc = pc;
    }

    pub(crate) fn set_decoded_register(&mut self, register: usize, value: u32) {
        debug_assert!(register < self.registers.len());
        if register != 0 {
            self.registers[register] = value;
        }
    }

    pub(crate) fn commit_instruction(&mut self) {
        self.registers[0] = 0;
        self.retired_instructions = self.retired_instructions.saturating_add(1);
    }

    pub(crate) fn clear_reservation(&mut self) {
        self.reservation = None;
    }

    pub(crate) fn invalidate_reservation(&mut self, address: u32, size: u32) {
        if self
            .reservation
            .is_some_and(|reservation| reservation.intersects(address, size))
        {
            self.reservation = None;
        }
    }

    pub fn run_until_stop(
        &mut self,
        bus: &mut dyn MemoryBus,
        max_steps: u64,
    ) -> Result<Rv32imStop, String> {
        for _ in 0..max_steps {
            if let Some(stop) = self.step(bus)? {
                return Ok(stop);
            }
        }
        Ok(Rv32imStop::StepLimit)
    }

    pub fn step(&mut self, bus: &mut dyn MemoryBus) -> Result<Option<Rv32imStop>, String> {
        require_alignment(self.pc, 4, "instruction")?;
        let instruction_pc = self.pc;
        let word = bus.load_i32(self.pc).map_err(|error| error.to_string())? as u32;
        let instruction = decode::decode(word)?;
        self.retire_decoded(bus, instruction_pc, instruction)
    }

    pub(crate) fn retire_decoded(
        &mut self,
        bus: &mut dyn MemoryBus,
        instruction_pc: u32,
        instruction: DecodedInstruction,
    ) -> Result<Option<Rv32imStop>, String> {
        let previous_pc = self.pc;
        let stop = match self.execute_decoded(bus, instruction_pc, instruction) {
            Ok(stop) => stop,
            Err(error) => {
                self.pc = previous_pc;
                return Err(error);
            }
        };
        self.commit_instruction();
        Ok(stop)
    }

    pub(crate) fn execute_decoded(
        &mut self,
        bus: &mut dyn MemoryBus,
        instruction_pc: u32,
        instruction: DecodedInstruction,
    ) -> Result<Option<Rv32imStop>, String> {
        self.execute_decoded_typed(bus, instruction_pc, instruction)
            .map_err(|error| error.to_string())
    }

    pub(crate) fn execute_decoded_typed(
        &mut self,
        bus: &mut dyn MemoryBus,
        instruction_pc: u32,
        instruction: DecodedInstruction,
    ) -> Result<Option<Rv32imStop>, Rv32RegularFault> {
        let previous_pc = self.pc;
        match self.execute_decoded_unchecked(bus, instruction_pc, instruction) {
            Ok(stop) => Ok(stop),
            Err(error) => {
                self.pc = previous_pc;
                Err(error)
            }
        }
    }

    fn execute_decoded_unchecked(
        &mut self,
        bus: &mut dyn MemoryBus,
        instruction_pc: u32,
        instruction: DecodedInstruction,
    ) -> Result<Option<Rv32imStop>, Rv32RegularFault> {
        let next_pc = instruction_pc.wrapping_add(4);
        self.pc = next_pc;
        match instruction {
            DecodedInstruction::Lui { rd, value } => self.registers[rd] = value,
            DecodedInstruction::Auipc { rd, value } => {
                self.registers[rd] = instruction_pc.wrapping_add(value)
            }
            DecodedInstruction::Jal { rd, offset } => {
                let target = checked_target(instruction_pc.wrapping_add_signed(offset))?;
                self.registers[rd] = next_pc;
                self.pc = target;
            }
            DecodedInstruction::Jalr { rd, rs1, immediate } => {
                let target = self.registers[rs1].wrapping_add_signed(immediate) & !1;
                let target = checked_target(target)?;
                self.registers[rd] = next_pc;
                self.pc = target;
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
                    Branch::Lt => (lhs as i32) < (rhs as i32),
                    Branch::Ge => (lhs as i32) >= (rhs as i32),
                    Branch::Ltu => lhs < rhs,
                    Branch::Geu => lhs >= rhs,
                };
                if take {
                    self.pc = checked_target(instruction_pc.wrapping_add_signed(offset))?;
                }
            }
            DecodedInstruction::Load {
                kind,
                rd,
                rs1,
                immediate,
            } => {
                let address = self.registers[rs1].wrapping_add_signed(immediate);
                self.registers[rd] = match kind {
                    Load::Byte => bus
                        .load_u8(address)
                        .map_err(|error| load_access_fault(address, error))?
                        as i8 as i32 as u32,
                    Load::Half => {
                        require_load_alignment(address, 2)?;
                        bus.load_u16(address)
                            .map_err(|error| load_access_fault(address, error))?
                            as i16 as i32 as u32
                    }
                    Load::Word => {
                        require_load_alignment(address, 4)?;
                        bus.load_i32(address)
                            .map_err(|error| load_access_fault(address, error))?
                            as u32
                    }
                    Load::ByteU => u32::from(
                        bus.load_u8(address)
                            .map_err(|error| load_access_fault(address, error))?,
                    ),
                    Load::HalfU => {
                        require_load_alignment(address, 2)?;
                        u32::from(
                            bus.load_u16(address)
                                .map_err(|error| load_access_fault(address, error))?,
                        )
                    }
                };
            }
            DecodedInstruction::Store {
                kind,
                rs1,
                rs2,
                immediate,
            } => {
                let address = self.registers[rs1].wrapping_add_signed(immediate);
                let value = self.registers[rs2];
                let size = match kind {
                    Store::Byte => 1,
                    Store::Half => 2,
                    Store::Word => 4,
                };
                match kind {
                    Store::Byte => bus.store_u8(address, value as u8),
                    Store::Half => {
                        require_store_alignment(address, 2)?;
                        bus.store_u16(address, value as u16)
                    }
                    Store::Word => {
                        require_store_alignment(address, 4)?;
                        bus.store_i32(address, value as i32)
                    }
                }
                .map_err(|error| store_access_fault(address, error))?;
                self.invalidate_reservation(address, size);
            }
            DecodedInstruction::Immediate {
                op,
                rd,
                rs1,
                immediate,
            } => {
                let lhs = self.registers[rs1];
                self.registers[rd] = match op {
                    ImmOp::Add => lhs.wrapping_add_signed(immediate),
                    ImmOp::Slt => u32::from((lhs as i32) < immediate),
                    ImmOp::Sltu => u32::from(lhs < immediate as u32),
                    ImmOp::Xor => lhs ^ immediate as u32,
                    ImmOp::Or => lhs | immediate as u32,
                    ImmOp::And => lhs & immediate as u32,
                    ImmOp::Sll => lhs.wrapping_shl(immediate as u32 & 31),
                    ImmOp::Srl => lhs.wrapping_shr(immediate as u32 & 31),
                    ImmOp::Sra => ((lhs as i32) >> (immediate as u32 & 31)) as u32,
                };
            }
            DecodedInstruction::Register { op, rd, rs1, rs2 } => {
                let lhs = self.registers[rs1];
                let rhs = self.registers[rs2];
                self.registers[rd] = execute_op(op, lhs, rhs);
            }
            DecodedInstruction::Fence | DecodedInstruction::FenceI => {}
            DecodedInstruction::LoadReserved { rd, rs1, ordering } => {
                let _ordering = ordering;
                let address = self.registers[rs1];
                self.clear_reservation();
                require_load_alignment(address, 4)?;
                let value = bus
                    .atomic_load_i32(address)
                    .map_err(|error| load_access_fault(address, error))?
                    as u32;
                self.reservation = Some(Rv32Reservation::new(address));
                self.registers[rd] = value;
            }
            DecodedInstruction::StoreConditional {
                rd,
                rs1,
                rs2,
                ordering,
            } => {
                let _ordering = ordering;
                let address = self.registers[rs1];
                let value = self.registers[rs2];
                let reservation = self.reservation.take();
                require_store_alignment(address, 4)?;
                bus.validate_atomic_i32(address, AtomicWordAccess::Store)
                    .map_err(|error| store_access_fault(address, error))?;
                if reservation.is_some_and(|reservation| reservation.matches(address)) {
                    bus.atomic_store_i32(address, value as i32)
                        .map_err(|error| store_access_fault(address, error))?;
                    self.registers[rd] = 0;
                } else {
                    self.registers[rd] = 1;
                }
            }
            DecodedInstruction::Atomic {
                operation,
                rd,
                rs1,
                rs2,
                ordering,
            } => {
                let _ordering = ordering;
                let address = self.registers[rs1];
                let operand = self.registers[rs2];
                require_store_alignment(address, 4)?;
                let mut update = |old: i32| apply_atomic(operation, old as u32, operand) as i32;
                let old = bus
                    .atomic_update_i32(address, &mut update)
                    .map_err(|error| store_access_fault(address, error))?
                    as u32;
                self.invalidate_reservation(address, 4);
                self.registers[rd] = old;
            }
            DecodedInstruction::Csr { .. } => {
                return Err(Rv32RegularFault::MachineInstructionRequired);
            }
            DecodedInstruction::Ecall => return Ok(Some(Rv32imStop::Ecall)),
            DecodedInstruction::Ebreak => return Ok(Some(Rv32imStop::Ebreak)),
            DecodedInstruction::Mret => {
                return Err(Rv32RegularFault::MachineInstructionRequired);
            }
        }
        Ok(None)
    }
}

#[cfg(test)]
pub(crate) fn decode_word(word: u32) -> Result<DecodedInstruction, String> {
    decode::decode(word)
}

fn execute_op(op: Op, lhs: u32, rhs: u32) -> u32 {
    match op {
        Op::Add => lhs.wrapping_add(rhs),
        Op::Sub => lhs.wrapping_sub(rhs),
        Op::Sll => lhs.wrapping_shl(rhs & 31),
        Op::Slt => u32::from((lhs as i32) < (rhs as i32)),
        Op::Sltu => u32::from(lhs < rhs),
        Op::Xor => lhs ^ rhs,
        Op::Srl => lhs.wrapping_shr(rhs & 31),
        Op::Sra => ((lhs as i32) >> (rhs & 31)) as u32,
        Op::Or => lhs | rhs,
        Op::And => lhs & rhs,
        Op::Mul => lhs.wrapping_mul(rhs),
        Op::Mulh => (((lhs as i32 as i64) * (rhs as i32 as i64)) >> 32) as u32,
        Op::Mulhsu => (((lhs as i32 as i64 as i128) * (rhs as i128)) >> 32) as u32,
        Op::Mulhu => ((u64::from(lhs) * u64::from(rhs)) >> 32) as u32,
        Op::Div => signed_div(lhs, rhs),
        Op::Divu => {
            if rhs == 0 {
                u32::MAX
            } else {
                lhs / rhs
            }
        }
        Op::Rem => signed_rem(lhs, rhs),
        Op::Remu => {
            if rhs == 0 {
                lhs
            } else {
                lhs % rhs
            }
        }
    }
}
fn signed_div(lhs: u32, rhs: u32) -> u32 {
    let a = lhs as i32;
    let b = rhs as i32;
    if b == 0 {
        u32::MAX
    } else if a == i32::MIN && b == -1 {
        a as u32
    } else {
        (a / b) as u32
    }
}
fn signed_rem(lhs: u32, rhs: u32) -> u32 {
    let a = lhs as i32;
    let b = rhs as i32;
    if b == 0 {
        lhs
    } else if a == i32::MIN && b == -1 {
        0
    } else {
        (a % b) as u32
    }
}
fn checked_target(target: u32) -> Result<u32, Rv32RegularFault> {
    if target.is_multiple_of(4) {
        Ok(target)
    } else {
        Err(Rv32RegularFault::InstructionAddressMisaligned { address: target })
    }
}
fn require_load_alignment(address: u32, alignment: u32) -> Result<(), Rv32RegularFault> {
    if address.is_multiple_of(alignment) {
        Ok(())
    } else {
        Err(Rv32RegularFault::LoadAddressMisaligned { address })
    }
}
fn require_store_alignment(address: u32, alignment: u32) -> Result<(), Rv32RegularFault> {
    if address.is_multiple_of(alignment) {
        Ok(())
    } else {
        Err(Rv32RegularFault::StoreAddressMisaligned { address })
    }
}
fn load_access_fault(address: u32, error: crate::memory::MemoryFault) -> Rv32RegularFault {
    Rv32RegularFault::LoadAccessFault {
        address: error.address().unwrap_or(address),
        detail: error.to_string(),
    }
}
fn store_access_fault(address: u32, error: crate::memory::MemoryFault) -> Rv32RegularFault {
    Rv32RegularFault::StoreAccessFault {
        address: error.address().unwrap_or(address),
        detail: error.to_string(),
    }
}
fn require_alignment(address: u32, alignment: u32, access: &str) -> Result<(), String> {
    if address.is_multiple_of(alignment) {
        Ok(())
    } else {
        Err(format!(
            "misaligned RV32IM {access} address {address:#010x}"
        ))
    }
}

#[cfg(test)]
mod typed_fault_tests {
    use super::{decode, encoding, Rv32RegularFault, Rv32imCpu};
    use crate::bus::MachineBus;

    #[test]
    fn typed_execution_fault_preserves_precise_cpu_state() {
        let mut bus = MachineBus::new(32).unwrap();
        let mut cpu = Rv32imCpu::new(0);
        cpu.set_register(1, 3).unwrap();
        cpu.set_register(2, 0xfeed_beef).unwrap();
        let instruction = decode::decode(encoding::lw(2, 1, 0)).unwrap();

        assert_eq!(
            cpu.execute_decoded_typed(&mut bus, 0, instruction),
            Err(Rv32RegularFault::LoadAddressMisaligned { address: 3 })
        );
        assert_eq!(cpu.pc(), 0);
        assert_eq!(cpu.register(2), 0xfeed_beef);
        assert_eq!(cpu.retired_instructions(), 0);
    }
}
