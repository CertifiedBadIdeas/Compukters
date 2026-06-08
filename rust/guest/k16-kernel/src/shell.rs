use crate::{console, timer};

const PROMPT: &[u8] = b"K16> ";

pub fn init() {
    write_prompt();
    console::flush();
}

pub fn handle_line(line_addr: u32, line_len: usize) -> bool {
    dispatch_line(line_addr, line_len);
    console::flush();
    true
}

fn dispatch_line(line_addr: u32, line_len: usize) {
    if line_len == 0 {
        run_empty();
    } else if is_ok(line_addr, line_len) {
        run_ok();
    } else if is_help(line_addr, line_len) {
        run_help();
    } else if is_clear(line_addr, line_len) {
        run_clear();
    } else if is_ticks(line_addr, line_len) {
        run_ticks();
    } else if is_echo_command(line_addr, line_len) {
        run_echo(line_addr, line_len);
    } else {
        run_unknown();
    }
}

fn write_prompt() {
    console::write_bytes(PROMPT);
}

fn is_ok(line_addr: u32, line_len: usize) -> bool {
    matches_command(line_addr, line_len, b"ok")
}

fn is_clear(line_addr: u32, line_len: usize) -> bool {
    matches_command(line_addr, line_len, b"clear")
}

fn is_help(line_addr: u32, line_len: usize) -> bool {
    matches_command(line_addr, line_len, b"help")
}

fn is_ticks(line_addr: u32, line_len: usize) -> bool {
    matches_command(line_addr, line_len, b"ticks")
}

fn is_echo(line_addr: u32, line_len: usize) -> bool {
    is_echo_command(line_addr, line_len)
}

fn matches_command(line_addr: u32, line_len: usize, command: &[u8]) -> bool {
    if line_len != command.len() {
        return false;
    }
    let mut offset = 0;
    while offset < command.len() {
        if read_line_byte(line_addr, offset) != command[offset] {
            return false;
        }
        offset += 1;
    }
    true
}

fn is_echo_command(line_addr: u32, line_len: usize) -> bool {
    line_len >= 4
        && read_line_byte(line_addr, 0) == b'e'
        && read_line_byte(line_addr, 1) == b'c'
        && read_line_byte(line_addr, 2) == b'h'
        && read_line_byte(line_addr, 3) == b'o'
        && (line_len == 4 || read_line_byte(line_addr, 4) == b' ')
}

fn run_empty() {
    write_prompt();
}

fn run_ok() {
    console::write_bytes(b"OK\n");
    write_prompt();
}

fn run_help() {
    console::write_bytes(b"HELP\nOK\nCLEAR\nECHO\nTICKS\n");
    write_prompt();
}

fn run_clear() {
    console::clear();
    write_prompt();
}

fn run_echo(line_addr: u32, line_len: usize) {
    write_echo_tail(line_addr, line_len);
    console::write_byte(b'\n');
    write_prompt();
}

fn run_ticks() {
    console::write_bytes(b"TICKS ");
    timer::now_ticks().write_decimal();
    console::write_byte(b'\n');
    write_prompt();
}

fn run_unknown() {
    console::write_bytes(b"ERR\n");
    write_prompt();
}

fn write_echo_tail(line_addr: u32, line_len: usize) {
    let mut offset = 5;
    while offset < line_len {
        console::write_byte(read_line_byte(line_addr, offset));
        offset += 1;
    }
}

fn read_line_byte(line_addr: u32, offset: usize) -> u8 {
    unsafe { core::ptr::read_volatile((line_addr + offset as u32) as usize as *const u8) }
}
