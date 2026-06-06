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

pub mod prelude {
    pub use crate::debug;
}
