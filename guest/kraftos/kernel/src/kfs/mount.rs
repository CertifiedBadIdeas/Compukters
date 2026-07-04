use crate::kfs::error::StorageError;
use crate::kfs::partition::KfsPartition;
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

pub unsafe fn read_root_partition_superblock(partition_type: &[u8; 4]) -> Result<(), StorageError> {
    unsafe { mount_root_partition_superblock(partition_type).map(|_| ()) }
}

pub unsafe fn mount_root_partition_superblock(
    partition_type: &[u8; 4],
) -> Result<MountedKfs, StorageError> {
    let partition = unsafe { read_partition(partition_type)? };
    let superblock = unsafe { read_superblock()? };
    MountedKfs::new(partition, superblock)
}

pub(crate) unsafe fn read_partition(
    partition_type: &[u8; 4],
) -> Result<KfsPartition, StorageError> {
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

pub(crate) unsafe fn read_superblock() -> Result<KfsSuperblock, StorageError> {
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
