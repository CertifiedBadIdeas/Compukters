#![no_std]
#![no_main]

use core::panic::PanicInfo;

#[no_mangle]
pub extern "C" fn k16_shared_smoke_value() -> u32 {
    42
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    loop {}
}
