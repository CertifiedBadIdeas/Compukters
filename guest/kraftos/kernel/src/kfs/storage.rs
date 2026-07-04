use crate::kfs::error::StorageError;
use crate::kfs::types::{PathMetadata, KFS_MAX_INLINE_EXTENTS};

const STATE_PARTITION_START_LBA: u32 = 0x0000_0200;
const STATE_PARTITION_BLOCK_COUNT: u32 = 0x0000_0204;
const STATE_SUPERBLOCK_TOTAL_BLOCKS: u32 = 0x0000_0208;
const STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK: u32 = 0x0000_020c;
const STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT: u32 = 0x0000_0210;
const STATE_SUPERBLOCK_ROOT_INODE_ID: u32 = 0x0000_0214;
const STATE_SUPERBLOCK_BITMAP_START_BLOCK: u32 = 0x0000_0244;
const STATE_SUPERBLOCK_BITMAP_BLOCK_COUNT: u32 = 0x0000_0248;

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

pub unsafe fn root_inode_id() -> u32 {
    unsafe { read_u32(STATE_SUPERBLOCK_ROOT_INODE_ID) }
}

pub(crate) unsafe fn partition_start_lba() -> u32 {
    unsafe { read_u32(STATE_PARTITION_START_LBA) }
}

pub(crate) unsafe fn partition_block_count() -> u32 {
    unsafe { read_u32(STATE_PARTITION_BLOCK_COUNT) }
}

pub(crate) unsafe fn superblock_total_blocks() -> u32 {
    unsafe { read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS) }
}

pub(crate) unsafe fn superblock_bitmap_start_block() -> u32 {
    unsafe { read_u32(STATE_SUPERBLOCK_BITMAP_START_BLOCK) }
}

pub(crate) unsafe fn superblock_bitmap_block_count() -> u32 {
    unsafe { read_u32(STATE_SUPERBLOCK_BITMAP_BLOCK_COUNT) }
}

pub(crate) unsafe fn superblock_inode_table_start_block() -> u32 {
    unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK) }
}

pub(crate) unsafe fn superblock_inode_table_block_count() -> u32 {
    unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT) }
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
    let old_start_lba = unsafe { read_u32(STATE_PARTITION_START_LBA) };
    let old_block_count = unsafe { read_u32(STATE_PARTITION_BLOCK_COUNT) };
    if old_start_lba != partition.start_lba || old_block_count != partition.block_count {
        unsafe { crate::kfs::block_io::invalidate_block_cache() };
    }
    unsafe {
        write_u32(STATE_PARTITION_START_LBA, partition.start_lba);
        write_u32(STATE_PARTITION_BLOCK_COUNT, partition.block_count);
    }
    Ok(partition)
}

pub(crate) unsafe fn read_superblock() -> Result<crate::kfs::superblock::KfsSuperblock, StorageError>
{
    unsafe { crate::kfs::block_io::read_fs_block(0)? };
    let block = unsafe { crate::kfs::block_io::scratch_block_bytes() };
    let superblock = crate::kfs::superblock::KfsSuperblock::decode(&block, unsafe {
        read_u32(STATE_PARTITION_BLOCK_COUNT)
    })?;
    unsafe {
        write_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS, superblock.total_blocks);
        write_u32(
            STATE_SUPERBLOCK_BITMAP_START_BLOCK,
            superblock.bitmap_start_block,
        );
        write_u32(
            STATE_SUPERBLOCK_BITMAP_BLOCK_COUNT,
            superblock.bitmap_block_count,
        );
        write_u32(
            STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK,
            superblock.inode_table_start_block,
        );
        write_u32(
            STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT,
            superblock.inode_table_block_count,
        );
        write_u32(STATE_SUPERBLOCK_ROOT_INODE_ID, superblock.root_inode_id);
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
        unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK) },
        unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT) },
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
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
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

unsafe fn read_u32(address: u32) -> u32 {
    unsafe { core::ptr::read_volatile(address as usize as *const u32) }
}

unsafe fn write_u32(address: u32, value: u32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u32, value) }
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
