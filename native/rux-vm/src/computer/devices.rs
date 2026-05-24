use crate::computer_abi;
use crate::low_bus::MmioDevice;
use crate::low_machine::{MachineMemory, MemoryFault};
use std::cell::RefCell;
use std::collections::VecDeque;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ComputerTextDisplaySnapshot {
    pub columns: u32,
    pub rows: u32,
    pub cursor_x: u32,
    pub cursor_y: u32,
    pub sequence: u64,
    pub cells: Vec<u8>,
}

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

pub(crate) struct DebugSerialDevice {
    bytes: Vec<u8>,
}

impl DebugSerialDevice {
    pub(crate) const SIZE: u32 = computer_abi::DEBUG_SIZE;

    pub(crate) fn new() -> Self {
        Self { bytes: Vec::new() }
    }

    pub(crate) fn bytes(&self) -> &[u8] {
        &self.bytes
    }

    pub(crate) fn drain(&mut self) -> Vec<u8> {
        std::mem::take(&mut self.bytes)
    }
}

impl MmioDevice for DebugSerialDevice {
    fn size(&self) -> u32 {
        Self::SIZE
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        if offset == 0 {
            Ok(0)
        } else {
            Err(MemoryFault::new(format!(
                "computer debug serial offset {offset} is not mapped",
            )))
        }
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        if offset != 0 {
            return Err(MemoryFault::new(format!(
                "computer debug serial offset {offset} is not mapped",
            )));
        }
        self.bytes.push(value.to_le_bytes()[0]);
        Ok(())
    }
}

pub(crate) struct SerialInputDevice {
    bytes: RefCell<VecDeque<u8>>,
}

impl SerialInputDevice {
    pub(crate) const SIZE: u32 = computer_abi::SERIAL_INPUT_SIZE;

    pub(crate) fn new() -> Self {
        Self {
            bytes: RefCell::new(VecDeque::new()),
        }
    }

    pub(crate) fn push_bytes(&mut self, bytes: &[u8]) {
        self.bytes.borrow_mut().extend(bytes.iter().copied());
    }

    pub(crate) fn len(&self) -> usize {
        self.bytes.borrow().len()
    }
}

impl MmioDevice for SerialInputDevice {
    fn size(&self) -> u32 {
        Self::SIZE
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        match offset {
            0 => Ok(if self.bytes.borrow().is_empty() { 0 } else { 1 }),
            4 => Ok(self.bytes.borrow_mut().pop_front().unwrap_or(0).into()),
            _ => Err(MemoryFault::new(format!(
                "computer serial input offset {offset} is not mapped",
            ))),
        }
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        if offset == 4 {
            self.bytes.borrow_mut().push_back(value.to_le_bytes()[0]);
            return Ok(());
        }
        Err(MemoryFault::new(format!(
            "computer serial input offset {offset} is read-only",
        )))
    }

    fn load_u8(&self, offset: u32) -> Result<u8, MemoryFault> {
        match offset {
            0 => Ok(if self.bytes.borrow().is_empty() { 0 } else { 1 }),
            4 => Ok(self.bytes.borrow_mut().pop_front().unwrap_or(0)),
            _ => Err(MemoryFault::new(format!(
                "computer serial input offset {offset} is not mapped",
            ))),
        }
    }

    fn store_u8(&mut self, offset: u32, value: u8) -> Result<(), MemoryFault> {
        if offset == 4 {
            self.bytes.borrow_mut().push_back(value);
            return Ok(());
        }
        Err(MemoryFault::new(format!(
            "computer serial input offset {offset} is read-only",
        )))
    }
}

pub(crate) struct TextDisplayDevice {
    columns: u32,
    rows: u32,
    cursor_x: u32,
    cursor_y: u32,
    data: i32,
    sequence: u64,
    cells: Vec<u8>,
}

impl TextDisplayDevice {
    pub(crate) const SIZE: u32 = computer_abi::DISPLAY0_SIZE;
    const COLUMNS: u32 = 80;
    const ROWS: u32 = 25;

    pub(crate) fn new() -> Self {
        Self {
            columns: Self::COLUMNS,
            rows: Self::ROWS,
            cursor_x: 0,
            cursor_y: 0,
            data: 0,
            sequence: 0,
            cells: vec![0; (Self::COLUMNS * Self::ROWS) as usize],
        }
    }

    pub(crate) fn snapshot(&self) -> ComputerTextDisplaySnapshot {
        ComputerTextDisplaySnapshot {
            columns: self.columns,
            rows: self.rows,
            cursor_x: self.cursor_x,
            cursor_y: self.cursor_y,
            sequence: self.sequence,
            cells: self.cells.clone(),
        }
    }

