use crate::{font, gpu};

pub const CELL_WIDTH: usize = font::CELL_WIDTH;
pub const CELL_HEIGHT: usize = font::CELL_HEIGHT;

const FOREGROUND: u16 = 0xffff;
const BACKGROUND: u16 = 0x0000;
const GLYPH_PIXELS: usize = font::GLYPH_WIDTH * font::GLYPH_HEIGHT;
const GLYPH_STRIDE_BYTES: u32 = (font::GLYPH_WIDTH * 2) as u32;

static mut GLYPH_BUFFER: [u16; GLYPH_PIXELS] = [0; GLYPH_PIXELS];

pub fn clear_screen() {
    gpu::clear(BACKGROUND);
}

pub fn repaint_cell(column: usize, row: usize, byte: u8) {
    render_glyph(byte);
    blit_glyph(column, row);
}

pub fn flush() {
    gpu::present();
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
