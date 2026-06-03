#![no_std]
#![no_main]

extern crate k16_rt;

use core::panic::PanicInfo;
use k16_abi::computer::{control, debug, display0, status};

#[no_mangle]
pub extern "C" fn _start() -> ! {
    clear_display();
    print_kernel_ok_display();
    print_kernel_ok_debug();
    set_halted(75);
    wait_forever()
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    print_kernel_panic_debug();
    set_panic();
    wait_forever()
}

fn clear_display() {
    unsafe {
        write_i32(display0::COMMAND, display0::COMMAND_CLEAR);
    }
}

fn print_kernel_ok_display() {
    unsafe {
        write_i32(display0::CURSOR_X, 0);
        write_i32(display0::CURSOR_Y, 0);
    }
    print_display_byte(b'K');
    print_display_byte(b'E');
    print_display_byte(b'R');
    print_display_byte(b'N');
    print_display_byte(b'E');
    print_display_byte(b'L');
    print_display_byte(b' ');
    print_display_byte(b'O');
    print_display_byte(b'K');
}

fn print_display_byte(byte: u8) {
    unsafe {
        write_u8(display0::DATA, byte);
        write_i32(display0::COMMAND, display0::COMMAND_PUT_BYTE_AT_CURSOR);
    }
}

fn print_kernel_ok_debug() {
    print_debug_byte(b'K');
    print_debug_byte(b'E');
    print_debug_byte(b'R');
    print_debug_byte(b'N');
    print_debug_byte(b'E');
    print_debug_byte(b'L');
    print_debug_byte(b' ');
    print_debug_byte(b'O');
    print_debug_byte(b'K');
    print_debug_byte(b'\n');
}

fn print_kernel_panic_debug() {
    print_debug_byte(b'K');
    print_debug_byte(b'1');
    print_debug_byte(b'6');
    print_debug_byte(b' ');
    print_debug_byte(b'K');
    print_debug_byte(b'E');
    print_debug_byte(b'R');
    print_debug_byte(b'N');
    print_debug_byte(b'E');
    print_debug_byte(b'L');
    print_debug_byte(b' ');
    print_debug_byte(b'P');
    print_debug_byte(b'A');
    print_debug_byte(b'N');
    print_debug_byte(b'I');
    print_debug_byte(b'C');
    print_debug_byte(b'\n');
}

fn print_debug_byte(byte: u8) {
    unsafe {
        write_u8(debug::WRITE, byte);
    }
}

fn set_halted(code: i32) {
    unsafe {
        write_i32(control::PANIC_CODE, code);
        write_i32(control::STATUS, status::HALTED);
    }
}

fn set_panic() {
    unsafe {
        write_i32(control::PANIC_CODE, status::PANIC);
        write_i32(control::STATUS, status::PANIC);
    }
}

unsafe fn write_i32(address: u32, value: i32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut i32, value) }
}

unsafe fn write_u8(address: u32, value: u8) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u8, value) }
}

fn wait_forever() -> ! {
    k16_rt::halt_forever()
}
