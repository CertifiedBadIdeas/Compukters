use crate::kfs::block_io::BLOCK_SIZE;
use crate::kfs::error::StorageError;

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
