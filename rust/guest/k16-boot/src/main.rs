#![no_std]
#![no_main]

extern crate k16_rt;

use core::panic::PanicInfo;
use k16_abi::computer::{control, debug, status};

#[no_mangle]
pub extern "C" fn _start() -> ! {
    print_debug(b"K16 BOOT\n");
    set_halted(66);
    wait_forever()
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    print_debug(b"K16 BOOT PANIC\n");
    set_panic();
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
