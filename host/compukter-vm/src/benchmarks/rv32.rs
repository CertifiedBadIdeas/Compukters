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

use super::{
    IsaBenchmarkCandidate, IsaBenchmarkObservation, IsaBenchmarkWorkload, IsaTraffic, DATA_BASE,
    MEMORY_SIZE, MMIO_BASE, STACK_TOP,
};
use crate::bus::{MachineBus, MachineBusTrafficSnapshot, MmioDevice};
use crate::memory::MemoryFault;
use crate::rv32im::encoding::{
    add, addi, and, andi, beq, bne, ebreak, ecall, jal, jalr, lbu, lw, materialize, mul, or, sb,
    sll, srl, sub, sw, xor,
};
use crate::rv32im::{CachedRv32imProgram, PredecodedRv32imProgram, Rv32imCpu, Rv32imStop};
use rvsim::{Clock, CpuError, CpuState, Interp, Memory, MemoryAccess};
use std::collections::HashMap;
use std::mem::size_of;

const COPY_SOURCE: u32 = DATA_BASE;
const COPY_DESTINATION: u32 = DATA_BASE + 0x0100;
const PACKET_RING: u32 = DATA_BASE;

pub(super) struct Prepared {
    candidate: IsaBenchmarkCandidate,
    workload: IsaBenchmarkWorkload,
    iterations: u32,
    image: ProgramImage,
    bus: MachineBus,
    cached: Option<CachedRv32imProgram>,
    predecoded: Option<PredecodedRv32imProgram>,
}

impl Prepared {
    pub(super) fn new(
        candidate: IsaBenchmarkCandidate,
        workload: IsaBenchmarkWorkload,
        iterations: u32,
    ) -> Result<Self, String> {
        let image = rv32_workload(workload, iterations)?;
        if !matches!(
            candidate,
            IsaBenchmarkCandidate::RvsimRv32im
                | IsaBenchmarkCandidate::Rv32Direct
                | IsaBenchmarkCandidate::Rv32Cached
                | IsaBenchmarkCandidate::Rv32Predecoded
        ) {
            return Err(format!(
                "candidate {} is not an RV32IM mode",
                candidate.name()
            ));
        }
        let (bus, code) = new_bus(workload, &image.words)?;
        let cached = if candidate == IsaBenchmarkCandidate::Rv32Cached {
            Some(CachedRv32imProgram::new())
        } else {
            None
        };
        let predecoded = if candidate == IsaBenchmarkCandidate::Rv32Predecoded {
            Some(PredecodedRv32imProgram::new(0, &code)?)
        } else {
            None
        };
        Ok(Self {
            candidate,
            workload,
            iterations,
            image,
            bus,
            cached,
            predecoded,
        })
    }

    pub(super) fn execute(&mut self) -> Result<IsaBenchmarkObservation, String> {
        reset_working_memory(&mut self.bus, self.workload)?;
        match self.candidate {
            IsaBenchmarkCandidate::RvsimRv32im => {
                run_rvsim(&self.image, &mut self.bus, self.workload, self.iterations)
            }
            IsaBenchmarkCandidate::Rv32Direct
            | IsaBenchmarkCandidate::Rv32Cached
            | IsaBenchmarkCandidate::Rv32Predecoded => run_specialized(
                self.candidate,
                &self.image,
                &mut self.bus,
                self.cached.as_mut(),
                self.predecoded.as_ref(),
                self.workload,
                self.iterations,
            ),
            _ => unreachable!(),
        }
    }
}

fn reset_working_memory(
    bus: &mut MachineBus,
    workload: IsaBenchmarkWorkload,
) -> Result<(), String> {
    let bytes = match workload {
        IsaBenchmarkWorkload::MemorySequential | IsaBenchmarkWorkload::MemoryRandom => 64 * 4,
        IsaBenchmarkWorkload::CopyChecksum => 512,
        IsaBenchmarkWorkload::PacketRing => super::PACKET_BYTES * super::RING_ENTRIES,
        _ => 0,
    };
    for offset in 0..bytes as u32 {
        if workload != IsaBenchmarkWorkload::CopyChecksum || offset >= 256 {
            bus.store_u8(DATA_BASE + offset, 0)
                .map_err(|error| error.to_string())?;
        }
    }
    Ok(())
}

