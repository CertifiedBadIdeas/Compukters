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

use std::cell::Cell;
use std::ops::Range;

use super::artifact::{CompiledCArtifact, CompiledCCandidate};
use crate::isa_benchmarks::{native_checksum, IsaBenchmarkWorkload};
use crate::k16_f32::{K16F32Cpu, K16F32Stop, PredecodedK16F32Program};
use crate::low_machine::{MachineMemory, MemoryBus, MemoryFault};
use crate::rv32im::{PredecodedRv32imProgram, Rv32imCpu, Rv32imStop};

const MEMORY_BYTES: usize = 128 * 1024;
const STACK_TOP: u32 = 0x0001_ffc0;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompiledCObservation {
    pub workload: IsaBenchmarkWorkload,
    pub candidate: CompiledCCandidate,
    pub iterations: u32,
    pub checksum: u32,
    pub retired_instructions: u64,
    pub code_bytes: usize,
    pub instruction_count: u32,
    pub cpu_state_bytes: usize,
    pub predecode_bytes: usize,
    pub data_read_bytes: u64,
    pub data_written_bytes: u64,
}

pub fn run_compiled_c(
    artifact: &CompiledCArtifact,
    iterations: u32,
    max_steps: u64,
) -> Result<CompiledCObservation, String> {
    let manifest_checksum = native_checksum(artifact.workload, artifact.validation_iterations);
    if artifact.expected_checksum != manifest_checksum {
        return Err(format!(
            "{} manifest checksum {} does not match native checksum {manifest_checksum}",
            artifact.candidate.name(),
            artifact.expected_checksum
        ));
    }
    let expected_checksum = native_checksum(artifact.workload, iterations);
    match artifact.candidate {
        CompiledCCandidate::K16F32 => run_k16(artifact, iterations, max_steps, expected_checksum),
        CompiledCCandidate::Rv32im => {
            run_rv32im(artifact, iterations, max_steps, expected_checksum)
        }
    }
}

fn run_k16(
    artifact: &CompiledCArtifact,
    iterations: u32,
    max_steps: u64,
    expected_checksum: u32,
) -> Result<CompiledCObservation, String> {
    let program = PredecodedK16F32Program::new(artifact.image_base, &artifact.image)?;
    let mut memory = initialize_memory(artifact)?;
    let stop_address = stop_address(artifact)?;
    memory
        .store_i32(STACK_TOP, stop_address as i32)
        .map_err(|error| error.to_string())?;
    let mut cpu = K16F32Cpu::new(entry_address(artifact)?);
    cpu.set_register(1, iterations)?;
    cpu.set_register(15, STACK_TOP)?;
    let mut bus = CompiledCBus::new(memory, artifact)?;
    let stop = program.run_until_stop(&mut cpu, &mut bus, max_steps)?;
    match stop {
        K16F32Stop::Halt => {}
        K16F32Stop::StepLimit => return Err("k16-f32 instruction limit reached".to_string()),
        other => return Err(format!("k16-f32 returned wrong stop reason {other:?}")),
    }
    validate_completion(artifact, cpu.pc(), cpu.register(0), expected_checksum, &bus)?;
    Ok(observation(
        artifact,
        iterations,
        cpu.register(0),
        cpu.retired_instructions(),
        K16F32Cpu::cpu_state_bytes(),
        program.retained_bytes(),
        &bus,
    ))
}

fn run_rv32im(
    artifact: &CompiledCArtifact,
    iterations: u32,
    max_steps: u64,
    expected_checksum: u32,
) -> Result<CompiledCObservation, String> {
    let program = PredecodedRv32imProgram::new(artifact.image_base, &artifact.image)?;
    let memory = initialize_memory(artifact)?;
    let stop_address = stop_address(artifact)?;
    let mut cpu = Rv32imCpu::new(entry_address(artifact)?);
    cpu.set_register(10, iterations)?;
    cpu.set_register(1, stop_address)?;
    cpu.set_register(2, STACK_TOP)?;
    let mut bus = CompiledCBus::new(memory, artifact)?;
    let stop = program.run_until_stop(&mut cpu, &mut bus, max_steps)?;
    match stop {
        Rv32imStop::Ebreak => {}
        Rv32imStop::StepLimit => return Err("rv32im instruction limit reached".to_string()),
        other => return Err(format!("rv32im returned wrong stop reason {other:?}")),
    }
    validate_completion(
        artifact,
        cpu.pc(),
        cpu.register(10),
        expected_checksum,
        &bus,
    )?;
    Ok(observation(
        artifact,
        iterations,
        cpu.register(10),
        cpu.retired_instructions(),
        Rv32imCpu::cpu_state_bytes(),
        program.retained_bytes(),
        &bus,
    ))
}

