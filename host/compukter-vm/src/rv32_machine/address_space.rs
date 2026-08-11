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

use super::{Rv32ElfLoader, Rv32LoadedImage, Rv32PagePermissions};
use crate::bus::MachineBus;
use crate::memory::{MemoryBus, MemoryFault};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum Rv32AddressSpaceError {
    #[error("RV32 address-space RAM construction failed: {0}")]
    Ram(#[from] MemoryFault),
    #[error("RV32 address-space has {actual} permission pages, expected {expected}")]
    PermissionPageCount { expected: usize, actual: usize },
}

pub struct Rv32AddressSpace {
    bus: MachineBus,
    page_permissions: Vec<Rv32PagePermissions>,
}

impl Rv32AddressSpace {
    pub fn from_loaded_image(image: &Rv32LoadedImage) -> Result<Self, Rv32AddressSpaceError> {
        let mut bus = MachineBus::new(image.ram().len())?;
        for (address, byte) in image.ram().iter().copied().enumerate() {
            bus.memory_mut().store_u8(address as u32, byte)?;
        }
        Self::from_parts(bus, image.page_table().to_vec())
    }

    pub fn page_permissions(&self, address: u32) -> Rv32PagePermissions {
        self.page_permissions
            .get(address as usize / Rv32ElfLoader::PAGE_SIZE as usize)
            .copied()
            .unwrap_or(Rv32PagePermissions::NONE)
    }

    pub(super) fn from_parts(
        bus: MachineBus,
        page_permissions: Vec<Rv32PagePermissions>,
    ) -> Result<Self, Rv32AddressSpaceError> {
        let expected = bus.len().div_ceil(Rv32ElfLoader::PAGE_SIZE as usize);
        if page_permissions.len() != expected {
            return Err(Rv32AddressSpaceError::PermissionPageCount {
                expected,
                actual: page_permissions.len(),
            });
        }
        Ok(Self {
            bus,
            page_permissions,
        })
    }

    pub(super) fn bus(&self) -> &MachineBus {
        &self.bus
    }

    fn require(&self, address: u32, size: usize, access: Access) -> Result<bool, MemoryFault> {
        let start = address as usize;
        let end = start.checked_add(size).ok_or_else(|| {
            MemoryFault::at(
                address,
                format!(
                    "RV32 {} access at {address:#010x} with size {size} overflows",
                    access.name(),
                ),
            )
        })?;
        let ram_size = self.bus.len();
        if start >= ram_size {
            return Ok(false);
        }
        if end > ram_size {
            return Err(MemoryFault::at(ram_size as u32, format!(
                "RV32 {} access {start:#010x}..{end:#010x} crosses the RAM boundary {ram_size:#010x}",
                access.name(),
            )));
        }
        let page_size = Rv32ElfLoader::PAGE_SIZE as usize;
        let first_page = start / page_size;
        let last_page = (end - 1) / page_size;
        for page in first_page..=last_page {
            let permissions = self.page_permissions[page];
            if !access.allowed(permissions) {
                let denied_address = start.max(page * page_size) as u32;
                return Err(MemoryFault::at(denied_address, format!(
                    "RV32 {} access {start:#010x}..{end:#010x} requires permission on page {page}, found {permissions:?}",
                    access.name(),
                )));
            }
        }
        Ok(true)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Access {
    Read,
    Write,
}

impl Access {
    fn name(self) -> &'static str {
        match self {
            Self::Read => "read",
            Self::Write => "write",
        }
    }

    fn allowed(self, permissions: Rv32PagePermissions) -> bool {
        match self {
            Self::Read => permissions.readable(),
            Self::Write => permissions.writable(),
        }
    }
}

impl MemoryBus for Rv32AddressSpace {
    fn len(&self) -> usize {
        self.bus.len()
    }

    fn take_yield_signal(&mut self) -> bool {
        self.bus.take_yield_signal()
    }

    fn load_i32(&self, address: u32) -> Result<i32, MemoryFault> {
        self.require(address, 4, Access::Read)?;
        self.bus.load_i32(address)
    }

    fn store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault> {
        self.require(address, 4, Access::Write)?;
        self.bus.store_i32(address, value)
    }

    fn load_u8(&self, address: u32) -> Result<u8, MemoryFault> {
        self.require(address, 1, Access::Read)?;
        self.bus.load_u8(address)
    }

    fn store_u8(&mut self, address: u32, value: u8) -> Result<(), MemoryFault> {
        self.require(address, 1, Access::Write)?;
        self.bus.store_u8(address, value)
    }

    fn load_u16(&self, address: u32) -> Result<u16, MemoryFault> {
        if !self.require(address, 2, Access::Read)? {
            return Err(MemoryFault::at(
                address,
                format!("RV32 halfword read at {address:#010x} is unsupported for MMIO"),
            ));
        }
        self.bus.load_u16(address)
    }

    fn store_u16(&mut self, address: u32, value: u16) -> Result<(), MemoryFault> {
        if !self.require(address, 2, Access::Write)? {
            return Err(MemoryFault::at(
                address,
                format!("RV32 halfword write at {address:#010x} is unsupported for MMIO"),
            ));
        }
        self.bus.store_u16(address, value)
    }

    fn load_u64(&self, address: u32) -> Result<u64, MemoryFault> {
        self.require(address, 8, Access::Read)?;
        self.bus.load_u64(address)
    }

    fn store_u64(&mut self, address: u32, value: u64) -> Result<(), MemoryFault> {
        self.require(address, 8, Access::Write)?;
        self.bus.store_u64(address, value)
    }
}

#[cfg(test)]
mod tests {
    use super::Rv32AddressSpace;
    use crate::bus::{MachineBus, MmioDevice};
    use crate::memory::{MemoryBus, MemoryFault};
    use crate::rv32_machine::{Rv32ElfLoader, Rv32PagePermissions};

    struct RegisterDevice(i32);

    impl MmioDevice for RegisterDevice {
        fn size(&self) -> u32 {
            4
        }

        fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
            if offset == 0 {
                Ok(self.0)
            } else {
                Err(MemoryFault::new("invalid test offset".to_string()))
            }
        }

        fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
            if offset == 0 {
                self.0 = value;
                Ok(())
            } else {
                Err(MemoryFault::new("invalid test offset".to_string()))
            }
        }
    }

    #[test]
    fn non_ram_accesses_delegate_only_to_mapped_mmio() {
        let mut space = Rv32AddressSpace {
            bus: MachineBus::new(0x1000).unwrap(),
            page_permissions: vec![Rv32PagePermissions::READ_EXECUTE],
        };
        space
            .bus
            .map_mmio(0x1000, Box::new(RegisterDevice(0)))
            .unwrap();

        space.store_i32(0x1000, 17).unwrap();
        assert_eq!(space.load_i32(0x1000).unwrap(), 17);
        assert!(space.load_i32(0x1004).is_err());
    }

    #[test]
    fn page_size_matches_the_elf_loader_contract() {
        assert_eq!(Rv32ElfLoader::PAGE_SIZE, 4096);
    }
}