fn new_bus(workload: IsaBenchmarkWorkload, words: &[u32]) -> Result<(MachineBus, Vec<u8>), String> {
    let mut bus = MachineBus::new(MEMORY_SIZE).map_err(|error| error.to_string())?;
    if workload == IsaBenchmarkWorkload::MmioControl {
        bus.map_mmio(MMIO_BASE, Box::new(BenchmarkRegisterDevice::default()))
            .map_err(|error| error.to_string())?;
    }
    if workload == IsaBenchmarkWorkload::CopyChecksum {
        for index in 0..256_u32 {
            let value = (index as u8).wrapping_mul(29).wrapping_add(7);
            bus.store_u8(COPY_SOURCE + index, value)
                .map_err(|error| error.to_string())?;
        }
    }
    let code = words
        .iter()
        .copied()
        .flat_map(u32::to_le_bytes)
        .collect::<Vec<_>>();
    for (index, word) in words.iter().copied().enumerate() {
        bus.store_i32(index as u32 * 4, word as i32)
            .map_err(|error| error.to_string())?;
    }
    Ok((bus, code))
}

fn run_specialized(
    candidate: IsaBenchmarkCandidate,
    image: &ProgramImage,
    bus: &mut MachineBus,
    mut cached: Option<&mut CachedRv32imProgram>,
    predecoded: Option<&PredecodedRv32imProgram>,
    workload: IsaBenchmarkWorkload,
    iterations: u32,
) -> Result<IsaBenchmarkObservation, String> {
    let before = bus.aggregate_traffic_snapshot();
    let mut cpu = Rv32imCpu::new(0);
    let max_steps = max_steps(iterations);
    let mut yields = 0_u64;
    let misses_before = cached.as_deref().map_or(0, CachedRv32imProgram::misses);
    loop {
        let stop = match (cached.as_deref_mut(), predecoded) {
            (Some(program), None) => program.run_until_stop(&mut cpu, bus, max_steps)?,
            (None, Some(program)) => program.run_until_stop(&mut cpu, bus, max_steps)?,
            (None, None) => cpu.run_until_stop(bus, max_steps)?,
            (Some(_), Some(_)) => unreachable!(),
        };
        match stop {
            Rv32imStop::Ebreak => break,
            Rv32imStop::Ecall if workload == IsaBenchmarkWorkload::YieldWake => yields += 1,
            Rv32imStop::StepLimit => return Err(step_limit_error(candidate, workload, max_steps)),
            stop => {
                return Err(format!(
                    "candidate {} workload {} returned unexpected stop {stop:?}",
                    candidate.name(),
                    workload.name()
                ))
            }
        }
    }
    let after = bus.aggregate_traffic_snapshot();
    let ram = subtract(after.0, before.0);
    let mmio = subtract(after.1, before.1);
    let instruction_fetch = if predecoded.is_some() {
        IsaTraffic::default()
    } else if let Some(program) = cached.as_deref() {
        let misses = program.misses().saturating_sub(misses_before);
        IsaTraffic {
            loads: misses,
            stores: 0,
            bytes_read: misses.saturating_mul(4),
            bytes_written: 0,
        }
    } else {
        IsaTraffic {
            loads: cpu.retired_instructions(),
            stores: 0,
            bytes_read: cpu.retired_instructions().saturating_mul(4),
            bytes_written: 0,
        }
    };
    finish_observation(
        candidate,
        workload,
        iterations,
        cpu.register(image.result_register as usize),
        cpu.retired_instructions(),
        yields,
        instruction_fetch,
        ram,
        mmio,
        Rv32imCpu::cpu_state_bytes(),
        cached.as_deref().map_or_else(
            || predecoded.map_or(0, PredecodedRv32imProgram::retained_bytes),
            CachedRv32imProgram::retained_bytes,
        ),
    )
}

