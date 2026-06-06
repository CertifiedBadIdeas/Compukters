#![no_std]

#[cfg(test)]
extern crate std;

mod control;
mod exports;
mod int64;
mod memory;
mod time;
mod trap;

#[cfg(test)]
mod tests;

pub use control::{halt_forever, halt_once, yield_once};
pub use exports::{abort, memcmp, memcpy, memmove, memset};
pub use int64::{k16_div64, k16_mod64, k16_udiv64, k16_umod64};
pub use k16_abi::cpu;
pub use memory::{k16_memcmp, k16_memcpy, k16_memmove, k16_memset};
pub use time::{sleep_ticks, timer0_game_ticks, timer0_monotonic_nanos, yield_frames};
pub use trap::{
    disable_interrupts, enable_interrupts, install_trap_vector, interrupt_pending, iret_once,
    iret_with_r0, set_interrupt_mask, syscall0, syscall1, syscall_arg0, syscall_once, trap_cause,
    trap_pc, trap_value,
};
