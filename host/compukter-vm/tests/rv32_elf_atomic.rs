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

use compukter_vm::rv32_machine::{
    Rv32ExecutionBackendConfig, Rv32Machine, Rv32MachineConfig, Rv32MachineOutcome,
};

const MARKER: &[u8] = b"RV32 ELF ATOMIC OK\n";

#[test]
#[ignore = "requires the stock Clang/LLD RV32IMA atomic fixture"]
fn stock_toolchain_rv32ima_elf_executes_atomics_and_fences() {
    let path = std::env::var_os("RV32_ELF_ATOMIC_FIXTURE")
        .expect("RV32_ELF_ATOMIC_FIXTURE must name the compiled ELF");
    let elf = std::fs::read(path).unwrap();

    for execution in [
        Rv32ExecutionBackendConfig::Cached { sets: 128 },
        Rv32ExecutionBackendConfig::Predecoded,
    ] {
        let config = Rv32MachineConfig {
            ram_size: 64 * 1024,
            debug_limit: MARKER.len(),
            execution,
        };
        let mut machine = Rv32Machine::from_elf(&elf, config).unwrap();
        let outcome = machine.run(4096).unwrap();
        assert!(matches!(
            outcome,
            Rv32MachineOutcome::Halted { exit_code: 0, .. }
        ));
        assert_eq!(machine.debug_bytes(), MARKER);
    }
}
