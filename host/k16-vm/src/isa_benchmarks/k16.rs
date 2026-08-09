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
use crate::k16::{
    DecodeResult, InstructionDecoder, K16CachedDecoder, K16Cpu, K16CpuSnapshot, K16Decoder,
    K16Signal, K16Trap,
};
use crate::low_bus::{MachineBus, MachineBusTrafficSnapshot, MmioDevice};
use crate::low_machine::{MemoryBus, MemoryFault};
use std::collections::{HashMap, HashSet};

const COPY_SOURCE: u32 = DATA_BASE;
const COPY_DESTINATION: u32 = DATA_BASE + 0x0100;
const PACKET_RING: u32 = DATA_BASE;

pub(super) fn run(
    candidate: IsaBenchmarkCandidate,
    workload: IsaBenchmarkWorkload,
    iterations: u32,
) -> Result<IsaBenchmarkObservation, String> {
    if !matches!(
        candidate,
        IsaBenchmarkCandidate::K16 | IsaBenchmarkCandidate::K16Cached
    ) {
        return Err(format!("candidate {} is not implemented", candidate.name()));
    }

    let image = k16_workload(workload, iterations)?;
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
    write_words(&mut bus, &image.words)?;
    let before = bus.stats_snapshot();
    let mut cpu = K16Cpu::new(0);
    let mut decoder = TrackingDecoder::new(candidate == IsaBenchmarkCandidate::K16Cached);
    let max_steps = u64::from(iterations)
        .saturating_mul(4_096)
        .saturating_add(100_000);
    let mut yields = 0_u64;
    loop {
        match cpu
            .run_until_signal_with_decoder(&mut bus, &mut decoder, max_steps)
            .map_err(|error| error.to_string())?
        {
            K16Signal::Halt => break,
            K16Signal::Wait if workload == IsaBenchmarkWorkload::YieldWake => {
                yields += 1;
            }
            K16Signal::StepLimitExceeded => {
                return Err(format!(
                    "candidate {} workload {} exceeded {max_steps} steps",
                    candidate.name(),
                    workload.name(),
                ));
            }
            signal => {
                return Err(format!(
                    "candidate {} workload {} returned unexpected signal {signal:?}",
                    candidate.name(),
                    workload.name(),
                ));
            }
        }
    }

    let after = bus.stats_snapshot();
    let ram = subtract_traffic(after.ram, before.ram);
    let mmio = subtract_traffic(after.mmio, before.mmio);
    let instruction_fetch = decoder.fetch;
    let data_ram = IsaTraffic {
        loads: ram.loads.saturating_sub(instruction_fetch.loads),
        stores: ram.stores,
        bytes_read: ram.bytes_read.saturating_sub(instruction_fetch.bytes_read),
        bytes_written: ram.bytes_written,
    };
    let observation = IsaBenchmarkObservation {
        candidate,
        workload,
        iterations,
        checksum: cpu.register(image.result_register as usize),
        retired_instructions: cpu.snapshot().metrics_steps,
        yields,
        instruction_fetch,
        data_ram,
        mmio: mmio.into(),
        cpu_state_bytes: std::mem::size_of::<K16CpuSnapshot>(),
        translation_bytes: decoder.retained_bytes(),
    };
    observation.validate_checksum()?;
    Ok(observation)
}

struct ProgramImage {
    words: Vec<u16>,
    result_register: u8,
}

#[derive(Default)]
struct ProgramBuilder {
    words: Vec<u16>,
    labels: HashMap<&'static str, u32>,
    fixups: Vec<(usize, &'static str)>,
}

impl ProgramBuilder {
    fn word(&mut self, word: u16) {
        self.words.push(word);
    }

    fn words(&mut self, words: impl IntoIterator<Item = u16>) {
        self.words.extend(words);
    }

    fn label(&mut self, name: &'static str) {
        assert!(self
            .labels
            .insert(name, self.words.len() as u32 * 2)
            .is_none());
    }

    fn load_label(&mut self, register: u8, label: &'static str) {
        self.word(0xe001 | (u16::from(register) << 8));
        let low_word = self.words.len();
        self.words.extend([0, 0]);
        self.fixups.push((low_word, label));
    }

