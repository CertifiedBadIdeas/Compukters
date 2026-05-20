use crate::low_machine::{MachineMemory, MemoryBus, MemoryFault};
use std::any::Any;

pub type MmioDeviceId = usize;

pub trait MmioDevice: Any {
    fn size(&self) -> u32;

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault>;

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault>;

    fn load_u8(&self, offset: u32) -> Result<u8, MemoryFault> {
        Ok(self.load_i32(offset)?.to_le_bytes()[0])
    }

    fn store_u8(&mut self, offset: u32, value: u8) -> Result<(), MemoryFault> {
        self.store_i32(offset, i32::from(value))
    }
}

struct MmioRegion {
    base: u32,
    size: u32,
    device: Box<dyn MmioDevice>,
}

impl MmioRegion {
    fn end(&self) -> Result<u32, MemoryFault> {
        self.base.checked_add(self.size).ok_or_else(|| {
            MemoryFault::new(format!(
                "mmio region {:#010x} with size {} overflows address space",
                self.base, self.size,
            ))
        })
    }

    fn offset_for_i32(&self, address: u32) -> Option<u32> {
        let end = self.end().ok()?;
        let access_end = address.checked_add(4)?;
        if address >= self.base && access_end <= end {
            Some(address - self.base)
        } else {
            None
        }
    }

    fn offset_for_u8(&self, address: u32) -> Option<u32> {
        let end = self.end().ok()?;
        if address >= self.base && address < end {
            Some(address - self.base)
        } else {
            None
        }
    }
}

pub struct MachineBus {
    memory: MachineMemory,
    regions: Vec<MmioRegion>,
}

impl MachineBus {
    pub fn new(memory_size: usize) -> Result<Self, MemoryFault> {
        Ok(Self {
            memory: MachineMemory::zeroed(memory_size)?,
            regions: Vec::new(),
        })
    }

    pub fn memory(&self) -> &MachineMemory {
        &self.memory
    }

    pub fn memory_mut(&mut self) -> &mut MachineMemory {
        &mut self.memory
    }

    pub fn map_mmio(
        &mut self,
        base: u32,
        device: Box<dyn MmioDevice>,
    ) -> Result<MmioDeviceId, MemoryFault> {
        let size = device.size();
        if size == 0 {
            return Err(MemoryFault::new(
                "mmio device size must be positive".to_string(),
            ));
        }
        let region = MmioRegion { base, size, device };
        let region_end = region.end()?;
        for existing in &self.regions {
            let existing_end = existing.end()?;
            if base < existing_end && existing.base < region_end {
                return Err(MemoryFault::new(format!(
                    "mmio region {base:#010x}..{region_end:#010x} overlaps existing region {:#010x}..{existing_end:#010x}",
                    existing.base,
                )));
            }
        }
        let device_id = self.regions.len();
        self.regions.push(region);
        Ok(device_id)
    }

    pub fn device<T: MmioDevice>(&self, id: MmioDeviceId) -> Option<&T> {
        self.regions
            .get(id)
            .and_then(|region| (&*region.device as &dyn Any).downcast_ref::<T>())
    }

    pub fn device_mut<T: MmioDevice>(&mut self, id: MmioDeviceId) -> Option<&mut T> {
        self.regions
            .get_mut(id)
            .and_then(|region| (&mut *region.device as &mut dyn Any).downcast_mut::<T>())
    }

    pub fn mmio_region_bounds(&self, id: MmioDeviceId) -> Option<(u32, u32)> {
        self.regions
            .get(id)
            .map(|region| (region.base, region.size))
    }

    pub fn load_i32(&self, address: u32) -> Result<i32, MemoryFault> {
        <Self as MemoryBus>::load_i32(self, address)
    }

    pub fn store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault> {
        <Self as MemoryBus>::store_i32(self, address, value)
    }

    pub fn load_u8(&self, address: u32) -> Result<u8, MemoryFault> {
        <Self as MemoryBus>::load_u8(self, address)
    }

    pub fn store_u8(&mut self, address: u32, value: u8) -> Result<(), MemoryFault> {
        <Self as MemoryBus>::store_u8(self, address, value)
    }

    pub fn load_u16(&self, address: u32) -> Result<u16, MemoryFault> {
        <Self as MemoryBus>::load_u16(self, address)
    }

