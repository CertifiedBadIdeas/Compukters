use crate::generated::terminal_font;

pub const GLYPH_WIDTH: usize = terminal_font::GLYPH_WIDTH;
pub const GLYPH_HEIGHT: usize = terminal_font::GLYPH_HEIGHT;
pub const CELL_WIDTH: usize = terminal_font::CELL_WIDTH;
pub const CELL_HEIGHT: usize = terminal_font::CELL_HEIGHT;

pub fn glyph(byte: u8) -> [u8; GLYPH_HEIGHT] {
    if byte > terminal_font::TERMINAL_FONT_LAST {
        return terminal_font::FALLBACK_ROWS;
    }
    terminal_font::TERMINAL_FONT_ROWS[byte as usize]
}
