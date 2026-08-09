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

mod decode;
pub mod encoding;
mod predecode;

pub use predecode::PredecodedK16F32Program;

use crate::low_machine::MemoryBus;
use decode::DecodedInstruction;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum K16F32Stop {
    Halt,
    Yield,
    StepLimit,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct K16F32Cpu {
    pc: u32,
    registers: [u32; 16],
    retired_instructions: u64,
}

impl K16F32Cpu {
    pub fn new(pc: u32) -> Self {
        Self {
            pc,
            registers: [0; 16],
            retired_instructions: 0,
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
    pub fn retired_instructions(&self) -> u64 {
        self.retired_instructions
    }

    pub fn run_until_stop(
        &mut self,
        bus: &mut dyn MemoryBus,
        max_steps: u64,
    ) -> Result<K16F32Stop, String> {
        for _ in 0..max_steps {
            if let Some(stop) = self.step(bus)? {
                return Ok(stop);
            }
        }
        Ok(K16F32Stop::StepLimit)
    }

    pub fn step(&mut self, bus: &mut dyn MemoryBus) -> Result<Option<K16F32Stop>, String> {
        if !self.pc.is_multiple_of(4) {
            return Err(format!(
                "misaligned K16-F32 instruction PC {:#010x}",
                self.pc
            ));
        }
        let instruction_pc = self.pc;
        let word = bus.load_i32(self.pc).map_err(|error| error.to_string())? as u32;
        let instruction = decode::decode(word)?;
        self.retire_decoded(bus, instruction_pc, instruction)
    }

    #[inline(always)]
    pub(crate) fn retire_decoded(
        &mut self,
        bus: &mut dyn MemoryBus,
        instruction_pc: u32,
        instruction: DecodedInstruction,
    ) -> Result<Option<K16F32Stop>, String> {
        let next_pc = self.pc.wrapping_add(4);
        self.pc = next_pc;
        let stop = self.execute(bus, instruction_pc, next_pc, instruction)?;
        self.retired_instructions = self.retired_instructions.saturating_add(1);
        Ok(stop)
    }

    #[inline(always)]
    fn execute(
        &mut self,
        bus: &mut dyn MemoryBus,
        instruction_pc: u32,
        next_pc: u32,
        instruction: DecodedInstruction,
    ) -> Result<Option<K16F32Stop>, String> {
        let address = |base: usize, offset: i32| self.registers[base].wrapping_add_signed(offset);
        let relative = |offset: i32| next_pc.wrapping_add_signed(offset.wrapping_mul(4));
        match instruction {
            DecodedInstruction::Nop => {}
            DecodedInstruction::Halt => return Ok(Some(K16F32Stop::Halt)),
            DecodedInstruction::Yield => return Ok(Some(K16F32Stop::Yield)),
            DecodedInstruction::Lui { dst, immediate } => self.registers[dst] = immediate << 12,
            DecodedInstruction::AddI {
                dst,
                src,
                immediate,
            } => {
                self.registers[dst] = self.registers[src].wrapping_add_signed(immediate);
            }
            DecodedInstruction::Add { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs].wrapping_add(self.registers[rhs])
            }
            DecodedInstruction::Sub { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs].wrapping_sub(self.registers[rhs])
            }
            DecodedInstruction::Mul { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs].wrapping_mul(self.registers[rhs])
            }
            DecodedInstruction::And { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs] & self.registers[rhs]
            }
            DecodedInstruction::Or { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs] | self.registers[rhs]
            }
            DecodedInstruction::Xor { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs] ^ self.registers[rhs]
            }
            DecodedInstruction::Shl { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs].wrapping_shl(self.registers[rhs] & 31)
            }
            DecodedInstruction::Shr { dst, lhs, rhs } => {
                self.registers[dst] = self.registers[lhs].wrapping_shr(self.registers[rhs] & 31)
            }
            DecodedInstruction::Sar { dst, lhs, rhs } => {
                self.registers[dst] =
                    ((self.registers[lhs] as i32) >> (self.registers[rhs] & 31)) as u32
            }
            DecodedInstruction::Eq { dst, lhs, rhs } => {
                self.registers[dst] = u32::from(self.registers[lhs] == self.registers[rhs])
            }
            DecodedInstruction::Ne { dst, lhs, rhs } => {
                self.registers[dst] = u32::from(self.registers[lhs] != self.registers[rhs])
            }
            DecodedInstruction::Ltu { dst, lhs, rhs } => {
                self.registers[dst] = u32::from(self.registers[lhs] < self.registers[rhs])
            }
            DecodedInstruction::LtS { dst, lhs, rhs } => {
                self.registers[dst] =
                    u32::from((self.registers[lhs] as i32) < (self.registers[rhs] as i32))
            }
            DecodedInstruction::Load8 { dst, base, offset } => {
                self.registers[dst] = u32::from(
                    bus.load_u8(address(base, offset))
                        .map_err(|error| error.to_string())?,
                )
            }
            DecodedInstruction::Load16 { dst, base, offset } => {
                let address = address(base, offset);
                require_alignment(address, 2, instruction_pc)?;
                self.registers[dst] =
                    u32::from(bus.load_u16(address).map_err(|error| error.to_string())?);
            }
            DecodedInstruction::Load32 { dst, base, offset } => {
                let address = address(base, offset);
                require_alignment(address, 4, instruction_pc)?;
                self.registers[dst] =
                    bus.load_i32(address).map_err(|error| error.to_string())? as u32;
            }
            DecodedInstruction::Store8 { base, src, offset } => bus
                .store_u8(address(base, offset), self.registers[src] as u8)
                .map_err(|error| error.to_string())?,
            DecodedInstruction::Store16 { base, src, offset } => {
                let address = address(base, offset);
                require_alignment(address, 2, instruction_pc)?;
                bus.store_u16(address, self.registers[src] as u16)
                    .map_err(|error| error.to_string())?;
            }
            DecodedInstruction::Store32 { base, src, offset } => {
                let address = address(base, offset);
                require_alignment(address, 4, instruction_pc)?;
                bus.store_i32(address, self.registers[src] as i32)
                    .map_err(|error| error.to_string())?;
            }
            DecodedInstruction::BranchZ { src, offset } => {
                if self.registers[src] == 0 {
                    self.pc = relative(offset);
                }
            }
            DecodedInstruction::BranchNz { src, offset } => {
                if self.registers[src] != 0 {
                    self.pc = relative(offset);
                }
            }
            DecodedInstruction::Jump { offset } => self.pc = relative(offset),
            DecodedInstruction::Call { offset } => {
                self.registers[15] = self.registers[15].wrapping_sub(4);
                require_alignment(self.registers[15], 4, instruction_pc)?;
                bus.store_i32(self.registers[15], next_pc as i32)
                    .map_err(|error| error.to_string())?;
                self.pc = relative(offset);
            }
            DecodedInstruction::Ret => {
                require_alignment(self.registers[15], 4, instruction_pc)?;
                self.pc = bus
                    .load_i32(self.registers[15])
                    .map_err(|error| error.to_string())? as u32;
                self.registers[15] = self.registers[15].wrapping_add(4);
            }
        }
        Ok(None)
    }
}

fn require_alignment(address: u32, alignment: u32, pc: u32) -> Result<(), String> {
    if address.is_multiple_of(alignment) {
        Ok(())
    } else {
        Err(format!(
            "misaligned K16-F32 access {address:#010x} at PC {pc:#010x}"
        ))
    }
}
