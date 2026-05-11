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

use std::env;
use std::time::Instant;

fn main() {
    let args: Vec<String> = env::args().collect();
    let workload = args
        .get(1)
        .unwrap_or_else(|| panic!("missing workload argument"));
    let iterations = parse_arg(&args, 2, "iterations");
    let samples = parse_arg(&args, 3, "samples");
    let warmup_iterations = parse_arg(&args, 4, "warmup_iterations");

    if warmup_iterations > 0 {
        run_workload(workload, warmup_iterations);
    }

    let mut checksum = None;
    let mut best_nanos = u128::MAX;
    for _ in 0..samples {
        let started = Instant::now();
        let sample_checksum = run_workload(workload, iterations);
        let elapsed = started.elapsed().as_nanos();
        if let Some(expected) = checksum {
            assert_eq!(
                expected, sample_checksum,
                "{workload} native benchmark checksum changed between samples"
            );
        } else {
            checksum = Some(sample_checksum);
        }
        best_nanos = best_nanos.min(elapsed);
    }

    println!("checksum\tbest_nanos");
    println!(
        "{}\t{}",
        checksum.expect("at least one sample is required"),
        best_nanos
    );
}

fn parse_arg(args: &[String], index: usize, name: &str) -> i32 {
    args.get(index)
        .unwrap_or_else(|| panic!("missing {name} argument"))
        .parse()
        .unwrap_or_else(|error| panic!("invalid {name} argument: {error}"))
}

fn run_workload(workload: &str, iterations: i32) -> i32 {
    match workload {
        "integer-mix" => integer_mix(iterations),
        "function-mix" => function_mix(iterations),
        "branch-div" => branch_div(iterations),
        "recursive-fib" => recursive_fib_workload(iterations),
        _ => panic!("unknown workload: {workload}"),
    }
}

fn integer_mix(iterations: i32) -> i32 {
    assert!(iterations >= 0, "iterations must be non-negative");
    let mut state: i32 = 305_419_896;
    let mut acc: i32 = -1_640_531_527;
    let mut i: i32 = 0;
    while i < iterations {
        state = state.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
        let x = state ^ (state >> 16);
        let previous_acc = acc;
        acc = previous_acc.wrapping_add(x) ^ previous_acc.wrapping_shl(5);
        acc = acc.wrapping_add(i.wrapping_mul(31) ^ (x >> 3));
        i += 1;
    }
    acc
}

fn function_mix(iterations: i32) -> i32 {
    assert!(iterations >= 0, "iterations must be non-negative");
    let mut acc: i32 = 324_508_639;
    let mut i: i32 = 0;
    while i < iterations {
        acc = function_mix_b(function_mix_a(acc, i), i);
        i += 1;
    }
    acc
}

fn function_mix_a(value: i32, index: i32) -> i32 {
    (value.wrapping_add(index.wrapping_mul(17)) ^ value.wrapping_shl(3)).wrapping_add(index >> 1)
}

fn function_mix_b(value: i32, index: i32) -> i32 {
    (value ^ index.wrapping_mul(131)).wrapping_add(value >> 2) ^ index.wrapping_shl(4)
}

fn branch_div(iterations: i32) -> i32 {
    assert!(iterations >= 0, "iterations must be non-negative");
    let mut acc: i32 = 7;
    let mut i: i32 = 1;
    while i < iterations + 1 {
        let modulo = i % 11;
        acc = if modulo == 0 {
            acc.wrapping_add(i / 3)
        } else if modulo < 5 {
            (acc ^ i.wrapping_mul(17)).wrapping_add(i % 7)
        } else {
            acc.wrapping_sub(i / (modulo + 1))
                .wrapping_add(acc.wrapping_shl(1))
        };
        i += 1;
    }
    acc
}

fn recursive_fib_workload(iterations: i32) -> i32 {
    assert!(iterations >= 0, "iterations must be non-negative");
    let mut acc: i32 = 0;
    let mut i: i32 = 0;
    while i < iterations {
        let n = 10 + (i % 6);
        acc = acc.wrapping_add(recursive_fib(n) ^ i.wrapping_mul(31));
        i += 1;
    }
    acc
}

fn recursive_fib(value: i32) -> i32 {
    if value < 2 {
        value
    } else {
        recursive_fib(value - 1).wrapping_add(recursive_fib(value - 2))
    }
}
