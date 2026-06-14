#![no_std]
#![no_main]

use core::panic::PanicInfo;

use k16_shell::{Command, InputLine, PathBuffer, WorkingDirectory};
use kraft_std::prelude::*;

const PROMPT: &[u8] = b"K16> ";
const NEWLINE: &[u8] = b"\n";
const HELP: &[u8] =
    b"HELP\nCLEAR\nPWD\nCD [PATH]\nECHO\nTICKS\nUNAME\nLS [PATH]\nCAT <PATH>\nALLOC\n";

#[no_mangle]
pub extern "C" fn main() -> ! {
    let stdin = io::stdin();
    let stdout = io::stdout();
    let mut input = InputLine::new();
    let mut cwd = WorkingDirectory::new();
    let mut read_buffer = [0u8; 1];
    let mut path_buffer = PathBuffer::new();

    must_write(stdout, b"K16 SHELL\n");
    loop {
        must_write(stdout, PROMPT);
        read_and_dispatch_line(
            stdin,
            stdout,
            &mut input,
            &mut cwd,
            &mut path_buffer,
            &mut read_buffer,
        );
    }
}

fn read_and_dispatch_line(
    stdin: io::Fd,
    stdout: io::Fd,
    input: &mut InputLine,
    cwd: &mut WorkingDirectory,
    path_buffer: &mut PathBuffer,
    read_buffer: &mut [u8; 1],
) {
    input.clear();
    loop {
        let read = match stdin.read(read_buffer) {
            Ok(read) => read,
            Err(_) => process::exit(1),
        };
        let mut index = 0;
        while index < read {
            match read_buffer[index] {
                b'\n' | b'\r' => {
                    must_write(stdout, NEWLINE);
                    dispatch_command(stdout, cwd, path_buffer, input.command());
                    return;
                }
                b'\x08' | 0x7f => {
                    if input.backspace() {
                        must_write(stdout, b"\x08");
                    }
                }
                byte @ 0x20..=0x7e => {
                    if input.push_printable(byte) {
                        must_write(stdout, &[byte]);
                    }
                }
                _ => {}
            }
            index += 1;
        }
    }
}

fn dispatch_command(
    stdout: io::Fd,
    cwd: &mut WorkingDirectory,
    path_buffer: &mut PathBuffer,
    command: Command<'_>,
) {
    match command {
        Command::Empty => {}
        Command::Help => must_write(stdout, HELP),
        Command::Clear => must_write(stdout, b"\x0c"),
        Command::Pwd => run_pwd(stdout, cwd),
        Command::Cd(path) => run_cd(stdout, cwd, path_buffer, path),
        Command::Ticks => run_ticks(stdout),
        Command::Uname => run_uname(stdout),
        Command::Ls(path) => run_ls(stdout, cwd, path_buffer, path),
        Command::Cat(path) => run_cat(stdout, cwd, path_buffer, path),
        Command::AllocTest => run_alloc_test(stdout),
        Command::Echo(bytes) => {
            must_write(stdout, bytes);
            must_write(stdout, NEWLINE);
        }
        Command::Unknown => must_write(stdout, b"ERR\n"),
    }
}

fn run_pwd(stdout: io::Fd, cwd: &WorkingDirectory) {
    must_write(stdout, cwd.as_bytes());
    must_write(stdout, NEWLINE);
}

fn run_cd(
    stdout: io::Fd,
    cwd: &mut WorkingDirectory,
    path_buffer: &mut PathBuffer,
    path: Option<&[u8]>,
) {
    let path = path.unwrap_or(b"/");
    if cwd.resolve_into(path, path_buffer).is_err() {
        must_write(stdout, b"ERR INVAL\n");
        return;
    }
    let Ok(path) = path_buffer.as_str() else {
        must_write(stdout, b"ERR INVAL\n");
        return;
    };
    match fs::metadata(path) {
        Ok(metadata) if metadata.file_type == fs::FileType::Directory => {
            if cwd.set_from_resolved(path_buffer).is_err() {
                must_write(stdout, b"ERR INVAL\n");
            }
        }
        Ok(_) => must_write(stdout, b"ERR INVAL\n"),
        Err(fs::Error::InvalidArgument) => must_write(stdout, b"ERR INVAL\n"),
        Err(fs::Error::Syscall(status)) => {
            must_write(stdout, b"ERR ");
            must_write(stdout, run_error_name(status));
            must_write(stdout, NEWLINE);
        }
    }
}

