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

pub mod time {
    pub type U64Parts = k16_rt::U64Parts;

    pub fn game_ticks() -> u64 {
        k16_rt::timer0_game_ticks()
    }

    pub fn game_ticks_parts() -> U64Parts {
        k16_rt::timer0_game_ticks_parts()
    }
}

pub mod io {
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum Error {
        ShortWrite,
        Syscall(u32),
    }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub struct Fd(u32);

    impl Fd {
        #[inline(always)]
        pub const fn raw(self) -> u32 {
            self.0
        }

        #[inline(always)]
        pub fn write_all(self, bytes: &[u8]) -> Result<(), Error> {
            let returned = k16_rt::write_syscall(self.0, bytes.as_ptr(), bytes.len());
            if is_error_status(returned) {
                return Err(Error::Syscall(returned));
            }
            if returned != bytes.len() as u32 {
                return Err(Error::ShortWrite);
            }
            Ok(())
        }

        #[inline(always)]
        pub fn read(self, bytes: &mut [u8]) -> Result<usize, Error> {
            let returned = k16_rt::read_syscall(self.0, bytes.as_mut_ptr(), bytes.len());
            if is_error_status(returned) {
                return Err(Error::Syscall(returned));
            }
            Ok(returned as usize)
        }
    }

    #[inline(always)]
    fn is_error_status(status: u32) -> bool {
        status & 0x8000_0000 != 0
    }

    #[inline(always)]
    pub fn stdin() -> Fd {
        Fd(k16_abi::syscall::FD_STDIN)
    }

    #[inline(always)]
    pub fn stdout() -> Fd {
        Fd(k16_abi::syscall::FD_STDOUT)
    }

    #[inline(always)]
    pub fn stderr() -> Fd {
        Fd(k16_abi::syscall::FD_STDERR)
    }
}

pub mod process {
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum Error {
        Syscall(u32),
    }

    pub fn exit(status: u32) -> ! {
        k16_rt::exit_syscall(status)
    }

    #[inline(always)]
    pub fn run(path: &str) -> Result<u32, Error> {
        let returned = k16_rt::run_syscall(path.as_ptr(), path.len());
        if returned & 0x8000_0000 != 0 {
            return Err(Error::Syscall(returned));
        }
        Ok(returned)
    }
}

pub mod prelude {
    pub use crate::{debug, io, process, thread, time};
}
