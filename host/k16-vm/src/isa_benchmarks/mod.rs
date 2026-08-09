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

mod k16;
mod programs;
mod workload;

pub use k16::run_candidate;
pub use programs::{DATA_BASE, MEMORY_SIZE, MMIO_BASE, PACKET_BYTES, RING_ENTRIES, STACK_TOP};
pub use workload::{native_checksum, IsaBenchmarkWorkload};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum IsaBenchmarkCandidate {
    K16,
    K16Cached,
    K16F32,
    RvsimRv32im,
    Rv32im,
    Rv32imPredecoded,
}

impl IsaBenchmarkCandidate {
    pub const fn all() -> &'static [Self] {
        &[
            Self::K16,
            Self::K16Cached,
            Self::K16F32,
            Self::RvsimRv32im,
            Self::Rv32im,
            Self::Rv32imPredecoded,
        ]
    }

    pub const fn name(self) -> &'static str {
        match self {
            Self::K16 => "k16",
            Self::K16Cached => "k16-cached",
            Self::K16F32 => "k16-f32",
            Self::RvsimRv32im => "rvsim-rv32im",
            Self::Rv32im => "rv32im",
            Self::Rv32imPredecoded => "rv32im-predecoded",
        }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct IsaTraffic {
    pub loads: u64,
    pub stores: u64,
    pub bytes_read: u64,
    pub bytes_written: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IsaBenchmarkObservation {
    pub candidate: IsaBenchmarkCandidate,
    pub workload: IsaBenchmarkWorkload,
    pub iterations: u32,
    pub checksum: u32,
    pub retired_instructions: u64,
    pub yields: u64,
    pub instruction_fetch: IsaTraffic,
    pub data_ram: IsaTraffic,
    pub mmio: IsaTraffic,
    pub cpu_state_bytes: usize,
    pub translation_bytes: usize,
}

impl IsaBenchmarkObservation {
    pub fn for_test(
        candidate: IsaBenchmarkCandidate,
        workload: IsaBenchmarkWorkload,
        iterations: u32,
        checksum: u32,
    ) -> Self {
        Self {
            candidate,
            workload,
            iterations,
            checksum,
            retired_instructions: 0,
            yields: 0,
            instruction_fetch: IsaTraffic::default(),
            data_ram: IsaTraffic::default(),
            mmio: IsaTraffic::default(),
            cpu_state_bytes: 0,
            translation_bytes: 0,
        }
    }

    pub fn validate_checksum(&self) -> Result<(), String> {
        let expected = native_checksum(self.workload, self.iterations);
        if self.checksum == expected {
            return Ok(());
        }

        Err(format!(
            "candidate {} workload {} checksum mismatch: expected {expected}, actual {}",
            self.candidate.name(),
            self.workload.name(),
            self.checksum,
        ))
    }
}
