use crate::kfs::error::StorageError;
use crate::kfs::types::{PathMetadata, KFS_MAX_INLINE_EXTENTS};

pub unsafe fn open_file_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
) -> Result<(), StorageError> {
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };
    unsafe { open_file_from_selected_filesystem(path) }
}

unsafe fn open_file_from_selected_filesystem(path: &[&[u8]]) -> Result<(), StorageError> {
    unsafe { crate::kfs::path::find_file_inode(path)? };
    Ok(())
}

pub unsafe fn read_root_partition_superblock(partition_type: &[u8; 4]) -> Result<(), StorageError> {
    unsafe { mount_root_partition_superblock(partition_type).map(|_| ()) }
}

pub unsafe fn mount_root_partition_superblock(
    partition_type: &[u8; 4],
) -> Result<crate::kfs::mount::MountedKfs, StorageError> {
    let partition = unsafe { read_partition(partition_type)? };
    let superblock = unsafe { read_superblock()? };
    crate::kfs::mount::MountedKfs::new(partition, superblock)
}

pub unsafe fn selected_directory_entry_inode(name: &[u8]) -> Result<u32, StorageError> {
    unsafe { crate::kfs::path::find_directory_entry(name) }
}

pub unsafe fn select_directory_inode(path: &[&[u8]]) -> Result<u32, StorageError> {
    unsafe { crate::kfs::path::find_directory_inode(path)? };
    Ok(unsafe { crate::kfs::selected_inode::selected_inode_id() })
}

pub unsafe fn stat_path_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
) -> Result<PathMetadata, StorageError> {
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };
    unsafe { crate::kfs::path::find_path_inode(path)? };
    unsafe { crate::kfs::selected_inode::selected_path_metadata() }
}

pub(crate) unsafe fn read_partition(
    partition_type: &[u8; 4],
) -> Result<crate::kfs::partition::KfsPartition, StorageError> {
    unsafe { crate::kfs::block_io::read_storage_block(0)? };
    let block = unsafe { crate::kfs::block_io::scratch_block_bytes() };
    let capacity_low = unsafe { crate::kfs::device::capacity_blocks_u32()? };
    let partition = crate::kfs::partition::KfsPartition::decode_from_k16pt(
        &block,
        partition_type,
        capacity_low,
    )?;
    let old_start_lba = unsafe { crate::kfs::filesystem_state::partition_start_lba() };
    let old_block_count = unsafe { crate::kfs::filesystem_state::partition_block_count() };
    if old_start_lba != partition.start_lba || old_block_count != partition.block_count {
        unsafe { crate::kfs::block_io::invalidate_block_cache() };
    }
    unsafe { crate::kfs::filesystem_state::store_partition(partition) };
    Ok(partition)
}

pub(crate) unsafe fn read_superblock() -> Result<crate::kfs::superblock::KfsSuperblock, StorageError>
{
    unsafe { crate::kfs::block_io::read_fs_block(0)? };
    let block = unsafe { crate::kfs::block_io::scratch_block_bytes() };
    let superblock = crate::kfs::superblock::KfsSuperblock::decode(&block, unsafe {
        crate::kfs::filesystem_state::partition_block_count()
    })?;
    unsafe {
        crate::kfs::filesystem_state::store_superblock(superblock);
        read_inode(superblock.root_inode_id)?;
    }
    if unsafe { crate::kfs::selected_inode::selected_inode_state() }
        != crate::kfs::selected_inode::INODE_STATE_DIRECTORY
    {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(superblock)
}

#[inline(always)]
pub(crate) unsafe fn read_inode(inode_id: u32) -> Result<(), StorageError> {
    crate::os_stats::record_inode_load();
    let location = crate::kfs::inode::locate_inode(
        inode_id,
        unsafe { crate::kfs::filesystem_state::superblock_inode_table_start_block() },
        unsafe { crate::kfs::filesystem_state::superblock_inode_table_block_count() },
    )?;
    let inode_block = location.block;
    let inode_offset = location.offset;
    unsafe { crate::kfs::block_io::read_fs_block(inode_block)? };

    let size_high = crate::kfs::block_io::scratch_u32(inode_offset + 0x0c);
    let extent_count = crate::kfs::block_io::scratch_u8(inode_offset + 0x10) as usize;
    if size_high != 0 || extent_count > KFS_MAX_INLINE_EXTENTS {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let state = crate::kfs::block_io::scratch_u8(inode_offset);
    let size_bytes = crate::kfs::block_io::scratch_u32(inode_offset + 0x08);
    let mut extent_start_blocks = [0; KFS_MAX_INLINE_EXTENTS];
    let mut extent_block_counts = [0; KFS_MAX_INLINE_EXTENTS];
    let mut index = 0;
    while index < extent_count {
        let offset = inode_offset + 0x20 + index as u32 * 8;
        let start_block = crate::kfs::block_io::scratch_u32(offset);
        let block_count = crate::kfs::block_io::scratch_u32(offset + 4);
        validate_extent(start_block, block_count, unsafe {
            crate::kfs::filesystem_state::superblock_total_blocks()
        })?;
        extent_start_blocks[index] = start_block;
        extent_block_counts[index] = block_count;
        index += 1;
    }

    unsafe {
        crate::kfs::selected_inode::store_loaded_inode(
            inode_id,
            state,
            size_bytes,
            extent_count,
            &extent_start_blocks,
            &extent_block_counts,
        )
    };

    Ok(())
}

pub unsafe fn flush_storage0() -> Result<(), StorageError> {
    unsafe { crate::kfs::device::flush_storage0() }
}

#[inline(always)]
pub(crate) fn validate_extent(
    start_block: u32,
    block_count: u32,
    total_blocks: u32,
) -> Result<(), StorageError> {
    let end = match start_block.checked_add(block_count) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    if block_count == 0 || end > total_blocks {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kfs::types::PathKind;

    #[test]
    fn storage_error_code_is_public_for_boot_chain_mapping() {
        assert_eq!(StorageError::STORAGE_VERSION.code(), 10);
        assert_eq!(StorageError::OUTPUT_BUFFER_TOO_SMALL.code(), 19);
        assert_eq!(StorageError::PATH_EXISTS.code(), 22);
        assert_eq!(StorageError::PATH_NOT_REGULAR.code(), 23);
        assert_eq!(StorageError::PATH_BUSY.code(), 24);
    }

    #[test]
    fn path_metadata_kind_values_are_stable_for_kernel_stat_abi() {
        assert_eq!(
            PathKind::Regular as u32,
            k16_abi::syscall::FILE_TYPE_REGULAR
        );
        assert_eq!(
            PathKind::Directory as u32,
            k16_abi::syscall::FILE_TYPE_DIRECTORY
        );

        let metadata = PathMetadata {
            kind: PathKind::Regular,
            size_bytes: 42,
        };

        assert_eq!(metadata.kind, PathKind::Regular);
        assert_eq!(metadata.size_bytes, 42);
    }
}