    fn finish(mut self, result_register: u8) -> Result<ProgramImage, String> {
        for (low_word, label) in self.fixups {
            let address = self
                .labels
                .get(label)
                .copied()
                .ok_or_else(|| format!("missing K16 benchmark label {label}"))?;
            self.words[low_word] = address as u16;
            self.words[low_word + 1] = (address >> 16) as u16;
        }
        Ok(ProgramImage {
            words: self.words,
            result_register,
        })
    }
}

fn k16_workload(workload: IsaBenchmarkWorkload, iterations: u32) -> Result<ProgramImage, String> {
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
    p.words(const32(0, iterations));
    p.word(const4(1, 0));
    p.words(const32(2, 0x6d2b_79f5));
    p.words(const32(3, 17));
    p.words(const32(4, 0x045d_9f3b));
    p.words(const32(10, 32));
    p.words(const32(11, 31));
    p.load_label(6, "loop");
    p.label("loop");
    p.words(eq(5, 1, 0));
    p.word(branch_if_zero(5, 1));
    p.word(halt());
    p.words(mul(7, 1, 3));
    p.words(add(2, 2, 7));
    p.words(mul(2, 2, 4));
    p.words(and(8, 1, 11));
    p.words(shl(7, 1, 8));
    p.words(sub(9, 10, 8));
    p.words(shr(12, 1, 9));
    p.words(or(7, 7, 12));
    p.words(xor(2, 2, 7));
    p.words(xor(8, 1, 2));
    p.words(and(8, 8, 11));
    p.words(shl(7, 2, 8));
    p.words(sub(9, 10, 8));
    p.words(shr(12, 2, 9));
    p.words(or(2, 7, 12));
    p.words(addi(1, 1, 1));
    p.word(jump(6));
    p.finish(2)
}

fn branch_mix_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(const32(0, iterations));
    p.word(const4(1, 0));
    p.word(const4(2, 0));
    p.word(const4(3, 1));
    p.word(const4(4, 3));
    p.load_label(5, "loop");
    p.label("loop");
    p.words(eq(6, 1, 0));
    p.word(branch_if_zero(6, 1));
    p.word(halt());
    p.words(and(7, 1, 3));
    p.word(branch_if_zero(7, 3));
    p.words(add(2, 2, 4));
    p.word(branch_if_nonzero(3, 2));
    p.words(add(2, 2, 3));
    p.words(addi(1, 1, 1));
    p.word(jump(5));
    p.finish(2)
}

fn call_stack_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(const32(0, iterations));
    p.word(const4(1, 0));
    p.word(const4(2, 0));
    p.words(const32(15, STACK_TOP));
    p.load_label(4, "loop");
    p.load_label(5, "outer");
    p.load_label(6, "inner");
    p.label("loop");
    p.words(eq(3, 1, 0));
    p.word(branch_if_zero(3, 1));
    p.word(halt());
    p.word(call(5));
    p.words(addi(1, 1, 1));
    p.word(jump(4));
    p.label("outer");
    p.words(add(2, 2, 1));
    p.words(addi(2, 2, 1));
    p.word(call(6));
    p.word(ret());
    p.label("inner");
    p.words(addi(2, 2, 2));
    p.word(ret());
    p.finish(2)
}

fn memory_program(iterations: u32, random: bool) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(const32(0, iterations));
    p.word(const4(1, 0));
    p.words(const32(2, DATA_BASE));
    p.words(const32(4, 63));
    p.word(const4(5, 2));
    p.word(const4(10, 0));
    if random {
        p.words(const32(11, 17));
        p.words(const32(12, 11));
    }
    p.load_label(6, "update");
    p.load_label(13, "scan_init");
    p.label("update");
    p.words(eq(7, 1, 0));
    p.word(branch_if_zero(7, 1));
    p.word(jump(13));
    if random {
        p.words(mul(10, 10, 11));
        p.words(add(10, 10, 12));
        p.words(and(10, 10, 4));
        p.words(shl(8, 10, 5));
    } else {
        p.words(and(8, 1, 4));
        p.words(shl(8, 8, 5));
    }
    p.words(add(8, 2, 8));
    p.word(load32(9, 8));
    p.words(add(9, 9, 1));
    p.words(addi(9, 9, 1));
    p.word(store32(8, 9));
    p.words(addi(1, 1, 1));
    p.word(jump(6));
    p.label("scan_init");
    p.words(const32(0, 64));
    p.word(const4(1, 0));
    p.word(const4(10, 0));
    p.load_label(11, "scan");
    p.label("scan");
    p.words(eq(7, 1, 0));
    p.word(branch_if_zero(7, 1));
    p.word(halt());
    p.words(shl(8, 1, 5));
    p.words(add(8, 2, 8));
    p.word(load32(9, 8));
    p.words(add(10, 10, 9));
    p.words(addi(1, 1, 1));
    p.word(jump(11));
    p.finish(10)
}

