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

use super::rv32::{mmio_control_program, rv32_workload, ProgramImage};
use super::{native_checksum, BenchmarkWorkload, DATA_BASE};
use crate::rv32_machine::{
    Rv32ExecutionBackendConfig, Rv32Machine, Rv32MachineConfig, Rv32MachineOutcome,
    Rv32TranslationStats, CONTROL_BASE, STATUS_HALTED,
};
use crate::rv32im::encoding::{addi, bne, csrrs, csrrw, ebreak, ecall, jal, materialize, mret, sw};
use std::collections::HashMap;
use std::hint::black_box;

const ELF32_HEADER_SIZE: usize = 52;
const ELF32_PROGRAM_HEADER_SIZE: usize = 32;
const PAGE_SIZE: usize = 4096;
const CSR_MTVEC: u16 = 0x305;
const CSR_MEPC: u16 = 0x341;
const COPY_SOURCE_BYTES: usize = 256;

pub const PRODUCT_RAM_BYTES: usize = 16 * 1024;
pub const PRODUCT_CACHE_SETS: usize = 64;
pub const PRODUCT_BLOCK_CACHE_SETS: usize = 32;
pub const PRODUCT_BLOCK_MAX_INSTRUCTIONS: usize = 8;
pub const PRODUCT_DEBUG_LIMIT: usize = 0;
pub const PRODUCT_RESIDENT_REPORT_HEADER: &str = "backend\tpopulation\tconstruction_median_ns\tconstruction_p95_ns\tresident_live_bytes\tpeak_construction_bytes\tlive_bytes_per_machine\taggregate_ram_bytes\telf_bytes\texecutable_bytes\trw_initialized_bytes\tram_bytes\tdebug_limit\tcache_sets\tblock_cache_sets\tblock_max_instructions";
pub const PRODUCT_ACTIVE_REPORT_HEADER: &str = "workload\tcandidate\titerations\tchecksum\tbatch\tcold_ns\twarm_median_ns\twarm_p95_ns\toperations_per_second\tretired_instructions\tlookup_unit\tcache_hits\tcache_misses\tcache_evictions\tblocks_built\tdecoded_slots_built\tram_bytes\texecutable_bytes\ttranslation_bytes\tsteady_allocations\tsteady_allocated_bytes\tvs_native";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProductExecutionCandidate {
    NativeHost,
    Cached,
    Predecoded,
    BlockCached,
}

