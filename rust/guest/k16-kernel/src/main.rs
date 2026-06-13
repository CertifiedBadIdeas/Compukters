#![no_std]
#![no_main]

extern crate k16_rt;

mod console;
mod control;
mod debug;
mod font;
mod generated;
mod gpu;
mod init;
mod mmio;
mod syscall;
mod terminal;
mod terminal_render;
mod timer;
mod trap;

use core::panic::PanicInfo;

#[no_mangle]
pub extern "C" fn _start() -> ! {
    console::init();
    debug::print_kernel_ok();
    trap::initialize();
    control::set_ready();
    init::launch()
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    debug::print_kernel_panic();
    control::set_panic();
    control::wait_forever()
}
