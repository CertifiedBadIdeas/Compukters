use crate::kfs::directory::KFS_DIRECTORY_ENTRY_SIZE;
use crate::kfs::error::StorageError;
use crate::kfs::storage;
use crate::kfs::types::{FileMetadata, KFS_MAX_INLINE_EXTENTS};

pub unsafe fn open_file_for_write_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
    create: bool,
    truncate: bool,
) -> Result<FileMetadata, StorageError> {
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { storage::read_partition(partition_type)? };
    unsafe { storage::read_superblock()? };
    match unsafe { crate::kfs::path::find_file_inode(path) } {
        Ok(()) => {
            if truncate {
                unsafe { truncate_selected_file()? };
            }
            Ok(unsafe { storage::selected_file_metadata() })
        }
        Err(error) if error == StorageError::PATH_NOT_FOUND && create => unsafe {
            create_empty_file(path)
        },
        Err(error) => Err(error),
    }
}

pub unsafe fn remove_file_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
) -> Result<(), StorageError> {
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { storage::read_partition(partition_type)? };
    unsafe { storage::read_superblock()? };
    let parent_len = path.len() - 1;
    unsafe { crate::kfs::path::find_directory_inode(&path[..parent_len])? };
    let slot = unsafe { crate::kfs::path::find_directory_entry_slot(path[parent_len])? };
    let inode_id = slot.inode_id;
    unsafe { storage::read_inode(inode_id)? };
    if unsafe { storage::selected_inode_state() } != storage::INODE_STATE_REGULAR {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    let metadata = unsafe { storage::selected_file_metadata() };
    let mut extent_index = 0;
    while extent_index < metadata.extent_count as usize {
        let start_block = metadata.extent_start_blocks[extent_index];
        let block_count = metadata.extent_block_counts[extent_index];
        storage::validate_extent(start_block, block_count, unsafe {
            storage::superblock_total_blocks()
        })?;
        let mut block = start_block;
        while block < start_block + block_count {
            unsafe { storage::clear_scratch_block() };
            unsafe { storage::write_fs_block(block)? };
            unsafe { crate::kfs::allocation::mark_block_free(block)? };
            block += 1;
        }
        extent_index += 1;
    }
    let deleted = FileMetadata {
        inode_id,
        size_bytes: 0,
        extent_count: 0,
        extent_start_blocks: [0; KFS_MAX_INLINE_EXTENTS],
        extent_block_counts: [0; KFS_MAX_INLINE_EXTENTS],
    };
    unsafe { crate::kfs::inode_mutation::encode_deleted_file_inode(deleted)? };
    unsafe {
        crate::kfs::directory_mutation::encode_deleted_directory_entry_at(slot.block, slot.offset)
    }
}

pub unsafe fn rename_file_from_storage0(
    partition_type: &[u8; 4],
    old_path: &[&[u8]],
    new_path: &[&[u8]],
    source_inode_is_busy: impl FnOnce(u32) -> bool,
) -> Result<(), StorageError> {
    if old_path.is_empty() || new_path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { storage::read_partition(partition_type)? };
    unsafe { storage::read_superblock()? };

    let old_parent_len = old_path.len() - 1;
    unsafe { crate::kfs::path::find_directory_inode(&old_path[..old_parent_len])? };
    let old_slot =
        unsafe { crate::kfs::path::find_directory_entry_slot(old_path[old_parent_len])? };
    let inode_id = old_slot.inode_id;
    unsafe { storage::read_inode(inode_id)? };
    if unsafe { storage::selected_inode_state() } != storage::INODE_STATE_REGULAR {
        return Err(StorageError::PATH_NOT_REGULAR);
    }
    if source_inode_is_busy(inode_id) {
        return Err(StorageError::PATH_BUSY);
    }

    let new_parent_len = new_path.len() - 1;
    let new_name = new_path[new_parent_len];
    unsafe { crate::kfs::path::find_directory_inode(&new_path[..new_parent_len])? };
    match unsafe { crate::kfs::path::find_directory_entry(new_name) } {
        Ok(_) => return Err(StorageError::PATH_EXISTS),
        Err(error) if error == StorageError::PATH_NOT_FOUND => {}
        Err(error) => return Err(error),
    }
    let new_slot = unsafe { crate::kfs::directory_mutation::find_selected_directory_free_slot()? };
    let new_parent_inode_id = unsafe { storage::selected_inode_id() };

    unsafe {
        crate::kfs::directory_mutation::encode_directory_entry_at(
            new_slot.block,
            new_slot.offset,
            inode_id,
            new_name,
        )?
    };
    unsafe { storage::read_inode(new_parent_inode_id)? };
    let new_size = max_u32(
        unsafe { storage::selected_inode_size() },
        new_slot.directory_offset + KFS_DIRECTORY_ENTRY_SIZE,
    );
    unsafe {
        crate::kfs::inode_mutation::encode_selected_inode_size(new_parent_inode_id, new_size)?
    };
    unsafe {
        crate::kfs::directory_mutation::encode_deleted_directory_entry_at(
            old_slot.block,
            old_slot.offset,
        )
    }
}

pub unsafe fn create_directory_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
) -> Result<(), StorageError> {
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { storage::read_partition(partition_type)? };
    unsafe { storage::read_superblock()? };
    unsafe { create_empty_directory(path) }
}

