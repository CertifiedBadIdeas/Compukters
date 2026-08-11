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

use compukter_vm::benchmarks::{
    native_checksum, PreparedProductMachine, ProductMachineBackend, ProductMachineWorkload,
};

#[test]
fn cached_and_predecoded_use_identical_strict_elf() {
    for workload in ProductMachineWorkload::all() {
        let cached =
            PreparedProductMachine::new(ProductMachineBackend::Cached, *workload, 17).unwrap();
        let predecoded =
            PreparedProductMachine::new(ProductMachineBackend::Predecoded, *workload, 17).unwrap();

        assert_eq!(cached.elf_bytes(), predecoded.elf_bytes());
        assert_eq!(&cached.elf_bytes()[..4], b"\x7fELF");
        assert_eq!(cached.elf_bytes()[4], 1, "ELFCLASS32");
        assert_eq!(cached.elf_bytes()[5], 1, "ELFDATA2LSB");
    }
}

#[test]
fn product_workloads_halt_with_expected_checksum() {
    for backend in ProductMachineBackend::all() {
        for workload in ProductMachineWorkload::all() {
            let mut prepared = PreparedProductMachine::new(*backend, *workload, 17).unwrap();
            let observation = prepared.execute().unwrap();
            let expected = match workload {
                ProductMachineWorkload::TrapRoundtrip => 17,
                _ => native_checksum(workload.decoder_workload().unwrap(), 17),
            };

            assert!(observation.complete_machine);
            assert_eq!(observation.checksum, expected, "{backend:?} {workload:?}");
            assert!(observation.retired_instructions > 0);
        }
    }
}