    pub(crate) fn sequence(&self) -> u64 {
        self.sequence
    }

    fn clamp_x(&self, value: i32) -> u32 {
        value.max(0).min(self.columns.saturating_sub(1) as i32) as u32
    }

    fn clamp_y(&self, value: i32) -> u32 {
        value.max(0).min(self.rows.saturating_sub(1) as i32) as u32
    }

    fn cell_index(&self, x: u32, y: u32) -> usize {
        (y * self.columns + x) as usize
    }

    fn clear(&mut self) {
        self.cells.fill(0);
        self.cursor_x = 0;
        self.cursor_y = 0;
        self.sequence = self.sequence.wrapping_add(1);
    }

    fn put_byte_at_cursor(&mut self, byte: u8) {
        let index = self.cell_index(self.cursor_x, self.cursor_y);
        self.cells[index] = byte;
        self.cursor_x += 1;
        if self.cursor_x >= self.columns {
            self.newline_without_sequence();
        }
        self.sequence = self.sequence.wrapping_add(1);
    }

    fn put_byte_at_xy(&mut self, byte: u8, x: u32, y: u32) {
        if x >= self.columns || y >= self.rows {
            return;
        }
        let index = self.cell_index(x, y);
        self.cells[index] = byte;
        self.sequence = self.sequence.wrapping_add(1);
    }

    fn newline(&mut self) {
        self.newline_without_sequence();
        self.sequence = self.sequence.wrapping_add(1);
    }

    fn newline_without_sequence(&mut self) {
        self.cursor_x = 0;
        if self.cursor_y + 1 >= self.rows {
            self.scroll();
        } else {
            self.cursor_y += 1;
        }
    }

    fn scroll(&mut self) {
        let columns = self.columns as usize;
        self.cells.copy_within(columns.., 0);
        let last_row = self.cells.len() - columns;
        self.cells[last_row..].fill(0);
        self.cursor_y = self.rows - 1;
    }

    fn execute_command(&mut self, command: i32) {
        match command {
            computer_abi::DISPLAY0_COMMAND_CLEAR => self.clear(),
            computer_abi::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR => {
                self.put_byte_at_cursor(self.data.to_le_bytes()[0]);
            }
            computer_abi::DISPLAY0_COMMAND_PUT_BYTE_AT_XY => {
                let packed = u32::from_le_bytes(self.data.to_le_bytes());
                let byte = (packed & 0xFF) as u8;
                let x = (packed >> 8) & 0x0FFF;
                let y = (packed >> 20) & 0x0FFF;
                self.put_byte_at_xy(byte, x, y);
            }
            computer_abi::DISPLAY0_COMMAND_NEWLINE => self.newline(),
            _ => {}
        }
    }

    fn load_register(&self, offset: u32) -> Result<i32, MemoryFault> {
        match offset {
            0 => Ok(self.columns as i32),
            4 => Ok(self.rows as i32),
            8 => Ok(self.cursor_x as i32),
            12 => Ok(self.cursor_y as i32),
            24 => Ok((self.sequence as u32) as i32),
            28 => Ok((self.sequence >> 32) as u32 as i32),
            _ => Err(MemoryFault::new(format!(
                "computer display0 offset {offset} is not readable",
            ))),
        }
    }

    fn store_register(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        match offset {
            8 => {
                self.cursor_x = self.clamp_x(value);
                Ok(())
            }
            12 => {
                self.cursor_y = self.clamp_y(value);
                Ok(())
            }
            16 => {
                self.execute_command(value);
                Ok(())
            }
            20 => {
                self.data = value;
                Ok(())
            }
            _ => Err(MemoryFault::new(format!(
                "computer display0 offset {offset} is not writable",
            ))),
        }
    }
}

impl MmioDevice for TextDisplayDevice {
    fn size(&self) -> u32 {
        Self::SIZE
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        self.load_register(offset)
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        self.store_register(offset, value)
    }
}

pub(crate) struct StoragePortDevice {
    status: i32,
    error: i32,
    lba_low: u32,
    lba_high: u32,
    block_count: u32,
    buffer_addr: u32,
    bytes_done: u32,
    sequence: u64,
    media: Option<Box<dyn StorageMedia>>,
}

pub(crate) trait StorageMedia {
    fn len(&self) -> u64;

    fn is_read_only(&self) -> bool;

