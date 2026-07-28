use crate::computer::stats::K16ComputerGpuStatsSnapshot;
use crate::computer_abi;
use crate::display::{DisplayEngine, DisplayFrameDelta, PixelFormat};
use crate::low_bus::MmioDevice;
use crate::low_machine::{MachineMemory, MemoryFault};

pub(crate) struct GpuDevice {
    display: DisplayEngine,
    pending_frames: Vec<DisplayFrameDelta>,
    status: i32,
    error: i32,
    x: i32,
    y: i32,
    src_x: i32,
    src_y: i32,
    rect_width: i32,
    rect_height: i32,
    buffer_addr: u32,
    buffer_stride_bytes: u32,
    color: u16,
    background_color: u16,
    sequence: u64,
    stats: K16ComputerGpuStatsSnapshot,
}

impl GpuDevice {
    pub(crate) const SIZE: u32 = computer_abi::GPU0_SIZE;
    const DISPLAY_ID: i32 = 1;
    const WIDTH: i32 = 320;
    const HEIGHT: i32 = 200;
    const BYTES_PER_PIXEL: u32 = 2;

    pub(crate) fn new() -> Self {
        Self {
            display: DisplayEngine::new(
                Self::DISPLAY_ID,
                Self::WIDTH,
                Self::HEIGHT,
                PixelFormat::Rgb565,
            )
            .expect("gpu0 default geometry must be valid"),
            pending_frames: Vec::new(),
            status: computer_abi::GPU0_STATUS_READY,
            error: computer_abi::GPU0_ERROR_NONE,
            x: 0,
            y: 0,
            src_x: 0,
            src_y: 0,
            rect_width: Self::WIDTH,
            rect_height: Self::HEIGHT,
            buffer_addr: 0,
            buffer_stride_bytes: (Self::WIDTH as u32) * Self::BYTES_PER_PIXEL,
            color: 0,
            background_color: 0,
            sequence: 0,
            stats: K16ComputerGpuStatsSnapshot::default(),
        }
    }

    pub(crate) fn drain_frames(&mut self) -> Vec<DisplayFrameDelta> {
        std::mem::take(&mut self.pending_frames)
    }

    pub(crate) fn stats_snapshot(&self) -> K16ComputerGpuStatsSnapshot {
        self.stats
    }

    fn load_register(&self, offset: u32) -> Result<i32, MemoryFault> {
        match offset {
            0 => Ok(Self::WIDTH),
            4 => Ok(Self::HEIGHT),
            8 => Ok((Self::WIDTH as u32 * Self::BYTES_PER_PIXEL) as i32),
            12 => Ok(computer_abi::GPU0_PIXEL_FORMAT_RGB565),
            20 => Ok(self.status),
            24 => Ok(self.error),
            28 => Ok(self.x),
            32 => Ok(self.y),
            36 => Ok(self.rect_width),
            40 => Ok(self.rect_height),
            44 => Ok(self.buffer_addr as i32),
            48 => Ok(self.buffer_stride_bytes as i32),
            52 => Ok(i32::from(self.color)),
            56 => Ok((self.sequence as u32) as i32),
            60 => Ok((self.sequence >> 32) as u32 as i32),
            64 => Ok(self.src_x),
            68 => Ok(self.src_y),
            72 => Ok(i32::from(self.background_color)),
            _ => Err(MemoryFault::new(format!(
                "computer gpu0 offset {offset} is not readable",
            ))),
        }
    }

    fn store_register(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        match offset {
            16 => self.execute_command(value, None),
            28 => {
                self.x = value;
                Ok(())
            }
            32 => {
                self.y = value;
                Ok(())
            }
            36 => {
                self.rect_width = value;
                Ok(())
            }
            40 => {
                self.rect_height = value;
                Ok(())
            }
            44 => {
                self.buffer_addr = u32::from_le_bytes(value.to_le_bytes());
                Ok(())
            }
            48 => {
                self.buffer_stride_bytes = u32::from_le_bytes(value.to_le_bytes());
                Ok(())
            }
            52 => {
                self.color = u16::from_le_bytes([value.to_le_bytes()[0], value.to_le_bytes()[1]]);
                Ok(())
            }
            64 => {
                self.src_x = value;
                Ok(())
            }
            68 => {
                self.src_y = value;
                Ok(())
            }
            72 => {
                self.background_color =
                    u16::from_le_bytes([value.to_le_bytes()[0], value.to_le_bytes()[1]]);
                Ok(())
            }
            _ => Err(MemoryFault::new(format!(
                "computer gpu0 offset {offset} is not writable",
            ))),
        }
    }

