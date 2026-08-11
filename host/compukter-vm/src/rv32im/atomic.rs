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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct MemoryOrdering {
    pub(crate) acquire: bool,
    pub(crate) release: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum AtomicOp {
    Swap,
    Add,
    Xor,
    And,
    Or,
    Min,
    Max,
    MinU,
    MaxU,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Rv32Reservation {
    address: u32,
}

impl Rv32Reservation {
    pub(crate) fn new(address: u32) -> Self {
        Self { address }
    }

    pub(crate) fn matches(self, address: u32) -> bool {
        self.address == address
    }

    pub(crate) fn intersects(self, address: u32, size: u32) -> bool {
        let reservation_start = u64::from(self.address);
        let reservation_end = reservation_start + 4;
        let write_start = u64::from(address);
        let write_end = write_start + u64::from(size);
        write_start < reservation_end && reservation_start < write_end
    }
}

pub(crate) fn apply_atomic(operation: AtomicOp, old: u32, operand: u32) -> u32 {
    match operation {
        AtomicOp::Swap => operand,
        AtomicOp::Add => old.wrapping_add(operand),
        AtomicOp::Xor => old ^ operand,
        AtomicOp::And => old & operand,
        AtomicOp::Or => old | operand,
        AtomicOp::Min => {
            if (old as i32) < (operand as i32) {
                old
            } else {
                operand
            }
        }
        AtomicOp::Max => {
            if (old as i32) > (operand as i32) {
                old
            } else {
                operand
            }
        }
        AtomicOp::MinU => old.min(operand),
        AtomicOp::MaxU => old.max(operand),
    }
}