fn run_rvsim(
    image: &ProgramImage,
    bus: &mut MachineBus,
    workload: IsaBenchmarkWorkload,
    iterations: u32,
) -> Result<IsaBenchmarkObservation, String> {
    let candidate = IsaBenchmarkCandidate::RvsimRv32im;
    let before = bus.aggregate_traffic_snapshot();
    let mut state = CpuState::new(0);
    let mut clock = QuotaClock::new(max_steps(iterations));
    let (yields, instruction_fetch) = {
        let mut memory = RvsimMemory::new(bus);
        let mut interp = Interp::new(&mut state, &mut memory, &mut clock);
        let mut yields = 0_u64;
        loop {
            match interp.step() {
                Ok(_) => {}
                Err((CpuError::Ecall, _)) if workload == IsaBenchmarkWorkload::YieldWake => yields += 1,
                Err((CpuError::Ebreak, _)) => break,
                Err((CpuError::QuotaExceeded, _)) => return Err(step_limit_error(candidate, workload, clock.limit)),
                Err((error, operation)) => return Err(format!("candidate {} workload {} failed with {error:?} at PC {:#010x}, op {operation:?}", candidate.name(), workload.name(), interp.state.pc)),
            }
        }
        (yields, memory.instruction_fetch)
    };
    let after = bus.aggregate_traffic_snapshot();
    let ram = subtract(after.0, before.0);
    let mmio = subtract(after.1, before.1);
    finish_observation(
        candidate,
        workload,
        iterations,
        state.x[image.result_register as usize],
        clock.retired,
        yields,
        instruction_fetch,
        ram,
        mmio,
        size_of::<CpuState>(),
        0,
    )
}

#[allow(clippy::too_many_arguments)]
fn finish_observation(
    candidate: IsaBenchmarkCandidate,
    workload: IsaBenchmarkWorkload,
    iterations: u32,
    checksum: u32,
    retired: u64,
    yields: u64,
    instruction_fetch: IsaTraffic,
    ram: MachineBusTrafficSnapshot,
    mmio: MachineBusTrafficSnapshot,
    cpu_state_bytes: usize,
    translation_bytes: usize,
) -> Result<IsaBenchmarkObservation, String> {
    let observation = IsaBenchmarkObservation {
        candidate,
        workload,
        iterations,
        checksum,
        retired_instructions: retired,
        yields,
        instruction_fetch,
        data_ram: IsaTraffic {
            loads: ram.loads.saturating_sub(instruction_fetch.loads),
            stores: ram.stores,
            bytes_read: ram.bytes_read.saturating_sub(instruction_fetch.bytes_read),
            bytes_written: ram.bytes_written,
        },
        mmio: IsaTraffic::from(mmio),
        cpu_state_bytes,
        translation_bytes,
    };
    observation.validate_checksum()?;
    Ok(observation)
}

fn max_steps(iterations: u32) -> u64 {
    u64::from(iterations)
        .saturating_mul(4_096)
        .saturating_add(100_000)
}
fn step_limit_error(
    candidate: IsaBenchmarkCandidate,
    workload: IsaBenchmarkWorkload,
    limit: u64,
) -> String {
    format!(
        "candidate {} workload {} exceeded {limit} steps",
        candidate.name(),
        workload.name()
    )
}

