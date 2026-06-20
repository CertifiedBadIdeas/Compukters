#![no_std]
#![no_main]

use core::panic::PanicInfo;

use k16_shell::{
    resolve_executable_path, Command, CommandArgs, InputLine, PathBuffer, WorkingDirectory,
};
use kraft_std::prelude::*;

const PROMPT: &[u8] = b"K16> ";
const NEWLINE: &[u8] = b"\n";
const HELP: &[u8] =
    b"HELP\nCLEAR\nPWD\nCD [PATH]\nECHO\nTICKS\nSTATUS\nUNAME\nLS [PATH...]\nCAT <PATH...>\nCP <SRC> <DST>\nMV <SRC> <DST>\nSTAT <PATH...>\nWRITE [--append] <PATH> <TEXT>\nRM <PATH...>\nMKDIR <PATH...>\nRMDIR <PATH...>\nALLOC\n";
const BIN_PREFIX: &[u8] = b"/bin/";
const PROGRAM_SUFFIX: &[u8] = b".kx";
const ALLOC_ALIAS: &[u8] = b"alloc";
const ALLOC_PROGRAM: &[u8] = b"alloc-test";

#[no_mangle]
pub extern "C" fn main() -> ! {
    let stdin = io::stdin();
    let stdout = io::stdout();
    let mut input = InputLine::new();
    let mut cwd = WorkingDirectory::new();
    let mut last_status = k16_abi::syscall::STATUS_OK;
    let mut read_buffer = [0u8; 1];
    let mut path_buffer = PathBuffer::new();
    let mut program_path = PathBuffer::new();
    let mut arg_paths = [
        PathBuffer::new(),
        PathBuffer::new(),
        PathBuffer::new(),
        PathBuffer::new(),
    ];

    must_write(stdout, b"K16 SHELL\n");
    loop {
        must_write(stdout, PROMPT);
        read_and_dispatch_line(
            stdin,
            stdout,
            &mut input,
            &mut cwd,
            &mut last_status,
            &mut path_buffer,
            &mut program_path,
            &mut arg_paths,
            &mut read_buffer,
        );
    }
}

fn read_and_dispatch_line(
    stdin: io::Fd,
    stdout: io::Fd,
    input: &mut InputLine,
    cwd: &mut WorkingDirectory,
    last_status: &mut u32,
    path_buffer: &mut PathBuffer,
    program_path: &mut PathBuffer,
    arg_paths: &mut [PathBuffer; k16_abi::syscall::MAX_RUN_ARGS],
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
                    dispatch_command(
                        stdout,
                        cwd,
                        last_status,
                        path_buffer,
                        program_path,
                        arg_paths,
                        input.command(),
                    );
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
    last_status: &mut u32,
    path_buffer: &mut PathBuffer,
    program_path: &mut PathBuffer,
    arg_paths: &mut [PathBuffer; k16_abi::syscall::MAX_RUN_ARGS],
    command: Command<'_>,
) {
    match command {
        Command::Empty => {}
        Command::Invalid => {
            must_write(stdout, b"ERR INVAL\n");
            *last_status = k16_abi::syscall::ERROR_INVALID;
        }
        Command::Help => {
            must_write(stdout, HELP);
            *last_status = k16_abi::syscall::STATUS_OK;
        }
        Command::Clear => {
            must_write(stdout, b"\x0c");
            *last_status = k16_abi::syscall::STATUS_OK;
        }
        Command::Pwd => {
            run_pwd(stdout, cwd);
            *last_status = k16_abi::syscall::STATUS_OK;
        }
        Command::Cd(path) => {
            *last_status = run_cd(stdout, cwd, path_buffer, path);
        }
        Command::Ticks => {
            *last_status = run_ticks(stdout);
        }
        Command::Status => write_status(stdout, *last_status),
        Command::Echo(bytes) => {
            must_write(stdout, bytes);
            must_write(stdout, NEWLINE);
            *last_status = k16_abi::syscall::STATUS_OK;
        }
        Command::Exec { name, args } => {
            *last_status = run_exec(stdout, cwd, arg_paths, program_path, name, args);
        }
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
) -> u32 {
    let path = path.unwrap_or(b"/");
    if cwd.resolve_into(path, path_buffer).is_err() {
        must_write(stdout, b"ERR INVAL\n");
        return k16_abi::syscall::ERROR_INVALID;
    }
    let Ok(path) = path_buffer.as_str() else {
        must_write(stdout, b"ERR INVAL\n");
        return k16_abi::syscall::ERROR_INVALID;
    };
    match fs::metadata(path) {
        Ok(metadata) if metadata.file_type == fs::FileType::Directory => {
            if cwd.set_from_resolved(path_buffer).is_err() {
                must_write(stdout, b"ERR INVAL\n");
                k16_abi::syscall::ERROR_INVALID
            } else {
                k16_abi::syscall::STATUS_OK
            }
        }
        Ok(_) => {
            must_write(stdout, b"ERR INVAL\n");
            k16_abi::syscall::ERROR_INVALID
        }
        Err(fs::Error::InvalidArgument) => {
            must_write(stdout, b"ERR INVAL\n");
            k16_abi::syscall::ERROR_INVALID
        }
        Err(fs::Error::Syscall(status)) => {
            must_write(stdout, b"ERR ");
            must_write(stdout, run_error_name(status));
            must_write(stdout, NEWLINE);
            status
        }
    }
}

fn run_ticks(stdout: io::Fd) -> u32 {
    must_write(stdout, b"TICKS ");
    let mut bytes = [0u8; k16_abi::syscall::GAME_TICKS_BYTES];
    match time::game_ticks_bytes(&mut bytes) {
        Ok(()) => {
            let low = u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
            let high = u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]);
            write_decimal_words(stdout, high, low);
            must_write(stdout, NEWLINE);
            k16_abi::syscall::STATUS_OK
        }
        Err(time::Error::Syscall(status)) => {
            must_write(stdout, b"ERR ");
            must_write(stdout, run_error_name(status));
            must_write(stdout, NEWLINE);
            status
        }
    }
}

