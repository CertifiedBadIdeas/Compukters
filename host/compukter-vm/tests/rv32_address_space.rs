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
#[allow(dead_code)]
mod rv32_elf_support;

use compukter_vm::memory::MemoryBus;
use compukter_vm::rv32_machine::{Rv32AddressSpace, Rv32ElfLoader, Rv32PagePermissions};
use rv32_elf_support::{Elf32Builder, LoadSegment};

fn address_space() -> Rv32AddressSpace {
    let elf = Elf32Builder::new(0)
        .load(LoadSegment::rx(0, [0x13, 0, 0, 0]))
        .load(LoadSegment::rw_with_mem_size(0x1000, [0; 8], 0x1000))
        .finish();
    let image = Rv32ElfLoader::load(&elf, 0x3000).unwrap();
    Rv32AddressSpace::from_loaded_image(&image).unwrap()
}

#[test]
fn ram_accesses_enforce_every_touched_page_permission() {
    let mut space = address_space();

    assert_eq!(space.page_permissions(0), Rv32PagePermissions::READ_EXECUTE);
    assert_eq!(space.load_i32(0).unwrap(), 0x13);
    assert!(space
        .store_u8(0, 7)
        .unwrap_err()
        .to_string()
        .contains("write"));
    assert!(space
        .store_u16(0, 7)
        .unwrap_err()
        .to_string()
        .contains("write"));
    assert!(space
        .store_i32(0, 7)
        .unwrap_err()
        .to_string()
        .contains("write"));
    assert!(space
        .store_u64(0, 7)
        .unwrap_err()
        .to_string()
        .contains("write"));

    space.store_i32(0x1000, 7).unwrap();
    assert_eq!(space.load_i32(0x1000).unwrap(), 7);
    assert!(space
        .load_u8(0x2000)
        .unwrap_err()
        .to_string()
        .contains("read"));
    let cross_page_fault = space.load_u16(0x1fff).unwrap_err();
    assert!(cross_page_fault.to_string().contains("read"));
    assert_eq!(cross_page_fault.address(), Some(0x2000));
}

#[test]
fn address_space_does_not_expose_loader_initialization_as_guest_writes() {
    let mut space = address_space();

    assert_eq!(space.load_i32(0).unwrap(), 0x13);
    assert!(space.store_i32(0, 0).is_err());
    assert_eq!(space.load_i32(0).unwrap(), 0x13);
}
