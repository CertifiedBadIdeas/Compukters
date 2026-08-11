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

mod c_comparison;
mod decoder;
mod native;
mod product_machine;
mod programs;
mod report;
mod rv32;
mod workload;

pub use c_comparison::{
    c_comparison_next_batch, c_comparison_qemu_target_nanos, c_comparison_timeout_nanos,
    parse_c_comparison_result,
};
pub use decoder::{
    DecoderBenchmarkImplementation, DecoderBenchmarkObservation, DecoderBenchmarkScenario,
    PreparedDecoderBenchmark,
};
pub use product_machine::{
    benchmark_geomean, benchmark_normalize_nanos, benchmark_rotating_order,
    format_product_active_row, populate_product_ratios, product_backend_order, product_percentile,
    PreparedProductMachine, PreparedProductNative, ProductActiveTiming, ProductExecutionCandidate,
    ProductMachineBackend, ProductMachineImage, ProductMachineObservation, ProductMachineWorkload,
    ProductNativeObservation, PRODUCT_ACTIVE_REPORT_HEADER, PRODUCT_BLOCK_CACHE_SETS,
    PRODUCT_BLOCK_MAX_INSTRUCTIONS, PRODUCT_CACHE_SETS, PRODUCT_DEBUG_LIMIT, PRODUCT_RAM_BYTES,
    PRODUCT_RESIDENT_REPORT_HEADER,
};
pub use programs::{DATA_BASE, MEMORY_SIZE, MMIO_BASE, PACKET_BYTES, RING_ENTRIES, STACK_TOP};
pub use report::{
    format_summary, format_timing_sample, populate_vs_native, timing_report_header, BenchmarkTiming,
};
pub use workload::{native_checksum, IsaBenchmarkWorkload as BenchmarkWorkload};

pub(crate) use workload::IsaBenchmarkWorkload;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum BenchmarkCandidate {
    RvsimRv32im,
    Rv32Direct,
    Rv32Cached,
    Rv32Predecoded,
    NativeRust,
}

impl BenchmarkCandidate {
    pub const fn all() -> &'static [Self] {
        &[
            Self::Rv32Direct,
            Self::Rv32Cached,
            Self::Rv32Predecoded,
            Self::NativeRust,
        ]
    }

    pub const fn name(self) -> &'static str {
        match self {
            Self::RvsimRv32im => "rvsim-rv32im",
            Self::Rv32Direct => "rv32-direct",
            Self::Rv32Cached => "rv32-cached",
            Self::Rv32Predecoded => "rv32-predecoded",
            Self::NativeRust => "native-rust",
        }
    }

    pub const fn is_native(self) -> bool {
        matches!(self, Self::NativeRust)
    }
}

pub(crate) type IsaBenchmarkCandidate = BenchmarkCandidate;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct BenchmarkTraffic {
    pub loads: u64,
    pub stores: u64,
    pub bytes_read: u64,
    pub bytes_written: u64,
}

pub(crate) type IsaTraffic = BenchmarkTraffic;

impl From<crate::bus::MachineBusTrafficSnapshot> for BenchmarkTraffic {
    fn from(value: crate::bus::MachineBusTrafficSnapshot) -> Self {
        Self {
            loads: value.loads,
            stores: value.stores,
            bytes_read: value.bytes_read,
            bytes_written: value.bytes_written,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BenchmarkObservation {
    pub candidate: BenchmarkCandidate,
    pub workload: BenchmarkWorkload,
    pub iterations: u32,
    pub checksum: u32,
    pub retired_instructions: u64,
    pub yields: u64,
    pub instruction_fetch: BenchmarkTraffic,
    pub data_ram: BenchmarkTraffic,
    pub mmio: BenchmarkTraffic,
    pub cpu_state_bytes: usize,
    pub translation_bytes: usize,
}

impl BenchmarkObservation {
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

pub(crate) type IsaBenchmarkObservation = BenchmarkObservation;

pub struct PreparedBenchmark {
    inner: PreparedCandidate,
}

enum PreparedCandidate {
    Native(native::Prepared),
    Rv32(rv32::Prepared),
}

impl PreparedBenchmark {
    pub fn new(
        candidate: BenchmarkCandidate,
        workload: BenchmarkWorkload,
        iterations: u32,
    ) -> Result<Self, String> {
        let inner = match candidate {
            BenchmarkCandidate::Rv32Direct
            | BenchmarkCandidate::Rv32Cached
            | BenchmarkCandidate::Rv32Predecoded => {
                PreparedCandidate::Rv32(rv32::Prepared::new(candidate, workload, iterations)?)
            }
            BenchmarkCandidate::NativeRust => {
                PreparedCandidate::Native(native::Prepared::new(workload, iterations))
            }
            BenchmarkCandidate::RvsimRv32im => {
                return Err("rvsim is a correctness reference, not a benchmark candidate".into())
            }
        };
        Ok(Self { inner })
    }

    pub fn execute(&mut self) -> Result<BenchmarkObservation, String> {
        match &mut self.inner {
            PreparedCandidate::Native(prepared) => prepared.execute(),
            PreparedCandidate::Rv32(prepared) => prepared.execute(),
        }
    }
}
