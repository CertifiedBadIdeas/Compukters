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
    format_recommendations, populate_vs_native, timing_report_header, IsaBenchmarkCandidate,
    IsaBenchmarkTiming, IsaBenchmarkWorkload, PreparedIsaBenchmark,
};

#[test]
fn timing_report_uses_the_stable_gate1_columns() {
    assert_eq!(
        timing_report_header(),
        "workload\tcandidate\titerations\tchecksum\tcold_ns\twarm_median_ns\twarm_p95_ns\tnanos_per_iteration\tretired\tfetch_bytes\tdata_read_bytes\tdata_written_bytes\tmmio_reads\tmmio_writes\tcpu_state_bytes\ttranslation_bytes\tsteady_allocations\tsteady_allocated_bytes\tvs_native",
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
fn predecoded_candidate_reports_data_reads_without_fake_bus_fetches() {
    for candidate in [
        IsaBenchmarkCandidate::K16F32Predecoded,
        IsaBenchmarkCandidate::Rv32imPredecoded,
    ] {
        let mut prepared =
            PreparedIsaBenchmark::new(candidate, IsaBenchmarkWorkload::MemorySequential, 17)
                .unwrap();
        let observation = prepared.execute().unwrap();
        assert_eq!(observation.instruction_fetch.bytes_read, 0);
        assert!(observation.data_ram.bytes_read > 0);
    }
}

#[test]
fn cached_candidates_fetch_cold_and_reuse_decoding_warm() {
    for candidate in [
        IsaBenchmarkCandidate::K16Cached,
        IsaBenchmarkCandidate::Rv32imCached,
    ] {
        let mut prepared =
            PreparedIsaBenchmark::new(candidate, IsaBenchmarkWorkload::Compute32, 17).unwrap();
        let cold = prepared.execute().unwrap();
        let warm = prepared.execute().unwrap();
        assert!(
            cold.instruction_fetch.bytes_read > 0,
            "{}",
            candidate.name()
        );
        assert_eq!(warm.instruction_fetch.bytes_read, 0, "{}", candidate.name());
        assert!(warm.translation_bytes > 0, "{}", candidate.name());
    }
}

#[test]
fn recommendation_requires_nonzero_samples_and_lists_every_candidate() {
    assert!(format_recommendations(&[]).is_err());

    let mut samples = IsaBenchmarkCandidate::all()
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
    populate_vs_native(&mut samples).unwrap();
    let report = format_recommendations(&samples).unwrap();
    assert!(
        report.starts_with("candidate\tnormalized_vm_geomean\thost_overhead_geomean\tdecision\n")
    );
    for candidate in IsaBenchmarkCandidate::all() {
        assert!(report
            .lines()
            .any(|line| line.starts_with(candidate.name())));
    }
    assert!(report.lines().any(|line| line.ends_with("\treference")));
    assert!(report
        .lines()
        .filter(|line| line.ends_with("\treference"))
        .all(|line| line.starts_with("native-rust\t")));
}

#[test]
fn custom_candidate_advances_only_inside_the_gate1_viability_window() {
    let samples = candidate_samples([400, 300, 250, 140, 131, 105, 120, 110, 100, 10]);
    let report = format_recommendations(&samples).unwrap();
    assert!(report.contains("k16-f32-predecoded\t1.310000\t13.100000\treject"));

    let samples = candidate_samples([400, 300, 250, 140, 125, 105, 120, 110, 100, 10]);
    let report = format_recommendations(&samples).unwrap();
    assert!(report.contains("k16-f32\t1.400000\t14.000000\treject"));
    assert!(report.contains("k16-f32-predecoded\t1.250000\t12.500000\tadvance"));
}

#[test]
fn native_ratios_are_absolute_and_do_not_change_vm_decisions() {
    let fast_native = candidate_samples([400, 300, 250, 140, 125, 105, 120, 110, 100, 10]);
    let slow_native = candidate_samples([400, 300, 250, 140, 125, 105, 120, 110, 100, 20]);

    assert_eq!(fast_native[0].vs_native, 40.0);
    assert_eq!(fast_native.last().unwrap().vs_native, 1.0);
    assert_eq!(slow_native[0].vs_native, 20.0);
    assert_eq!(slow_native.last().unwrap().vs_native, 1.0);

    let fast_report = format_recommendations(&fast_native).unwrap();
    let slow_report = format_recommendations(&slow_native).unwrap();
    let fast_decisions = decision_columns(&fast_report);
    let slow_decisions = decision_columns(&slow_report);
    assert_eq!(fast_decisions, slow_decisions);
}

fn candidate_samples(nanos: [u128; 10]) -> Vec<IsaBenchmarkTiming> {
    let mut samples = IsaBenchmarkCandidate::all()
        .iter()
        .zip(nanos)
        .map(|(candidate, nanos)| {
            IsaBenchmarkTiming::for_test(*candidate, IsaBenchmarkWorkload::Compute32, nanos)
        })
        .collect::<Vec<_>>();
    populate_vs_native(&mut samples).unwrap();
    samples
}

fn decision_columns(report: &str) -> Vec<(&str, &str)> {
    report
        .lines()
        .skip(1)
        .map(|line| {
            let columns = line.split('\t').collect::<Vec<_>>();
            (columns[0], columns[3])
        })
        .collect()
}
