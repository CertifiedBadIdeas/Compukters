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
fn current_k16_runs_every_gate1_workload_in_both_decode_modes() {
    for workload in IsaBenchmarkWorkload::all() {
        let direct = run_candidate(IsaBenchmarkCandidate::K16, *workload, 19).unwrap();
        let cached = run_candidate(IsaBenchmarkCandidate::K16Cached, *workload, 19).unwrap();
        direct.validate_checksum().unwrap();
        cached.validate_checksum().unwrap();
        assert_eq!(direct.checksum, cached.checksum, "{}", workload.name());
        assert_eq!(
            direct.retired_instructions,
            cached.retired_instructions,
            "{}",
            workload.name(),
        );
    }
}
