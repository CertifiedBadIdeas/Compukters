#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

const DEFAULT_PATH: &str = "/bin";

#[no_mangle]
pub extern "C" fn main(argc: u32, argv: *const process::Arg) -> ! {
    match list_first_arg_or_default(argc, argv) {
        Ok(()) => process::exit(0),
        Err(()) => process::exit(1),
    }
}

fn list_first_arg_or_default(argc: u32, argv: *const process::Arg) -> Result<(), ()> {
    let argv = unsafe { process::Argv::from_raw(argc, argv) };
    let path = match argv.get(0) {
        Some(path) => core::str::from_utf8(path).map_err(|_| ())?,
        None => DEFAULT_PATH,
    };
    list_dir(path)
}

fn list_dir(path: &str) -> Result<(), ()> {
    let stdout = io::stdout();
    let mut buffer = [0u8; 256];
    let read = fs::read_dir(path, &mut buffer).map_err(|_| ())?;
    stdout.write_all(&buffer[..read]).map_err(|_| ())
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
