use crate::kfs::error::StorageError;
use crate::kfs::types::{FileMetadata, PathKind, PathMetadata, KFS_MAX_INLINE_EXTENTS};

const STATE_INODE_STATE: u32 = 0x0000_0218;
const STATE_INODE_SIZE_BYTES: u32 = 0x0000_021c;
const STATE_INODE_EXTENT_COUNT: u32 = 0x0000_0220;
const STATE_INODE_EXTENT_START_BLOCKS: u32 = 0x0000_0224;
const STATE_INODE_EXTENT_BLOCK_COUNTS: u32 = 0x0000_0234;
const STATE_SELECTED_INODE_ID: u32 = 0x0000_024c;

pub(crate) const INODE_STATE_REGULAR: u8 = 1;
pub(crate) const INODE_STATE_DIRECTORY: u8 = 2;

pub(crate) unsafe fn selected_inode_state() -> u8 {
    unsafe { read_u32(STATE_INODE_STATE) as u8 }
}

pub(crate) unsafe fn selected_inode_size() -> u32 {
    unsafe { read_u32(STATE_INODE_SIZE_BYTES) }
}

pub(crate) unsafe fn selected_inode_extent_count() -> u32 {
    unsafe { read_u32(STATE_INODE_EXTENT_COUNT) }
}

pub(crate) unsafe fn selected_inode_extent_start_block(index: usize) -> u32 {
    unsafe { read_u32(STATE_INODE_EXTENT_START_BLOCKS + index as u32 * 4) }
}

pub(crate) unsafe fn selected_inode_extent_block_count(index: usize) -> u32 {
    unsafe { read_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + index as u32 * 4) }
}

pub(crate) unsafe fn selected_inode_id() -> u32 {
    unsafe { read_u32(STATE_SELECTED_INODE_ID) }
}

pub unsafe fn select_inode_metadata_for_cache(
    inode_id: u32,
) -> Result<crate::kfs::cache::CachedPathMetadata, StorageError> {
    unsafe { crate::kfs::inode::load_inode(inode_id)? };
    unsafe { selected_metadata_for_cache() }
}

pub unsafe fn selected_metadata_for_cache(
) -> Result<crate::kfs::cache::CachedPathMetadata, StorageError> {
    let metadata = unsafe { selected_path_metadata()? };
    Ok(crate::kfs::cache::CachedPathMetadata {
        file_type: metadata.kind as u32,
        size_bytes: metadata.size_bytes,
    })
}

pub unsafe fn selected_file_size() -> u32 {
    unsafe { selected_inode_size() }
}

pub unsafe fn selected_path_metadata() -> Result<PathMetadata, StorageError> {
    let kind = match unsafe { selected_inode_state() } {
        INODE_STATE_REGULAR => PathKind::Regular,
        INODE_STATE_DIRECTORY => PathKind::Directory,
        _ => return Err(StorageError::INVALID_FILESYSTEM),
    };
    Ok(PathMetadata {
        kind,
        size_bytes: unsafe { selected_file_size() },
    })
}

pub unsafe fn selected_file_metadata() -> FileMetadata {
    let mut extent_start_blocks = [0; KFS_MAX_INLINE_EXTENTS];
    let mut extent_block_counts = [0; KFS_MAX_INLINE_EXTENTS];
    let extent_count = unsafe { selected_inode_extent_count() };
    let mut index = 0;
    while index < KFS_MAX_INLINE_EXTENTS {
        extent_start_blocks[index] = unsafe { selected_inode_extent_start_block(index) };
        extent_block_counts[index] = unsafe { selected_inode_extent_block_count(index) };
        index += 1;
    }
    FileMetadata {
        inode_id: unsafe { selected_inode_id() },
        size_bytes: unsafe { selected_file_size() },
        extent_count,
        extent_start_blocks,
        extent_block_counts,
    }
}

pub unsafe fn select_file_metadata(metadata: FileMetadata) -> Result<(), StorageError> {
    unsafe { crate::kfs::inode::load_inode(metadata.inode_id)? };
    if unsafe { selected_inode_state() } != INODE_STATE_REGULAR {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    if unsafe { selected_file_metadata() } != metadata {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(())
}

pub(crate) unsafe fn store_loaded_inode(
    inode_id: u32,
    state: u8,
    size_bytes: u32,
    extent_count: usize,
    extent_start_blocks: &[u32; KFS_MAX_INLINE_EXTENTS],
    extent_block_counts: &[u32; KFS_MAX_INLINE_EXTENTS],
) {
    unsafe {
        write_u32(STATE_SELECTED_INODE_ID, inode_id);
        write_u32(STATE_INODE_STATE, state as u32);
        write_u32(STATE_INODE_SIZE_BYTES, size_bytes);
        write_u32(STATE_INODE_EXTENT_COUNT, extent_count as u32);
    }
    let mut index = 0;
    while index < KFS_MAX_INLINE_EXTENTS {
        unsafe {
            write_u32(
                STATE_INODE_EXTENT_START_BLOCKS + index as u32 * 4,
                extent_start_blocks[index],
            );
            write_u32(
                STATE_INODE_EXTENT_BLOCK_COUNTS + index as u32 * 4,
                extent_block_counts[index],
            );
        }
        index += 1;
    }
}

unsafe fn read_u32(address: u32) -> u32 {
    unsafe { core::ptr::read_volatile(address as usize as *const u32) }
}

unsafe fn write_u32(address: u32, value: u32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u32, value) }
}
