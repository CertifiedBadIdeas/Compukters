#![no_std]
#![no_main]

extern crate k16_rt;

use core::panic::PanicInfo;

use k16_abi::computer::{control, debug, display0, status};

#[no_mangle]
pub extern "C" fn _start() -> ! {
    clear_display();
    print_display_line(0, b"K16 BIOS");
    print_display_line(2, b"No bootable device");
    print_debug(b"K16 BIOS\nNO BOOTABLE DEVICE\n");
    set_halted();
    wait_forever()
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    print_debug(b"K16 BIOS PANIC\n");
    unsafe {
        write_i32(control::PANIC_CODE, status::PANIC);
        write_i32(control::STATUS, status::PANIC);
    }
    wait_forever()
}

fn print_debug(bytes: &[u8]) {
    let mut index = 0;
    while index < bytes.len() {
        unsafe {
            write_u8(debug::WRITE, *bytes.as_ptr().add(index));
        }
        index += 1;
    }
}

fn clear_display() {
    unsafe {
        write_i32(display0::COMMAND, display0::COMMAND_CLEAR);
    }
}

fn print_display_line(row: i32, bytes: &[u8]) {
    unsafe {
        write_i32(display0::CURSOR_X, 0);
        write_i32(display0::CURSOR_Y, row);
    }
    let mut index = 0;
    while index < bytes.len() {
        unsafe {
            write_u8(display0::DATA, *bytes.as_ptr().add(index));
            write_i32(display0::COMMAND, display0::COMMAND_PUT_BYTE_AT_CURSOR);
        }
        index += 1;
    }
}

fn set_halted() {
    unsafe {
        write_i32(control::PANIC_CODE, status::READY);
        write_i32(control::STATUS, status::HALTED);
    }
}

unsafe fn write_i32(address: u32, value: i32) {
    unsafe {
        *(address as usize as *mut i32) = value;
    }
}

unsafe fn write_u8(address: u32, value: u8) {
    unsafe {
        *(address as usize as *mut u8) = value;
    }
}

fn wait_forever() -> ! {
    k16_rt::halt_forever()
}
