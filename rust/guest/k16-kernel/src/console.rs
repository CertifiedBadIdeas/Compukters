use crate::{font, gpu};

const FOREGROUND: u16 = 0xffff;
const BACKGROUND: u16 = 0x0000;
const GLYPH_PIXELS: usize = font::GLYPH_WIDTH * font::GLYPH_HEIGHT;
const GLYPH_STRIDE_BYTES: u32 = (font::GLYPH_WIDTH * 2) as u32;

static mut GLYPH_BUFFER: [u16; GLYPH_PIXELS] = [0; GLYPH_PIXELS];

static mut COLUMNS: usize = 0;
static mut ROWS: usize = 0;
static mut CURSOR_X: usize = 0;
static mut CURSOR_Y: usize = 0;

pub fn init() {
    let width = gpu::width().max(0) as usize;
    let height = gpu::height().max(0) as usize;
    unsafe {
        COLUMNS = (width / font::CELL_WIDTH).max(1);
        ROWS = (height / font::CELL_HEIGHT).max(1);
        CURSOR_X = 0;
        CURSOR_Y = 0;
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
        render_glyph(byte);
        blit_glyph(x, y);
        CURSOR_X += 1;
    }
}

fn newline() {
    unsafe {
        CURSOR_X = 0;
        if CURSOR_Y + 1 < ROWS {
            CURSOR_Y += 1;
        } else {
            CURSOR_Y = 0;
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
        render_glyph(b' ');
        blit_glyph(x, y);
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
