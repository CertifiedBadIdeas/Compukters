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
    format_vm_stats_report_sample, run_k16_workload_stats, vm_stats_report_header,
    VmBenchmarkWorkload,
};
use std::env;

fn main() {
    let args = env::args().collect::<Vec<_>>();
    let iterations = parse_arg(&args, 1, "iterations");

    println!("{}", vm_stats_report_header());
    for workload in VmBenchmarkWorkload::all() {
        let sample = run_k16_workload_stats(*workload, iterations).unwrap();
        println!("{}", format_vm_stats_report_sample(&sample));
    }
}

fn parse_arg(args: &[String], index: usize, name: &str) -> u32 {
    args.get(index)
        .unwrap_or_else(|| panic!("missing {name} argument"))
        .parse()
        .unwrap_or_else(|error| panic!("invalid {name} argument: {error}"))
}
