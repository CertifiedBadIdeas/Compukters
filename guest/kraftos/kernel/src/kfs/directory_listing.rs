use crate::kfs::directory::{
    KfsDirectoryEntryHeader, KFS_DIRECTORY_ENTRIES_PER_BLOCK, KFS_DIRECTORY_ENTRY_SIZE,
    KFS_MAX_NAME_BYTES,
};
use crate::kfs::error::StorageError;
use crate::kfs::types::{DirectoryListingSink, KFS_MAX_INLINE_EXTENTS};
use crate::kfs::{block_io, file, filesystem_state, inode, selected_inode};

const INVALID_CACHED_INODE_BLOCK: u32 = u32::MAX;

pub unsafe fn copy_selected_directory_listing_into_cached<S: DirectoryListingSink>(
    sink: &mut S,
    cache: &mut crate::kfs::cache::KfsCache,
) -> Result<u32, StorageError> {
    if unsafe { selected_inode::selected_inode_state() } != selected_inode::INODE_STATE_DIRECTORY
        || !crate::kfs::directory::directory_size_is_aligned(unsafe {
            selected_inode::selected_inode_size()
        })
    {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let directory = unsafe { selected_inode::selected_file_metadata() };
    if directory.extent_count as usize > KFS_MAX_INLINE_EXTENTS {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let mut remaining = directory.size_bytes;
    let mut extent_index = 0;
    while extent_index < directory.extent_count as usize {
        let extent_start_block = directory.extent_start_blocks[extent_index];
        let extent_block_count = directory.extent_block_counts[extent_index];
        file::validate_extent(extent_start_block, extent_block_count, unsafe {
            filesystem_state::superblock_total_blocks()
        })?;
        let mut block_index = 0;
        while block_index < extent_block_count && remaining > 0 {
            let fs_block = extent_start_block + block_index;
            unsafe { block_io::read_fs_block(fs_block)? };
            crate::os_stats::record_read_dir_data_read(min_u32(block_io::BLOCK_SIZE, remaining));
            let mut entry_inode_ids = [0_u32; KFS_DIRECTORY_ENTRIES_PER_BLOCK];
            let mut entry_name_lengths = [0_u8; KFS_DIRECTORY_ENTRIES_PER_BLOCK];
            let mut entry_names = [[0_u8; KFS_MAX_NAME_BYTES]; KFS_DIRECTORY_ENTRIES_PER_BLOCK];
            let mut entry_count = 0_usize;
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
                        entry_inode_ids[entry_count] = inode_id;
                        entry_name_lengths[entry_count] = name_len as u8;
                        let mut name_offset = 0;
                        while name_offset < name_len {
                            entry_names[entry_count][name_offset] =
                                block_io::scratch_u8(offset + 8 + name_offset as u32);
                            name_offset += 1;
                        }
                        entry_count += 1;
                    }
                }
                remaining -= KFS_DIRECTORY_ENTRY_SIZE;
                offset += KFS_DIRECTORY_ENTRY_SIZE;
            }

            let mut cached_inode_block = INVALID_CACHED_INODE_BLOCK;
            let mut entry_index = 0;
            while entry_index < entry_count {
                let inode_id = entry_inode_ids[entry_index];
                let child = match cache.lookup_inode(inode_id) {
                    Some(metadata) => metadata,
                    None => {
                        let metadata = unsafe {
                            read_inode_path_metadata_cached(inode_id, &mut cached_inode_block)?
                        };
                        cache.store_inode(inode_id, metadata);
                        metadata
                    }
                };
                let name_len = entry_name_lengths[entry_index] as usize;
                unsafe {
                    push_directory_entry(
                        sink,
                        child.file_type,
                        &entry_names[entry_index][..name_len],
                        child.size_bytes,
                    )?;
                }
                entry_index += 1;
            }
            block_index += 1;
        }
        extent_index += 1;
    }

    if remaining != 0 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(sink.written())
}

