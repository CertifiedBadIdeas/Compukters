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
use super::{K16F32R32Cpu, K16F32R32Stop};
use crate::low_machine::MemoryBus;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PredecodedK16F32R32Program {
    base: u32,
    instructions: Vec<DecodedInstruction>,
}

impl PredecodedK16F32R32Program {
    pub fn new(base: u32, image: &[u8]) -> Result<Self, String> {
        if !base.is_multiple_of(4) {
            return Err(format!("misaligned K16-F32R32 predecode base {base:#010x}"));
        }
        if !image.len().is_multiple_of(4) {
            return Err(format!(
                "K16-F32R32 predecode image length {} is not a multiple of four",
                image.len()
            ));
        }
        let image_len = u32::try_from(image.len()).map_err(|_| {
            format!(
                "K16-F32R32 predecode image length {} exceeds the address space",
                image.len()
            )
        })?;
        base.checked_add(image_len).ok_or_else(|| {
            format!(
                "K16-F32R32 predecode image at {base:#010x} with length {} wraps the address space",
                image.len()
            )
        })?;
        let mut instructions = Vec::with_capacity(image.len() / 4);
        for (index, bytes) in image.chunks_exact(4).enumerate() {
            let word = u32::from_le_bytes(bytes.try_into().unwrap());
            instructions.push(decode(word).map_err(|error| {
                format!(
                    "K16-F32R32 predecode failed at {:#010x}: {error}",
                    base + index as u32 * 4
                )
            })?);
        }
        Ok(Self { base, instructions })
    }

    pub fn retained_bytes(&self) -> usize {
        self.instructions.capacity() * std::mem::size_of::<DecodedInstruction>()
    }

    pub fn run_until_stop(
        &self,
        cpu: &mut K16F32R32Cpu,
        bus: &mut dyn MemoryBus,
        max_steps: u64,
    ) -> Result<K16F32R32Stop, String> {
        for _ in 0..max_steps {
            if let Some(stop) = self.step(cpu, bus)? {
                return Ok(stop);
            }
        }
        Ok(K16F32R32Stop::StepLimit)
    }

    pub fn step(
        &self,
        cpu: &mut K16F32R32Cpu,
        bus: &mut dyn MemoryBus,
    ) -> Result<Option<K16F32R32Stop>, String> {
        let offset = cpu
            .pc()
            .checked_sub(self.base)
            .ok_or_else(|| format!("K16-F32R32 predecoded PC {:#010x} precedes image", cpu.pc()))?;
        if !offset.is_multiple_of(4) {
            return Err(format!(
                "misaligned K16-F32R32 predecoded PC {:#010x}",
                cpu.pc()
            ));
        }
        let instruction = self
            .instructions
            .get((offset / 4) as usize)
            .copied()
            .ok_or_else(|| {
                format!(
                    "K16-F32R32 predecoded PC {:#010x} is outside image",
                    cpu.pc()
                )
            })?;
        cpu.retire_decoded(bus, cpu.pc(), instruction)
    }
}
