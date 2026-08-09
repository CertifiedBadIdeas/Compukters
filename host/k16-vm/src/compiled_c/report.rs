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

use std::collections::HashMap;

use super::artifact::CompiledCCandidate;
use super::runner::CompiledCObservation;
use crate::isa_benchmarks::IsaBenchmarkWorkload;

const INCONCLUSIVE_MINIMUM_ADVANTAGE: f64 = 0.12;
const INCONCLUSIVE_MAXIMUM_ADVANTAGE: f64 = 0.18;
const MAXIMUM_WORKLOAD_SLOWDOWN: f64 = 1.30;
const MAXIMUM_RETAINED_SIZE_RATIO: f64 = 1.25;
const COMPARISON_EPSILON: f64 = 1.0e-12;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CompiledCDecision {
    SelectK16F32,
    SelectK16F32R32LR,
    SelectRv32im,
    Inconclusive,
}

impl CompiledCDecision {
    pub const fn name(self) -> &'static str {
        match self {
            Self::SelectK16F32 => "select-k16-f32",
            Self::SelectK16F32R32LR => "select-k16-f32r32-lr",
            Self::SelectRv32im => "select-rv32im",
            Self::Inconclusive => "inconclusive-expanded-run",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct CompiledCTiming {
    pub observation: CompiledCObservation,
    pub cold_predecode_nanos: u128,
    pub warm_median_nanos: u128,
    pub warm_p95_nanos: u128,
    pub steady_allocations: u64,
    pub steady_allocated_bytes: u64,
    pub vs_native: f64,
}

impl CompiledCTiming {
    pub fn nanos_per_iteration(&self) -> f64 {
        if self.observation.iterations == 0 {
            0.0
        } else {
            self.warm_median_nanos as f64 / f64::from(self.observation.iterations)
        }
    }
}

pub const fn timing_report_header() -> &'static str {
    "workload\tcandidate\titerations\tchecksum\tcold_predecode_ns\twarm_median_ns\twarm_p95_ns\tnanos_per_iteration\tretired\tcode_bytes\tinstruction_count\tcpu_state_bytes\tpredecode_bytes\tdata_read_bytes\tdata_written_bytes\tsteady_allocations\tsteady_allocated_bytes\tvs_native"
}

pub fn format_timing_sample(sample: &CompiledCTiming) -> String {
    let observation = &sample.observation;
    format!(
        "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{:.3}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{:.6}",
        observation.workload.name(),
        observation.candidate.name(),
        observation.iterations,
        observation.checksum,
        sample.cold_predecode_nanos,
        sample.warm_median_nanos,
        sample.warm_p95_nanos,
        sample.nanos_per_iteration(),
        observation.retired_instructions,
        observation.code_bytes,
        observation.instruction_count,
        observation.cpu_state_bytes,
        observation.predecode_bytes,
        observation.data_read_bytes,
        observation.data_written_bytes,
        sample.steady_allocations,
        sample.steady_allocated_bytes,
        sample.vs_native,
    )
}

pub fn populate_vs_native(
    samples: &mut [CompiledCTiming],
    native: &[(IsaBenchmarkWorkload, u128)],
) -> Result<(), String> {
    let mut native_by_workload = HashMap::new();
    for (workload, nanos) in native {
        if *nanos == 0 {
            return Err(format!(
                "native workload {} has zero warm duration",
                workload.name()
            ));
        }
        if native_by_workload.insert(*workload, *nanos).is_some() {
            return Err(format!("duplicate native workload {}", workload.name()));
        }
    }
    for sample in samples {
        let native_nanos = native_by_workload
            .get(&sample.observation.workload)
            .ok_or_else(|| {
                format!(
                    "missing native workload {}",
                    sample.observation.workload.name()
                )
            })?;
        sample.vs_native = sample.warm_median_nanos as f64 / *native_nanos as f64;
    }
    Ok(())
}

pub fn select_compiled_c(samples: &[CompiledCTiming]) -> Result<CompiledCDecision, String> {
    select_compiled_c_candidate(samples, CompiledCCandidate::K16F32)
}

pub fn select_compiled_c_candidate(
    samples: &[CompiledCTiming],
    custom: CompiledCCandidate,
) -> Result<CompiledCDecision, String> {
    let metrics = decision_metrics(samples, custom)?;
    if metrics.speed_advantage + COMPARISON_EPSILON >= INCONCLUSIVE_MINIMUM_ADVANTAGE
        && metrics.speed_advantage <= INCONCLUSIVE_MAXIMUM_ADVANTAGE + COMPARISON_EPSILON
    {
        return Ok(CompiledCDecision::Inconclusive);
    }
    if metrics.speed_advantage < INCONCLUSIVE_MINIMUM_ADVANTAGE - COMPARISON_EPSILON {
        return Ok(CompiledCDecision::SelectRv32im);
    }
    if metrics.maximum_k16_slowdown > MAXIMUM_WORKLOAD_SLOWDOWN + COMPARISON_EPSILON
        || metrics.code_ratio > MAXIMUM_RETAINED_SIZE_RATIO + COMPARISON_EPSILON
        || metrics.predecode_ratio > MAXIMUM_RETAINED_SIZE_RATIO + COMPARISON_EPSILON
    {
        return Ok(CompiledCDecision::SelectRv32im);
    }
    match custom {
        CompiledCCandidate::K16F32 => Ok(CompiledCDecision::SelectK16F32),
        CompiledCCandidate::K16F32R32LR => Ok(CompiledCDecision::SelectK16F32R32LR),
        CompiledCCandidate::Rv32im | CompiledCCandidate::Rv64im => Err(format!(
            "compiled-C custom candidate cannot be {}",
            custom.name()
        )),
    }
}

pub fn format_decision(samples: &[CompiledCTiming]) -> Result<String, String> {
    format_decision_for_candidate(samples, CompiledCCandidate::K16F32)
}

pub fn format_decision_for_candidate(
    samples: &[CompiledCTiming],
    custom: CompiledCCandidate,
) -> Result<String, String> {
    let metrics = decision_metrics(samples, custom)?;
    let decision = select_compiled_c_candidate(samples, custom)?;
    let fastest = metrics.k16_to_rv_geomean.min(1.0);
    Ok(format!(
        "candidate\tnormalized_warm_geomean\ttotal_code_bytes\ttotal_predecode_bytes\n{}\t{:.6}\t{}\t{}\nrv32im\t{:.6}\t{}\t{}\nmetric\tvalue\nk16_speed_advantage\t{:.6}\nmaximum_k16_workload_slowdown\t{:.6}\nk16_to_rv_code_bytes\t{:.6}\nk16_to_rv_predecode_bytes\t{:.6}\ndecision\t{}\n",
        custom.name(),
        metrics.k16_to_rv_geomean / fastest,
        metrics.k16_code,
        metrics.k16_predecode,
        1.0 / fastest,
        metrics.rv_code,
        metrics.rv_predecode,
        metrics.speed_advantage,
        metrics.maximum_k16_slowdown,
        metrics.code_ratio,
        metrics.predecode_ratio,
        decision.name(),
    ))
}

pub fn format_riscv_xlen_comparison(samples: &[CompiledCTiming]) -> Result<String, String> {
    format_riscv_xlen_comparison_for(samples, &IsaBenchmarkWorkload::all()[..6])
}

pub fn format_riscv_xlen_u64_comparison(samples: &[CompiledCTiming]) -> Result<String, String> {
    format_riscv_xlen_comparison_for(samples, IsaBenchmarkWorkload::u64_compiled_c())
}

fn format_riscv_xlen_comparison_for(
    samples: &[CompiledCTiming],
    workloads: &[IsaBenchmarkWorkload],
) -> Result<String, String> {
    let metrics = riscv_xlen_metrics(samples, workloads)?;
    let fastest = metrics.rv64_to_rv32_geomean.min(1.0);
    Ok(format!(
        "candidate\tnormalized_warm_geomean\ttotal_code_bytes\ttotal_predecode_bytes\nrv32im\t{:.6}\t{}\t{}\nrv64im\t{:.6}\t{}\t{}\nmetric\tvalue\nrv64_to_rv32_warm_geomean\t{:.6}\nrv64_to_rv32_code_bytes\t{:.6}\nrv64_to_rv32_predecode_bytes\t{:.6}\n",
        1.0 / fastest,
        metrics.rv32_code,
        metrics.rv32_predecode,
        metrics.rv64_to_rv32_geomean / fastest,
        metrics.rv64_code,
        metrics.rv64_predecode,
        metrics.rv64_to_rv32_geomean,
        metrics.rv64_code as f64 / metrics.rv32_code as f64,
        metrics.rv64_predecode as f64 / metrics.rv32_predecode as f64,
    ))
}

struct RiscvXlenMetrics {
    rv64_to_rv32_geomean: f64,
    rv32_code: usize,
    rv64_code: usize,
    rv32_predecode: usize,
    rv64_predecode: usize,
}

fn riscv_xlen_metrics(
    samples: &[CompiledCTiming],
    workloads: &[IsaBenchmarkWorkload],
) -> Result<RiscvXlenMetrics, String> {
    if samples
        .iter()
        .any(|sample| sample.steady_allocations != 0 || sample.steady_allocated_bytes != 0)
    {
        return Err("RISC-V XLEN report rejects any steady allocation".to_string());
    }
    let mut indexed = HashMap::new();
    for sample in samples {
        if sample.warm_median_nanos == 0 {
            return Err(format!(
                "{} {} has zero warm duration",
                sample.observation.workload.name(),
                sample.observation.candidate.name()
            ));
        }
        let key = (sample.observation.workload, sample.observation.candidate);
        if indexed.insert(key, sample).is_some() {
            return Err(format!(
                "duplicate timing for {} {}",
                sample.observation.workload.name(),
                sample.observation.candidate.name()
            ));
        }
    }

    let mut ratios = Vec::with_capacity(workloads.len());
    let mut rv32_code = 0_usize;
    let mut rv64_code = 0_usize;
    let mut rv32_predecode = 0_usize;
    let mut rv64_predecode = 0_usize;
    for workload in workloads.iter().copied() {
        let rv32 = indexed
            .get(&(workload, CompiledCCandidate::Rv32im))
            .ok_or_else(|| format!("missing rv32im timing for {}", workload.name()))?;
        let rv64 = indexed
            .get(&(workload, CompiledCCandidate::Rv64im))
            .ok_or_else(|| format!("missing rv64im timing for {}", workload.name()))?;
        ratios.push(rv64.warm_median_nanos as f64 / rv32.warm_median_nanos as f64);
        rv32_code = rv32_code
            .checked_add(rv32.observation.code_bytes)
            .ok_or("rv32im code total overflow")?;
        rv64_code = rv64_code
            .checked_add(rv64.observation.code_bytes)
            .ok_or("rv64im code total overflow")?;
        rv32_predecode = rv32_predecode
            .checked_add(rv32.observation.predecode_bytes)
            .ok_or("rv32im predecode total overflow")?;
        rv64_predecode = rv64_predecode
            .checked_add(rv64.observation.predecode_bytes)
            .ok_or("rv64im predecode total overflow")?;
    }
    let expected_timings = workloads
        .len()
        .checked_mul(2)
        .ok_or("RISC-V XLEN timing count overflow")?;
    if indexed.len() != expected_timings {
        return Err(format!(
            "RISC-V XLEN comparison requires exactly {expected_timings} VM timings, got {}",
            indexed.len()
        ));
    }
    if rv32_code == 0 || rv32_predecode == 0 {
        return Err("rv32im retained-size totals must be positive".to_string());
    }
    Ok(RiscvXlenMetrics {
        rv64_to_rv32_geomean: geometric_mean(&ratios),
        rv32_code,
        rv64_code,
        rv32_predecode,
        rv64_predecode,
    })
}

struct DecisionMetrics {
    k16_to_rv_geomean: f64,
    speed_advantage: f64,
    maximum_k16_slowdown: f64,
    code_ratio: f64,
    predecode_ratio: f64,
    k16_code: usize,
    rv_code: usize,
    k16_predecode: usize,
    rv_predecode: usize,
}

fn decision_metrics(
    samples: &[CompiledCTiming],
    custom: CompiledCCandidate,
) -> Result<DecisionMetrics, String> {
    if matches!(
        custom,
        CompiledCCandidate::Rv32im | CompiledCCandidate::Rv64im
    ) {
        return Err(format!(
            "compiled-C custom candidate cannot be {}",
            custom.name()
        ));
    }
    if samples
        .iter()
        .any(|sample| sample.steady_allocations != 0 || sample.steady_allocated_bytes != 0)
    {
        return Err("compiled-C report rejects any steady allocation".to_string());
    }
    let mut indexed = HashMap::new();
    for sample in samples {
        if sample.warm_median_nanos == 0 {
            return Err(format!(
                "{} {} has zero warm duration",
                sample.observation.workload.name(),
                sample.observation.candidate.name()
            ));
        }
        let key = (sample.observation.workload, sample.observation.candidate);
        if indexed.insert(key, sample).is_some() {
            return Err(format!(
                "duplicate timing for {} {}",
                sample.observation.workload.name(),
                sample.observation.candidate.name()
            ));
        }
    }

    let workloads = IsaBenchmarkWorkload::all()
        .iter()
        .copied()
        .take(6)
        .collect::<Vec<_>>();
    let mut speed_ratios = Vec::with_capacity(workloads.len());
    let mut maximum_k16_slowdown = 0.0_f64;
    let mut k16_code = 0_usize;
    let mut rv_code = 0_usize;
    let mut k16_predecode = 0_usize;
    let mut rv_predecode = 0_usize;
    for workload in workloads {
        let k16 = indexed
            .get(&(workload, custom))
            .ok_or_else(|| format!("missing {} timing for {}", custom.name(), workload.name()))?;
        let rv = indexed
            .get(&(workload, CompiledCCandidate::Rv32im))
            .ok_or_else(|| format!("missing rv32im timing for {}", workload.name()))?;
        let ratio = k16.warm_median_nanos as f64 / rv.warm_median_nanos as f64;
        speed_ratios.push(ratio);
        maximum_k16_slowdown = maximum_k16_slowdown.max(ratio);
        k16_code = k16_code
            .checked_add(k16.observation.code_bytes)
            .ok_or_else(|| format!("{} code total overflow", custom.name()))?;
        rv_code = rv_code
            .checked_add(rv.observation.code_bytes)
            .ok_or("rv32im code total overflow")?;
        k16_predecode = k16_predecode
            .checked_add(k16.observation.predecode_bytes)
            .ok_or_else(|| format!("{} predecode total overflow", custom.name()))?;
        rv_predecode = rv_predecode
            .checked_add(rv.observation.predecode_bytes)
            .ok_or("rv32im predecode total overflow")?;
    }
    if indexed.len() != 12 {
        return Err(format!(
            "compiled-C decision requires exactly 12 VM timings, got {}",
            indexed.len()
        ));
    }
    if rv_code == 0 || rv_predecode == 0 {
        return Err("rv32im retained-size totals must be positive".to_string());
    }
    let k16_to_rv_geomean = geometric_mean(&speed_ratios);
    Ok(DecisionMetrics {
        k16_to_rv_geomean,
        speed_advantage: 1.0 - k16_to_rv_geomean,
        maximum_k16_slowdown,
        code_ratio: k16_code as f64 / rv_code as f64,
        predecode_ratio: k16_predecode as f64 / rv_predecode as f64,
        k16_code,
        rv_code,
        k16_predecode,
        rv_predecode,
    })
}

fn geometric_mean(ratios: &[f64]) -> f64 {
    (ratios.iter().map(|ratio| ratio.ln()).sum::<f64>() / ratios.len() as f64).exp()
}
