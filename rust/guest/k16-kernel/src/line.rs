use crate::{console, shell};

const MAX_LINE_BYTES: usize = 128;
const LINE_BUFFER_ADDR: u32 = 0x0000_8800;

static mut BUFFER_LEN: usize = 0;

pub fn input_byte(byte: u8) -> bool {
    match byte {
        b'\n' | b'\r' => complete_line(),
        b'\x08' | 0x7f => erase_previous_byte(),
        0x20..=0x7e => append_printable_byte(byte),
        _ => false,
    }
}

pub fn flush() {
    console::flush();
}

fn append_printable_byte(byte: u8) -> bool {
    if line_is_full() {
        return false;
    }
    store_line_byte(byte);
    echo_printable_byte(byte);
    true
}

fn erase_previous_byte() -> bool {
    unsafe {
        if BUFFER_LEN == 0 {
            return false;
        }
        BUFFER_LEN -= 1;
    }
    echo_backspace();
    true
}

fn complete_line() -> bool {
    let completed_len = reset_buffer();
    console::write_byte(b'\n');
    shell::handle_line(LINE_BUFFER_ADDR, completed_len)
}

fn line_is_full() -> bool {
    unsafe { BUFFER_LEN >= MAX_LINE_BYTES }
}

fn reset_buffer() -> usize {
    unsafe {
        let completed_len = BUFFER_LEN;
        BUFFER_LEN = 0;
        completed_len
    }
}

fn store_line_byte(byte: u8) {
    unsafe {
        core::ptr::write_volatile(
            (LINE_BUFFER_ADDR + BUFFER_LEN as u32) as usize as *mut u8,
            byte,
        );
        BUFFER_LEN += 1;
    }
}

fn echo_printable_byte(byte: u8) {
    console::write_byte(byte);
}

fn echo_backspace() {
    console::write_byte(b'\x08');
}