    fn read_at(&mut self, offset: u64, dst: &mut [u8]) -> Result<(), MemoryFault>;

    fn write_at(&mut self, offset: u64, src: &[u8]) -> Result<(), MemoryFault>;

    fn flush(&mut self) -> Result<(), MemoryFault>;

    fn snapshot_bytes(&self) -> Option<Vec<u8>>;
}

pub(crate) struct InMemoryStorageMedia {
    bytes: Vec<u8>,
    read_only: bool,
}

impl InMemoryStorageMedia {
    pub(crate) fn new(bytes: Vec<u8>, read_only: bool) -> Self {
        Self { bytes, read_only }
    }
}

impl StorageMedia for InMemoryStorageMedia {
    fn len(&self) -> u64 {
        self.bytes.len() as u64
    }

    fn is_read_only(&self) -> bool {
        self.read_only
    }

    fn read_at(&mut self, offset: u64, dst: &mut [u8]) -> Result<(), MemoryFault> {
        let offset = usize::try_from(offset)
            .map_err(|_| MemoryFault::new("storage0 read offset does not fit usize".to_string()))?;
        let end = offset
            .checked_add(dst.len())
            .ok_or_else(|| MemoryFault::new("storage0 read range overflow".to_string()))?;
        let Some(bytes) = self.bytes.get(offset..end) else {
            return Err(MemoryFault::new(
                "storage0 read range is out of bounds".to_string(),
            ));
        };
        dst.copy_from_slice(bytes);
        Ok(())
    }

    fn write_at(&mut self, offset: u64, src: &[u8]) -> Result<(), MemoryFault> {
        let offset = usize::try_from(offset)
            .map_err(|_| MemoryFault::new("storage0 write offset does not fit usize".to_string()))?;
        let end = offset
            .checked_add(src.len())
            .ok_or_else(|| MemoryFault::new("storage0 write range overflow".to_string()))?;
        let Some(bytes) = self.bytes.get_mut(offset..end) else {
            return Err(MemoryFault::new(
                "storage0 write range is out of bounds".to_string(),
            ));
        };
        bytes.copy_from_slice(src);
        Ok(())
    }

    fn flush(&mut self) -> Result<(), MemoryFault> {
        Ok(())
    }

    fn snapshot_bytes(&self) -> Option<Vec<u8>> {
        Some(self.bytes.clone())
    }
}

impl StoragePortDevice {
    pub(crate) const SIZE: u32 = computer_abi::STORAGE0_SIZE;
    const BLOCK_SIZE: u32 = 512;

    pub(crate) fn new_absent() -> Self {
        Self {
            status: computer_abi::STORAGE_STATUS_READY,
            error: computer_abi::STORAGE_ERROR_NONE,
            lba_low: 0,
            lba_high: 0,
            block_count: 0,
            buffer_addr: 0,
            bytes_done: 0,
            sequence: 0,
            media: None,
        }
    }

    pub(crate) fn with_media(bytes: Vec<u8>, read_only: bool) -> Result<Self, MemoryFault> {
        Self::with_media_backend(Box::new(InMemoryStorageMedia::new(bytes, read_only)))
    }

    pub(crate) fn with_media_backend(
        media: Box<dyn StorageMedia>,
    ) -> Result<Self, MemoryFault> {
        let len = media.len();
        if len % u64::from(Self::BLOCK_SIZE) != 0 {
            return Err(MemoryFault::new(format!(
                "storage0 media size {} is not a multiple of block size {}",
                len,
                Self::BLOCK_SIZE,
            )));
        }
        let mut device = Self::new_absent();
        device.media = Some(media);
        Ok(device)
    }

    pub(crate) fn media_bytes(&self) -> Option<Vec<u8>> {
        self.media.as_ref().and_then(|media| media.snapshot_bytes())
    }

    fn execute_command(&mut self, command: i32, memory: Option<&mut MachineMemory>) {
        self.sequence = self.sequence.wrapping_add(1);
        self.bytes_done = 0;
        match command {
            computer_abi::STORAGE_COMMAND_NOP => {
                self.status = computer_abi::STORAGE_STATUS_DONE;
                self.error = computer_abi::STORAGE_ERROR_NONE;
            }
            computer_abi::STORAGE_COMMAND_FLUSH => {
                match self.media.as_mut() {
                    Some(media) => match media.flush() {
                        Ok(()) => {
                            self.status = computer_abi::STORAGE_STATUS_DONE;
                            self.error = computer_abi::STORAGE_ERROR_NONE;
                        }
                        Err(_) => self.fail(computer_abi::STORAGE_ERROR_IO_ERROR),
                    },
                    None => self.fail(computer_abi::STORAGE_ERROR_MEDIA_ABSENT),
                }
            }
            computer_abi::STORAGE_COMMAND_READ_BLOCKS
            | computer_abi::STORAGE_COMMAND_WRITE_BLOCKS => self.execute_transfer(command, memory),
            _ => {
                self.status = computer_abi::STORAGE_STATUS_ERROR;
                self.error = computer_abi::STORAGE_ERROR_INVALID_COMMAND;
            }
        }
    }

