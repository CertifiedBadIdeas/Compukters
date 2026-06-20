#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

#[no_mangle]
pub extern "C" fn main(argc: u32, argv: *const process::Arg) -> ! {
    match list_first_arg_or_default(argc, argv) {
        Ok(()) => process::exit(0),
        Err(()) => process::exit(1),
    }
}

fn list_first_arg_or_default(argc: u32, argv: *const process::Arg) -> Result<(), ()> {
    let argv = unsafe { process::Argv::from_raw(argc, argv) };
    k16_ls::for_each_path_arg_or_default(argv.len(), |index| argv.get(index), list_dir)
}

fn list_dir(path: &str) -> Result<(), ()> {
    let stdout = io::stdout();
    let path_ref = match path::PathRef::try_from_str(path) {
        Ok(path_ref) => path_ref,
        Err(_) => {
            write_ls_error(
                stdout,
                status::fs_error_name_or(fs::Error::InvalidArgument, b"READDIR"),
                path,
            )?;
            return Err(());
        }
    };
    let mut buffer = [0u8; 256];
    match fs::read_dir_entries_path(path_ref, &mut buffer, |entry| {
        stdout.write_all(entry.name().as_bytes()).map_err(|_| ())?;
        if entry.file_type() == fs::FileType::Directory {
            stdout.write_all(b"/").map_err(|_| ())?;
        }
        stdout.write_all(b"\n").map_err(|_| ())
    }) {
        Ok(()) => Ok(()),
        Err(fs::ReadDirEntryError::Visit(())) => Err(()),
        Err(fs::ReadDirEntryError::Fs(error)) => {
            write_ls_error(stdout, status::fs_error_name_or(error, b"READDIR"), path)?;
            Err(())
        }
    }
}

fn write_ls_error(stdout: io::Fd, name: &[u8], path: &str) -> Result<(), ()> {
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
