use crate::kfs::partition::KfsPartition;
use crate::kfs::storage::StorageError;
use crate::kfs::superblock::KfsSuperblock;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MountedKfs {
    pub partition: KfsPartition,
    pub superblock: KfsSuperblock,
}

impl MountedKfs {
    pub fn new(partition: KfsPartition, superblock: KfsSuperblock) -> Result<Self, StorageError> {
        if superblock.total_blocks == 0 || superblock.total_blocks > partition.block_count {
            return Err(StorageError::INVALID_FILESYSTEM);
        }
        Ok(Self {
            partition,
            superblock,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mounted_kfs_keeps_root_partition_and_superblock_state() {
        let partition = crate::kfs::partition::KfsPartition {
            start_lba: 3,
            block_count: 40,
        };
        let superblock = crate::kfs::superblock::KfsSuperblock {
            total_blocks: 32,
            bitmap_start_block: 1,
            bitmap_block_count: 1,
            inode_table_start_block: 2,
            inode_table_block_count: 2,
            root_inode_id: 1,
        };

        assert_eq!(
            MountedKfs::new(partition, superblock),
            Ok(MountedKfs {
                partition,
                superblock,
            }),
        );
    }
}
