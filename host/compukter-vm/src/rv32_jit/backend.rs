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

#[derive(Debug, Clone, PartialEq, Eq)]
#[allow(
    dead_code,
    reason = "the backend-neutral JIT contract is introduced before its planner and arena consumers"
)]
pub(crate) enum CodeRelocation {
    External { offset: u32, symbol: String },
}

#[derive(Debug, Clone, PartialEq, Eq)]
#[allow(
    dead_code,
    reason = "the backend-neutral JIT contract is introduced before its planner and arena consumers"
)]
pub(crate) struct CodeBlob {
    bytes: Vec<u8>,
    relocations: Vec<CodeRelocation>,
}

impl CodeBlob {
    #[allow(
        dead_code,
        reason = "the backend-neutral JIT contract is introduced before its planner and arena consumers"
    )]
    pub(crate) fn new(bytes: Vec<u8>, relocations: Vec<CodeRelocation>) -> Self {
        Self { bytes, relocations }
    }

    #[allow(
        dead_code,
        reason = "the backend-neutral JIT contract is introduced before its planner and arena consumers"
    )]
    pub(crate) fn validate_relocations(&self) -> Result<(), String> {
        let _ = &self.bytes;
        for relocation in &self.relocations {
            let CodeRelocation::External { symbol, .. } = relocation;
            return Err(format!(
                "RV32 JIT relocation targets non-VM symbol {symbol}"
            ));
        }
        Ok(())
    }
}