pub(super) struct ProgramImage {
    pub(super) words: Vec<u32>,
    pub(super) result_register: u8,
}
#[derive(Clone, Copy)]
enum FixupKind {
    Beq(u8, u8),
    Bne(u8, u8),
    Jal(u8),
}
#[derive(Default)]
struct ProgramBuilder {
    words: Vec<u32>,
    labels: HashMap<&'static str, usize>,
    fixups: Vec<(usize, FixupKind, &'static str)>,
}
impl ProgramBuilder {
    fn word(&mut self, word: u32) {
        self.words.push(word);
    }
    fn words(&mut self, words: impl IntoIterator<Item = u32>) {
        self.words.extend(words);
    }
    fn label(&mut self, name: &'static str) {
        assert!(self.labels.insert(name, self.words.len()).is_none());
    }
    fn fixup(&mut self, kind: FixupKind, label: &'static str) {
        let index = self.words.len();
        self.words.push(0);
        self.fixups.push((index, kind, label));
    }
    fn beq(&mut self, lhs: u8, rhs: u8, label: &'static str) {
        self.fixup(FixupKind::Beq(lhs, rhs), label);
    }
    fn bne(&mut self, lhs: u8, rhs: u8, label: &'static str) {
        self.fixup(FixupKind::Bne(lhs, rhs), label);
    }
    fn jump(&mut self, label: &'static str) {
        self.fixup(FixupKind::Jal(0), label);
    }
    fn call(&mut self, label: &'static str) {
        self.fixup(FixupKind::Jal(1), label);
    }
    fn finish(mut self, result_register: u8) -> Result<ProgramImage, String> {
        for (index, kind, label) in self.fixups {
            let target = self
                .labels
                .get(label)
                .copied()
                .ok_or_else(|| format!("missing RV32 benchmark label {label}"))?;
            let offset = (i32::try_from(target).unwrap() - i32::try_from(index).unwrap()) * 4;
            self.words[index] = match kind {
                FixupKind::Beq(a, b) => beq(a, b, offset),
                FixupKind::Bne(a, b) => bne(a, b, offset),
                FixupKind::Jal(rd) => jal(rd, offset),
            };
        }
        Ok(ProgramImage {
            words: self.words,
            result_register,
        })
    }
}

pub(super) fn rv32_workload(
    workload: IsaBenchmarkWorkload,
    iterations: u32,
) -> Result<ProgramImage, String> {
    match workload {
        IsaBenchmarkWorkload::Compute32 => compute32_program(iterations),
        IsaBenchmarkWorkload::BranchMix => branch_mix_program(iterations),
        IsaBenchmarkWorkload::CallStack => call_stack_program(iterations),
        IsaBenchmarkWorkload::MemorySequential => memory_program(iterations, false),
        IsaBenchmarkWorkload::MemoryRandom => memory_program(iterations, true),
        IsaBenchmarkWorkload::CopyChecksum => copy_checksum_program(iterations),
        IsaBenchmarkWorkload::MmioControl => mmio_control_program(iterations, 0),
        IsaBenchmarkWorkload::YieldWake => yield_wake_program(iterations),
        IsaBenchmarkWorkload::PacketRing => packet_ring_program(iterations),
        IsaBenchmarkWorkload::U64Mix
        | IsaBenchmarkWorkload::Fixed64Geometry
        | IsaBenchmarkWorkload::U64Memory => Err(format!(
            "{} is not part of the RV32 decoder benchmark",
            workload.name()
        )),
    }
}

fn compute32_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(materialize(5, iterations));
    p.words(materialize(6, 0));
    p.words(materialize(7, 0x6d2b_79f5));
    p.words(materialize(8, 17));
    p.words(materialize(9, 0x045d_9f3b));
    p.words(materialize(10, 32));
    p.words(materialize(11, 31));
    p.label("loop");
    p.bne(6, 5, "body");
    p.word(ebreak());
    p.label("body");
    p.word(mul(12, 6, 8));
    p.word(add(7, 7, 12));
    p.word(mul(7, 7, 9));
    p.word(and(13, 6, 11));
    p.word(sll(12, 6, 13));
    p.word(sub(14, 10, 13));
    p.word(srl(15, 6, 14));
    p.word(or(12, 12, 15));
    p.word(xor(7, 7, 12));
    p.word(xor(13, 6, 7));
    p.word(and(13, 13, 11));
    p.word(sll(12, 7, 13));
    p.word(sub(14, 10, 13));
    p.word(srl(15, 7, 14));
    p.word(or(7, 12, 15));
    p.word(addi(6, 6, 1));
    p.jump("loop");
    p.finish(7)
}
fn branch_mix_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(materialize(5, iterations));
    p.words(materialize(6, 0));
    p.words(materialize(7, 0));
    p.label("loop");
    p.bne(6, 5, "body");
    p.word(ebreak());
    p.label("body");
    p.word(andi(8, 6, 1));
    p.beq(8, 0, "even");
    p.word(addi(7, 7, 3));
    p.jump("next");
    p.label("even");
    p.word(addi(7, 7, 1));
    p.label("next");
    p.word(addi(6, 6, 1));
    p.jump("loop");
    p.finish(7)
}
fn call_stack_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(materialize(5, iterations));
    p.words(materialize(6, 0));
    p.words(materialize(7, 0));
    p.words(materialize(31, STACK_TOP));
    p.label("loop");
    p.bne(6, 5, "body");
    p.word(ebreak());
    p.label("body");
    p.call("outer");
    p.word(addi(6, 6, 1));
    p.jump("loop");
    p.label("outer");
    p.word(add(7, 7, 6));
    p.word(addi(7, 7, 1));
    p.word(addi(31, 31, -4));
    p.word(sw(31, 1, 0));
    p.call("inner");
    p.word(lw(1, 31, 0));
    p.word(addi(31, 31, 4));
    p.word(jalr(0, 1, 0));
    p.label("inner");
    p.word(addi(7, 7, 2));
    p.word(jalr(0, 1, 0));
    p.finish(7)
}
fn memory_program(iterations: u32, random: bool) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(materialize(5, iterations));
    p.words(materialize(6, 0));
    p.words(materialize(7, DATA_BASE));
    p.words(materialize(8, 0));
    p.words(materialize(13, 2));
    if random {
        p.words(materialize(9, 17));
        p.words(materialize(10, 11));
    }
    p.label("update");
    p.bne(6, 5, "update_body");
    p.jump("scan_init");
    p.label("update_body");
    if random {
        p.word(mul(8, 8, 9));
        p.word(add(8, 8, 10));
        p.word(andi(8, 8, 63));
        p.word(sll(11, 8, 13));
    } else {
        p.word(andi(11, 6, 63));
        p.word(sll(11, 11, 13));
    }
    p.word(add(11, 7, 11));
    p.word(lw(12, 11, 0));
    p.word(add(12, 12, 6));
    p.word(addi(12, 12, 1));
    p.word(sw(11, 12, 0));
    p.word(addi(6, 6, 1));
    p.jump("update");
    p.label("scan_init");
    p.words(materialize(5, 64));
    p.words(materialize(6, 0));
    p.words(materialize(8, 0));
    p.label("scan");
    p.bne(6, 5, "scan_body");
    p.word(ebreak());
    p.label("scan_body");
    p.word(sll(11, 6, 13));
    p.word(add(11, 7, 11));
    p.word(lw(12, 11, 0));
    p.word(add(8, 8, 12));
    p.word(addi(6, 6, 1));
    p.jump("scan");
    p.finish(8)
}
fn copy_checksum_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(materialize(5, iterations));
    p.words(materialize(6, 0));
    p.words(materialize(7, 0));
    p.words(materialize(8, COPY_SOURCE));
    p.words(materialize(9, COPY_DESTINATION));
    p.words(materialize(10, 256));
    p.label("outer");
    p.bne(6, 5, "outer_body");
    p.word(ebreak());
    p.label("outer_body");
    p.words(materialize(11, 0));
    p.label("inner");
    p.bne(11, 10, "inner_body");
    p.jump("next_outer");
    p.label("inner_body");
    p.word(add(12, 8, 11));
    p.word(lbu(13, 12, 0));
    p.word(add(12, 9, 11));
    p.word(sb(12, 13, 0));
    p.word(add(14, 13, 6));
    p.word(add(7, 7, 14));
    p.word(addi(11, 11, 1));
    p.jump("inner");
    p.label("next_outer");
    p.word(addi(6, 6, 1));
    p.jump("outer");
    p.finish(7)
}
pub(super) fn mmio_control_program(
    iterations: u32,
    register_offset: i32,
) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(materialize(5, iterations));
    p.words(materialize(6, 0));
    p.words(materialize(7, 0));
    p.words(materialize(8, MMIO_BASE));
    p.label("loop");
    p.bne(6, 5, "body");
    p.word(ebreak());
    p.label("body");
    p.word(sw(8, 6, register_offset));
    p.word(lw(9, 8, register_offset));
    p.word(add(7, 7, 9));
    p.word(addi(6, 6, 1));
    p.jump("loop");
    p.finish(7)
}
fn yield_wake_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(materialize(5, iterations));
    p.words(materialize(6, 0));
    p.words(materialize(7, 0));
    p.label("loop");
    p.bne(6, 5, "body");
    p.word(ebreak());
    p.label("body");
    p.word(addi(7, 7, 1));
    p.word(addi(6, 6, 1));
    p.word(ecall());
    p.jump("loop");
    p.finish(7)
}
fn packet_ring_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(materialize(5, iterations));
    p.words(materialize(6, 0));
    p.words(materialize(7, 0));
    p.words(materialize(8, PACKET_RING));
    p.words(materialize(9, 16));
    p.words(materialize(10, 13));
    p.words(materialize(11, 4));
    p.label("outer");
    p.bne(6, 5, "outer_body");
    p.word(ebreak());
    p.label("outer_body");
    p.words(materialize(12, 0));
    p.label("inner");
    p.bne(12, 9, "inner_body");
    p.jump("next_outer");
    p.label("inner_body");
    p.word(andi(13, 6, 7));
    p.word(sll(13, 13, 11));
    p.word(add(13, 8, 13));
    p.word(mul(14, 6, 10));
    p.word(add(14, 14, 12));
    p.word(andi(14, 14, 255));
    p.word(add(13, 13, 12));
    p.word(sb(13, 14, 0));
    p.word(lbu(14, 13, 0));
    p.word(add(7, 7, 14));
    p.word(addi(12, 12, 1));
    p.jump("inner");
    p.label("next_outer");
    p.word(addi(6, 6, 1));
    p.jump("outer");
    p.finish(7)
}

