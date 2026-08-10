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

#[path = "support/rv32_elf.rs"]
mod rv32_elf_support;

use k16_vm::computer_abi;
use k16_vm::rv32_machine::{
    Rv32ExecutionBackendConfig, Rv32Machine, Rv32MachineConfig, Rv32MachineOutcome,
};
use k16_vm::rv32im::encoding::{addi, ebreak, ecall, jal, lui, materialize, sb, sw};
use rv32_elf_support::{halting_machine_elf, machine_program_elf};

fn configs() -> [Rv32ExecutionBackendConfig; 2] {
    [
        Rv32ExecutionBackendConfig::Cached { sets: 64 },
        Rv32ExecutionBackendConfig::Predecoded,
    ]
}

#[test]
fn both_backends_run_from_elf_entry_under_budget_and_halt_through_mmio() {
    let elf = halting_machine_elf(b'R');

    for execution in configs() {
        let config = Rv32MachineConfig {
            ram_size: 0x10_000,
            debug_limit: 16,
            execution,
        };
        let mut machine = Rv32Machine::from_elf(&elf, config).unwrap();
        assert_eq!(machine.pc(), 0x1000);
        assert_eq!(machine.control_status(), computer_abi::STATUS_BOOTING);
        assert_eq!(
            machine.run(0).unwrap(),
            Rv32MachineOutcome::BudgetExhausted {
                retired_delta: 0,
                retired_total: 0,
            }
        );
        assert_eq!(
            machine.run(3).unwrap(),
            Rv32MachineOutcome::BudgetExhausted {
                retired_delta: 3,
                retired_total: 3,
            }
        );
        assert_eq!(
            machine.run(64).unwrap(),
            Rv32MachineOutcome::Halted {
                exit_code: 0,
                retired_delta: 4,
                retired_total: 7,
            }
        );
        assert_eq!(machine.debug_bytes(), b"R");
        assert_eq!(machine.control_status(), computer_abi::STATUS_HALTED);
        assert_eq!(machine.retired_instructions(), 7);
    }
}

fn config(execution: Rv32ExecutionBackendConfig, debug_limit: usize) -> Rv32MachineConfig {
    Rv32MachineConfig {
        ram_size: 0x10_000,
        debug_limit,
        execution,
    }
}

#[test]
fn both_backends_reject_execution_from_rw_memory() {
    let elf = machine_program_elf(&[jal(0, 0x2000)]);

    for execution in configs() {
        let mut machine = Rv32Machine::from_elf(&elf, config(execution, 8)).unwrap();
        let error = machine.run(2).unwrap_err();
        assert_eq!(error.pc(), 0x3000);
        assert_eq!(error.retired_total(), 1);
        assert!(error.to_string().contains("outside executable memory"));
    }
}

#[test]
fn both_backends_reject_guest_writes_to_rx_memory_without_retiring_the_store() {
    let [address_hi, address_lo] = materialize(1, 0x1000);
    let elf = machine_program_elf(&[address_hi, address_lo, addi(2, 0, 7), sw(1, 2, 0)]);

    for execution in configs() {
        let mut machine = Rv32Machine::from_elf(&elf, config(execution, 8)).unwrap();
        let error = machine.run(8).unwrap_err();
        assert_eq!(error.pc(), 0x100c);
        assert_eq!(error.retired_total(), 3);
        assert!(error.to_string().contains("write"));
    }
}

#[test]
fn both_backends_report_invalid_and_unsupported_stop_instructions_without_fallback() {
    for word in [0xffff_ffff, ecall(), ebreak()] {
        let elf = machine_program_elf(&[word]);
        for execution in configs() {
            let mut machine = Rv32Machine::from_elf(&elf, config(execution, 8)).unwrap();
            let error = machine.run(1).unwrap_err();
            let expected_retired = u64::from(word != 0xffff_ffff);
            assert_eq!(error.retired_total(), expected_retired);
        }
    }
}

#[test]
fn bounded_debug_overflow_is_a_machine_execution_error() {
    let elf = machine_program_elf(&[
        lui(1, 0x10000),
        addi(2, 1, 0x100),
        addi(3, 0, i32::from(b'A')),
        sb(2, 3, 0),
        addi(3, 0, i32::from(b'B')),
        sb(2, 3, 0),
    ]);

    for execution in configs() {
        let mut machine = Rv32Machine::from_elf(&elf, config(execution, 1)).unwrap();
        let error = machine.run(16).unwrap_err();
        assert!(error.to_string().contains("limit 1"));
        assert_eq!(machine.debug_bytes(), b"A");
    }
}

#[test]
fn panic_status_returns_the_guest_panic_code() {
    let elf = machine_program_elf(&[
        lui(1, 0x10000),
        addi(2, 0, 99),
        sw(1, 2, 4),
        addi(3, 0, computer_abi::STATUS_PANIC),
        sw(1, 3, 0),
    ]);

    for execution in configs() {
        let mut machine = Rv32Machine::from_elf(&elf, config(execution, 0)).unwrap();
        assert_eq!(
            machine.run(16).unwrap(),
            Rv32MachineOutcome::Panicked {
                panic_code: 99,
                retired_delta: 5,
                retired_total: 5,
            }
        );
    }
}

#[test]
fn invalid_cache_and_ram_layouts_fail_before_machine_allocation() {
    let elf = halting_machine_elf(b'R');
    assert!(Rv32Machine::from_elf(
        &elf,
        config(Rv32ExecutionBackendConfig::Cached { sets: 3 }, 8)
    )
    .is_err());
    assert!(Rv32Machine::from_elf(
        &elf,
        Rv32MachineConfig {
            ram_size: computer_abi::CONTROL_BASE as usize + 1,
            debug_limit: 8,
            execution: Rv32ExecutionBackendConfig::Predecoded,
        }
    )
    .is_err());
}
