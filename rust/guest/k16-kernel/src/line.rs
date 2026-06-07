use crate::console;

const MAX_LINE_BYTES: usize = 128;
const LINE_BUFFER_ADDR: u32 = 0x0000_8800;

static mut BUFFER_LEN: usize = 0;
static mut COMPLETED_LEN: usize = 0;

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
        write_buffer_byte(BUFFER_LEN, byte);
        BUFFER_LEN += 1;
    }
    console::write_byte(byte);
    true
}

fn backspace() -> bool {
    unsafe {
        if BUFFER_LEN == 0 {
            return false;
        }
        BUFFER_LEN -= 1;
        write_buffer_byte(BUFFER_LEN, 0);
    }
    console::write_byte(b'\x08');
    true
}

fn complete_current() -> bool {
    unsafe {
        COMPLETED_LEN = BUFFER_LEN;
        BUFFER_LEN = 0;
    }
    console::write_byte(b'\n');
    true
}

fn write_buffer_byte(index: usize, value: u8) {
    unsafe {
        core::ptr::write_volatile((LINE_BUFFER_ADDR + index as u32) as usize as *mut u8, value);
    }
}
