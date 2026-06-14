use crate::computer_abi;
use crate::low_bus::MmioDevice;
use crate::low_machine::MemoryFault;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct MmuControlCommand {
    pub(crate) command: i32,
    pub(crate) address_space: u32,
    pub(crate) virtual_start: u32,
    pub(crate) physical_start: u32,
    pub(crate) page_count: u32,
    pub(crate) flags: u32,
    pub(crate) entry_pc: u32,
    pub(crate) stack_pointer: u32,
}

pub(crate) struct MmuControlDevice {
    status: i32,
    error: i32,
    command: i32,
    address_space: u32,
    virtual_start: u32,
    physical_start: u32,
    page_count: u32,
    flags: u32,
    entry_pc: u32,
    stack_pointer: u32,
    result: u32,
    pending_command: Option<MmuControlCommand>,
    yield_requested: bool,
}

impl MmuControlDevice {
    pub(crate) const SIZE: u32 = computer_abi::MMU0_SIZE;

    pub(crate) fn new() -> Self {
        Self {
            status: computer_abi::MMU0_STATUS_READY,
            error: computer_abi::MMU0_ERROR_NONE,
            command: computer_abi::MMU0_COMMAND_NOP,
            address_space: 0,
            virtual_start: 0,
            physical_start: 0,
            page_count: 0,
            flags: 0,
            entry_pc: 0,
            stack_pointer: 0,
            result: 0,
            pending_command: None,
            yield_requested: false,
        }
    }

    pub(crate) fn take_pending_command(&mut self) -> Option<MmuControlCommand> {
        self.pending_command.take()
    }

    pub(crate) fn finish_success(&mut self, result: u32) {
        self.status = computer_abi::MMU0_STATUS_DONE;
        self.error = computer_abi::MMU0_ERROR_NONE;
        self.result = result;
    }

    pub(crate) fn finish_error(&mut self, error: i32) {
        self.status = computer_abi::MMU0_STATUS_ERROR;
        self.error = error;
    }

    fn submit_command(&mut self, command: i32) {
        self.command = command;
        self.status = computer_abi::MMU0_STATUS_READY;
        self.error = computer_abi::MMU0_ERROR_NONE;
        self.pending_command = Some(MmuControlCommand {
            command,
            address_space: self.address_space,
            virtual_start: self.virtual_start,
            physical_start: self.physical_start,
            page_count: self.page_count,
            flags: self.flags,
            entry_pc: self.entry_pc,
            stack_pointer: self.stack_pointer,
        });
        self.yield_requested = true;
    }

    fn load_u32(&self, offset: u32) -> Result<u32, MemoryFault> {
        match offset {
            0 => Ok(computer_abi::MMU0_VERSION_VALUE as u32),
            4 => Ok(self.status as u32),
            8 => Ok(self.error as u32),
            12 => Ok(self.command as u32),
            16 => Ok(self.address_space),
            20 => Ok(self.virtual_start),
            24 => Ok(self.physical_start),
            28 => Ok(self.page_count),
            32 => Ok(self.flags),
            36 => Ok(self.entry_pc),
            40 => Ok(self.stack_pointer),
            44 => Ok(self.result),
            _ => Err(MemoryFault::new(format!(
                "mmu0 offset {offset} is not mapped"
            ))),
        }
    }

    fn store_u32(&mut self, offset: u32, value: u32) -> Result<(), MemoryFault> {
        match offset {
            12 => self.submit_command(i32::from_le_bytes(value.to_le_bytes())),
            16 => self.address_space = value,
            20 => self.virtual_start = value,
            24 => self.physical_start = value,
            28 => self.page_count = value,
            32 => self.flags = value,
            36 => self.entry_pc = value,
            40 => self.stack_pointer = value,
            _ => {
                return Err(MemoryFault::new(format!(
                    "mmu0 offset {offset} is read-only"
                )));
            }
        }
        Ok(())
    }
}

impl MmioDevice for MmuControlDevice {
    fn size(&self) -> u32 {
        Self::SIZE
    }

    fn take_yield_signal(&mut self) -> bool {
        let requested = self.yield_requested;
        self.yield_requested = false;
        requested
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        Ok(i32::from_le_bytes(self.load_u32(offset)?.to_le_bytes()))
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        self.store_u32(offset, u32::from_le_bytes(value.to_le_bytes()))
    }
}
