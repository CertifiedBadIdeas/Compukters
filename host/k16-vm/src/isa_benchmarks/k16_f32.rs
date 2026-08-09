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
use crate::k16_f32::encoding::{
    add, addi, and, branchz, call, eq, halt, jump, load32, load8, materialize, mul, or, ret, shl,
    shr, store32, store8, sub, xor, yield_now,
};
use crate::k16_f32::{K16F32Cpu, K16F32Stop, PredecodedK16F32Program};
use crate::low_bus::{MachineBus, MmioDevice};
use crate::low_machine::MemoryFault;
use std::collections::HashMap;

const COPY_SOURCE: u32 = DATA_BASE;
const COPY_DESTINATION: u32 = DATA_BASE + 0x0100;
const PACKET_RING: u32 = DATA_BASE;

pub(super) fn run(
    candidate: IsaBenchmarkCandidate,
    workload: IsaBenchmarkWorkload,
    iterations: u32,
) -> Result<IsaBenchmarkObservation, String> {
    Prepared::new(candidate, workload, iterations)?.execute()
}

pub(super) struct Prepared {
    candidate: IsaBenchmarkCandidate,
    workload: IsaBenchmarkWorkload,
    iterations: u32,
    image: ProgramImage,
    bus: MachineBus,
    predecoded: Option<PredecodedK16F32Program>,
}

impl Prepared {
    pub(super) fn new(
        candidate: IsaBenchmarkCandidate,
        workload: IsaBenchmarkWorkload,
        iterations: u32,
    ) -> Result<Self, String> {
        if !candidate.is_k16_f32() {
            return Err(format!(
                "candidate {} is not a K16-F32 execution mode",
                candidate.name()
            ));
        }
        let image = k16_f32_workload(workload, iterations)?;
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
        for (index, word) in image.words.iter().copied().enumerate() {
            bus.store_i32(index as u32 * 4, word as i32)
                .map_err(|error| error.to_string())?;
        }
        let predecoded = if candidate == IsaBenchmarkCandidate::K16F32Predecoded {
            let code = image
                .words
                .iter()
                .copied()
                .flat_map(u32::to_le_bytes)
                .collect::<Vec<_>>();
            Some(PredecodedK16F32Program::new(0, &code)?)
        } else {
            None
        };
        Ok(Self {
            candidate,
            workload,
            iterations,
            image,
            bus,
            predecoded,
        })
    }

