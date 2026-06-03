#![no_std]
#![no_main]

extern crate k16_rt;

use core::panic::PanicInfo;
use k16_abi::computer::{control, debug, status};
use k16_boot_chain::{enter_loaded_image, load_k16e_from_storage0, K16eAbiKind};

#[no_mangle]
pub extern "C" fn _start() -> ! {
    print_boot_debug();
    let image = unsafe {
        load_k16e_from_storage0(
            b"ROOT",
            &[b"boot".as_slice(), b"kernel.kx".as_slice()],
            K16eAbiKind::Kernel,
        )
    };
    match image {
        Ok(image) => unsafe { enter_loaded_image(image) },
        Err(error) => {
            set_halted(error.code());
            wait_forever()
        }
    }
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    print_boot_panic_debug();
    set_panic();
    wait_forever()
}

fn print_boot_debug() {
    print_debug_byte(b'K');
    print_debug_byte(b'1');
    print_debug_byte(b'6');
    print_debug_byte(b' ');
    print_debug_byte(b'B');
    print_debug_byte(b'O');
    print_debug_byte(b'O');
    print_debug_byte(b'T');
    print_debug_byte(b'\n');
}

fn print_boot_panic_debug() {
    print_debug_byte(b'K');
    print_debug_byte(b'1');
    print_debug_byte(b'6');
    print_debug_byte(b' ');
    print_debug_byte(b'B');
    print_debug_byte(b'O');
    print_debug_byte(b'O');
    print_debug_byte(b'T');
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
