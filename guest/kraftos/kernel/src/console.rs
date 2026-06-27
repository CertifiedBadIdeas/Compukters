use crate::terminal;

pub fn init() {
    terminal::init();
}

pub fn clear() {
    terminal::clear();
}

pub fn write_bytes(bytes: &[u8]) {
    terminal::write_bytes(bytes);
}

pub fn write_byte(byte: u8) {
    terminal::write_byte(byte);
}

pub fn flush() {
    terminal::flush();
}
