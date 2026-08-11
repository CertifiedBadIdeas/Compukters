use std::error::Error;
use std::fmt::{Display, Formatter};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoryFault {
    message: String,
    address: Option<u32>,
}

impl MemoryFault {
    pub fn new(message: String) -> Self {
        Self {
            message,
            address: None,
        }
    }

    pub fn at(address: u32, message: String) -> Self {
        Self {
            message,
            address: Some(address),
        }
    }

    pub fn address(&self) -> Option<u32> {
        self.address
    }
}

impl Display for MemoryFault {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl Error for MemoryFault {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MachineMemory {
    bytes: Vec<u8>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AtomicWordAccess {
    Load,
    Store,
    ReadModifyWrite,
}

pub trait MemoryBus {
    fn len(&self) -> usize;

    fn take_yield_signal(&mut self) -> bool {
        false
    }

    fn load_i32(&self, address: u32) -> Result<i32, MemoryFault>;

    fn store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault>;

    fn validate_atomic_i32(
        &self,
        address: u32,
        access: AtomicWordAccess,
    ) -> Result<(), MemoryFault>;

    fn atomic_load_i32(&self, address: u32) -> Result<i32, MemoryFault>;

    fn atomic_store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault>;

    fn atomic_update_i32(
        &mut self,
        address: u32,
        update: &mut dyn FnMut(i32) -> i32,
    ) -> Result<i32, MemoryFault>;

    fn load_u8(&self, address: u32) -> Result<u8, MemoryFault>;

    fn store_u8(&mut self, address: u32, value: u8) -> Result<(), MemoryFault>;

    fn load_u16(&self, address: u32) -> Result<u16, MemoryFault> {
        let next = address.checked_add(1).ok_or_else(|| {
            MemoryFault::at(
                address,
                format!("memory access starts at {address} and overflows u32",),
            )
        })?;
        Ok(u16::from_le_bytes([
            self.load_u8(address)?,
            self.load_u8(next)?,
        ]))
    }

    fn store_u16(&mut self, address: u32, value: u16) -> Result<(), MemoryFault> {
        let next = address.checked_add(1).ok_or_else(|| {
            MemoryFault::at(
                address,
                format!("memory access starts at {address} and overflows u32",),
            )
        })?;
        let [lo, hi] = value.to_le_bytes();
        self.store_u8(address, lo)?;
        self.store_u8(next, hi)
    }

    fn load_u64(&self, address: u32) -> Result<u64, MemoryFault> {
        let mut bytes = [0_u8; 8];
        for (offset, byte) in bytes.iter_mut().enumerate() {
            let address = address.checked_add(offset as u32).ok_or_else(|| {
                MemoryFault::at(
                    address,
                    format!("memory access starts at {address} and overflows u32",),
                )
            })?;
            *byte = self.load_u8(address)?;
        }
        Ok(u64::from_le_bytes(bytes))
    }

    fn store_u64(&mut self, address: u32, value: u64) -> Result<(), MemoryFault> {
        for (offset, byte) in value.to_le_bytes().into_iter().enumerate() {
            let address = address.checked_add(offset as u32).ok_or_else(|| {
                MemoryFault::at(
                    address,
                    format!("memory access starts at {address} and overflows u32",),
                )
            })?;
            self.store_u8(address, byte)?;
        }
        Ok(())
    }
}

impl MachineMemory {
    pub fn zeroed(size: usize) -> Result<Self, MemoryFault> {
        if size == 0 {
            return Err(MemoryFault::new("memory size must be positive".to_string()));
        }
        Ok(Self {
            bytes: vec![0_u8; size],
        })
    }

    pub fn from_sections(
        memory_size: usize,
        rodata: &[u8],
        data: &[u8],
        bss_size: u32,
    ) -> Result<Self, MemoryFault> {
        let initialized = rodata
            .len()
            .checked_add(data.len())
            .and_then(|value| value.checked_add(bss_size as usize))
            .ok_or_else(|| MemoryFault::new("memory sections overflow".to_string()))?;
        if initialized > memory_size {
            return Err(MemoryFault::new(format!(
                "memory sections require {initialized} bytes but memory size is {memory_size}",
            )));
        }
        let mut memory = Self::zeroed(memory_size)?;
        memory.bytes[..rodata.len()].copy_from_slice(rodata);
        let data_start = rodata.len();
        memory.bytes[data_start..data_start + data.len()].copy_from_slice(data);
        Ok(memory)
    }

    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }

