use k16_abi::syscall as abi_syscall;

use crate::{control, debug, timer, trap};

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
        abi_syscall::YIELD => {
            k16_rt::yield_once();
            unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) }
        }
        abi_syscall::SLEEP_TICKS => {
            timer::sleep_ticks(k16_rt::syscall_arg0());
            unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) }
        }
        _ => trap::kernel_trap(),
    }
}