fn run_ticks(stdout: io::Fd) {
    must_write(stdout, b"TICKS ");
    write_decimal_parts(stdout, time::game_ticks_parts());
    must_write(stdout, NEWLINE);
}

fn run_uname(stdout: io::Fd) {
    match process::run("/bin/uname.kx") {
        Ok(_) => {}
        Err(error) => write_run_error(stdout, error),
    }
}

fn run_ls(
    stdout: io::Fd,
    cwd: &WorkingDirectory,
    path_buffer: &mut PathBuffer,
    path: Option<&[u8]>,
) {
    let Some(path) = path else {
        match process::run("/bin/ls.kx") {
            Ok(_) => {}
            Err(error) => write_run_error(stdout, error),
        }
        return;
    };
    if cwd.resolve_into(path, path_buffer).is_err() {
        must_write(stdout, b"ERR INVAL\n");
        return;
    }
    let Ok(path) = path_buffer.as_str() else {
        must_write(stdout, b"ERR INVAL\n");
        return;
    };
    match process::run_with_args("/bin/ls.kx", &[path]) {
        Ok(_) => {}
        Err(error) => write_run_error(stdout, error),
    }
}

fn run_cat(stdout: io::Fd, cwd: &WorkingDirectory, path_buffer: &mut PathBuffer, path: &[u8]) {
    if cwd.resolve_into(path, path_buffer).is_err() {
        must_write(stdout, b"ERR INVAL\n");
        return;
    }
    let Ok(path) = path_buffer.as_str() else {
        must_write(stdout, b"ERR INVAL\n");
        return;
    };
    match process::run_with_args("/bin/cat.kx", &[path]) {
        Ok(_) => {}
        Err(error) => write_run_error(stdout, error),
    }
}

fn run_alloc_test(stdout: io::Fd) {
    match process::run("/bin/alloc-test.kx") {
        Ok(_) => {}
        Err(error) => write_run_error(stdout, error),
    }
}

fn write_run_error(stdout: io::Fd, error: process::Error) {
    match error {
        process::Error::InvalidArgument => must_write(stdout, b"ERR INVAL\n"),
        process::Error::Syscall(status) => {
            must_write(stdout, b"ERR ");
            must_write(stdout, run_error_name(status));
            must_write(stdout, NEWLINE);
        }
    }
}

fn run_error_name(status: u32) -> &'static [u8] {
    match status {
        0xffff_fffe => b"NOENT",
        0xffff_fff8 => b"NOEXEC",
        0xffff_fff4 => b"NOMEM",
        0xffff_fff2 => b"FAULT",
        0xffff_fff0 => b"BUSY",
        0xffff_ffea => b"INVAL",
        _ => b"RUN",
    }
}

fn write_decimal_parts(stdout: io::Fd, parts: time::U64Parts) {
    let mut digits = [0u8; 20];
    let mut start = digits.len() - 1;
    write_decimal_bits(&mut digits, &mut start, parts.high, 32);
    write_decimal_bits(&mut digits, &mut start, parts.low, 32);
    let mut index = start;
    while index < digits.len() {
        digits[index] += b'0';
        index += 1;
    }
    must_write(stdout, &digits[start..]);
}

fn write_decimal_bits(digits: &mut [u8; 20], start: &mut usize, bits: u32, count: u32) {
    let mut remaining = count;
    while remaining > 0 {
        remaining -= 1;
        let bit = ((bits >> remaining) & 1) as u8;
        double_decimal_digits_and_add_bit(digits, start, bit);
    }
}

fn double_decimal_digits_and_add_bit(digits: &mut [u8; 20], start: &mut usize, bit: u8) {
    let mut carry = bit;
    let mut index = digits.len();
    while index > *start {
        index -= 1;
        let value = digits[index] * 2 + carry;
        if value >= 10 {
            digits[index] = value - 10;
            carry = 1;
        } else {
            digits[index] = value;
            carry = 0;
        }
    }
    if carry != 0 && *start > 0 {
        *start -= 1;
        digits[*start] = carry;
    }
}

fn must_write(fd: io::Fd, bytes: &[u8]) {
    if fd.write_all(bytes).is_err() {
        process::exit(1);
    }
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
