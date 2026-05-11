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
    let iterations = parse_arg(&args, 1, "iterations");
    let samples = parse_arg(&args, 2, "samples");
    let warmup_iterations = parse_arg(&args, 3, "warmup_iterations");

    if warmup_iterations > 0 {
        integer_mix(warmup_iterations);
    }

    let mut checksum = None;
    let mut best_nanos = u128::MAX;
    for _ in 0..samples {
        let started = Instant::now();
        let sample_checksum = integer_mix(iterations);
        let elapsed = started.elapsed().as_nanos();
        if let Some(expected) = checksum {
            assert_eq!(
                expected, sample_checksum,
                "native benchmark checksum changed between samples"
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
