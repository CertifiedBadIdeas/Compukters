#![no_std]
#![no_main]

extern crate k16_rt;

mod control;
mod debug;
mod display;
mod mmio;
mod syscall;
mod timer;
mod trap;

use core::panic::PanicInfo;

#[no_mangle]
pub extern "C" fn _start() -> ! {
    display::clear();
    display::print_kernel_ok();
    debug::print_kernel_ok();
    trap::initialize();
    control::set_ready();
    idle_forever()
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    debug::print_kernel_panic();
    control::set_panic();
    control::wait_forever()
}

fn idle_forever() -> ! {
    loop {
        k16_rt::yield_once();
    }
}
