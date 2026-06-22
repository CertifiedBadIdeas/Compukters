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
            let returned = write_all_raw(self.0, bytes.as_ptr(), bytes.len());
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

    #[cfg(feature = "shared-library-imports")]
    extern "C" {
        fn kraft_write_all(fd: u32, ptr: *const u8, len: usize) -> u32;
    }

    #[cfg(feature = "shared-library-imports")]
    #[inline(always)]
    fn write_all_raw(fd: u32, ptr: *const u8, len: usize) -> u32 {
        unsafe { kraft_write_all(fd, ptr, len) }
    }

    #[cfg(not(feature = "shared-library-imports"))]
    #[inline(always)]
    fn write_all_raw(fd: u32, ptr: *const u8, len: usize) -> u32 {
        k16_rt::write_syscall(fd, ptr, len)
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

pub mod path {
    pub const MAX_PATH_BYTES: usize = k16_abi::syscall::MAX_STAT_PATH_BYTES;

    #[derive(Debug, Eq, PartialEq)]
    pub enum PathError {
        Invalid,
        TooLong,
    }

    pub struct PathBuffer {
        bytes: [u8; MAX_PATH_BYTES],
        len: usize,
    }

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    pub struct PathRef<'a> {
        value: &'a str,
    }

    impl<'a> PathRef<'a> {
        pub fn try_from_str(value: &'a str) -> Result<Self, PathError> {
            if value.is_empty() {
                return Err(PathError::Invalid);
            }
            if value.len() > MAX_PATH_BYTES {
                return Err(PathError::TooLong);
            }
            Ok(Self { value })
        }

        pub const fn as_str(self) -> &'a str {
            self.value
        }

        pub const fn as_bytes(self) -> &'a [u8] {
            self.value.as_bytes()
        }
    }

    impl PathBuffer {
        pub const fn new() -> Self {
            Self {
                bytes: [0; MAX_PATH_BYTES],
                len: 0,
            }
        }

        pub fn as_bytes(&self) -> &[u8] {
            &self.bytes[..self.len]
        }

        pub fn as_str(&self) -> Result<&str, PathError> {
            core::str::from_utf8(self.as_bytes()).map_err(|_| PathError::Invalid)
        }

        pub fn replace_with_parts(
            &mut self,
            prefix: &[u8],
            middle: &[u8],
            suffix: &[u8],
        ) -> Result<(), PathError> {
            if middle.is_empty() {
                return Err(PathError::Invalid);
            }
            let end = prefix
                .len()
                .checked_add(middle.len())
                .and_then(|value| value.checked_add(suffix.len()))
                .ok_or(PathError::TooLong)?;
            if end > self.bytes.len() {
                return Err(PathError::TooLong);
            }
            let mut cursor = 0;
            self.bytes[cursor..cursor + prefix.len()].copy_from_slice(prefix);
            cursor += prefix.len();
            self.bytes[cursor..cursor + middle.len()].copy_from_slice(middle);
            cursor += middle.len();
            self.bytes[cursor..cursor + suffix.len()].copy_from_slice(suffix);
            self.len = end;
            Ok(())
        }

        fn clear(&mut self) {
            self.len = 0;
        }

        fn push_root(&mut self) {
            self.bytes[0] = b'/';
            self.len = 1;
        }

        fn copy_from(&mut self, bytes: &[u8]) -> Result<(), PathError> {
            if bytes.is_empty() || bytes.len() > self.bytes.len() {
                return Err(PathError::TooLong);
            }
            self.bytes[..bytes.len()].copy_from_slice(bytes);
            self.len = bytes.len();
            Ok(())
        }

        fn push_component(&mut self, component: &[u8]) -> Result<(), PathError> {
            if component.is_empty() {
                return Err(PathError::Invalid);
            }
            let separator_len = if self.as_bytes() == b"/" { 0 } else { 1 };
            let end = self
                .len
                .checked_add(separator_len)
                .and_then(|value| value.checked_add(component.len()))
                .ok_or(PathError::TooLong)?;
            if end > self.bytes.len() {
                return Err(PathError::TooLong);
            }
            if separator_len == 1 {
                self.bytes[self.len] = b'/';
                self.len += 1;
            }
            self.bytes[self.len..end].copy_from_slice(component);
            self.len = end;
            Ok(())
        }

        fn pop_component(&mut self) {
            if self.as_bytes() == b"/" {
                return;
            }
            let mut index = self.len;
            while index > 0 {
                index -= 1;
                if self.bytes[index] == b'/' {
                    self.len = if index == 0 { 1 } else { index };
                    return;
                }
            }
            self.push_root();
        }
    }

    impl Default for PathBuffer {
        fn default() -> Self {
            Self::new()
        }
    }

    pub struct WorkingDirectory {
        path: PathBuffer,
    }

    impl WorkingDirectory {
        pub fn new() -> Self {
            let mut path = PathBuffer::new();
            path.bytes[0] = b'/';
            path.len = 1;
            Self { path }
        }

        pub fn as_bytes(&self) -> &[u8] {
            self.path.as_bytes()
        }

        pub fn resolve_into(&self, input: &[u8], out: &mut PathBuffer) -> Result<(), PathError> {
            if input.is_empty() {
                return Err(PathError::Invalid);
            }
            out.clear();
            if input[0] == b'/' {
                out.push_root();
            } else {
                out.copy_from(self.as_bytes())?;
            }

            let mut cursor = 0;
            while cursor < input.len() {
                while cursor < input.len() && input[cursor] == b'/' {
                    cursor += 1;
                }
                let start = cursor;
                while cursor < input.len() && input[cursor] != b'/' {
                    cursor += 1;
                }
                if start == cursor {
                    continue;
                }
                let component = &input[start..cursor];
                if component == b"." {
                    continue;
                }
                if component == b".." {
                    out.pop_component();
                } else {
                    out.push_component(component)?;
                }
            }
            Ok(())
        }

        pub fn set_from_resolved(&mut self, path: &PathBuffer) -> Result<(), PathError> {
            let bytes = path.as_bytes();
            if bytes.is_empty() || bytes[0] != b'/' {
                return Err(PathError::Invalid);
            }
            self.path.copy_from(bytes)
        }
    }

    impl Default for WorkingDirectory {
        fn default() -> Self {
            Self::new()
        }
    }
}

