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

use super::{Rv32AddressSpace, Rv32AddressSpaceError, Rv32ElfError, Rv32ElfLoader};
use crate::computer::devices::{ComputerControlDevice, DebugSerialDevice};
use crate::computer_abi;
use crate::low_bus::{MachineBus, MmioDeviceId};
use crate::low_machine::MemoryFault;
use crate::rv32im::{
    BoundedCachedRv32imProgram, PredecodedRv32imImage, Rv32imCacheStats, Rv32imCpu,
};
use std::ops::Range;
use thiserror::Error;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rv32ExecutionBackendConfig {
    Cached { sets: usize },
    Predecoded,
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

enum Rv32ExecutionBackend {
    Cached(BoundedCachedRv32imProgram),
    Predecoded(PredecodedRv32imImage),
}

pub struct Rv32Machine {
    cpu: Rv32imCpu,
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
        };
        let (entry_point, ram, page_permissions, executable_ranges) = image.into_parts();
        let mut bus = MachineBus::new(ram.len())?;
        for (address, byte) in ram.into_iter().enumerate() {
            bus.memory_mut().store_u8(address as u32, byte)?;
        }

        let mut control = ComputerControlDevice::new();
        control.status = computer_abi::STATUS_BOOTING;
        let control_device = bus.map_mmio(computer_abi::CONTROL_BASE, Box::new(control))?;
        let debug_device = bus.map_mmio(
            computer_abi::DEBUG_BASE,
            Box::new(DebugSerialDevice::with_limit(config.debug_limit)),
        )?;
        let address_space = Rv32AddressSpace::from_parts(bus, page_permissions)?;

        Ok(Self {
            cpu: Rv32imCpu::new(entry_point),
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
        let retired_before = self.cpu.retired_instructions();
        if let Some(outcome) = self.terminal_outcome(retired_before) {
            return Ok(outcome);
        }
        for _ in 0..instruction_budget {
            let instruction_pc = self.cpu.pc();
            self.require_executable_pc(instruction_pc)?;
            let stop = match &mut self.execution {
                Rv32ExecutionBackend::Cached(cache) => {
                    cache.step(&mut self.cpu, &mut self.address_space)
                }
                Rv32ExecutionBackend::Predecoded(image) => {
                    image.step(&mut self.cpu, &mut self.address_space)
                }
            }
            .map_err(|message| self.execution_error(instruction_pc, message))?;
            if let Some(stop) = stop {
                return Err(self
                    .execution_error(instruction_pc, format!("unsupported RV32IM stop {stop:?}")));
            }
            if let Some(outcome) = self.terminal_outcome(retired_before) {
                return Ok(outcome);
            }
        }
        Ok(Rv32MachineOutcome::BudgetExhausted {
            retired_delta: self
                .cpu
                .retired_instructions()
                .saturating_sub(retired_before),
            retired_total: self.cpu.retired_instructions(),
        })
    }

    pub fn debug_bytes(&self) -> &[u8] {
        self.address_space
            .bus()
            .device::<DebugSerialDevice>(self.debug_device)
            .expect("RV32 machine debug device invariant")
            .bytes()
    }

    pub fn control_status(&self) -> i32 {
        self.control().status
    }

    pub fn retired_instructions(&self) -> u64 {
        self.cpu.retired_instructions()
    }

    pub fn pc(&self) -> u32 {
        self.cpu.pc()
    }

    pub fn cache_stats(&self) -> Option<Rv32imCacheStats> {
        match &self.execution {
            Rv32ExecutionBackend::Cached(cache) => Some(cache.stats()),
            Rv32ExecutionBackend::Predecoded(_) => None,
        }
    }

    fn require_executable_pc(&self, pc: u32) -> Result<(), Rv32MachineExecutionError> {
        let aligned = pc.is_multiple_of(4);
        let in_range = self
            .executable_ranges
            .iter()
            .any(|range| range.contains(&pc));
        let page_executable = self.address_space.page_permissions(pc).executable();
        if aligned && in_range && page_executable {
            return Ok(());
        }
        Err(self.execution_error(pc, "PC is outside executable memory".to_string()))
    }

    fn terminal_outcome(&self, retired_before: u64) -> Option<Rv32MachineOutcome> {
        let retired_total = self.cpu.retired_instructions();
        let retired_delta = retired_total.saturating_sub(retired_before);
        let control = self.control();
        match control.status {
            computer_abi::STATUS_HALTED => Some(Rv32MachineOutcome::Halted {
                exit_code: control.exit_code,
                retired_delta,
                retired_total,
            }),
            computer_abi::STATUS_PANIC => Some(Rv32MachineOutcome::Panicked {
                panic_code: control.panic_code,
                retired_delta,
                retired_total,
            }),
            _ => None,
        }
    }

    fn control(&self) -> &ComputerControlDevice {
        self.address_space
            .bus()
            .device::<ComputerControlDevice>(self.control_device)
            .expect("RV32 machine control device invariant")
    }

    fn execution_error(&self, pc: u32, message: String) -> Rv32MachineExecutionError {
        Rv32MachineExecutionError {
            pc,
            retired_total: self.cpu.retired_instructions(),
            message,
        }
    }
}

fn validate_config(config: Rv32MachineConfig) -> Result<(), Rv32MachineBuildError> {
    if config.ram_size == 0 {
        return Err(Rv32MachineBuildError::Config(
            "RAM size must be positive".to_string(),
        ));
    }
    if config.ram_size > computer_abi::CONTROL_BASE as usize {
        return Err(Rv32MachineBuildError::Config(format!(
            "RAM size {} overlaps control MMIO at {:#010x}",
            config.ram_size,
            computer_abi::CONTROL_BASE,
        )));
    }
    Ok(())
}
