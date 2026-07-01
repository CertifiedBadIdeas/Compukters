use crate::kfs::storage::{StorageError, BLOCK_SIZE};

const KFS_MAGIC: &[u8; 5] = b"KFS\0\0";
const KFS_VERSION: u8 = 1;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KfsSuperblock {
    pub total_blocks: u32,
    pub bitmap_start_block: u32,
    pub bitmap_block_count: u32,
    pub inode_table_start_block: u32,
    pub inode_table_block_count: u32,
    pub root_inode_id: u32,
}

impl KfsSuperblock {
    pub fn decode(
        block: &[u8; BLOCK_SIZE as usize],
        partition_block_count: u32,
    ) -> Result<Self, StorageError> {
        if !bytes_eq(&block[0..5], KFS_MAGIC)
            || block[5] != KFS_VERSION
            || block[6] != 0
            || block[7] != 0
            || read_u32(block, 0x08) != BLOCK_SIZE
        {
            return Err(StorageError::INVALID_FILESYSTEM);
        }

        let total_blocks = read_u32(block, 0x0c);
        if total_blocks == 0 || total_blocks > partition_block_count {
            return Err(StorageError::INVALID_FILESYSTEM);
        }

        Ok(Self {
            total_blocks,
            bitmap_start_block: read_u32(block, 0x10),
            bitmap_block_count: read_u32(block, 0x14),
            inode_table_start_block: read_u32(block, 0x18),
            inode_table_block_count: read_u32(block, 0x1c),
            root_inode_id: read_u32(block, 0x20),
        })
    }
}

fn read_u32(bytes: &[u8; BLOCK_SIZE as usize], offset: usize) -> u32 {
    u32::from_le_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}

fn bytes_eq(left: &[u8], right: &[u8]) -> bool {
    if left.len() != right.len() {
        return false;
    }
    let mut index = 0;
    while index < left.len() {
        if left[index] != right[index] {
            return false;
        }
        index += 1;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write_u32(
        block: &mut [u8; crate::kfs::storage::BLOCK_SIZE as usize],
        offset: usize,
        value: u32,
    ) {
        block[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
    }

    fn superblock(total_blocks: u32) -> [u8; crate::kfs::storage::BLOCK_SIZE as usize] {
        let mut block = [0_u8; crate::kfs::storage::BLOCK_SIZE as usize];
        block[0..5].copy_from_slice(b"KFS\0\0");
        block[5] = 1;
        write_u32(&mut block, 0x08, crate::kfs::storage::BLOCK_SIZE);
        write_u32(&mut block, 0x0c, total_blocks);
        write_u32(&mut block, 0x10, 1);
        write_u32(&mut block, 0x14, 1);
        write_u32(&mut block, 0x18, 2);
        write_u32(&mut block, 0x1c, 2);
        write_u32(&mut block, 0x20, 1);
        block
    }

    #[test]
    fn superblock_decode_reads_kfs_v1_layout() {
        let block = superblock(32);

        assert_eq!(
            KfsSuperblock::decode(&block, 40),
            Ok(KfsSuperblock {
                total_blocks: 32,
                bitmap_start_block: 1,
                bitmap_block_count: 1,
                inode_table_start_block: 2,
                inode_table_block_count: 2,
                root_inode_id: 1,
            }),
        );
    }

    #[test]
    fn superblock_decode_rejects_invalid_magic() {
        let mut block = superblock(32);
        block[0] = b'X';

        assert_eq!(
            KfsSuperblock::decode(&block, 40),
            Err(crate::kfs::storage::StorageError::INVALID_FILESYSTEM),
        );
    }

    #[test]
    fn superblock_decode_rejects_invalid_block_size() {
        let mut block = superblock(32);
        write_u32(&mut block, 0x08, 1024);

        assert_eq!(
            KfsSuperblock::decode(&block, 40),
            Err(crate::kfs::storage::StorageError::INVALID_FILESYSTEM),
        );
    }
}
