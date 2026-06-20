#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

#[no_mangle]
pub extern "C" fn main() -> ! {
    let fault_ptr = 0xffff_f000 as *const u32;
    let _ = unsafe { core::ptr::read_volatile(fault_ptr) };
    process::exit(0)
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
