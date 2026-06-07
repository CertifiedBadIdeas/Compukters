use crate::console;

const PROMPT: &[u8] = b"K16> ";

pub fn init() {
    prompt();
    console::flush();
}

pub fn handle_line(line_addr: u32, line_len: usize) -> bool {
    if line_len == 0 {
        prompt();
    } else if is_ok(line_addr, line_len) {
        console::write_bytes(b"OK\n");
        prompt();
    } else if is_clear(line_addr, line_len) {
        console::clear();
        prompt();
    } else if is_echo(line_addr, line_len) {
        write_echo_tail(line_addr, line_len);
        console::write_byte(b'\n');
        prompt();
    } else {
        console::write_bytes(b"ERR\n");
        prompt();
    }
    console::flush();
    true
}

fn prompt() {
    console::write_bytes(PROMPT);
}

fn is_ok(line_addr: u32, line_len: usize) -> bool {
    line_len == 2 && read_line_byte(line_addr, 0) == b'o' && read_line_byte(line_addr, 1) == b'k'
}

fn is_clear(line_addr: u32, line_len: usize) -> bool {
    line_len == 5
        && read_line_byte(line_addr, 0) == b'c'
        && read_line_byte(line_addr, 1) == b'l'
        && read_line_byte(line_addr, 2) == b'e'
        && read_line_byte(line_addr, 3) == b'a'
        && read_line_byte(line_addr, 4) == b'r'
}

fn is_echo(line_addr: u32, line_len: usize) -> bool {
    line_len >= 4
        && read_line_byte(line_addr, 0) == b'e'
        && read_line_byte(line_addr, 1) == b'c'
        && read_line_byte(line_addr, 2) == b'h'
        && read_line_byte(line_addr, 3) == b'o'
        && (line_len == 4 || read_line_byte(line_addr, 4) == b' ')
}

fn write_echo_tail(line_addr: u32, line_len: usize) {
    let mut offset = 5;
    while offset < line_len {
        console::write_byte(display_byte(read_line_byte(line_addr, offset)));
        offset += 1;
    }
}

fn read_line_byte(line_addr: u32, offset: usize) -> u8 {
    unsafe { core::ptr::read_volatile((line_addr + offset as u32) as usize as *const u8) }
}

fn display_byte(byte: u8) -> u8 {
    if byte >= b'a' && byte <= b'z' {
        byte - 32
    } else {
        byte
    }
}