pub mod fs {
    use crate::path::PathRef;

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

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub struct DirEntry<'a> {
        name: &'a str,
        file_type: FileType,
    }

    impl<'a> DirEntry<'a> {
        #[inline(always)]
        pub const fn name(self) -> &'a str {
            self.name
        }

        #[inline(always)]
        pub const fn file_type(self) -> FileType {
            self.file_type
        }
    }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum ReadDirEntryError<E> {
        Fs(Error),
        Visit(E),
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
        open_raw(path, k16_abi::syscall::OPEN_READ_ONLY)
    }

    pub fn open_path(path: PathRef<'_>) -> Result<File, Error> {
        open_raw(path.as_str(), k16_abi::syscall::OPEN_READ_ONLY)
    }

    fn open_raw(path: &str, flags: u32) -> Result<File, Error> {
        let returned = k16_rt::open_syscall(path.as_ptr(), path.len(), flags);
        if is_error_status(returned) {
            return Err(Error::Syscall(returned));
        }
        Ok(File(returned))
    }

    pub fn create(path: &str) -> Result<File, Error> {
        let returned = open_raw(
            path,
            k16_abi::syscall::OPEN_WRITE_ONLY
                | k16_abi::syscall::OPEN_CREATE
                | k16_abi::syscall::OPEN_TRUNCATE,
        )?;
        Ok(returned)
    }

    pub fn create_path(path: PathRef<'_>) -> Result<File, Error> {
        open_raw(
            path.as_str(),
            k16_abi::syscall::OPEN_WRITE_ONLY
                | k16_abi::syscall::OPEN_CREATE
                | k16_abi::syscall::OPEN_TRUNCATE,
        )
    }

    pub fn append(path: &str) -> Result<File, Error> {
        open_raw(
            path,
            k16_abi::syscall::OPEN_WRITE_ONLY
                | k16_abi::syscall::OPEN_CREATE
                | k16_abi::syscall::OPEN_APPEND,
        )
    }

    pub fn append_path(path: PathRef<'_>) -> Result<File, Error> {
        open_raw(
            path.as_str(),
            k16_abi::syscall::OPEN_WRITE_ONLY
                | k16_abi::syscall::OPEN_CREATE
                | k16_abi::syscall::OPEN_APPEND,
        )
    }

    pub fn read_dir(path: &str, out: &mut [u8]) -> Result<usize, Error> {
        let request = ReadDirRequest::new(path, out)?;
        read_dir_raw(request)
    }

    pub fn read_dir_path(path: PathRef<'_>, out: &mut [u8]) -> Result<usize, Error> {
        let request = ReadDirRequest::new(path.as_str(), out)?;
        read_dir_raw(request)
    }

    pub fn read_dir_entries<E>(
        path: &str,
        out: &mut [u8],
        visit: impl FnMut(DirEntry<'_>) -> Result<(), E>,
    ) -> Result<(), ReadDirEntryError<E>> {
        let path = PathRef::try_from_str(path)
            .map_err(|_| ReadDirEntryError::Fs(Error::InvalidArgument))?;
        read_dir_entries_path(path, out, visit)
    }

    pub fn read_dir_entries_path<E>(
        path: PathRef<'_>,
        out: &mut [u8],
        visit: impl FnMut(DirEntry<'_>) -> Result<(), E>,
    ) -> Result<(), ReadDirEntryError<E>> {
        let read = read_dir_path(path, out).map_err(ReadDirEntryError::Fs)?;
        if read > out.len() {
            return Err(ReadDirEntryError::Fs(Error::InvalidArgument));
        }
        read_dir_entries_from_listing(path, &out[..read], metadata_path, visit)
    }

    fn read_dir_entries_from_listing<E>(
        path: PathRef<'_>,
        listing: &[u8],
        mut metadata_for: impl FnMut(PathRef<'_>) -> Result<Metadata, Error>,
        mut visit: impl FnMut(DirEntry<'_>) -> Result<(), E>,
    ) -> Result<(), ReadDirEntryError<E>> {
        let mut child_path = [0u8; k16_abi::syscall::MAX_STAT_PATH_BYTES];
        let mut cursor = 0;
        while cursor < listing.len() {
            let start = cursor;
            while cursor < listing.len() && listing[cursor] != b'\n' {
                cursor += 1;
            }
            let name = &listing[start..cursor];
            if name.is_empty() {
                return Err(ReadDirEntryError::Fs(Error::InvalidArgument));
            }
            let name = core::str::from_utf8(name)
                .map_err(|_| ReadDirEntryError::Fs(Error::InvalidArgument))?;
            let child_path_len =
                write_child_path(path.as_str().as_bytes(), name.as_bytes(), &mut child_path)
                    .map_err(ReadDirEntryError::Fs)?;
            let child_path = core::str::from_utf8(&child_path[..child_path_len])
                .map_err(|_| ReadDirEntryError::Fs(Error::InvalidArgument))?;
            let child_path = PathRef::try_from_str(child_path)
                .map_err(|_| ReadDirEntryError::Fs(Error::InvalidArgument))?;
            let metadata = metadata_for(child_path).map_err(ReadDirEntryError::Fs)?;
            visit(DirEntry {
                name,
                file_type: metadata.file_type,
            })
            .map_err(ReadDirEntryError::Visit)?;
            if cursor < listing.len() {
                cursor += 1;
            }
        }
        Ok(())
    }

    fn write_child_path(base: &[u8], name: &[u8], out: &mut [u8]) -> Result<usize, Error> {
        if base.is_empty() || name.is_empty() {
            return Err(Error::InvalidArgument);
        }
        let separator_len = if base == b"/" { 0 } else { 1 };
        let len = base
            .len()
            .checked_add(separator_len)
            .and_then(|value| value.checked_add(name.len()))
            .ok_or(Error::InvalidArgument)?;
        if len > out.len() {
            return Err(Error::InvalidArgument);
        }
        out[..base.len()].copy_from_slice(base);
        let mut cursor = base.len();
        if separator_len == 1 {
            out[cursor] = b'/';
            cursor += 1;
        }
        out[cursor..cursor + name.len()].copy_from_slice(name);
        Ok(len)
    }

    fn read_dir_raw(request: ReadDirRequest) -> Result<usize, Error> {
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
        metadata_raw(path)
    }

    pub fn metadata_path(path: PathRef<'_>) -> Result<Metadata, Error> {
        metadata_raw(path.as_str())
    }

    fn metadata_raw(path: &str) -> Result<Metadata, Error> {
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
        remove_file_raw(path)
    }

    pub fn remove_file_path(path: PathRef<'_>) -> Result<(), Error> {
        remove_file_raw(path.as_str())
    }

    fn remove_file_raw(path: &str) -> Result<(), Error> {
        let returned = k16_rt::unlink_syscall(path.as_ptr(), path.len());
        if is_error_status(returned) {
            return Err(Error::Syscall(returned));
        }
        Ok(())
    }

    pub fn create_dir(path: &str) -> Result<(), Error> {
        if path.len() > k16_abi::syscall::MAX_STAT_PATH_BYTES {
            return Err(Error::InvalidArgument);
        }
        create_dir_raw(path)
    }

    pub fn create_dir_path(path: PathRef<'_>) -> Result<(), Error> {
        create_dir_raw(path.as_str())
    }

    fn create_dir_raw(path: &str) -> Result<(), Error> {
        let returned = k16_rt::mkdir_syscall(path.as_ptr(), path.len());
        if is_error_status(returned) {
            return Err(Error::Syscall(returned));
        }
        Ok(())
    }

    pub fn remove_dir(path: &str) -> Result<(), Error> {
        if path.len() > k16_abi::syscall::MAX_STAT_PATH_BYTES {
            return Err(Error::InvalidArgument);
        }
        remove_dir_raw(path)
    }

    pub fn remove_dir_path(path: PathRef<'_>) -> Result<(), Error> {
        remove_dir_raw(path.as_str())
    }

    fn remove_dir_raw(path: &str) -> Result<(), Error> {
        let returned = k16_rt::rmdir_syscall(path.as_ptr(), path.len());
        if is_error_status(returned) {
            return Err(Error::Syscall(returned));
        }
        Ok(())
    }

    pub fn rename(old_path: &str, new_path: &str) -> Result<(), Error> {
        let (request, len) = unsafe { rename_request(old_path, new_path)? };
        rename_raw(request, len)
    }

    pub fn rename_path(old_path: PathRef<'_>, new_path: PathRef<'_>) -> Result<(), Error> {
        let (request, len) = unsafe { rename_request(old_path.as_str(), new_path.as_str())? };
        rename_raw(request, len)
    }

    fn rename_raw(request: *const u8, len: usize) -> Result<(), Error> {
        let returned = k16_rt::rename_syscall(request, len);
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

    static mut RENAME_REQUEST_BYTES: [u8; k16_abi::syscall::MAX_RENAME_REQUEST_BYTES] =
        [0; k16_abi::syscall::MAX_RENAME_REQUEST_BYTES];

    unsafe fn rename_request(old_path: &str, new_path: &str) -> Result<(*const u8, usize), Error> {
        if old_path.is_empty()
            || new_path.is_empty()
            || old_path.len() > k16_abi::syscall::MAX_RENAME_PATH_BYTES
            || new_path.len() > k16_abi::syscall::MAX_RENAME_PATH_BYTES
        {
            return Err(Error::InvalidArgument);
        }
        let ptr = core::ptr::addr_of_mut!(RENAME_REQUEST_BYTES).cast::<u8>();
        let mut writer = RenameRequestWriter { ptr, len: 0 };
        unsafe { writer.push_u32(k16_abi::syscall::RENAME_REQUEST_MAGIC)? };
        unsafe { writer.push_u32(old_path.len() as u32)? };
        unsafe { writer.push_u32(new_path.len() as u32)? };
        unsafe { writer.push_bytes(old_path.as_bytes())? };
        unsafe { writer.push_bytes(new_path.as_bytes())? };
        Ok((ptr.cast_const(), writer.len))
    }

    struct RenameRequestWriter {
        ptr: *mut u8,
        len: usize,
    }

    impl RenameRequestWriter {
        unsafe fn push_u32(&mut self, value: u32) -> Result<(), Error> {
            unsafe { self.push_bytes(&value.to_le_bytes()) }
        }

        unsafe fn push_bytes(&mut self, bytes: &[u8]) -> Result<(), Error> {
            let end = self
                .len
                .checked_add(bytes.len())
                .ok_or(Error::InvalidArgument)?;
            if end > k16_abi::syscall::MAX_RENAME_REQUEST_BYTES {
                return Err(Error::InvalidArgument);
            }
            unsafe {
                core::ptr::copy_nonoverlapping(bytes.as_ptr(), self.ptr.add(self.len), bytes.len())
            };
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

    #[cfg(feature = "host-test")]
    pub mod host_test {
        use super::*;

        pub fn read_dir_entries_from_listing<E>(
            path: PathRef<'_>,
            listing: &[u8],
            metadata_for: impl FnMut(PathRef<'_>) -> Result<Metadata, Error>,
            visit: impl FnMut(DirEntry<'_>) -> Result<(), E>,
        ) -> Result<(), ReadDirEntryError<E>> {
            super::read_dir_entries_from_listing(path, listing, metadata_for, visit)
        }
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

pub mod status {
    #[inline(always)]
    pub const fn syscall_status_name(status: u32) -> &'static [u8] {
        syscall_status_name_or(status, b"STATUS")
    }

    #[inline(always)]
    pub const fn syscall_status_name_or(status: u32, fallback: &'static [u8]) -> &'static [u8] {
        match status {
            k16_abi::syscall::ERROR_NO_ENTRY => b"NOENT",
            k16_abi::syscall::ERROR_EXEC_FORMAT => b"NOEXEC",
            k16_abi::syscall::ERROR_BAD_FD => b"BADFD",
            k16_abi::syscall::ERROR_NO_FD => b"NOFD",
            k16_abi::syscall::ERROR_NOT_EMPTY => b"NOTEMPTY",
            k16_abi::syscall::ERROR_INVALID => b"INVAL",
            k16_abi::syscall::ERROR_NO_MEMORY => b"NOMEM",
            k16_abi::syscall::ERROR_FAULT => b"FAULT",
            k16_abi::syscall::ERROR_BUSY => b"BUSY",
            _ => fallback,
        }
    }

    #[inline(always)]
    pub const fn fs_error_name(error: crate::fs::Error) -> &'static [u8] {
        fs_error_name_or(error, b"IO")
    }

    #[inline(always)]
    pub const fn fs_error_name_or(
        error: crate::fs::Error,
        fallback: &'static [u8],
    ) -> &'static [u8] {
        match error {
            crate::fs::Error::InvalidArgument => b"INVAL",
            crate::fs::Error::Syscall(status) => syscall_status_name_or(status, fallback),
        }
    }
}

pub mod coreutils {
    pub fn for_each_path_arg<'a>(
        arg_count: usize,
        mut arg_at: impl FnMut(usize) -> Option<&'a [u8]>,
        mut visit: impl FnMut(&'a str) -> Result<(), ()>,
    ) -> Result<(), ()> {
        if arg_count == 0 {
            return Err(());
        }
        for_each_present_path_arg(arg_count, &mut arg_at, &mut visit)
    }

    pub fn for_each_path_arg_or_default<'a>(
        arg_count: usize,
        mut arg_at: impl FnMut(usize) -> Option<&'a [u8]>,
        default_path: &'a str,
        mut visit: impl FnMut(&'a str) -> Result<(), ()>,
    ) -> Result<(), ()> {
        if arg_count == 0 {
            return visit(default_path);
        }
        for_each_present_path_arg(arg_count, &mut arg_at, &mut visit)
    }

    fn for_each_present_path_arg<'a>(
        arg_count: usize,
        arg_at: &mut impl FnMut(usize) -> Option<&'a [u8]>,
        visit: &mut impl FnMut(&'a str) -> Result<(), ()>,
    ) -> Result<(), ()> {
        let mut index = 0;
        let mut failed = false;
        while index < arg_count {
            let path = arg_at(index).ok_or(())?;
            let path = core::str::from_utf8(path).map_err(|_| ())?;
            if visit(path).is_err() {
                failed = true;
            }
            index += 1;
        }
        if failed {
            Err(())
        } else {
            Ok(())
        }
    }

    #[inline(always)]
    pub fn should_resolve_path_arg(command: &[u8], args: &[&[u8]], index: usize) -> bool {
        match command {
            b"ls" | b"cat" | b"cp" | b"mv" | b"stat" | b"rm" | b"mkdir" | b"rmdir" => true,
            b"write" => write_path_arg_index(args) == Some(index),
            _ => false,
        }
    }

    fn write_path_arg_index(args: &[&[u8]]) -> Option<usize> {
        if args.len() == 2 {
            Some(0)
        } else if args.len() == 3 && args[0] == b"--append" {
            Some(1)
        } else {
            None
        }
    }
}

pub mod process {
    use core::{ptr, slice};

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum Error {
        InvalidArgument,
        Syscall(u32),
    }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub struct ExitStatus {
        code: u32,
    }

    impl ExitStatus {
        pub const fn new(code: u32) -> Self {
            Self { code }
        }

        pub const fn code(self) -> u32 {
            self.code
        }

        pub const fn success(self) -> bool {
            self.code == 0
        }
    }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub struct ProcessId {
        raw: u32,
    }

    impl ProcessId {
        #[inline(always)]
        pub const fn from_raw(raw: u32) -> Self {
            Self { raw }
        }

        #[inline(always)]
        pub const fn raw(self) -> u32 {
            self.raw
        }
    }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub struct WaitStatus {
        pid: ProcessId,
        status: ExitStatus,
    }

    impl WaitStatus {
        #[inline(always)]
        pub const fn new(pid: ProcessId, status: ExitStatus) -> Self {
            Self { pid, status }
        }

        #[inline(always)]
        pub const fn pid(self) -> ProcessId {
            self.pid
        }

        #[inline(always)]
        pub const fn status(self) -> ExitStatus {
            self.status
        }
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
        exit_raw(status)
    }

    #[cfg(feature = "shared-library-imports")]
    extern "C" {
        fn kraft_exit(status: u32) -> !;
    }

    #[cfg(feature = "shared-library-imports")]
    #[inline(always)]
    fn exit_raw(status: u32) -> ! {
        unsafe { kraft_exit(status) }
    }

    #[cfg(not(feature = "shared-library-imports"))]
    #[inline(always)]
    fn exit_raw(status: u32) -> ! {
        k16_rt::exit_syscall(status)
    }

    pub fn run_with_args(path: &str, args: &[&str]) -> Result<ExitStatus, Error> {
        let request = RunArgvRequest::new(k16_abi::syscall::RUN_ARGV_MAGIC, path, args)?;
        let returned = k16_rt::run_argv_syscall(request.bytes.as_ptr(), request.len);
        if returned & 0x8000_0000 != 0 {
            return Err(Error::Syscall(returned));
        }
        Ok(ExitStatus::new(returned))
    }

    #[inline(always)]
    pub fn spawn_with_args(path: &str, args: &[&str]) -> Result<ProcessId, Error> {
        let request = RunArgvRequest::new(k16_abi::syscall::SPAWN_ARGV_MAGIC, path, args)?;
        let returned = k16_rt::spawn_argv_syscall(request.bytes.as_ptr(), request.len);
        if returned & 0x8000_0000 != 0 || returned == 0 {
            return Err(Error::Syscall(returned));
        }
        Ok(ProcessId::from_raw(returned))
    }

    #[inline(always)]
    pub fn wait(pid: ProcessId) -> Result<WaitStatus, Error> {
        wait_raw(pid.raw())
    }

    #[inline(always)]
    pub fn wait_any() -> Result<WaitStatus, Error> {
        wait_raw(0)
    }

    #[inline(always)]
    fn wait_raw(pid: u32) -> Result<WaitStatus, Error> {
        let mut status = 0_u32;
        let returned = k16_rt::wait_syscall(pid, &mut status);
        if returned & 0x8000_0000 != 0 || returned == 0 {
            return Err(Error::Syscall(returned));
        }
        Ok(WaitStatus::new(
            ProcessId::from_raw(returned),
            ExitStatus::new(status),
        ))
    }

    struct RunArgvRequest {
        bytes: [u8; k16_abi::syscall::MAX_RUN_ARGV_REQUEST_BYTES],
        len: usize,
    }

    impl RunArgvRequest {
        #[inline(always)]
        fn new(magic: u32, path: &str, args: &[&str]) -> Result<Self, Error> {
            if args.len() > k16_abi::syscall::MAX_RUN_ARGS {
                return Err(Error::InvalidArgument);
            }
            if path.len() > k16_abi::syscall::MAX_RUN_PATH_BYTES {
                return Err(Error::InvalidArgument);
            }
            let mut request = Self {
                bytes: [0; k16_abi::syscall::MAX_RUN_ARGV_REQUEST_BYTES],
                len: 0,
            };
            request.push_u32(magic)?;
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
            let request = RunArgvRequest::new(k16_abi::syscall::RUN_ARGV_MAGIC, path, args)?;
            Ok(EncodedRunArgvRequest {
                bytes: request.bytes,
                len: request.len,
            })
        }
    }
}

pub mod prelude {
    pub use crate::{coreutils, debug, fs, heap, io, path, process, status, thread, time};
}
