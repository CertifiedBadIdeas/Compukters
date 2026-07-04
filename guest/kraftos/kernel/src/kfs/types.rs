use crate::kfs::error::StorageError;

pub const KFS_MAX_INLINE_EXTENTS: usize = 4;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FileMetadata {
    pub inode_id: u32,
    pub size_bytes: u32,
    pub extent_count: u32,
    pub extent_start_blocks: [u32; KFS_MAX_INLINE_EXTENTS],
    pub extent_block_counts: [u32; KFS_MAX_INLINE_EXTENTS],
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FileReadProfileKind {
    GenericFile,
    Program(FileReadProfileFile),
    DynamicImport(FileReadProfileFile),
    Library(FileReadProfileFile),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FileReadProfileFile {
    Generic,
    InitProgram,
    ShellProgram,
    OtherProgram,
    LibkraftLibrary,
    OtherLibrary,
}

#[repr(u32)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PathKind {
    Regular = k16_abi::syscall::FILE_TYPE_REGULAR,
    Directory = k16_abi::syscall::FILE_TYPE_DIRECTORY,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PathMetadata {
    pub kind: PathKind,
    pub size_bytes: u32,
}

pub trait DirectoryListingSink {
    unsafe fn push_byte(&mut self, byte: u8) -> Result<(), StorageError>;
    fn written(&self) -> u32;
}

pub struct RamDirectoryListingSink {
    dst_addr: u32,
    len: u32,
    written: u32,
}

impl RamDirectoryListingSink {
    pub const fn new(dst_addr: u32, len: u32) -> Self {
        Self {
            dst_addr,
            len,
            written: 0,
        }
    }
}

impl DirectoryListingSink for RamDirectoryListingSink {
    unsafe fn push_byte(&mut self, byte: u8) -> Result<(), StorageError> {
        if self.written >= self.len {
            return Err(StorageError::OUTPUT_BUFFER_TOO_SMALL);
        }
        unsafe { write_u8(self.dst_addr + self.written, byte) };
        self.written += 1;
        Ok(())
    }

    fn written(&self) -> u32 {
        self.written
    }
}

unsafe fn write_u8(address: u32, value: u8) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u8, value) }
}
