#![no_std]
#![no_main]
#![feature(alloc_error_handler)]

extern crate alloc;

use alloc::vec::Vec;
use core::alloc::Layout;
use core::panic::PanicInfo;

use kraft_std::prelude::*;

#[no_mangle]
pub extern "C" fn main() -> ! {
    match run() {
        Ok(()) => process::exit(0),
        Err(()) => process::exit(1),
    }
}

fn run() -> Result<(), ()> {
    let mut bytes = Vec::new();
    bytes.push(b'A');
    bytes.push(b'L');
    bytes.push(b'L');
    bytes.push(b'O');
    bytes.push(b'C');
    bytes.push(b'\n');
    io::stdout().write_all(&bytes).map_err(|_| ())
}

#[alloc_error_handler]
fn alloc_error(_layout: Layout) -> ! {
    process::exit(1)
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
