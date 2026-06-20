#![no_std]

#[cfg(any(test, feature = "host-test"))]
extern crate std;

mod control;
mod exports;
mod int64;
mod memory;
mod time;
mod trap;

#[cfg(test)]
mod tests;

#[cfg(any(test, feature = "host-test"))]
pub mod host_test {
    pub use k16_abi::syscall::{
        BRK, CLOSE, DEBUG_MARKER, DEBUG_MARKER_RETURN, DEBUG_WRITE_BYTE, ERROR_BAD_FD, ERROR_FAULT,
        ERROR_NOT_EMPTY, ERROR_NO_ENTRY, ERROR_NO_MEMORY, EXIT, FD_STDERR, FD_STDIN, FD_STDOUT,
        GAME_TICKS, MKDIR, OPEN, READ, READ_DIR, RENAME, RMDIR, RUN, RUN_FORMAT_ARGV, SBRK, SEEK,
        SLEEP_TICKS, SPAWN, STAT, STATUS_OK, UNLINK, WAIT, WRITE, YIELD,
    };

    pub fn reset_syscalls() {
        crate::trap::reset_test_interrupts();
    }

    pub fn set_syscall_return(value: u32) {
        crate::trap::set_test_syscall_return(value);
    }

    pub fn syscall_number() -> u32 {
        crate::trap::test_syscall_number()
    }

    pub fn syscall_arg0() -> u32 {
        crate::trap::test_syscall_arg0()
    }

    pub fn syscall_arg1() -> u32 {
        crate::trap::test_syscall_arg1()
    }

    pub fn syscall_arg2() -> u32 {
        crate::trap::test_syscall_arg2()
    }

    pub fn reset_timer0() {
        crate::time::reset_test_timer0();
    }

    pub fn set_timer0_game_ticks(value: u64) {
        crate::time::set_test_timer0_game_ticks(value);
    }

    pub fn set_timer0_monotonic_nanos(value: u64) {
        crate::time::set_test_timer0_monotonic_nanos(value);
    }

    pub fn yield_count() -> u64 {
        crate::control::test_yield_count()
    }

    pub fn set_trap_state(cause: u32, pc: u32, value: u32) {
        crate::trap::set_test_trap_state(cause, pc, value);
    }

    pub fn trap_vector() -> u32 {
        crate::trap::test_trap_vector()
    }

    pub fn interrupt_enable() -> u32 {
        crate::trap::test_interrupt_enable()
    }

    pub fn interrupt_mask() -> u32 {
        crate::trap::test_interrupt_mask()
    }
}

pub use control::{halt_forever, halt_once, wait_once, yield_once};
pub use exports::{abort, memcmp, memcpy, memmove, memset};
pub use int64::{k16_div64, k16_mod64, k16_udiv64, k16_umod64};
pub use k16_abi::cpu;
pub use memory::{k16_memcmp, k16_memcpy, k16_memmove, k16_memset};
pub use time::{
    sleep_ticks, timer0_game_ticks, timer0_game_ticks_high, timer0_game_ticks_low,
    timer0_game_ticks_parts, timer0_monotonic_nanos, timer0_monotonic_nanos_parts, yield_frames,
    U64Parts,
};
pub use trap::{
    brk_syscall, close_syscall, debug_marker, debug_write_byte, disable_interrupts,
    enable_interrupts, exit_syscall, game_ticks_syscall, install_trap_vector, interrupt_pending,
    iret_once, iret_with_r0, mkdir_syscall, open_syscall, read_dir_syscall, read_syscall,
    rename_syscall, restore_trap_frame, rmdir_syscall, run_argv_syscall, save_trap_frame,
    sbrk_syscall, seek_syscall, set_interrupt_mask, sleep_ticks_syscall, spawn_argv_syscall,
    stat_syscall, syscall0, syscall1, syscall3, syscall_arg0, syscall_arg1, syscall_arg2,
    syscall_once, trap_cause, trap_pc, trap_value, unlink_syscall, wait_syscall, write_syscall,
    yield_syscall, TrapFrame,
};
