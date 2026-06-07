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

pub fn present() {
    unsafe {
        mmio::write_i32(gpu0::COMMAND, gpu0::COMMAND_PRESENT);
    }
}
