use crate::kfs::error::StorageError;
use crate::kfs::types::PathMetadata;

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
        crate::kfs::inode::load_inode(superblock.root_inode_id)?;
    }
    if unsafe { crate::kfs::selected_inode::selected_inode_state() }
        != crate::kfs::selected_inode::INODE_STATE_DIRECTORY
    {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(superblock)
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
