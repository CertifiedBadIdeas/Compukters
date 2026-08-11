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

use crate::rv32im::CsrOperation;
use thiserror::Error;

pub(super) const CSR_MSTATUS: u16 = 0x300;
pub(super) const CSR_MISA: u16 = 0x301;
pub(super) const CSR_MTVEC: u16 = 0x305;
pub(super) const CSR_MSCRATCH: u16 = 0x340;
pub(super) const CSR_MEPC: u16 = 0x341;
pub(super) const CSR_MCAUSE: u16 = 0x342;
pub(super) const CSR_MTVAL: u16 = 0x343;
pub(super) const CSR_MHARTID: u16 = 0xf14;

pub(super) const MSTATUS_MIE: u32 = 1 << 3;
pub(super) const MSTATUS_MPIE: u32 = 1 << 7;
pub(super) const MSTATUS_MPP_MACHINE: u32 = 3 << 11;
const MSTATUS_WRITABLE: u32 = MSTATUS_MIE | MSTATUS_MPIE;

const MISA_MXL_RV32: u32 = 1 << 30;
const MISA_A: u32 = 1 << 0;
const MISA_I: u32 = 1 << 8;
const MISA_M: u32 = 1 << 12;
const MISA_RV32IMA: u32 = MISA_MXL_RV32 | MISA_A | MISA_I | MISA_M;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Error)]
pub(super) enum Rv32CsrError {
    #[error("RV32 machine CSR {0:#05x} is absent")]
    Absent(u16),
    #[error("RV32 machine CSR {0:#05x} is read-only")]
    ReadOnly(u16),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct Rv32MachineCsrs {
    mstatus: u32,
    mtvec: u32,
    mscratch: u32,
    mepc: u32,
    mcause: u32,
    mtval: u32,
}

impl Rv32MachineCsrs {
    pub(super) fn new() -> Self {
        Self {
            mstatus: MSTATUS_MPP_MACHINE,
            mtvec: 0,
            mscratch: 0,
            mepc: 0,
            mcause: 0,
            mtval: 0,
        }
    }

    pub(super) fn read(&self, csr: u16) -> Result<u32, Rv32CsrError> {
        match csr {
            CSR_MSTATUS => Ok(self.mstatus),
            CSR_MISA => Ok(MISA_RV32IMA),
            CSR_MTVEC => Ok(self.mtvec),
            CSR_MSCRATCH => Ok(self.mscratch),
            CSR_MEPC => Ok(self.mepc),
            CSR_MCAUSE => Ok(self.mcause),
            CSR_MTVAL => Ok(self.mtval),
            CSR_MHARTID => Ok(0),
            _ => Err(Rv32CsrError::Absent(csr)),
        }
    }

    pub(super) fn access(
        &mut self,
        csr: u16,
        operation: CsrOperation,
        source: u32,
        write_requested: bool,
    ) -> Result<u32, Rv32CsrError> {
        let old = self.read(csr)?;
        if !write_requested {
            return Ok(old);
        }
        if matches!(csr, CSR_MISA | CSR_MHARTID) {
            return Err(Rv32CsrError::ReadOnly(csr));
        }
        let value = match operation {
            CsrOperation::Write => source,
            CsrOperation::Set => old | source,
            CsrOperation::Clear => old & !source,
        };
        self.write_mutable(csr, value)?;
        Ok(old)
    }

    #[cfg(test)]
    pub(super) fn write_software(&mut self, csr: u16, value: u32) -> Result<(), Rv32CsrError> {
        self.access(csr, CsrOperation::Write, value, true)
            .map(|_| ())
    }

    pub(super) fn enter_trap(&mut self, pc: u32, cause: u32, value: u32) -> u32 {
        let previous_mie = self.mstatus & MSTATUS_MIE != 0;
        self.mstatus = MSTATUS_MPP_MACHINE;
        if previous_mie {
            self.mstatus |= MSTATUS_MPIE;
        }
        self.mepc = pc & !3;
        self.mcause = cause;
        self.mtval = value;
        self.mtvec
    }

