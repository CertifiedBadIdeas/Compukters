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
    native_checksum, IsaBenchmarkCandidate, IsaBenchmarkObservation, IsaBenchmarkWorkload,
    IsaTraffic, PACKET_BYTES, RING_ENTRIES,
};
use std::hint::black_box;

pub(super) struct Prepared {
    workload: IsaBenchmarkWorkload,
    iterations: u32,
    copy_source: [u8; 256],
    copy_destination: [u8; 256],
    packet_ring: [[u8; PACKET_BYTES]; RING_ENTRIES],
}

impl Prepared {
    pub(super) fn new(workload: IsaBenchmarkWorkload, iterations: u32) -> Self {
        Self {
            workload,
            iterations,
            copy_source: std::array::from_fn(|index| {
                (index as u8).wrapping_mul(29).wrapping_add(7)
            }),
            copy_destination: [0; 256],
            packet_ring: [[0; PACKET_BYTES]; RING_ENTRIES],
        }
    }

    pub(super) fn execute(&mut self) -> Result<IsaBenchmarkObservation, String> {
        let iterations = black_box(self.iterations);
        let checksum = match self.workload {
            IsaBenchmarkWorkload::CopyChecksum => self.native_copy_checksum(iterations),
            IsaBenchmarkWorkload::PacketRing => self.native_packet_ring(iterations),
            IsaBenchmarkWorkload::MmioControl => native_mmio_control(iterations),
            IsaBenchmarkWorkload::YieldWake => native_yield_wake(iterations),
            _ => native_checksum(self.workload, iterations),
        };
        let checksum = black_box(checksum);
        let observation = IsaBenchmarkObservation {
            candidate: IsaBenchmarkCandidate::NativeRust,
            workload: self.workload,
            iterations: self.iterations,
            checksum,
            retired_instructions: 0,
            yields: 0,
            instruction_fetch: IsaTraffic::default(),
            data_ram: IsaTraffic::default(),
            mmio: IsaTraffic::default(),
            cpu_state_bytes: 0,
            translation_bytes: 0,
        };
        observation.validate_checksum()?;
        Ok(observation)
    }

    fn native_copy_checksum(&mut self, iterations: u32) -> u32 {
        let mut checksum = 0_u32;
        for iteration in 0..iterations {
            let source = black_box(&self.copy_source);
            let destination = black_box(&mut self.copy_destination);
            destination.copy_from_slice(source);
            checksum = black_box(&*destination).iter().fold(checksum, |sum, byte| {
                sum.wrapping_add(u32::from(*byte).wrapping_add(iteration))
            });
        }
        checksum
    }

    fn native_packet_ring(&mut self, iterations: u32) -> u32 {
        let mut checksum = 0_u32;
        for sequence in 0..iterations {
            let slot = sequence as usize % RING_ENTRIES;
            let packet = black_box(&mut self.packet_ring[slot]);
            for (offset, byte) in packet.iter_mut().enumerate() {
                *byte = (sequence as u8).wrapping_mul(13).wrapping_add(offset as u8);
            }
            checksum = black_box(&self.packet_ring[slot])
                .iter()
                .fold(checksum, |sum, byte| sum.wrapping_add(u32::from(*byte)));
        }
        checksum
    }
}

fn native_mmio_control(iterations: u32) -> u32 {
    let mut checksum = 0_u32;
    for index in 0..iterations {
        let register = black_box(index);
        checksum = checksum.wrapping_add(black_box(register));
    }
    checksum
}

fn native_yield_wake(iterations: u32) -> u32 {
    let mut wakes = 0_u32;
    for _ in 0..iterations {
        wakes = black_box(wakes.wrapping_add(1));
    }
    wakes
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn native_copy_and_ring_workloads_materialize_their_working_sets() {
        let mut copy = Prepared::new(IsaBenchmarkWorkload::CopyChecksum, 1);
        copy.execute().unwrap();
        assert_eq!(copy.copy_destination, copy.copy_source);

        let mut ring = Prepared::new(IsaBenchmarkWorkload::PacketRing, 9);
        ring.execute().unwrap();
        assert_eq!(ring.packet_ring[0][0], 8_u8.wrapping_mul(13));
        assert_eq!(
            ring.packet_ring[0][15],
            8_u8.wrapping_mul(13).wrapping_add(15)
        );
    }
}