impl ProductExecutionCandidate {
    pub const fn all() -> &'static [Self] {
        &[
            Self::NativeHost,
            Self::Cached,
            Self::Predecoded,
            Self::BlockCached,
        ]
    }

    pub const fn name(self) -> &'static str {
        match self {
            Self::NativeHost => "native-host",
            Self::Cached => "rv32-cached",
            Self::Predecoded => "rv32-predecoded",
            Self::BlockCached => "rv32-block-cached",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct ProductActiveTiming {
    pub candidate: ProductExecutionCandidate,
    pub workload: ProductMachineWorkload,
    pub iterations: u32,
    pub checksum: u32,
    pub batch: u64,
    pub cold_nanos: f64,
    pub warm_median_nanos: f64,
    pub warm_p95_nanos: f64,
    pub machine: Option<ProductMachineObservation>,
    pub steady_allocations: u64,
    pub steady_allocated_bytes: u64,
    pub vs_native: f64,
}

impl ProductActiveTiming {
    pub fn native(
        workload: ProductMachineWorkload,
        iterations: u32,
        batch: u64,
        median_nanos: f64,
        p95_nanos: f64,
        checksum: u32,
    ) -> Self {
        Self {
            candidate: ProductExecutionCandidate::NativeHost,
            workload,
            iterations,
            checksum,
            batch,
            cold_nanos: median_nanos,
            warm_median_nanos: median_nanos,
            warm_p95_nanos: p95_nanos,
            machine: None,
            steady_allocations: 0,
            steady_allocated_bytes: 0,
            vs_native: 0.0,
        }
    }

    pub fn machine(
        candidate: ProductExecutionCandidate,
        workload: ProductMachineWorkload,
        iterations: u32,
        median_nanos: f64,
        p95_nanos: f64,
        checksum: u32,
    ) -> Self {
        assert!(candidate != ProductExecutionCandidate::NativeHost);
        Self {
            candidate,
            workload,
            iterations,
            checksum,
            batch: 1,
            cold_nanos: median_nanos,
            warm_median_nanos: median_nanos,
            warm_p95_nanos: p95_nanos,
            machine: None,
            steady_allocations: 0,
            steady_allocated_bytes: 0,
            vs_native: 0.0,
        }
    }

    pub fn operations_per_second(&self) -> f64 {
        1_000_000_000.0 / self.warm_median_nanos
    }
}

pub fn populate_product_ratios(
    mut rows: Vec<ProductActiveTiming>,
) -> Result<Vec<ProductActiveTiming>, String> {
    let native = rows
        .iter()
        .filter(|row| row.candidate == ProductExecutionCandidate::NativeHost)
        .map(|row| (row.workload, row.warm_median_nanos))
        .collect::<HashMap<_, _>>();
    for row in &mut rows {
        let reference = native
            .get(&row.workload)
            .copied()
            .filter(|value| value.is_finite() && *value > 0.0)
            .ok_or_else(|| format!("missing native timing for {}", row.workload.name()))?;
        row.vs_native = row.warm_median_nanos / reference;
    }
    Ok(rows)
}

pub fn format_product_active_row(row: &ProductActiveTiming) -> String {
    let unavailable = || "-".to_string();
    let (
        retired,
        lookup_unit,
        cache_hits,
        cache_misses,
        cache_evictions,
        blocks_built,
        decoded_slots_built,
        ram,
        executable,
        translation,
    ) = match row.machine.as_ref() {
        Some(machine) => {
            let stats = machine.translation_stats;
            (
                machine.retired_instructions.to_string(),
                stats.map_or_else(unavailable, |value| value.lookup_unit.name().to_string()),
                stats.map_or_else(unavailable, |value| value.hits.to_string()),
                stats.map_or_else(unavailable, |value| value.misses.to_string()),
                stats.map_or_else(unavailable, |value| value.evictions.to_string()),
                stats.map_or_else(unavailable, |value| value.blocks_built.to_string()),
                stats.map_or_else(unavailable, |value| value.decoded_slots_built.to_string()),
                machine.ram_bytes.to_string(),
                machine.executable_bytes.to_string(),
                machine.translation_bytes.to_string(),
            )
        }
        None => (
            unavailable(),
            unavailable(),
            unavailable(),
            unavailable(),
            unavailable(),
            unavailable(),
            unavailable(),
            unavailable(),
            unavailable(),
            unavailable(),
        ),
    };
    format!(
        "{}\t{}\t{}\t{}\t{}\t{:.3}\t{:.3}\t{:.3}\t{:.3}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{:.6}",
        row.workload.name(),
        row.candidate.name(),
        row.iterations,
        row.checksum,
        row.batch,
        row.cold_nanos,
        row.warm_median_nanos,
        row.warm_p95_nanos,
        row.operations_per_second(),
        retired,
        lookup_unit,
        cache_hits,
        cache_misses,
        cache_evictions,
        blocks_built,
        decoded_slots_built,
        ram,
        executable,
        translation,
        row.steady_allocations,
        row.steady_allocated_bytes,
        row.vs_native,
    )
}

pub fn benchmark_rotating_order<const N: usize>(group: usize, sample: usize) -> [usize; N] {
    std::array::from_fn(|index| (index + group + sample) % N)
}

pub fn benchmark_normalize_nanos(total: u128, batch: u64) -> Result<f64, String> {
    if batch == 0 {
        return Err("benchmark batch must be positive".to_string());
    }
    Ok(total as f64 / batch as f64)
}

pub fn benchmark_geomean(values: &[f64]) -> Result<f64, String> {
    if values.is_empty()
        || values
            .iter()
            .any(|value| !value.is_finite() || *value <= 0.0)
    {
        return Err("benchmark geomean requires finite positive values".to_string());
    }
    Ok((values.iter().map(|value| value.ln()).sum::<f64>() / values.len() as f64).exp())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProductMachineBackend {
    Cached,
    Predecoded,
    BlockCached,
}

impl ProductMachineBackend {
    pub const fn all() -> &'static [Self] {
        &[Self::Cached, Self::Predecoded, Self::BlockCached]
    }

    pub const fn name(self) -> &'static str {
        match self {
            Self::Cached => "cached",
            Self::Predecoded => "predecoded",
            Self::BlockCached => "block-cached",
        }
    }

    fn config(self) -> Rv32ExecutionBackendConfig {
        match self {
            Self::Cached => Rv32ExecutionBackendConfig::Cached {
                sets: PRODUCT_CACHE_SETS,
            },
            Self::Predecoded => Rv32ExecutionBackendConfig::Predecoded,
            Self::BlockCached => Rv32ExecutionBackendConfig::BlockCached {
                sets: PRODUCT_BLOCK_CACHE_SETS,
                max_instructions: PRODUCT_BLOCK_MAX_INSTRUCTIONS,
            },
        }
    }
}

