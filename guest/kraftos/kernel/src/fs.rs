const MAX_PATH_COMPONENTS: usize = 4;
const MAX_NAME_BYTES: usize = 56;
const FIRST_FILE_FD: u32 = 3;
const FILE_DESCRIPTOR_CAPACITY: usize = 4;
#[cfg(any(not(test), feature = "host-test"))]
const ROOT_PARTITION: &[u8; 4] = b"ROOT";
pub const OPEN_READ_ONLY: u32 = k16_abi::syscall::OPEN_READ_ONLY;
pub const OPEN_WRITE_ONLY: u32 = k16_abi::syscall::OPEN_WRITE_ONLY;
pub const OPEN_CREATE: u32 = k16_abi::syscall::OPEN_CREATE;
pub const OPEN_TRUNCATE: u32 = k16_abi::syscall::OPEN_TRUNCATE;
pub const OPEN_APPEND: u32 = k16_abi::syscall::OPEN_APPEND;
#[cfg(any(not(test), feature = "host-test"))]
const OPEN_CREATE_TRUNCATE_FLAGS: u32 = OPEN_WRITE_ONLY | OPEN_CREATE | OPEN_TRUNCATE;
#[cfg(any(not(test), feature = "host-test"))]
const OPEN_CREATE_APPEND_FLAGS: u32 = OPEN_WRITE_ONLY | OPEN_CREATE | OPEN_APPEND;
pub const MAX_OPEN_PATH_BYTES: u32 =
    1 + (MAX_PATH_COMPONENTS as u32 * MAX_NAME_BYTES as u32) + (MAX_PATH_COMPONENTS as u32 - 1);
pub const MAX_READ_DIR_PATH_BYTES: u32 = MAX_OPEN_PATH_BYTES;
pub const MAX_STAT_PATH_BYTES: u32 = MAX_OPEN_PATH_BYTES;

#[cfg(any(not(test), feature = "host-test"))]
use core::cell::UnsafeCell;

#[cfg(any(not(test), feature = "host-test"))]
static RUNTIME_FD_TABLE: KernelFileDescriptorTable =
    KernelFileDescriptorTable::new(FileDescriptorTable::new());
#[cfg(any(not(test), feature = "host-test"))]
static ROOT_FS: KernelRootFs = KernelRootFs::new(crate::kfs::root::KfsRootFs::new());

#[cfg(any(not(test), feature = "host-test"))]
struct KernelFileDescriptorTable {
    table: UnsafeCell<FileDescriptorTable>,
}

#[cfg(any(not(test), feature = "host-test"))]
unsafe impl Sync for KernelFileDescriptorTable {}

#[cfg(any(not(test), feature = "host-test"))]
struct KernelRootFs {
    fs: UnsafeCell<crate::kfs::root::KfsRootFs>,
}

#[cfg(any(not(test), feature = "host-test"))]
unsafe impl Sync for KernelRootFs {}

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

#[cfg(any(not(test), feature = "host-test"))]
impl KernelRootFs {
    const fn new(fs: crate::kfs::root::KfsRootFs) -> Self {
        Self {
            fs: UnsafeCell::new(fs),
        }
    }

    unsafe fn get(&self) -> &mut crate::kfs::root::KfsRootFs {
        unsafe { &mut *self.fs.get() }
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
    pub const NoMemory: Self = Self(k16_abi::syscall::ERROR_NO_MEMORY);
    pub const NotEmpty: Self = Self(k16_abi::syscall::ERROR_NOT_EMPTY);
    pub const Busy: Self = Self(k16_abi::syscall::ERROR_BUSY);
    pub const Fault: Self = Self(k16_abi::syscall::ERROR_FAULT);
    pub const Storage: Self = Self(k16_abi::syscall::ERROR_NO_ENTRY);
}

pub trait DirectoryByteSink {
    fn push_byte(&mut self, byte: u8) -> Result<(), FsError>;
    fn written(&self) -> u32;
}

#[cfg(any(not(test), feature = "host-test"))]
struct StorageDirectoryByteSink<'a, S: DirectoryByteSink> {
    sink: &'a mut S,
}