pub unsafe fn remove_directory_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
) -> Result<(), StorageError> {
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { storage::read_partition(partition_type)? };
    unsafe { storage::read_superblock()? };
    let parent_len = path.len() - 1;
    unsafe { crate::kfs::path::find_directory_inode(&path[..parent_len])? };
    let slot = unsafe { crate::kfs::path::find_directory_entry_slot(path[parent_len])? };
    let inode_id = slot.inode_id;
    unsafe { storage::read_inode(inode_id)? };
    if unsafe { storage::selected_inode_state() } != storage::INODE_STATE_DIRECTORY {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { crate::kfs::directory_listing::ensure_selected_directory_is_empty()? };
    let metadata = unsafe { storage::selected_file_metadata() };
    let mut extent_index = 0;
    while extent_index < metadata.extent_count as usize {
        let start_block = metadata.extent_start_blocks[extent_index];
        let block_count = metadata.extent_block_counts[extent_index];
        storage::validate_extent(start_block, block_count, unsafe {
            storage::superblock_total_blocks()
        })?;
        let mut block = start_block;
        while block < start_block + block_count {
            unsafe { storage::clear_scratch_block() };
            unsafe { storage::write_fs_block(block)? };
            unsafe { crate::kfs::allocation::mark_block_free(block)? };
            block += 1;
        }
        extent_index += 1;
    }
    unsafe { crate::kfs::inode_mutation::encode_deleted_directory_inode(metadata)? };
    unsafe {
        crate::kfs::directory_mutation::encode_deleted_directory_entry_at(slot.block, slot.offset)
    }
}

unsafe fn create_empty_file(path: &[&[u8]]) -> Result<FileMetadata, StorageError> {
    let parent_len = path.len() - 1;
    let name = path[parent_len];
    unsafe { crate::kfs::path::find_directory_inode(&path[..parent_len])? };
    match unsafe { crate::kfs::path::find_directory_entry(name) } {
        Ok(_) => return Err(StorageError::INVALID_FILESYSTEM),
        Err(error) if error == StorageError::PATH_NOT_FOUND => {}
        Err(error) => return Err(error),
    }
    let slot = unsafe { crate::kfs::directory_mutation::find_selected_directory_free_slot()? };
    let parent_inode_id = unsafe { storage::selected_inode_id() };
    let inode_id = unsafe { crate::kfs::allocation::allocate_inode()? };
    let start_block = unsafe { crate::kfs::allocation::allocate_contiguous_blocks(1)? };
    unsafe { storage::clear_scratch_block() };
    unsafe { storage::write_fs_block(start_block)? };
    let mut extent_start_blocks = [0; KFS_MAX_INLINE_EXTENTS];
    let mut extent_block_counts = [0; KFS_MAX_INLINE_EXTENTS];
    extent_start_blocks[0] = start_block;
    extent_block_counts[0] = 1;
    let metadata = FileMetadata {
        inode_id,
        size_bytes: 0,
        extent_count: 1,
        extent_start_blocks,
        extent_block_counts,
    };
    unsafe { crate::kfs::inode_mutation::encode_file_inode(metadata)? };
    unsafe {
        crate::kfs::directory_mutation::encode_directory_entry_at(
            slot.block,
            slot.offset,
            inode_id,
            name,
        )?
    };
    unsafe { storage::read_inode(parent_inode_id)? };
    let new_size = max_u32(
        unsafe { storage::selected_inode_size() },
        slot.directory_offset + KFS_DIRECTORY_ENTRY_SIZE,
    );
    unsafe { crate::kfs::inode_mutation::encode_selected_inode_size(parent_inode_id, new_size)? };
    unsafe { storage::read_inode(inode_id)? };
    Ok(unsafe { storage::selected_file_metadata() })
}

unsafe fn create_empty_directory(path: &[&[u8]]) -> Result<(), StorageError> {
    let parent_len = path.len() - 1;
    let name = path[parent_len];
    unsafe { crate::kfs::path::find_directory_inode(&path[..parent_len])? };
    match unsafe { crate::kfs::path::find_directory_entry(name) } {
        Ok(_) => return Err(StorageError::INVALID_FILESYSTEM),
        Err(error) if error == StorageError::PATH_NOT_FOUND => {}
        Err(error) => return Err(error),
    }
    let slot = unsafe { crate::kfs::directory_mutation::find_selected_directory_free_slot()? };
    let parent_inode_id = unsafe { storage::selected_inode_id() };
    let inode_id = unsafe { crate::kfs::allocation::allocate_inode()? };
    let start_block = unsafe { crate::kfs::allocation::allocate_contiguous_blocks(1)? };
    unsafe { storage::clear_scratch_block() };
    unsafe { storage::write_fs_block(start_block)? };
    let mut extent_start_blocks = [0; KFS_MAX_INLINE_EXTENTS];
    let mut extent_block_counts = [0; KFS_MAX_INLINE_EXTENTS];
    extent_start_blocks[0] = start_block;
    extent_block_counts[0] = 1;
    let metadata = FileMetadata {
        inode_id,
        size_bytes: 0,
        extent_count: 1,
        extent_start_blocks,
        extent_block_counts,
    };
    unsafe { crate::kfs::inode_mutation::encode_directory_inode(metadata)? };
    unsafe {
        crate::kfs::directory_mutation::encode_directory_entry_at(
            slot.block,
            slot.offset,
            inode_id,
            name,
        )?
    };
    unsafe { storage::read_inode(parent_inode_id)? };
    let new_size = max_u32(
        unsafe { storage::selected_inode_size() },
        slot.directory_offset + KFS_DIRECTORY_ENTRY_SIZE,
    );
    unsafe { crate::kfs::inode_mutation::encode_selected_inode_size(parent_inode_id, new_size) }
}

unsafe fn truncate_selected_file() -> Result<(), StorageError> {
    let mut metadata = unsafe { storage::selected_file_metadata() };
    if metadata.extent_count == 0 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    metadata.size_bytes = 0;
    unsafe { crate::kfs::inode_mutation::encode_file_inode(metadata) }
}

fn max_u32(left: u32, right: u32) -> u32 {
    if left > right {
        left
    } else {
        right
    }
}
