const MAX_PATH_COMPONENTS: usize = 4;
const MAX_NAME_BYTES: usize = 56;
const FIRST_FILE_FD: u32 = 3;
const FILE_DESCRIPTOR_CAPACITY: usize = 4;
#[cfg(any(not(test), feature = "host-test"))]
const ROOT_PARTITION: &[u8; 4] = b"ROOT";
pub const OPEN_READ_ONLY: u32 = 0;
pub const MAX_OPEN_PATH_BYTES: u32 =
    1 + (MAX_PATH_COMPONENTS as u32 * MAX_NAME_BYTES as u32) + (MAX_PATH_COMPONENTS as u32 - 1);

#[cfg(any(not(test), feature = "host-test"))]
use core::cell::UnsafeCell;

#[cfg(any(not(test), feature = "host-test"))]
static RUNTIME_FD_TABLE: KernelFileDescriptorTable =
    KernelFileDescriptorTable::new(FileDescriptorTable::new());

#[cfg(any(not(test), feature = "host-test"))]
struct KernelFileDescriptorTable {
    table: UnsafeCell<FileDescriptorTable>,
}

#[cfg(any(not(test), feature = "host-test"))]
unsafe impl Sync for KernelFileDescriptorTable {}

#[cfg(any(not(test), feature = "host-test"))]
impl KernelFileDescriptorTable {
    const fn new(table: FileDescriptorTable) -> Self {
        Self {
            table: UnsafeCell::new(table),
        }
    }

