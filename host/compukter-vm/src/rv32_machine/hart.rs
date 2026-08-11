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

use super::csr::Rv32MachineCsrs;
use crate::memory::MemoryBus;
use crate::rv32im::{CsrOperation, CsrSource, DecodedInstruction, Rv32RegularFault, Rv32imCpu};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
pub(super) enum Rv32ExceptionCause {
    InstructionAddressMisaligned = 0,
    InstructionAccessFault = 1,
    IllegalInstruction = 2,
    Breakpoint = 3,
    LoadAddressMisaligned = 4,
    LoadAccessFault = 5,
    StoreAddressMisaligned = 6,
    StoreAccessFault = 7,
    EnvironmentCallFromMachine = 11,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum Rv32HartStep {
    Retired,
    TrapTaken,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct Rv32MachineHart {
    cpu: Rv32imCpu,
    csrs: Rv32MachineCsrs,
}

impl Rv32MachineHart {
    pub(super) fn new(pc: u32) -> Self {
        Self {
            cpu: Rv32imCpu::new(pc),
            csrs: Rv32MachineCsrs::new(),
        }
    }

    pub(super) fn pc(&self) -> u32 {
        self.cpu.pc()
    }

    #[cfg(test)]
    pub(super) fn register(&self, register: usize) -> u32 {
        self.cpu.register(register)
    }

    pub(super) fn retired_instructions(&self) -> u64 {
        self.cpu.retired_instructions()
    }

    #[allow(
        dead_code,
        reason = "machine-owned writers use this when multi-hart or DMA support is added"
    )]
    pub(super) fn invalidate_reservation(&mut self, address: u32, size: u32) {
        self.cpu.invalidate_reservation(address, size);
    }

    pub(super) fn execute_resolved(
        &mut self,
        bus: &mut dyn MemoryBus,
        instruction_pc: u32,
        word: u32,
        instruction: DecodedInstruction,
    ) -> Rv32HartStep {
        match instruction {
            DecodedInstruction::Csr {
                operation,
                rd,
                csr,
                source,
            } => self.execute_csr(instruction_pc, word, operation, rd, csr, source),
            DecodedInstruction::Mret => {
                let pc = self.csrs.return_from_trap();
                self.cpu.set_pc_internal(pc);
                self.cpu.commit_instruction();
                Rv32HartStep::Retired
            }
            DecodedInstruction::Ecall => self.take_trap(
                instruction_pc,
                Rv32ExceptionCause::EnvironmentCallFromMachine,
                0,
            ),
            DecodedInstruction::Ebreak => self.take_trap(
                instruction_pc,
                Rv32ExceptionCause::Breakpoint,
                instruction_pc,
            ),
            regular => match self.cpu.execute_decoded_typed(bus, instruction_pc, regular) {
                Ok(_) => {
                    self.cpu.commit_instruction();
                    Rv32HartStep::Retired
                }
                Err(fault) => self.take_regular_fault(instruction_pc, fault),
            },
        }
    }

    pub(super) fn take_trap(
        &mut self,
        instruction_pc: u32,
        cause: Rv32ExceptionCause,
        value: u32,
    ) -> Rv32HartStep {
        self.cpu.clear_reservation();
        let vector = self.csrs.enter_trap(instruction_pc, cause as u32, value);
        self.cpu.set_pc_internal(vector);
        Rv32HartStep::TrapTaken
    }

    pub(super) fn take_instruction_access_fault(&mut self, address: u32) -> Rv32HartStep {
        self.take_trap(
            self.pc(),
            Rv32ExceptionCause::InstructionAccessFault,
            address,
        )
    }

    pub(super) fn take_instruction_address_misaligned(&mut self, address: u32) -> Rv32HartStep {
        self.take_trap(
            self.pc(),
            Rv32ExceptionCause::InstructionAddressMisaligned,
            address,
        )
    }

    pub(super) fn take_illegal_instruction(&mut self, word: u32) -> Rv32HartStep {
        self.take_trap(self.pc(), Rv32ExceptionCause::IllegalInstruction, word)
    }

