#![no_std]
#![no_main]

extern crate k16_rt;

mod console;
mod control;
mod debug;
mod font;
mod generated;
mod gpu;
mod keyboard;
mod line;
mod mmio;
mod shell;
mod syscall;
mod terminal;
mod terminal_render;
mod timer;
mod trap;

use core::panic::PanicInfo;

#[no_mangle]
pub extern "C" fn _start() -> ! {
    console::init();
    console::write_bytes(b"KERNEL OK\n");
    console::flush();
    shell::init();
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
        idle_once();
    }
}

fn idle_once() {
    keyboard::drain_to_line();
    k16_rt::wait_once();
}