    pub fn store_u16(&mut self, address: u32, value: u16) -> Result<(), MemoryFault> {
        <Self as MemoryBus>::store_u16(self, address, value)
    }

    pub fn load_u64(&self, address: u32) -> Result<u64, MemoryFault> {
        <Self as MemoryBus>::load_u64(self, address)
    }

    pub fn store_u64(&mut self, address: u32, value: u64) -> Result<(), MemoryFault> {
        <Self as MemoryBus>::store_u64(self, address, value)
    }
}

impl MemoryBus for MachineBus {
    fn len(&self) -> usize {
        self.memory.len()
    }

    fn load_i32(&self, address: u32) -> Result<i32, MemoryFault> {
        for region in &self.regions {
            if let Some(offset) = region.offset_for_i32(address) {
                return region.device.load_i32(offset);
            }
        }
        self.memory.load_i32(address)
    }

    fn store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault> {
        for region in &mut self.regions {
            if let Some(offset) = region.offset_for_i32(address) {
                return region.device.store_i32(offset, value);
            }
        }
        self.memory.store_i32(address, value)
    }

    fn load_u8(&self, address: u32) -> Result<u8, MemoryFault> {
        for region in &self.regions {
            if let Some(offset) = region.offset_for_u8(address) {
                return region.device.load_u8(offset);
            }
        }
        self.memory.load_u8(address)
    }

    fn store_u8(&mut self, address: u32, value: u8) -> Result<(), MemoryFault> {
        for region in &mut self.regions {
            if let Some(offset) = region.offset_for_u8(address) {
                return region.device.store_u8(offset, value);
            }
        }
        self.memory.store_u8(address, value)
    }
}

#[cfg(test)]
mod tests {
    use crate::low_bus::{MachineBus, MmioDevice};
    use crate::low_machine::MemoryFault;

    struct RegisterDevice {
        value: i32,
        read_only: bool,
    }

    impl MmioDevice for RegisterDevice {
        fn size(&self) -> u32 {
            4
        }

        fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
            assert_eq!(offset, 0);
            Ok(self.value)
        }

        fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
            assert_eq!(offset, 0);
            if self.read_only {
                return Err(MemoryFault::new("register is read-only".to_string()));
            }
            self.value = value;
            Ok(())
        }
    }

    #[test]
    fn machine_bus_routes_regular_addresses_to_ram() {
        let mut bus = MachineBus::new(16).unwrap();

        bus.store_i32(4, 0x11223344).unwrap();

        assert_eq!(bus.load_i32(4).unwrap(), 0x11223344);
        assert_eq!(&bus.memory().bytes()[4..8], &[0x44, 0x33, 0x22, 0x11]);
    }

    #[test]
    fn machine_bus_routes_low_ram_addresses_even_when_high_mmio_is_mapped() {
        let mut bus = MachineBus::new(16).unwrap();
        bus.map_mmio(
            0x1000_0000,
            Box::new(RegisterDevice {
                value: 7,
                read_only: false,
            }),
        )
        .unwrap();

        bus.store_i32(4, 0x55667788).unwrap();

        assert_eq!(bus.load_i32(4).unwrap(), 0x55667788);
    }

    #[test]
    fn machine_bus_routes_mmio_addresses_to_registered_devices() {
        let mut bus = MachineBus::new(16).unwrap();
        let device_id = bus
            .map_mmio(
                0x1000_0000,
                Box::new(RegisterDevice {
                    value: 7,
                    read_only: false,
                }),
            )
            .unwrap();

        assert_eq!(bus.load_i32(0x1000_0000).unwrap(), 7);
        bus.store_i32(0x1000_0000, 9).unwrap();

        let device = bus.device::<RegisterDevice>(device_id).unwrap();
        assert_eq!(device.value, 9);
    }

    #[test]
    fn machine_bus_preserves_device_faults() {
        let mut bus = MachineBus::new(16).unwrap();
        bus.map_mmio(
            0x1000_0100,
            Box::new(RegisterDevice {
                value: 11,
                read_only: true,
            }),
        )
        .unwrap();

        let error = bus.store_i32(0x1000_0100, 3).unwrap_err();

        assert_eq!(error.to_string(), "register is read-only");
    }
}
