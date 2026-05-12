use std::error::Error;
use std::fmt::{Display, Formatter};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoryFault {
    message: String,
}

impl MemoryFault {
    pub(crate) fn new(message: String) -> Self {
        Self { message }
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

pub trait MemoryBus {
    fn len(&self) -> usize;

    fn load_i32(&self, address: u32) -> Result<i32, MemoryFault>;

    fn store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault>;
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

    fn range(&self, address: u32, size: usize) -> Result<&[u8], MemoryFault> {
        let start = address as usize;
        let end = start.checked_add(size).ok_or_else(|| {
            MemoryFault::new(format!(
                "memory access starts at {address} and overflows usize",
            ))
        })?;
        self.bytes.get(start..end).ok_or_else(|| {
            MemoryFault::new(format!(
                "memory access {start}..{end} is outside {} bytes",
                self.bytes.len(),
            ))
        })
    }

    fn range_mut(&mut self, address: u32, size: usize) -> Result<&mut [u8], MemoryFault> {
        let start = address as usize;
        let end = start.checked_add(size).ok_or_else(|| {
            MemoryFault::new(format!(
                "memory access starts at {address} and overflows usize",
            ))
        })?;
        let len = self.bytes.len();
        self.bytes.get_mut(start..end).ok_or_else(|| {
            MemoryFault::new(format!(
                "memory access {start}..{end} is outside {len} bytes"
            ))
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
    fn machine_memory_reports_out_of_bounds_ranges() {
        let memory = MachineMemory::zeroed(8).unwrap();

        let error = memory.load_i32(6).unwrap_err();

        assert_eq!(error.to_string(), "memory access 6..10 is outside 8 bytes");
    }
}
