#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

#[no_mangle]
pub extern "C" fn main(argc: u32, argv: *const process::Arg) -> ! {
    match stat_args(argc, argv) {
        Ok(()) => process::exit(0),
        Err(()) => process::exit(1),
    }
}

fn stat_args(argc: u32, argv: *const process::Arg) -> Result<(), ()> {
    let argv = unsafe { process::Argv::from_raw(argc, argv) };
    if argv.len() == 0 {
        return Err(());
    }
    let mut index = 0;
    let mut ok = true;
    while index < argv.len() {
        let Some(path) = argv.get(index) else {
            return Err(());
        };
        let path = core::str::from_utf8(path).map_err(|_| ())?;
        if stat_path(path).is_err() {
            ok = false;
        }
        index += 1;
    }
    if ok {
        Ok(())
    } else {
        Err(())
    }
}

fn stat_path(path: &str) -> Result<(), ()> {
    let stdout = io::stdout();
    match fs::metadata(path) {
        Ok(metadata) => {
            match metadata.file_type {
                fs::FileType::Regular => stdout.write_all(b"FILE ").map_err(|_| ())?,
                fs::FileType::Directory => stdout.write_all(b"DIR ").map_err(|_| ())?,
            }
            write_decimal(stdout, metadata.size_bytes)?;
            stdout.write_all(b" ").map_err(|_| ())?;
            stdout.write_all(path.as_bytes()).map_err(|_| ())?;
            stdout.write_all(b"\n").map_err(|_| ())?;
            Ok(())
        }
        Err(fs::Error::InvalidArgument) => {
            write_stat_error(stdout, b"INVAL", path)?;
            Err(())
        }
        Err(fs::Error::Syscall(status)) => {
            write_stat_error(stdout, status_name(status), path)?;
            Err(())
        }
    }
}

fn write_stat_error(stdout: io::Fd, name: &[u8], path: &str) -> Result<(), ()> {
    stdout.write_all(b"ERR ").map_err(|_| ())?;
    stdout.write_all(name).map_err(|_| ())?;
    stdout.write_all(b" ").map_err(|_| ())?;
    stdout.write_all(path.as_bytes()).map_err(|_| ())?;
    stdout.write_all(b"\n").map_err(|_| ())
}

fn status_name(status: u32) -> &'static [u8] {
    match status {
        0xffff_fffe => b"NOENT",
        0xffff_ffea => b"INVAL",
        0xffff_fff2 => b"FAULT",
        _ => b"STAT",
    }
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
