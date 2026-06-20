#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

#[no_mangle]
pub extern "C" fn main(argc: u32, argv: *const process::Arg) -> ! {
    match cp_args(argc, argv) {
        Ok(()) => process::exit(0),
        Err(error) => {
            let _ = write_error(error);
            process::exit(1);
        }
    }
}

#[derive(Clone, Copy)]
enum CopyError {
    InvalidArgument,
    Source(&'static str, fs::Error),
    Destination(&'static str, fs::Error),
    Read(&'static str, fs::Error),
    Write(&'static str, fs::Error),
    Close(&'static str, fs::Error),
}

fn cp_args(argc: u32, argv: *const process::Arg) -> Result<(), CopyError> {
    let argv = unsafe { process::Argv::from_raw(argc, argv) };
    if argv.len() != 2 {
        return Err(CopyError::InvalidArgument);
    }
    let Some(source) = argv.get(0) else {
        return Err(CopyError::InvalidArgument);
    };
    let Some(destination) = argv.get(1) else {
        return Err(CopyError::InvalidArgument);
    };
    let source = core::str::from_utf8(source).map_err(|_| CopyError::InvalidArgument)?;
    let destination = core::str::from_utf8(destination).map_err(|_| CopyError::InvalidArgument)?;

    copy_file(source, destination)?;
    let stdout = io::stdout();
    stdout.write_all(b"COPIED ").map_err(|_| CopyError::InvalidArgument)?;
    stdout
        .write_all(source.as_bytes())
        .map_err(|_| CopyError::InvalidArgument)?;
    stdout.write_all(b" ").map_err(|_| CopyError::InvalidArgument)?;
    stdout
        .write_all(destination.as_bytes())
        .map_err(|_| CopyError::InvalidArgument)?;
    stdout.write_all(b"\n").map_err(|_| CopyError::InvalidArgument)
}

fn copy_file(source_path: &'static str, destination_path: &'static str) -> Result<(), CopyError> {
    let source = fs::open(source_path).map_err(|error| CopyError::Source(source_path, error))?;
    let destination = match fs::create(destination_path) {
        Ok(file) => file,
        Err(error) => {
            let _ = source.close();
            return Err(CopyError::Destination(destination_path, error));
        }
    };

    let result = copy_open_files(source, destination, source_path, destination_path);
    let source_close = source.close();
    let destination_close = destination.close();
    result?;
    source_close.map_err(|error| CopyError::Close(source_path, error))?;
    destination_close.map_err(|error| CopyError::Close(destination_path, error))
}

fn copy_open_files(
    source: fs::File,
    destination: fs::File,
    source_path: &'static str,
    destination_path: &'static str,
) -> Result<(), CopyError> {
    let mut buffer = [0u8; 64];
    loop {
        let read = source
            .read(&mut buffer)
            .map_err(|error| CopyError::Read(source_path, error))?;
        if read == 0 {
            return Ok(());
        }
        destination
            .write_all(&buffer[..read])
            .map_err(|error| CopyError::Write(destination_path, error))?;
    }
}

fn write_error(error: CopyError) -> Result<(), io::Error> {
    let stdout = io::stdout();
    match error {
        CopyError::InvalidArgument => stdout.write_all(b"ERR INVAL\n"),
        CopyError::Source(path, error) => write_path_error(stdout, fs_error_name(error), path),
        CopyError::Destination(path, error) => write_path_error(stdout, fs_error_name(error), path),
        CopyError::Read(path, error) => write_path_error(stdout, fs_error_name(error), path),
        CopyError::Write(path, error) => write_path_error(stdout, fs_error_name(error), path),
        CopyError::Close(path, error) => write_path_error(stdout, fs_error_name(error), path),
    }
}

fn write_path_error(stdout: io::Fd, name: &[u8], path: &str) -> Result<(), io::Error> {
    stdout.write_all(b"ERR ")?;
    stdout.write_all(name)?;
    stdout.write_all(b" ")?;
    stdout.write_all(path.as_bytes())?;
    stdout.write_all(b"\n")
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
        0xffff_fff4 => b"NOMEM",
        0xffff_fff2 => b"FAULT",
        0xffff_fff0 => b"BUSY",
        _ => b"IO",
    }
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
