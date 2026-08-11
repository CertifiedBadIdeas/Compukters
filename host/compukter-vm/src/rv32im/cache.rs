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

use super::decode::{decode, DecodedInstruction};
use super::{Rv32imCpu, Rv32imStop};
use crate::memory::MemoryBus;
use std::collections::HashMap;

#[derive(Debug, Clone, Default)]
pub struct CachedRv32imProgram {
    instructions: HashMap<u32, DecodedInstruction>,
    misses: u64,
}

impl CachedRv32imProgram {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn misses(&self) -> u64 {
        self.misses
    }

    pub fn retained_bytes(&self) -> usize {
        self.instructions.capacity()
            * (std::mem::size_of::<u32>() + std::mem::size_of::<DecodedInstruction>())
    }

    pub fn run_until_stop(
        &mut self,
        cpu: &mut Rv32imCpu,
        bus: &mut dyn MemoryBus,
        max_steps: u64,
    ) -> Result<Rv32imStop, String> {
        for _ in 0..max_steps {
            if let Some(stop) = self.step(cpu, bus)? {
                return Ok(stop);
            }
        }
        Ok(Rv32imStop::StepLimit)
    }

    pub fn step(
        &mut self,
        cpu: &mut Rv32imCpu,
        bus: &mut dyn MemoryBus,
    ) -> Result<Option<Rv32imStop>, String> {
        let instruction_pc = cpu.pc();
        if !instruction_pc.is_multiple_of(4) {
            return Err(format!(
                "misaligned RV32IM cached instruction address {instruction_pc:#010x}"
            ));
        }
        let instruction = match self.instructions.get(&instruction_pc).copied() {
            Some(instruction) => instruction,
            None => {
                let word = bus
                    .load_i32(instruction_pc)
                    .map_err(|error| error.to_string())? as u32;
                let instruction = decode(word)?;
                self.instructions.insert(instruction_pc, instruction);
                self.misses = self.misses.saturating_add(1);
                instruction
            }
        };
        cpu.retire_decoded(bus, instruction_pc, instruction)
    }
}
