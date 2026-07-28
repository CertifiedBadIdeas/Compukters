use k16_abi::computer::gpu0;

use crate::mmio;

pub fn clear(color: u16) {
    unsafe {
        mmio::write_i32(gpu0::COLOR, color as i32);
        mmio::write_i32(gpu0::COMMAND, gpu0::COMMAND_CLEAR);
    }
}

pub fn blit_buffer(x: i32, y: i32, width: i32, height: i32, buffer_addr: u32, stride_bytes: u32) {
    unsafe {
        mmio::write_i32(gpu0::X, x);
        mmio::write_i32(gpu0::Y, y);
        mmio::write_i32(gpu0::RECT_WIDTH, width);
        mmio::write_i32(gpu0::RECT_HEIGHT, height);
        mmio::write_i32(gpu0::BUFFER_ADDR, buffer_addr as i32);
        mmio::write_i32(gpu0::BUFFER_STRIDE_BYTES, stride_bytes as i32);
        mmio::write_i32(gpu0::COMMAND, gpu0::COMMAND_BLIT_BUFFER);
    }
}

#[allow(clippy::too_many_arguments)]
pub fn blit_mono_buffer(
    x: i32,
    y: i32,
    width: i32,
    height: i32,
    buffer_addr: u32,
    stride_bytes: u32,
    foreground: u16,
    background: u16,
) {
    unsafe {
        mmio::write_i32(gpu0::X, x);
        mmio::write_i32(gpu0::Y, y);
        mmio::write_i32(gpu0::RECT_WIDTH, width);
        mmio::write_i32(gpu0::RECT_HEIGHT, height);
        mmio::write_i32(gpu0::BUFFER_ADDR, buffer_addr as i32);
        mmio::write_i32(gpu0::BUFFER_STRIDE_BYTES, stride_bytes as i32);
        mmio::write_i32(gpu0::COLOR, foreground as i32);
        mmio::write_i32(gpu0::BACKGROUND_COLOR, background as i32);
        mmio::write_i32(gpu0::COMMAND, gpu0::COMMAND_BLIT_MONO_BUFFER);
    }
}

pub fn fill_rect(x: i32, y: i32, width: i32, height: i32, color: u16) {
    unsafe {
        mmio::write_i32(gpu0::X, x);
        mmio::write_i32(gpu0::Y, y);
        mmio::write_i32(gpu0::RECT_WIDTH, width);
        mmio::write_i32(gpu0::RECT_HEIGHT, height);
        mmio::write_i32(gpu0::COLOR, color as i32);
        mmio::write_i32(gpu0::COMMAND, gpu0::COMMAND_FILL_RECT);
    }
}

pub fn copy_rect(src_x: i32, src_y: i32, width: i32, height: i32, dst_x: i32, dst_y: i32) {
    unsafe {
        mmio::write_i32(gpu0::SRC_X, src_x);
        mmio::write_i32(gpu0::SRC_Y, src_y);
        mmio::write_i32(gpu0::X, dst_x);
        mmio::write_i32(gpu0::Y, dst_y);
        mmio::write_i32(gpu0::RECT_WIDTH, width);
        mmio::write_i32(gpu0::RECT_HEIGHT, height);
        mmio::write_i32(gpu0::COMMAND, gpu0::COMMAND_COPY_RECT);
    }
}

pub fn present() {
    unsafe {
        mmio::write_i32(gpu0::COMMAND, gpu0::COMMAND_PRESENT);
    }
}
