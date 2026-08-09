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

use k16_vm::isa_benchmarks::{
    native_checksum, IsaBenchmarkCandidate, IsaBenchmarkObservation, IsaBenchmarkWorkload,
};

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

#[test]
fn required_candidates_are_stable() {
    assert_eq!(
        IsaBenchmarkCandidate::all()
            .iter()
            .map(|candidate| candidate.name())
            .collect::<Vec<_>>(),
        vec![
            "k16",
            "k16-cached",
            "k16-predecoded",
            "k16-f32",
            "k16-f32-predecoded",
            "rvsim-rv32im",
            "rv32im",
            "rv32im-cached",
            "rv32im-predecoded",
            "native-rust",
        ],
    );
}

#[test]
fn observation_rejects_a_wrong_checksum() {
    let observation = IsaBenchmarkObservation::for_test(
        IsaBenchmarkCandidate::K16,
        IsaBenchmarkWorkload::Compute32,
        3,
        99,
    );

    let error = observation.validate_checksum().unwrap_err();
    assert!(error.contains("k16"), "{error}");
    assert!(error.contains("compute32"), "{error}");
    assert!(error.contains("expected"), "{error}");
    assert!(error.contains("actual 99"), "{error}");
}
