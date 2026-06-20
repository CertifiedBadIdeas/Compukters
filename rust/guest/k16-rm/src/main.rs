#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

#[no_mangle]
pub extern "C" fn main(argc: u32, argv: *const process::Arg) -> ! {
    match rm_args(argc, argv) {
        Ok(()) => process::exit(0),
        Err(()) => process::exit(1),
    }
}

fn rm_args(argc: u32, argv: *const process::Arg) -> Result<(), ()> {
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
        if remove_path(path).is_err() {
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

fn remove_path(path: &str) -> Result<(), ()> {
    let stdout = io::stdout();
    match fs::remove_file(path) {
        Ok(()) => {
            stdout.write_all(b"REMOVED ").map_err(|_| ())?;
            stdout.write_all(path.as_bytes()).map_err(|_| ())?;
            stdout.write_all(b"\n").map_err(|_| ())
        }
        Err(fs::Error::InvalidArgument) => {
            write_remove_error(stdout, b"INVAL", path)?;
            Err(())
        }
        Err(fs::Error::Syscall(status)) => {
            write_remove_error(stdout, status_name(status), path)?;
            Err(())
        }
    }
}

fn write_remove_error(stdout: io::Fd, name: &[u8], path: &str) -> Result<(), ()> {
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
        0xffff_fff0 => b"BUSY",
        0xffff_fff2 => b"FAULT",
        _ => b"UNLINK",
    }
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
