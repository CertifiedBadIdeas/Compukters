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

pub(crate) mod abi;
mod backend;
pub(crate) mod block;
pub(crate) mod planner;

#[cfg(test)]
use backend::{CodeBlob, CodeRelocation};

#[cfg(test)]
mod tests {
    use super::{CodeBlob, CodeRelocation};

    #[test]
    fn code_blob_rejects_relocations_to_non_vm_symbols() {
        let blob = CodeBlob::new(
            vec![0xc3],
            vec![CodeRelocation::External {
                offset: 0,
                symbol: "libc_printf".into(),
            }],
        );

        assert!(blob.validate_relocations().is_err());
    }
}
