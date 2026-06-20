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
    let file = match fs::open(path) {
        Ok(file) => file,
        Err(error) => {
            write_cat_error(stdout, fs_error_name(error), path)?;
            return Err(());
        }
    };
    let mut buffer = [0u8; 64];
    loop {
        let read = match file.read(&mut buffer) {
            Ok(read) => read,
            Err(error) => {
                write_cat_error(stdout, fs_error_name(error), path)?;
                return Err(());
            }
        };
        if read == 0 {
            break;
        }
        stdout.write_all(&buffer[..read]).map_err(|_| ())?;
    }
    match file.close() {
        Ok(()) => Ok(()),
        Err(error) => {
            write_cat_error(stdout, fs_error_name(error), path)?;
            Err(())
        }
    }
}

fn write_cat_error(stdout: io::Fd, name: &[u8], path: &str) -> Result<(), ()> {
    stdout.write_all(b"ERR ").map_err(|_| ())?;
    stdout.write_all(name).map_err(|_| ())?;
    stdout.write_all(b" ").map_err(|_| ())?;
    stdout.write_all(path.as_bytes()).map_err(|_| ())?;
    stdout.write_all(b"\n").map_err(|_| ())
}

fn fs_error_name(error: fs::Error) -> &'static [u8] {
    match error {
        fs::Error::InvalidArgument => b"INVAL",
        fs::Error::Syscall(status) => status_name(status),
    }
}

fn status_name(status: u32) -> &'static [u8] {
    match status {
        0xffff_fffe => b"NOENT",
        0xffff_ffea => b"INVAL",
        0xffff_fff7 => b"BADFD",
        0xffff_fff2 => b"FAULT",
        _ => b"IO",
    }
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
