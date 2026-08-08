#![no_std]
#![no_main]

extern crate k16_rt;

mod boot_chain;
mod child_exit;
mod console;
mod control;
mod debug;
mod font;
mod fs;
mod generated;
mod gpu;
mod image;
mod init;
mod kfs;

mod memory_layout;
mod mmio;
mod os_stats;
mod page_alloc;
mod process;
mod stdin;
mod syscall;
mod terminal;
mod terminal_render;
mod timer;
mod trap;
mod trap_policy;
mod user_buffer;
mod vfs;

use core::panic::PanicInfo;

#[no_mangle]
pub extern "C" fn _start() -> ! {
    console::init();
    os_stats::register();
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
