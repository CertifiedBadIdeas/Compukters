use k16_abi::computer::display0;

use crate::mmio;

pub fn clear() {
    unsafe {
        mmio::write_i32(display0::COMMAND, display0::COMMAND_CLEAR);
    }
}

pub fn print_kernel_ok() {
    unsafe {
        mmio::write_i32(display0::CURSOR_X, 0);
        mmio::write_i32(display0::CURSOR_Y, 0);
    }
    print_bytes(b"KERNEL OK");
}

fn print_bytes(bytes: &[u8]) {
    for &byte in bytes {
        print_byte(byte);
    }
}

fn print_byte(byte: u8) {
    unsafe {
        mmio::write_u8(display0::DATA, byte);
        mmio::write_i32(display0::COMMAND, display0::COMMAND_PUT_BYTE_AT_CURSOR);
    }
}
