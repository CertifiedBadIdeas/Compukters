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

pub mod io {
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum Error {
        ShortWrite,
        Syscall(u32),
    }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub struct Fd(u32);

    impl Fd {
        pub const fn raw(self) -> u32 {
            self.0
        }

        pub fn write_all(self, bytes: &[u8]) -> Result<(), Error> {
            let returned = k16_rt::write_syscall(self.0, bytes.as_ptr(), bytes.len());
            if (returned as i32) < 0 {
                return Err(Error::Syscall(returned));
            }
            if returned != bytes.len() as u32 {
                return Err(Error::ShortWrite);
            }
            Ok(())
        }
    }

    pub fn stdin() -> Fd {
        Fd(k16_abi::syscall::FD_STDIN)
    }

    pub fn stdout() -> Fd {
        Fd(k16_abi::syscall::FD_STDOUT)
    }

    pub fn stderr() -> Fd {
        Fd(k16_abi::syscall::FD_STDERR)
    }
}

pub mod process {
    pub fn exit(status: u32) -> ! {
        k16_rt::exit_syscall(status)
    }
}

pub mod prelude {
    pub use crate::{debug, io, process, thread};
}
