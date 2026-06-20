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

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum Error {
        Syscall(u32),
    }

    pub fn game_ticks() -> Result<u64, Error> {
        let parts = game_ticks_parts()?;
        Ok((u64::from(parts.high) << 32) | u64::from(parts.low))
    }

    pub fn game_ticks_parts() -> Result<U64Parts, Error> {
        let mut bytes = [0u8; k16_abi::syscall::GAME_TICKS_BYTES];
        game_ticks_bytes(&mut bytes)?;
        Ok(U64Parts {
            low: u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
            high: u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]),
        })
    }

    pub fn game_ticks_bytes(
        bytes: &mut [u8; k16_abi::syscall::GAME_TICKS_BYTES],
    ) -> Result<(), Error> {
        let returned = k16_rt::game_ticks_syscall(bytes.as_mut_ptr());
        if returned != k16_abi::syscall::STATUS_OK {
            return Err(Error::Syscall(returned));
        }
        Ok(())
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
        InvalidArgument,
        Syscall(u32),
    }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub struct File(u32);

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum FileType {
        Regular,
        Directory,
    }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub struct Metadata {
        pub file_type: FileType,
        pub size_bytes: u32,
    }

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

        pub fn write_all(self, bytes: &[u8]) -> Result<(), Error> {
            let returned = k16_rt::write_syscall(self.0, bytes.as_ptr(), bytes.len());
            if is_error_status(returned) {
                return Err(Error::Syscall(returned));
            }
            if returned != bytes.len() as u32 {
                return Err(Error::Syscall(k16_abi::syscall::ERROR_NO_MEMORY));
            }
            Ok(())
        }

        pub fn seek_start(self, offset: u32) -> Result<u32, Error> {
            seek(self.0, offset, k16_abi::syscall::SEEK_SET)
        }

        pub fn seek_end(self) -> Result<u32, Error> {
            seek(self.0, 0, k16_abi::syscall::SEEK_END)
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
        let returned =
            k16_rt::open_syscall(path.as_ptr(), path.len(), k16_abi::syscall::OPEN_READ_ONLY);
        if is_error_status(returned) {
            return Err(Error::Syscall(returned));
        }
        Ok(File(returned))
    }

    pub fn create(path: &str) -> Result<File, Error> {
        let returned = k16_rt::open_syscall(
            path.as_ptr(),
            path.len(),
            k16_abi::syscall::OPEN_WRITE_ONLY
                | k16_abi::syscall::OPEN_CREATE
                | k16_abi::syscall::OPEN_TRUNCATE,
        );
        if is_error_status(returned) {
            return Err(Error::Syscall(returned));
        }
        Ok(File(returned))
    }

    pub fn append(path: &str) -> Result<File, Error> {
        let returned = k16_rt::open_syscall(
            path.as_ptr(),
            path.len(),
            k16_abi::syscall::OPEN_WRITE_ONLY
                | k16_abi::syscall::OPEN_CREATE
                | k16_abi::syscall::OPEN_APPEND,
        );
        if is_error_status(returned) {
            return Err(Error::Syscall(returned));
        }
        Ok(File(returned))
    }

    pub fn read_dir(path: &str, out: &mut [u8]) -> Result<usize, Error> {
        let request = ReadDirRequest::new(path, out)?;
        let returned = k16_rt::read_dir_syscall(request.bytes.as_ptr(), request.len);
        if is_error_status(returned) {
            return Err(Error::Syscall(returned));
        }
        Ok(returned as usize)
    }

    pub fn metadata(path: &str) -> Result<Metadata, Error> {
        if path.len() > k16_abi::syscall::MAX_STAT_PATH_BYTES {
            return Err(Error::InvalidArgument);
        }
        let mut bytes = [0u8; k16_abi::syscall::STAT_METADATA_BYTES];
        let returned = k16_rt::stat_syscall(path.as_ptr(), path.len(), bytes.as_mut_ptr());
        if is_error_status(returned) {
            return Err(Error::Syscall(returned));
        }
        Metadata::from_bytes(&bytes)
    }

    pub fn remove_file(path: &str) -> Result<(), Error> {
        if path.len() > k16_abi::syscall::MAX_STAT_PATH_BYTES {
            return Err(Error::InvalidArgument);
        }
        let returned = k16_rt::unlink_syscall(path.as_ptr(), path.len());
        if is_error_status(returned) {
            return Err(Error::Syscall(returned));
        }
        Ok(())
    }

    struct ReadDirRequest {
        bytes: [u8; k16_abi::syscall::MAX_READ_DIR_REQUEST_BYTES],
        len: usize,
    }

    impl ReadDirRequest {
        fn new(path: &str, out: &mut [u8]) -> Result<Self, Error> {
            if path.len() > k16_abi::syscall::MAX_READ_DIR_PATH_BYTES
                || out.len() > u32::MAX as usize
            {
                return Err(Error::InvalidArgument);
            }
            let mut request = Self {
                bytes: [0; k16_abi::syscall::MAX_READ_DIR_REQUEST_BYTES],
                len: 0,
            };
            request.push_u32(k16_abi::syscall::READ_DIR_REQUEST_MAGIC)?;
            request.push_u32(path.len() as u32)?;
            request.push_u32(out.as_mut_ptr() as usize as u32)?;
            request.push_u32(out.len() as u32)?;
            request.push_bytes(path.as_bytes())?;
            Ok(request)
        }

        fn push_u32(&mut self, value: u32) -> Result<(), Error> {
            self.push_bytes(&value.to_le_bytes())
        }

        fn push_bytes(&mut self, bytes: &[u8]) -> Result<(), Error> {
            let end = self
                .len
                .checked_add(bytes.len())
                .ok_or(Error::InvalidArgument)?;
            if end > self.bytes.len() {
                return Err(Error::InvalidArgument);
            }
            self.bytes[self.len..end].copy_from_slice(bytes);
            self.len = end;
            Ok(())
        }
    }

    impl Metadata {
        fn from_bytes(bytes: &[u8; k16_abi::syscall::STAT_METADATA_BYTES]) -> Result<Self, Error> {
            let file_type = match read_u32_le(bytes, 0) {
                k16_abi::syscall::FILE_TYPE_REGULAR => FileType::Regular,
                k16_abi::syscall::FILE_TYPE_DIRECTORY => FileType::Directory,
                _ => return Err(Error::InvalidArgument),
            };
            Ok(Self {
                file_type,
                size_bytes: read_u32_le(bytes, 4),
            })
        }
    }

    fn read_u32_le(bytes: &[u8], offset: usize) -> u32 {
        u32::from_le_bytes([
            bytes[offset],
            bytes[offset + 1],
            bytes[offset + 2],
            bytes[offset + 3],
        ])
    }

    #[inline(always)]
    fn is_error_status(status: u32) -> bool {
        status & 0x8000_0000 != 0
    }

    fn seek(fd: u32, offset: u32, whence: u32) -> Result<u32, Error> {
        let returned = k16_rt::seek_syscall(fd, offset, whence);
        if is_error_status(returned) {
            return Err(Error::Syscall(returned));
        }
        Ok(returned)
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
    use core::{ptr, slice};

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum Error {
        InvalidArgument,
        Syscall(u32),
    }

    #[repr(C)]
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub struct Arg {
        ptr: *const u8,
        len: u32,
    }

    impl Arg {
        pub const fn from_slice(bytes: &[u8]) -> Self {
            Self {
                ptr: bytes.as_ptr(),
                len: bytes.len() as u32,
            }
        }

        pub unsafe fn as_slice(self) -> &'static [u8] {
            unsafe { slice::from_raw_parts(self.ptr, self.len as usize) }
        }
    }

    #[derive(Clone, Copy, Debug)]
    pub struct Argv {
        argc: u32,
        argv: *const Arg,
    }

    impl Argv {
        pub const unsafe fn from_raw(argc: u32, argv: *const Arg) -> Self {
            Self { argc, argv }
        }

        pub const fn len(self) -> usize {
            self.argc as usize
        }

        pub fn get(self, index: usize) -> Option<&'static [u8]> {
            if index >= self.len() || self.argv.is_null() {
                return None;
            }
            let arg = unsafe { ptr::read(self.argv.add(index)) };
            Some(unsafe { arg.as_slice() })
        }
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

    pub fn run_with_args(path: &str, args: &[&str]) -> Result<u32, Error> {
        let request = RunArgvRequest::new(path, args)?;
        let returned = k16_rt::run_argv_syscall(request.bytes.as_ptr(), request.len);
        if returned & 0x8000_0000 != 0 {
            return Err(Error::Syscall(returned));
        }
        Ok(returned)
    }

    struct RunArgvRequest {
        bytes: [u8; k16_abi::syscall::MAX_RUN_ARGV_REQUEST_BYTES],
        len: usize,
    }

    impl RunArgvRequest {
        fn new(path: &str, args: &[&str]) -> Result<Self, Error> {
            if args.is_empty() || args.len() > k16_abi::syscall::MAX_RUN_ARGS {
                return Err(Error::InvalidArgument);
            }
            if path.len() > k16_abi::syscall::MAX_RUN_PATH_BYTES {
                return Err(Error::InvalidArgument);
            }
            let mut request = Self {
                bytes: [0; k16_abi::syscall::MAX_RUN_ARGV_REQUEST_BYTES],
                len: 0,
            };
            request.push_u32(k16_abi::syscall::RUN_ARGV_MAGIC)?;
            request.push_u32(path.len() as u32)?;
            request.push_u32(args.len() as u32)?;
            for arg in args {
                let bytes = arg.as_bytes();
                if bytes.len() > k16_abi::syscall::MAX_RUN_ARG_BYTES {
                    return Err(Error::InvalidArgument);
                }
                request.push_u32(bytes.len() as u32)?;
            }
            request.push_bytes(path.as_bytes())?;
            for arg in args {
                request.push_bytes(arg.as_bytes())?;
            }
            Ok(request)
        }

        fn push_u32(&mut self, value: u32) -> Result<(), Error> {
            self.push_bytes(&value.to_le_bytes())
        }

        fn push_bytes(&mut self, bytes: &[u8]) -> Result<(), Error> {
            let end = self
                .len
                .checked_add(bytes.len())
                .ok_or(Error::InvalidArgument)?;
            if end > self.bytes.len() {
                return Err(Error::InvalidArgument);
            }
            self.bytes[self.len..end].copy_from_slice(bytes);
            self.len = end;
            Ok(())
        }
    }

    #[cfg(feature = "host-test")]
    pub mod host_test {
        use super::{Error, RunArgvRequest};

        pub struct EncodedRunArgvRequest {
            pub bytes: [u8; k16_abi::syscall::MAX_RUN_ARGV_REQUEST_BYTES],
            pub len: usize,
        }

        pub fn encode_run_argv_request(
            path: &str,
            args: &[&str],
        ) -> Result<EncodedRunArgvRequest, Error> {
            let request = RunArgvRequest::new(path, args)?;
            Ok(EncodedRunArgvRequest {
                bytes: request.bytes,
                len: request.len,
            })
        }
    }
}

pub mod prelude {
    pub use crate::{debug, fs, heap, io, process, thread, time};
}