    #[allow(
        dead_code,
        reason = "the RV32 JIT dispatcher consumes the direct RAM pointer in a later slice"
    )]
    pub(crate) fn as_mut_ptr(&mut self) -> *mut u8 {
        self.bytes.as_mut_ptr()
    }

    pub fn len(&self) -> usize {
        self.bytes.len()
    }

    pub fn load_i32(&self, address: u32) -> Result<i32, MemoryFault> {
        let bytes = self.range(address, 4)?;
        let mut raw = [0_u8; 4];
        raw.copy_from_slice(bytes);
        Ok(i32::from_le_bytes(raw))
    }

    pub fn store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault> {
        self.range_mut(address, 4)?
            .copy_from_slice(&value.to_le_bytes());
        Ok(())
    }

    pub fn validate_atomic_i32(&self, address: u32) -> Result<(), MemoryFault> {
        self.range(address, 4).map(|_| ())
    }

    pub fn atomic_load_i32(&self, address: u32) -> Result<i32, MemoryFault> {
        self.validate_atomic_i32(address)?;
        self.load_i32(address)
    }

    pub fn atomic_store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault> {
        self.validate_atomic_i32(address)?;
        self.store_i32(address, value)
    }

    pub fn atomic_update_i32(
        &mut self,
        address: u32,
        update: &mut dyn FnMut(i32) -> i32,
    ) -> Result<i32, MemoryFault> {
        let bytes = self.range_mut(address, 4)?;
        let old = i32::from_le_bytes(bytes.try_into().expect("atomic word has four bytes"));
        bytes.copy_from_slice(&update(old).to_le_bytes());
        Ok(old)
    }

    pub fn load_u8(&self, address: u32) -> Result<u8, MemoryFault> {
        Ok(self.range(address, 1)?[0])
    }

    pub fn store_u8(&mut self, address: u32, value: u8) -> Result<(), MemoryFault> {
        self.range_mut(address, 1)?[0] = value;
        Ok(())
    }

    pub fn load_u16(&self, address: u32) -> Result<u16, MemoryFault> {
        let bytes = self.range(address, 2)?;
        Ok(u16::from_le_bytes([bytes[0], bytes[1]]))
    }

    pub fn store_u16(&mut self, address: u32, value: u16) -> Result<(), MemoryFault> {
        self.range_mut(address, 2)?
            .copy_from_slice(&value.to_le_bytes());
        Ok(())
    }

    pub fn load_u64(&self, address: u32) -> Result<u64, MemoryFault> {
        let bytes = self.range(address, 8)?;
        let mut raw = [0_u8; 8];
        raw.copy_from_slice(bytes);
        Ok(u64::from_le_bytes(raw))
    }

    pub fn store_u64(&mut self, address: u32, value: u64) -> Result<(), MemoryFault> {
        self.range_mut(address, 8)?
            .copy_from_slice(&value.to_le_bytes());
        Ok(())
    }

    fn range(&self, address: u32, size: usize) -> Result<&[u8], MemoryFault> {
        let start = address as usize;
        let end = start.checked_add(size).ok_or_else(|| {
            MemoryFault::at(
                address,
                format!("memory access starts at {address} and overflows usize",),
            )
        })?;
        self.bytes.get(start..end).ok_or_else(|| {
            MemoryFault::at(
                address,
                format!(
                    "memory access {start}..{end} is outside {} bytes",
                    self.bytes.len(),
                ),
            )
        })
    }

    fn range_mut(&mut self, address: u32, size: usize) -> Result<&mut [u8], MemoryFault> {
        let start = address as usize;
        let end = start.checked_add(size).ok_or_else(|| {
            MemoryFault::at(
                address,
                format!("memory access starts at {address} and overflows usize",),
            )
        })?;
        let len = self.bytes.len();
        self.bytes.get_mut(start..end).ok_or_else(|| {
            MemoryFault::at(
                address,
                format!("memory access {start}..{end} is outside {len} bytes"),
            )
        })
    }
}

impl MemoryBus for MachineMemory {
    fn len(&self) -> usize {
        self.len()
    }

    fn load_i32(&self, address: u32) -> Result<i32, MemoryFault> {
        self.load_i32(address)
    }

    fn store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault> {
        self.store_i32(address, value)
    }

    fn validate_atomic_i32(
        &self,
        address: u32,
        _access: AtomicWordAccess,
    ) -> Result<(), MemoryFault> {
        self.validate_atomic_i32(address)
    }

    fn atomic_load_i32(&self, address: u32) -> Result<i32, MemoryFault> {
        self.atomic_load_i32(address)
    }

    fn atomic_store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault> {
        self.atomic_store_i32(address, value)
    }