#[cfg(any(not(test), feature = "host-test"))]
impl<S: DirectoryByteSink> crate::kfs::types::DirectoryListingSink
    for StorageDirectoryByteSink<'_, S>
{
    unsafe fn push_byte(&mut self, byte: u8) -> Result<(), crate::kfs::error::StorageError> {
        self.sink
            .push_byte(byte)
            .map_err(fs_error_to_storage_output_error)
    }

    fn written(&self) -> u32 {
        self.sink.written()
    }
}

#[cfg(any(not(test), feature = "host-test"))]
struct RamDirectoryByteSink {
    ptr: u32,
    len: u32,
    written: u32,
}

#[cfg(any(not(test), feature = "host-test"))]
impl RamDirectoryByteSink {
    const fn new(ptr: u32, len: u32) -> Self {
        Self {
            ptr,
            len,
            written: 0,
        }
    }
}

#[cfg(any(not(test), feature = "host-test"))]
impl DirectoryByteSink for RamDirectoryByteSink {
    fn push_byte(&mut self, byte: u8) -> Result<(), FsError> {
        if self.written >= self.len {
            return Err(FsError::NoMemory);
        }
        unsafe { core::ptr::write_volatile((self.ptr + self.written) as usize as *mut u8, byte) };
        self.written += 1;
        Ok(())
    }

