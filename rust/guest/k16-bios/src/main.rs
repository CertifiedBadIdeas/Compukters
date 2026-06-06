#![no_std]
#![no_main]

extern crate k16_rt;

use core::panic::PanicInfo;

use k16_abi::computer::{control, debug, display0, status};
use k16_boot_chain::{enter_loaded_image, load_k16e_from_storage0, K16eAbiKind};

#[no_mangle]
pub extern "C" fn _start() -> ! {
    set_booting();
    clear_display();
    print_bios_banner();
    print_bios_debug();
    k16_rt::sleep_ticks(1);

    let image = unsafe {
        load_k16e_from_storage0(
            b"BOOT",
            &[b"boot".as_slice(), b"loader.kb".as_slice()],
            K16eAbiKind::Bootloader,
        )
    };
    match image {
        Ok(image) => unsafe { enter_loaded_image(image) },
        Err(error) => {
            print_no_bootable_device();
            print_no_bootable_debug();
            set_halted(error.code());
            wait_forever()
        }
    }
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    print_bios_panic_debug();
    unsafe {
        write_i32(control::PANIC_CODE, status::PANIC);
        write_i32(control::STATUS, status::PANIC);
    }
    wait_forever()
}

fn clear_display() {
    unsafe {
        write_i32(display0::COMMAND, display0::COMMAND_CLEAR);
    }
}

fn print_bios_banner() {
    unsafe {
        write_i32(display0::CURSOR_X, 0);
        write_i32(display0::CURSOR_Y, 0);
    }
    print_display_byte(b'K');
    print_display_byte(b'1');
    print_display_byte(b'6');
    print_display_byte(b' ');
    print_display_byte(b'B');
    print_display_byte(b'I');
    print_display_byte(b'O');
    print_display_byte(b'S');
}

fn print_no_bootable_device() {
    unsafe {
        write_i32(display0::CURSOR_X, 0);
        write_i32(display0::CURSOR_Y, 2);
    }
    print_display_byte(b'N');
    print_display_byte(b'o');
    print_display_byte(b' ');
    print_display_byte(b'b');
    print_display_byte(b'o');
    print_display_byte(b'o');
    print_display_byte(b't');
    print_display_byte(b'a');
    print_display_byte(b'b');
    print_display_byte(b'l');
    print_display_byte(b'e');
    print_display_byte(b' ');
    print_display_byte(b'd');
    print_display_byte(b'e');
    print_display_byte(b'v');
    print_display_byte(b'i');
    print_display_byte(b'c');
    print_display_byte(b'e');
}

fn print_display_byte(byte: u8) {
    unsafe {
        write_u8(display0::DATA, byte);
        write_i32(display0::COMMAND, display0::COMMAND_PUT_BYTE_AT_CURSOR);
    }
}

fn set_booting() {
    unsafe {
        write_i32(control::STATUS, status::BOOTING);
    }
}

fn print_bios_debug() {
    print_debug_byte(b'K');
    print_debug_byte(b'1');
    print_debug_byte(b'6');
    print_debug_byte(b' ');
    print_debug_byte(b'B');
    print_debug_byte(b'I');
    print_debug_byte(b'O');
    print_debug_byte(b'S');
    print_debug_byte(b'\n');
}

fn print_no_bootable_debug() {
    print_debug_byte(b'N');
    print_debug_byte(b'O');
    print_debug_byte(b' ');
    print_debug_byte(b'B');
    print_debug_byte(b'O');
    print_debug_byte(b'O');
    print_debug_byte(b'T');
    print_debug_byte(b'A');
    print_debug_byte(b'B');
    print_debug_byte(b'L');
    print_debug_byte(b'E');
    print_debug_byte(b' ');
    print_debug_byte(b'D');
    print_debug_byte(b'E');
    print_debug_byte(b'V');
    print_debug_byte(b'I');
    print_debug_byte(b'C');
    print_debug_byte(b'E');
    print_debug_byte(b'\n');
}

fn print_bios_panic_debug() {
    print_debug_byte(b'K');
    print_debug_byte(b'1');
    print_debug_byte(b'6');
    print_debug_byte(b' ');
    print_debug_byte(b'B');
    print_debug_byte(b'I');
    print_debug_byte(b'O');
    print_debug_byte(b'S');
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

unsafe fn write_i32(address: u32, value: i32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut i32, value) }
}

unsafe fn write_u8(address: u32, value: u8) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u8, value) }
}

fn wait_forever() -> ! {
    k16_rt::halt_forever()
}
