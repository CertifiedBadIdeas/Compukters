#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

#[no_mangle]
pub extern "C" fn main() -> ! {
    match print_motd() {
        Ok(()) => process::exit(0),
        Err(()) => process::exit(1),
    }
}

fn print_motd() -> Result<(), ()> {
    let stdout = io::stdout();
    let file = fs::open("/etc/motd").map_err(|_| ())?;
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
