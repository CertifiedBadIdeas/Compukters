use crate::computer_abi;
use crate::low_bus::MmioDevice;
use crate::low_machine::MemoryFault;
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
