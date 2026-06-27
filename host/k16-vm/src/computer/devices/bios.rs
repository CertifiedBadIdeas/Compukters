use crate::low_bus::MmioDevice;
use crate::low_machine::MemoryFault;

pub(crate) struct BiosFlashDevice {
    bytes: Vec<u8>,
}

impl BiosFlashDevice {
    pub(crate) fn new(bytes: Vec<u8>) -> Result<Self, MemoryFault> {
        if bytes.len() > u32::MAX as usize {
            return Err(MemoryFault::new(
                "BIOS flash size does not fit u32".to_string(),
            ));
        }
        Ok(Self { bytes })
    }

    fn byte_at(&self, offset: u32) -> Result<u8, MemoryFault> {
        self.bytes
            .get(offset as usize)
            .copied()
            .ok_or_else(|| MemoryFault::new(format!("BIOS flash offset {offset} is not mapped")))
    }
}

impl MmioDevice for BiosFlashDevice {
    fn size(&self) -> u32 {
        self.bytes.len() as u32
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        let mut bytes = [0_u8; 4];
        for (index, byte) in bytes.iter_mut().enumerate() {
            let offset = offset.checked_add(index as u32).ok_or_else(|| {
                MemoryFault::new("BIOS flash i32 load offset overflows u32".to_string())
            })?;
            *byte = self.byte_at(offset)?;
        }
        Ok(i32::from_le_bytes(bytes))
    }

    fn store_i32(&mut self, _offset: u32, _value: i32) -> Result<(), MemoryFault> {
        Err(MemoryFault::new("BIOS flash is read-only".to_string()))
    }

    fn load_u8(&self, offset: u32) -> Result<u8, MemoryFault> {
        self.byte_at(offset)
    }

    fn store_u8(&mut self, _offset: u32, _value: u8) -> Result<(), MemoryFault> {
        Err(MemoryFault::new("BIOS flash is read-only".to_string()))
    }
}
