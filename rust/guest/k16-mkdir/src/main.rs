#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

#[no_mangle]
pub extern "C" fn main(argc: u32, argv: *const process::Arg) -> ! {
    match mkdir_args(argc, argv) {
        Ok(()) => process::exit(0),
        Err(()) => process::exit(1),
    }
}

fn mkdir_args(argc: u32, argv: *const process::Arg) -> Result<(), ()> {
    let argv = unsafe { process::Argv::from_raw(argc, argv) };
    coreutils::for_each_path_arg(argv.len(), |index| argv.get(index), create_path)
}

fn create_path(path: &str) -> Result<(), ()> {
    let stdout = io::stdout();
    let path_ref = match path::PathRef::try_from_str(path) {
        Ok(path_ref) => path_ref,
        Err(_) => {
            write_mkdir_error(stdout, b"INVAL", path)?;
            return Err(());
        }
    };
    match fs::create_dir_path(path_ref) {
        Ok(()) => {
            stdout.write_all(b"CREATED ").map_err(|_| ())?;
            stdout.write_all(path.as_bytes()).map_err(|_| ())?;
            stdout.write_all(b"\n").map_err(|_| ())
        }
        Err(fs::Error::InvalidArgument) => {
            write_mkdir_error(stdout, b"INVAL", path)?;
            Err(())
        }
        Err(fs::Error::Syscall(status)) => {
            write_mkdir_error(
                stdout,
                status::syscall_status_name_or(status, b"MKDIR"),
                path,
            )?;
            Err(())
        }
    }
}

fn write_mkdir_error(stdout: io::Fd, name: &[u8], path: &str) -> Result<(), ()> {
    stdout.write_all(b"ERR ").map_err(|_| ())?;
    stdout.write_all(name).map_err(|_| ())?;
    stdout.write_all(b" ").map_err(|_| ())?;
    stdout.write_all(path.as_bytes()).map_err(|_| ())?;
    stdout.write_all(b"\n").map_err(|_| ())
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