    pub(super) fn execute(&mut self) -> Result<IsaBenchmarkObservation, String> {
        reset_working_memory(&mut self.bus, self.workload)?;
        let before = self.bus.aggregate_traffic_snapshot();
        let mut cpu = K16F32Cpu::new(0);
        let max_steps = u64::from(self.iterations)
            .saturating_mul(4_096)
            .saturating_add(100_000);
        let mut yields = 0_u64;
        loop {
            let stop = if let Some(predecoded) = self.predecoded.as_ref() {
                predecoded.run_until_stop(&mut cpu, &mut self.bus, max_steps)?
            } else {
                cpu.run_until_stop(&mut self.bus, max_steps)?
            };
            match stop {
                K16F32Stop::Halt => break,
                K16F32Stop::Yield if self.workload == IsaBenchmarkWorkload::YieldWake => {
                    yields += 1
                }
                K16F32Stop::StepLimit => {
                    return Err(format!(
                        "candidate {} workload {} exceeded {max_steps} steps",
                        self.candidate.name(),
                        self.workload.name(),
                    ));
                }
                stop => {
                    return Err(format!(
                        "candidate {} workload {} returned unexpected stop {stop:?}",
                        self.candidate.name(),
                        self.workload.name(),
                    ));
                }
            }
        }
        let after = self.bus.aggregate_traffic_snapshot();
        let ram = subtract(after.0, before.0);
        let mmio = subtract(after.1, before.1);
        let instruction_fetch = if self.predecoded.is_some() {
            IsaTraffic::default()
        } else {
            IsaTraffic {
                loads: cpu.retired_instructions(),
                stores: 0,
                bytes_read: cpu.retired_instructions().saturating_mul(4),
                bytes_written: 0,
            }
        };
        let observation = IsaBenchmarkObservation {
            candidate: self.candidate,
            workload: self.workload,
            iterations: self.iterations,
            checksum: cpu.register(self.image.result_register as usize),
            retired_instructions: cpu.retired_instructions(),
            yields,
            instruction_fetch,
            data_ram: IsaTraffic {
                loads: ram.loads.saturating_sub(instruction_fetch.loads),
                stores: ram.stores,
                bytes_read: ram.bytes_read.saturating_sub(instruction_fetch.bytes_read),
                bytes_written: ram.bytes_written,
            },
            mmio: IsaTraffic::from(mmio),
            cpu_state_bytes: K16F32Cpu::cpu_state_bytes(),
            translation_bytes: self
                .predecoded
                .as_ref()
                .map_or(0, PredecodedK16F32Program::retained_bytes),
        };
        observation.validate_checksum()?;
        Ok(observation)
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

struct ProgramImage {
    words: Vec<u32>,
    result_register: u8,
}

#[derive(Clone, Copy)]
enum FixupKind {
    BranchZ(u8),
    Jump,
    Call,
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
    fn branchz(&mut self, src: u8, label: &'static str) {
        let index = self.words.len();
        self.words.push(0);
        self.fixups.push((index, FixupKind::BranchZ(src), label));
    }
    fn jump(&mut self, label: &'static str) {
        let index = self.words.len();
        self.words.push(0);
        self.fixups.push((index, FixupKind::Jump, label));
    }
    fn call(&mut self, label: &'static str) {
        let index = self.words.len();
        self.words.push(0);
        self.fixups.push((index, FixupKind::Call, label));
    }
    fn finish(mut self, result_register: u8) -> Result<ProgramImage, String> {
        for (index, kind, label) in self.fixups {
            let target = self
                .labels
                .get(label)
                .copied()
                .ok_or_else(|| format!("missing K16-F32 benchmark label {label}"))?;
            let offset = i32::try_from(target).unwrap() - i32::try_from(index + 1).unwrap();
            self.words[index] = match kind {
                FixupKind::BranchZ(src) => branchz(src, offset),
                FixupKind::Jump => jump(offset),
                FixupKind::Call => call(offset),
            };
        }
        Ok(ProgramImage {
            words: self.words,
            result_register,
        })
    }
}

fn k16_f32_workload(
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
        IsaBenchmarkWorkload::MmioControl => mmio_control_program(iterations),
        IsaBenchmarkWorkload::YieldWake => yield_wake_program(iterations),
        IsaBenchmarkWorkload::PacketRing => packet_ring_program(iterations),
    }
}

fn compute32_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(materialize(0, iterations));
    p.words(materialize(1, 0));
    p.words(materialize(2, 0x6d2b_79f5));
    p.words(materialize(3, 17));
    p.words(materialize(4, 0x045d_9f3b));
    p.words(materialize(10, 32));
    p.words(materialize(11, 31));
    p.label("loop");
    p.word(eq(5, 1, 0));
    p.branchz(5, "body");
    p.word(halt());
    p.label("body");
    p.word(mul(7, 1, 3));
    p.word(add(2, 2, 7));
    p.word(mul(2, 2, 4));
    p.word(and(8, 1, 11));
    p.word(shl(7, 1, 8));
    p.word(sub(9, 10, 8));
    p.word(shr(12, 1, 9));
    p.word(or(7, 7, 12));
    p.word(xor(2, 2, 7));
    p.word(xor(8, 1, 2));
    p.word(and(8, 8, 11));
    p.word(shl(7, 2, 8));
    p.word(sub(9, 10, 8));
    p.word(shr(12, 2, 9));
    p.word(or(2, 7, 12));
    p.word(addi(1, 1, 1));
    p.jump("loop");
    p.finish(2)
}

fn branch_mix_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(materialize(0, iterations));
    p.words(materialize(1, 0));
    p.words(materialize(2, 0));
    p.words(materialize(3, 1));
    p.words(materialize(4, 3));
    p.label("loop");
    p.word(eq(5, 1, 0));
    p.branchz(5, "body");
    p.word(halt());
    p.label("body");
    p.word(and(6, 1, 3));
    p.branchz(6, "even");
    p.word(add(2, 2, 4));
    p.jump("next");
    p.label("even");
    p.word(add(2, 2, 3));
    p.label("next");
    p.word(addi(1, 1, 1));
    p.jump("loop");
    p.finish(2)
}

fn call_stack_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(materialize(0, iterations));
    p.words(materialize(1, 0));
    p.words(materialize(2, 0));
    p.words(materialize(15, STACK_TOP));
    p.label("loop");
    p.word(eq(3, 1, 0));
    p.branchz(3, "body");
    p.word(halt());
    p.label("body");
    p.call("outer");
    p.word(addi(1, 1, 1));
    p.jump("loop");
    p.label("outer");
    p.word(add(2, 2, 1));
    p.word(addi(2, 2, 1));
    p.call("inner");
    p.word(ret());
    p.label("inner");
    p.word(addi(2, 2, 2));
    p.word(ret());
    p.finish(2)
}

