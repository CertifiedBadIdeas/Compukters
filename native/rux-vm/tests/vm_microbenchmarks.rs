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

use rux_vm::vm_microbenchmarks::{run_low_image_workload, run_rux16_workload, VmBenchmarkWorkload};

#[test]
fn compute_loop_matches_between_low_image_and_rux16() {
    let iterations = 7;

    assert_eq!(
        run_low_image_workload(VmBenchmarkWorkload::ComputeLoop, iterations).unwrap(),
        run_rux16_workload(VmBenchmarkWorkload::ComputeLoop, iterations).unwrap(),
    );
}

#[test]
fn memory_loop_matches_between_low_image_and_rux16() {
    let iterations = 7;

    assert_eq!(
        run_low_image_workload(VmBenchmarkWorkload::MemoryLoop, iterations).unwrap(),
        run_rux16_workload(VmBenchmarkWorkload::MemoryLoop, iterations).unwrap(),
    );
}

#[test]
fn memory_loop_budget_covers_larger_benchmark_runs() {
    let iterations = 1_000;

    assert_eq!(
        run_rux16_workload(VmBenchmarkWorkload::MemoryLoop, iterations).unwrap(),
        iterations,
    );
}
