#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

const PROMPT: &[u8] = b"INIT> ";
const PREFIX: &[u8] = b"READ ";
const NEWLINE: &[u8] = b"\n";
const INPUT_CAPACITY: usize = 32;

static mut INPUT: [u8; INPUT_CAPACITY] = [0; INPUT_CAPACITY];

#[no_mangle]
pub extern "C" fn main() -> ! {
    let stdin = io::stdin();
    let stdout = io::stdout();

    must_write(stdout, b"K16 INIT\n");
    loop {
        must_write(stdout, PROMPT);
        let input = input_buffer();
        let read = match stdin.read(input) {
            Ok(read) => read,
            Err(_) => process::exit(1),
        };
        if read > INPUT_CAPACITY {
            process::exit(1);
        }

        must_write(stdout, PREFIX);
        must_write(stdout, &input[..read]);
        if input[read - 1] != b'\n' {
            must_write(stdout, NEWLINE);
        }
    }
}

fn input_buffer() -> &'static mut [u8] {
    unsafe {
        core::slice::from_raw_parts_mut(core::ptr::addr_of_mut!(INPUT).cast(), INPUT_CAPACITY)
    }
}

fn must_write(fd: io::Fd, bytes: &[u8]) {
    if fd.write_all(bytes).is_err() {
        process::exit(1);
    }
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
