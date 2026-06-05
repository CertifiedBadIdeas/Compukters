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
use std::env;
use std::hint::black_box;
use std::time::Instant;

fn main() {
    let args = env::args().collect::<Vec<_>>();
    let iterations = parse_arg(&args, 1, "iterations");
    let samples = parse_arg(&args, 2, "samples");

    println!("{}", benchmark_output_header());
    for workload in VmBenchmarkWorkload::all() {
        let k16 = collect_sample(*workload, "k16", iterations, samples, run_k16_workload);
        let native = collect_sample(
            *workload,
            "native-rust",
            iterations,
            samples,
            run_native_rust_workload,
        );
        println!("{}", format_benchmark_sample(&k16, native.best_nanos));
        println!("{}", format_benchmark_sample(&native, native.best_nanos));
    }
}

fn collect_sample(
    workload: VmBenchmarkWorkload,
    vm: &'static str,
    iterations: u32,
    samples: u32,
    run: fn(VmBenchmarkWorkload, u32) -> Result<u32, String>,
) -> VmBenchmarkSample {
    let mut expected_checksum = None;
    let mut best_nanos = u128::MAX;
    for _ in 0..samples {
        let started = Instant::now();
        let checksum = black_box(run(workload, black_box(iterations)).unwrap());
        let elapsed = started.elapsed().as_nanos();
        if let Some(expected) = expected_checksum {
            assert_eq!(
                expected,
                checksum,
                "{} {vm} checksum changed between samples",
                workload.name(),
            );
        } else {
            expected_checksum = Some(checksum);
        }
        best_nanos = best_nanos.min(elapsed);
    }

    VmBenchmarkSample {
        workload,
        vm,
        iterations,
        checksum: expected_checksum.expect("at least one sample is required"),
        best_nanos,
    }
}

fn parse_arg(args: &[String], index: usize, name: &str) -> u32 {
    args.get(index)
        .unwrap_or_else(|| panic!("missing {name} argument"))
        .parse()
        .unwrap_or_else(|error| panic!("invalid {name} argument: {error}"))
}
