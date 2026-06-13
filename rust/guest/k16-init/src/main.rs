#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

#[no_mangle]
pub extern "C" fn main() -> u32 {
    for byte in b"K16 INIT\n" {
        debug::write_byte(*byte);
    }
    0
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    k16_rt::halt_forever()
}
