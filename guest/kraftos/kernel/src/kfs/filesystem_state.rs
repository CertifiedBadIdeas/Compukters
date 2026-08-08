use crate::kfs::partition::KfsPartition;
use crate::kfs::superblock::KfsSuperblock;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KfsFilesystemState {
    partition: KfsPartition,
    superblock: KfsSuperblock,
}

impl KfsFilesystemState {
    pub const fn new() -> Self {
        Self {
            partition: KfsPartition {
                start_lba: 0,
                block_count: 0,
            },
            superblock: KfsSuperblock {
                total_blocks: 0,
                bitmap_start_block: 0,
                bitmap_block_count: 0,
                inode_table_start_block: 0,
                inode_table_block_count: 0,
                root_inode_id: 0,
            },
        }
    }

    pub const fn partition_start_lba(&self) -> u32 {
        self.partition.start_lba
    }

    pub const fn partition_block_count(&self) -> u32 {
        self.partition.block_count
    }

    pub const fn superblock_total_blocks(&self) -> u32 {
        self.superblock.total_blocks
    }

    pub const fn superblock_bitmap_start_block(&self) -> u32 {
        self.superblock.bitmap_start_block
    }

    pub const fn superblock_bitmap_block_count(&self) -> u32 {
        self.superblock.bitmap_block_count
    }

    pub const fn superblock_inode_table_start_block(&self) -> u32 {
        self.superblock.inode_table_start_block
    }

    pub const fn superblock_inode_table_block_count(&self) -> u32 {
        self.superblock.inode_table_block_count
    }

    pub const fn root_inode_id(&self) -> u32 {
        self.superblock.root_inode_id
    }

    pub fn store_partition(&mut self, partition: KfsPartition) {
        self.partition = partition;
    }

    pub fn store_superblock(&mut self, superblock: KfsSuperblock) {
        self.superblock = superblock;
    }
}
