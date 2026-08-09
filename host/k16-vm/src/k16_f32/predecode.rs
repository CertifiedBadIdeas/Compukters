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
use super::{K16F32Cpu, K16F32Stop};
use crate::low_machine::MemoryBus;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PredecodedK16F32Program {
    base: u32,
    instructions: Vec<DecodedInstruction>,
}

impl PredecodedK16F32Program {
    pub fn new(base: u32, code: &[u8]) -> Result<Self, String> {
        if !base.is_multiple_of(4) {
            return Err(format!("misaligned K16-F32 predecode base {base:#010x}"));
        }
        if !code.len().is_multiple_of(4) {
            return Err(format!(
                "K16-F32 predecode image length {} is not a multiple of four",
                code.len(),
            ));
        }
        let instructions = code
            .chunks_exact(4)
            .enumerate()
            .map(|(index, bytes)| {
                let word = u32::from_le_bytes(bytes.try_into().unwrap());
                decode(word).map_err(|error| {
                    format!(
                        "K16-F32 predecode failed at {:#010x}: {error}",
                        base.wrapping_add(index as u32 * 4),
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
        cpu: &mut K16F32Cpu,
        bus: &mut dyn MemoryBus,
        max_steps: u64,
    ) -> Result<K16F32Stop, String> {
        for _ in 0..max_steps {
            if let Some(stop) = self.step(cpu, bus)? {
                return Ok(stop);
            }
        }
        Ok(K16F32Stop::StepLimit)
    }

    pub fn step(
        &self,
        cpu: &mut K16F32Cpu,
        bus: &mut dyn MemoryBus,
    ) -> Result<Option<K16F32Stop>, String> {
        let offset = cpu
            .pc()
            .checked_sub(self.base)
            .ok_or_else(|| format!("K16-F32 predecoded PC {:#010x} precedes image", cpu.pc()))?;
        if !offset.is_multiple_of(4) {
            return Err(format!(
                "misaligned K16-F32 predecoded PC {:#010x}",
                cpu.pc(),
            ));
        }
        let instruction = self
            .instructions
            .get(offset as usize / 4)
            .copied()
            .ok_or_else(|| format!("K16-F32 predecoded PC {:#010x} is outside image", cpu.pc()))?;
        cpu.retire_decoded(bus, cpu.pc(), instruction)
    }
}