pub unsafe fn copy_selected_directory_listing_into<S: DirectoryListingSink>(
    sink: &mut S,
) -> Result<u32, StorageError> {
    if unsafe { selected_inode::selected_inode_state() } != selected_inode::INODE_STATE_DIRECTORY
        || !crate::kfs::directory::directory_size_is_aligned(unsafe {
            selected_inode::selected_inode_size()
        })
    {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let directory = unsafe { selected_inode::selected_file_metadata() };
    if directory.extent_count as usize > KFS_MAX_INLINE_EXTENTS {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let mut remaining = directory.size_bytes;
    let mut extent_index = 0;
    while extent_index < directory.extent_count as usize {
        let extent_start_block = directory.extent_start_blocks[extent_index];
        let extent_block_count = directory.extent_block_counts[extent_index];
        file::validate_extent(extent_start_block, extent_block_count, unsafe {
            filesystem_state::superblock_total_blocks()
        })?;
        let mut block_index = 0;
        while block_index < extent_block_count && remaining > 0 {
            let fs_block = extent_start_block + block_index;
            let mut block_loaded = false;
            let mut offset = 0;
            while offset < block_io::BLOCK_SIZE && remaining > 0 {
                if !block_loaded {
                    unsafe { block_io::read_fs_block(fs_block)? };
                    block_loaded = true;
                }
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
                        let mut name = [0_u8; KFS_MAX_NAME_BYTES];
                        let mut name_offset = 0;
                        while name_offset < name_len {
                            name[name_offset] =
                                block_io::scratch_u8(offset + 8 + name_offset as u32);
                            name_offset += 1;
                        }
                        unsafe { inode::load_inode(inode_id)? };
                        let child = unsafe { selected_inode::selected_path_metadata()? };
                        unsafe {
                            push_directory_entry(
                                sink,
                                child.kind as u32,
                                &name[..name_len],
                                child.size_bytes,
                            )?;
                        }
                        block_loaded = false;
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
    Ok(sink.written())
}

pub unsafe fn ensure_selected_directory_is_empty() -> Result<(), StorageError> {
    if unsafe { selected_inode::selected_inode_state() } != selected_inode::INODE_STATE_DIRECTORY
        || !crate::kfs::directory::directory_size_is_aligned(unsafe {
            selected_inode::selected_inode_size()
        })
    {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let directory = unsafe { selected_inode::selected_file_metadata() };
    if directory.extent_count as usize > KFS_MAX_INLINE_EXTENTS {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let mut remaining = directory.size_bytes;
    let mut extent_index = 0;
    while extent_index < directory.extent_count as usize {
        let extent_start_block = directory.extent_start_blocks[extent_index];
        let extent_block_count = directory.extent_block_counts[extent_index];
        file::validate_extent(extent_start_block, extent_block_count, unsafe {
            filesystem_state::superblock_total_blocks()
        })?;
        let mut block_index = 0;
        while block_index < extent_block_count {
            unsafe { block_io::read_fs_block(extent_start_block + block_index)? };
            let mut offset = 0;
            while offset < block_io::BLOCK_SIZE && remaining > 0 {
                match crate::kfs::directory::decode_entry_header(
                    block_io::scratch_u8(offset),
                    block_io::scratch_u8(offset + 1),
                    block_io::scratch_u8(offset + 2),
                    block_io::scratch_u8(offset + 3),
                    block_io::scratch_u32(offset + 4),
                )? {
                    KfsDirectoryEntryHeader::Free | KfsDirectoryEntryHeader::Deleted => {}
                    KfsDirectoryEntryHeader::Live { .. } => {
                        return Err(StorageError::PATH_NOT_EMPTY);
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
    Ok(())
}

unsafe fn push_directory_entry<S: DirectoryListingSink>(
    sink: &mut S,
    file_type: u32,
    name: &[u8],
    size_bytes: u32,
) -> Result<(), StorageError> {
    unsafe {
        push_u32_le(sink, file_type)?;
        push_u32_le(sink, name.len() as u32)?;
    }
    for byte in name {
        unsafe { sink.push_byte(*byte)? };
    }
    unsafe { push_u32_le(sink, size_bytes) }
}

unsafe fn read_inode_path_metadata_cached(
    inode_id: u32,
    cached_inode_block: &mut u32,
) -> Result<crate::kfs::cache::CachedPathMetadata, StorageError> {
    let location = crate::kfs::inode::locate_inode(
        inode_id,
        unsafe { filesystem_state::superblock_inode_table_start_block() },
        unsafe { filesystem_state::superblock_inode_table_block_count() },
    )?;
    let inode_block = location.block;
    let inode_offset = location.offset;
    if *cached_inode_block != inode_block {
        crate::os_stats::record_inode_load();
        unsafe { block_io::read_fs_block(inode_block)? };
        *cached_inode_block = inode_block;
    }
    let size_high = block_io::scratch_u32(inode_offset + 0x0c);
    let extent_count = block_io::scratch_u8(inode_offset + 0x10) as usize;
    if size_high != 0 || extent_count > KFS_MAX_INLINE_EXTENTS {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let mut index = 0;
    while index < extent_count {
        let offset = inode_offset + 0x20 + index as u32 * 8;
        let start_block = block_io::scratch_u32(offset);
        let block_count = block_io::scratch_u32(offset + 4);
        file::validate_extent(start_block, block_count, unsafe {
            filesystem_state::superblock_total_blocks()
        })?;
        index += 1;
    }
    let file_type = match block_io::scratch_u8(inode_offset) {
        selected_inode::INODE_STATE_REGULAR => k16_abi::syscall::FILE_TYPE_REGULAR,
        selected_inode::INODE_STATE_DIRECTORY => k16_abi::syscall::FILE_TYPE_DIRECTORY,
        _ => return Err(StorageError::INVALID_FILESYSTEM),
    };
    Ok(crate::kfs::cache::CachedPathMetadata {
        file_type,
        size_bytes: block_io::scratch_u32(inode_offset + 0x08),
    })
}

unsafe fn push_u32_le<S: DirectoryListingSink>(
    sink: &mut S,
    value: u32,
) -> Result<(), StorageError> {
    unsafe {
        sink.push_byte((value & 0xff) as u8)?;
        sink.push_byte(((value >> 8) & 0xff) as u8)?;
        sink.push_byte(((value >> 16) & 0xff) as u8)?;
        sink.push_byte(((value >> 24) & 0xff) as u8)
    }
}

fn min_u32(a: u32, b: u32) -> u32 {
    if a < b {
        a
    } else {
        b
    }
}
