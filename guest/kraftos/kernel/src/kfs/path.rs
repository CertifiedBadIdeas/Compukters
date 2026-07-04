use crate::kfs::directory::{KfsDirectoryEntryHeader, KFS_DIRECTORY_ENTRY_SIZE};
use crate::kfs::error::StorageError;
use crate::kfs::{block_io, file, filesystem_state, inode, selected_inode};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KfsDirectoryEntrySlot {
    pub inode_id: u32,
    pub block: u32,
    pub offset: u32,
}

pub unsafe fn find_file_inode(path: &[&[u8]]) -> Result<(), StorageError> {
    crate::os_stats::record_path_lookup();
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }

    let mut inode_id = unsafe { filesystem_state::root_inode_id() };
    let mut index = 0;
    while index < path.len() {
        let component = path[index];
        unsafe { inode::load_inode(inode_id)? };
        if unsafe { selected_inode::selected_inode_state() }
            != selected_inode::INODE_STATE_DIRECTORY
        {
            return Err(StorageError::PATH_NOT_FOUND);
        }
        inode_id = unsafe { find_directory_entry(component)? };
        index += 1;
    }

    unsafe { inode::load_inode(inode_id)? };
    if unsafe { selected_inode::selected_inode_state() } != selected_inode::INODE_STATE_REGULAR {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    Ok(())
}

pub unsafe fn find_directory_inode(path: &[&[u8]]) -> Result<(), StorageError> {
    crate::os_stats::record_path_lookup();
    let mut inode_id = unsafe { filesystem_state::root_inode_id() };
    let mut index = 0;
    while index < path.len() {
        unsafe { inode::load_inode(inode_id)? };
        if unsafe { selected_inode::selected_inode_state() }
            != selected_inode::INODE_STATE_DIRECTORY
        {
            return Err(StorageError::PATH_NOT_FOUND);
        }
        inode_id = unsafe { find_directory_entry(path[index])? };
        index += 1;
    }

    unsafe { inode::load_inode(inode_id)? };
    if unsafe { selected_inode::selected_inode_state() } != selected_inode::INODE_STATE_DIRECTORY {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    Ok(())
}

pub unsafe fn find_path_inode(path: &[&[u8]]) -> Result<(), StorageError> {
    crate::os_stats::record_path_lookup();
    let mut inode_id = unsafe { filesystem_state::root_inode_id() };
    if path.is_empty() {
        unsafe { inode::load_inode(inode_id)? };
        return Ok(());
    }

    let mut index = 0;
    while index < path.len() {
        unsafe { inode::load_inode(inode_id)? };
        if unsafe { selected_inode::selected_inode_state() }
            != selected_inode::INODE_STATE_DIRECTORY
        {
            return Err(StorageError::PATH_NOT_FOUND);
        }
        inode_id = unsafe { find_directory_entry(path[index])? };
        index += 1;
    }

    unsafe { inode::load_inode(inode_id) }
}

pub unsafe fn find_directory_entry(name: &[u8]) -> Result<u32, StorageError> {
    let slot = unsafe { find_directory_entry_slot(name)? };
    Ok(slot.inode_id)
}

pub unsafe fn find_directory_entry_slot(
    name: &[u8],
) -> Result<KfsDirectoryEntrySlot, StorageError> {
    crate::kfs::directory::validate_name(name)?;
    if !crate::kfs::directory::directory_size_is_aligned(unsafe {
        selected_inode::selected_inode_size()
    }) {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let mut remaining = unsafe { selected_inode::selected_inode_size() };
    let mut extent_index = 0;
    while extent_index < unsafe { selected_inode::selected_inode_extent_count() as usize } {
        let extent_start_block =
            unsafe { selected_inode::selected_inode_extent_start_block(extent_index) };
        let extent_block_count =
            unsafe { selected_inode::selected_inode_extent_block_count(extent_index) };
        file::validate_extent(extent_start_block, extent_block_count, unsafe {
            filesystem_state::superblock_total_blocks()
        })?;
        let mut block_index = 0;
        while block_index < extent_block_count && remaining > 0 {
            unsafe { block_io::read_fs_block(extent_start_block + block_index)? };
            let mut offset = 0;
            while offset < block_io::BLOCK_SIZE && remaining > 0 {
                crate::os_stats::record_dir_entry_scan();
                match crate::kfs::directory::decode_entry_header(
                    block_io::scratch_u8(offset),
                    block_io::scratch_u8(offset + 1),
                    block_io::scratch_u8(offset + 2),
                    block_io::scratch_u8(offset + 3),
                    block_io::scratch_u32(offset + 4),
                )? {
                    KfsDirectoryEntryHeader::Free | KfsDirectoryEntryHeader::Deleted => {}
                    KfsDirectoryEntryHeader::Live { inode_id, name_len } => {
                        if name_len == name.len() && block_io::scratch_bytes_eq(offset + 8, name) {
                            return Ok(KfsDirectoryEntrySlot {
                                inode_id,
                                block: extent_start_block + block_index,
                                offset,
                            });
                        }
                    }
                }
                remaining -= KFS_DIRECTORY_ENTRY_SIZE;
                offset += KFS_DIRECTORY_ENTRY_SIZE;
            }
            block_index += 1;
        }
        extent_index += 1;
    }

    if remaining != 0 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Err(StorageError::PATH_NOT_FOUND)
}