struct RvsimMemory<'a> {
    bus: &'a mut MachineBus,
    instruction_fetch: IsaTraffic,
}
impl<'a> RvsimMemory<'a> {
    fn new(bus: &'a mut MachineBus) -> Self {
        Self {
            bus,
            instruction_fetch: IsaTraffic::default(),
        }
    }
}
impl Memory for RvsimMemory<'_> {
    fn access<T: Copy>(&mut self, address: u32, access: MemoryAccess<T>) -> bool {
        let size = size_of::<T>();
        match access {
            MemoryAccess::Load(destination) => load_value(self.bus, address, destination).is_ok(),
            MemoryAccess::Exec(destination) => {
                let result = load_value(self.bus, address, destination);
                if result.is_ok() {
                    self.instruction_fetch.loads += 1;
                    self.instruction_fetch.bytes_read += size as u64;
                }
                result.is_ok()
            }
            MemoryAccess::Store(value) => store_value(self.bus, address, value).is_ok(),
        }
    }
}
fn load_value<T: Copy>(
    bus: &MachineBus,
    address: u32,
    destination: &mut T,
) -> Result<(), MemoryFault> {
    unsafe {
        match size_of::<T>() {
            1 => (destination as *mut T as *mut u8).write_unaligned(bus.load_u8(address)?),
            2 => (destination as *mut T as *mut u16).write_unaligned(bus.load_u16(address)?),
            4 => (destination as *mut T as *mut u32).write_unaligned(bus.load_i32(address)? as u32),
            _ => {
                return Err(MemoryFault::new(format!(
                    "unsupported rvsim load width {}",
                    size_of::<T>()
                )))
            }
        }
    }
    Ok(())
}
fn store_value<T: Copy>(bus: &mut MachineBus, address: u32, value: T) -> Result<(), MemoryFault> {
    unsafe {
        match size_of::<T>() {
            1 => bus.store_u8(address, (&value as *const T as *const u8).read_unaligned()),
            2 => bus.store_u16(address, (&value as *const T as *const u16).read_unaligned()),
            4 => bus.store_i32(
                address,
                (&value as *const T as *const u32).read_unaligned() as i32,
            ),
            _ => Err(MemoryFault::new(format!(
                "unsupported rvsim store width {}",
                size_of::<T>()
            ))),
        }
    }
}

