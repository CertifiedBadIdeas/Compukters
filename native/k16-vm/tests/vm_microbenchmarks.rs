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

use k16_vm::vm_microbenchmarks::{run_rux16_workload, VmBenchmarkWorkload};
use std::fs;
use std::path::Path;

#[test]
fn compute_loop_runs_on_rux16() {
    let iterations = 7;

    assert_eq!(
        run_rux16_workload(VmBenchmarkWorkload::ComputeLoop, iterations).unwrap(),
        iterations,
    );
}

#[test]
fn memory_loop_runs_on_rux16() {
    let iterations = 7;

    assert_eq!(
        run_rux16_workload(VmBenchmarkWorkload::MemoryLoop, iterations).unwrap(),
        iterations,
    );
}

#[test]
fn mmio_loop_runs_on_rux16() {
    let iterations = 7;

    assert_eq!(
        run_rux16_workload(VmBenchmarkWorkload::MmioLoop, iterations).unwrap(),
        iterations,
    );
}

#[test]
fn benchmark_workload_list_includes_mmio_loop_for_cli_output() {
    let names = VmBenchmarkWorkload::all()
        .iter()
        .map(|workload| workload.name())
        .collect::<Vec<_>>();

    assert_eq!(names, vec!["compute-loop", "memory-loop", "mmio-loop"]);
}

#[test]
fn memory_loop_budget_covers_larger_benchmark_runs() {
    let iterations = 1_000;

    assert_eq!(
        run_rux16_workload(VmBenchmarkWorkload::MemoryLoop, iterations).unwrap(),
        iterations,
    );
}

#[test]
fn vm_microbenchmarks_source_does_not_expose_low_image_path() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let source = fs::read_to_string(manifest_dir.join("src/vm_microbenchmarks.rs")).unwrap();

    assert!(!source.contains("low_image"));
    assert!(!source.contains("LowImage"));
    assert!(!source.contains("run_low_image_workload"));
}
