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

use k16_vm::vm_microbenchmarks::{run_k16_workload, VmBenchmarkWorkload};
use std::env;
use std::hint::black_box;
use std::time::Instant;

fn main() {
    let args = env::args().collect::<Vec<_>>();
    let iterations = parse_arg(&args, 1, "iterations");
    let samples = parse_arg(&args, 2, "samples");

    println!("workload\tvm\titerations\tchecksum\tbest_nanos\tnanos_per_iteration");
    for workload in VmBenchmarkWorkload::all() {
        print_sample(*workload, "k16", iterations, samples, run_k16_workload);
    }
}

fn print_sample(
    workload: VmBenchmarkWorkload,
    vm: &str,
    iterations: u32,
    samples: u32,
    run: fn(VmBenchmarkWorkload, u32) -> Result<u32, String>,
) {
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

    let nanos_per_iteration = if iterations == 0 {
        0.0
    } else {
        best_nanos as f64 / f64::from(iterations)
    };
    println!(
        "{}\t{vm}\t{iterations}\t{}\t{best_nanos}\t{nanos_per_iteration:.3}",
        workload.name(),
        expected_checksum.expect("at least one sample is required"),
    );
}

fn parse_arg(args: &[String], index: usize, name: &str) -> u32 {
    args.get(index)
        .unwrap_or_else(|| panic!("missing {name} argument"))
        .parse()
        .unwrap_or_else(|error| panic!("invalid {name} argument: {error}"))
}