pub fn product_backend_order(
    group_index: usize,
    sample_index: usize,
) -> [ProductMachineBackend; 3] {
    let all = ProductMachineBackend::all();
    benchmark_rotating_order::<3>(group_index, sample_index).map(|index| all[index])
}

pub fn product_percentile(sorted_values: &[u128], percentile: usize) -> u128 {
    assert!(!sorted_values.is_empty());
    assert!((1..=100).contains(&percentile));
    let index = (sorted_values.len() * percentile)
        .div_ceil(100)
        .saturating_sub(1);
    sorted_values[index]
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ProductMachineWorkload {
    Compute32,
    BranchMix,
    CallStack,
    MemorySequential,
    MemoryRandom,
    CopyChecksum,
    MmioControl,
    PacketRing,
    TrapRoundtrip,
}

pub struct ProductNativeObservation {
    pub workload: ProductMachineWorkload,
    pub iterations: u32,
    pub batch: u64,
    pub checksum: u32,
}

pub struct PreparedProductNative {
    workload: ProductMachineWorkload,
    iterations: u32,
    mapped: Option<super::native::Prepared>,
}

impl PreparedProductNative {
    pub fn new(workload: ProductMachineWorkload, iterations: u32) -> Result<Self, String> {
        if iterations == 0 {
            return Err("product native benchmark iterations must be positive".to_string());
        }
        let mapped = workload
            .decoder_workload()
            .map(|mapped| super::native::Prepared::new(mapped, iterations));
        Ok(Self {
            workload,
            iterations,
            mapped,
        })
    }

    pub fn execute_batch(&mut self, batch: u64) -> Result<ProductNativeObservation, String> {
        if batch == 0 {
            return Err("product native benchmark batch must be positive".to_string());
        }
        let expected = self.workload.expected_checksum(self.iterations);
        let mut checksum = 0_u32;
        for _ in 0..batch {
            checksum = match &mut self.mapped {
                Some(prepared) => prepared.execute()?.checksum,
                None => native_trap_roundtrip(self.iterations),
            };
            checksum = black_box(checksum);
            if checksum != expected {
                return Err(format!(
                    "native workload {} checksum mismatch: expected {expected}, actual {checksum}",
                    self.workload.name(),
                ));
            }
        }
        Ok(ProductNativeObservation {
            workload: self.workload,
            iterations: self.iterations,
            batch,
            checksum,
        })
    }
}

fn native_trap_roundtrip(iterations: u32) -> u32 {
    let mut checksum = 0_u32;
    for _ in 0..black_box(iterations) {
        checksum = black_box(checksum.wrapping_add(1));
    }
    checksum
}

impl ProductMachineWorkload {
    pub const fn all() -> &'static [Self] {
        &[
            Self::Compute32,
            Self::BranchMix,
            Self::CallStack,
            Self::MemorySequential,
            Self::MemoryRandom,
            Self::CopyChecksum,
            Self::MmioControl,
            Self::PacketRing,
            Self::TrapRoundtrip,
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
            Self::PacketRing => "packet-ring",
            Self::TrapRoundtrip => "trap-roundtrip",
        }
    }

    pub const fn decoder_workload(self) -> Option<BenchmarkWorkload> {
        Some(match self {
            Self::Compute32 => BenchmarkWorkload::Compute32,
            Self::BranchMix => BenchmarkWorkload::BranchMix,
            Self::CallStack => BenchmarkWorkload::CallStack,
            Self::MemorySequential => BenchmarkWorkload::MemorySequential,
            Self::MemoryRandom => BenchmarkWorkload::MemoryRandom,
            Self::CopyChecksum => BenchmarkWorkload::CopyChecksum,
            Self::MmioControl => BenchmarkWorkload::MmioControl,
            Self::PacketRing => BenchmarkWorkload::PacketRing,
            Self::TrapRoundtrip => return None,
        })
    }

    fn expected_checksum(self, iterations: u32) -> u32 {
        self.decoder_workload()
            .map_or(iterations, |workload| native_checksum(workload, iterations))
    }
}

