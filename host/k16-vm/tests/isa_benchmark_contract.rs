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

use k16_vm::isa_benchmarks::{native_checksum, IsaBenchmarkWorkload};

#[test]
fn gate1_catalogue_has_the_accepted_workloads() {
    let names = IsaBenchmarkWorkload::all()
        .iter()
        .map(|workload| workload.name())
        .collect::<Vec<_>>();

    assert_eq!(
        names,
        vec![
            "compute32",
            "branch-mix",
            "call-stack",
            "memory-sequential",
            "memory-random",
            "copy-checksum",
            "mmio-control",
            "yield-wake",
            "packet-ring",
        ],
    );
}

#[test]
fn native_checksums_are_deterministic() {
    for workload in IsaBenchmarkWorkload::all() {
        assert_eq!(
            native_checksum(*workload, 17),
            native_checksum(*workload, 17),
        );
    }
}
