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

use k16_vm::isa_benchmarks::{run_candidate, IsaBenchmarkCandidate, IsaBenchmarkWorkload};

#[test]
fn current_k16_runs_every_gate1_workload_in_all_decode_modes() {
    for workload in IsaBenchmarkWorkload::all() {
        let direct = run_candidate(IsaBenchmarkCandidate::K16, *workload, 19).unwrap();
        let cached = run_candidate(IsaBenchmarkCandidate::K16Cached, *workload, 19).unwrap();
        let predecoded =
            run_candidate(IsaBenchmarkCandidate::K16Predecoded, *workload, 19).unwrap();
        direct.validate_checksum().unwrap();
        cached.validate_checksum().unwrap();
        predecoded.validate_checksum().unwrap();
        assert_eq!(direct.checksum, cached.checksum, "{}", workload.name());
        assert_eq!(direct.checksum, predecoded.checksum, "{}", workload.name());
        assert_eq!(
            direct.retired_instructions,
            cached.retired_instructions,
            "{}",
            workload.name(),
        );
        assert_eq!(
            direct.retired_instructions,
            predecoded.retired_instructions,
            "{}",
            workload.name(),
        );
        assert_eq!(direct.yields, cached.yields, "{}", workload.name());
        assert_eq!(direct.yields, predecoded.yields, "{}", workload.name());
        assert_eq!(direct.data_ram, cached.data_ram, "{}", workload.name());
        assert_eq!(direct.data_ram, predecoded.data_ram, "{}", workload.name());
        assert_eq!(direct.mmio, cached.mmio, "{}", workload.name());
        assert_eq!(direct.mmio, predecoded.mmio, "{}", workload.name());
        assert_eq!(predecoded.instruction_fetch.bytes_read, 0);
        assert!(cached.translation_bytes > 0);
        assert!(predecoded.translation_bytes > 0);
    }
}

#[test]
fn k16_f32_matches_native_checksums() {
    for workload in IsaBenchmarkWorkload::all() {
        let sample = run_candidate(IsaBenchmarkCandidate::K16F32, *workload, 19).unwrap();
        sample.validate_checksum().unwrap();
        assert_eq!(sample.instruction_fetch.bytes_read % 4, 0);
        assert_eq!(sample.translation_bytes, 0);
    }
}

#[test]
fn native_rust_matches_checksums_without_guest_state() {
    for workload in IsaBenchmarkWorkload::all() {
        let sample = run_candidate(IsaBenchmarkCandidate::NativeRust, *workload, 19).unwrap();
        sample.validate_checksum().unwrap();
        assert_eq!(sample.retired_instructions, 0);
        assert_eq!(sample.yields, 0);
        assert_eq!(sample.instruction_fetch, Default::default());
        assert_eq!(sample.data_ram, Default::default());
        assert_eq!(sample.mmio, Default::default());
        assert_eq!(sample.cpu_state_bytes, 0);
        assert_eq!(sample.translation_bytes, 0);
    }
}

#[test]
fn all_rv32im_candidates_share_programs_and_checksums() {
    for workload in IsaBenchmarkWorkload::all() {
        let external = run_candidate(IsaBenchmarkCandidate::RvsimRv32im, *workload, 19).unwrap();
        let direct = run_candidate(IsaBenchmarkCandidate::Rv32im, *workload, 19).unwrap();
        let cached = run_candidate(IsaBenchmarkCandidate::Rv32imCached, *workload, 19).unwrap();
        let predecoded =
            run_candidate(IsaBenchmarkCandidate::Rv32imPredecoded, *workload, 19).unwrap();
        external.validate_checksum().unwrap();
        direct.validate_checksum().unwrap();
        cached.validate_checksum().unwrap();
        predecoded.validate_checksum().unwrap();
        assert_eq!(external.checksum, direct.checksum, "{}", workload.name());
        assert_eq!(direct.checksum, predecoded.checksum, "{}", workload.name());
        assert_eq!(direct.checksum, cached.checksum, "{}", workload.name());
        assert_eq!(
            external.retired_instructions,
            direct.retired_instructions,
            "{}",
            workload.name(),
        );
        assert_eq!(
            direct.retired_instructions,
            cached.retired_instructions,
            "{}",
            workload.name(),
        );
        assert_eq!(
            direct.retired_instructions,
            predecoded.retired_instructions,
            "{}",
            workload.name(),
        );
        assert_eq!(direct.yields, cached.yields, "{}", workload.name());
        assert_eq!(direct.yields, predecoded.yields, "{}", workload.name());
        assert_eq!(direct.data_ram, cached.data_ram, "{}", workload.name());
        assert_eq!(direct.data_ram, predecoded.data_ram, "{}", workload.name());
        assert_eq!(direct.mmio, cached.mmio, "{}", workload.name());
        assert_eq!(direct.mmio, predecoded.mmio, "{}", workload.name());
        assert_eq!(predecoded.instruction_fetch.bytes_read, 0);
        assert!(cached.translation_bytes > 0);
        assert!(predecoded.translation_bytes > 0);
    }
}
