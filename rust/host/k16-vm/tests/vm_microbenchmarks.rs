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

use k16_vm::vm_microbenchmarks::{
    benchmark_output_header, format_benchmark_sample, run_k16_workload, run_native_rust_workload,
    VmBenchmarkSample, VmBenchmarkWorkload,
};
use std::fs;
use std::path::Path;

#[test]
fn compute_loop_runs_on_k16() {
    let iterations = 7;

    assert_eq!(
        run_k16_workload(VmBenchmarkWorkload::ComputeLoop, iterations).unwrap(),
        iterations,
    );
}

#[test]
fn memory_loop_runs_on_k16() {
    let iterations = 7;

    assert_eq!(
        run_k16_workload(VmBenchmarkWorkload::MemoryLoop, iterations).unwrap(),
        iterations,
    );
}

#[test]
fn mmio_loop_runs_on_k16() {
    let iterations = 7;

    assert_eq!(
        run_k16_workload(VmBenchmarkWorkload::MmioLoop, iterations).unwrap(),
        iterations,
    );
}

#[test]
fn branch_mix_runs_on_k16() {
    let iterations = 7;

    assert_eq!(
        run_k16_workload(VmBenchmarkWorkload::BranchMix, iterations).unwrap(),
        run_native_rust_workload(VmBenchmarkWorkload::BranchMix, iterations).unwrap(),
    );
}

#[test]
fn call_loop_runs_on_k16() {
    let iterations = 7;

    assert_eq!(
        run_k16_workload(VmBenchmarkWorkload::CallLoop, iterations).unwrap(),
        run_native_rust_workload(VmBenchmarkWorkload::CallLoop, iterations).unwrap(),
    );
}

#[test]
fn benchmark_workload_list_includes_mmio_loop_for_cli_output() {
    let names = VmBenchmarkWorkload::all()
        .iter()
        .map(|workload| workload.name())
        .collect::<Vec<_>>();

    assert_eq!(
        names,
        vec![
            "compute-loop",
            "memory-loop",
            "mmio-loop",
            "branch-mix",
            "call-loop",
        ],
    );
}

#[test]
fn native_rust_workloads_match_k16_checksums() {
    let iterations = 11;

    for workload in VmBenchmarkWorkload::all() {
        assert_eq!(
            run_native_rust_workload(*workload, iterations).unwrap(),
            run_k16_workload(*workload, iterations).unwrap(),
            "{} checksum mismatch",
            workload.name(),
        );
    }
}

#[test]
fn benchmark_output_header_includes_native_ratio_columns() {
    let header = benchmark_output_header();

    assert!(header.contains("vs_native"));
    assert!(header.contains("native_pct"));
}

#[test]
fn benchmark_output_rows_show_multiplier_and_percent_against_native() {
    let native = VmBenchmarkSample {
        workload: VmBenchmarkWorkload::ComputeLoop,
        vm: "native-rust",
        iterations: 100,
        checksum: 100,
        best_nanos: 100,
    };
    let k16 = VmBenchmarkSample {
        workload: VmBenchmarkWorkload::ComputeLoop,
        vm: "k16",
        iterations: 100,
        checksum: 100,
        best_nanos: 250,
    };

    let native_row = format_benchmark_sample(&native, native.best_nanos);
    let k16_row = format_benchmark_sample(&k16, native.best_nanos);

    assert!(native_row.contains("1.000x"));
    assert!(native_row.contains("100.0%"));
    assert!(k16_row.contains("2.500x"));
    assert!(k16_row.contains("250.0%"));
}

#[test]
fn memory_loop_budget_covers_larger_benchmark_runs() {
    let iterations = 1_000;

    assert_eq!(
        run_k16_workload(VmBenchmarkWorkload::MemoryLoop, iterations).unwrap(),
        iterations,
    );
}

#[test]
fn vm_microbenchmarks_source_does_not_expose_low_image_path() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let source = fs::read_to_string(manifest_dir.join("src/vm_microbenchmarks.rs")).unwrap();
    let example = fs::read_to_string(manifest_dir.join("examples/vm_microbenchmarks.rs")).unwrap();

    assert!(source.contains("pub fn run_k16_workload("));
    assert!(example.contains("run_k16_workload"));
    assert!(example.contains("collect_sample(*workload, \"k16\""));
    assert!(!source.contains("low_image"));
    assert!(!source.contains("LowImage"));
    assert!(!source.contains("run_low_image_workload"));
    assert!(!source.contains("run_rux_workload"));
    assert!(!example.contains("\"rux\""));
}
