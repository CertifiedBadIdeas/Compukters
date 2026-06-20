#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

static mut CHILD_PATH: [u8; k16_abi::syscall::MAX_STAT_PATH_BYTES] =
    [0; k16_abi::syscall::MAX_STAT_PATH_BYTES];

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
    let mut buffer = [0u8; 256];
    let child_path = child_path_buffer();
    let read = match fs::read_dir(path, &mut buffer) {
        Ok(read) => read,
        Err(error) => {
            write_ls_error(stdout, fs_error_name(error), path)?;
            return Err(());
        }
    };
    let mut cursor = 0;
    while cursor < read {
        let start = cursor;
        while cursor < read && buffer[cursor] != b'\n' {
            cursor += 1;
        }
        let name = &buffer[start..cursor];
        let child_path_len = join_child_path(path.as_bytes(), name, child_path)?;
        let child_path = core::str::from_utf8(&child_path[..child_path_len]).map_err(|_| ())?;
        let metadata = match fs::metadata(child_path) {
            Ok(metadata) => metadata,
            Err(error) => {
                write_ls_error(stdout, fs_error_name(error), child_path)?;
                return Err(());
            }
        };
        stdout.write_all(name).map_err(|_| ())?;
        if metadata.file_type == fs::FileType::Directory {
            stdout.write_all(b"/").map_err(|_| ())?;
        }
        stdout.write_all(b"\n").map_err(|_| ())?;
        cursor += 1;
    }
    Ok(())
}

fn write_ls_error(stdout: io::Fd, name: &[u8], path: &str) -> Result<(), ()> {
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
        0xffff_fff2 => b"FAULT",
        _ => b"READDIR",
    }
}

fn child_path_buffer() -> &'static mut [u8; k16_abi::syscall::MAX_STAT_PATH_BYTES] {
    unsafe { &mut *core::ptr::addr_of_mut!(CHILD_PATH) }
}

fn join_child_path(base: &[u8], name: &[u8], out: &mut [u8]) -> Result<usize, ()> {
    if name.is_empty() || base.is_empty() {
        return Err(());
    }
    let separator_len = if base == b"/" { 0 } else { 1 };
    let len = base
        .len()
        .checked_add(separator_len)
        .and_then(|value| value.checked_add(name.len()))
        .ok_or(())?;
    if len > out.len() {
        return Err(());
    }
    out[..base.len()].copy_from_slice(base);
    let mut cursor = base.len();
    if separator_len == 1 {
        out[cursor] = b'/';
        cursor += 1;
    }
    out[cursor..cursor + name.len()].copy_from_slice(name);
    Ok(len)
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
