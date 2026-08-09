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

use k16_vm::compiled_c::artifact::CompiledCCandidate;
use k16_vm::compiled_c::report::{
    format_decision, format_decision_for_candidate, populate_vs_native, select_compiled_c,
    select_compiled_c_candidate, CompiledCDecision, CompiledCTiming,
};
use k16_vm::compiled_c::runner::CompiledCObservation;
use k16_vm::isa_benchmarks::IsaBenchmarkWorkload;

fn timing(
    candidate: CompiledCCandidate,
    workload: IsaBenchmarkWorkload,
    nanos: u128,
) -> CompiledCTiming {
    CompiledCTiming {
        observation: CompiledCObservation {
            workload,
            candidate,
            iterations: 1,
            checksum: 1,
            retired_instructions: 1,
            code_bytes: 100,
            instruction_count: 25,
            cpu_state_bytes: 128,
            predecode_bytes: 100,
            data_read_bytes: 0,
            data_written_bytes: 0,
        },
        cold_predecode_nanos: nanos,
        warm_median_nanos: nanos,
        warm_p95_nanos: nanos,
        steady_allocations: 0,
        steady_allocated_bytes: 0,
        vs_native: 0.0,
    }
}

fn rows(k16_nanos: u128, rv_nanos: u128) -> Vec<CompiledCTiming> {
    rows_for(CompiledCCandidate::K16F32, k16_nanos, rv_nanos)
}

fn rows_for(
    custom: CompiledCCandidate,
    custom_nanos: u128,
    rv_nanos: u128,
) -> Vec<CompiledCTiming> {
    let mut rows = Vec::new();
    for workload in IsaBenchmarkWorkload::all().iter().copied().take(6) {
        rows.push(timing(custom, workload, custom_nanos));
        rows.push(timing(CompiledCCandidate::Rv32im, workload, rv_nanos));
    }
    rows
}

#[test]
fn gate2_wrapper_keeps_legacy_decision_rendering() {
    let rendered = format_decision(&rows(500_000, 1_000_000)).unwrap();
    assert!(rendered.starts_with(
        "candidate\tnormalized_warm_geomean\ttotal_code_bytes\ttotal_predecode_bytes\nk16-f32\t"
    ));
    assert!(rendered.ends_with("decision\tselect-k16-f32\n"));
    assert!(!rendered.contains("k16-f32r32-lr"));
}

#[test]
fn f32r32_candidate_uses_the_unchanged_thresholds_and_rendering() {
    let custom = CompiledCCandidate::K16F32R32LR;
    assert_eq!(
        select_compiled_c_candidate(&rows_for(custom, 819_900, 1_000_000), custom).unwrap(),
        CompiledCDecision::SelectK16F32R32LR
    );
    assert_eq!(
        select_compiled_c_candidate(&rows_for(custom, 880_100, 1_000_000), custom).unwrap(),
        CompiledCDecision::SelectRv32im
    );
    for custom_nanos in [880_000, 850_000, 820_000] {
        assert_eq!(
            select_compiled_c_candidate(&rows_for(custom, custom_nanos, 1_000_000), custom)
                .unwrap(),
            CompiledCDecision::Inconclusive
        );
    }

    let rendered =
        format_decision_for_candidate(&rows_for(custom, 500_000, 1_000_000), custom).unwrap();
    assert!(rendered.contains("\nk16-f32r32-lr\t"));
    assert!(rendered.ends_with("decision\tselect-k16-f32r32-lr\n"));
}

#[test]
fn f32r32_candidate_keeps_workload_and_retained_size_guardrails() {
    let custom = CompiledCCandidate::K16F32R32LR;
    let mut slowdown = rows_for(custom, 500_000, 1_000_000);
    slowdown
        .iter_mut()
        .find(|sample| sample.observation.candidate == custom)
        .unwrap()
        .warm_median_nanos = 1_300_100;
    assert_eq!(
        select_compiled_c_candidate(&slowdown, custom).unwrap(),
        CompiledCDecision::SelectRv32im
    );

    for field in ["code", "predecode"] {
        let mut samples = rows_for(custom, 500_000, 1_000_000);
        for sample in &mut samples {
            if sample.observation.candidate == custom {
                if field == "code" {
                    sample.observation.code_bytes = 12_501;
                } else {
                    sample.observation.predecode_bytes = 12_501;
                }
            } else if field == "code" {
                sample.observation.code_bytes = 10_000;
            } else {
                sample.observation.predecode_bytes = 10_000;
            }
        }
        assert_eq!(
            select_compiled_c_candidate(&samples, custom).unwrap(),
            CompiledCDecision::SelectRv32im,
            "{field}"
        );
    }
}

