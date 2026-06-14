#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

#[no_mangle]
pub extern "C" fn main(argc: u32, argv: *const process::Arg) -> ! {
    match print_first_arg(argc, argv) {
        Ok(()) => process::exit(0),
        Err(()) => process::exit(1),
    }
}

fn print_first_arg(argc: u32, argv: *const process::Arg) -> Result<(), ()> {
    let argv = unsafe { process::Argv::from_raw(argc, argv) };
    k16_cat::for_each_path_arg(argv.len(), |index| argv.get(index), print_file)
}

fn print_file(path: &str) -> Result<(), ()> {
    let stdout = io::stdout();
    let file = fs::open(path).map_err(|_| ())?;
    let mut buffer = [0u8; 64];
    loop {
        let read = file.read(&mut buffer).map_err(|_| ())?;
        if read == 0 {
            break;
        }
        stdout.write_all(&buffer[..read]).map_err(|_| ())?;
    }
    file.close().map_err(|_| ())
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