    fn execute_csr(
        &mut self,
        instruction_pc: u32,
        word: u32,
        operation: CsrOperation,
        rd: usize,
        csr: u16,
        source: CsrSource,
    ) -> Rv32HartStep {
        let (source_value, source_is_zero) = match source {
            CsrSource::Register(register) => (self.cpu.register(register), register == 0),
            CsrSource::Immediate(immediate) => (u32::from(immediate), immediate == 0),
        };
        let write_requested = operation == CsrOperation::Write || !source_is_zero;
        let old = match self
            .csrs
            .access(csr, operation, source_value, write_requested)
        {
            Ok(old) => old,
            Err(_) => {
                return self.take_trap(instruction_pc, Rv32ExceptionCause::IllegalInstruction, word)
            }
        };
        self.cpu.set_decoded_register(rd, old);
        self.cpu.set_pc_internal(instruction_pc.wrapping_add(4));
        self.cpu.commit_instruction();
        Rv32HartStep::Retired
    }

    fn take_regular_fault(&mut self, instruction_pc: u32, fault: Rv32RegularFault) -> Rv32HartStep {
        let (cause, value) = match fault {
            Rv32RegularFault::InstructionAddressMisaligned { address } => {
                (Rv32ExceptionCause::InstructionAddressMisaligned, address)
            }
            Rv32RegularFault::LoadAddressMisaligned { address } => {
                (Rv32ExceptionCause::LoadAddressMisaligned, address)
            }
            Rv32RegularFault::LoadAccessFault { address, .. } => {
                (Rv32ExceptionCause::LoadAccessFault, address)
            }
            Rv32RegularFault::StoreAddressMisaligned { address } => {
                (Rv32ExceptionCause::StoreAddressMisaligned, address)
            }
            Rv32RegularFault::StoreAccessFault { address, .. } => {
                (Rv32ExceptionCause::StoreAccessFault, address)
            }
            Rv32RegularFault::MachineInstructionRequired => {
                (Rv32ExceptionCause::IllegalInstruction, 0)
            }
        };
        self.take_trap(instruction_pc, cause, value)
    }
}

#[cfg(test)]
impl Rv32MachineHart {
    fn set_register_for_test(&mut self, register: usize, value: u32) -> Result<(), String> {
        self.cpu.set_register(register, value)
    }

    fn read_csr(&self, csr: u16) -> Result<u32, super::csr::Rv32CsrError> {
        self.csrs.read(csr)
    }

    fn write_csr_for_test(&mut self, csr: u16, value: u32) -> Result<(), super::csr::Rv32CsrError> {
        self.csrs.write_software(csr, value)
    }

    fn execute_word_for_test(&mut self, word: u32) -> Rv32HartStep {
        let instruction_pc = self.pc();
        let instruction = match crate::rv32im::decode_word(word) {
            Ok(instruction) => instruction,
            Err(_) => return self.take_illegal_instruction(word),
        };
        let mut bus = crate::bus::MachineBus::new(4).unwrap();
        self.execute_resolved(&mut bus, instruction_pc, word, instruction)
    }
}

#[cfg(test)]
mod tests {
    use super::{Rv32HartStep, Rv32MachineHart};
    use crate::rv32_machine::csr::{
        CSR_MCAUSE, CSR_MEPC, CSR_MHARTID, CSR_MSCRATCH, CSR_MSTATUS, CSR_MTVAL, CSR_MTVEC,
        MSTATUS_MIE, MSTATUS_MPIE, MSTATUS_MPP_MACHINE,
    };
    use crate::rv32im::encoding::{
        amoswap_w, csrrc, csrrci, csrrs, csrrsi, csrrw, csrrwi, ebreak, ecall, jal, lr_w, lw, mret,
        sc_w, sw,
    };

