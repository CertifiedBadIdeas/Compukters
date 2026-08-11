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

use super::hart::{Rv32HartStep, Rv32MachineHart};
use super::platform::{self, ControlDevice, DebugDevice};
use super::{Rv32AddressSpace, Rv32AddressSpaceError, Rv32ElfError, Rv32ElfLoader};
use crate::bus::{MachineBus, MmioDeviceId};
use crate::memory::MemoryFault;
use crate::rv32_jit::abi::JitEntry;
use crate::rv32_jit::arena::{CompiledBlockId, ExecutableCodeArena};
use crate::rv32_jit::block::JitBlockInput;
use crate::rv32_jit::cranelift::CraneliftBackend;
use crate::rv32_jit::planner::{JitPlanner, JitPlannerConfig};
use crate::rv32im::{
    ends_basic_block, BoundedCachedRv32imProgram, BoundedDecodedBlockCache, PredecodedRv32imImage,
    Rv32ResolvedInstruction, Rv32imCacheStats,
};
use std::ops::Range;
use thiserror::Error;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rv32ExecutionBackendConfig {
    Cached {
        sets: usize,
    },
    Predecoded,
    BlockCached {
        sets: usize,
        max_instructions: usize,
    },
    Jit {
        sets: usize,
        max_instructions: usize,
        hotness_threshold: u32,
        candidate_capacity: usize,
        request_capacity: usize,
        code_bytes: usize,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rv32TranslationLookupUnit {
    Instruction,
    Block,
}

impl Rv32TranslationLookupUnit {
    pub const fn name(self) -> &'static str {
        match self {
            Self::Instruction => "instruction",
            Self::Block => "block",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rv32TranslationStats {
    pub lookup_unit: Rv32TranslationLookupUnit,
    pub hits: u64,
    pub misses: u64,
    pub evictions: u64,
    pub blocks_built: u64,
    pub decoded_slots_built: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rv32JitStats {
    pub prepared_blocks: u64,
    pub dispatches: u64,
    pub emitted_bytes: usize,
    pub reserved_bytes: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rv32MachineConfig {
    pub ram_size: usize,
    pub debug_limit: usize,
    pub execution: Rv32ExecutionBackendConfig,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Rv32MachineOutcome {
    BudgetExhausted {
        retired_delta: u64,
        retired_total: u64,
    },
    Halted {
        exit_code: i32,
        retired_delta: u64,
        retired_total: u64,
    },
    Panicked {
        panic_code: i32,
        retired_delta: u64,
        retired_total: u64,
    },
}

#[derive(Debug, Error)]
pub enum Rv32MachineBuildError {
    #[error("invalid RV32 machine configuration: {0}")]
    Config(String),
    #[error(transparent)]
    Elf(#[from] Rv32ElfError),
    #[error(transparent)]
    AddressSpace(#[from] Rv32AddressSpaceError),
    #[error("RV32 machine memory/device construction failed: {0}")]
    Memory(#[from] MemoryFault),
    #[error("RV32 execution backend construction failed: {0}")]
    Backend(String),
}

#[derive(Debug, Clone, PartialEq, Eq, Error)]
#[error(
    "RV32 execution failed at PC {pc:#010x} after {retired_total} retired instructions: {message}"
)]
pub struct Rv32MachineExecutionError {
    pc: u32,
    retired_total: u64,
    message: String,
}

impl Rv32MachineExecutionError {
    pub fn pc(&self) -> u32 {
        self.pc
    }

    pub fn retired_total(&self) -> u64 {
        self.retired_total
    }
}

struct Rv32CompiledJitBlock {
    input: JitBlockInput,
    id: CompiledBlockId,
}

struct Rv32JitExecution {
    cache: BoundedDecodedBlockCache,
    planner: JitPlanner,
    backend: CraneliftBackend,
    arena: ExecutableCodeArena,
    compiled: Vec<Rv32CompiledJitBlock>,
    prepared_blocks: u64,
    dispatches: u64,
}

impl Rv32JitExecution {
    fn new(
        sets: usize,
        max_instructions: usize,
        planner: JitPlannerConfig,
        code_bytes: usize,
    ) -> Result<Self, String> {
        Ok(Self {
            cache: BoundedDecodedBlockCache::new(sets, max_instructions)?,
            planner: JitPlanner::new(planner)?,
            backend: CraneliftBackend::new()?,
            arena: ExecutableCodeArena::new(code_bytes).map_err(|error| error.to_string())?,
            compiled: Vec::with_capacity(planner.candidate_capacity),
            prepared_blocks: 0,
            dispatches: 0,
        })
    }

    fn prepare(&mut self, max_blocks: usize) -> Result<usize, String> {
        let mut prepared = 0;
        for input in self.planner.take_requests(max_blocks) {
            let blob = self.backend.compile(&input)?;
            let id = self.arena.stage(blob).map_err(|error| error.to_string())?;
            self.arena.seal_batch().map_err(|error| error.to_string())?;
            self.compiled.push(Rv32CompiledJitBlock { input, id });
            self.prepared_blocks = self.prepared_blocks.saturating_add(1);
            prepared += 1;
        }
        Ok(prepared)
    }

    fn entry(&mut self, pc: u32, remaining_budget: u64) -> Option<(JitEntry, u32)> {
        let block = self
            .compiled
            .iter()
            .find(|block| block.input.start_pc() == pc)?;
        let instruction_count = block.input.slots().len() as u32;
        if u64::from(instruction_count) > remaining_budget {
            return None;
        }
        let address = self.arena.entry_address(block.id)?;
        // SAFETY: the code arena publishes only Cranelift functions with the
        // JitEntry ABI, and keeps their RX mappings alive for this call.
        let entry = unsafe { std::mem::transmute::<*const u8, JitEntry>(address) };
        self.dispatches = self.dispatches.saturating_add(1);
        Some((entry, instruction_count))
    }

    fn stats(&self) -> Rv32JitStats {
        Rv32JitStats {
            prepared_blocks: self.prepared_blocks,
            dispatches: self.dispatches,
            emitted_bytes: self.arena.emitted_bytes(),
            reserved_bytes: self.arena.reserved_bytes(),
        }
    }
}

enum Rv32ExecutionBackend {
    Cached(BoundedCachedRv32imProgram),
    Predecoded(PredecodedRv32imImage),
    BlockCached(BoundedDecodedBlockCache),
    Jit(Rv32JitExecution),
}

pub struct Rv32Machine {
    hart: Rv32MachineHart,
    address_space: Rv32AddressSpace,
    execution: Rv32ExecutionBackend,
    executable_ranges: Vec<Range<u32>>,
    control_device: MmioDeviceId,
    debug_device: MmioDeviceId,
}

impl Rv32Machine {
    pub fn from_elf(elf: &[u8], config: Rv32MachineConfig) -> Result<Self, Rv32MachineBuildError> {
        validate_config(config)?;
        let image = Rv32ElfLoader::load(elf, config.ram_size)?;
        let execution = match config.execution {
            Rv32ExecutionBackendConfig::Cached { sets } => Rv32ExecutionBackend::Cached(
                BoundedCachedRv32imProgram::new(sets).map_err(Rv32MachineBuildError::Backend)?,
            ),
            Rv32ExecutionBackendConfig::Predecoded => Rv32ExecutionBackend::Predecoded(
                PredecodedRv32imImage::new(image.ram(), image.executable_ranges())
                    .map_err(Rv32MachineBuildError::Backend)?,
            ),
            Rv32ExecutionBackendConfig::BlockCached {
                sets,
                max_instructions,
            } => Rv32ExecutionBackend::BlockCached(
                BoundedDecodedBlockCache::new(sets, max_instructions)
                    .map_err(Rv32MachineBuildError::Backend)?,
            ),
            Rv32ExecutionBackendConfig::Jit {
                sets,
                max_instructions,
                hotness_threshold,
                candidate_capacity,
                request_capacity,
                code_bytes,
            } => Rv32ExecutionBackend::Jit(
                Rv32JitExecution::new(
                    sets,
                    max_instructions,
                    JitPlannerConfig {
                        hotness_threshold,
                        candidate_capacity,
                        request_capacity,
                    },
                    code_bytes,
                )
                .map_err(Rv32MachineBuildError::Backend)?,
            ),
        };
        let (entry_point, ram, page_permissions, executable_ranges) = image.into_parts();
        let mut bus = MachineBus::new(ram.len())?;
        for (address, byte) in ram.into_iter().enumerate() {
            bus.memory_mut().store_u8(address as u32, byte)?;
        }

        let mut control = ControlDevice::new();
        control.status = platform::STATUS_BOOTING;
        let control_device = bus.map_mmio(platform::CONTROL_BASE, Box::new(control))?;
        let debug_device = bus.map_mmio(
            platform::DEBUG_BASE,
            Box::new(DebugDevice::with_limit(config.debug_limit)),
        )?;
        let address_space = Rv32AddressSpace::from_parts(bus, page_permissions)?;

        Ok(Self {
            hart: Rv32MachineHart::new(entry_point),
            address_space,
            execution,
            executable_ranges,
            control_device,
            debug_device,
        })
    }

    pub fn run(
        &mut self,
        instruction_budget: u64,
    ) -> Result<Rv32MachineOutcome, Rv32MachineExecutionError> {
        match self.execution {
            Rv32ExecutionBackend::BlockCached(_) => self.run_block_cached(instruction_budget),
            Rv32ExecutionBackend::Jit(_) => self.run_jit(instruction_budget),
            Rv32ExecutionBackend::Cached(_) | Rv32ExecutionBackend::Predecoded(_) => {
                self.run_single_instruction(instruction_budget)
            }
        }
    }

    pub fn prepare_jit(&mut self, max_blocks: usize) -> Result<usize, Rv32MachineExecutionError> {
        let pc = self.hart.pc();
        let retired_total = self.hart.retired_instructions();
        let Rv32ExecutionBackend::Jit(execution) = &mut self.execution else {
            return Ok(0);
        };
        execution
            .prepare(max_blocks)
            .map_err(|message| Rv32MachineExecutionError {
                pc,
                retired_total,
                message,
            })
    }

    pub fn jit_stats(&self) -> Option<Rv32JitStats> {
        match &self.execution {
            Rv32ExecutionBackend::Jit(execution) => Some(execution.stats()),
            _ => None,
        }
    }

    fn jit_entry(&mut self, pc: u32, remaining_budget: u64) -> Option<(JitEntry, u32)> {
        let Rv32ExecutionBackend::Jit(execution) = &mut self.execution else {
            return None;
        };
        execution.entry(pc, remaining_budget)
    }

    fn run_single_instruction(
        &mut self,
        instruction_budget: u64,
    ) -> Result<Rv32MachineOutcome, Rv32MachineExecutionError> {
        let retired_before = self.hart.retired_instructions();
        if let Some(outcome) = self.terminal_outcome(retired_before) {
            return Ok(outcome);
        }
        for _ in 0..instruction_budget {
            let instruction_pc = self.hart.pc();
            if !instruction_pc.is_multiple_of(4) {
                self.hart
                    .take_instruction_address_misaligned(instruction_pc);
            } else if !self.is_executable_pc(instruction_pc) {
                self.hart.take_instruction_access_fault(instruction_pc);
            } else {
                let resolved = match &mut self.execution {
                    Rv32ExecutionBackend::Cached(cache) => {
                        match cache.resolve(instruction_pc, &self.address_space) {
                            Ok(resolved) => resolved,
                            Err(error) => {
                                self.hart.take_instruction_access_fault(
                                    error.address().unwrap_or(instruction_pc),
                                );
                                if let Some(outcome) = self.terminal_outcome(retired_before) {
                                    return Ok(outcome);
                                }
                                continue;
                            }
                        }
                    }
                    Rv32ExecutionBackend::Predecoded(image) => image
                        .resolve(instruction_pc)
                        .map_err(|message| self.execution_error(instruction_pc, message))?,
                    Rv32ExecutionBackend::BlockCached(_) => {
                        unreachable!("block backend uses the block execution loop")
                    }
                    Rv32ExecutionBackend::Jit(_) => {
                        unreachable!("JIT backend uses the explicit JIT execution loop")
                    }
                };
                match resolved {
                    Rv32ResolvedInstruction::Valid { word, instruction } => {
                        self.hart.execute_resolved(
                            &mut self.address_space,
                            instruction_pc,
                            word,
                            instruction,
                        );
                    }
                    Rv32ResolvedInstruction::Invalid { word } => {
                        self.hart.take_illegal_instruction(word);
                    }
                }
            }
            if let Some(outcome) = self.terminal_outcome(retired_before) {
                return Ok(outcome);
            }
        }
        Ok(Rv32MachineOutcome::BudgetExhausted {
            retired_delta: self
                .hart
                .retired_instructions()
                .saturating_sub(retired_before),
            retired_total: self.hart.retired_instructions(),
        })
    }

    fn run_block_cached(
        &mut self,
        instruction_budget: u64,
    ) -> Result<Rv32MachineOutcome, Rv32MachineExecutionError> {
        let retired_before = self.hart.retired_instructions();
        if let Some(outcome) = terminal_outcome(
            &self.hart,
            &self.address_space,
            self.control_device,
            retired_before,
        ) {
            return Ok(outcome);
        }
        let mut attempted = 0;
        while attempted < instruction_budget {
            let instruction_pc = self.hart.pc();
            if !instruction_pc.is_multiple_of(4) {
                attempted += 1;
                self.hart
                    .take_instruction_address_misaligned(instruction_pc);
            } else if self.executable_range_end(instruction_pc).is_none() {
                attempted += 1;
                self.hart.take_instruction_access_fault(instruction_pc);
            } else {
                let executable_end = self
                    .executable_range_end(instruction_pc)
                    .expect("executable PC has an owning ELF range");
                let Rv32ExecutionBackend::BlockCached(cache) = &mut self.execution else {
                    unreachable!("block execution loop requires the block backend")
                };
                let block = match cache.resolve(instruction_pc, executable_end, &self.address_space)
                {
                    Ok(block) => block,
                    Err(error) => {
                        attempted += 1;
                        self.hart.take_instruction_access_fault(
                            error.address().unwrap_or(instruction_pc),
                        );
                        if let Some(outcome) = terminal_outcome(
                            &self.hart,
                            &self.address_space,
                            self.control_device,
                            retired_before,
                        ) {
                            return Ok(outcome);
                        }
                        continue;
                    }
                };
                for (slot_index, slot) in block.iter().copied().enumerate() {
                    if attempted >= instruction_budget {
                        break;
                    }
                    let slot_pc = instruction_pc.wrapping_add((slot_index as u32) * 4);
                    if self.hart.pc() != slot_pc {
                        break;
                    }
                    attempted += 1;
                    let step = execute_slot(&mut self.hart, &mut self.address_space, slot_pc, slot);
                    if let Some(outcome) = terminal_outcome(
                        &self.hart,
                        &self.address_space,
                        self.control_device,
                        retired_before,
                    ) {
                        return Ok(outcome);
                    }
                    if step == Rv32HartStep::TrapTaken
                        || ends_basic_block(slot)
                        || self.hart.pc() != slot_pc.wrapping_add(4)
                    {
                        break;
                    }
                }
            }
            if let Some(outcome) = terminal_outcome(
                &self.hart,
                &self.address_space,
                self.control_device,
                retired_before,
            ) {
                return Ok(outcome);
            }
        }
        Ok(Rv32MachineOutcome::BudgetExhausted {
            retired_delta: self
                .hart
                .retired_instructions()
                .saturating_sub(retired_before),
            retired_total: self.hart.retired_instructions(),
        })
    }

    fn run_jit(
        &mut self,
        instruction_budget: u64,
    ) -> Result<Rv32MachineOutcome, Rv32MachineExecutionError> {
        let retired_before = self.hart.retired_instructions();
        if let Some(outcome) = self.terminal_outcome(retired_before) {
            return Ok(outcome);
        }
        let mut attempted = 0;
        while attempted < instruction_budget {
            let instruction_pc = self.hart.pc();
            if !instruction_pc.is_multiple_of(4) {
                attempted += 1;
                self.hart
                    .take_instruction_address_misaligned(instruction_pc);
            } else if self.executable_range_end(instruction_pc).is_none() {
                attempted += 1;
                self.hart.take_instruction_access_fault(instruction_pc);
            } else if let Some((entry, expected_instructions)) =
                self.jit_entry(instruction_pc, instruction_budget.saturating_sub(attempted))
            {
                let executed = self.hart.execute_jit_entry(entry);
                if executed != expected_instructions {
                    return Err(self.execution_error(
                        instruction_pc,
                        format!(
                            "RV32 JIT block returned {executed} instructions, expected {expected_instructions}"
                        ),
                    ));
                }
                attempted = attempted.saturating_add(u64::from(executed));
            } else {
                let executable_end = self
                    .executable_range_end(instruction_pc)
                    .expect("executable PC has an owning ELF range");
                let retired_total = self.hart.retired_instructions();
                let (slot, input) = {
                    let Rv32ExecutionBackend::Jit(execution) = &mut self.execution else {
                        unreachable!("JIT loop requires JIT backend")
                    };
                    let block = match execution.cache.resolve(
                        instruction_pc,
                        executable_end,
                        &self.address_space,
                    ) {
                        Ok(block) => block,
                        Err(error) => {
                            return Err(Rv32MachineExecutionError {
                                pc: instruction_pc,
                                retired_total,
                                message: error.to_string(),
                            });
                        }
                    };
                    (
                        block[0],
                        JitBlockInput::supported_prefix(instruction_pc, block),
                    )
                };
                if let Some(input) = input {
                    let Rv32ExecutionBackend::Jit(execution) = &mut self.execution else {
                        unreachable!("JIT loop requires JIT backend")
                    };
                    execution.planner.observe(input);
                }
                attempted += 1;
                execute_slot(
                    &mut self.hart,
                    &mut self.address_space,
                    instruction_pc,
                    slot,
                );
            }
            if let Some(outcome) = self.terminal_outcome(retired_before) {
                return Ok(outcome);
            }
        }
        Ok(Rv32MachineOutcome::BudgetExhausted {
            retired_delta: self
                .hart
                .retired_instructions()
                .saturating_sub(retired_before),
            retired_total: self.hart.retired_instructions(),
        })
    }

    pub fn debug_bytes(&self) -> &[u8] {
        self.address_space
            .bus()
            .device::<DebugDevice>(self.debug_device)
            .expect("RV32 machine debug device invariant")
            .bytes()
    }

    pub fn control_status(&self) -> i32 {
        self.control().status
    }

    pub fn retired_instructions(&self) -> u64 {
        self.hart.retired_instructions()
    }

    pub fn pc(&self) -> u32 {
        self.hart.pc()
    }

    pub fn cache_stats(&self) -> Option<Rv32imCacheStats> {
        match &self.execution {
            Rv32ExecutionBackend::Cached(cache) => Some(cache.stats()),
            Rv32ExecutionBackend::Predecoded(_)
            | Rv32ExecutionBackend::BlockCached(_)
            | Rv32ExecutionBackend::Jit(_) => None,
        }
    }

    pub fn translation_stats(&self) -> Option<Rv32TranslationStats> {
        match &self.execution {
            Rv32ExecutionBackend::Cached(cache) => {
                let stats = cache.stats();
                Some(Rv32TranslationStats {
                    lookup_unit: Rv32TranslationLookupUnit::Instruction,
                    hits: stats.hits,
                    misses: stats.misses,
                    evictions: stats.evictions,
                    blocks_built: 0,
                    decoded_slots_built: 0,
                })
            }
            Rv32ExecutionBackend::Predecoded(_) => None,
            Rv32ExecutionBackend::BlockCached(cache) => {
                let stats = cache.stats();
                Some(Rv32TranslationStats {
                    lookup_unit: Rv32TranslationLookupUnit::Block,
                    hits: stats.hits,
                    misses: stats.misses,
                    evictions: stats.evictions,
                    blocks_built: stats.blocks_built,
                    decoded_slots_built: stats.decoded_slots_built,
                })
            }
            Rv32ExecutionBackend::Jit(execution) => {
                let stats = execution.cache.stats();
                Some(Rv32TranslationStats {
                    lookup_unit: Rv32TranslationLookupUnit::Block,
                    hits: stats.hits,
                    misses: stats.misses,
                    evictions: stats.evictions,
                    blocks_built: stats.blocks_built,
                    decoded_slots_built: stats.decoded_slots_built,
                })
            }
        }
    }

    pub fn executable_bytes(&self) -> usize {
        self.executable_ranges.iter().map(Range::len).sum()
    }

    pub fn translation_bytes(&self) -> usize {
        match &self.execution {
            Rv32ExecutionBackend::Cached(cache) => cache.retained_bytes(),
            Rv32ExecutionBackend::Predecoded(image) => image.retained_bytes(),
            Rv32ExecutionBackend::BlockCached(cache) => cache.retained_bytes(),
            Rv32ExecutionBackend::Jit(execution) => {
                execution.cache.retained_bytes() + execution.arena.reserved_bytes()
            }
        }
    }

    fn executable_range_end(&self, pc: u32) -> Option<u32> {
        let range_end = self
            .executable_ranges
            .iter()
            .find(|range| range.contains(&pc))
            .map(|range| range.end)?;
        let page_executable = self.address_space.page_permissions(pc).executable();
        page_executable.then_some(range_end)
    }

    fn is_executable_pc(&self, pc: u32) -> bool {
        let in_range = self
            .executable_ranges
            .iter()
            .any(|range| range.contains(&pc));
        let page_executable = self.address_space.page_permissions(pc).executable();
        in_range && page_executable
    }

    fn terminal_outcome(&self, retired_before: u64) -> Option<Rv32MachineOutcome> {
        let retired_total = self.hart.retired_instructions();
        let retired_delta = retired_total.saturating_sub(retired_before);
        let control = self.control();
        match control.status {
            platform::STATUS_HALTED => Some(Rv32MachineOutcome::Halted {
                exit_code: control.exit_code,
                retired_delta,
                retired_total,
            }),
            platform::STATUS_PANIC => Some(Rv32MachineOutcome::Panicked {
                panic_code: control.panic_code,
                retired_delta,
                retired_total,
            }),
            _ => None,
        }
    }

    fn control(&self) -> &ControlDevice {
        self.address_space
            .bus()
            .device::<ControlDevice>(self.control_device)
            .expect("RV32 machine control device invariant")
    }

    fn execution_error(&self, pc: u32, message: String) -> Rv32MachineExecutionError {
        Rv32MachineExecutionError {
            pc,
            retired_total: self.hart.retired_instructions(),
            message,
        }
    }
}

fn execute_slot(
    hart: &mut Rv32MachineHart,
    address_space: &mut Rv32AddressSpace,
    instruction_pc: u32,
    resolved: Rv32ResolvedInstruction,
) -> Rv32HartStep {
    match resolved {
        Rv32ResolvedInstruction::Valid { word, instruction } => {
            hart.execute_resolved(address_space, instruction_pc, word, instruction)
        }
        Rv32ResolvedInstruction::Invalid { word } => hart.take_illegal_instruction(word),
    }
}

fn terminal_outcome(
    hart: &Rv32MachineHart,
    address_space: &Rv32AddressSpace,
    control_device: MmioDeviceId,
    retired_before: u64,
) -> Option<Rv32MachineOutcome> {
    let retired_total = hart.retired_instructions();
    let retired_delta = retired_total.saturating_sub(retired_before);
    let control = address_space
        .bus()
        .device::<ControlDevice>(control_device)
        .expect("RV32 machine control device invariant");
    match control.status {
        platform::STATUS_HALTED => Some(Rv32MachineOutcome::Halted {
            exit_code: control.exit_code,
            retired_delta,
            retired_total,
        }),
        platform::STATUS_PANIC => Some(Rv32MachineOutcome::Panicked {
            panic_code: control.panic_code,
            retired_delta,
            retired_total,
        }),
        _ => None,
    }
}

fn validate_config(config: Rv32MachineConfig) -> Result<(), Rv32MachineBuildError> {
    if config.ram_size == 0 {
        return Err(Rv32MachineBuildError::Config(
            "RAM size must be positive".to_string(),
        ));
    }
    if config.ram_size > platform::CONTROL_BASE as usize {
        return Err(Rv32MachineBuildError::Config(format!(
            "RAM size {} overlaps control MMIO at {:#010x}",
            config.ram_size,
            platform::CONTROL_BASE,
        )));
    }
    Ok(())
}
