#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

#[no_mangle]
pub extern "C" fn main() -> ! {
    let stdout = io::stdout();
    if stdout.write_all(b"K16\n").is_err() {
        process::exit(1);
    }
    process::exit(0)
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