fn memory_program(iterations: u32, random: bool) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(materialize(0, iterations));
    p.words(materialize(1, 0));
    p.words(materialize(2, DATA_BASE));
    p.words(materialize(4, 63));
    p.words(materialize(5, 2));
    p.words(materialize(10, 0));
    if random {
        p.words(materialize(11, 17));
        p.words(materialize(12, 11));
    }
    p.label("update");
    p.word(eq(7, 1, 0));
    p.branchz(7, "update_body");
    p.jump("scan_init");
    p.label("update_body");
    if random {
        p.word(mul(10, 10, 11));
        p.word(add(10, 10, 12));
        p.word(and(10, 10, 4));
        p.word(shl(8, 10, 5));
    } else {
        p.word(and(8, 1, 4));
        p.word(shl(8, 8, 5));
    }
    p.word(add(8, 2, 8));
    p.word(load32(9, 8, 0));
    p.word(add(9, 9, 1));
    p.word(addi(9, 9, 1));
    p.word(store32(8, 9, 0));
    p.word(addi(1, 1, 1));
    p.jump("update");
    p.label("scan_init");
    p.words(materialize(0, 64));
    p.words(materialize(1, 0));
    p.words(materialize(10, 0));
    p.label("scan");
    p.word(eq(7, 1, 0));
    p.branchz(7, "scan_body");
    p.word(halt());
    p.label("scan_body");
    p.word(shl(8, 1, 5));
    p.word(add(8, 2, 8));
    p.word(load32(9, 8, 0));
    p.word(add(10, 10, 9));
    p.word(addi(1, 1, 1));
    p.jump("scan");
    p.finish(10)
}

fn copy_checksum_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(materialize(0, iterations));
    p.words(materialize(1, 0));
    p.words(materialize(2, 0));
    p.words(materialize(3, COPY_SOURCE));
    p.words(materialize(4, COPY_DESTINATION));
    p.words(materialize(8, 256));
    p.label("outer");
    p.word(eq(6, 1, 0));
    p.branchz(6, "outer_body");
    p.word(halt());
    p.label("outer_body");
    p.words(materialize(7, 0));
    p.label("inner");
    p.word(eq(6, 7, 8));
    p.branchz(6, "inner_body");
    p.jump("next_outer");
    p.label("inner_body");
    p.word(add(10, 3, 7));
    p.word(load8(11, 10, 0));
    p.word(add(10, 4, 7));
    p.word(store8(10, 11, 0));
    p.word(add(12, 11, 1));
    p.word(add(2, 2, 12));
    p.word(addi(7, 7, 1));
    p.jump("inner");
    p.label("next_outer");
    p.word(addi(1, 1, 1));
    p.jump("outer");
    p.finish(2)
}

fn mmio_control_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(materialize(0, iterations));
    p.words(materialize(1, 0));
    p.words(materialize(2, 0));
    p.words(materialize(3, MMIO_BASE));
    p.label("loop");
    p.word(eq(5, 1, 0));
    p.branchz(5, "body");
    p.word(halt());
    p.label("body");
    p.word(store32(3, 1, 0));
    p.word(load32(6, 3, 0));
    p.word(add(2, 2, 6));
    p.word(addi(1, 1, 1));
    p.jump("loop");
    p.finish(2)
}

fn yield_wake_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(materialize(0, iterations));
    p.words(materialize(1, 0));
    p.words(materialize(2, 0));
    p.label("loop");
    p.word(eq(5, 1, 0));
    p.branchz(5, "body");
    p.word(halt());
    p.label("body");
    p.word(addi(2, 2, 1));
    p.word(addi(1, 1, 1));
    p.word(yield_now());
    p.jump("loop");
    p.finish(2)
}

fn packet_ring_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(materialize(0, iterations));
    p.words(materialize(1, 0));
    p.words(materialize(2, 0));
    p.words(materialize(3, PACKET_RING));
    p.words(materialize(7, 16));
    p.words(materialize(11, 13));
    p.words(materialize(12, 7));
    p.words(materialize(13, 4));
    p.words(materialize(14, 255));
    p.label("outer");
    p.word(eq(5, 1, 0));
    p.branchz(5, "outer_body");
    p.word(halt());
    p.label("outer_body");
    p.words(materialize(6, 0));
    p.label("inner");
    p.word(eq(5, 6, 7));
    p.branchz(5, "inner_body");
    p.jump("next_outer");
    p.label("inner_body");
    p.word(and(9, 1, 12));
    p.word(shl(9, 9, 13));
    p.word(add(9, 3, 9));
    p.word(mul(10, 1, 11));
    p.word(add(10, 10, 6));
    p.word(and(10, 10, 14));
    p.word(add(9, 9, 6));
    p.word(store8(9, 10, 0));
    p.word(load8(10, 9, 0));
    p.word(add(2, 2, 10));
    p.word(addi(6, 6, 1));
    p.jump("inner");
    p.label("next_outer");
    p.word(addi(1, 1, 1));
    p.jump("outer");
    p.finish(2)
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
    after: crate::low_bus::MachineBusTrafficSnapshot,
    before: crate::low_bus::MachineBusTrafficSnapshot,
) -> crate::low_bus::MachineBusTrafficSnapshot {
    crate::low_bus::MachineBusTrafficSnapshot {
        loads: after.loads.saturating_sub(before.loads),
        stores: after.stores.saturating_sub(before.stores),
        bytes_read: after.bytes_read.saturating_sub(before.bytes_read),
        bytes_written: after.bytes_written.saturating_sub(before.bytes_written),
    }
}
