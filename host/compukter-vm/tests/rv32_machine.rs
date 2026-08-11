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

use compukter_vm::rv32_machine::{
    Rv32ExecutionBackendConfig, Rv32Machine, Rv32MachineConfig, Rv32MachineOutcome, CONTROL_BASE,
    DEBUG_BASE, STATUS_BOOTING, STATUS_HALTED, STATUS_PANIC,
};
use compukter_vm::rv32im::encoding::{
    addi, csrrs, csrrw, ebreak, ecall, jal, lui, materialize, sb, sh, sw,
};
use rv32_elf_support::{halting_machine_elf, machine_program_elf, Elf32Builder, LoadSegment};

const CSR_MTVEC: u16 = 0x305;
const CSR_MEPC: u16 = 0x341;
const CSR_MCAUSE: u16 = 0x342;
const CSR_MTVAL: u16 = 0x343;

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
        assert_eq!(machine.control_status(), STATUS_BOOTING);
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
        assert_eq!(machine.control_status(), STATUS_HALTED);
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
fn both_backends_turn_execution_from_rw_memory_into_bounded_traps() {
    let elf = machine_program_elf(&[jal(0, 0x2000)]);

    for execution in configs() {
        let mut machine = Rv32Machine::from_elf(&elf, config(execution, 8)).unwrap();
        assert_eq!(
            machine.run(2).unwrap(),
            Rv32MachineOutcome::BudgetExhausted {
                retired_delta: 1,
                retired_total: 1,
            }
        );
        assert_eq!(machine.pc(), 0);
        assert_eq!(
            machine.run(3).unwrap(),
            Rv32MachineOutcome::BudgetExhausted {
                retired_delta: 0,
                retired_total: 1,
            }
        );
    }
}

#[test]
fn both_backends_trap_guest_writes_to_rx_memory_without_retiring_the_store() {
    let [address_hi, address_lo] = materialize(1, 0x1000);
    let elf = machine_program_elf(&[address_hi, address_lo, addi(2, 0, 7), sw(1, 2, 0)]);

    for execution in configs() {
        let mut machine = Rv32Machine::from_elf(&elf, config(execution, 8)).unwrap();
        assert_eq!(
            machine.run(8).unwrap(),
            Rv32MachineOutcome::BudgetExhausted {
                retired_delta: 3,
                retired_total: 3,
            }
        );
        assert_eq!(machine.pc(), 0);
    }
}

#[test]
fn both_backends_turn_invalid_ecall_and_ebreak_into_non_retiring_traps() {
    for word in [0xffff_ffff, ecall(), ebreak()] {
        let elf = machine_program_elf(&[word]);
        for execution in configs() {
            let mut machine = Rv32Machine::from_elf(&elf, config(execution, 8)).unwrap();
            assert_eq!(
                machine.run(1).unwrap(),
                Rv32MachineOutcome::BudgetExhausted {
                    retired_delta: 0,
                    retired_total: 0,
                }
            );
            assert_eq!(machine.pc(), 0);
        }
    }
}

#[test]
fn bounded_debug_overflow_is_a_guest_trap() {
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
        assert_eq!(
            machine.run(16).unwrap(),
            Rv32MachineOutcome::BudgetExhausted {
                retired_delta: 5,
                retired_total: 5,
            }
        );
        assert_eq!(machine.debug_bytes(), b"A");
    }
}

#[test]
fn faulting_halfword_mmio_stores_have_no_partial_device_effects() {
    let [debug_hi, debug_lo] = materialize(1, DEBUG_BASE);
    let debug_elf =
        machine_program_elf(&[debug_hi, debug_lo, addi(2, 0, i32::from(b'A')), sh(1, 2, 0)]);
    let [control_hi, control_lo] = materialize(1, CONTROL_BASE);
    let control_elf = machine_program_elf(&[
        control_hi,
        control_lo,
        addi(2, 0, STATUS_HALTED),
        sh(1, 2, 0),
    ]);

    for execution in configs() {
        let mut machine = Rv32Machine::from_elf(&debug_elf, config(execution, 8)).unwrap();
        assert_eq!(
            machine.run(8).unwrap(),
            Rv32MachineOutcome::BudgetExhausted {
                retired_delta: 3,
                retired_total: 3,
            }
        );
        assert_eq!(machine.debug_bytes(), b"");

        let mut machine = Rv32Machine::from_elf(&control_elf, config(execution, 0)).unwrap();
        assert_eq!(
            machine.run(8).unwrap(),
            Rv32MachineOutcome::BudgetExhausted {
                retired_delta: 3,
                retired_total: 3,
            }
        );
        assert_eq!(machine.control_status(), STATUS_BOOTING);
    }
}

#[test]
fn both_backends_share_precise_trap_entry_and_attempt_budgeting() {
    let [vector_hi, vector_lo] = materialize(1, 0x2000);
    let main = [vector_hi, vector_lo, csrrw(0, CSR_MTVEC, 1), ecall()];
    let [debug_hi, debug_lo] = materialize(5, DEBUG_BASE);
    let handler = [
        csrrs(2, CSR_MEPC, 0),
        csrrs(3, CSR_MCAUSE, 0),
        csrrs(4, CSR_MTVAL, 0),
        debug_hi,
        debug_lo,
        sb(5, 2, 0),
        sb(5, 3, 0),
        sb(5, 4, 0),
        lui(1, 0x10000),
        sw(1, 0, 8),
        addi(6, 0, STATUS_HALTED),
        sw(1, 6, 0),
    ];
    let words = |words: &[u32]| {
        words
            .iter()
            .copied()
            .flat_map(u32::to_le_bytes)
            .collect::<Vec<_>>()
    };
    let elf = Elf32Builder::new(0x1000)
        .load(LoadSegment::rx(0x1000, words(&main)))
        .load(LoadSegment::rx(0x2000, words(&handler)))
        .load(LoadSegment::rw_with_mem_size(0x3000, [], 0x1000))
        .finish();

    for execution in configs() {
        let mut machine = Rv32Machine::from_elf(&elf, config(execution, 3)).unwrap();
        assert_eq!(
            machine.run(3).unwrap(),
            Rv32MachineOutcome::BudgetExhausted {
                retired_delta: 3,
                retired_total: 3,
            }
        );
        assert_eq!(machine.pc(), 0x100c);
        assert_eq!(
            machine.run(1).unwrap(),
            Rv32MachineOutcome::BudgetExhausted {
                retired_delta: 0,
                retired_total: 3,
            }
        );
        assert_eq!(machine.pc(), 0x2000);
        assert_eq!(
            machine.run(32).unwrap(),
            Rv32MachineOutcome::Halted {
                exit_code: 0,
                retired_delta: 12,
                retired_total: 15,
            }
        );
        assert_eq!(machine.debug_bytes(), &[0x0c, 11, 0]);
    }
}

#[test]
fn panic_status_returns_the_guest_panic_code() {
    let elf = machine_program_elf(&[
        lui(1, 0x10000),
        addi(2, 0, 99),
        sw(1, 2, 4),
        addi(3, 0, STATUS_PANIC),
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
            ram_size: CONTROL_BASE as usize + 1,
            debug_limit: 8,
            execution: Rv32ExecutionBackendConfig::Predecoded,
        }
    )
    .is_err());
}