pub struct ProductMachineImage {
    workload: ProductMachineWorkload,
    iterations: u32,
    elf: Vec<u8>,
    executable_bytes: usize,
    rw_initialized_bytes: usize,
    fingerprint: u64,
}

pub struct PreparedProductMachine {
    backend: ProductMachineBackend,
    workload: ProductMachineWorkload,
    iterations: u32,
    machine: Rv32Machine,
    executable_bytes: usize,
    image_fingerprint: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProductMachineObservation {
    pub backend: ProductMachineBackend,
    pub workload: ProductMachineWorkload,
    pub iterations: u32,
    pub checksum: u32,
    pub retired_instructions: u64,
    pub translation_stats: Option<Rv32TranslationStats>,
    pub ram_bytes: usize,
    pub executable_bytes: usize,
    pub translation_bytes: usize,
    pub complete_machine: bool,
}

impl ProductMachineImage {
    pub fn new(workload: ProductMachineWorkload, iterations: u32) -> Result<Self, String> {
        if iterations == 0 {
            return Err("product machine benchmark iterations must be positive".to_string());
        }
        let (elf, executable_bytes, rw_initialized_bytes) = product_elf(workload, iterations)?;
        let fingerprint = elf_fingerprint(&elf);
        Ok(Self {
            workload,
            iterations,
            elf,
            executable_bytes,
            rw_initialized_bytes,
            fingerprint,
        })
    }

    pub fn prepare(
        &self,
        backend: ProductMachineBackend,
    ) -> Result<PreparedProductMachine, String> {
        let machine = Rv32Machine::from_elf(
            &self.elf,
            Rv32MachineConfig {
                ram_size: PRODUCT_RAM_BYTES,
                debug_limit: PRODUCT_DEBUG_LIMIT,
                execution: backend.config(),
            },
        )
        .map_err(|error| error.to_string())?;
        Ok(PreparedProductMachine {
            backend,
            workload: self.workload,
            iterations: self.iterations,
            machine,
            executable_bytes: self.executable_bytes,
            image_fingerprint: self.fingerprint,
        })
    }

    pub fn elf_bytes(&self) -> &[u8] {
        &self.elf
    }

    pub fn executable_bytes(&self) -> usize {
        self.executable_bytes
    }

