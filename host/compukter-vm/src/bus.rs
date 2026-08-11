use crate::memory::{MachineMemory, MemoryBus, MemoryFault};
use std::any::Any;
use std::cell::Cell;

pub type MmioDeviceId = usize;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct MachineBusTrafficSnapshot {
    pub loads: u64,
    pub stores: u64,
    pub bytes_read: u64,
    pub bytes_written: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MmioDeviceTrafficSnapshot {
    pub device_id: MmioDeviceId,
    pub base: u32,
    pub size: u32,
    pub traffic: MachineBusTrafficSnapshot,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct MachineBusStatsSnapshot {
    pub ram: MachineBusTrafficSnapshot,
    pub mmio: MachineBusTrafficSnapshot,
    pub mmio_devices: Vec<MmioDeviceTrafficSnapshot>,
}

#[derive(Default)]
struct MachineBusTrafficCounters {
    loads: Cell<u64>,
    stores: Cell<u64>,
    bytes_read: Cell<u64>,
    bytes_written: Cell<u64>,
}

impl MachineBusTrafficCounters {
    fn record_load(&self, bytes: u64) {
        self.loads.set(self.loads.get().saturating_add(1));
        self.bytes_read
            .set(self.bytes_read.get().saturating_add(bytes));
    }

    fn record_store(&self, bytes: u64) {
        self.stores.set(self.stores.get().saturating_add(1));
        self.bytes_written
            .set(self.bytes_written.get().saturating_add(bytes));
    }

    fn snapshot(&self) -> MachineBusTrafficSnapshot {
        MachineBusTrafficSnapshot {
            loads: self.loads.get(),
            stores: self.stores.get(),
            bytes_read: self.bytes_read.get(),
            bytes_written: self.bytes_written.get(),
        }
    }
}

pub trait MmioDevice: Any {
    fn size(&self) -> u32;

    fn take_yield_signal(&mut self) -> bool {
        false
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault>;

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault>;

    fn store_i32_with_memory(
        &mut self,
        offset: u32,
        value: i32,
        _memory: &mut MachineMemory,
    ) -> Result<(), MemoryFault> {
        self.store_i32(offset, value)
    }

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
    traffic: MachineBusTrafficCounters,
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

    fn offset_for_u16(&self, address: u32) -> Option<u32> {
        let end = self.end().ok()?;
        let access_end = address.checked_add(2)?;
        if address >= self.base && access_end <= end {
            Some(address - self.base)
        } else {
            None
        }
    }

    fn offset_for_u64(&self, address: u32) -> Option<u32> {
        let end = self.end().ok()?;
        let access_end = address.checked_add(8)?;
        if address >= self.base && access_end <= end {
            Some(address - self.base)
        } else {
            None
        }
    }
}

pub struct MachineBus {
    memory: MachineMemory,
    regions: Vec<MmioRegion>,
    ram_traffic: MachineBusTrafficCounters,
    mmio_traffic: MachineBusTrafficCounters,
}

impl MachineBus {
    pub fn new(memory_size: usize) -> Result<Self, MemoryFault> {
        Ok(Self {
            memory: MachineMemory::zeroed(memory_size)?,
            regions: Vec::new(),
            ram_traffic: MachineBusTrafficCounters::default(),
            mmio_traffic: MachineBusTrafficCounters::default(),
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
        let region = MmioRegion {
            base,
            size,
            device,
            traffic: MachineBusTrafficCounters::default(),
        };
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

    pub fn stats_snapshot(&self) -> MachineBusStatsSnapshot {
        MachineBusStatsSnapshot {
            ram: self.ram_traffic.snapshot(),
            mmio: self.mmio_traffic.snapshot(),
            mmio_devices: self
                .regions
                .iter()
                .enumerate()
                .map(|(device_id, region)| MmioDeviceTrafficSnapshot {
                    device_id,
                    base: region.base,
                    size: region.size,
                    traffic: region.traffic.snapshot(),
                })
                .collect(),
        }
    }

    /// Returns aggregate RAM/MMIO counters without allocating per-device detail.
    pub fn aggregate_traffic_snapshot(
        &self,
    ) -> (MachineBusTrafficSnapshot, MachineBusTrafficSnapshot) {
        (self.ram_traffic.snapshot(), self.mmio_traffic.snapshot())
    }
}

impl MemoryBus for MachineBus {
    fn len(&self) -> usize {
        self.memory.len()
    }

    fn take_yield_signal(&mut self) -> bool {
        self.regions
            .iter_mut()
            .any(|region| region.device.take_yield_signal())
    }

    fn load_i32(&self, address: u32) -> Result<i32, MemoryFault> {
        for region in &self.regions {
            if let Some(offset) = region.offset_for_i32(address) {
                let value = region.device.load_i32(offset)?;
                region.traffic.record_load(4);
                self.mmio_traffic.record_load(4);
                return Ok(value);
            }
        }
        let value = self.memory.load_i32(address)?;
        self.ram_traffic.record_load(4);
        Ok(value)
    }

    fn store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault> {
        for region in &mut self.regions {
            if let Some(offset) = region.offset_for_i32(address) {
                region
                    .device
                    .store_i32_with_memory(offset, value, &mut self.memory)?;
                region.traffic.record_store(4);
                self.mmio_traffic.record_store(4);
                return Ok(());
            }
        }
        self.memory.store_i32(address, value)?;
        self.ram_traffic.record_store(4);
        Ok(())
    }

    fn load_u8(&self, address: u32) -> Result<u8, MemoryFault> {
        for region in &self.regions {
            if let Some(offset) = region.offset_for_u8(address) {
                let value = region.device.load_u8(offset)?;
                region.traffic.record_load(1);
                self.mmio_traffic.record_load(1);
                return Ok(value);
            }
        }
        let value = self.memory.load_u8(address)?;
        self.ram_traffic.record_load(1);
        Ok(value)
    }

    fn store_u8(&mut self, address: u32, value: u8) -> Result<(), MemoryFault> {
        for region in &mut self.regions {
            if let Some(offset) = region.offset_for_u8(address) {
                region.device.store_u8(offset, value)?;
                region.traffic.record_store(1);
                self.mmio_traffic.record_store(1);
                return Ok(());
            }
        }
        self.memory.store_u8(address, value)?;
        self.ram_traffic.record_store(1);
        Ok(())
    }

    fn load_u16(&self, address: u32) -> Result<u16, MemoryFault> {
        for region in &self.regions {
            if let Some(offset) = region.offset_for_u16(address) {
                let value = u16::from_le_bytes([
                    region.device.load_u8(offset)?,
                    region.device.load_u8(offset + 1)?,
                ]);
                region.traffic.record_load(2);
                self.mmio_traffic.record_load(2);
                return Ok(value);
            }
        }
        let value = self.memory.load_u16(address)?;
        self.ram_traffic.record_load(2);
        Ok(value)
    }

    fn store_u16(&mut self, address: u32, value: u16) -> Result<(), MemoryFault> {
        for region in &mut self.regions {
            if let Some(offset) = region.offset_for_u16(address) {
                let [lo, hi] = value.to_le_bytes();
                region.device.store_u8(offset, lo)?;
                region.device.store_u8(offset + 1, hi)?;
                region.traffic.record_store(2);
                self.mmio_traffic.record_store(2);
                return Ok(());
            }
        }
        self.memory.store_u16(address, value)?;
        self.ram_traffic.record_store(2);
        Ok(())
    }

    fn load_u64(&self, address: u32) -> Result<u64, MemoryFault> {
        for region in &self.regions {
            if let Some(offset) = region.offset_for_u64(address) {
                let mut bytes = [0_u8; 8];
                for (index, byte) in bytes.iter_mut().enumerate() {
                    *byte = region.device.load_u8(offset + index as u32)?;
                }
                region.traffic.record_load(8);
                self.mmio_traffic.record_load(8);
                return Ok(u64::from_le_bytes(bytes));
            }
        }
        let value = self.memory.load_u64(address)?;
        self.ram_traffic.record_load(8);
        Ok(value)
    }

    fn store_u64(&mut self, address: u32, value: u64) -> Result<(), MemoryFault> {
        for region in &mut self.regions {
            if let Some(offset) = region.offset_for_u64(address) {
                for (index, byte) in value.to_le_bytes().into_iter().enumerate() {
                    region.device.store_u8(offset + index as u32, byte)?;
                }
                region.traffic.record_store(8);
                self.mmio_traffic.record_store(8);
                return Ok(());
            }
        }
        self.memory.store_u64(address, value)?;
        self.ram_traffic.record_store(8);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use crate::bus::{MachineBus, MmioDevice};
    use crate::memory::MemoryFault;
    use std::fs;
    use std::path::Path;

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

    struct ByteWindowDevice {
        bytes: Vec<u8>,
    }

    impl MmioDevice for ByteWindowDevice {
        fn size(&self) -> u32 {
            self.bytes.len() as u32
        }

        fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
            let offset = offset as usize;
            let bytes = self
                .bytes
                .get(offset..offset + 4)
                .ok_or_else(|| MemoryFault::new(format!("invalid i32 offset {offset}")))?;
            Ok(i32::from_le_bytes(
                bytes.try_into().expect("slice has length 4"),
            ))
        }

        fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
            let offset = offset as usize;
            let bytes = self
                .bytes
                .get_mut(offset..offset + 4)
                .ok_or_else(|| MemoryFault::new(format!("invalid i32 offset {offset}")))?;
            bytes.copy_from_slice(&value.to_le_bytes());
            Ok(())
        }

        fn load_u8(&self, offset: u32) -> Result<u8, MemoryFault> {
            self.bytes
                .get(offset as usize)
                .copied()
                .ok_or_else(|| MemoryFault::new(format!("invalid u8 offset {offset}")))
        }

        fn store_u8(&mut self, offset: u32, value: u8) -> Result<(), MemoryFault> {
            let byte = self
                .bytes
                .get_mut(offset as usize)
                .ok_or_else(|| MemoryFault::new(format!("invalid u8 offset {offset}")))?;
            *byte = value;
            Ok(())
        }
    }

    #[test]
    fn machine_bus_overrides_word_access_for_fetch_path() {
        let source_path = Path::new(env!("CARGO_MANIFEST_DIR")).join("src/bus.rs");
        let source = fs::read_to_string(source_path).unwrap();
        let impl_start = source.find("impl MemoryBus for MachineBus").unwrap();
        let impl_end = source[impl_start..].find("\n}\n\n#[cfg(test)]").unwrap();
        let impl_source = &source[impl_start..impl_start + impl_end];

        assert!(impl_source.contains("fn load_u16("));
        assert!(impl_source.contains("fn store_u16("));
        assert!(impl_source.contains("fn load_u64("));
        assert!(impl_source.contains("fn store_u64("));
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
    fn machine_bus_stats_snapshot_counts_ram_and_mmio_traffic() {
        let mut bus = MachineBus::new(16).unwrap();
        let device_id = bus
            .map_mmio(
                8,
                Box::new(RegisterDevice {
                    value: 7,
                    read_only: false,
                }),
            )
            .unwrap();

        bus.store_u8(0, 1).unwrap();
        assert_eq!(bus.load_u16(0).unwrap(), 1);
        bus.store_i32(8, 11).unwrap();
        assert_eq!(bus.load_i32(8).unwrap(), 11);

        let stats = bus.stats_snapshot();

        assert_eq!(stats.ram.loads, 1);
        assert_eq!(stats.ram.stores, 1);
        assert_eq!(stats.ram.bytes_read, 2);
        assert_eq!(stats.ram.bytes_written, 1);
        assert_eq!(stats.mmio.loads, 1);
        assert_eq!(stats.mmio.stores, 1);
        assert_eq!(stats.mmio.bytes_read, 4);
        assert_eq!(stats.mmio.bytes_written, 4);
        assert_eq!(stats.mmio_devices.len(), 1);
        assert_eq!(stats.mmio_devices[0].device_id, device_id);
        assert_eq!(stats.mmio_devices[0].base, 8);
        assert_eq!(stats.mmio_devices[0].size, 4);
        assert_eq!(stats.mmio_devices[0].traffic, stats.mmio);
    }

    #[test]
    fn machine_bus_stats_snapshot_counts_u64_as_single_bus_access() {
        let mut bus = MachineBus::new(32).unwrap();
        bus.map_mmio(
            16,
            Box::new(ByteWindowDevice {
                bytes: vec![0_u8; 8],
            }),
        )
        .unwrap();

        bus.store_u64(0, 0x0102_0304_0506_0708).unwrap();
        assert_eq!(bus.load_u64(0).unwrap(), 0x0102_0304_0506_0708);
        bus.store_u64(16, 0x1112_1314_1516_1718).unwrap();
        assert_eq!(bus.load_u64(16).unwrap(), 0x1112_1314_1516_1718);

        let stats = bus.stats_snapshot();

        assert_eq!(stats.ram.loads, 1);
        assert_eq!(stats.ram.stores, 1);
        assert_eq!(stats.ram.bytes_read, 8);
        assert_eq!(stats.ram.bytes_written, 8);
        assert_eq!(stats.mmio.loads, 1);
        assert_eq!(stats.mmio.stores, 1);
        assert_eq!(stats.mmio.bytes_read, 8);
        assert_eq!(stats.mmio.bytes_written, 8);
        assert_eq!(stats.mmio_devices[0].traffic, stats.mmio);
    }

    #[test]
    fn machine_bus_routes_word_load_to_overlapping_mmio_before_ram() {
        let mut bus = MachineBus::new(16).unwrap();
        bus.memory_mut().store_u16(0, 0x1122).unwrap();
        bus.map_mmio(
            0,
            Box::new(ByteWindowDevice {
                bytes: vec![0x78, 0x56, 0x34, 0x12],
            }),
        )
        .unwrap();

        assert_eq!(bus.load_u16(0).unwrap(), 0x5678);
    }

    #[test]
    fn machine_bus_routes_word_store_to_overlapping_mmio_before_ram() {
        let mut bus = MachineBus::new(16).unwrap();
        bus.memory_mut().store_u16(0, 0x1122).unwrap();
        let device_id = bus
            .map_mmio(
                0,
                Box::new(ByteWindowDevice {
                    bytes: vec![0x78, 0x56, 0x34, 0x12],
                }),
            )
            .unwrap();

        bus.store_u16(0, 0xa1b2).unwrap();

        assert_eq!(bus.memory().load_u16(0).unwrap(), 0x1122);
        let device = bus.device::<ByteWindowDevice>(device_id).unwrap();
        assert_eq!(device.bytes, vec![0xb2, 0xa1, 0x34, 0x12]);
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