    #[test]
    fn machine_hart_executes_all_atomic_zicsr_forms_and_suppression() {
        let cases = [
            (csrrw(2, CSR_MSCRATCH, 1), 0b0101),
            (csrrs(2, CSR_MSCRATCH, 1), 0b1111),
            (csrrc(2, CSR_MSCRATCH, 1), 0b1010),
            (csrrwi(2, CSR_MSCRATCH, 0b0101), 0b0101),
            (csrrsi(2, CSR_MSCRATCH, 0b0101), 0b1111),
            (csrrci(2, CSR_MSCRATCH, 0b0101), 0b1010),
        ];
        for (word, expected_new) in cases {
            let mut hart = Rv32MachineHart::new(0x1000);
            hart.set_register_for_test(1, 0b0101).unwrap();
            hart.write_csr_for_test(CSR_MSCRATCH, 0b1010).unwrap();

            assert_eq!(hart.execute_word_for_test(word), Rv32HartStep::Retired);
            assert_eq!(hart.register(2), 0b1010);
            assert_eq!(hart.read_csr(CSR_MSCRATCH).unwrap(), expected_new);
            assert_eq!(hart.retired_instructions(), 1);
        }

        let mut hart = Rv32MachineHart::new(0x1000);
        assert_eq!(
            hart.execute_word_for_test(csrrs(2, CSR_MHARTID, 0)),
            Rv32HartStep::Retired
        );
        assert_eq!(hart.register(2), 0);
    }

    #[test]
    fn requested_read_only_csr_write_traps_atomically() {
        let mut hart = Rv32MachineHart::new(0x1000);
        hart.write_csr_for_test(CSR_MTVEC, 0x2000).unwrap();
        hart.set_register_for_test(1, 0).unwrap();
        hart.set_register_for_test(2, 0xfeed_beef).unwrap();
        let word = csrrs(2, CSR_MHARTID, 1);

        assert_eq!(hart.execute_word_for_test(word), Rv32HartStep::TrapTaken);
        assert_eq!(hart.pc(), 0x2000);
        assert_eq!(hart.register(2), 0xfeed_beef);
        assert_eq!(hart.read_csr(CSR_MEPC).unwrap(), 0x1000);
        assert_eq!(hart.read_csr(CSR_MCAUSE).unwrap(), 2);
        assert_eq!(hart.read_csr(CSR_MTVAL).unwrap(), word);
        assert_eq!(hart.retired_instructions(), 0);
    }

    #[test]
    fn ecall_trap_entry_and_mret_update_exact_machine_state() {
        let mut hart = Rv32MachineHart::new(0x1000);
        hart.write_csr_for_test(CSR_MTVEC, 0x2000).unwrap();
        hart.write_csr_for_test(CSR_MSTATUS, MSTATUS_MIE).unwrap();

        assert_eq!(hart.execute_word_for_test(ecall()), Rv32HartStep::TrapTaken);
        assert_eq!(hart.pc(), 0x2000);
        assert_eq!(hart.read_csr(CSR_MEPC).unwrap(), 0x1000);
        assert_eq!(hart.read_csr(CSR_MCAUSE).unwrap(), 11);
        assert_eq!(hart.read_csr(CSR_MTVAL).unwrap(), 0);
        assert_eq!(
            hart.read_csr(CSR_MSTATUS).unwrap(),
            MSTATUS_MPIE | MSTATUS_MPP_MACHINE
        );
        assert_eq!(hart.retired_instructions(), 0);

        hart.write_csr_for_test(CSR_MEPC, 0x3000).unwrap();
        assert_eq!(hart.execute_word_for_test(mret()), Rv32HartStep::Retired);
        assert_eq!(hart.pc(), 0x3000);
        assert_eq!(
            hart.read_csr(CSR_MSTATUS).unwrap(),
            MSTATUS_MIE | MSTATUS_MPIE | MSTATUS_MPP_MACHINE
        );
        assert_eq!(hart.retired_instructions(), 1);
    }

    #[test]
    fn breakpoint_trap_reports_the_faulting_pc_in_mtval() {
        let mut hart = Rv32MachineHart::new(0x1234);
        hart.write_csr_for_test(CSR_MTVEC, 0x2000).unwrap();

        assert_eq!(
            hart.execute_word_for_test(ebreak()),
            Rv32HartStep::TrapTaken
        );
        assert_eq!(hart.read_csr(CSR_MEPC).unwrap(), 0x1234);
        assert_eq!(hart.read_csr(CSR_MCAUSE).unwrap(), 3);
        assert_eq!(hart.read_csr(CSR_MTVAL).unwrap(), 0x1234);
        assert_eq!(hart.retired_instructions(), 0);
    }