fn copy_checksum_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(const32(0, iterations));
    p.word(const4(1, 0));
    p.word(const4(2, 0));
    p.words(const32(3, COPY_SOURCE));
    p.words(const32(4, COPY_DESTINATION));
    p.words(const32(8, 256));
    p.load_label(5, "outer");
    p.load_label(9, "inner");
    p.load_label(14, "next_outer");
    p.label("outer");
    p.words(eq(6, 1, 0));
    p.word(branch_if_zero(6, 1));
    p.word(halt());
    p.word(const4(7, 0));
    p.label("inner");
    p.words(eq(6, 7, 8));
    p.word(branch_if_zero(6, 1));
    p.word(jump(14));
    p.words(add(10, 3, 7));
    p.word(load8(11, 10));
    p.words(add(10, 4, 7));
    p.word(store8(10, 11));
    p.words(add(12, 11, 1));
    p.words(add(2, 2, 12));
    p.words(addi(7, 7, 1));
    p.word(jump(9));
    p.label("next_outer");
    p.words(addi(1, 1, 1));
    p.word(jump(5));
    p.finish(2)
}

fn mmio_control_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(const32(0, iterations));
    p.word(const4(1, 0));
    p.word(const4(2, 0));
    p.words(const32(3, MMIO_BASE));
    p.load_label(4, "loop");
    p.label("loop");
    p.words(eq(5, 1, 0));
    p.word(branch_if_zero(5, 1));
    p.word(halt());
    p.word(store32(3, 1));
    p.word(load32(6, 3));
    p.words(add(2, 2, 6));
    p.words(addi(1, 1, 1));
    p.word(jump(4));
    p.finish(2)
}

fn yield_wake_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(const32(0, iterations));
    p.word(const4(1, 0));
    p.word(const4(2, 0));
    p.load_label(4, "loop");
    p.label("loop");
    p.words(eq(5, 1, 0));
    p.word(branch_if_zero(5, 1));
    p.word(halt());
    p.words(addi(2, 2, 1));
    p.words(addi(1, 1, 1));
    p.word(wait());
    p.word(jump(4));
    p.finish(2)
}

fn packet_ring_program(iterations: u32) -> Result<ProgramImage, String> {
    let mut p = ProgramBuilder::default();
    p.words(const32(0, iterations));
    p.word(const4(1, 0));
    p.word(const4(2, 0));
    p.words(const32(3, PACKET_RING));
    p.words(const32(7, 16));
    p.words(const32(11, 13));
    p.words(const32(12, 7));
    p.word(const4(13, 4));
    p.words(const32(14, 255));
    p.load_label(4, "outer");
    p.load_label(8, "inner");
    p.load_label(15, "next_outer");
    p.label("outer");
    p.words(eq(5, 1, 0));
    p.word(branch_if_zero(5, 1));
    p.word(halt());
    p.word(const4(6, 0));
    p.label("inner");
    p.words(eq(5, 6, 7));
    p.word(branch_if_zero(5, 1));
    p.word(jump(15));
    p.words(and(9, 1, 12));
    p.words(shl(9, 9, 13));
    p.words(add(9, 3, 9));
    p.words(mul(10, 1, 11));
    p.words(add(10, 10, 6));
    p.words(and(10, 10, 14));
    p.words(add(9, 9, 6));
    p.word(store8(9, 10));
    p.word(load8(10, 9));
    p.words(add(2, 2, 10));
    p.words(addi(6, 6, 1));
    p.word(jump(8));
    p.label("next_outer");
    p.words(addi(1, 1, 1));
    p.word(jump(4));
    p.finish(2)
}

enum BenchmarkDecoder {
    Direct(K16Decoder),
    Cached(K16CachedDecoder),
}

impl InstructionDecoder for BenchmarkDecoder {
    fn decode(&mut self, bus: &mut dyn MemoryBus, pc: u32) -> Result<DecodeResult, K16Trap> {
        match self {
            Self::Direct(decoder) => decoder.decode(bus, pc),
            Self::Cached(decoder) => decoder.decode(bus, pc),
        }
    }
}

struct TrackingDecoder {
    decoder: BenchmarkDecoder,
    seen: HashSet<u32>,
    fetch: IsaTraffic,
}