    fn execute_transfer(&mut self, command: i32, memory: Option<&mut MachineMemory>) {
        let Some(media) = self.media.as_mut() else {
            self.fail(computer_abi::STORAGE_ERROR_MEDIA_ABSENT);
            return;
        };
        if command == computer_abi::STORAGE_COMMAND_WRITE_BLOCKS && media.is_read_only() {
            self.fail(computer_abi::STORAGE_ERROR_WRITE_PROTECTED);
            return;
        }
        let byte_count = match self.block_count.checked_mul(Self::BLOCK_SIZE) {
            Some(value) => value,
            None => {
                self.fail(computer_abi::STORAGE_ERROR_BYTE_COUNT_OVERFLOW);
                return;
            }
        };
        let lba = (u64::from(self.lba_high) << 32) | u64::from(self.lba_low);
        let end_lba = match lba.checked_add(u64::from(self.block_count)) {
            Some(value) => value,
            None => {
                self.fail(computer_abi::STORAGE_ERROR_LBA_OUT_OF_BOUNDS);
                return;
            }
        };
        let capacity_blocks = media.len() / u64::from(Self::BLOCK_SIZE);
        if end_lba > capacity_blocks {
            self.fail(computer_abi::STORAGE_ERROR_LBA_OUT_OF_BOUNDS);
            return;
        }
        let Some(buffer_end) = self.buffer_addr.checked_add(byte_count) else {
            self.fail(computer_abi::STORAGE_ERROR_BUFFER_OUT_OF_BOUNDS);
            return;
        };
        let Some(memory) = memory else {
            self.fail(computer_abi::STORAGE_ERROR_BUFFER_OUT_OF_BOUNDS);
            return;
        };
        if buffer_end as usize > memory.len() {
            self.fail(computer_abi::STORAGE_ERROR_BUFFER_OUT_OF_BOUNDS);
            return;
        }
        let media_start = match lba.checked_mul(u64::from(Self::BLOCK_SIZE)) {
            Some(value) => value as usize,
            None => {
                self.fail(computer_abi::STORAGE_ERROR_LBA_OUT_OF_BOUNDS);
                return;
            }
        };
        let byte_count_usize = byte_count as usize;
        match command {
            computer_abi::STORAGE_COMMAND_READ_BLOCKS => {
                let mut bytes = vec![0; byte_count_usize];
                if media.read_at(media_start as u64, &mut bytes).is_err() {
                    self.fail(computer_abi::STORAGE_ERROR_IO_ERROR);
                    return;
                }
                for (offset, byte) in bytes.into_iter().enumerate() {
                    if memory
                        .store_u8(self.buffer_addr + offset as u32, byte)
                        .is_err()
                    {
                        self.fail(computer_abi::STORAGE_ERROR_BUFFER_OUT_OF_BOUNDS);
                        return;
                    }
                }
            }
            computer_abi::STORAGE_COMMAND_WRITE_BLOCKS => {
                let mut bytes = vec![0; byte_count_usize];
                for offset in 0..byte_count_usize {
                    bytes[offset] = match memory.load_u8(self.buffer_addr + offset as u32) {
                        Ok(byte) => byte,
                        Err(_) => {
                            self.fail(computer_abi::STORAGE_ERROR_BUFFER_OUT_OF_BOUNDS);
                            return;
                        }
                    };
                }
                if media.write_at(media_start as u64, &bytes).is_err() {
                    self.fail(computer_abi::STORAGE_ERROR_IO_ERROR);
                    return;
                }
            }
            _ => unreachable!("transfer command is validated by caller"),
        }
        self.status = computer_abi::STORAGE_STATUS_DONE;
        self.error = computer_abi::STORAGE_ERROR_NONE;
        self.bytes_done = byte_count;
    }

    fn fail(&mut self, error: i32) {
        self.status = computer_abi::STORAGE_STATUS_ERROR;
        self.error = error;
        self.bytes_done = 0;
    }