struct QuotaClock {
    retired: u64,
    limit: u64,
}
impl QuotaClock {
    fn new(limit: u64) -> Self {
        Self { retired: 0, limit }
    }
}
impl Clock for QuotaClock {
    fn read_cycle(&self) -> u64 {
        self.retired
    }
    fn read_time(&self) -> u64 {
        self.retired
    }
    fn read_instret(&self) -> u64 {
        self.retired
    }
    fn progress(&mut self, _: &rvsim::Op) {
        self.retired = self.retired.saturating_add(1);
    }
    fn check_quota(&self) -> bool {
        self.retired < self.limit
    }
}

#[derive(Default)]
struct BenchmarkRegisterDevice {
    value: i32,
}
impl MmioDevice for BenchmarkRegisterDevice {
    fn size(&self) -> u32 {
        4
    }
    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        if offset == 0 {
            Ok(self.value)
        } else {
            Err(MemoryFault::new(format!(
                "benchmark register offset {offset} is not mapped"
            )))
        }
    }
    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        if offset == 0 {
            self.value = value;
            Ok(())
        } else {
            Err(MemoryFault::new(format!(
                "benchmark register offset {offset} is not mapped"
            )))
        }
    }
}
fn subtract(
    after: MachineBusTrafficSnapshot,
    before: MachineBusTrafficSnapshot,
) -> MachineBusTrafficSnapshot {
    MachineBusTrafficSnapshot {
        loads: after.loads.saturating_sub(before.loads),
        stores: after.stores.saturating_sub(before.stores),
        bytes_read: after.bytes_read.saturating_sub(before.bytes_read),
        bytes_written: after.bytes_written.saturating_sub(before.bytes_written),
    }
}

