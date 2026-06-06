use k16_abi::computer::debug as debug_mmio;

use crate::mmio;

pub fn print_byte(byte: u8) {
    unsafe {
        mmio::write_u8(debug_mmio::WRITE, byte);
    }
}

pub fn print_kernel_ok() {
    print_bytes(b"KERNEL OK\n");
}

pub fn print_kernel_panic() {
    print_bytes(b"K16 KERNEL PANIC\n");
}

pub fn print_kernel_trap() {
    print_bytes(b"K16 KERNEL TRAP\n");
}

fn print_bytes(bytes: &[u8]) {
    for &byte in bytes {
        print_byte(byte);
    }
}
