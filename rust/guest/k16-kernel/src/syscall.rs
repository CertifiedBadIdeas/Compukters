use k16_abi::syscall as abi_syscall;

use crate::{console, control, debug, timer, trap};

pub fn dispatch(number: u32) -> ! {
    match number {
        abi_syscall::DEBUG_MARKER => {
            debug::print_byte(b'S');
            control::set_ready();
            unsafe { k16_rt::iret_with_r0(abi_syscall::DEBUG_MARKER_RETURN) }
        }
        abi_syscall::DEBUG_WRITE_BYTE => {
            debug::print_byte((k16_rt::syscall_arg0() & 0xff) as u8);
            unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) }
        }
        abi_syscall::EXIT => {
            control::set_exit_code(k16_rt::syscall_arg0());
            control::set_halted();
            control::wait_forever()
        }
        abi_syscall::WRITE => {
            let fd = k16_rt::syscall_arg0();
            let ptr = k16_rt::syscall_arg1();
            let len = k16_rt::syscall_arg2();
            match write_fd(fd, ptr, len) {
                Ok(written) => unsafe { k16_rt::iret_with_r0(written) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::YIELD => {
            k16_rt::yield_once();
            unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) }
        }
        abi_syscall::SLEEP_TICKS => {
            timer::sleep_ticks(k16_rt::syscall_arg0());
            unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) }
        }
        _ => trap::unknown_syscall(number),
    }
}

fn write_fd(fd: u32, ptr: u32, len: u32) -> Result<u32, u32> {
    match fd {
        abi_syscall::FD_STDOUT | abi_syscall::FD_STDERR => write_guest_bytes(ptr, len),
        _ => Err(abi_syscall::ERROR_BAD_FD),
    }
}

fn write_guest_bytes(ptr: u32, len: u32) -> Result<u32, u32> {
    if len == 0 {
        return Ok(0);
    }
    let end = match ptr.checked_add(len) {
        Some(value) => value,
        None => return Err(abi_syscall::ERROR_FAULT),
    };
    if ptr < 0x0000_8000 || end > 0x0001_0000 {
        return Err(abi_syscall::ERROR_FAULT);
    }
    let mut offset = 0;
    while offset < len {
        let byte = unsafe { core::ptr::read_volatile((ptr + offset) as usize as *const u8) };
        console::write_byte(byte);
        offset += 1;
    }
    console::flush();
    Ok(len)
}
