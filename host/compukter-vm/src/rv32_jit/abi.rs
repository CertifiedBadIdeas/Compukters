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

use std::marker::PhantomData;

/// The contiguous RAM region that a compiled block may access directly.
///
/// Addresses outside `len`, including all MMIO, must leave the compiled block
/// before they are accessed. Page permission bytes use the ELF R/W/X bit layout.
#[repr(C)]
#[allow(
    dead_code,
    reason = "the RV32 JIT dispatcher consumes this ABI in a later slice"
)]
pub(crate) struct JitRamView<'memory> {
    pub(crate) base: *mut u8,
    pub(crate) len: usize,
    pub(crate) page_permissions: *const u8,
    pub(crate) page_count: usize,
    _borrow: PhantomData<&'memory mut ()>,
}

#[allow(
    dead_code,
    reason = "the RV32 JIT dispatcher consumes this ABI in a later slice"
)]
impl<'memory> JitRamView<'memory> {
    pub(crate) fn new(
        base: *mut u8,
        len: usize,
        page_permissions: *const u8,
        page_count: usize,
    ) -> Self {
        Self {
            base,
            len,
            page_permissions,
            page_count,
            _borrow: PhantomData,
        }
    }

    pub(crate) fn base(&self) -> *mut u8 {
        self.base
    }

    pub(crate) fn len(&self) -> usize {
        self.len
    }

    pub(crate) fn page_permission_bits(&self, page: usize) -> u8 {
        if page >= self.page_count {
            return 0;
        }
        // SAFETY: Rv32AddressSpace constructs this view from its fixed page
        // permission vector and ties the view lifetime to its mutable borrow.
        unsafe { *self.page_permissions.add(page) }
    }
}

#[cfg(test)]
mod tests {
    use crate::rv32im::Rv32ArchitecturalState;

    #[test]
    fn architectural_state_preserves_x0_and_register_values() {
        let mut state = Rv32ArchitecturalState::new(0x1000);
        state.set_register(0, 0xfeed_beef);
        state.set_register(7, 42);

        assert_eq!(state.pc(), 0x1000);
        assert_eq!(state.register(0), 0);
        assert_eq!(state.register(7), 42);
    }
}
