use crate::computer_abi;
use crate::low_bus::MmioDevice;
use crate::low_machine::MemoryFault;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ComputerTextDisplaySnapshot {
    pub columns: u32,
    pub rows: u32,
    pub cursor_x: u32,
    pub cursor_y: u32,
    pub sequence: u64,
    pub cells: Vec<u8>,
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

    pub(crate) fn restore_snapshot(
        &mut self,
        snapshot: ComputerTextDisplaySnapshot,
    ) -> Result<(), String> {
        if snapshot.columns != self.columns || snapshot.rows != self.rows {
            return Err(format!(
                "display0 snapshot dimensions {}x{} do not match device dimensions {}x{}",
                snapshot.columns, snapshot.rows, self.columns, self.rows
            ));
        }
        let expected_cells = (self.columns * self.rows) as usize;
        if snapshot.cells.len() != expected_cells {
            return Err(format!(
                "display0 snapshot has {} cells but expected {expected_cells}",
                snapshot.cells.len()
            ));
        }
        if snapshot.cursor_x >= self.columns || snapshot.cursor_y >= self.rows {
            return Err(format!(
                "display0 snapshot cursor {},{} is outside {}x{}",
                snapshot.cursor_x, snapshot.cursor_y, self.columns, self.rows
            ));
        }
        self.cursor_x = snapshot.cursor_x;
        self.cursor_y = snapshot.cursor_y;
        self.sequence = snapshot.sequence;
        self.cells = snapshot.cells;
        Ok(())
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

    fn store_u8(&mut self, offset: u32, value: u8) -> Result<(), MemoryFault> {
        if offset == 20 {
            self.data = i32::from(value);
            return Ok(());
        }
        self.store_register(offset, i32::from(value))
    }
}