fn run_exec(
    stdout: io::Fd,
    cwd: &WorkingDirectory,
    arg_paths: &mut [PathBuffer; k16_abi::syscall::MAX_RUN_ARGS],
    program_path: &mut PathBuffer,
    name: &[u8],
    args: CommandArgs<'_>,
) -> u32 {
    if command_name_has_separator(name) {
        if resolve_executable_path(cwd, name, program_path).is_err() {
            must_write(stdout, b"ERR INVAL\n");
            return k16_abi::syscall::ERROR_INVALID;
        }
    } else if build_program_path(name, program_path).is_err() {
        must_write(stdout, b"ERR INVAL\n");
        return k16_abi::syscall::ERROR_INVALID;
    }
    let Ok(program_path) = program_path.as_str() else {
        must_write(stdout, b"ERR INVAL\n");
        return k16_abi::syscall::ERROR_INVALID;
    };
    if args.is_empty() {
        return match process::run(program_path) {
            Ok(status) => {
                write_child_exit_status(stdout, status.code());
                status.code()
            }
            Err(error) => write_run_error(stdout, error),
        };
    }

    let raw_args = args.as_slice();
    let mut argv = [""; k16_abi::syscall::MAX_RUN_ARGS];
    if should_resolve_path_arg(name) {
        let mut index = 0;
        while index < raw_args.len() {
            let raw_arg = raw_args[index];
            if cwd.resolve_into(raw_arg, &mut arg_paths[index]).is_err() {
                must_write(stdout, b"ERR INVAL\n");
                return k16_abi::syscall::ERROR_INVALID;
            }
            index += 1;
        }
        let mut index = 0;
        while index < raw_args.len() {
            let Ok(arg) = arg_paths[index].as_str() else {
                must_write(stdout, b"ERR INVAL\n");
                return k16_abi::syscall::ERROR_INVALID;
            };
            argv[index] = arg;
            index += 1;
        }
    } else {
        let mut index = 0;
        while index < raw_args.len() {
            let raw_arg = raw_args[index];
            let Ok(arg) = core::str::from_utf8(raw_arg) else {
                must_write(stdout, b"ERR INVAL\n");
                return k16_abi::syscall::ERROR_INVALID;
            };
            argv[index] = arg;
            index += 1;
        }
    }
    match process::run_with_args(program_path, &argv[..raw_args.len()]) {
        Ok(status) => {
            write_child_exit_status(stdout, status.code());
            status.code()
        }
        Err(error) => write_run_error(stdout, error),
    }
}

fn should_resolve_path_arg(name: &[u8]) -> bool {
    matches!(name, b"ls" | b"cat" | b"cp" | b"mv")
}

fn command_name_has_separator(name: &[u8]) -> bool {
    let mut index = 0;
    while index < name.len() {
        if name[index] == b'/' {
            return true;
        }
        index += 1;
    }
    false
}

fn build_program_path(name: &[u8], out: &mut PathBuffer) -> Result<(), k16_shell::PathError> {
    let name = if name == ALLOC_ALIAS {
        ALLOC_PROGRAM
    } else {
        name
    };
    out.replace_with_parts(BIN_PREFIX, name, PROGRAM_SUFFIX)
}

fn write_run_error(stdout: io::Fd, error: process::Error) -> u32 {
    match error {
        process::Error::InvalidArgument => {
            must_write(stdout, b"ERR INVAL\n");
            k16_abi::syscall::ERROR_INVALID
        }
        process::Error::Syscall(status) => {
            must_write(stdout, b"ERR ");
            must_write(stdout, run_error_name(status));
            must_write(stdout, NEWLINE);
            status
        }
    }
}

fn write_status(stdout: io::Fd, status: u32) {
    must_write(stdout, b"STATUS ");
    if status & 0x8000_0000 != 0 {
        must_write(stdout, run_error_name(status));
    } else {
        write_decimal_words(stdout, 0, status);
    }
    must_write(stdout, NEWLINE);
}

fn write_child_exit_status(stdout: io::Fd, status: u32) {
    if status == 0 {
        return;
    }
    must_write(stdout, b"ERR EXIT ");
    write_decimal_words(stdout, 0, status);
    must_write(stdout, NEWLINE);
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

fn write_decimal_words(stdout: io::Fd, high: u32, low: u32) {
    let mut digits = [0u8; 20];
    let mut start = digits.len() - 1;
    write_decimal_bits(&mut digits, &mut start, high, 32);
    write_decimal_bits(&mut digits, &mut start, low, 32);
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
