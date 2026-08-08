use crate::kfs::directory::KFS_DIRECTORY_ENTRY_SIZE;
use crate::kfs::error::StorageError;
use crate::kfs::types::{FileMetadata, KFS_MAX_INLINE_EXTENTS};
use crate::kfs::{block_io, file, inode, selected_inode};

pub unsafe fn open_file_for_write(
    volume: &mut crate::kfs::volume::KfsVolume,
    path: &[&[u8]],
    create: bool,
    truncate: bool,
) -> Result<FileMetadata, StorageError> {
    if volume.read_only {
        return Err(StorageError::READ_ONLY);
    }
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    match unsafe { crate::kfs::path::find_file_inode(volume, path) } {
        Ok(()) => {
            if truncate {
                unsafe { truncate_selected_file(volume)? };
            }
            Ok(volume.selected_inode.file_metadata())
        }
        Err(error) if error == StorageError::PATH_NOT_FOUND && create => unsafe {
            create_empty_file(volume, path)
        },
        Err(error) => Err(error),
    }
}

pub unsafe fn remove_file(
    volume: &mut crate::kfs::volume::KfsVolume,
    path: &[&[u8]],
    source_inode_is_busy: impl FnOnce(u32) -> bool,
) -> Result<(), StorageError> {
    if volume.read_only {
        return Err(StorageError::READ_ONLY);
    }
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    let parent_len = path.len() - 1;
    unsafe { crate::kfs::path::find_directory_inode(volume, &path[..parent_len])? };
    let slot = unsafe { crate::kfs::path::find_directory_entry_slot(volume, path[parent_len])? };
    let inode_id = slot.inode_id;
    unsafe { inode::load_inode(volume, inode_id)? };
    if volume.selected_inode.state() != selected_inode::INODE_STATE_REGULAR {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    if source_inode_is_busy(inode_id) {
        return Err(StorageError::PATH_BUSY);
    }
    let metadata = volume.selected_inode.file_metadata();
    let mut extent_index = 0;
    while extent_index < metadata.extent_count as usize {
        let start_block = metadata.extent_start_blocks[extent_index];
        let block_count = metadata.extent_block_counts[extent_index];
        file::validate_extent(
            start_block,
            block_count,
            volume.filesystem.superblock_total_blocks(),
        )?;
        let mut block = start_block;
        while block < start_block + block_count {
            unsafe { block_io::clear_scratch_block() };
            unsafe { block_io::write_fs_block(volume, block)? };
            unsafe { crate::kfs::allocation::mark_block_free(volume, block)? };
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
    unsafe { crate::kfs::inode_mutation::encode_deleted_file_inode(volume, deleted)? };
    unsafe {
        crate::kfs::directory_mutation::encode_deleted_directory_entry_at(
            volume,
            slot.block,
            slot.offset,
        )
    }
}

pub unsafe fn rename_file(
    volume: &mut crate::kfs::volume::KfsVolume,
    old_path: &[&[u8]],
    new_path: &[&[u8]],
    source_inode_is_busy: impl FnOnce(u32) -> bool,
) -> Result<(), StorageError> {
    if volume.read_only {
        return Err(StorageError::READ_ONLY);
    }
    if old_path.is_empty() || new_path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }

    let old_parent_len = old_path.len() - 1;
    unsafe { crate::kfs::path::find_directory_inode(volume, &old_path[..old_parent_len])? };
    let old_slot =
        unsafe { crate::kfs::path::find_directory_entry_slot(volume, old_path[old_parent_len])? };
    let inode_id = old_slot.inode_id;
    unsafe { inode::load_inode(volume, inode_id)? };
    if volume.selected_inode.state() != selected_inode::INODE_STATE_REGULAR {
        return Err(StorageError::PATH_NOT_REGULAR);
    }
    if source_inode_is_busy(inode_id) {
        return Err(StorageError::PATH_BUSY);
    }

    let new_parent_len = new_path.len() - 1;
    let new_name = new_path[new_parent_len];
    unsafe { crate::kfs::path::find_directory_inode(volume, &new_path[..new_parent_len])? };
    match unsafe { crate::kfs::path::find_directory_entry(volume, new_name) } {
        Ok(_) => return Err(StorageError::PATH_EXISTS),
        Err(error) if error == StorageError::PATH_NOT_FOUND => {}
        Err(error) => return Err(error),
    }
    let new_slot =
        unsafe { crate::kfs::directory_mutation::find_selected_directory_free_slot(volume)? };
    let new_parent_inode_id = volume.selected_inode.inode_id();

    unsafe {
        crate::kfs::directory_mutation::encode_directory_entry_at(
            volume,
            new_slot.block,
            new_slot.offset,
            inode_id,
            new_name,
        )?
    };
    unsafe { inode::load_inode(volume, new_parent_inode_id)? };
    let new_size = max_u32(
        volume.selected_inode.size(),
        new_slot.directory_offset + KFS_DIRECTORY_ENTRY_SIZE,
    );
    unsafe {
        crate::kfs::inode_mutation::encode_selected_inode_size(
            volume,
            new_parent_inode_id,
            new_size,
        )?
    };
    unsafe {
        crate::kfs::directory_mutation::encode_deleted_directory_entry_at(
            volume,
            old_slot.block,
            old_slot.offset,
        )
    }
}

pub unsafe fn create_directory(
    volume: &mut crate::kfs::volume::KfsVolume,
    path: &[&[u8]],
) -> Result<(), StorageError> {
    if volume.read_only {
        return Err(StorageError::READ_ONLY);
    }
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { create_empty_directory(volume, path) }
}

pub unsafe fn remove_directory(
    volume: &mut crate::kfs::volume::KfsVolume,
    path: &[&[u8]],
) -> Result<(), StorageError> {
    if volume.read_only {
        return Err(StorageError::READ_ONLY);
    }
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    let parent_len = path.len() - 1;
    unsafe { crate::kfs::path::find_directory_inode(volume, &path[..parent_len])? };
    let slot = unsafe { crate::kfs::path::find_directory_entry_slot(volume, path[parent_len])? };
    let inode_id = slot.inode_id;
    unsafe { inode::load_inode(volume, inode_id)? };
    if volume.selected_inode.state() != selected_inode::INODE_STATE_DIRECTORY {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { crate::kfs::directory_listing::ensure_selected_directory_is_empty(volume)? };
    let metadata = volume.selected_inode.file_metadata();
    let mut extent_index = 0;
    while extent_index < metadata.extent_count as usize {
        let start_block = metadata.extent_start_blocks[extent_index];
        let block_count = metadata.extent_block_counts[extent_index];
        file::validate_extent(
            start_block,
            block_count,
            volume.filesystem.superblock_total_blocks(),
        )?;
        let mut block = start_block;
        while block < start_block + block_count {
            unsafe { block_io::clear_scratch_block() };
            unsafe { block_io::write_fs_block(volume, block)? };
            unsafe { crate::kfs::allocation::mark_block_free(volume, block)? };
            block += 1;
        }
        extent_index += 1;
    }
    unsafe { crate::kfs::inode_mutation::encode_deleted_directory_inode(volume, metadata)? };
    unsafe {
        crate::kfs::directory_mutation::encode_deleted_directory_entry_at(
            volume,
            slot.block,
            slot.offset,
        )
    }
}

unsafe fn create_empty_file(
    volume: &mut crate::kfs::volume::KfsVolume,
    path: &[&[u8]],
) -> Result<FileMetadata, StorageError> {
    let parent_len = path.len() - 1;
    let name = path[parent_len];
    unsafe { crate::kfs::path::find_directory_inode(volume, &path[..parent_len])? };
    match unsafe { crate::kfs::path::find_directory_entry(volume, name) } {
        Ok(_) => return Err(StorageError::INVALID_FILESYSTEM),
        Err(error) if error == StorageError::PATH_NOT_FOUND => {}
        Err(error) => return Err(error),
    }
    let slot =
        unsafe { crate::kfs::directory_mutation::find_selected_directory_free_slot(volume)? };
    let parent_inode_id = volume.selected_inode.inode_id();
    let inode_id = unsafe { crate::kfs::allocation::allocate_inode(volume)? };
    let start_block = unsafe { crate::kfs::allocation::allocate_contiguous_blocks(volume, 1)? };
    unsafe { block_io::clear_scratch_block() };
    unsafe { block_io::write_fs_block(volume, start_block)? };
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
    unsafe { crate::kfs::inode_mutation::encode_file_inode(volume, metadata)? };
    unsafe {
        crate::kfs::directory_mutation::encode_directory_entry_at(
            volume,
            slot.block,
            slot.offset,
            inode_id,
            name,
        )?
    };
    unsafe { inode::load_inode(volume, parent_inode_id)? };
    let new_size = max_u32(
        volume.selected_inode.size(),
        slot.directory_offset + KFS_DIRECTORY_ENTRY_SIZE,
    );
    unsafe {
        crate::kfs::inode_mutation::encode_selected_inode_size(volume, parent_inode_id, new_size)?
    };
    unsafe { inode::load_inode(volume, inode_id)? };
    Ok(volume.selected_inode.file_metadata())
}

unsafe fn create_empty_directory(
    volume: &mut crate::kfs::volume::KfsVolume,
    path: &[&[u8]],
) -> Result<(), StorageError> {
    let parent_len = path.len() - 1;
    let name = path[parent_len];
    unsafe { crate::kfs::path::find_directory_inode(volume, &path[..parent_len])? };
    match unsafe { crate::kfs::path::find_directory_entry(volume, name) } {
        Ok(_) => return Err(StorageError::INVALID_FILESYSTEM),
        Err(error) if error == StorageError::PATH_NOT_FOUND => {}
        Err(error) => return Err(error),
    }
    let slot =
        unsafe { crate::kfs::directory_mutation::find_selected_directory_free_slot(volume)? };
    let parent_inode_id = volume.selected_inode.inode_id();
    let inode_id = unsafe { crate::kfs::allocation::allocate_inode(volume)? };
    let start_block = unsafe { crate::kfs::allocation::allocate_contiguous_blocks(volume, 1)? };
    unsafe { block_io::clear_scratch_block() };
    unsafe { block_io::write_fs_block(volume, start_block)? };
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
    unsafe { crate::kfs::inode_mutation::encode_directory_inode(volume, metadata)? };
    unsafe {
        crate::kfs::directory_mutation::encode_directory_entry_at(
            volume,
            slot.block,
            slot.offset,
            inode_id,
            name,
        )?
    };
    unsafe { inode::load_inode(volume, parent_inode_id)? };
    let new_size = max_u32(
        volume.selected_inode.size(),
        slot.directory_offset + KFS_DIRECTORY_ENTRY_SIZE,
    );
    unsafe {
        crate::kfs::inode_mutation::encode_selected_inode_size(volume, parent_inode_id, new_size)
    }
}

unsafe fn truncate_selected_file(
    volume: &mut crate::kfs::volume::KfsVolume,
) -> Result<(), StorageError> {
    let mut metadata = volume.selected_inode.file_metadata();
    if metadata.extent_count == 0 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    metadata.size_bytes = 0;
    unsafe { crate::kfs::inode_mutation::encode_file_inode(volume, metadata) }
}

fn max_u32(left: u32, right: u32) -> u32 {
    if left > right {
        left
    } else {
        right
    }
}
