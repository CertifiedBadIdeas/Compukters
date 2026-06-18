#![no_std]
#![no_main]

extern crate k16_rt;

mod console;
mod control;
mod debug;
mod font;
mod fs;
mod generated;
mod gpu;
mod init;
mod memory_layout;
mod mmio;
mod page_alloc;
mod process;
mod stdin;
mod syscall;
mod terminal;
mod terminal_render;
mod timer;
mod trap;
mod user_buffer;

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
