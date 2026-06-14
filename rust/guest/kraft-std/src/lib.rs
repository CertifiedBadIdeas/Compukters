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

pub mod fs {
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum Error {
        Syscall(u32),
    }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub struct File(u32);

    impl File {
        #[inline(always)]
        pub const fn from_raw(fd: u32) -> Self {
            Self(fd)
        }

        #[inline(always)]
        pub const fn raw(self) -> u32 {
            self.0
        }

        pub fn read(self, bytes: &mut [u8]) -> Result<usize, Error> {
            let returned = k16_rt::read_syscall(self.0, bytes.as_mut_ptr(), bytes.len());
            if is_error_status(returned) {
                return Err(Error::Syscall(returned));
            }
            Ok(returned as usize)
        }

        pub fn close(self) -> Result<(), Error> {
            let returned = k16_rt::close_syscall(self.0);
            if is_error_status(returned) {
                return Err(Error::Syscall(returned));
            }
            Ok(())
        }
    }

    pub fn open(path: &str) -> Result<File, Error> {
        let returned = k16_rt::open_syscall(path.as_ptr(), path.len(), 0);
        if is_error_status(returned) {
            return Err(Error::Syscall(returned));
        }
        Ok(File(returned))
    }

    #[inline(always)]
    fn is_error_status(status: u32) -> bool {
        status & 0x8000_0000 != 0
    }
}

pub mod heap {
    use core::alloc::{GlobalAlloc, Layout};
    use core::ptr;

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum Error {
        Syscall(u32),
    }

    pub struct SbrkAllocator;

    unsafe impl GlobalAlloc for SbrkAllocator {
        unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
            let Some(delta) = allocation_delta(layout) else {
                return ptr::null_mut();
            };
            let old_break = match sbrk(delta) {
                Ok(old_break) => old_break,
                Err(_) => return ptr::null_mut(),
            };
            let Some(aligned) = align_up(old_break, layout.align() as u32) else {
                return ptr::null_mut();
            };
            aligned as usize as *mut u8
        }

        unsafe fn dealloc(&self, _ptr: *mut u8, _layout: Layout) {}
    }

    pub fn brk(address: u32) -> Result<u32, Error> {
        let returned = k16_rt::brk_syscall(address);
        if is_error_status(returned) {
            return Err(Error::Syscall(returned));
        }
        Ok(returned)
    }

    pub fn sbrk(delta: u32) -> Result<u32, Error> {
        let returned = k16_rt::sbrk_syscall(delta);
        if is_error_status(returned) {
            return Err(Error::Syscall(returned));
        }
        Ok(returned)
    }

    fn allocation_delta(layout: Layout) -> Option<u32> {
        let size = u32::try_from(layout.size()).ok()?;
        let align = u32::try_from(layout.align()).ok()?;
        size.checked_add(align.checked_sub(1)?)
    }

    fn align_up(value: u32, alignment: u32) -> Option<u32> {
        let mask = alignment.checked_sub(1)?;
        value.checked_add(mask).map(|value| value & !mask)
    }

    #[inline(always)]
    fn is_error_status(status: u32) -> bool {
        status & 0x8000_0000 != 0
    }
}

#[cfg(not(any(test, feature = "host-test")))]
#[global_allocator]
static GLOBAL_ALLOCATOR: heap::SbrkAllocator = heap::SbrkAllocator;

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
    pub use crate::{debug, fs, heap, io, process, thread, time};
}
