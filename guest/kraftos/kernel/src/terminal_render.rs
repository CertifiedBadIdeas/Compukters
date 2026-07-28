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
const ROW_MASK_STRIDE: usize = TERMINAL_WIDTH.div_ceil(8);
const ROW_MASK_BYTES: usize = ROW_MASK_STRIDE * CELL_HEIGHT;

static mut ROW_MASK: [u8; ROW_MASK_BYTES] = [0; ROW_MASK_BYTES];

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
    unsafe {
        let mask = core::slice::from_raw_parts_mut(
            core::ptr::addr_of_mut!(ROW_MASK).cast::<u8>(),
            ROW_MASK_BYTES,
        );
        render_glyph_run_mask(&bytes[..run_len], mask, ROW_MASK_STRIDE);
    }
    blit_glyph_run(column, row, run_len);
}

pub fn flush() {
    gpu::present();
}

fn render_glyph_run_mask(bytes: &[u8], mask: &mut [u8], stride: usize) {
    let pixel_width = bytes.len() * CELL_WIDTH;
    let used_row_bytes = pixel_width.div_ceil(8);
    assert!(stride >= used_row_bytes);
    assert!(mask.len() >= stride * CELL_HEIGHT);
    let mut row = 0;
    while row < CELL_HEIGHT {
        let mut byte = 0;
        while byte < used_row_bytes {
            mask[row * stride + byte] = 0;
            byte += 1;
        }
        row += 1;
    }
    let mut index = 0;
    while index < bytes.len() {
        render_glyph_into_mask(mask, stride, index, bytes[index]);
        index += 1;
    }
}

fn render_glyph_into_mask(mask: &mut [u8], stride: usize, index: usize, byte: u8) {
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
            mask[target_row * stride + target_col / 8] |= 0x80 >> (target_col % 8);
            col += 1;
        }
        row += 1;
    }
}

fn blit_glyph_run(column: usize, row: usize, run_len: usize) {
    let x = column * font::CELL_WIDTH;
    let y = row * font::CELL_HEIGHT;
    let buffer_addr = core::ptr::addr_of!(ROW_MASK) as u32;
    gpu::blit_mono_buffer(
        x as i32,
        y as i32,
        (run_len * font::CELL_WIDTH) as i32,
        font::CELL_HEIGHT as i32,
        buffer_addr,
        ROW_MASK_STRIDE as u32,
        FOREGROUND,
        BACKGROUND,
    );
}

#[cfg(test)]
mod tests {
    use super::{render_glyph_run_mask, CELL_HEIGHT};

    #[test]
    fn glyph_a_is_packed_msb_first() {
        let mut mask = [0xff; CELL_HEIGHT];

        render_glyph_run_mask(b"A", &mut mask, 1);

        assert_eq!(mask, [0x00, 0x60, 0x90, 0x90, 0xf0, 0x90, 0x90, 0x00],);
    }

    #[test]
    fn adjacent_glyphs_share_bytes_without_dirtying_unused_bits() {
        let mut mask = [0xff; CELL_HEIGHT * 2];

        render_glyph_run_mask(b"AB", &mut mask, 2);

        assert_eq!(
            mask,
            [
                0x00, 0x00, 0x67, 0x00, 0x94, 0x80, 0x97, 0x00, 0xf4, 0x80, 0x94, 0x80, 0x97, 0x00,
                0x00, 0x00,
            ],
        );
        assert!(mask.chunks_exact(2).all(|row| row[1] & 0x3f == 0));
    }
}
