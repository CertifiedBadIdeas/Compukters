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

use super::{decode_product_word, DecodedInstruction, Rv32ResolvedInstruction};
use crate::memory::{MemoryBus, MemoryFault};

const WAYS_PER_SET: usize = 2;
const PAGE_BYTES: u32 = 4096;
const MAX_BLOCK_INSTRUCTIONS: usize = 64;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Rv32BlockCacheStats {
    pub hits: u64,
    pub misses: u64,
    pub evictions: u64,
    pub blocks_built: u64,
    pub decoded_slots_built: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct DecodedBlockWay {
    start_pc: u32,
    valid: bool,
    slots: Vec<Rv32ResolvedInstruction>,
}

impl DecodedBlockWay {
    fn new(max_instructions: usize) -> Self {
        Self {
            start_pc: 0,
            valid: false,
            slots: Vec::with_capacity(max_instructions),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct BlockCacheSet {
    ways: [DecodedBlockWay; WAYS_PER_SET],
    next_victim: usize,
}

impl BlockCacheSet {
    fn new(max_instructions: usize) -> Self {
        Self {
            ways: std::array::from_fn(|_| DecodedBlockWay::new(max_instructions)),
            next_victim: 0,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct BoundedDecodedBlockCache {
    sets: Vec<BlockCacheSet>,
    max_instructions: usize,
    stats: Rv32BlockCacheStats,
}

impl BoundedDecodedBlockCache {
    pub(crate) fn new(set_count: usize, max_instructions: usize) -> Result<Self, String> {
        if !set_count.is_power_of_two() {
            return Err(format!(
                "RV32 decoded block cache set count {set_count} is not a positive power of two"
            ));
        }
        if !(1..=MAX_BLOCK_INSTRUCTIONS).contains(&max_instructions) {
            return Err(format!(
                "RV32 decoded block cache maximum instruction count {max_instructions} is outside 1..={MAX_BLOCK_INSTRUCTIONS}"
            ));
        }
        Ok(Self {
            sets: (0..set_count)
                .map(|_| BlockCacheSet::new(max_instructions))
                .collect(),
            max_instructions,
            stats: Rv32BlockCacheStats::default(),
        })
    }

    pub(crate) fn resolve(
        &mut self,
        start_pc: u32,
        executable_end: u32,
        bus: &dyn MemoryBus,
    ) -> Result<&[Rv32ResolvedInstruction], MemoryFault> {
        let set_index = ((start_pc >> 2) as usize) & (self.sets.len() - 1);
        if let Some(way_index) = self.sets[set_index]
            .ways
            .iter()
            .position(|way| way.valid && way.start_pc == start_pc)
        {
            self.stats.hits = self.stats.hits.saturating_add(1);
            return Ok(&self.sets[set_index].ways[way_index].slots);
        }

        self.stats.misses = self.stats.misses.saturating_add(1);
        let page_end = (start_pc & !(PAGE_BYTES - 1))
            .checked_add(PAGE_BYTES)
            .unwrap_or(u32::MAX);
        let block_end = executable_end.min(page_end);
        if start_pc
            .checked_add(4)
            .is_none_or(|instruction_end| instruction_end > block_end)
        {
            return Err(MemoryFault::at(
                start_pc,
                format!(
                    "RV32 instruction at {start_pc:#010x} crosses decoded block boundary {block_end:#010x}"
                ),
            ));
        }

        let first = resolve_slot(start_pc, bus)?;
        let set = &mut self.sets[set_index];
        let way_index = match set.ways.iter().position(|way| !way.valid) {
            Some(empty) => empty,
            None => {
                let victim = set.next_victim;
                set.next_victim ^= 1;
                self.stats.evictions = self.stats.evictions.saturating_add(1);
                victim
            }
        };
        let way = &mut set.ways[way_index];
        way.start_pc = start_pc;
        way.valid = true;
        way.slots.clear();
        way.slots.push(first);

        let mut next_pc = start_pc.wrapping_add(4);
        while way.slots.len() < self.max_instructions
            && !ends_basic_block(*way.slots.last().unwrap())
        {
            if next_pc
                .checked_add(4)
                .is_none_or(|instruction_end| instruction_end > block_end)
            {
                break;
            }
            match resolve_slot(next_pc, bus) {
                Ok(slot) => way.slots.push(slot),
                Err(_) => break,
            }
            next_pc = next_pc.wrapping_add(4);
        }

        self.stats.blocks_built = self.stats.blocks_built.saturating_add(1);
        self.stats.decoded_slots_built = self
            .stats
            .decoded_slots_built
            .saturating_add(way.slots.len() as u64);
        Ok(&way.slots)
    }

    pub(crate) fn stats(&self) -> Rv32BlockCacheStats {
        self.stats
    }

    pub(crate) fn retained_bytes(&self) -> usize {
        self.sets.capacity() * std::mem::size_of::<BlockCacheSet>()
            + self
                .sets
                .iter()
                .flat_map(|set| &set.ways)
                .map(|way| way.slots.capacity() * std::mem::size_of::<Rv32ResolvedInstruction>())
                .sum::<usize>()
    }
}

fn resolve_slot(
    instruction_pc: u32,
    bus: &dyn MemoryBus,
) -> Result<Rv32ResolvedInstruction, MemoryFault> {
    let word = bus.load_i32(instruction_pc)? as u32;
    Ok(match decode_product_word(word) {
        Ok(instruction) => Rv32ResolvedInstruction::Valid { word, instruction },
        Err(_) => Rv32ResolvedInstruction::Invalid { word },
    })
}

pub(crate) const fn ends_basic_block(slot: Rv32ResolvedInstruction) -> bool {
    matches!(
        slot,
        Rv32ResolvedInstruction::Invalid { .. }
            | Rv32ResolvedInstruction::Valid {
                instruction: DecodedInstruction::Jal { .. }
                    | DecodedInstruction::Jalr { .. }
                    | DecodedInstruction::Branch { .. }
                    | DecodedInstruction::Ecall
                    | DecodedInstruction::Ebreak
                    | DecodedInstruction::Mret
                    | DecodedInstruction::FenceI,
                ..
            }
    )
}

#[cfg(test)]
mod tests {
    use super::{ends_basic_block, BoundedDecodedBlockCache, Rv32BlockCacheStats};
    use crate::memory::MachineMemory;
    use crate::rv32im::encoding::{
        addi, beq, csrrw, ebreak, ecall, fence, fence_i, jal, jalr, lr_w, lw, mret, sw,
    };
    use crate::rv32im::Rv32ResolvedInstruction;

    fn memory(words: &[u32]) -> MachineMemory {
        let mut memory = MachineMemory::zeroed(words.len().max(1) * 4).unwrap();
        for (index, word) in words.iter().copied().enumerate() {
            memory.store_i32(index as u32 * 4, word as i32).unwrap();
        }
        memory
    }

    #[test]
    fn constructor_rejects_invalid_geometry() {
        assert!(BoundedDecodedBlockCache::new(0, 8).is_err());
        assert!(BoundedDecodedBlockCache::new(3, 8).is_err());
        assert!(BoundedDecodedBlockCache::new(4, 0).is_err());
        assert!(BoundedDecodedBlockCache::new(4, 65).is_err());
        assert!(BoundedDecodedBlockCache::new(4, 64).is_ok());
    }

    #[test]
    fn cache_builds_bounded_prefixes_and_reuses_preallocated_ways() {
        let memory = memory(&[addi(1, 1, 1), addi(2, 2, 1), jal(0, 0)]);
        let mut cache = BoundedDecodedBlockCache::new(1, 8).unwrap();
        let retained = cache.retained_bytes();

        assert_eq!(cache.resolve(0, 12, &memory).unwrap().len(), 3);
        assert_eq!(cache.resolve(0, 12, &memory).unwrap().len(), 3);
        assert_eq!(
            cache.stats(),
            Rv32BlockCacheStats {
                hits: 1,
                misses: 1,
                evictions: 0,
                blocks_built: 1,
                decoded_slots_built: 3,
            }
        );
        assert_eq!(cache.retained_bytes(), retained);
    }

    #[test]
    fn construction_stops_at_maximum_page_and_executable_boundaries() {
        let words = vec![addi(1, 1, 1); 1025];
        let memory = memory(&words);

        let mut max_cache = BoundedDecodedBlockCache::new(1, 2).unwrap();
        assert_eq!(max_cache.resolve(0, 16, &memory).unwrap().len(), 2);

        let mut range_cache = BoundedDecodedBlockCache::new(1, 8).unwrap();
        assert_eq!(range_cache.resolve(0, 4, &memory).unwrap().len(), 1);

        let mut page_cache = BoundedDecodedBlockCache::new(1, 8).unwrap();
        assert_eq!(
            page_cache.resolve(0x0ffc, 0x1004, &memory).unwrap().len(),
            1
        );
    }

    #[test]
    fn every_control_system_and_invalid_terminator_is_included() {
        for word in [
            jal(0, 0),
            jalr(0, 1, 0),
            beq(1, 2, 4),
            ecall(),
            ebreak(),
            mret(),
            fence_i(),
            0xffff_ffff,
        ] {
            let memory = memory(&[word, addi(1, 1, 1)]);
            let mut cache = BoundedDecodedBlockCache::new(1, 8).unwrap();
            let block = cache.resolve(0, 8, &memory).unwrap();
            assert_eq!(block.len(), 1, "word {word:#010x}");
            assert!(ends_basic_block(block[0]));
        }
    }

    #[test]
    fn fence_csr_memory_and_atomic_slots_do_not_terminate_statically() {
        let words = [
            fence(),
            csrrw(1, 0x340, 2),
            lw(1, 2, 0),
            sw(2, 1, 0),
            lr_w(1, 2, false, false),
            jal(0, 0),
        ];
        let memory = memory(&words);
        let mut cache = BoundedDecodedBlockCache::new(1, 8).unwrap();

        let block = cache.resolve(0, words.len() as u32 * 4, &memory).unwrap();

        assert_eq!(block.len(), words.len());
        assert!(block[..block.len() - 1]
            .iter()
            .copied()
            .all(|slot| !ends_basic_block(slot)));
        assert!(ends_basic_block(*block.last().unwrap()));
    }

    #[test]
    fn invalid_words_are_cached_and_two_way_eviction_is_deterministic() {
        let memory = memory(&[0xffff_ffff, jal(0, 0), jal(0, 0)]);
        let mut cache = BoundedDecodedBlockCache::new(1, 1).unwrap();

        assert!(matches!(
            cache.resolve(0, 12, &memory).unwrap()[0],
            Rv32ResolvedInstruction::Invalid { word: 0xffff_ffff }
        ));
        cache.resolve(0, 12, &memory).unwrap();
        cache.resolve(4, 12, &memory).unwrap();
        cache.resolve(8, 12, &memory).unwrap();
        cache.resolve(0, 12, &memory).unwrap();

        assert_eq!(cache.stats().hits, 1);
        assert_eq!(cache.stats().misses, 4);
        assert_eq!(cache.stats().evictions, 2);
    }

    #[test]
    fn entry_fetch_fault_is_precise_and_later_fault_caches_only_valid_prefix() {
        let memory = memory(&[addi(1, 1, 1)]);
        let mut cache = BoundedDecodedBlockCache::new(1, 8).unwrap();

        let entry_fault = cache.resolve(4, 8, &memory).unwrap_err();
        assert_eq!(entry_fault.address(), Some(4));
        assert_eq!(cache.stats().blocks_built, 0);

        assert_eq!(cache.resolve(0, 8, &memory).unwrap().len(), 1);
        assert_eq!(cache.resolve(0, 8, &memory).unwrap().len(), 1);
        assert_eq!(cache.stats().blocks_built, 1);
        assert_eq!(cache.stats().hits, 1);
    }
}
