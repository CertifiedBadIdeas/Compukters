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

use super::programs::{PACKET_BYTES, RING_ENTRIES};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum IsaBenchmarkWorkload {
    Compute32,
    BranchMix,
    CallStack,
    MemorySequential,
    MemoryRandom,
    CopyChecksum,
    MmioControl,
    YieldWake,
    PacketRing,
    U64Mix,
    Fixed64Geometry,
    U64Memory,
}

impl IsaBenchmarkWorkload {
    pub const fn all() -> &'static [Self] {
        &[
            Self::Compute32,
            Self::BranchMix,
            Self::CallStack,
            Self::MemorySequential,
            Self::MemoryRandom,
            Self::CopyChecksum,
            Self::MmioControl,
            Self::YieldWake,
            Self::PacketRing,
        ]
    }

    pub const fn name(self) -> &'static str {
        match self {
            Self::Compute32 => "compute32",
            Self::BranchMix => "branch-mix",
            Self::CallStack => "call-stack",
            Self::MemorySequential => "memory-sequential",
            Self::MemoryRandom => "memory-random",
            Self::CopyChecksum => "copy-checksum",
            Self::MmioControl => "mmio-control",
            Self::YieldWake => "yield-wake",
            Self::PacketRing => "packet-ring",
            Self::U64Mix => "u64-mix",
            Self::Fixed64Geometry => "fixed64-geometry",
            Self::U64Memory => "u64-memory",
        }
    }

    pub const fn u64_compiled_c() -> &'static [Self] {
        &[Self::U64Mix, Self::Fixed64Geometry, Self::U64Memory]
    }
}

pub fn native_checksum(workload: IsaBenchmarkWorkload, iterations: u32) -> u32 {
    match workload {
        IsaBenchmarkWorkload::Compute32 => compute32(iterations),
        IsaBenchmarkWorkload::BranchMix => branch_mix(iterations),
        IsaBenchmarkWorkload::CallStack => call_stack(iterations),
        IsaBenchmarkWorkload::MemorySequential => memory_sequential(iterations),
        IsaBenchmarkWorkload::MemoryRandom => memory_random(iterations),
        IsaBenchmarkWorkload::CopyChecksum => copy_checksum(iterations),
        IsaBenchmarkWorkload::MmioControl => triangular_checksum(iterations),
        IsaBenchmarkWorkload::YieldWake => iterations,
        IsaBenchmarkWorkload::PacketRing => packet_ring(iterations),
        IsaBenchmarkWorkload::U64Mix => u64_mix(iterations),
        IsaBenchmarkWorkload::Fixed64Geometry => fixed64_geometry(iterations),
        IsaBenchmarkWorkload::U64Memory => u64_memory(iterations),
    }
}

fn compute32(iterations: u32) -> u32 {
    let mut accumulator = 0x6d2b_79f5_u32;
    for index in 0..iterations {
        accumulator = accumulator
            .wrapping_add(index.wrapping_mul(17))
            .wrapping_mul(0x045d_9f3b)
            ^ index.rotate_left(index & 31);
        accumulator = accumulator.rotate_left((index ^ accumulator) & 31);
    }
    accumulator
}

fn branch_mix(iterations: u32) -> u32 {
    (0..iterations).fold(0_u32, |sum, index| {
        sum.wrapping_add(if index & 1 == 0 { 1 } else { 3 })
    })
}

#[inline(never)]
fn call_stack_inner(value: u32, index: u32) -> u32 {
    value.wrapping_add(index).wrapping_add(1)
}

#[inline(never)]
fn call_stack_outer(value: u32, index: u32) -> u32 {
    call_stack_inner(value, index).wrapping_add(2)
}

fn call_stack(iterations: u32) -> u32 {
    (0..iterations).fold(0_u32, call_stack_outer)
}

fn memory_sequential(iterations: u32) -> u32 {
    let mut cells = [0_u32; 64];
    for index in 0..iterations {
        let slot = index as usize & 63;
        cells[slot] = cells[slot].wrapping_add(index).wrapping_add(1);
    }
    cells.into_iter().fold(0_u32, u32::wrapping_add)
}

