use crate::kfs::block_io::BLOCK_SIZE;
use crate::kfs::error::StorageError;
use crate::kfs::types::KFS_MAX_INLINE_EXTENTS;

pub const KFS_INODE_SIZE: u32 = 64;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KfsInodeLocation {
    pub block: u32,
    pub offset: u32,
}

pub fn inode_capacity(inode_table_block_count: u32) -> Result<u32, StorageError> {
    let inodes_per_block = BLOCK_SIZE / KFS_INODE_SIZE;
    match inode_table_block_count.checked_mul(inodes_per_block) {
        Some(value) => Ok(value),
        None => Err(StorageError::INVALID_FILESYSTEM),
    }
}

pub fn locate_inode(
    inode_id: u32,
    inode_table_start_block: u32,
    inode_table_block_count: u32,
) -> Result<KfsInodeLocation, StorageError> {
    let capacity = inode_capacity(inode_table_block_count)?;
    if inode_id >= capacity {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let inodes_per_block = BLOCK_SIZE / KFS_INODE_SIZE;
    let block = match inode_table_start_block.checked_add(inode_id / inodes_per_block) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    let offset = (inode_id % inodes_per_block) * KFS_INODE_SIZE;
    Ok(KfsInodeLocation { block, offset })
}

#[inline(always)]
pub(crate) unsafe fn load_inode(inode_id: u32) -> Result<(), StorageError> {
    crate::os_stats::record_inode_load();
    let location = locate_inode(
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
        crate::kfs::file::validate_extent(start_block, block_count, unsafe {
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inode_capacity_scales_with_inode_table_blocks() {
        assert_eq!(inode_capacity(0), Ok(0));
        assert_eq!(inode_capacity(1), Ok(8));
        assert_eq!(inode_capacity(3), Ok(24));
    }

    #[test]
    fn locate_inode_maps_inode_to_table_block_and_offset() {
        assert_eq!(
            locate_inode(0, 10, 2),
            Ok(KfsInodeLocation {
                block: 10,
                offset: 0,
            }),
        );
        assert_eq!(
            locate_inode(7, 10, 2),
            Ok(KfsInodeLocation {
                block: 10,
                offset: 448,
            }),
        );
        assert_eq!(
            locate_inode(8, 10, 2),
            Ok(KfsInodeLocation {
                block: 11,
                offset: 0,
            }),
        );
    }

    #[test]
    fn locate_inode_rejects_out_of_capacity_and_overflow() {
        assert_eq!(
            locate_inode(16, 10, 2),
            Err(StorageError::INVALID_FILESYSTEM),
        );
        assert_eq!(
            locate_inode(8, u32::MAX, 2),
            Err(StorageError::INVALID_FILESYSTEM),
        );
        assert_eq!(
            inode_capacity(u32::MAX),
            Err(StorageError::INVALID_FILESYSTEM),
        );
    }
}
