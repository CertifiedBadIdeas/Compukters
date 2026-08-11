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

mod address_space;
mod csr;
mod elf;
mod hart;
mod machine;
mod platform;

pub use address_space::{Rv32AddressSpace, Rv32AddressSpaceError};
pub use elf::{
    Rv32ElfError, Rv32ElfErrorKind, Rv32ElfLoader, Rv32LoadedImage, Rv32PagePermissions,
};
pub use machine::{
    Rv32ExecutionBackendConfig, Rv32Machine, Rv32MachineBuildError, Rv32MachineConfig,
    Rv32MachineExecutionError, Rv32MachineOutcome, Rv32TranslationLookupUnit, Rv32TranslationStats,
};
pub use platform::{CONTROL_BASE, DEBUG_BASE, STATUS_BOOTING, STATUS_HALTED, STATUS_PANIC};
