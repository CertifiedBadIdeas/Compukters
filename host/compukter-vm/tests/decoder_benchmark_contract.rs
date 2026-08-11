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

use compukter_vm::benchmarks::{native_checksum, BenchmarkCandidate, BenchmarkWorkload};

#[test]
fn decoder_candidates_exclude_retired_architectures() {
    assert_eq!(
        BenchmarkCandidate::all()
            .iter()
            .map(|candidate| candidate.name())
            .collect::<Vec<_>>(),
        [
            "rv32-direct",
            "rv32-cached",
            "rv32-predecoded",
            "native-rust",
        ],
    );
}

#[test]
fn decoder_workloads_keep_stable_native_oracles() {
    assert_eq!(BenchmarkWorkload::all().len(), 9);
    for &workload in BenchmarkWorkload::all() {
        assert_eq!(native_checksum(workload, 17), native_checksum(workload, 17),);
        assert_ne!(native_checksum(workload, 17), native_checksum(workload, 18),);
    }
}