    unsafe fn get(&self) -> &mut FileDescriptorTable {
        unsafe { &mut *self.table.get() }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FsError(pub u32);

#[allow(non_upper_case_globals)]
impl FsError {
    pub const BadFd: Self = Self(k16_abi::syscall::ERROR_BAD_FD);
    pub const InvalidPath: Self = Self(k16_abi::syscall::ERROR_INVALID);
    pub const InvalidFlags: Self = Self(k16_abi::syscall::ERROR_INVALID);
    pub const NoEntry: Self = Self(k16_abi::syscall::ERROR_NO_ENTRY);
    pub const NoFd: Self = Self(k16_abi::syscall::ERROR_NO_FD);
    pub const Storage: Self = Self(k16_abi::syscall::ERROR_NO_ENTRY);
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RootFilePath {
    bytes: [[u8; MAX_NAME_BYTES]; MAX_PATH_COMPONENTS],
    lens: [usize; MAX_PATH_COMPONENTS],
    count: usize,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RootFilePathComponents<'a> {
    components: [&'a [u8]; MAX_PATH_COMPONENTS],
    count: usize,
}

impl RootFilePath {
    pub fn parse(path: &[u8]) -> Result<Self, FsError> {
        if !path.starts_with(b"/") || path.len() == 1 || path.ends_with(b"/") {
            return Err(FsError::InvalidPath);
        }

        let mut parsed = Self {
            bytes: [[0; MAX_NAME_BYTES]; MAX_PATH_COMPONENTS],
            lens: [0; MAX_PATH_COMPONENTS],
            count: 0,
        };
        let mut cursor = 1;
        while cursor < path.len() {
            if parsed.count == MAX_PATH_COMPONENTS {
                return Err(FsError::InvalidPath);
            }
            let start = cursor;
            while cursor < path.len() && path[cursor] != b'/' {
                cursor += 1;
            }
            let component = &path[start..cursor];
            if component.is_empty()
                || component.len() > MAX_NAME_BYTES
                || component == b"."
                || component == b".."
            {
                return Err(FsError::InvalidPath);
            }
            let index = parsed.count;
            let mut byte_index = 0;
            while byte_index < component.len() {
                parsed.bytes[index][byte_index] = component[byte_index];
                byte_index += 1;
            }
            parsed.lens[index] = component.len();
            parsed.count += 1;
            cursor += 1;
        }
        Ok(parsed)
    }

    pub fn components(&self) -> RootFilePathComponents<'_> {
        let mut components = [&[][..]; MAX_PATH_COMPONENTS];
        let mut index = 0;
        while index < self.count {
            components[index] = &self.bytes[index][..self.lens[index]];
            index += 1;
        }
        RootFilePathComponents {
            components,
            count: self.count,
        }
    }
}

impl<'a> RootFilePathComponents<'a> {
    pub fn as_slice(&self) -> &[&'a [u8]] {
        &self.components[..self.count]
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FileMetadata {
    pub size_bytes: u32,
    pub extent_count: u32,
    pub extent_start_blocks: [u32; 4],
    pub extent_block_counts: [u32; 4],
}

impl FileMetadata {
    pub const fn empty() -> Self {
        Self {
            size_bytes: 0,
            extent_count: 0,
            extent_start_blocks: [0; 4],
            extent_block_counts: [0; 4],
        }
    }
}

impl From<k16_storage::FileMetadata> for FileMetadata {
    fn from(metadata: k16_storage::FileMetadata) -> Self {
        Self {
            size_bytes: metadata.size_bytes,
            extent_count: metadata.extent_count,
            extent_start_blocks: metadata.extent_start_blocks,
            extent_block_counts: metadata.extent_block_counts,
        }
    }
}

impl From<FileMetadata> for k16_storage::FileMetadata {
    fn from(metadata: FileMetadata) -> Self {
        Self {
            size_bytes: metadata.size_bytes,
            extent_count: metadata.extent_count,
            extent_start_blocks: metadata.extent_start_blocks,
            extent_block_counts: metadata.extent_block_counts,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct FileDescriptor {
    owner_pid: u32,
    metadata: FileMetadata,
    offset: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FileDescriptorTable {
    slots: [Option<FileDescriptor>; FILE_DESCRIPTOR_CAPACITY],
}

impl FileDescriptorTable {
    pub const fn new() -> Self {
        Self {
            slots: [None; FILE_DESCRIPTOR_CAPACITY],
        }
    }

    pub fn open(&mut self, metadata: FileMetadata) -> Result<u32, FsError> {
        self.open_for_process(0, metadata)
    }

    pub fn open_for_process(
        &mut self,
        owner_pid: u32,
        metadata: FileMetadata,
    ) -> Result<u32, FsError> {
        let mut index = 0;
        while index < self.slots.len() {
            if self.slots[index].is_none() {
                self.slots[index] = Some(FileDescriptor {
                    owner_pid,
                    metadata,
                    offset: 0,
                });
                return Ok(FIRST_FILE_FD + index as u32);
            }
            index += 1;
        }
        Err(FsError::NoFd)
    }

    pub fn read_plan(&self, fd: u32, len: u32) -> Result<(u32, u32), FsError> {
        self.read_plan_for_process(0, fd, len)
    }

    pub fn read_plan_for_process(
        &self,
        owner_pid: u32,
        fd: u32,
        len: u32,
    ) -> Result<(u32, u32), FsError> {
        let descriptor = self.descriptor_for_process(owner_pid, fd)?;
        let remaining = descriptor
            .metadata
            .size_bytes
            .saturating_sub(descriptor.offset);
        Ok((descriptor.offset, min_u32(len, remaining)))
    }

    pub fn advance(&mut self, fd: u32, len: u32) -> Result<(), FsError> {
        self.advance_for_process(0, fd, len)
    }

    pub fn advance_for_process(
        &mut self,
        owner_pid: u32,
        fd: u32,
        len: u32,
    ) -> Result<(), FsError> {
        let descriptor = self.descriptor_mut_for_process(owner_pid, fd)?;
        let remaining = descriptor
            .metadata
            .size_bytes
            .saturating_sub(descriptor.offset);
        if len > remaining {
            return Err(FsError::Storage);
        }
        descriptor.offset += len;
        Ok(())
    }

    pub fn metadata(&self, fd: u32) -> Result<FileMetadata, FsError> {
        self.metadata_for_process(0, fd)
    }

    pub fn metadata_for_process(&self, owner_pid: u32, fd: u32) -> Result<FileMetadata, FsError> {
        Ok(self.descriptor_for_process(owner_pid, fd)?.metadata)
    }

    pub fn close(&mut self, fd: u32) -> Result<(), FsError> {
        self.close_for_process(0, fd)
    }

    pub fn close_for_process(&mut self, owner_pid: u32, fd: u32) -> Result<(), FsError> {
        let index = fd_index(fd)?;
        match self.slots[index] {
            Some(descriptor) if descriptor.owner_pid == owner_pid => {
                self.slots[index] = None;
                Ok(())
            }
            _ => Err(FsError::BadFd),
        }
    }

    pub fn close_all(&mut self) {
        let mut index = 0;
        while index < self.slots.len() {
            self.slots[index] = None;
            index += 1;
        }
    }

    pub fn close_all_for_process(&mut self, owner_pid: u32) {
        let mut index = 0;
        while index < self.slots.len() {
            if matches!(self.slots[index], Some(descriptor) if descriptor.owner_pid == owner_pid) {
                self.slots[index] = None;
            }
            index += 1;
        }
    }

    fn descriptor_for_process(&self, owner_pid: u32, fd: u32) -> Result<&FileDescriptor, FsError> {
        let index = fd_index(fd)?;
        match self.slots[index].as_ref() {
            Some(descriptor) if descriptor.owner_pid == owner_pid => Ok(descriptor),
            _ => Err(FsError::BadFd),
        }
    }

    fn descriptor_mut_for_process(
        &mut self,
        owner_pid: u32,
        fd: u32,
    ) -> Result<&mut FileDescriptor, FsError> {
        let index = fd_index(fd)?;
        match self.slots[index].as_mut() {
            Some(descriptor) if descriptor.owner_pid == owner_pid => Ok(descriptor),
            _ => Err(FsError::BadFd),
        }
    }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn open_root_file_for_process(
    owner_pid: u32,
    path: &[u8],
    flags: u32,
) -> Result<u32, FsError> {
    if flags != OPEN_READ_ONLY {
        return Err(FsError::InvalidFlags);
    }
    let path = RootFilePath::parse(path)?;
    let components = path.components();
    let metadata = unsafe {
        k16_storage::open_file_from_storage0(ROOT_PARTITION, components.as_slice())
            .map_err(storage_error_to_fs_error)?;
        k16_storage::selected_file_metadata()
    };
    unsafe {
        RUNTIME_FD_TABLE
            .get()
            .open_for_process(owner_pid, FileMetadata::from(metadata))
    }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn read_file_fd_for_process(
    owner_pid: u32,
    fd: u32,
    ptr: u32,
    len: u32,
) -> Result<u32, FsError> {
    let descriptor = unsafe {
        RUNTIME_FD_TABLE
            .get()
            .descriptor_mut_for_process(owner_pid, fd)?
    };
    let remaining = descriptor
        .metadata
        .size_bytes
        .saturating_sub(descriptor.offset);
    let file_offset = descriptor.offset;
    let read_len = min_u32(len, remaining);
    if read_len == 0 {
        return Ok(0);
    }
    let metadata = descriptor.metadata;
    unsafe {
        k16_storage::copy_file_range_to_ram(metadata.into(), file_offset, ptr, read_len)
            .map_err(storage_error_to_fs_error)?;
    }
    descriptor.offset += read_len;
    Ok(read_len)
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn close_file_fd_for_process(owner_pid: u32, fd: u32) -> Result<(), FsError> {
    unsafe { RUNTIME_FD_TABLE.get().close_for_process(owner_pid, fd) }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn close_file_fds_for_process(owner_pid: u32) {
    unsafe { RUNTIME_FD_TABLE.get().close_all_for_process(owner_pid) }
}

#[cfg(any(not(test), feature = "host-test"))]
fn storage_error_to_fs_error(error: k16_storage::StorageError) -> FsError {
    if error == k16_storage::StorageError::PATH_NOT_FOUND {
        FsError::NoEntry
    } else {
        FsError::Storage
    }
}

fn fd_index(fd: u32) -> Result<usize, FsError> {
    if fd < FIRST_FILE_FD {
        return Err(FsError::BadFd);
    }
    let index = (fd - FIRST_FILE_FD) as usize;
    if index >= FILE_DESCRIPTOR_CAPACITY {
        return Err(FsError::BadFd);
    }
    Ok(index)
}

fn min_u32(left: u32, right: u32) -> u32 {
    if left < right {
        left
    } else {
        right
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn root_file_path_accepts_absolute_k16fs_file_path() {
        let path = RootFilePath::parse(b"/etc/motd").expect("path parses");
        let components = path.components();

        assert_eq!(
            components.as_slice(),
            &[b"etc".as_slice(), b"motd".as_slice()]
        );
    }

    #[test]
    fn root_file_path_rejects_relative_empty_parent_and_too_deep_paths() {
        assert_eq!(RootFilePath::parse(b"etc/motd"), Err(FsError::InvalidPath));
        assert_eq!(RootFilePath::parse(b"/"), Err(FsError::InvalidPath));
        assert_eq!(
            RootFilePath::parse(b"/etc/../motd"),
            Err(FsError::InvalidPath)
        );
        assert_eq!(
            RootFilePath::parse(b"/a/b/c/d/e.txt"),
            Err(FsError::InvalidPath)
        );
    }

    #[test]
    fn file_descriptor_table_allocates_reads_and_closes_file_fds() {
        let mut table = FileDescriptorTable::new();
        let fd = table
            .open(FileMetadata {
                size_bytes: 7,
                extent_count: 1,
                extent_start_blocks: [5, 0, 0, 0],
                extent_block_counts: [1, 0, 0, 0],
            })
            .expect("fd allocates");

        assert_eq!(fd, 3);
        assert_eq!(table.read_plan(fd, 4).expect("first read plans"), (0, 4));
        table.advance(fd, 4).expect("first read advances");
        assert_eq!(table.read_plan(fd, 4).expect("second read plans"), (4, 3));
        table.advance(fd, 3).expect("second read advances");
        assert_eq!(table.read_plan(fd, 4).expect("eof read plans"), (7, 0));
        table.close(fd).expect("fd closes");
        assert_eq!(table.read_plan(fd, 1), Err(FsError::BadFd));
    }

    #[test]
    fn file_descriptor_table_rejects_stdio_close_and_exhaustion() {
        let mut table = FileDescriptorTable::new();

        assert_eq!(table.close(0), Err(FsError::BadFd));
        assert_eq!(table.close(1), Err(FsError::BadFd));
        assert_eq!(table.close(2), Err(FsError::BadFd));
        for index in 0..FILE_DESCRIPTOR_CAPACITY {
            assert_eq!(
                table.open(FileMetadata::empty()).expect("fd allocates"),
                FIRST_FILE_FD + index as u32
            );
        }
        assert_eq!(table.open(FileMetadata::empty()), Err(FsError::NoFd));
    }

    #[test]
    fn file_descriptor_table_closes_all_regular_file_fds_on_child_exit() {
        let mut table = FileDescriptorTable::new();
        let first = table
            .open(FileMetadata::empty())
            .expect("first fd allocates");
        let second = table
            .open(FileMetadata::empty())
            .expect("second fd allocates");

        table.close_all();

        assert_eq!(table.read_plan(first, 1), Err(FsError::BadFd));
        assert_eq!(table.read_plan(second, 1), Err(FsError::BadFd));
        assert_eq!(table.open(FileMetadata::empty()), Ok(first));
    }

    #[test]
    fn file_descriptor_table_closes_only_owned_regular_file_fds() {
        let mut table = FileDescriptorTable::new();
        let parent_pid = 1;
        let child_pid = 2;
        let parent_fd = table
            .open_for_process(parent_pid, FileMetadata::empty())
            .expect("parent fd allocates");
        let child_fd = table
            .open_for_process(child_pid, FileMetadata::empty())
            .expect("child fd allocates");

        table.close_all_for_process(child_pid);

        assert_eq!(
            table.read_plan_for_process(parent_pid, parent_fd, 1),
            Ok((0, 0))
        );
        assert_eq!(
            table.read_plan_for_process(child_pid, parent_fd, 1),
            Err(FsError::BadFd)
        );
        assert_eq!(
            table.read_plan_for_process(child_pid, child_fd, 1),
            Err(FsError::BadFd)
        );
        assert_eq!(
            table.open_for_process(child_pid, FileMetadata::empty()),
            Ok(child_fd)
        );
    }
}
