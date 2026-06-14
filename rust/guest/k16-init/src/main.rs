#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

#[no_mangle]
pub extern "C" fn main() -> ! {
    match process::run("/bin/shell.kx") {
        Ok(status) => process::exit(status),
        Err(_) => process::exit(1),
    }
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
