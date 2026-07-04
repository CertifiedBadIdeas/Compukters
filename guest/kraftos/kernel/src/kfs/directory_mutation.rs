use crate::kfs::directory::{KfsDirectoryEntryHeader, KFS_DIRECTORY_ENTRY_SIZE};
use crate::kfs::error::StorageError;
use crate::kfs::storage;
use crate::kfs::types::KFS_MAX_INLINE_EXTENTS;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KfsDirectoryFreeSlot {
    pub block: u32,
    pub offset: u32,
    pub directory_offset: u32,
}

pub unsafe fn find_selected_directory_free_slot() -> Result<KfsDirectoryFreeSlot, StorageError> {
    if unsafe { storage::selected_inode_state() } != storage::INODE_STATE_DIRECTORY {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let mut directory_offset = 0;
    let mut extent_index = 0;
    while extent_index < unsafe { storage::selected_inode_extent_count() as usize } {
        let extent_start_block =
            unsafe { storage::selected_inode_extent_start_block(extent_index) };
        let extent_block_count =
            unsafe { storage::selected_inode_extent_block_count(extent_index) };
        storage::validate_extent(extent_start_block, extent_block_count, unsafe {
            storage::superblock_total_blocks()
        })?;
        let mut block_index = 0;
        while block_index < extent_block_count {
            unsafe { storage::read_fs_block(extent_start_block + block_index)? };
            let mut offset = 0;
            while offset < storage::BLOCK_SIZE {
                match crate::kfs::directory::decode_entry_header(
                    storage::scratch_u8(offset),
                    storage::scratch_u8(offset + 1),
                    storage::scratch_u8(offset + 2),
                    storage::scratch_u8(offset + 3),
                    storage::scratch_u32(offset + 4),
                )? {
                    KfsDirectoryEntryHeader::Free | KfsDirectoryEntryHeader::Deleted => {
                        return Ok(KfsDirectoryFreeSlot {
                            block: extent_start_block + block_index,
                            offset,
                            directory_offset,
                        });
                    }
                    KfsDirectoryEntryHeader::Live { .. } => {}
                }
                offset += KFS_DIRECTORY_ENTRY_SIZE;
                directory_offset += KFS_DIRECTORY_ENTRY_SIZE;
            }
            block_index += 1;
        }
        extent_index += 1;
    }

    Ok(KfsDirectoryFreeSlot {
        block: unsafe { grow_selected_directory_capacity()? },
        offset: 0,
        directory_offset,
    })
}

pub unsafe fn grow_selected_directory_capacity() -> Result<u32, StorageError> {
    if unsafe { storage::selected_inode_state() } != storage::INODE_STATE_DIRECTORY {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let mut metadata = unsafe { storage::selected_file_metadata() };
    if metadata.extent_count == 0 || metadata.extent_count as usize > KFS_MAX_INLINE_EXTENTS {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let last_extent_index = metadata.extent_count as usize - 1;
    let last_start = metadata.extent_start_blocks[last_extent_index];
    let last_count = metadata.extent_block_counts[last_extent_index];
    let grow_block = match last_start.checked_add(last_count) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    if grow_block < unsafe { storage::superblock_total_blocks() }
        && !unsafe { crate::kfs::allocation::is_block_allocated(grow_block)? }
    {
        unsafe { crate::kfs::allocation::mark_block_allocated(grow_block)? };
        unsafe { storage::clear_scratch_block() };
        unsafe { storage::write_fs_block(grow_block)? };
        metadata.extent_block_counts[last_extent_index] = match last_count.checked_add(1) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        unsafe { crate::kfs::inode_mutation::encode_directory_inode(metadata)? };
        return Ok(grow_block);
    }

    let new_extent_index = metadata.extent_count as usize;
    if new_extent_index >= KFS_MAX_INLINE_EXTENTS {
        return Err(StorageError::OUTPUT_BUFFER_TOO_SMALL);
    }
    let new_extent_block = unsafe { crate::kfs::allocation::allocate_contiguous_blocks(1)? };
    unsafe { storage::clear_scratch_block() };
    unsafe { storage::write_fs_block(new_extent_block)? };
    metadata.extent_start_blocks[new_extent_index] = new_extent_block;
    metadata.extent_block_counts[new_extent_index] = 1;
    metadata.extent_count = match metadata.extent_count.checked_add(1) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    unsafe { crate::kfs::inode_mutation::encode_directory_inode(metadata)? };
    Ok(new_extent_block)
}

pub unsafe fn encode_directory_entry_at(
    block: u32,
    offset: u32,
    inode_id: u32,
    name: &[u8],
) -> Result<(), StorageError> {
    let record = crate::kfs::directory::encode_entry(inode_id, name)?;
    unsafe { storage::read_fs_block(block)? };
    let mut cursor = 0;
    while cursor < KFS_DIRECTORY_ENTRY_SIZE {
        unsafe { storage::write_scratch_u8(offset + cursor, record.bytes[cursor as usize]) };
        cursor += 1;
    }
    unsafe { storage::write_fs_block(block) }
}

pub unsafe fn encode_deleted_directory_entry_at(
    block: u32,
    offset: u32,
) -> Result<(), StorageError> {
    let record = crate::kfs::directory::encode_deleted_entry();
    unsafe { storage::read_fs_block(block)? };
    let mut cursor = 0;
    while cursor < KFS_DIRECTORY_ENTRY_SIZE {
        unsafe { storage::write_scratch_u8(offset + cursor, record.bytes[cursor as usize]) };
        cursor += 1;
    }
    unsafe { storage::write_fs_block(block) }
}