    pub fn rw_initialized_bytes(&self) -> usize {
        self.rw_initialized_bytes
    }
}

impl PreparedProductMachine {
    pub fn new(
        backend: ProductMachineBackend,
        workload: ProductMachineWorkload,
        iterations: u32,
    ) -> Result<Self, String> {
        ProductMachineImage::new(workload, iterations)?.prepare(backend)
    }

    pub fn image_fingerprint(&self) -> u64 {
        self.image_fingerprint
    }

    pub fn execute(&mut self) -> Result<ProductMachineObservation, String> {
        let outcome = self
            .machine
            .run(max_budget(self.iterations))
            .map_err(|error| error.to_string())?;
        let (exit_code, retired_instructions) = match outcome {
            Rv32MachineOutcome::Halted {
                exit_code,
                retired_delta,
                ..
            } => (exit_code as u32, retired_delta),
            outcome => {
                return Err(format!(
                    "product machine {} {} returned unexpected outcome {outcome:?}",
                    self.backend.name(),
                    self.workload.name(),
                ))
            }
        };
        let expected = self.workload.expected_checksum(self.iterations);
        if exit_code != expected {
            return Err(format!(
                "product machine {} {} checksum mismatch: expected {expected}, actual {exit_code}",
                self.backend.name(),
                self.workload.name(),
            ));
        }
        let executable_bytes = self.machine.executable_bytes();
        debug_assert_eq!(executable_bytes, self.executable_bytes);
        Ok(ProductMachineObservation {
            backend: self.backend,
            workload: self.workload,
            iterations: self.iterations,
            checksum: exit_code,
            retired_instructions,
            translation_stats: self.machine.translation_stats(),
            ram_bytes: PRODUCT_RAM_BYTES,
            executable_bytes,
            translation_bytes: self.machine.translation_bytes(),
            complete_machine: true,
        })
    }
}

fn product_elf(
    workload: ProductMachineWorkload,
    iterations: u32,
) -> Result<(Vec<u8>, usize, usize), String> {
    let image = match workload {
        ProductMachineWorkload::TrapRoundtrip => trap_roundtrip_program(iterations),
        ProductMachineWorkload::MmioControl => mmio_control_program(iterations, 8)?,
        _ => rv32_workload(workload.decoder_workload().unwrap(), iterations)?,
    };
    let words = add_product_termination(image);
    let executable_bytes = words.len() * 4;
    let code = words.into_iter().flat_map(u32::to_le_bytes).collect();
    let initialized_data = if workload == ProductMachineWorkload::CopyChecksum {
        (0..COPY_SOURCE_BYTES)
            .map(|index| (index as u8).wrapping_mul(29).wrapping_add(7))
            .collect()
    } else {
        Vec::new()
    };
    let rw_initialized_bytes = initialized_data.len();
    Ok((
        strict_elf32(code, initialized_data),
        executable_bytes,
        rw_initialized_bytes,
    ))
}

fn elf_fingerprint(bytes: &[u8]) -> u64 {
    bytes.iter().fold(0xcbf2_9ce4_8422_2325, |hash, byte| {
        (hash ^ u64::from(*byte)).wrapping_mul(0x0000_0100_0000_01b3)
    })
}

fn add_product_termination(mut image: ProgramImage) -> Vec<u32> {
    let epilogue_index = image.words.len();
    for (index, word) in image.words.iter_mut().enumerate() {
        if *word == ebreak() {
            *word = jal(0, instruction_offset(index, epilogue_index));
        }
    }
    image.words.extend(materialize(30, CONTROL_BASE));
    image.words.push(sw(30, image.result_register, 8));
    image.words.extend(materialize(29, STATUS_HALTED as u32));
    image.words.push(sw(30, 29, 0));
    image.words
}

fn trap_roundtrip_program(iterations: u32) -> ProgramImage {
    let mut words = Vec::new();
    words.extend(materialize(5, iterations));
    words.extend(materialize(6, 0));
    words.extend(materialize(7, 0));
    let vector_materialize = words.len();
    words.extend([0, 0]);
    words.push(csrrw(0, CSR_MTVEC, 8));
    let loop_index = words.len();
    let branch_index = words.len();
    words.push(0);
    words.push(ebreak());
    let body_index = words.len();
    words.push(addi(7, 7, 1));
    words.push(addi(6, 6, 1));
    words.push(ecall());
    let jump_index = words.len();
    words.push(jal(0, instruction_offset(jump_index, loop_index)));

    let handler_index = words.len();
    words.extend([
        csrrs(9, CSR_MEPC, 0),
        addi(9, 9, 4),
        csrrw(0, CSR_MEPC, 9),
        mret(),
    ]);
    words[vector_materialize..vector_materialize + 2]
        .copy_from_slice(&materialize(8, (handler_index * 4) as u32));
    words[branch_index] = bne(6, 5, instruction_offset(branch_index, body_index));
    ProgramImage {
        words,
        result_register: 7,
    }
}

fn instruction_offset(from: usize, to: usize) -> i32 {
    (i32::try_from(to).unwrap() - i32::try_from(from).unwrap()) * 4
}

fn max_budget(iterations: u32) -> u64 {
    u64::from(iterations)
        .saturating_mul(4_096)
        .saturating_add(100_000)
}

fn strict_elf32(code: Vec<u8>, initialized_data: Vec<u8>) -> Vec<u8> {
    let first_segment_offset = PAGE_SIZE;
    let second_segment_offset = align_up(first_segment_offset + code.len(), PAGE_SIZE);
    let mut elf = vec![0_u8; second_segment_offset + initialized_data.len()];
    elf[0..4].copy_from_slice(b"\x7fELF");
    elf[4] = 1;
    elf[5] = 1;
    elf[6] = 1;
    put_u16(&mut elf, 16, 2);
    put_u16(&mut elf, 18, 243);
    put_u32(&mut elf, 20, 1);
    put_u32(&mut elf, 24, 0);
    put_u32(&mut elf, 28, ELF32_HEADER_SIZE as u32);
    put_u16(&mut elf, 40, ELF32_HEADER_SIZE as u16);
    put_u16(&mut elf, 42, ELF32_PROGRAM_HEADER_SIZE as u16);
    put_u16(&mut elf, 44, 2);

    put_program_header(
        &mut elf,
        ELF32_HEADER_SIZE,
        first_segment_offset,
        0,
        code.len(),
        code.len(),
        0b101,
    );
    put_program_header(
        &mut elf,
        ELF32_HEADER_SIZE + ELF32_PROGRAM_HEADER_SIZE,
        second_segment_offset,
        DATA_BASE,
        initialized_data.len(),
        PRODUCT_RAM_BYTES - DATA_BASE as usize,
        0b110,
    );
    elf[first_segment_offset..first_segment_offset + code.len()].copy_from_slice(&code);
    elf[second_segment_offset..].copy_from_slice(&initialized_data);
    elf
}

fn put_program_header(
    elf: &mut [u8],
    header: usize,
    file_offset: usize,
    virtual_address: u32,
    file_size: usize,
    memory_size: usize,
    flags: u32,
) {
    put_u32(elf, header, 1);
    put_u32(elf, header + 4, file_offset as u32);
    put_u32(elf, header + 8, virtual_address);
    put_u32(elf, header + 12, virtual_address);
    put_u32(elf, header + 16, file_size as u32);
    put_u32(elf, header + 20, memory_size as u32);
    put_u32(elf, header + 24, flags);
    put_u32(elf, header + 28, PAGE_SIZE as u32);
}

fn align_up(value: usize, alignment: usize) -> usize {
    value.div_ceil(alignment) * alignment
}

fn put_u16(bytes: &mut [u8], offset: usize, value: u16) {
    bytes[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
}

fn put_u32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}
