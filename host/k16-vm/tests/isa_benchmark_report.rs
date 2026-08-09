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

use k16_vm::isa_benchmarks::{
    format_recommendations, timing_report_header, IsaBenchmarkCandidate, IsaBenchmarkTiming,
    IsaBenchmarkWorkload, PreparedIsaBenchmark,
};

#[test]
fn timing_report_uses_the_stable_gate1_columns() {
    assert_eq!(
        timing_report_header(),
        "workload\tcandidate\titerations\tchecksum\tcold_ns\twarm_median_ns\twarm_p95_ns\tnanos_per_iteration\tretired\tfetch_bytes\tdata_read_bytes\tdata_written_bytes\tmmio_reads\tmmio_writes\tcpu_state_bytes\ttranslation_bytes\tsteady_allocations\tsteady_allocated_bytes",
    );
}

#[test]
fn prepared_candidate_can_be_executed_repeatedly() {
    for candidate in IsaBenchmarkCandidate::all() {
        let mut prepared =
            PreparedIsaBenchmark::new(*candidate, IsaBenchmarkWorkload::Compute32, 17).unwrap();
        let first = prepared.execute().unwrap();
        let warm = prepared.execute().unwrap();
        assert_eq!(warm.checksum, first.checksum, "{}", candidate.name());
        assert_eq!(warm.retired_instructions, first.retired_instructions);
    }
}

#[test]
fn recommendation_requires_nonzero_samples_and_lists_every_candidate() {
    assert!(format_recommendations(&[]).is_err());

    let samples = IsaBenchmarkCandidate::all()
        .iter()
        .enumerate()
        .map(|(index, candidate)| {
            IsaBenchmarkTiming::for_test(
                *candidate,
                IsaBenchmarkWorkload::Compute32,
                100 + index as u128,
            )
        })
        .collect::<Vec<_>>();
    let report = format_recommendations(&samples).unwrap();
    assert!(report.starts_with("candidate\tnormalized_geomean\tdecision\n"));
    for candidate in IsaBenchmarkCandidate::all() {
        assert!(report
            .lines()
            .any(|line| line.starts_with(candidate.name())));
    }
    assert!(report
        .lines()
        .skip(1)
        .all(|line| { line.ends_with("\tadvance") || line.ends_with("\treject") }));
}
