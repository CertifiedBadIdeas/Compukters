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
use crate::memory::{MemoryBus, MemoryFault};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Rv32imCacheStats {
    pub hits: u64,
    pub misses: u64,
    pub evictions: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct CacheEntry {
    pc: u32,
    word: u32,
    instruction: DecodedInstruction,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
struct CacheSet {
    ways: [Option<CacheEntry>; 2],
    next_victim: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BoundedCachedRv32imProgram {
    sets: Vec<CacheSet>,
    stats: Rv32imCacheStats,
}

impl BoundedCachedRv32imProgram {
    pub fn new(set_count: usize) -> Result<Self, String> {
        if !set_count.is_power_of_two() {
            return Err(format!(
                "RV32IM bounded cache set count {set_count} is not a positive power of two"
            ));
        }
        Ok(Self {
            sets: vec![CacheSet::default(); set_count],
            stats: Rv32imCacheStats::default(),
        })
    }

    pub fn capacity(&self) -> usize {
        self.sets.len() * 2
    }

    pub fn retained_bytes(&self) -> usize {
        self.sets.capacity() * std::mem::size_of::<CacheSet>()
    }

    pub fn stats(&self) -> Rv32imCacheStats {
        self.stats
    }

    pub fn step(
        &mut self,
        cpu: &mut Rv32imCpu,
        bus: &mut dyn MemoryBus,
    ) -> Result<Option<Rv32imStop>, String> {
        let instruction_pc = cpu.pc();
        if !instruction_pc.is_multiple_of(4) {
            return Err(format!(
                "misaligned RV32IM bounded-cache instruction address {instruction_pc:#010x}"
            ));
        }
        match self
            .resolve(instruction_pc, bus)
            .map_err(|error| error.to_string())?
        {
            Rv32ResolvedInstruction::Valid { instruction, .. } => {
                cpu.retire_decoded(bus, instruction_pc, instruction)
            }
            Rv32ResolvedInstruction::Invalid { word } => {
                Err(format!("illegal RV32IM instruction {word:#010x}"))
            }
        }
    }

    pub(crate) fn resolve(
        &mut self,
        instruction_pc: u32,
        bus: &dyn MemoryBus,
    ) -> Result<Rv32ResolvedInstruction, MemoryFault> {
        self.resolve_with_decoder(instruction_pc, bus, decode)
    }

    pub(crate) fn resolve_with_decoder<D>(
        &mut self,
        instruction_pc: u32,
        bus: &dyn MemoryBus,
        decoder: D,
    ) -> Result<Rv32ResolvedInstruction, MemoryFault>
    where
        D: FnOnce(u32) -> Result<DecodedInstruction, String>,
    {
        let set_index = ((instruction_pc >> 2) as usize) & (self.sets.len() - 1);
        if let Some(entry) = self.sets[set_index]
            .ways
            .iter()
            .flatten()
            .find(|entry| entry.pc == instruction_pc)
            .copied()
        {
            self.stats.hits = self.stats.hits.saturating_add(1);
            return Ok(Rv32ResolvedInstruction::Valid {
                word: entry.word,
                instruction: entry.instruction,
            });
        }

        self.stats.misses = self.stats.misses.saturating_add(1);
        let word = bus.load_i32(instruction_pc)? as u32;
        let instruction = match decoder(word) {
            Ok(instruction) => instruction,
            Err(_) => return Ok(Rv32ResolvedInstruction::Invalid { word }),
        };
        let set = &mut self.sets[set_index];
        let way = match set.ways.iter().position(Option::is_none) {
            Some(empty) => empty,
            None => {
                let victim = set.next_victim;
                set.next_victim ^= 1;
                self.stats.evictions = self.stats.evictions.saturating_add(1);
                victim
            }
        };
        set.ways[way] = Some(CacheEntry {
            pc: instruction_pc,
            word,
            instruction,
        });
        Ok(Rv32ResolvedInstruction::Valid { word, instruction })
    }

    pub(crate) fn reset_for_benchmark(&mut self) {
        for set in &mut self.sets {
            *set = CacheSet::default();
        }
        self.stats = Rv32imCacheStats::default();
    }
}