fn initialize_memory(artifact: &CompiledCArtifact) -> Result<MachineMemory, String> {
    let mut memory = MachineMemory::zeroed(MEMORY_BYTES).map_err(|error| error.to_string())?;
    let start = artifact.image_base as usize;
    let end = start
        .checked_add(artifact.image.len())
        .ok_or_else(|| "compiled-C image memory range overflow".to_string())?;
    if end > memory.len() {
        return Err(format!(
            "compiled-C image range {start}..{end} is outside {MEMORY_BYTES} bytes"
        ));
    }
    if start < STACK_TOP as usize + 4 && end > STACK_TOP as usize {
        return Err("compiled-C image overlaps the harness return slot".to_string());
    }
    for (offset, byte) in artifact.image.iter().copied().enumerate() {
        memory
            .store_u8(artifact.image_base + offset as u32, byte)
            .map_err(|error| error.to_string())?;
    }
    Ok(memory)
}

fn entry_address(artifact: &CompiledCArtifact) -> Result<u32, String> {
    artifact
        .image_base
        .checked_add(artifact.entry_offset)
        .ok_or_else(|| "compiled-C entry address overflow".to_string())
}

fn stop_address(artifact: &CompiledCArtifact) -> Result<u32, String> {
    artifact
        .image_base
        .checked_add(artifact.stop_offset)
        .ok_or_else(|| "compiled-C stop address overflow".to_string())
}

fn validate_completion(
    artifact: &CompiledCArtifact,
    pc: u32,
    checksum: u32,
    expected_checksum: u32,
    bus: &CompiledCBus,
) -> Result<(), String> {
    let expected_pc = stop_address(artifact)?
        .checked_add(4)
        .ok_or_else(|| "compiled-C post-stop PC overflow".to_string())?;
    if pc != expected_pc {
        return Err(format!(
            "{} stop PC is {pc:#010x}, expected {expected_pc:#010x}",
            artifact.candidate.name()
        ));
    }
    if checksum != expected_checksum {
        return Err(format!(
            "{} checksum {checksum} does not match native checksum {expected_checksum}",
            artifact.candidate.name()
        ));
    }
    if bus.code_bytes() != artifact.image {
        return Err(format!(
            "{} execution mutated its predecoded image",
            artifact.candidate.name()
        ));
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn observation(
    artifact: &CompiledCArtifact,
    iterations: u32,
    checksum: u32,
    retired_instructions: u64,
    cpu_state_bytes: usize,
    predecode_bytes: usize,
    bus: &CompiledCBus,
) -> CompiledCObservation {
    CompiledCObservation {
        workload: artifact.workload,
        candidate: artifact.candidate,
        iterations,
        checksum,
        retired_instructions,
        code_bytes: artifact.code_bytes,
        instruction_count: artifact.instruction_count,
        cpu_state_bytes,
        predecode_bytes,
        data_read_bytes: bus.data_read_bytes.get(),
        data_written_bytes: bus.data_written_bytes,
    }
}

struct CompiledCBus {
    memory: MachineMemory,
    code_range: Range<usize>,
    data_read_bytes: Cell<u64>,
    data_written_bytes: u64,
}

impl CompiledCBus {
    fn new(memory: MachineMemory, artifact: &CompiledCArtifact) -> Result<Self, String> {
        let start = artifact.image_base as usize;
        let end = start
            .checked_add(artifact.image.len())
            .ok_or_else(|| "compiled-C immutable code range overflow".to_string())?;
        Ok(Self {
            memory,
            code_range: start..end,
            data_read_bytes: Cell::new(0),
            data_written_bytes: 0,
        })
    }

    fn code_bytes(&self) -> &[u8] {
        &self.memory.bytes()[self.code_range.clone()]
    }

    fn reject_code_store(&self, address: u32, size: usize) -> Result<(), MemoryFault> {
        let start = address as usize;
        let end = start.checked_add(size).ok_or_else(|| {
            MemoryFault::new(format!("store at {address:#010x} overflows address range"))
        })?;
        if start < self.code_range.end && end > self.code_range.start {
            return Err(MemoryFault::new(format!(
                "store {start}..{end} overlaps immutable code {}..{}",
                self.code_range.start, self.code_range.end
            )));
        }
        Ok(())
    }

    fn count_read(&self, bytes: u64) {
        self.data_read_bytes
            .set(self.data_read_bytes.get().saturating_add(bytes));
    }
}

impl MemoryBus for CompiledCBus {
    fn len(&self) -> usize {
        self.memory.len()
    }

    fn load_i32(&self, address: u32) -> Result<i32, MemoryFault> {
        let value = self.memory.load_i32(address)?;
        self.count_read(4);
        Ok(value)
    }

    fn store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault> {
        self.reject_code_store(address, 4)?;
        self.memory.store_i32(address, value)?;
        self.data_written_bytes = self.data_written_bytes.saturating_add(4);
        Ok(())
    }

    fn load_u8(&self, address: u32) -> Result<u8, MemoryFault> {
        let value = self.memory.load_u8(address)?;
        self.count_read(1);
        Ok(value)
    }

    fn store_u8(&mut self, address: u32, value: u8) -> Result<(), MemoryFault> {
        self.reject_code_store(address, 1)?;
        self.memory.store_u8(address, value)?;
        self.data_written_bytes = self.data_written_bytes.saturating_add(1);
        Ok(())
    }
}