    #[test]
    fn trap_entry_clears_the_hart_reservation() {
        let mut hart = Rv32MachineHart::new(0x1000);
        hart.set_register_for_test(1, 0).unwrap();
        hart.set_register_for_test(2, 42).unwrap();

        assert_eq!(
            hart.execute_word_for_test(lr_w(3, 1, false, false)),
            Rv32HartStep::Retired
        );
        assert_eq!(hart.execute_word_for_test(ecall()), Rv32HartStep::TrapTaken);
        assert_eq!(
            hart.execute_word_for_test(sc_w(4, 1, 2, false, false)),
            Rv32HartStep::Retired
        );
        assert_eq!(hart.register(4), 1);
    }

    #[test]
    fn machine_owned_write_ranges_can_invalidate_the_hart_reservation() {
        let mut hart = Rv32MachineHart::new(0x1000);
        hart.set_register_for_test(1, 0).unwrap();
        hart.set_register_for_test(2, 42).unwrap();

        assert_eq!(
            hart.execute_word_for_test(lr_w(3, 1, false, false)),
            Rv32HartStep::Retired
        );
        hart.invalidate_reservation(4, 4);
        assert_eq!(
            hart.execute_word_for_test(sc_w(4, 1, 2, false, false)),
            Rv32HartStep::Retired
        );
        assert_eq!(hart.register(4), 0);

        assert_eq!(
            hart.execute_word_for_test(lr_w(3, 1, false, false)),
            Rv32HartStep::Retired
        );
        hart.invalidate_reservation(0, 1);
        assert_eq!(
            hart.execute_word_for_test(sc_w(4, 1, 2, false, false)),
            Rv32HartStep::Retired
        );
        assert_eq!(hart.register(4), 1);
    }

    #[test]
    fn regular_and_fetch_failures_map_to_exact_machine_causes() {
        let cases = [
            (jal(0, 2), None, 0, 0x1002),
            (0, None, 2, 0),
            (lw(2, 1, 0), Some(3), 4, 3),
            (lw(2, 1, 0), Some(8), 5, 8),
            (sw(1, 2, 0), Some(3), 6, 3),
            (sw(1, 2, 0), Some(8), 7, 8),
            (lr_w(2, 1, false, false), Some(3), 4, 3),
            (lr_w(2, 1, false, false), Some(8), 5, 8),
            (sc_w(2, 1, 3, false, false), Some(3), 6, 3),
            (sc_w(2, 1, 3, false, false), Some(8), 7, 8),
            (amoswap_w(2, 1, 3, false, false), Some(3), 6, 3),
            (amoswap_w(2, 1, 3, false, false), Some(8), 7, 8),
        ];
        for (word, address, cause, value) in cases {
            let mut hart = Rv32MachineHart::new(0x1000);
            hart.write_csr_for_test(CSR_MTVEC, 0x2000).unwrap();
            if let Some(address) = address {
                hart.set_register_for_test(1, address).unwrap();
            }

            assert_eq!(hart.execute_word_for_test(word), Rv32HartStep::TrapTaken);
            assert_eq!(hart.read_csr(CSR_MEPC).unwrap(), 0x1000);
            assert_eq!(hart.read_csr(CSR_MCAUSE).unwrap(), cause);
            assert_eq!(hart.read_csr(CSR_MTVAL).unwrap(), value);
            assert_eq!(hart.retired_instructions(), 0);
        }

        let mut hart = Rv32MachineHart::new(0x1000);
        hart.write_csr_for_test(CSR_MTVEC, 0x2000).unwrap();
        assert_eq!(
            hart.take_instruction_access_fault(0x1000),
            Rv32HartStep::TrapTaken
        );
        assert_eq!(hart.read_csr(CSR_MCAUSE).unwrap(), 1);
        assert_eq!(hart.read_csr(CSR_MTVAL).unwrap(), 0x1000);
    }
}
