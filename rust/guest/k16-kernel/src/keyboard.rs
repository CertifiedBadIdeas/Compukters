use k16_abi::computer::keyboard0;

use crate::{line, mmio};

pub fn drain_to_line() {
    let mut wrote = false;
    while status() != keyboard0::STATUS_EMPTY {
        let event_kind = unsafe { mmio::read_i32(keyboard0::EVENT_KIND) };
        let code = unsafe { mmio::read_i32(keyboard0::CODE) };
        if event_kind == keyboard0::EVENT_CHAR || event_kind == keyboard0::EVENT_PASTE_BYTE {
            wrote |= line::input_byte(code as u8);
        } else if event_kind == keyboard0::EVENT_KEY_DOWN {
            wrote |= input_key_down(code);
        }
        consume();
    }
    if wrote {
        line::flush();
    }
}

fn status() -> i32 {
    unsafe { mmio::read_i32(keyboard0::STATUS) }
}

fn input_key_down(code: i32) -> bool {
    match code {
        keyboard0::KEY_ENTER | keyboard0::KEY_KP_ENTER => line::input_byte(b'\n'),
        keyboard0::KEY_BACKSPACE => line::input_byte(b'\x08'),
        _ => false,
    }
}

fn consume() {
    unsafe {
        mmio::write_i32(keyboard0::COMMAND, keyboard0::COMMAND_CONSUME);
    }
}
