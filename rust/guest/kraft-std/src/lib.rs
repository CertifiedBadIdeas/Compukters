#![no_std]

//! Experimental KraftOS userland library surface.
//!
//! Low-level CPU, CSR, MMIO, and raw syscall mechanics belong in `k16-abi` and
//! `k16-rt`. This crate is the higher-level guest API boundary that future
//! KraftOS programs should import.

pub mod debug {
    pub fn marker() -> u32 {
        k16_rt::debug_marker()
    }

    pub fn write_byte(byte: u8) -> u32 {
        k16_rt::debug_write_byte(byte)
    }
}

pub mod thread {
    pub fn yield_now() {
        let _ = k16_rt::yield_syscall();
    }

    pub fn sleep_ticks(ticks: u32) {
        let _ = k16_rt::sleep_ticks_syscall(ticks);
    }
}

pub mod prelude {
    pub use crate::{debug, thread};
}
