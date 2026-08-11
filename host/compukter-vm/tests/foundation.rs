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

use compukter_vm::bus::MachineBus;

#[test]
fn neutral_bus_routes_little_endian_ram_without_an_isa() {
    let mut bus = MachineBus::new(16).unwrap();

    bus.store_i32(4, 0x1122_3344).unwrap();

    assert_eq!(bus.load_i32(4).unwrap(), 0x1122_3344);
    assert_eq!(bus.load_u8(4).unwrap(), 0x44);
}