fn memory_random(iterations: u32) -> u32 {
    let mut cells = [0_u32; 64];
    let mut slot = 0_u32;
    for index in 0..iterations {
        slot = slot.wrapping_mul(17).wrapping_add(11) & 63;
        cells[slot as usize] = cells[slot as usize].wrapping_add(index).wrapping_add(1);
    }
    cells.into_iter().fold(0_u32, u32::wrapping_add)
}

fn copy_checksum(iterations: u32) -> u32 {
    let source =
        std::array::from_fn::<_, 256, _>(|index| (index as u8).wrapping_mul(29).wrapping_add(7));
    let mut destination = [0_u8; 256];
    let mut checksum = 0_u32;
    for iteration in 0..iterations {
        destination.copy_from_slice(&source);
        checksum = destination.iter().fold(checksum, |sum, byte| {
            sum.wrapping_add(u32::from(*byte).wrapping_add(iteration))
        });
    }
    checksum
}

fn triangular_checksum(iterations: u32) -> u32 {
    (0..iterations).fold(0_u32, u32::wrapping_add)
}

fn packet_ring(iterations: u32) -> u32 {
    let mut ring = [[0_u8; PACKET_BYTES]; RING_ENTRIES];
    let mut checksum = 0_u32;
    for sequence in 0..iterations {
        let slot = sequence as usize % RING_ENTRIES;
        for (offset, byte) in ring[slot].iter_mut().enumerate() {
            *byte = (sequence as u8).wrapping_mul(13).wrapping_add(offset as u8);
        }
        checksum = ring[slot]
            .iter()
            .fold(checksum, |sum, byte| sum.wrapping_add(u32::from(*byte)));
    }
    checksum
}

fn u64_mix(iterations: u32) -> u32 {
    let mut state = 0x9e37_79b9_7f4a_7c15_u64;
    for index in 0..iterations {
        let lane = u64::from(index)
            .wrapping_mul(0xd6e8_feb8_6659_fd93)
            .wrapping_add(0xa5a3_564e_27f8_862f);
        state ^= lane.rotate_left(index & 63);
        state = state
            .wrapping_mul(0x9e37_79b1_85eb_ca87)
            .wrapping_add(0x632b_e59b_d9b4_e019);
        state ^= state >> 29;
    }
    fold_u64(state)
}

fn fixed64_geometry(iterations: u32) -> u32 {
    const COS_Q16: i64 = 46_341;
    const SIN_Q16: i64 = 46_340;
    let mut checksum = 0x243f_6a88_85a3_08d3_u64;
    for index in 0..iterations {
        let x = (i64::from((index & 0xffff) as i32) - 32_768) << 16;
        let y_index = index.wrapping_mul(17) & 0xffff;
        let y = (i64::from(y_index as i32) - 32_768) << 16;
        let rotated_x = (x * COS_Q16 - y * SIN_Q16) >> 16;
        let rotated_y = (x * SIN_Q16 + y * COS_Q16) >> 16;
        checksum = checksum.wrapping_add(
            (rotated_x as u64).wrapping_mul(0x0000_0001_0000_01b3)
                ^ (rotated_y as u64).rotate_left(index & 63),
        );
    }
    fold_u64(checksum)
}

fn u64_memory(iterations: u32) -> u32 {
    let mut cells = std::array::from_fn::<_, 64, _>(|index| {
        (index as u64)
            .wrapping_mul(0xa5a3_564e_27f8_862f)
            .wrapping_add(7)
    });
    let mut slot = 0_u32;
    for index in 0..iterations {
        slot = slot.wrapping_mul(17).wrapping_add(11) & 63;
        let value = cells[slot as usize]
            .wrapping_add(
                u64::from(index)
                    .wrapping_add(1)
                    .wrapping_mul(0x9e37_79b9_7f4a_7c15),
            )
            .rotate_left(index & 63)
            ^ 0xd6e8_feb8_6659_fd93;
        cells[slot as usize] = value;
    }
    fold_u64(cells.into_iter().fold(0_u64, u64::wrapping_add))
}

fn fold_u64(value: u64) -> u32 {
    value as u32 ^ (value >> 32) as u32
}