    fn execute_command(
        &mut self,
        command: i32,
        memory: Option<&mut MachineMemory>,
    ) -> Result<(), MemoryFault> {
        self.error = computer_abi::GPU0_ERROR_NONE;
        self.status = computer_abi::GPU0_STATUS_READY;
        match command {
            computer_abi::GPU0_COMMAND_NOP => Ok(()),
            computer_abi::GPU0_COMMAND_CLEAR => {
                self.display.clear(self.color);
                self.status = computer_abi::GPU0_STATUS_DONE;
                Ok(())
            }
            computer_abi::GPU0_COMMAND_BLIT_BUFFER => {
                let Some(memory) = memory else {
                    self.set_error(computer_abi::GPU0_ERROR_BUFFER_OUT_OF_BOUNDS);
                    return Ok(());
                };
                self.blit_buffer(memory);
                Ok(())
            }
            computer_abi::GPU0_COMMAND_BLIT_MONO_BUFFER => {
                let Some(memory) = memory else {
                    self.set_error(computer_abi::GPU0_ERROR_BUFFER_OUT_OF_BOUNDS);
                    return Ok(());
                };
                self.blit_mono_buffer(memory);
                Ok(())
            }
            computer_abi::GPU0_COMMAND_FILL_RECT => {
                if self.rect_width <= 0 || self.rect_height <= 0 {
                    self.set_error(computer_abi::GPU0_ERROR_INVALID_RECT);
                    return Ok(());
                }
                self.display.fill_rect(
                    self.x,
                    self.y,
                    self.rect_width,
                    self.rect_height,
                    self.color,
                );
                self.status = computer_abi::GPU0_STATUS_DONE;
                Ok(())
            }
            computer_abi::GPU0_COMMAND_COPY_RECT => {
                if self.rect_width <= 0 || self.rect_height <= 0 {
                    self.set_error(computer_abi::GPU0_ERROR_INVALID_RECT);
                    return Ok(());
                }
                self.display.copy_rect(
                    self.src_x,
                    self.src_y,
                    self.rect_width,
                    self.rect_height,
                    self.x,
                    self.y,
                );
                self.status = computer_abi::GPU0_STATUS_DONE;
                Ok(())
            }
            computer_abi::GPU0_COMMAND_PRESENT => {
                self.stats.present_commands += 1;
                if let Some(frame) = self.display.present() {
                    self.sequence = frame.sequence as u64;
                    self.stats.frames += 1;
                    self.stats.frame_tiles += frame.tiles.len() as u64;
                    self.stats.frame_payload_bytes += frame
                        .tiles
                        .iter()
                        .map(|tile| tile.payload.len() as u64)
                        .sum::<u64>();
                    self.stats.frame_mono_payload_bytes += frame
                        .operations
                        .iter()
                        .map(|operation| match operation {
                            crate::display::DisplayFrameOperation::MonoBlit {
                                packed_mask, ..
                            } => packed_mask.len() as u64,
                            _ => 0,
                        })
                        .sum::<u64>();
                    self.pending_frames.push(frame);
                }
                self.status = computer_abi::GPU0_STATUS_DONE;
                Ok(())
            }
            _ => {
                self.set_error(computer_abi::GPU0_ERROR_INVALID_COMMAND);
                Ok(())
            }
        }
    }

    fn blit_buffer(&mut self, memory: &MachineMemory) {
        if self.rect_width <= 0 || self.rect_height <= 0 {
            self.set_error(computer_abi::GPU0_ERROR_INVALID_RECT);
            return;
        }
        let min_stride = match u32::try_from(self.rect_width)
            .ok()
            .and_then(|width| width.checked_mul(Self::BYTES_PER_PIXEL))
        {
            Some(value) => value,
            None => {
                self.set_error(computer_abi::GPU0_ERROR_INVALID_RECT);
                return;
            }
        };
        if self.buffer_stride_bytes < min_stride {
            self.set_error(computer_abi::GPU0_ERROR_INVALID_STRIDE);
            return;
        }
        let rows = self.rect_height as u32;
        let last_row_offset = match rows
            .checked_sub(1)
            .and_then(|row| row.checked_mul(self.buffer_stride_bytes))
        {
            Some(value) => value,
            None => {
                self.set_error(computer_abi::GPU0_ERROR_BUFFER_OUT_OF_BOUNDS);
                return;
            }
        };
        let byte_len = match last_row_offset.checked_add(min_stride) {
            Some(value) => value,
            None => {
                self.set_error(computer_abi::GPU0_ERROR_BUFFER_OUT_OF_BOUNDS);
                return;
            }
        };
        if !ram_range_in_bounds(self.buffer_addr, byte_len, memory.len()) {
            self.set_error(computer_abi::GPU0_ERROR_BUFFER_OUT_OF_BOUNDS);
            return;
        }
        self.stats.blit_buffer_commands += 1;
        self.stats.blit_pixels += (self.rect_width as u64) * (self.rect_height as u64);
        self.stats.blit_source_bytes += u64::from(byte_len);
        let buffer_addr = self.buffer_addr;
        let buffer_stride_bytes = self.buffer_stride_bytes;
        self.display.blit_rgb565_rect(
            self.x,
            self.y,
            self.rect_width,
            self.rect_height,
            |col, row| {
                let row_offset = row as u32 * buffer_stride_bytes;
                let source = buffer_addr + row_offset + col as u32 * Self::BYTES_PER_PIXEL;
                let lo = memory
                    .load_u8(source)
                    .expect("gpu0 source range was prevalidated");
                let hi = memory
                    .load_u8(source + 1)
                    .expect("gpu0 source range was prevalidated");
                u16::from_le_bytes([lo, hi])
            },
        );
        self.status = computer_abi::GPU0_STATUS_DONE;
    }

