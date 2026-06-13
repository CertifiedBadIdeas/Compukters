#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

const PROMPT: &[u8] = b"INIT> ";
const NEWLINE: &[u8] = b"\n";
const HELP: &[u8] = b"HELP\nCLEAR\nECHO\nTICKS\n";
const INPUT_CAPACITY: usize = 128;

#[no_mangle]
pub extern "C" fn main() -> ! {
    let stdin = io::stdin();
    let stdout = io::stdout();
    let mut input = [0u8; INPUT_CAPACITY];
    let mut read_buffer = [0u8; 1];

    must_write(stdout, b"K16 INIT\n");
    loop {
        must_write(stdout, PROMPT);
        read_and_dispatch_line(stdin, stdout, &mut input, &mut read_buffer);
    }
}

fn read_and_dispatch_line(
    stdin: io::Fd,
    stdout: io::Fd,
    input: &mut [u8; INPUT_CAPACITY],
    read_buffer: &mut [u8; 1],
) {
    clear_input(input);
    let mut line_len = 0;
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
                    if line_len < INPUT_CAPACITY {
                        input[line_len] = 0;
                    }
                    dispatch_line(stdout, input, line_len);
                    return;
                }
                b'\x08' | 0x7f => {
                    if line_len > 0 {
                        line_len -= 1;
                        must_write(stdout, b"\x08");
                    }
                }
                byte @ 0x20..=0x7e => {
                    if line_len < INPUT_CAPACITY {
                        input[line_len] = byte;
                        line_len += 1;
                        must_write(stdout, &[byte]);
                    }
                }
                _ => {}
            }
            index += 1;
        }
    }
}

fn dispatch_line(stdout: io::Fd, input: &[u8], line_len: usize) {
    if line_len == 0 {
        return;
    }
    if matches_command(input, b"help") {
        must_write(stdout, HELP);
    } else if matches_command(input, b"clear") {
        must_write(stdout, b"\x0c");
    } else if matches_command(input, b"ticks") {
        run_ticks(stdout);
    } else if is_echo_command(input, line_len) {
        run_echo(stdout, input, line_len);
    } else {
        must_write(stdout, b"ERR\n");
    }
}

fn clear_input(input: &mut [u8; INPUT_CAPACITY]) {
    let mut index = 0;
    while index < input.len() {
        input[index] = 0;
        index += 1;
    }
}

fn matches_command(input: &[u8], command: &[u8]) -> bool {
    let mut index = 0;
    while index < command.len() {
        if input[index] != command[index] {
            return false;
        }
        index += 1;
    }
    input[index] == 0
}

fn is_echo_command(input: &[u8], line_len: usize) -> bool {
    line_len >= 4
        && input[0] == b'e'
        && input[1] == b'c'
        && input[2] == b'h'
        && input[3] == b'o'
        && (line_len == 4 || input[4] == b' ')
}

fn run_echo(stdout: io::Fd, input: &[u8], line_len: usize) {
    let start = if line_len > 4 { 5 } else { 4 };
    must_write(stdout, &input[start..line_len]);
    must_write(stdout, NEWLINE);
}

fn run_ticks(stdout: io::Fd) {
    must_write(stdout, b"TICKS ");
    write_decimal_parts(stdout, time::game_ticks_parts());
    must_write(stdout, NEWLINE);
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
