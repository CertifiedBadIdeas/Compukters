use crate::{font, gpu};

pub const CELL_WIDTH: usize = font::CELL_WIDTH;
pub const CELL_HEIGHT: usize = font::CELL_HEIGHT;

const FOREGROUND: u16 = 0xffff;
const BACKGROUND: u16 = 0x0000;
const COLUMNS: usize = crate::memory_layout::TERMINAL_COLUMNS as usize;
const ROWS: usize = crate::memory_layout::TERMINAL_ROWS as usize;
const TERMINAL_WIDTH: usize = CELL_WIDTH * COLUMNS;
const SCROLL_HEIGHT: usize = CELL_HEIGHT * (ROWS - 1);
const LAST_ROW_Y: usize = CELL_HEIGHT * (ROWS - 1);
const ROW_PIXELS: usize = TERMINAL_WIDTH * CELL_HEIGHT;
const ROW_STRIDE_BYTES: u32 = (TERMINAL_WIDTH * 2) as u32;

static mut ROW_BUFFER: [u16; ROW_PIXELS] = [0; ROW_PIXELS];

pub fn clear_screen() {
    gpu::clear(BACKGROUND);
}

pub fn scroll_up() {
    gpu::copy_rect(
        0,
        CELL_HEIGHT as i32,
        TERMINAL_WIDTH as i32,
        SCROLL_HEIGHT as i32,
        0,
        0,
    );
    gpu::fill_rect(
        0,
        LAST_ROW_Y as i32,
        TERMINAL_WIDTH as i32,
        CELL_HEIGHT as i32,
        BACKGROUND,
    );
}

pub fn repaint_cell(column: usize, row: usize, byte: u8) {
    repaint_run(column, row, core::slice::from_ref(&byte));
}

pub fn repaint_run(column: usize, row: usize, bytes: &[u8]) {
    if bytes.is_empty() || column >= COLUMNS || row >= ROWS {
        return;
    }
    let run_len = bytes.len().min(COLUMNS - column);
    render_glyph_run(&bytes[..run_len]);
    blit_glyph_run(column, row, run_len);
}

pub fn flush() {
    gpu::present();
}

fn render_glyph_run(bytes: &[u8]) {
    let pixel_width = bytes.len() * CELL_WIDTH;
    unsafe {
        let mut row = 0;
        while row < CELL_HEIGHT {
            let mut col = 0;
            while col < pixel_width {
                ROW_BUFFER[row * TERMINAL_WIDTH + col] = BACKGROUND;
                col += 1;
            }
            row += 1;
        }
        let mut index = 0;
        while index < bytes.len() {
            render_glyph_into_run(index, bytes[index]);
            index += 1;
        }
    }
}

unsafe fn render_glyph_into_run(index: usize, byte: u8) {
    let glyph = font::glyph(byte);
    let base_col = index * CELL_WIDTH;
    let mut row = 0;
    while row < font::GLYPH_HEIGHT {
        let bits = glyph[row];
        let mut col = 0;
        while col < font::GLYPH_WIDTH {
            if bits & (1 << (font::GLYPH_WIDTH - 1 - col)) == 0 {
                col += 1;
                continue;
            }
            let target_row = font::GLYPH_Y + row;
            let target_col = base_col + font::GLYPH_X + col;
            ROW_BUFFER[target_row * TERMINAL_WIDTH + target_col] = FOREGROUND;
            col += 1;
        }
        row += 1;
    }
}

fn blit_glyph_run(column: usize, row: usize, run_len: usize) {
    let x = column * font::CELL_WIDTH;
    let y = row * font::CELL_HEIGHT;
    let buffer_addr = core::ptr::addr_of!(ROW_BUFFER) as u32;
    gpu::blit_buffer(
        x as i32,
        y as i32,
        (run_len * font::CELL_WIDTH) as i32,
        font::CELL_HEIGHT as i32,
        buffer_addr,
        ROW_STRIDE_BYTES,
    );
}
