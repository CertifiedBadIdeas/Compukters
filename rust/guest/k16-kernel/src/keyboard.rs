use k16_abi::computer::keyboard0;

use crate::{console, mmio};

pub fn drain_to_console() {
    let mut wrote = false;
    while status() != keyboard0::STATUS_EMPTY {
        let event_kind = unsafe { mmio::read_i32(keyboard0::EVENT_KIND) };
        let code = unsafe { mmio::read_i32(keyboard0::CODE) };
        if event_kind == keyboard0::EVENT_CHAR || event_kind == keyboard0::EVENT_PASTE_BYTE {
            console::write_byte(code as u8);
            wrote = true;
        }
        consume();
    }
    if wrote {
        console::flush();
    }
}

fn status() -> i32 {
    unsafe { mmio::read_i32(keyboard0::STATUS) }
}

fn consume() {
    unsafe {
        mmio::write_i32(keyboard0::COMMAND, keyboard0::COMMAND_CONSUME);
    }
}