#[test]
fn candidate_report_rejects_mixed_custom_rows() {
    let custom = CompiledCCandidate::K16F32R32LR;
    let mut mixed = rows_for(custom, 500_000, 1_000_000);
    mixed[0].observation.candidate = CompiledCCandidate::K16F32;
    assert!(select_compiled_c_candidate(&mixed, custom)
        .unwrap_err()
        .contains("missing k16-f32r32-lr timing"));
}

#[test]
fn speed_band_has_inclusive_inconclusive_edges() {
    assert_eq!(
        select_compiled_c(&rows(819_900, 1_000_000)).unwrap(),
        CompiledCDecision::SelectK16F32
    );
    assert_eq!(
        select_compiled_c(&rows(880_100, 1_000_000)).unwrap(),
        CompiledCDecision::SelectRv32im
    );
    for k16_nanos in [880_000, 850_000, 820_000] {
        assert_eq!(
            select_compiled_c(&rows(k16_nanos, 1_000_000)).unwrap(),
            CompiledCDecision::Inconclusive,
            "k16 duration {k16_nanos}"
        );
    }
}

#[test]
fn one_workload_more_than_thirty_percent_slower_selects_rv32im() {
    let mut samples = rows(500_000, 1_000_000);
    samples
        .iter_mut()
        .find(|sample| sample.observation.candidate == CompiledCCandidate::K16F32)
        .unwrap()
        .warm_median_nanos = 1_300_100;

    assert_eq!(
        select_compiled_c(&samples).unwrap(),
        CompiledCDecision::SelectRv32im
    );
}

#[test]
fn code_or_predecode_more_than_twenty_five_percent_larger_selects_rv32im() {
    for field in ["code", "predecode"] {
        let mut samples = rows(500_000, 1_000_000);
        for sample in &mut samples {
            if sample.observation.candidate == CompiledCCandidate::K16F32 {
                if field == "code" {
                    sample.observation.code_bytes = 12_501;
                } else {
                    sample.observation.predecode_bytes = 12_501;
                }
            } else if field == "code" {
                sample.observation.code_bytes = 10_000;
            } else {
                sample.observation.predecode_bytes = 10_000;
            }
        }
        assert_eq!(
            select_compiled_c(&samples).unwrap(),
            CompiledCDecision::SelectRv32im,
            "{field}"
        );
    }
}

#[test]
fn native_duration_changes_ratios_but_not_isa_decision() {
    let mut first = rows(500_000, 1_000_000);
    let mut second = first.clone();
    let native_fast = IsaBenchmarkWorkload::all()
        .iter()
        .copied()
        .take(6)
        .map(|workload| (workload, 100_000))
        .collect::<Vec<_>>();
    let native_slow = IsaBenchmarkWorkload::all()
        .iter()
        .copied()
        .take(6)
        .map(|workload| (workload, 200_000))
        .collect::<Vec<_>>();
    populate_vs_native(&mut first, &native_fast).unwrap();
    populate_vs_native(&mut second, &native_slow).unwrap();

    assert_ne!(first[0].vs_native, second[0].vs_native);
    assert_eq!(select_compiled_c(&first), select_compiled_c(&second));
}

#[test]
fn any_steady_allocation_rejects_the_report() {
    let mut allocations = rows(500_000, 1_000_000);
    allocations[0].steady_allocations = 1;
    assert!(select_compiled_c(&allocations)
        .unwrap_err()
        .contains("steady allocation"));

    let mut bytes = rows(500_000, 1_000_000);
    bytes[0].steady_allocated_bytes = 1;
    assert!(select_compiled_c(&bytes)
        .unwrap_err()
        .contains("steady allocation"));
}
