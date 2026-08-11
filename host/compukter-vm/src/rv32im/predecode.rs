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
use super::{Rv32ResolvedInstruction, Rv32imCpu, Rv32imStop};
use crate::memory::MemoryBus;
use std::ops::Range;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PredecodedRv32imProgram {
    base: u32,
    instructions: Vec<DecodedInstruction>,
}

impl PredecodedRv32imProgram {
    pub fn new(base: u32, code: &[u8]) -> Result<Self, String> {
        if !base.is_multiple_of(4) {
            return Err(format!("misaligned RV32IM predecode base {base:#010x}"));
        }
        if !code.len().is_multiple_of(4) {
            return Err(format!(
                "RV32IM predecode image length {} is not a multiple of four",
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
                        "RV32IM predecode failed at {:#010x}: {error}",
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
        &self,
        cpu: &mut Rv32imCpu,
        bus: &mut dyn MemoryBus,
    ) -> Result<Option<Rv32imStop>, String> {
        let offset = cpu
            .pc()
            .checked_sub(self.base)
            .ok_or_else(|| format!("RV32IM predecoded PC {:#010x} precedes image", cpu.pc()))?;
        if !offset.is_multiple_of(4) {
            return Err(format!(
                "misaligned RV32IM predecoded PC {:#010x}",
                cpu.pc(),
            ));
        }
        let instruction = self
            .instructions
            .get(offset as usize / 4)
            .copied()
            .ok_or_else(|| format!("RV32IM predecoded PC {:#010x} is outside image", cpu.pc()))?;
        cpu.retire_decoded(bus, cpu.pc(), instruction)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PredecodedSlot {
    Instruction {
        word: u32,
        instruction: DecodedInstruction,
    },
    Invalid {
        word: u32,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct PredecodedRange {
    start: u32,
    end: u32,
    slots: Vec<PredecodedSlot>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PredecodedRv32imImage {
    ranges: Vec<PredecodedRange>,
}

impl PredecodedRv32imImage {
    pub fn new(memory: &[u8], ranges: &[Range<u32>]) -> Result<Self, String> {
        let mut decoded_ranges = Vec::with_capacity(ranges.len());
        let mut previous_end = None;
        for range in ranges {
            if range.is_empty() {
                return Err(format!(
                    "RV32IM predecode range {:#010x}..{:#010x} is empty",
                    range.start, range.end
                ));
            }
            if !range.start.is_multiple_of(4) || !range.end.is_multiple_of(4) {
                return Err(format!(
                    "RV32IM predecode range {:#010x}..{:#010x} is not four-byte aligned",
                    range.start, range.end
                ));
            }
            if previous_end.is_some_and(|end| range.start < end) {
                return Err(format!(
                    "RV32IM predecode range {:#010x}..{:#010x} is unsorted or overlapping",
                    range.start, range.end
                ));
            }
            let bytes = memory
                .get(range.start as usize..range.end as usize)
                .ok_or_else(|| {
                    format!(
                        "RV32IM predecode range {:#010x}..{:#010x} exceeds {} bytes",
                        range.start,
                        range.end,
                        memory.len()
                    )
                })?;
            let slots = bytes
                .chunks_exact(4)
                .map(|bytes| {
                    let word = u32::from_le_bytes(bytes.try_into().unwrap());
                    match decode(word) {
                        Ok(instruction) => PredecodedSlot::Instruction { word, instruction },
                        Err(_) => PredecodedSlot::Invalid { word },
                    }
                })
                .collect();
            decoded_ranges.push(PredecodedRange {
                start: range.start,
                end: range.end,
                slots,
            });
            previous_end = Some(range.end);
        }
        if decoded_ranges.is_empty() {
            return Err("RV32IM predecode image has no executable ranges".to_string());
        }
        Ok(Self {
            ranges: decoded_ranges,
        })
    }

    pub fn retained_bytes(&self) -> usize {
        self.ranges.capacity() * std::mem::size_of::<PredecodedRange>()
            + self
                .ranges
                .iter()
                .map(|range| range.slots.capacity() * std::mem::size_of::<PredecodedSlot>())
                .sum::<usize>()
    }

    pub fn step(
        &self,
        cpu: &mut Rv32imCpu,
        bus: &mut dyn MemoryBus,
    ) -> Result<Option<Rv32imStop>, String> {
        let instruction_pc = cpu.pc();
        if !instruction_pc.is_multiple_of(4) {
            return Err(format!(
                "misaligned RV32IM predecoded-image PC {instruction_pc:#010x}"
            ));
        }
        match self.resolve(instruction_pc)? {
            Rv32ResolvedInstruction::Valid { instruction, .. } => {
                cpu.retire_decoded(bus, instruction_pc, instruction)
            }
            Rv32ResolvedInstruction::Invalid { word } => Err(format!(
                "illegal RV32IM instruction {word:#010x} at predecoded PC {instruction_pc:#010x}"
            )),
        }
    }

    pub(crate) fn resolve(&self, instruction_pc: u32) -> Result<Rv32ResolvedInstruction, String> {
        let insertion = self
            .ranges
            .partition_point(|range| range.start <= instruction_pc);
        let range = insertion
            .checked_sub(1)
            .and_then(|index| self.ranges.get(index))
            .filter(|range| instruction_pc < range.end)
            .ok_or_else(|| {
                format!("RV32IM predecoded PC {instruction_pc:#010x} is outside executable ranges")
            })?;
        let slot = range.slots[((instruction_pc - range.start) / 4) as usize];
        match slot {
            PredecodedSlot::Instruction { word, instruction } => {
                Ok(Rv32ResolvedInstruction::Valid { word, instruction })
            }
            PredecodedSlot::Invalid { word } => Ok(Rv32ResolvedInstruction::Invalid { word }),
        }
    }
}