impl TrackingDecoder {
    fn new(cached: bool) -> Self {
        Self {
            decoder: if cached {
                BenchmarkDecoder::Cached(K16CachedDecoder::new())
            } else {
                BenchmarkDecoder::Direct(K16Decoder::new())
            },
            seen: HashSet::new(),
            fetch: IsaTraffic::default(),
        }
    }

    fn retained_bytes(&self) -> usize {
        match &self.decoder {
            BenchmarkDecoder::Direct(_) => 0,
            BenchmarkDecoder::Cached(decoder) => decoder.estimated_retained_bytes(),
        }
    }
}

impl InstructionDecoder for TrackingDecoder {
    fn decode(&mut self, bus: &mut dyn MemoryBus, pc: u32) -> Result<DecodeResult, K16Trap> {
        let record_fetch =
            matches!(self.decoder, BenchmarkDecoder::Direct(_)) || self.seen.insert(pc);
        let result = self.decoder.decode(bus, pc)?;
        if record_fetch {
            let bytes = u64::from(result.next_pc.wrapping_sub(pc));
            self.fetch.loads += bytes / 2;
            self.fetch.bytes_read += bytes;
        }
        Ok(result)
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
                "benchmark register offset {offset} is not mapped",
            )))
        }
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        if offset == 0 {
            self.value = value;
            Ok(())
        } else {
            Err(MemoryFault::new(format!(
                "benchmark register offset {offset} is not mapped",
            )))
        }
    }
}

fn write_words(bus: &mut MachineBus, words: &[u16]) -> Result<(), String> {
    for (index, word) in words.iter().copied().enumerate() {
        bus.store_u16(index as u32 * 2, word)
            .map_err(|error| error.to_string())?;
    }
    Ok(())
}

fn subtract_traffic(
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

impl From<MachineBusTrafficSnapshot> for IsaTraffic {
    fn from(value: MachineBusTrafficSnapshot) -> Self {
        Self {
            loads: value.loads,
            stores: value.stores,
            bytes_read: value.bytes_read,
            bytes_written: value.bytes_written,
        }
    }
}

fn const4(register: u8, value: u8) -> u16 {
    0x1000 | (u16::from(register) << 8) | u16::from(value & 0x0f)
}

fn const32(register: u8, value: u32) -> [u16; 3] {
    [
        0xe001 | (u16::from(register) << 8),
        value as u16,
        (value >> 16) as u16,
    ]
}

fn alu_rrr(dst: u8, subop: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x2000 | (u16::from(dst) << 8) | u16::from(subop),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

fn add(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x0, lhs, rhs)
}
fn sub(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x1, lhs, rhs)
}
fn and(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x2, lhs, rhs)
}
fn or(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x3, lhs, rhs)
}
fn xor(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x4, lhs, rhs)
}
fn shl(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x5, lhs, rhs)
}
fn shr(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x6, lhs, rhs)
}
fn eq(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x8, lhs, rhs)
}
fn mul(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0xc, lhs, rhs)
}

fn addi(dst: u8, src: u8, immediate: i16) -> [u16; 2] {
    [
        0x3002 | (u16::from(dst) << 8) | (u16::from(src) << 4),
        immediate as u16,
    ]
}

fn load8(dst: u8, addr: u8) -> u16 {
    0x4000 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn load32(dst: u8, addr: u8) -> u16 {
    0x4002 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn store8(addr: u8, src: u8) -> u16 {
    0x5000 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn store32(addr: u8, src: u8) -> u16 {
    0x5002 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn branch_if_zero(register: u8, offset_words: i8) -> u16 {
    0x6000 | (u16::from(register) << 8) | encode_signed_nibble(offset_words)
}

fn branch_if_nonzero(register: u8, offset_words: i8) -> u16 {
    0x6010 | (u16::from(register) << 8) | encode_signed_nibble(offset_words)
}

fn jump(target: u8) -> u16 {
    0x7000 | (u16::from(target) << 8)
}
fn call(target: u8) -> u16 {
    0x8000 | (u16::from(target) << 8)
}
fn ret() -> u16 {
    0x9000
}
fn halt() -> u16 {
    0x0001
}
fn wait() -> u16 {
    0x0006
}

fn encode_signed_nibble(value: i8) -> u16 {
    assert!((-8..=7).contains(&value));
    u16::from((value as i16 & 0x000f) as u8)
}
