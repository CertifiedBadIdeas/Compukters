use k16_abi::{computer::keyboard0, syscall as abi_syscall};

use crate::{mmio, user_buffer};

pub fn read(ptr: u32, len: u32) -> Result<u32, u32> {
    if len == 0 {
        return Ok(0);
    }
    if !user_buffer::valid_user_buffer(ptr, len) {
        return Err(abi_syscall::ERROR_FAULT);
    }

    let mut copied = 0;
    while copied == 0 {
        copied = drain_available_bytes(ptr, len)?;
        if copied == 0 {
            k16_rt::wait_once();
        }
    }
    Ok(copied)
}

fn drain_available_bytes(ptr: u32, len: u32) -> Result<u32, u32> {
    let mut copied = 0;
    while copied < len && status() != keyboard0::STATUS_EMPTY {
        let event_kind = unsafe { mmio::read_i32(keyboard0::EVENT_KIND) };
        let code = unsafe { mmio::read_i32(keyboard0::CODE) };
        if let Some(byte) = event_byte(event_kind, code) {
            let bytes = [byte];
            user_buffer::copy_to_user(ptr + copied, &bytes)?;
            copied += 1;
        }
        consume();
    }
    Ok(copied)
}

fn event_byte(event_kind: i32, code: i32) -> Option<u8> {
    if event_kind == keyboard0::EVENT_CHAR || event_kind == keyboard0::EVENT_PASTE_BYTE {
        return Some((code & 0xff) as u8);
    }
    if event_kind == keyboard0::EVENT_KEY_DOWN {
        return key_down_byte(code);
    }
    None
}

fn key_down_byte(code: i32) -> Option<u8> {
    match code {
        keyboard0::KEY_ENTER | keyboard0::KEY_KP_ENTER => Some(b'\n'),
        keyboard0::KEY_BACKSPACE => Some(b'\x08'),
        _ => None,
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
