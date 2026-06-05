use crate::computer_abi;
use crate::low_bus::MmioDevice;
use crate::low_machine::MemoryFault;

pub(crate) struct ComputerControlDevice {
    pub(crate) status: i32,
    pub(crate) panic_code: i32,
    pub(crate) exit_code: i32,
}

impl ComputerControlDevice {
    pub(crate) const SIZE: u32 = computer_abi::CONTROL_SIZE;

    pub(crate) fn new() -> Self {
        Self {
            status: computer_abi::STATUS_RESET,
            panic_code: 0,
            exit_code: 0,
        }
    }

    fn register_for_offset(&mut self, offset: u32) -> Result<&mut i32, MemoryFault> {
        match offset {
            0 => Ok(&mut self.status),
            4 => Ok(&mut self.panic_code),
            8 => Ok(&mut self.exit_code),
            _ => Err(MemoryFault::new(format!(
                "computer control offset {offset} is not mapped",
            ))),
        }
    }

    fn value_for_offset(&self, offset: u32) -> Result<i32, MemoryFault> {
        match offset {
            0 => Ok(self.status),
            4 => Ok(self.panic_code),
            8 => Ok(self.exit_code),
            _ => Err(MemoryFault::new(format!(
                "computer control offset {offset} is not mapped",
            ))),
        }
    }
}

impl MmioDevice for ComputerControlDevice {
    fn size(&self) -> u32 {
        Self::SIZE
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        self.value_for_offset(offset)
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        *self.register_for_offset(offset)? = value;
        Ok(())
    }
}