    fn load_register(&self, offset: u32) -> Result<i32, MemoryFault> {
        match offset {
            0 => Ok(computer_abi::STORAGE_VERSION),
            4 => Ok(self.status),
            8 => Ok(self.error),
            16 => Ok(Self::BLOCK_SIZE as i32),
            20 => Ok((self.capacity_blocks() as u32) as i32),
            24 => Ok((self.capacity_blocks() >> 32) as u32 as i32),
            28 => Ok(self.lba_low as i32),
            32 => Ok(self.lba_high as i32),
            36 => Ok(self.block_count as i32),
            40 => Ok(self.buffer_addr as i32),
            44 => Ok(self.bytes_done as i32),
            48 => Ok((self.sequence as u32) as i32),
            52 => Ok((self.sequence >> 32) as u32 as i32),
            56 => Ok(self.media_status()),
            _ => Err(MemoryFault::new(format!(
                "computer storage0 offset {offset} is not readable",
            ))),
        }
    }

    fn capacity_blocks(&self) -> u64 {
        self.media
            .as_ref()
            .map(|media| media.len() / u64::from(Self::BLOCK_SIZE))
            .unwrap_or(0)
    }

    fn media_status(&self) -> i32 {
        match &self.media {
            None => computer_abi::STORAGE_MEDIA_ABSENT,
            Some(media) if media.is_read_only() => computer_abi::STORAGE_MEDIA_READ_ONLY,
            Some(_) => computer_abi::STORAGE_MEDIA_PRESENT,
        }
    }

    fn store_register(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        match offset {
            12 => {
                self.execute_command(value, None);
                Ok(())
            }
            28 => {
                self.lba_low = u32::from_le_bytes(value.to_le_bytes());
                Ok(())
            }
            32 => {
                self.lba_high = u32::from_le_bytes(value.to_le_bytes());
                Ok(())
            }
            36 => {
                self.block_count = u32::from_le_bytes(value.to_le_bytes());
                Ok(())
            }
            40 => {
                self.buffer_addr = u32::from_le_bytes(value.to_le_bytes());
                Ok(())
            }
            _ => Err(MemoryFault::new(format!(
                "computer storage0 offset {offset} is not writable",
            ))),
        }
    }
}

impl MmioDevice for StoragePortDevice {
    fn size(&self) -> u32 {
        Self::SIZE
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        self.load_register(offset)
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        self.store_register(offset, value)
    }

    fn store_i32_with_memory(
        &mut self,
        offset: u32,
        value: i32,
        memory: &mut MachineMemory,
    ) -> Result<(), MemoryFault> {
        if offset == 12 {
            self.execute_command(value, Some(memory));
            return Ok(());
        }
        self.store_register(offset, value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    use std::rc::Rc;

    struct CountingFlushMedia {
        bytes: Vec<u8>,
        flush_count: Rc<Cell<u32>>,
    }

    impl StorageMedia for CountingFlushMedia {
        fn len(&self) -> u64 {
            self.bytes.len() as u64
        }

        fn is_read_only(&self) -> bool {
            false
        }

        fn read_at(&mut self, offset: u64, dst: &mut [u8]) -> Result<(), MemoryFault> {
            let offset = offset as usize;
            dst.copy_from_slice(&self.bytes[offset..offset + dst.len()]);
            Ok(())
        }

        fn write_at(&mut self, offset: u64, src: &[u8]) -> Result<(), MemoryFault> {
            let offset = offset as usize;
            self.bytes[offset..offset + src.len()].copy_from_slice(src);
            Ok(())
        }

        fn flush(&mut self) -> Result<(), MemoryFault> {
            self.flush_count.set(self.flush_count.get() + 1);
            Ok(())
        }

        fn snapshot_bytes(&self) -> Option<Vec<u8>> {
            Some(self.bytes.clone())
        }
    }

    #[test]
    fn storage_port_flush_delegates_to_media_backend() {
        let flush_count = Rc::new(Cell::new(0));
        let media = CountingFlushMedia {
            bytes: vec![0; 512],
            flush_count: flush_count.clone(),
        };
        let mut device = StoragePortDevice::with_media_backend(Box::new(media)).unwrap();

        device
            .store_i32(
                12,
                computer_abi::STORAGE_COMMAND_FLUSH,
            )
            .unwrap();

        assert_eq!(flush_count.get(), 1);
        assert_eq!(
            device
                .load_i32(4)
                .unwrap(),
            computer_abi::STORAGE_STATUS_DONE,
        );
    }
}