    fn written(&self) -> u32 {
        self.written
    }
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

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RootDirectoryPath {
    path: RootFilePath,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RootMetadataPath {
    path: RootFilePath,
}

impl RootFilePath {
    pub fn parse(path: &[u8]) -> Result<Self, FsError> {
        parse_root_path(path, false)
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

impl RootDirectoryPath {
    pub fn parse(path: &[u8]) -> Result<Self, FsError> {
        Ok(Self {
            path: parse_root_path(path, true)?,
        })
    }

    pub fn components(&self) -> RootFilePathComponents<'_> {
        self.path.components()
    }
}

impl RootMetadataPath {
    pub fn parse(path: &[u8]) -> Result<Self, FsError> {
        Ok(Self {
            path: parse_root_path(path, true)?,
        })
    }

    pub fn components(&self) -> RootFilePathComponents<'_> {
        self.path.components()
    }
}

impl<'a> RootFilePathComponents<'a> {
    pub fn as_slice(&self) -> &[&'a [u8]] {
        &self.components[..self.count]
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FileMetadata {
    pub inode_id: u32,
    pub size_bytes: u32,
    pub extent_count: u32,
    pub extent_start_blocks: [u32; 4],
    pub extent_block_counts: [u32; 4],
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PathMetadata {
    pub file_type: u32,
    pub size_bytes: u32,
}

impl FileMetadata {
    pub const fn empty() -> Self {
        Self {
            inode_id: 0,
            size_bytes: 0,
            extent_count: 0,
            extent_start_blocks: [0; 4],
            extent_block_counts: [0; 4],
        }
    }
}

impl From<crate::kfs::types::FileMetadata> for FileMetadata {
    fn from(metadata: crate::kfs::types::FileMetadata) -> Self {
        Self {
            inode_id: metadata.inode_id,
            size_bytes: metadata.size_bytes,
            extent_count: metadata.extent_count,
            extent_start_blocks: metadata.extent_start_blocks,
            extent_block_counts: metadata.extent_block_counts,
        }
    }
}

impl From<FileMetadata> for crate::kfs::types::FileMetadata {
    fn from(metadata: FileMetadata) -> Self {
        Self {
            inode_id: metadata.inode_id,
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
    open_file: crate::kfs::open_file::KfsOpenFile,
    flags: u32,
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
        self.open_for_process(0, metadata, OPEN_READ_ONLY)
    }

    pub fn open_for_process(
        &mut self,
        owner_pid: u32,
        metadata: FileMetadata,
        flags: u32,
    ) -> Result<u32, FsError> {
        let mut index = 0;
        while index < self.slots.len() {
            if self.slots[index].is_none() {
                let append = append_requested(flags)?;
                self.slots[index] = Some(FileDescriptor {
                    owner_pid,
                    open_file: crate::kfs::open_file::KfsOpenFile::regular_file(
                        metadata.into(),
                        append,
                    ),
                    flags,
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
        if descriptor.flags != OPEN_READ_ONLY {
            return Err(FsError::BadFd);
        }
        let plan = descriptor.open_file.read_plan(len);
        Ok((plan.offset, plan.len))
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
        descriptor
            .open_file
            .finish_read(len)
            .map_err(|_| FsError::Storage)
    }

    pub fn write_plan_for_process(
        &self,
        owner_pid: u32,
        fd: u32,
        len: u32,
    ) -> Result<(FileMetadata, u32, u32), FsError> {
        let descriptor = self.descriptor_for_process(owner_pid, fd)?;
        if descriptor.flags & OPEN_WRITE_ONLY == 0 {
            return Err(FsError::BadFd);
        }
        let plan = descriptor
            .open_file
            .write_plan(len)
            .map_err(|_| FsError::Storage)?;
        Ok((
            FileMetadata::from(descriptor.open_file.metadata()),
            plan.offset,
            plan.len,
        ))
    }

    pub fn finish_write_for_process(
        &mut self,
        owner_pid: u32,
        fd: u32,
        metadata: FileMetadata,
        len: u32,
    ) -> Result<(), FsError> {
        let descriptor = self.descriptor_mut_for_process(owner_pid, fd)?;
        if descriptor.flags & OPEN_WRITE_ONLY == 0 {
            return Err(FsError::BadFd);
        }
        descriptor
            .open_file
            .finish_write(metadata.into(), len)
            .map_err(|_| FsError::Storage)
    }

    pub fn seek_for_process(
        &mut self,
        owner_pid: u32,
        fd: u32,
        offset: u32,
        whence: u32,
    ) -> Result<u32, FsError> {
        let descriptor = self.descriptor_mut_for_process(owner_pid, fd)?;
        let new_offset = match whence {
            k16_abi::syscall::SEEK_SET => offset,
            k16_abi::syscall::SEEK_END if offset == 0 => {
                return Ok(descriptor.open_file.seek_end())
            }
            k16_abi::syscall::SEEK_END => return Err(FsError::InvalidFlags),
            _ => return Err(FsError::InvalidFlags),
        };
        descriptor
            .open_file
            .seek_set(new_offset)
            .map_err(|_| FsError::InvalidFlags)
    }

    pub fn metadata(&self, fd: u32) -> Result<FileMetadata, FsError> {
        self.metadata_for_process(0, fd)
    }

    pub fn metadata_for_process(&self, owner_pid: u32, fd: u32) -> Result<FileMetadata, FsError> {
        Ok(FileMetadata::from(
            self.descriptor_for_process(owner_pid, fd)?
                .open_file
                .metadata(),
        ))
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

    pub fn has_open_inode(&self, inode_id: u32) -> bool {
        let mut index = 0;
        while index < self.slots.len() {
            if matches!(self.slots[index], Some(descriptor) if descriptor.open_file.inode_id() == inode_id)
            {
                return true;
            }
            index += 1;
        }
        false
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
    crate::os_stats::record_file_open();
    if flags != OPEN_READ_ONLY
        && flags != OPEN_CREATE_TRUNCATE_FLAGS
        && flags != OPEN_CREATE_APPEND_FLAGS
    {
        return Err(FsError::InvalidFlags);
    }
    let path = RootFilePath::parse(path)?;
    let components = path.components();
    let metadata = if flags == OPEN_READ_ONLY {
        unsafe {
            ROOT_FS
                .get()
                .open_file(ROOT_PARTITION, components.as_slice())
                .map_err(storage_error_to_fs_error)?
        }
    } else {
        let truncate = flags == OPEN_CREATE_TRUNCATE_FLAGS;
        let metadata = unsafe {
            ROOT_FS
                .get()
                .open_file_for_write(ROOT_PARTITION, components.as_slice(), true, truncate)
                .map_err(storage_error_to_fs_error)?
        };
        unsafe { flush_root_storage()? };
        metadata
    };
    unsafe {
        RUNTIME_FD_TABLE
            .get()
            .open_for_process(owner_pid, FileMetadata::from(metadata), flags)
    }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn seek_file_fd_for_process(
    owner_pid: u32,
    fd: u32,
    offset: u32,
    whence: u32,
) -> Result<u32, FsError> {
    unsafe {
        RUNTIME_FD_TABLE
            .get()
            .seek_for_process(owner_pid, fd, offset, whence)
    }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn read_file_fd_for_process(
    owner_pid: u32,
    fd: u32,
    ptr: u32,
    len: u32,
) -> Result<u32, FsError> {
    let read_len = unsafe { copy_file_fd_range_to_ram_for_process(owner_pid, fd, ptr, len)? };
    unsafe { advance_file_fd_for_process(owner_pid, fd, read_len)? };
    Ok(read_len)
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn remove_root_file_for_process(path: &[u8]) -> Result<(), FsError> {
    let path = RootFilePath::parse(path)?;
    let components = path.components();
    unsafe {
        ROOT_FS
            .get()
            .remove_file(ROOT_PARTITION, components.as_slice(), |inode_id| {
                RUNTIME_FD_TABLE.get().has_open_inode(inode_id)
            })
            .map_err(storage_error_to_fs_error)?;
        flush_root_storage()?;
    }
    Ok(())
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn rename_root_file_for_process(
    _owner_pid: u32,
    old_path: &[u8],
    new_path: &[u8],
) -> Result<(), FsError> {
    let old_path = RootFilePath::parse(old_path)?;
    let old_components = old_path.components();
    let new_path = RootFilePath::parse(new_path)?;
    let new_components = new_path.components();
    unsafe {
        ROOT_FS
            .get()
            .rename_file(
                ROOT_PARTITION,
                old_components.as_slice(),
                new_components.as_slice(),
                |inode_id| RUNTIME_FD_TABLE.get().has_open_inode(inode_id),
            )
            .map_err(storage_error_to_fs_error)?;
        flush_root_storage()?;
    }
    Ok(())
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn create_root_directory(path: &[u8]) -> Result<(), FsError> {
    let path = RootFilePath::parse(path)?;
    let components = path.components();
    unsafe {
        ROOT_FS
            .get()
            .create_directory(ROOT_PARTITION, components.as_slice())
            .map_err(storage_error_to_fs_error)?;
        flush_root_storage()?;
    }
    Ok(())
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn remove_root_directory(path: &[u8]) -> Result<(), FsError> {
    let path = RootFilePath::parse(path)?;
    let components = path.components();
    unsafe {
        ROOT_FS
            .get()
            .remove_directory(ROOT_PARTITION, components.as_slice())
            .map_err(storage_error_to_fs_error)?;
        flush_root_storage()?;
    }
    Ok(())
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn copy_file_fd_range_to_ram_for_process(
    owner_pid: u32,
    fd: u32,
    ptr: u32,
    len: u32,
) -> Result<u32, FsError> {
    crate::os_stats::record_file_read();
    let (file_offset, read_len) = unsafe {
        RUNTIME_FD_TABLE
            .get()
            .read_plan_for_process(owner_pid, fd, len)?
    };
    if read_len == 0 {
        return Ok(0);
    }
    let metadata = unsafe { RUNTIME_FD_TABLE.get().metadata_for_process(owner_pid, fd)? };
    unsafe {
        crate::kfs::file_io::copy_file_range_to_ram(metadata.into(), file_offset, ptr, read_len)
            .map_err(storage_error_to_fs_error)?;
    }
    Ok(read_len)
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn advance_file_fd_for_process(
    owner_pid: u32,
    fd: u32,
    len: u32,
) -> Result<(), FsError> {
    unsafe {
        RUNTIME_FD_TABLE
            .get()
            .advance_for_process(owner_pid, fd, len)
    }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn copy_ram_to_file_fd_range_for_process(
    owner_pid: u32,
    fd: u32,
    ptr: u32,
    len: u32,
) -> Result<u32, FsError> {
    if len == 0 {
        return Ok(0);
    }
    let (metadata, offset, write_len) = unsafe {
        RUNTIME_FD_TABLE
            .get()
            .write_plan_for_process(owner_pid, fd, len)?
    };
    let updated = unsafe {
        crate::kfs::file_write::copy_ram_to_file_range(metadata.into(), offset, ptr, write_len)
            .map_err(storage_error_to_fs_error)?
    };
    unsafe {
        RUNTIME_FD_TABLE.get().finish_write_for_process(
            owner_pid,
            fd,
            FileMetadata::from(updated),
            write_len,
        )?
    };
    unsafe { flush_root_storage()? };
    unsafe { invalidate_root_fs_cache() };
    Ok(write_len)
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn read_root_directory(path: &[u8], ptr: u32, len: u32) -> Result<u32, FsError> {
    let mut sink = RamDirectoryByteSink::new(ptr, len);
    unsafe { read_root_directory_into(path, &mut sink) }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn read_root_directory_into<S: DirectoryByteSink>(
    path: &[u8],
    sink: &mut S,
) -> Result<u32, FsError> {
    crate::os_stats::record_read_dir_call();
    let path = RootDirectoryPath::parse(path)?;
    let components = path.components();
    let mut storage_sink = StorageDirectoryByteSink { sink };
    unsafe {
        ROOT_FS
            .get()
            .read_directory_into(ROOT_PARTITION, components.as_slice(), &mut storage_sink)
            .map_err(storage_error_to_fs_error)
    }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn open_root_file_cached_components(
    components: &[&[u8]],
) -> Result<crate::kfs::types::FileMetadata, FsError> {
    unsafe {
        ROOT_FS
            .get()
            .open_file(ROOT_PARTITION, components)
            .map_err(storage_error_to_fs_error)
    }
}

#[cfg(all(test, not(feature = "host-test")))]
pub unsafe fn open_root_file_cached_components(
    components: &[&[u8]],
) -> Result<crate::kfs::types::FileMetadata, FsError> {
    unsafe {
        crate::kfs::root::open_file_from_storage0(b"ROOT", components)
            .map_err(storage_error_to_fs_error)?;
        Ok(crate::kfs::selected_inode::selected_file_metadata())
    }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn stat_root_path(path: &[u8]) -> Result<PathMetadata, FsError> {
    crate::os_stats::record_stat_call();
    let path = RootMetadataPath::parse(path)?;
    let components = path.components();
    let metadata = unsafe {
        ROOT_FS
            .get()
            .stat_path(ROOT_PARTITION, components.as_slice())
            .map_err(storage_error_to_fs_error)?
    };
    Ok(PathMetadata {
        file_type: metadata.file_type,
        size_bytes: metadata.size_bytes,
    })
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn close_file_fd_for_process(owner_pid: u32, fd: u32) -> Result<(), FsError> {
    unsafe { RUNTIME_FD_TABLE.get().close_for_process(owner_pid, fd) }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn close_file_fds_for_process(owner_pid: u32) {
    unsafe { RUNTIME_FD_TABLE.get().close_all_for_process(owner_pid) }
}

fn storage_error_to_fs_error(error: crate::kfs::error::StorageError) -> FsError {
    if error == crate::kfs::error::StorageError::PATH_NOT_FOUND {
        FsError::NoEntry
    } else if error == crate::kfs::error::StorageError::OUTPUT_BUFFER_TOO_SMALL {
        FsError::NoMemory
    } else if error == crate::kfs::error::StorageError::OUTPUT_TRANSFER {
        FsError::Fault
    } else if error == crate::kfs::error::StorageError::PATH_NOT_EMPTY {
        FsError::NotEmpty
    } else if error == crate::kfs::error::StorageError::PATH_EXISTS {
        FsError::InvalidPath
    } else if error == crate::kfs::error::StorageError::PATH_NOT_REGULAR {
        FsError::InvalidPath
    } else if error == crate::kfs::error::StorageError::PATH_BUSY {
        FsError::Busy
    } else {
        FsError::Storage
    }
}

#[cfg(any(not(test), feature = "host-test"))]
unsafe fn flush_root_storage() -> Result<(), FsError> {
    unsafe { crate::kfs::device::flush_storage0().map_err(storage_error_to_fs_error) }
}

#[cfg(any(not(test), feature = "host-test"))]
unsafe fn invalidate_root_fs_cache() {
    unsafe { ROOT_FS.get().invalidate_all() };
}

#[cfg(any(not(test), feature = "host-test"))]
fn fs_error_to_storage_output_error(error: FsError) -> crate::kfs::error::StorageError {
    if error == FsError::NoMemory {
        crate::kfs::error::StorageError::OUTPUT_BUFFER_TOO_SMALL
    } else if error == FsError::Fault {
        crate::kfs::error::StorageError::OUTPUT_TRANSFER
    } else {
        crate::kfs::error::StorageError::INVALID_FILESYSTEM
    }
}

fn parse_root_path(path: &[u8], allow_root: bool) -> Result<RootFilePath, FsError> {
    if !path.starts_with(b"/") || path.ends_with(b"/") && path.len() > 1 {
        return Err(FsError::InvalidPath);
    }
    if path.len() == 1 {
        return if allow_root {
            Ok(RootFilePath {
                bytes: [[0; MAX_NAME_BYTES]; MAX_PATH_COMPONENTS],
                lens: [0; MAX_PATH_COMPONENTS],
                count: 0,
            })
        } else {
            Err(FsError::InvalidPath)
        };
    }

    let mut parsed = RootFilePath {
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

fn append_requested(flags: u32) -> Result<bool, FsError> {
    if flags & OPEN_APPEND != 0 {
        if flags & OPEN_WRITE_ONLY == 0 {
            return Err(FsError::InvalidFlags);
        }
        Ok(true)
    } else {
        Ok(false)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn root_file_path_accepts_absolute_kfs_file_path() {
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
    fn root_directory_path_accepts_root_and_absolute_directory_path() {
        let root = RootDirectoryPath::parse(b"/").expect("root parses");
        assert!(root.components().as_slice().is_empty());

        let bin = RootDirectoryPath::parse(b"/bin").expect("directory parses");
        assert_eq!(bin.components().as_slice(), &[b"bin".as_slice()]);
    }

    #[test]
    fn root_metadata_path_accepts_root_file_and_directory_paths() {
        let root = RootMetadataPath::parse(b"/").expect("root parses");
        assert!(root.components().as_slice().is_empty());

        let bin = RootMetadataPath::parse(b"/bin").expect("directory parses");
        assert_eq!(bin.components().as_slice(), &[b"bin".as_slice()]);

        let cat = RootMetadataPath::parse(b"/bin/cat.kx").expect("file parses");
        assert_eq!(
            cat.components().as_slice(),
            &[b"bin".as_slice(), b"cat.kx".as_slice()]
        );
    }

    #[test]
    fn file_descriptor_table_allocates_reads_and_closes_file_fds() {
        let mut table = FileDescriptorTable::new();
        let fd = table
            .open(FileMetadata {
                inode_id: 2,
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
            .open_for_process(parent_pid, FileMetadata::empty(), OPEN_READ_ONLY)
            .expect("parent fd allocates");
        let child_fd = table
            .open_for_process(child_pid, FileMetadata::empty(), OPEN_READ_ONLY)
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
            table.open_for_process(child_pid, FileMetadata::empty(), OPEN_READ_ONLY),
            Ok(child_fd)
        );
    }

    #[test]
    fn file_descriptor_table_opens_append_at_end_and_seeks_within_file() {
        let mut table = FileDescriptorTable::new();
        let metadata = FileMetadata {
            inode_id: 2,
            size_bytes: 11,
            extent_count: 1,
            extent_start_blocks: [5, 0, 0, 0],
            extent_block_counts: [1, 0, 0, 0],
        };
        let fd = table
            .open_for_process(1, metadata, OPEN_WRITE_ONLY | OPEN_CREATE | OPEN_APPEND)
            .expect("append fd allocates");

        assert_eq!(
            table.write_plan_for_process(1, fd, 0).expect("fd writable"),
            (metadata, 11, 0)
        );
        assert_eq!(
            table.seek_for_process(1, fd, 0, k16_abi::syscall::SEEK_SET),
            Ok(0)
        );
        assert_eq!(
            table.write_plan_for_process(1, fd, 0).expect("fd writable"),
            (metadata, 0, 0)
        );
        assert_eq!(
            table.seek_for_process(1, fd, 0, k16_abi::syscall::SEEK_END),
            Ok(11)
        );
        assert_eq!(
            table.seek_for_process(1, fd, 12, k16_abi::syscall::SEEK_SET),
            Err(FsError::InvalidFlags)
        );
    }

    #[test]
    fn file_descriptor_table_reports_open_inode_for_busy_unlink() {
        let mut table = FileDescriptorTable::new();
        let metadata = FileMetadata {
            inode_id: 42,
            size_bytes: 5,
            extent_count: 1,
            extent_start_blocks: [9, 0, 0, 0],
            extent_block_counts: [1, 0, 0, 0],
        };
        let fd = table
            .open_for_process(1, metadata, OPEN_READ_ONLY)
            .expect("fd allocates");

        assert!(table.has_open_inode(42));
        assert!(!table.has_open_inode(43));
        table.close_for_process(1, fd).expect("fd closes");
        assert!(!table.has_open_inode(42));
    }
}