    fn atomic_update_i32(
        &mut self,
        address: u32,
        update: &mut dyn FnMut(i32) -> i32,
    ) -> Result<i32, MemoryFault> {
        self.atomic_update_i32(address, update)
    }

    fn load_u8(&self, address: u32) -> Result<u8, MemoryFault> {
        self.load_u8(address)
    }

    fn store_u8(&mut self, address: u32, value: u8) -> Result<(), MemoryFault> {
        self.store_u8(address, value)
    }

    fn load_u16(&self, address: u32) -> Result<u16, MemoryFault> {
        self.load_u16(address)
    }

    fn store_u16(&mut self, address: u32, value: u16) -> Result<(), MemoryFault> {
        self.store_u16(address, value)
    }

    fn load_u64(&self, address: u32) -> Result<u64, MemoryFault> {
        self.load_u64(address)
    }

    fn store_u64(&mut self, address: u32, value: u64) -> Result<(), MemoryFault> {
        self.store_u64(address, value)
    }
}

#[cfg(test)]
mod tests {
    use super::MachineMemory;

    #[test]
    fn machine_memory_loads_initial_sections_and_zeroes_the_rest() {
        let memory = MachineMemory::from_sections(16, &[1, 2], &[3, 4, 5], 3).unwrap();

        assert_eq!(
            memory.bytes(),
            &[1, 2, 3, 4, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
        );
    }

    #[test]
    fn machine_memory_rejects_sections_that_do_not_fit() {
        let error = MachineMemory::from_sections(4, &[1, 2], &[3, 4], 1).unwrap_err();

        assert_eq!(
            error.to_string(),
            "memory sections require 5 bytes but memory size is 4",
        );
    }

    #[test]
    fn machine_memory_loads_and_stores_i32_little_endian() {
        let mut memory = MachineMemory::zeroed(8).unwrap();

        memory.store_i32(2, 0x11223344).unwrap();

        assert_eq!(memory.load_i32(2).unwrap(), 0x11223344);
        assert_eq!(&memory.bytes()[2..6], &[0x44, 0x33, 0x22, 0x11]);
    }

    #[test]
    fn machine_memory_atomic_word_update_returns_old_value_and_commits_once() {
        let mut memory = MachineMemory::zeroed(16).unwrap();
        memory.store_i32(4, 7).unwrap();
        let mut add_five = |old: i32| old.wrapping_add(5);

        let old = memory.atomic_update_i32(4, &mut add_five).unwrap();

        assert_eq!(old, 7);
        assert_eq!(memory.load_i32(4).unwrap(), 12);
    }

    #[test]
    fn machine_memory_loads_and_stores_u8_without_touching_neighbors() {
        let mut memory = MachineMemory::zeroed(4).unwrap();
        memory.store_i32(0, 0x11223344).unwrap();

        memory.store_u8(1, 0xaa).unwrap();

        assert_eq!(memory.load_u8(0).unwrap(), 0x44);
        assert_eq!(memory.load_u8(1).unwrap(), 0xaa);
        assert_eq!(memory.load_u8(2).unwrap(), 0x22);
        assert_eq!(memory.load_u8(3).unwrap(), 0x11);
        assert_eq!(&memory.bytes()[..4], &[0x44, 0xaa, 0x22, 0x11]);
    }

    #[test]
    fn machine_memory_loads_and_stores_u16_little_endian_without_alignment() {
        let mut memory = MachineMemory::zeroed(4).unwrap();
        memory.store_i32(0, 0x11223344).unwrap();

        memory.store_u16(1, 0xaabb).unwrap();

        assert_eq!(memory.load_u16(1).unwrap(), 0xaabb);
        assert_eq!(&memory.bytes()[..4], &[0x44, 0xbb, 0xaa, 0x11]);
    }

    #[test]
    fn machine_memory_loads_and_stores_u64_little_endian_without_alignment() {
        let mut memory = MachineMemory::zeroed(10).unwrap();

        memory.store_u64(1, 0x1122_3344_5566_7788).unwrap();

        assert_eq!(memory.load_u64(1).unwrap(), 0x1122_3344_5566_7788);
        assert_eq!(
            &memory.bytes()[..10],
            &[0, 0x88, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, 0],
        );
    }

    #[test]
    fn machine_memory_reports_out_of_bounds_ranges() {
        let memory = MachineMemory::zeroed(8).unwrap();

        let error = memory.load_i32(6).unwrap_err();

        assert_eq!(error.to_string(), "memory access 6..10 is outside 8 bytes");
    }
}
