use crate::{console, shell};

const MAX_LINE_BYTES: usize = 128;
const LINE_BUFFER_ADDR: u32 = 0x0000_8800;

static mut BUFFER_LEN: usize = 0;

pub fn input_byte(byte: u8) -> bool {
    match byte {
        b'\n' | b'\r' => complete_current(),
        b'\x08' | 0x7f => backspace(),
        0x20..=0x7e => write_printable(byte),
        _ => false,
    }
}

pub fn flush() {
    console::flush();
}

fn write_printable(byte: u8) -> bool {
    unsafe {
        if BUFFER_LEN >= MAX_LINE_BYTES {
            return false;
        }
        core::ptr::write_volatile(
            (LINE_BUFFER_ADDR + BUFFER_LEN as u32) as usize as *mut u8,
            byte,
        );
        BUFFER_LEN += 1;
    }
    console::write_byte(display_byte(byte));
    true
}

fn backspace() -> bool {
    unsafe {
        if BUFFER_LEN == 0 {
            return false;
        }
        BUFFER_LEN -= 1;
    }
    console::write_byte(b'\x08');
    true
}

fn complete_current() -> bool {
    let completed_len = unsafe {
        let completed_len = BUFFER_LEN;
        BUFFER_LEN = 0;
        completed_len
    };
    console::write_byte(b'\n');
    shell::handle_line(LINE_BUFFER_ADDR, completed_len)
}

fn display_byte(byte: u8) -> u8 {
    if byte >= b'a' && byte <= b'z' {
        byte - 32
    } else {
        byte
    }
}
