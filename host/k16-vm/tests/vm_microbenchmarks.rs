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
    benchmark_output_header, format_benchmark_sample, format_instruction_profile_report_sample,
    format_vm_stats_report_sample, instruction_profile_report_header, run_k16_workload,
    run_k16_workload_cached_decode, run_k16_workload_instruction_profile, run_k16_workload_stats,
    run_native_rust_workload, vm_stats_report_header, VmBenchmarkSample, VmBenchmarkWorkload,
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
fn k16_workload_stats_report_counts_cpu_ram_and_mmio_work() {
    let iterations = 7;

    let sample = run_k16_workload_stats(VmBenchmarkWorkload::MmioLoop, iterations).unwrap();

    assert_eq!(sample.workload, VmBenchmarkWorkload::MmioLoop);
    assert_eq!(sample.iterations, iterations);
    assert_eq!(sample.checksum, iterations);
    assert!(sample.cpu_steps > 0, "{sample:?}");
    assert!(sample.bus.ram.loads > 0, "{sample:?}");
    assert!(sample.bus.ram.bytes_read > 0, "{sample:?}");
    assert_eq!(sample.bus.mmio.loads, u64::from(iterations) + 1);
    assert_eq!(sample.bus.mmio.stores, u64::from(iterations));
    assert_eq!(sample.bus.mmio_devices.len(), 1);
    assert_eq!(sample.bus.mmio_devices[0].traffic, sample.bus.mmio);
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
fn cached_decode_k16_workloads_match_normal_k16_checksums() {
    let iterations = 11;

    for workload in VmBenchmarkWorkload::all() {
        assert_eq!(
            run_k16_workload(*workload, iterations).unwrap(),
            run_k16_workload_cached_decode(*workload, iterations).unwrap(),
            "{} cached decode checksum mismatch",
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
fn vm_stats_report_output_shows_cpu_ram_and_mmio_columns() {
    let sample = run_k16_workload_stats(VmBenchmarkWorkload::MmioLoop, 3).unwrap();
    let header = vm_stats_report_header();
    let row = format_vm_stats_report_sample(&sample);

    assert!(header.contains("cpu_steps"));
    assert!(header.contains("ram_loads"));
    assert!(header.contains("mmio_stores"));
    assert!(header.contains("mmio_bytes_written"));
    assert!(row.contains("mmio-loop"));
    assert!(row.contains("3"));
}

#[test]
fn instruction_profile_counts_phases_and_opcode_families() {
    let sample = run_k16_workload_instruction_profile(VmBenchmarkWorkload::BranchMix, 3).unwrap();

    assert_eq!(sample.workload, VmBenchmarkWorkload::BranchMix);
    assert_eq!(sample.iterations, 3);
    assert_eq!(
        sample.checksum,
        run_native_rust_workload(VmBenchmarkWorkload::BranchMix, 3).unwrap()
    );
    assert_eq!(sample.profile.instructions, sample.cpu_steps);
    assert!(sample.profile.fetch_decode_nanos > 0, "{sample:?}");
    assert!(sample.profile.execute_nanos > 0, "{sample:?}");
    assert!(sample.profile.families.alu > 0, "{sample:?}");
    assert!(sample.profile.families.branch > 0, "{sample:?}");
    assert!(sample.profile.families.control > 0, "{sample:?}");
}

#[test]
fn instruction_profile_report_output_shows_phase_and_family_columns() {
    let sample = run_k16_workload_instruction_profile(VmBenchmarkWorkload::MemoryLoop, 2).unwrap();
    let header = instruction_profile_report_header();
    let row = format_instruction_profile_report_sample(&sample);

    assert!(header.contains("fetch_decode_ns"));
    assert!(header.contains("execute_ns"));
    assert!(header.contains("load_store_ops"));
    assert!(header.contains("control_ops"));
    assert!(row.contains("memory-loop"));
    assert!(row.contains("2"));
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
    let stats_example =
        fs::read_to_string(manifest_dir.join("examples/vm_stats_report.rs")).unwrap();

    assert!(source.contains("pub fn run_k16_workload("));
    assert!(source.contains("pub fn run_k16_workload_stats("));
    assert!(example.contains("run_k16_workload"));
    assert!(example.contains("run_k16_workload_cached_decode"));
    assert!(example.contains("\"k16-cached\""));
    assert!(stats_example.contains("run_k16_workload_stats"));
    assert!(stats_example.contains("vm_stats_report_header"));
    assert!(example.contains("collect_sample(*workload, \"k16\""));
    assert!(!source.contains("low_image"));
    assert!(!source.contains("LowImage"));
    assert!(!source.contains("run_low_image_workload"));
    assert!(!source.contains("run_rux_workload"));
    assert!(!example.contains("\"rux\""));
}
