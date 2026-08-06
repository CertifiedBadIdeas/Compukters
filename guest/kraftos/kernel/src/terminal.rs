use crate::{memory_layout, terminal_render};

const COLUMNS: usize = memory_layout::TERMINAL_COLUMNS as usize;
const ROWS: usize = memory_layout::TERMINAL_ROWS as usize;
const CELL_COUNT: usize = COLUMNS * ROWS;
const CELLS_ADDR: u32 = memory_layout::TERMINAL_CELLS_ADDR;

static mut CURSOR_X: usize = 0;
static mut CURSOR_Y: usize = 0;
static mut ROW_HEAD: usize = 0;

pub fn init() {
    reset_cursor();
    unsafe {
        ROW_HEAD = 0;
        clear_cells();
    }
    terminal_render::init();
}

pub fn clear() {
    clear_terminal();
}

pub fn write_bytes(bytes: &[u8]) {
    let mut index = 0;
    while index < bytes.len() {
        if is_printable_byte(bytes[index]) {
            index = write_printable_run(bytes, index);
        } else {
            write_byte(bytes[index]);
            index += 1;
        }
    }
}

pub fn write_byte(byte: u8) {
    match byte {
        b'\n' => move_to_next_line(),
        b'\r' => move_to_column_start(),
        b'\x0c' => clear_terminal(),
        b'\x08' => erase_previous_cell(),
        b'\t' => write_tab_spaces(),
        0x20..=0x7e => put_printable_byte(byte),
        _ => {}
    }
}

pub fn flush() {
    terminal_render::flush();
}

fn clear_terminal() {
    reset_cursor();
    unsafe {
        ROW_HEAD = 0;
        clear_cells();
    }
    terminal_render::reset();
}

fn reset_cursor() {
    unsafe {
        CURSOR_X = 0;
        CURSOR_Y = 0;
    }
}

fn move_to_column_start() {
    unsafe {
        CURSOR_X = 0;
    }
}

fn put_printable_byte(byte: u8) {
    unsafe {
        if CURSOR_X >= COLUMNS {
            move_to_next_line();
        }
        let x = CURSOR_X;
        let y = CURSOR_Y;
        set_cell(x, y, byte);
        CURSOR_X += 1;
    }
}

fn write_printable_run(bytes: &[u8], start: usize) -> usize {
    let mut index = start;
    while index < bytes.len() && is_printable_byte(bytes[index]) {
        unsafe {
            if CURSOR_X >= COLUMNS {
                move_to_next_line();
            }
            let column = CURSOR_X;
            let row = CURSOR_Y;
            let remaining_columns = COLUMNS - column;
            let mut run_len = 0;
            while run_len < remaining_columns
                && index + run_len < bytes.len()
                && is_printable_byte(bytes[index + run_len])
            {
                set_cell(column + run_len, row, bytes[index + run_len]);
                run_len += 1;
            }
            CURSOR_X += run_len;
            index += run_len;
        }
    }
    index
}

fn is_printable_byte(byte: u8) -> bool {
    (0x20..=0x7e).contains(&byte)
}

fn move_to_next_line() {
    unsafe {
        CURSOR_X = 0;
        if CURSOR_Y + 1 < ROWS {
            CURSOR_Y += 1;
        } else {
            scroll_up();
        }
    }
}

fn erase_previous_cell() {
    unsafe {
        if CURSOR_X == 0 {
            if CURSOR_Y == 0 {
                return;
            }
            CURSOR_Y -= 1;
            CURSOR_X = COLUMNS;
        }
        CURSOR_X -= 1;
        let x = CURSOR_X;
        let y = CURSOR_Y;
        set_cell(x, y, b' ');
    }
}

fn write_tab_spaces() {
    loop {
        write_byte(b' ');
        let cursor = unsafe { CURSOR_X };
        if cursor & 3 == 0 {
            break;
        }
    }
}

unsafe fn clear_cells() {
    let mut index = 0;
    while index < CELL_COUNT {
        write_cell(index, b' ');
        index += 1;
    }
}

unsafe fn scroll_up() {
    ROW_HEAD = (ROW_HEAD + 1) % ROWS;
    clear_last_row();
    terminal_render::scroll_up();
}

unsafe fn clear_last_row() {
    let mut column = 0;
    while column < COLUMNS {
        write_cell(cell_index(column, ROWS - 1), b' ');
        column += 1;
    }
}

fn set_cell(column: usize, row: usize, byte: u8) {
    unsafe {
        write_cell(cell_index(column, row), byte);
    }
    terminal_render::set_cell(column, row, byte);
}

fn cell_index(column: usize, row: usize) -> usize {
    unsafe { ((ROW_HEAD + row) % ROWS) * COLUMNS + column }
}

unsafe fn write_cell(index: usize, value: u8) {
    unsafe { core::ptr::write_volatile((CELLS_ADDR + index as u32) as usize as *mut u8, value) }
}
