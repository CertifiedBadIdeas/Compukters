#![no_std]
#![no_main]

use core::panic::PanicInfo;

extern "C" {
    fn k16_shared_smoke_value() -> u32;
}

#[no_mangle]
pub extern "C" fn main() -> ! {
    let value = unsafe { k16_shared_smoke_value() };
    k16_rt::exit_syscall(value)
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    k16_rt::exit_syscall(1)
}