    pub(super) fn return_from_trap(&mut self) -> u32 {
        let previous_mpie = self.mstatus & MSTATUS_MPIE != 0;
        self.mstatus = MSTATUS_MPP_MACHINE | MSTATUS_MPIE;
        if previous_mpie {
            self.mstatus |= MSTATUS_MIE;
        }
        self.mepc
    }

    fn write_mutable(&mut self, csr: u16, value: u32) -> Result<(), Rv32CsrError> {
        match csr {
            CSR_MSTATUS => {
                self.mstatus = value & MSTATUS_WRITABLE | MSTATUS_MPP_MACHINE;
            }
            CSR_MTVEC => self.mtvec = value & !3,
            CSR_MSCRATCH => self.mscratch = value,
            CSR_MEPC => self.mepc = value & !3,
            CSR_MCAUSE => self.mcause = value,
            CSR_MTVAL => self.mtval = value,
            CSR_MISA | CSR_MHARTID => return Err(Rv32CsrError::ReadOnly(csr)),
            _ => return Err(Rv32CsrError::Absent(csr)),
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn csr_bank_reports_rv32ima_and_canonicalizes_warl_fields() {
        let mut csrs = Rv32MachineCsrs::new();
        assert_eq!(
            csrs.read(CSR_MISA).unwrap(),
            MISA_MXL_RV32 | MISA_A | MISA_I | MISA_M
        );
        assert_eq!(csrs.read(CSR_MHARTID).unwrap(), 0);
        assert_eq!(csrs.read(CSR_MSTATUS).unwrap(), MSTATUS_MPP_MACHINE);

        csrs.write_software(CSR_MTVEC, 0x1237).unwrap();
        assert_eq!(csrs.read(CSR_MTVEC).unwrap(), 0x1234);
        csrs.write_software(CSR_MEPC, 0x2003).unwrap();
        assert_eq!(csrs.read(CSR_MEPC).unwrap(), 0x2000);

        csrs.write_software(CSR_MSTATUS, u32::MAX).unwrap();
        assert_eq!(
            csrs.read(CSR_MSTATUS).unwrap(),
            MSTATUS_MIE | MSTATUS_MPIE | MSTATUS_MPP_MACHINE
        );
    }

    #[test]
    fn csr_bank_applies_write_set_and_clear_atomically() {
        let mut csrs = Rv32MachineCsrs::new();
        assert_eq!(
            csrs.access(CSR_MSCRATCH, CsrOperation::Write, 0b0101, true),
            Ok(0)
        );
        assert_eq!(
            csrs.access(CSR_MSCRATCH, CsrOperation::Set, 0b0010, true),
            Ok(0b0101)
        );
        assert_eq!(csrs.read(CSR_MSCRATCH).unwrap(), 0b0111);
        assert_eq!(
            csrs.access(CSR_MSCRATCH, CsrOperation::Clear, 0b0010, true),
            Ok(0b0111)
        );
        assert_eq!(csrs.read(CSR_MSCRATCH).unwrap(), 0b0101);
    }

    #[test]
    fn csr_bank_distinguishes_suppressed_and_requested_read_only_writes() {
        let mut csrs = Rv32MachineCsrs::new();
        assert_eq!(csrs.access(CSR_MHARTID, CsrOperation::Set, 0, false), Ok(0));
        assert_eq!(
            csrs.access(CSR_MHARTID, CsrOperation::Set, 0, true),
            Err(Rv32CsrError::ReadOnly(CSR_MHARTID))
        );
        assert_eq!(
            csrs.access(0x07ff, CsrOperation::Write, 1, true),
            Err(Rv32CsrError::Absent(0x07ff))
        );
        assert_eq!(csrs.read(CSR_MHARTID).unwrap(), 0);
    }
}
