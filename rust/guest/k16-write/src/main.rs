#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

#[no_mangle]
pub extern "C" fn main(argc: u32, argv: *const process::Arg) -> ! {
    match write_arg(argc, argv) {
        Ok(()) => process::exit(0),
        Err(()) => process::exit(1),
    }
}

fn write_arg(argc: u32, argv: *const process::Arg) -> Result<(), ()> {
    let argv = unsafe { process::Argv::from_raw(argc, argv) };
    let (append, path, payload) = match argv.len() {
        2 => (false, argv.get(0), argv.get(1)),
        3 if argv.get(0) == Some(b"--append".as_slice()) => (true, argv.get(1), argv.get(2)),
        _ => return Err(()),
    };
    let Some(path) = path else { return Err(()) };
    let Some(payload) = payload else {
        return Err(());
    };
    let path = core::str::from_utf8(path).map_err(|_| ())?;
    let file = if append {
        fs::append(path).map_err(|_| ())?
    } else {
        fs::create(path).map_err(|_| ())?
    };
    file.write_all(payload).map_err(|_| ())?;
    file.close().map_err(|_| ())?;

    let stdout = io::stdout();
    stdout.write_all(b"WROTE ").map_err(|_| ())?;
    write_decimal(stdout, payload.len() as u32)?;
    stdout.write_all(b" ").map_err(|_| ())?;
    stdout.write_all(path.as_bytes()).map_err(|_| ())?;
    stdout.write_all(b"\n").map_err(|_| ())
}

fn write_decimal(stdout: io::Fd, mut value: u32) -> Result<(), ()> {
    let mut digits = [0u8; 10];
    let mut start = digits.len();
    loop {
        start -= 1;
        digits[start] = b'0' + (value % 10) as u8;
        value /= 10;
        if value == 0 {
            break;
        }
    }
    stdout.write_all(&digits[start..]).map_err(|_| ())
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
