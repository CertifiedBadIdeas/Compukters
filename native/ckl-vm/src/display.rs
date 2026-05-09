use std::collections::{BTreeMap, BTreeSet};

const TILE_SIZE: i32 = 16;
const BYTES_PER_PIXEL_RGB565: usize = 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PixelFormat {
    Rgb565,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DisplayTile {
    pub tile_x: i32,
    pub tile_y: i32,
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DisplayFrameDelta {
    pub display_id: i32,
    pub sequence: i64,
    pub width: i32,
    pub height: i32,
    pub pixel_format: PixelFormat,
    pub full_refresh: bool,
    pub tiles: Vec<DisplayTile>,
}

pub struct DisplayEngine {
    display_id: i32,
    width: i32,
    height: i32,
    pixel_format: PixelFormat,
    pixels: Vec<u16>,
    dirty_tiles: BTreeSet<(i32, i32)>,
    sequence: i64,
}

impl DisplayEngine {
    pub fn new(
        display_id: i32,
        width: i32,
        height: i32,
        pixel_format: PixelFormat,
    ) -> Result<Self, String> {
        if width <= 0 || height <= 0 {
            return Err(format!(
                "display size must be positive, got {width}x{height}"
            ));
        }
        let pixel_count = width
            .checked_mul(height)
            .ok_or_else(|| "display size overflows i32".to_string())?;
        let len =
            usize::try_from(pixel_count).map_err(|_| "display size overflows usize".to_string())?;
        Ok(Self {
            display_id,
            width,
            height,
            pixel_format,
            pixels: vec![0; len],
            dirty_tiles: BTreeSet::new(),
            sequence: 0,
        })
    }

    pub fn clear(&mut self, rgb565: u16) {
        self.pixels.fill(rgb565);
        self.mark_all_dirty();
    }

    pub fn set_pixel(&mut self, x: i32, y: i32, rgb565: u16) {
        if !self.in_bounds(x, y) {
            return;
        }
        let index = self.index(x, y);
        self.pixels[index] = rgb565;
        self.mark_rect_dirty(x, y, 1, 1);
    }

    pub fn fill_rect(&mut self, x: i32, y: i32, width: i32, height: i32, rgb565: u16) {
        if width <= 0 || height <= 0 {
            return;
        }
        for row in y.max(0)..(y + height).min(self.height) {
            for col in x.max(0)..(x + width).min(self.width) {
                let index = self.index(col, row);
                self.pixels[index] = rgb565;
            }
        }
        self.mark_rect_dirty(x, y, width, height);
    }

    pub fn copy_rect(
        &mut self,
        src_x: i32,
        src_y: i32,
        width: i32,
        height: i32,
        dst_x: i32,
        dst_y: i32,
    ) {
        if width <= 0 || height <= 0 {
            return;
        }
        let mut copied = Vec::with_capacity((width * height) as usize);
        for row in 0..height {
            for col in 0..width {
                let sx = src_x + col;
                let sy = src_y + row;
                copied.push(if self.in_bounds(sx, sy) {
                    self.pixels[self.index(sx, sy)]
                } else {
                    0
                });
            }
        }
        for row in 0..height {
            for col in 0..width {
                let dx = dst_x + col;
                let dy = dst_y + row;
                if self.in_bounds(dx, dy) {
                    let target = self.index(dx, dy);
                    self.pixels[target] = copied[(row * width + col) as usize];
                }
            }
        }
        self.mark_rect_dirty(dst_x, dst_y, width, height);
    }

    pub fn blit_mono(
        &mut self,
        x: i32,
        y: i32,
        width: i32,
        height: i32,
        mask: &str,
        foreground: u16,
        background: Option<u16>,
    ) {
        if width <= 0 || height <= 0 {
            return;
        }
        let bytes = mask.as_bytes();
        for row in 0..height {
            for col in 0..width {
                let target_x = x + col;
                let target_y = y + row;
                if !self.in_bounds(target_x, target_y) {
                    continue;
                }
                let mask_index = (row * width + col) as usize;
                let bit = bytes.get(mask_index).copied().unwrap_or(b'0');
                if bit == b'1' {
                    let index = self.index(target_x, target_y);
                    self.pixels[index] = foreground;
                } else if let Some(background) = background {
                    let index = self.index(target_x, target_y);
                    self.pixels[index] = background;
                }
            }
        }
        self.mark_rect_dirty(x, y, width, height);
    }

    pub fn blit_mono5x7_text(
        &mut self,
        x: i32,
        y: i32,
        text: &str,
        foreground: u16,
        background: Option<u16>,
    ) {
        if text.is_empty() {
            return;
        }
        for (index, ch) in text.chars().enumerate() {
            self.blit_mono5x7_packed(
                x + index as i32 * 6,
                y,
                mono5x7_glyph(ch),
                foreground,
                background,
            );
        }
        let dirty_width = (text.chars().count() as i32 - 1) * 6 + 5;
        self.mark_rect_dirty(x, y, dirty_width, 7);
    }

    pub fn blit_mono5x7_packed(
        &mut self,
        x: i32,
        y: i32,
        glyph: u64,
        foreground: u16,
        background: Option<u16>,
    ) {
        for row in 0..7 {
            let bits = ((glyph >> ((6 - row) * 5)) & 0b11111) as i32;
            for col in 0..5 {
                let target_x = x + col;
                let target_y = y + row;
                if !self.in_bounds(target_x, target_y) {
                    continue;
                }
                if bits & (1 << (4 - col)) != 0 {
                    let index = self.index(target_x, target_y);
                    self.pixels[index] = foreground;
                } else if let Some(background) = background {
                    let index = self.index(target_x, target_y);
                    self.pixels[index] = background;
                }
            }
        }
        self.mark_rect_dirty(x, y, 5, 7);
    }

    pub fn present(&mut self) -> Option<DisplayFrameDelta> {
        if self.dirty_tiles.is_empty() {
            return None;
        }
        self.sequence += 1;
        let tiles = self.build_tiles();
        self.dirty_tiles.clear();
        Some(DisplayFrameDelta {
            display_id: self.display_id,
            sequence: self.sequence,
            width: self.width,
            height: self.height,
            pixel_format: self.pixel_format,
            full_refresh: false,
            tiles,
        })
    }

    pub fn full_refresh(&mut self) -> Option<DisplayFrameDelta> {
        self.mark_all_dirty();
        let mut frame = self.present()?;
        frame.full_refresh = true;
        Some(frame)
    }

    fn build_tiles(&self) -> Vec<DisplayTile> {
        self.dirty_tiles
            .iter()
            .map(|&(tile_x, tile_y)| {
                let x = tile_x * TILE_SIZE;
                let y = tile_y * TILE_SIZE;
                let width = TILE_SIZE.min(self.width - x);
                let height = TILE_SIZE.min(self.height - y);
                let mut payload =
                    Vec::with_capacity(width as usize * height as usize * BYTES_PER_PIXEL_RGB565);
                for row in y..y + height {
                    for col in x..x + width {
                        let value = self.pixels[self.index(col, row)];
                        payload.push((value >> 8) as u8);
                        payload.push(value as u8);
                    }
                }
                DisplayTile {
                    tile_x,
                    tile_y,
                    x,
                    y,
                    width,
                    height,
                    payload,
                }
            })
            .collect()
    }

    fn mark_all_dirty(&mut self) {
        self.mark_rect_dirty(0, 0, self.width, self.height);
    }

    fn mark_rect_dirty(&mut self, x: i32, y: i32, width: i32, height: i32) {
        if width <= 0 || height <= 0 {
            return;
        }
        let min_x = x.max(0);
        let min_y = y.max(0);
        let max_x = (x + width - 1).min(self.width - 1);
        let max_y = (y + height - 1).min(self.height - 1);
        if min_x > max_x || min_y > max_y {
            return;
        }
        for tile_y in (min_y / TILE_SIZE)..=(max_y / TILE_SIZE) {
            for tile_x in (min_x / TILE_SIZE)..=(max_x / TILE_SIZE) {
                self.dirty_tiles.insert((tile_x, tile_y));
            }
        }
    }

    fn in_bounds(&self, x: i32, y: i32) -> bool {
        x >= 0 && y >= 0 && x < self.width && y < self.height
    }

    fn index(&self, x: i32, y: i32) -> usize {
        (y * self.width + x) as usize
    }
}

pub struct DeviceDisplayRegistry {
    displays: BTreeMap<i32, DisplayEngine>,
    pending_frames: Vec<DisplayFrameDelta>,
}

impl DeviceDisplayRegistry {
    pub fn new() -> Self {
        Self {
            displays: BTreeMap::new(),
            pending_frames: Vec::new(),
        }
    }

    pub fn attach(
        &mut self,
        display_id: i32,
        width: i32,
        height: i32,
        pixel_format: PixelFormat,
    ) -> Result<(), String> {
        let mut display = DisplayEngine::new(display_id, width, height, pixel_format)?;
        if let Some(frame) = display.full_refresh() {
            self.pending_frames.push(frame);
        }
        self.displays.insert(display_id, display);
        Ok(())
    }

    pub fn detach(&mut self, display_id: i32) {
        self.displays.remove(&display_id);
    }

    pub fn first_display_id(&self) -> Option<i32> {
        self.displays.keys().next().copied()
    }

    pub fn clear(
        &mut self,
        display_id: i32,
        rgb565: u16,
    ) {
        if let Some(display) = self.displays.get_mut(&display_id) {
            display.clear(rgb565);
        }
    }

    pub fn fill_rect(
        &mut self,
        display_id: i32,
        x: i32,
        y: i32,
        width: i32,
        height: i32,
        rgb565: u16,
    ) {
        if let Some(display) = self.displays.get_mut(&display_id) {
            display.fill_rect(x, y, width, height, rgb565);
        }
    }

    pub fn copy_rect(
        &mut self,
        display_id: i32,
        src_x: i32,
        src_y: i32,
        width: i32,
        height: i32,
        dst_x: i32,
        dst_y: i32,
    ) {
        if let Some(display) = self.displays.get_mut(&display_id) {
            display.copy_rect(src_x, src_y, width, height, dst_x, dst_y);
        }
    }

    pub fn blit_mono5x7_text(
        &mut self,
        display_id: i32,
        x: i32,
        y: i32,
        text: &str,
        foreground: u16,
        background: Option<u16>,
    ) {
        if let Some(display) = self.displays.get_mut(&display_id) {
            display.blit_mono5x7_text(x, y, text, foreground, background);
        }
    }

    pub fn present(&mut self, display_id: i32) {
        if let Some(display) = self.displays.get_mut(&display_id) {
            if let Some(frame) = display.present() {
                self.pending_frames.push(frame);
            }
        }
    }

    pub fn drain_frames(&mut self) -> Vec<DisplayFrameDelta> {
        std::mem::take(&mut self.pending_frames)
    }
}

fn mono5x7_glyph(ch: char) -> u64 {
    match ch {
        'A' => 0b01110100011000111111100011000110001,
        'B' => 0b11110100011000111110100011000111110,
        _ => 0b11111100011000110001100011000111111,
    }
}
