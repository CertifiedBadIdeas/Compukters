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

pub const MEMORY_SIZE: usize = 16 * 1024;
pub const DATA_BASE: u32 = 0x0000_2000;
pub const STACK_TOP: u32 = 0x0000_3ff0;
pub const MMIO_BASE: u32 = 0x1000_0000;
pub const PACKET_BYTES: usize = 16;
pub const RING_ENTRIES: usize = 8;
