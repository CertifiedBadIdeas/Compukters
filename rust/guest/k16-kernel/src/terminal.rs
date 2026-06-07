use crate::terminal_render;

const COLUMNS: usize = 320 / terminal_render::CELL_WIDTH;
const ROWS: usize = 200 / terminal_render::CELL_HEIGHT;
const CELL_COUNT: usize = COLUMNS * ROWS;
const CELLS_ADDR: u32 = 0x0000_8000;

static mut CURSOR_X: usize = 0;
static mut CURSOR_Y: usize = 0;

pub fn init() {
    clear_terminal();
}

pub fn clear() {
    clear_terminal();
}

pub fn write_bytes(bytes: &[u8]) {
    let mut index = 0;
    while index < bytes.len() {
        write_byte(bytes[index]);
        index += 1;
    }
}

pub fn write_byte(byte: u8) {
    match byte {
        b'\n' => move_to_next_line(),
        b'\r' => move_to_column_start(),
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
        clear_cells();
    }
    terminal_render::clear_screen();
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
        repaint_cell(x, y);
        CURSOR_X += 1;
    }
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
            return;
        }
        CURSOR_X -= 1;
        let x = CURSOR_X;
        let y = CURSOR_Y;
        set_cell(x, y, b' ');
        repaint_cell(x, y);
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
    copy_scrolled_cells();
    clear_last_row();
    terminal_render::clear_screen();
    repaint_all_cells();
}

unsafe fn copy_scrolled_cells() {
    let mut row = 1;
    while row < ROWS {
        let mut column = 0;
        while column < COLUMNS {
            let value = read_cell(cell_index(column, row));
            write_cell(cell_index(column, row - 1), value);
            column += 1;
        }
        row += 1;
    }
}

unsafe fn clear_last_row() {
    let mut column = 0;
    while column < COLUMNS {
        write_cell(cell_index(column, ROWS - 1), b' ');
        column += 1;
    }
}

fn repaint_all_cells() {
    let mut row = 0;
    while row < ROWS {
        let mut column = 0;
        while column < COLUMNS {
            repaint_cell(column, row);
            column += 1;
        }
        row += 1;
    }
}

fn set_cell(column: usize, row: usize, byte: u8) {
    unsafe {
        write_cell(cell_index(column, row), byte);
    }
}

fn repaint_cell(column: usize, row: usize) {
    let byte = unsafe { read_cell(cell_index(column, row)) };
    terminal_render::repaint_cell(column, row, byte);
}

fn cell_index(column: usize, row: usize) -> usize {
    row * COLUMNS + column
}

unsafe fn read_cell(index: usize) -> u8 {
    unsafe { core::ptr::read_volatile((CELLS_ADDR + index as u32) as usize as *const u8) }
}

unsafe fn write_cell(index: usize, value: u8) {
    unsafe { core::ptr::write_volatile((CELLS_ADDR + index as u32) as usize as *mut u8, value) }
}
