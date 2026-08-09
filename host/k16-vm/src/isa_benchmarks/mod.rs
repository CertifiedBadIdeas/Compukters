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
mod k16_f32;
mod native;
mod programs;
mod report;
mod rv32;
mod workload;

pub use programs::{DATA_BASE, MEMORY_SIZE, MMIO_BASE, PACKET_BYTES, RING_ENTRIES, STACK_TOP};
pub use report::{
    format_recommendations, format_timing_sample, populate_vs_native, timing_report_header,
    IsaBenchmarkTiming,
};
pub use workload::{native_checksum, IsaBenchmarkWorkload};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum IsaBenchmarkCandidate {
    K16,
    K16Cached,
    K16Predecoded,
    K16F32,
    K16F32Predecoded,
    RvsimRv32im,
    Rv32im,
    Rv32imCached,
    Rv32imPredecoded,
    NativeRust,
}

impl IsaBenchmarkCandidate {
    pub const fn all() -> &'static [Self] {
        &[
            Self::K16,
            Self::K16Cached,
            Self::K16Predecoded,
            Self::K16F32,
            Self::K16F32Predecoded,
            Self::RvsimRv32im,
            Self::Rv32im,
            Self::Rv32imCached,
            Self::Rv32imPredecoded,
            Self::NativeRust,
        ]
    }

    pub const fn name(self) -> &'static str {
        match self {
            Self::K16 => "k16",
            Self::K16Cached => "k16-cached",
            Self::K16Predecoded => "k16-predecoded",
            Self::K16F32 => "k16-f32",
            Self::K16F32Predecoded => "k16-f32-predecoded",
            Self::RvsimRv32im => "rvsim-rv32im",
            Self::Rv32im => "rv32im",
            Self::Rv32imCached => "rv32im-cached",
            Self::Rv32imPredecoded => "rv32im-predecoded",
            Self::NativeRust => "native-rust",
        }
    }

    pub const fn is_native_reference(self) -> bool {
        matches!(self, Self::NativeRust)
    }

    pub const fn is_k16_v1(self) -> bool {
        matches!(self, Self::K16 | Self::K16Cached | Self::K16Predecoded)
    }

    pub const fn is_specialized_rv32im(self) -> bool {
        matches!(
            self,
            Self::Rv32im | Self::Rv32imCached | Self::Rv32imPredecoded
        )
    }

    pub const fn is_k16_f32(self) -> bool {
        matches!(self, Self::K16F32 | Self::K16F32Predecoded)
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

pub fn run_candidate(
    candidate: IsaBenchmarkCandidate,
    workload: IsaBenchmarkWorkload,
    iterations: u32,
) -> Result<IsaBenchmarkObservation, String> {
    match candidate {
        IsaBenchmarkCandidate::K16
        | IsaBenchmarkCandidate::K16Cached
        | IsaBenchmarkCandidate::K16Predecoded => k16::run(candidate, workload, iterations),
        IsaBenchmarkCandidate::K16F32 | IsaBenchmarkCandidate::K16F32Predecoded => {
            k16_f32::run(candidate, workload, iterations)
        }
        IsaBenchmarkCandidate::RvsimRv32im
        | IsaBenchmarkCandidate::Rv32im
        | IsaBenchmarkCandidate::Rv32imCached
        | IsaBenchmarkCandidate::Rv32imPredecoded => rv32::run(candidate, workload, iterations),
        IsaBenchmarkCandidate::NativeRust => native::run(workload, iterations),
    }
}

pub struct PreparedIsaBenchmark {
    inner: PreparedCandidate,
}

enum PreparedCandidate {
    K16(k16::Prepared),
    K16F32(k16_f32::Prepared),
    Native(native::Prepared),
    Rv32(rv32::Prepared),
}

impl PreparedIsaBenchmark {
    pub fn new(
        candidate: IsaBenchmarkCandidate,
        workload: IsaBenchmarkWorkload,
        iterations: u32,
    ) -> Result<Self, String> {
        let inner = match candidate {
            IsaBenchmarkCandidate::K16
            | IsaBenchmarkCandidate::K16Cached
            | IsaBenchmarkCandidate::K16Predecoded => {
                PreparedCandidate::K16(k16::Prepared::new(candidate, workload, iterations)?)
            }
            IsaBenchmarkCandidate::K16F32 | IsaBenchmarkCandidate::K16F32Predecoded => {
                PreparedCandidate::K16F32(k16_f32::Prepared::new(candidate, workload, iterations)?)
            }
            IsaBenchmarkCandidate::RvsimRv32im
            | IsaBenchmarkCandidate::Rv32im
            | IsaBenchmarkCandidate::Rv32imCached
            | IsaBenchmarkCandidate::Rv32imPredecoded => {
                PreparedCandidate::Rv32(rv32::Prepared::new(candidate, workload, iterations)?)
            }
            IsaBenchmarkCandidate::NativeRust => {
                PreparedCandidate::Native(native::Prepared::new(workload, iterations))
            }
        };
        Ok(Self { inner })
    }

    /// Executes from a fresh CPU state while retaining immutable program data
    /// and candidate-specific decode/translation caches between calls.
    pub fn execute(&mut self) -> Result<IsaBenchmarkObservation, String> {
        match &mut self.inner {
            PreparedCandidate::K16(prepared) => prepared.execute(),
            PreparedCandidate::K16F32(prepared) => prepared.execute(),
            PreparedCandidate::Native(prepared) => prepared.execute(),
            PreparedCandidate::Rv32(prepared) => prepared.execute(),
        }
    }
}
