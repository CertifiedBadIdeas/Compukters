use crate::generated::font_mono5x7;

pub const GLYPH_WIDTH: usize = font_mono5x7::GLYPH_WIDTH;
pub const GLYPH_HEIGHT: usize = font_mono5x7::GLYPH_HEIGHT;
pub const CELL_WIDTH: usize = font_mono5x7::CELL_WIDTH;
pub const CELL_HEIGHT: usize = font_mono5x7::CELL_HEIGHT;

pub fn glyph(byte: u8) -> [u8; GLYPH_HEIGHT] {
    if byte > font_mono5x7::MONO5X7_LAST {
        return font_mono5x7::FALLBACK_ROWS;
    }
    font_mono5x7::MONO5X7_ROWS[byte as usize]
}
