#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

#[no_mangle]
pub extern "C" fn main(argc: u32, argv: *const process::Arg) -> ! {
    match mv_args(argc, argv) {
        Ok(()) => process::exit(0),
        Err(error) => {
            let _ = write_error(error);
            process::exit(1);
        }
    }
}

#[derive(Clone, Copy)]
enum MoveError {
    InvalidArgument,
    Source(&'static str, fs::Error),
    Destination(&'static str, fs::Error),
}

fn mv_args(argc: u32, argv: *const process::Arg) -> Result<(), MoveError> {
    let argv = unsafe { process::Argv::from_raw(argc, argv) };
    if argv.len() != 2 {
        return Err(MoveError::InvalidArgument);
    }
    let Some(source) = argv.get(0) else {
        return Err(MoveError::InvalidArgument);
    };
    let Some(destination) = argv.get(1) else {
        return Err(MoveError::InvalidArgument);
    };
    let source = core::str::from_utf8(source).map_err(|_| MoveError::InvalidArgument)?;
    let destination = core::str::from_utf8(destination).map_err(|_| MoveError::InvalidArgument)?;

    move_file(source, destination)?;
    let stdout = io::stdout();
    stdout
        .write_all(b"MOVED ")
        .map_err(|_| MoveError::InvalidArgument)?;
    stdout
        .write_all(source.as_bytes())
        .map_err(|_| MoveError::InvalidArgument)?;
    stdout
        .write_all(b" ")
        .map_err(|_| MoveError::InvalidArgument)?;
    stdout
        .write_all(destination.as_bytes())
        .map_err(|_| MoveError::InvalidArgument)?;
    stdout
        .write_all(b"\n")
        .map_err(|_| MoveError::InvalidArgument)
}

fn move_file(source: &'static str, destination: &'static str) -> Result<(), MoveError> {
    let source_ref = path::PathRef::try_from_str(source)
        .map_err(|_| MoveError::Source(source, fs::Error::InvalidArgument))?;
    let destination_ref = path::PathRef::try_from_str(destination)
        .map_err(|_| MoveError::Destination(destination, fs::Error::InvalidArgument))?;
    match fs::metadata_path(source_ref) {
        Ok(metadata) if metadata.file_type == fs::FileType::Regular => {}
        Ok(_) => return Err(MoveError::Source(source, fs::Error::InvalidArgument)),
        Err(error) => return Err(MoveError::Source(source, error)),
    }
    match fs::metadata_path(destination_ref) {
        Ok(_) => {
            return Err(MoveError::Destination(
                destination,
                fs::Error::InvalidArgument,
            ))
        }
        Err(fs::Error::Syscall(status)) if is_no_entry_status(status) => {}
        Err(error) => return Err(MoveError::Destination(destination, error)),
    }
    fs::rename_path(source_ref, destination_ref).map_err(|error| MoveError::Source(source, error))
}

fn write_error(error: MoveError) -> Result<(), io::Error> {
    let stdout = io::stdout();
    match error {
        MoveError::InvalidArgument => stdout.write_all(b"ERR INVAL\n"),
        MoveError::Source(path, error) => {
            write_path_error(stdout, status::fs_error_name_or(error, b"RENAME"), path)
        }
        MoveError::Destination(path, error) => {
            write_path_error(stdout, status::fs_error_name_or(error, b"RENAME"), path)
        }
    }
}

fn write_path_error(stdout: io::Fd, name: &[u8], path: &str) -> Result<(), io::Error> {
    stdout.write_all(b"ERR ")?;
    stdout.write_all(name)?;
    stdout.write_all(b" ")?;
    stdout.write_all(path.as_bytes())?;
    stdout.write_all(b"\n")
}

fn is_no_entry_status(status: u32) -> bool {
    status == 0xffff_fffe
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
