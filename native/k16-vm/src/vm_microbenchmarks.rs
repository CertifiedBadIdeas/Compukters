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

use crate::k16::{K16Cpu, K16Signal};
use crate::low_bus::{MachineBus, MmioDevice};
use crate::low_machine::MemoryFault;

const MEMORY_SIZE: usize = 1024;
const DATA_ADDR: u32 = 512;
const MMIO_ADDR: u32 = 0x1000_0000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VmBenchmarkWorkload {
    ComputeLoop,
    MemoryLoop,
    MmioLoop,
}

impl VmBenchmarkWorkload {
    pub fn name(self) -> &'static str {
        match self {
            Self::ComputeLoop => "compute-loop",
            Self::MemoryLoop => "memory-loop",
            Self::MmioLoop => "mmio-loop",
        }
    }

    pub fn all() -> &'static [Self] {
        &[Self::ComputeLoop, Self::MemoryLoop, Self::MmioLoop]
    }
}

impl std::str::FromStr for VmBenchmarkWorkload {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "compute-loop" => Ok(Self::ComputeLoop),
            "memory-loop" => Ok(Self::MemoryLoop),
            "mmio-loop" => Ok(Self::MmioLoop),
            _ => Err(format!("unknown VM benchmark workload: {value}")),
        }
    }
}

pub fn run_k16_workload(workload: VmBenchmarkWorkload, iterations: u32) -> Result<u32, String> {
    let mut bus = MachineBus::new(MEMORY_SIZE).map_err(|error| error.to_string())?;
    if workload == VmBenchmarkWorkload::MmioLoop {
        bus.map_mmio(MMIO_ADDR, Box::new(BenchmarkRegisterDevice { value: 0 }))
            .map_err(|error| error.to_string())?;
    }
    let (words, result_register) = k16_workload(workload, iterations);
    write_words(&mut bus, 0, &words)?;
    let mut cpu = K16Cpu::new(0);
    match cpu
        .run_until_signal(&mut bus, k16_max_steps(workload, iterations))
        .map_err(|error| error.to_string())?
    {
        K16Signal::Halt => Ok(cpu.register(result_register)),
        signal => Err(format!("unexpected K16 signal: {signal:?}")),
    }
}

fn k16_workload(workload: VmBenchmarkWorkload, iterations: u32) -> (Vec<u16>, usize) {
    match workload {
        VmBenchmarkWorkload::ComputeLoop => {
            let mut words = vec![
                const32(0),
                low16(iterations),
                high16(iterations),
                const4(1, 0),
                const4(2, 1),
            ];
            words.extend(eq(3, 1, 0));
            words.extend([branch_if_zero(3, 1), halt()]);
            words.extend(add(1, 1, 2));
            words.push(branch_if_nonzero(2, -7));
            (words, 1)
        }
        VmBenchmarkWorkload::MemoryLoop => {
            let mut words = vec![
                const32(0),
                low16(iterations),
                high16(iterations),
                const4(1, 0),
                const4(2, 1),
                const32(4),
                low16(DATA_ADDR),
                high16(DATA_ADDR),
                const32(6),
                low16(22),
                high16(22),
            ];
            words.extend(eq(3, 1, 0));
            words.extend([branch_if_zero(3, 2), load32(5, 4), halt(), load32(5, 4)]);
            words.extend(add(5, 5, 2));
            words.push(store32(4, 5));
            words.extend(add(1, 1, 2));
            words.push(jump(6));
            (words, 5)
        }
        VmBenchmarkWorkload::MmioLoop => {
            let mut words = vec![
                const32(0),
                low16(iterations),
                high16(iterations),
                const4(1, 0),
                const4(2, 1),
                const32(4),
                low16(MMIO_ADDR),
                high16(MMIO_ADDR),
                const32(6),
                low16(22),
                high16(22),
            ];
            words.extend(eq(3, 1, 0));
            words.extend([branch_if_zero(3, 2), load32(5, 4), halt()]);
            words.extend(add(1, 1, 2));
            words.extend([store32(4, 1), load32(5, 4), jump(6)]);
            (words, 5)
        }
    }
}

fn k16_max_steps(workload: VmBenchmarkWorkload, iterations: u32) -> u64 {
    match workload {
        VmBenchmarkWorkload::ComputeLoop => u64::from(iterations) * 4 + 16,
        VmBenchmarkWorkload::MemoryLoop => u64::from(iterations) * 7 + 16,
        VmBenchmarkWorkload::MmioLoop => u64::from(iterations) * 7 + 16,
    }
}

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

fn write_words(bus: &mut MachineBus, address: u32, words: &[u16]) -> Result<(), String> {
    for (index, word) in words.iter().copied().enumerate() {
        bus.store_u16(address + (index as u32 * 2), word)
            .map_err(|error| error.to_string())?;
    }
    Ok(())
}

fn const4(dst: u8, value: u8) -> u16 {
    0x1000 | (u16::from(dst) << 8) | u16::from(value & 0x0f)
}

fn const32(dst: u8) -> u16 {
    0xe001 | (u16::from(dst) << 8)
}

fn add(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x0, lhs, rhs)
}

fn eq(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x8, lhs, rhs)
}

fn alu_rrr(dst: u8, subop: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x2000 | (u16::from(dst) << 8) | u16::from(subop),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

fn load32(dst: u8, addr: u8) -> u16 {
    0x4002 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn store32(addr: u8, src: u8) -> u16 {
    0x5002 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn branch_if_zero(register: u8, offset_words: i8) -> u16 {
    0x6000 | (u16::from(register) << 8) | encode_signed_nibble(offset_words)
}

fn branch_if_nonzero(register: u8, offset_words: i8) -> u16 {
    0x6000 | (u16::from(register) << 8) | 0x0010 | encode_signed_nibble(offset_words)
}

fn jump(target: u8) -> u16 {
    0x7000 | (u16::from(target) << 8)
}

fn halt() -> u16 {
    0x0001
}

fn low16(value: u32) -> u16 {
    value as u16
}

fn high16(value: u32) -> u16 {
    (value >> 16) as u16
}

fn encode_signed_nibble(value: i8) -> u16 {
    assert!((-8..=7).contains(&value));
    u16::from((value as i16 & 0x000f) as u8)
}
