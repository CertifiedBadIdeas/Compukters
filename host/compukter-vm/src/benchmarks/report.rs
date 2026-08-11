/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

use super::{BenchmarkCandidate, BenchmarkObservation};
use std::collections::HashMap;

#[derive(Debug, Clone, PartialEq)]
pub struct BenchmarkTiming {
    pub observation: BenchmarkObservation,
    pub cold_nanos: u128,
    pub warm_median_nanos: u128,
    pub warm_p95_nanos: u128,
    pub steady_allocations: u64,
    pub steady_allocated_bytes: u64,
    pub vs_native: f64,
}

impl BenchmarkTiming {
    pub fn nanos_per_iteration(&self) -> f64 {
        self.warm_median_nanos as f64 / f64::from(self.observation.iterations.max(1))
    }
}

pub const fn timing_report_header() -> &'static str {
    "workload\tcandidate\titerations\tchecksum\tcold_ns\twarm_median_ns\twarm_p95_ns\tnanos_per_iteration\tretired\tfetch_bytes\tdata_read_bytes\tdata_written_bytes\tmmio_reads\tmmio_writes\tcpu_state_bytes\ttranslation_bytes\tsteady_allocations\tsteady_allocated_bytes\tvs_native"
}

pub fn format_timing_sample(sample: &BenchmarkTiming) -> String {
    let observation = &sample.observation;
    format!(
        "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{:.3}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{:.6}",
        observation.workload.name(),
        observation.candidate.name(),
        observation.iterations,
        observation.checksum,
        sample.cold_nanos,
        sample.warm_median_nanos,
        sample.warm_p95_nanos,
        sample.nanos_per_iteration(),
        observation.retired_instructions,
        observation.instruction_fetch.bytes_read,
        observation.data_ram.bytes_read,
        observation.data_ram.bytes_written,
        observation.mmio.loads,
        observation.mmio.stores,
        observation.cpu_state_bytes,
        observation.translation_bytes,
        sample.steady_allocations,
        sample.steady_allocated_bytes,
        sample.vs_native,
    )
}

pub fn populate_vs_native(samples: &mut [BenchmarkTiming]) -> Result<(), String> {
    let native = samples
        .iter()
        .filter(|sample| sample.observation.candidate == BenchmarkCandidate::NativeRust)
        .map(|sample| (sample.observation.workload, sample.warm_median_nanos))
        .collect::<HashMap<_, _>>();
    for sample in samples {
        let duration = native
            .get(&sample.observation.workload)
            .filter(|duration| **duration > 0)
            .ok_or_else(|| {
                format!(
                    "missing native-rust sample for {}",
                    sample.observation.workload.name()
                )
            })?;
        sample.vs_native = sample.warm_median_nanos as f64 / *duration as f64;
    }
    Ok(())
}

pub fn format_summary(samples: &[BenchmarkTiming]) -> Result<String, String> {
    let mut output = String::from("candidate\thost_overhead_geomean\n");
    for candidate in BenchmarkCandidate::all() {
        let ratios = samples
            .iter()
            .filter(|sample| sample.observation.candidate == *candidate)
            .map(|sample| sample.vs_native)
            .filter(|ratio| *ratio > 0.0)
            .collect::<Vec<_>>();
        if ratios.is_empty() {
            return Err(format!("missing timing samples for {}", candidate.name()));
        }
        let mean = (ratios.iter().map(|ratio| ratio.ln()).sum::<f64>() / ratios.len() as f64).exp();
        output.push_str(&format!("{}\t{mean:.6}\n", candidate.name()));
    }
    Ok(output)
}
