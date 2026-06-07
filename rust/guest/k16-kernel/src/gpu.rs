use k16_abi::computer::gpu0;

use crate::mmio;

pub fn width() -> i32 {
    unsafe { mmio::read_i32(gpu0::WIDTH) }
}

pub fn height() -> i32 {
    unsafe { mmio::read_i32(gpu0::HEIGHT) }
}

pub fn clear(color: u16) {
    unsafe {
        mmio::write_i32(gpu0::COLOR, i32::from(color));
        mmio::write_i32(gpu0::COMMAND, gpu0::COMMAND_CLEAR);
    }
}

pub fn blit_buffer(x: i32, y: i32, width: i32, height: i32, buffer_addr: u32, stride_bytes: u32) {
    unsafe {
        mmio::write_i32(gpu0::X, x);
        mmio::write_i32(gpu0::Y, y);
        mmio::write_i32(gpu0::RECT_WIDTH, width);
        mmio::write_i32(gpu0::RECT_HEIGHT, height);
        mmio::write_i32(
            gpu0::BUFFER_ADDR,
            i32::from_le_bytes(buffer_addr.to_le_bytes()),
        );
        mmio::write_i32(
            gpu0::BUFFER_STRIDE_BYTES,
            i32::from_le_bytes(stride_bytes.to_le_bytes()),
        );
        mmio::write_i32(gpu0::COMMAND, gpu0::COMMAND_BLIT_BUFFER);
    }
}

pub fn present() {
    unsafe {
        mmio::write_i32(gpu0::COMMAND, gpu0::COMMAND_PRESENT);
    }
}
