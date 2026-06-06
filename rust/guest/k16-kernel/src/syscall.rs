use k16_abi::syscall as abi_syscall;

use crate::{control, debug, timer, trap};

#[derive(Clone, Copy)]
enum KernelSyscall {
    DebugMarker,
    DebugWriteByte,
    Yield,
    SleepTicks,
}

impl KernelSyscall {
    fn from_raw(number: u32) -> Option<Self> {
        match number {
            abi_syscall::DEBUG_MARKER => Some(Self::DebugMarker),
            abi_syscall::DEBUG_WRITE_BYTE => Some(Self::DebugWriteByte),
            abi_syscall::YIELD => Some(Self::Yield),
            abi_syscall::SLEEP_TICKS => Some(Self::SleepTicks),
            _ => None,
        }
    }
}

pub fn dispatch(number: u32) -> ! {
    let Some(syscall) = KernelSyscall::from_raw(number) else {
        trap::kernel_trap();
    };

    dispatch_kernel_syscall(syscall)
}

fn dispatch_kernel_syscall(syscall: KernelSyscall) -> ! {
    match syscall {
        KernelSyscall::DebugMarker => {
            debug::print_byte(b'S');
            control::set_ready();
            unsafe { k16_rt::iret_with_r0(abi_syscall::DEBUG_MARKER_RETURN) }
        }
        KernelSyscall::DebugWriteByte => {
            debug::print_byte((k16_rt::syscall_arg0() & 0xff) as u8);
            unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) }
        }
        KernelSyscall::Yield => {
            k16_rt::yield_once();
            unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) }
        }
        KernelSyscall::SleepTicks => {
            timer::sleep_ticks(k16_rt::syscall_arg0());
            unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) }
        }
    }
}
