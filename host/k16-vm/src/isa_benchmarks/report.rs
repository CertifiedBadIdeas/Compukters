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

use super::{IsaBenchmarkCandidate, IsaBenchmarkObservation, IsaBenchmarkWorkload};
use std::collections::HashMap;

const GATE1_CUSTOM_VIABILITY_RATIO: f64 = 1.30;

#[derive(Debug, Clone, PartialEq)]
pub struct IsaBenchmarkTiming {
    pub observation: IsaBenchmarkObservation,
    pub cold_nanos: u128,
    pub warm_median_nanos: u128,
    pub warm_p95_nanos: u128,
    pub steady_allocations: u64,
    pub steady_allocated_bytes: u64,
    pub vs_native: f64,
}

impl IsaBenchmarkTiming {
    pub fn for_test(
        candidate: IsaBenchmarkCandidate,
        workload: IsaBenchmarkWorkload,
        warm_median_nanos: u128,
    ) -> Self {
        Self {
            observation: IsaBenchmarkObservation::for_test(candidate, workload, 1, 0),
            cold_nanos: warm_median_nanos,
            warm_median_nanos,
            warm_p95_nanos: warm_median_nanos,
            steady_allocations: 0,
            steady_allocated_bytes: 0,
            vs_native: 0.0,
        }
    }

    pub fn nanos_per_iteration(&self) -> f64 {
        if self.observation.iterations == 0 {
            0.0
        } else {
            self.warm_median_nanos as f64 / f64::from(self.observation.iterations)
        }
    }
}

pub const fn timing_report_header() -> &'static str {
    "workload\tcandidate\titerations\tchecksum\tcold_ns\twarm_median_ns\twarm_p95_ns\tnanos_per_iteration\tretired\tfetch_bytes\tdata_read_bytes\tdata_written_bytes\tmmio_reads\tmmio_writes\tcpu_state_bytes\ttranslation_bytes\tsteady_allocations\tsteady_allocated_bytes\tvs_native"
}

pub fn format_timing_sample(sample: &IsaBenchmarkTiming) -> String {
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

pub fn populate_vs_native(samples: &mut [IsaBenchmarkTiming]) -> Result<(), String> {
    let mut native_by_workload = HashMap::new();
    for sample in samples
        .iter()
        .filter(|sample| sample.observation.candidate == IsaBenchmarkCandidate::NativeRust)
    {
        if sample.warm_median_nanos == 0 {
            return Err(format!(
                "native-rust workload {} has zero warm duration",
                sample.observation.workload.name(),
            ));
        }
        if native_by_workload
            .insert(sample.observation.workload, sample.warm_median_nanos)
            .is_some()
        {
            return Err(format!(
                "duplicate native-rust sample for workload {}",
                sample.observation.workload.name(),
            ));
        }
    }
    for sample in samples {
        let native = native_by_workload
            .get(&sample.observation.workload)
            .ok_or_else(|| {
                format!(
                    "missing native-rust sample for workload {}",
                    sample.observation.workload.name(),
                )
            })?;
        sample.vs_native = sample.warm_median_nanos as f64 / *native as f64;
    }
    Ok(())
}

pub fn format_recommendations(samples: &[IsaBenchmarkTiming]) -> Result<String, String> {
    if samples.is_empty()
        || samples
            .iter()
            .any(|sample| sample.warm_median_nanos == 0 || sample.vs_native <= 0.0)
    {
        return Err("Gate 1 recommendations require non-zero timing samples".to_string());
    }
    let mut fastest_by_workload = HashMap::new();
    for sample in samples
        .iter()
        .filter(|sample| !sample.observation.candidate.is_native_reference())
    {
        fastest_by_workload
            .entry(sample.observation.workload)
            .and_modify(|fastest: &mut u128| *fastest = (*fastest).min(sample.warm_median_nanos))
            .or_insert(sample.warm_median_nanos);
    }
    let mut means = HashMap::new();
    for candidate in IsaBenchmarkCandidate::all() {
        let vm_ratios = samples
            .iter()
            .filter(|sample| sample.observation.candidate == *candidate)
            .filter_map(|sample| {
                fastest_by_workload
                    .get(&sample.observation.workload)
                    .map(|fastest| sample.warm_median_nanos as f64 / *fastest as f64)
            })
            .collect::<Vec<_>>();
        let host_ratios = samples
            .iter()
            .filter(|sample| sample.observation.candidate == *candidate)
            .map(|sample| sample.vs_native)
            .collect::<Vec<_>>();
        if host_ratios.is_empty() || (!candidate.is_native_reference() && vm_ratios.is_empty()) {
            return Err(format!("missing timing samples for {}", candidate.name()));
        }
        let vm_mean = if candidate.is_native_reference() {
            None
        } else {
            Some(geometric_mean(&vm_ratios))
        };
        means.insert(*candidate, (vm_mean, geometric_mean(&host_ratios)));
    }
    let k16_winner = fastest_family(&means, IsaBenchmarkCandidate::is_k16_v1);
    let rv32_winner = fastest_family(&means, IsaBenchmarkCandidate::is_specialized_rv32im);
    let mut output =
        String::from("candidate\tnormalized_vm_geomean\thost_overhead_geomean\tdecision\n");
    for candidate in IsaBenchmarkCandidate::all() {
        let decision = if candidate.is_native_reference() {
            "reference"
        } else if *candidate == k16_winner
            || *candidate == rv32_winner
            || (*candidate == IsaBenchmarkCandidate::K16F32
                && means[candidate].0.unwrap()
                    <= means[&rv32_winner].0.unwrap() * GATE1_CUSTOM_VIABILITY_RATIO)
        {
            "advance"
        } else {
            "reject"
        };
        if candidate.is_native_reference() {
            output.push_str(&format!(
                "{}\tn/a\t{:.6}\t{decision}\n",
                candidate.name(),
                means[candidate].1,
            ));
        } else {
            output.push_str(&format!(
                "{}\t{:.6}\t{:.6}\t{decision}\n",
                candidate.name(),
                means[candidate].0.unwrap(),
                means[candidate].1,
            ));
        }
    }
    Ok(output)
}

fn geometric_mean(ratios: &[f64]) -> f64 {
    (ratios.iter().map(|ratio| ratio.ln()).sum::<f64>() / ratios.len() as f64).exp()
}

fn fastest_family(
    means: &HashMap<IsaBenchmarkCandidate, (Option<f64>, f64)>,
    belongs: impl Fn(IsaBenchmarkCandidate) -> bool,
) -> IsaBenchmarkCandidate {
    IsaBenchmarkCandidate::all()
        .iter()
        .copied()
        .filter(|candidate| belongs(*candidate))
        .min_by(|lhs, rhs| means[lhs].0.unwrap().total_cmp(&means[rhs].0.unwrap()))
        .unwrap()
}
