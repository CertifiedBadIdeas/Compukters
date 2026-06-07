use crate::{font, gpu};

const FOREGROUND: u16 = 0xffff;
const BACKGROUND: u16 = 0x0000;
const GLYPH_PIXELS: usize = font::GLYPH_WIDTH * font::GLYPH_HEIGHT;
const GLYPH_STRIDE_BYTES: u32 = (font::GLYPH_WIDTH * 2) as u32;
const MAX_COLUMNS: usize = 320 / font::CELL_WIDTH;
const MAX_ROWS: usize = 200 / font::CELL_HEIGHT;
const CELLS_ADDR: u32 = 0x0000_8000;

static mut GLYPH_BUFFER: [u16; GLYPH_PIXELS] = [0; GLYPH_PIXELS];

static mut COLUMNS: usize = 0;
static mut ROWS: usize = 0;
static mut CURSOR_X: usize = 0;
static mut CURSOR_Y: usize = 0;

pub fn init() {
    let width = gpu::width().max(0) as usize;
    let height = gpu::height().max(0) as usize;
    unsafe {
        COLUMNS = (width / font::CELL_WIDTH).clamp(1, MAX_COLUMNS);
        ROWS = (height / font::CELL_HEIGHT).clamp(1, MAX_ROWS);
        CURSOR_X = 0;
        CURSOR_Y = 0;
    }
    clear();
}

pub fn clear() {
    unsafe {
        CURSOR_X = 0;
        CURSOR_Y = 0;
        clear_cells();
    }
    gpu::clear(BACKGROUND);
}

pub fn write_bytes(bytes: &[u8]) {
    for &byte in bytes {
        write_byte(byte);
    }
}

pub fn write_byte(byte: u8) {
    match byte {
        b'\n' => newline(),
        b'\r' => unsafe {
            CURSOR_X = 0;
        },
        b'\x08' => backspace(),
        b'\t' => write_tab(),
        0x20..=0x7e => write_printable(byte),
        _ => {}
    }
}

pub fn flush() {
    gpu::present();
}

fn write_printable(byte: u8) {
    unsafe {
        if CURSOR_X >= COLUMNS {
            newline();
        }
        let x = CURSOR_X;
        let y = CURSOR_Y;
        set_cell(x, y, byte);
        repaint_cell(x, y);
        CURSOR_X += 1;
    }
}

fn newline() {
    unsafe {
        CURSOR_X = 0;
        if CURSOR_Y + 1 < ROWS {
            CURSOR_Y += 1;
        } else {
            scroll_up();
        }
    }
}

fn backspace() {
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

fn write_tab() {
    loop {
        write_byte(b' ');
        let cursor = unsafe { CURSOR_X };
        if cursor % 4 == 0 {
            break;
        }
    }
}

unsafe fn clear_cells() {
    let cell_count = COLUMNS * ROWS;
    let mut index = 0;
    while index < cell_count {
        write_cell(index, b' ');
        index += 1;
    }
}

unsafe fn scroll_up() {
    if ROWS <= 1 {
        clear_cells();
        repaint_all();
        return;
    }
    let last_row = ROWS - 1;
    let mut row = 1;
    while row < ROWS {
        let mut col = 0;
        while col < COLUMNS {
            let source = cell_index(col, row);
            let target = cell_index(col, row - 1);
            write_cell(target, read_cell(source));
            col += 1;
        }
        row += 1;
    }
    let mut col = 0;
    while col < COLUMNS {
        write_cell(cell_index(col, last_row), b' ');
        col += 1;
    }
    repaint_all();
}

fn set_cell(column: usize, row: usize, byte: u8) {
    unsafe {
        write_cell(cell_index(column, row), byte);
    }
}

fn repaint_all() {
    gpu::clear(BACKGROUND);
    unsafe {
        let mut row = 0;
        while row < ROWS {
            repaint_row(row);
            row += 1;
        }
    }
}

fn repaint_row(row: usize) {
    unsafe {
        let mut column = 0;
        while column < COLUMNS {
            repaint_cell(column, row);
            column += 1;
        }
    }
}

fn repaint_cell(column: usize, row: usize) {
    let byte = unsafe { read_cell(cell_index(column, row)) };
    render_glyph(byte);
    blit_glyph(column, row);
}

fn cell_index(column: usize, row: usize) -> usize {
    row * unsafe { COLUMNS } + column
}

unsafe fn read_cell(index: usize) -> u8 {
    unsafe { core::ptr::read_volatile((CELLS_ADDR + index as u32) as usize as *const u8) }
}

unsafe fn write_cell(index: usize, value: u8) {
    unsafe { core::ptr::write_volatile((CELLS_ADDR + index as u32) as usize as *mut u8, value) }
}

fn render_glyph(byte: u8) {
    let glyph = font::glyph(byte);
    unsafe {
        GLYPH_BUFFER = [BACKGROUND; GLYPH_PIXELS];
        for row in 0..font::GLYPH_HEIGHT {
            let bits = glyph[row];
            for col in 0..font::GLYPH_WIDTH {
                if bits & (1 << (font::GLYPH_WIDTH - 1 - col)) == 0 {
                    continue;
                }
                GLYPH_BUFFER[row * font::GLYPH_WIDTH + col] = FOREGROUND;
            }
        }
    }
}

fn blit_glyph(column: usize, row: usize) {
    let x = column * font::CELL_WIDTH;
    let y = row * font::CELL_HEIGHT + 1;
    let buffer_addr = core::ptr::addr_of!(GLYPH_BUFFER) as u32;
    gpu::blit_buffer(
        x as i32,
        y as i32,
        font::GLYPH_WIDTH as i32,
        font::GLYPH_HEIGHT as i32,
        buffer_addr,
        GLYPH_STRIDE_BYTES,
    );
}
