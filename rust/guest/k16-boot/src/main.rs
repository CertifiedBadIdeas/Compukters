#![no_std]
#![no_main]

use core::panic::PanicInfo;
use k16_abi::computer::{control, debug, status};
use k16_abi::mmio;

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
    for byte in bytes {
        unsafe {
            mmio::<u8>(debug::WRITE).write(*byte);
        }
    }
}

fn set_halted(code: i32) {
    unsafe {
        mmio::<i32>(control::PANIC_CODE).write(code);
        mmio::<i32>(control::STATUS).write(status::HALTED);
    }
}

fn set_panic() {
    unsafe {
        mmio::<i32>(control::PANIC_CODE).write(status::PANIC);
        mmio::<i32>(control::STATUS).write(status::PANIC);
    }
}

fn wait_forever() -> ! {
    loop {
        core::hint::spin_loop();
    }
}
