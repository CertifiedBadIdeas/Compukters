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

#[derive(Debug, Clone, PartialEq)]
pub struct IsaBenchmarkTiming {
    pub observation: IsaBenchmarkObservation,
    pub cold_nanos: u128,
    pub warm_median_nanos: u128,
    pub warm_p95_nanos: u128,
    pub steady_allocations: u64,
    pub steady_allocated_bytes: u64,
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
    "workload\tcandidate\titerations\tchecksum\tcold_ns\twarm_median_ns\twarm_p95_ns\tnanos_per_iteration\tretired\tfetch_bytes\tdata_read_bytes\tdata_written_bytes\tmmio_reads\tmmio_writes\tcpu_state_bytes\ttranslation_bytes\tsteady_allocations\tsteady_allocated_bytes"
}

pub fn format_timing_sample(sample: &IsaBenchmarkTiming) -> String {
    let observation = &sample.observation;
    format!(
        "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{:.3}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
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
    )
}

pub fn format_recommendations(samples: &[IsaBenchmarkTiming]) -> Result<String, String> {
    if samples.is_empty() || samples.iter().any(|sample| sample.warm_median_nanos == 0) {
        return Err("Gate 1 recommendations require non-zero timing samples".to_string());
    }
    let mut fastest_by_workload = HashMap::new();
    for sample in samples {
        fastest_by_workload
            .entry(sample.observation.workload)
            .and_modify(|fastest: &mut u128| *fastest = (*fastest).min(sample.warm_median_nanos))
            .or_insert(sample.warm_median_nanos);
    }
    let mut means = HashMap::new();
    for candidate in IsaBenchmarkCandidate::all() {
        let ratios = samples
            .iter()
            .filter(|sample| sample.observation.candidate == *candidate)
            .map(|sample| {
                sample.warm_median_nanos as f64
                    / *fastest_by_workload
                        .get(&sample.observation.workload)
                        .unwrap() as f64
            })
            .collect::<Vec<_>>();
        if ratios.is_empty() {
            return Err(format!("missing timing samples for {}", candidate.name()));
        }
        let logarithmic_mean =
            ratios.iter().map(|ratio| ratio.ln()).sum::<f64>() / ratios.len() as f64;
        means.insert(*candidate, logarithmic_mean.exp());
    }
    let k16_winner = faster(
        &means,
        IsaBenchmarkCandidate::K16,
        IsaBenchmarkCandidate::K16Cached,
    );
    let rv32_winner = faster(
        &means,
        IsaBenchmarkCandidate::Rv32im,
        IsaBenchmarkCandidate::Rv32imPredecoded,
    );
    let mut output = String::from("candidate\tnormalized_geomean\tdecision\n");
    for candidate in IsaBenchmarkCandidate::all() {
        let advance = *candidate == IsaBenchmarkCandidate::K16F32
            || *candidate == k16_winner
            || *candidate == rv32_winner;
        output.push_str(&format!(
            "{}\t{:.6}\t{}\n",
            candidate.name(),
            means[candidate],
            if advance { "advance" } else { "reject" },
        ));
    }
    Ok(output)
}

fn faster(
    means: &HashMap<IsaBenchmarkCandidate, f64>,
    lhs: IsaBenchmarkCandidate,
    rhs: IsaBenchmarkCandidate,
) -> IsaBenchmarkCandidate {
    if means[&lhs] <= means[&rhs] {
        lhs
    } else {
        rhs
    }
}