#[cfg(test)]
fn assert_stepwise_differential(mut seed: u64, instruction_count: usize) {
    use crate::rv32im::encoding::{
        div, divu, lb, lbu, lh, lhu, lui, mulh, mulhsu, mulhu, ori, rem, remu, sb, sh, slli, slt,
        slti, sltiu, sltu, srai, srli, xori,
    };

    let mut words = vec![lui(20, DATA_BASE >> 12)];
    for register in 1..16_u8 {
        words.push(addi(register, 0, i32::from(register) * 37 - 211));
    }
    for index in 0..instruction_count {
        seed ^= seed >> 12;
        seed ^= seed << 25;
        seed ^= seed >> 27;
        let random = seed.wrapping_mul(0x2545_f491_4f6c_dd1d);
        let rd = 1 + ((random >> 8) % 15) as u8;
        let lhs = 1 + ((random >> 16) % 15) as u8;
        let rhs = 1 + ((random >> 24) % 15) as u8;
        let immediate = ((random >> 32) as i32 % 1024).clamp(-1024, 1023);
        let word = match random % 34 {
            0 => add(rd, lhs, rhs),
            1 => sub(rd, lhs, rhs),
            2 => xor(rd, lhs, rhs),
            3 => or(rd, lhs, rhs),
            4 => and(rd, lhs, rhs),
            5 => mul(rd, lhs, rhs),
            6 => div(rd, lhs, rhs),
            7 => divu(rd, lhs, rhs),
            8 => sll(rd, lhs, rhs),
            9 => srl(rd, lhs, rhs),
            10 => slt(rd, lhs, rhs),
            11 => sltu(rd, lhs, rhs),
            12 => rem(rd, lhs, rhs),
            13 => remu(rd, lhs, rhs),
            14 => mulh(rd, lhs, rhs),
            15 => mulhsu(rd, lhs, rhs),
            16 => mulhu(rd, lhs, rhs),
            17 => addi(rd, lhs, immediate),
            18 => xori(rd, lhs, immediate),
            19 => ori(rd, lhs, immediate),
            20 => andi(rd, lhs, immediate),
            21 => slti(rd, lhs, immediate),
            22 => sltiu(rd, lhs, immediate),
            23 => slli(rd, lhs, (random >> 40) as u8 & 31),
            24 => srli(rd, lhs, (random >> 40) as u8 & 31),
            25 => srai(rd, lhs, (random >> 40) as u8 & 31),
            26 => sw(20, lhs, ((index % 32) * 4) as i32),
            27 => lw(rd, 20, ((index % 32) * 4) as i32),
            28 => sb(20, lhs, (index % 128) as i32),
            29 => lb(rd, 20, (index % 128) as i32),
            30 => lbu(rd, 20, (index % 128) as i32),
            31 => sh(20, lhs, ((index % 64) * 2) as i32),
            32 => lh(rd, 20, ((index % 64) * 2) as i32),
            _ => lhu(rd, 20, ((index % 64) * 2) as i32),
        };
        words.push(word);
    }

    let (mut direct_bus, _) = new_bus(IsaBenchmarkWorkload::Compute32, &words).unwrap();
    let (mut reference_bus, _) = new_bus(IsaBenchmarkWorkload::Compute32, &words).unwrap();
    let mut direct = Rv32imCpu::new(0);
    let mut reference = CpuState::new(0);
    let mut clock = QuotaClock::new(words.len() as u64 + 1);

    for step in 0..words.len() {
        assert_eq!(direct.step(&mut direct_bus).unwrap(), None, "step {step}");
        let reference_result = {
            let mut memory = RvsimMemory::new(&mut reference_bus);
            let mut interp = Interp::new(&mut reference, &mut memory, &mut clock);
            interp.step()
        };
        assert!(reference_result.is_ok(), "rvsim failed at step {step}");
        assert_eq!(direct.pc(), reference.pc, "PC at step {step}");
        for register in 0..32 {
            assert_eq!(
                direct.register(register),
                reference.x[register],
                "x{register} at step {step}",
            );
        }
        assert_eq!(
            direct_bus.memory(),
            reference_bus.memory(),
            "RAM at step {step}",
        );
    }
}

#[cfg(test)]
mod tests {
    use super::assert_stepwise_differential;

    #[test]
    fn specialized_rv32im_matches_rvsim_after_every_randomized_instruction() {
        assert_stepwise_differential(0x6a09_e667_f3bc_c909, 512);
    }
}
