#![no_std]
#![no_main]

use core::panic::PanicInfo;
use k16_abi::computer::{control, debug, display0, status};
use k16_abi::mmio;

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
        mmio::<i32>(control::PANIC_CODE).write(status::PANIC);
        mmio::<i32>(control::STATUS).write(status::PANIC);
    }
    wait_forever()
}

fn print_debug(bytes: &[u8]) {
    for byte in bytes {
        unsafe {
            mmio::<u8>(debug::WRITE).write(*byte);
        }
    }
}

fn clear_display() {
    unsafe {
        mmio::<i32>(display0::COMMAND).write(display0::COMMAND_CLEAR);
    }
}

fn print_display_line(row: i32, bytes: &[u8]) {
    unsafe {
        mmio::<i32>(display0::CURSOR_X).write(0);
        mmio::<i32>(display0::CURSOR_Y).write(row);
    }
    for byte in bytes {
        unsafe {
            mmio::<u8>(display0::DATA).write(*byte);
            mmio::<i32>(display0::COMMAND).write(display0::COMMAND_PUT_BYTE_AT_CURSOR);
        }
    }
}

fn set_halted() {
    unsafe {
        mmio::<i32>(control::PANIC_CODE).write(status::READY);
        mmio::<i32>(control::STATUS).write(status::HALTED);
    }
}

fn wait_forever() -> ! {
    loop {
        core::hint::spin_loop();
    }
}