    fn blit_mono_buffer(&mut self, memory: &MachineMemory) {
        if self.rect_width <= 0 || self.rect_height <= 0 {
            self.set_error(computer_abi::GPU0_ERROR_INVALID_RECT);
            return;
        }
        let row_bytes = match u32::try_from(self.rect_width)
            .ok()
            .and_then(|width| width.checked_add(7))
            .map(|width| width / 8)
        {
            Some(value) => value,
            None => {
                self.set_error(computer_abi::GPU0_ERROR_INVALID_RECT);
                return;
            }
        };
        if self.buffer_stride_bytes < row_bytes {
            self.set_error(computer_abi::GPU0_ERROR_INVALID_STRIDE);
            return;
        }
        let rows = self.rect_height as u32;
        let last_row_offset = match rows
            .checked_sub(1)
            .and_then(|row| row.checked_mul(self.buffer_stride_bytes))
        {
            Some(value) => value,
            None => {
                self.set_error(computer_abi::GPU0_ERROR_BUFFER_OUT_OF_BOUNDS);
                return;
            }
        };
        let source_range_len = match last_row_offset.checked_add(row_bytes) {
            Some(value) => value,
            None => {
                self.set_error(computer_abi::GPU0_ERROR_BUFFER_OUT_OF_BOUNDS);
                return;
            }
        };
        let tight_len = match row_bytes.checked_mul(rows) {
            Some(value) => value,
            None => {
                self.set_error(computer_abi::GPU0_ERROR_BUFFER_OUT_OF_BOUNDS);
                return;
            }
        };
        if !ram_range_in_bounds(self.buffer_addr, source_range_len, memory.len()) {
            self.set_error(computer_abi::GPU0_ERROR_BUFFER_OUT_OF_BOUNDS);
            return;
        }
        let mut packed_mask = Vec::with_capacity(tight_len as usize);
        for row in 0..rows {
            let row_address = self.buffer_addr + row * self.buffer_stride_bytes;
            for byte in 0..row_bytes {
                packed_mask.push(
                    memory
                        .load_u8(row_address + byte)
                        .expect("gpu0 mono source range was prevalidated"),
                );
            }
        }
        self.stats.blit_mono_commands += 1;
        self.stats.blit_mono_pixels += (self.rect_width as u64) * (self.rect_height as u64);
        self.stats.blit_mono_source_bytes += u64::from(tight_len);
        self.display.blit_mono_mask(
            self.x,
            self.y,
            self.rect_width,
            self.rect_height,
            &packed_mask,
            self.color,
            self.background_color,
        );
        self.status = computer_abi::GPU0_STATUS_DONE;
    }

    fn set_error(&mut self, error: i32) {
        self.status = computer_abi::GPU0_STATUS_ERROR;
        self.error = error;
    }
}

impl MmioDevice for GpuDevice {
    fn size(&self) -> u32 {
        Self::SIZE
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        self.load_register(offset)
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        self.store_register(offset, value)
    }

    fn store_i32_with_memory(
        &mut self,
        offset: u32,
        value: i32,
        memory: &mut MachineMemory,
    ) -> Result<(), MemoryFault> {
        if offset == 16 {
            return self.execute_command(value, Some(memory));
        }
        self.store_register(offset, value)
    }
}

fn ram_range_in_bounds(address: u32, byte_len: u32, ram_len: usize) -> bool {
    let Some(end) = address.checked_add(byte_len) else {
        return false;
    };
    let start = address as usize;
    let end = end as usize;
    start <= ram_len && end <= ram_len
}
