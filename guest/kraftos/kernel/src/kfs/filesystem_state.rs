const STATE_PARTITION_START_LBA: u32 = 0x0000_0200;
const STATE_PARTITION_BLOCK_COUNT: u32 = 0x0000_0204;
const STATE_SUPERBLOCK_TOTAL_BLOCKS: u32 = 0x0000_0208;
const STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK: u32 = 0x0000_020c;
const STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT: u32 = 0x0000_0210;
const STATE_SUPERBLOCK_ROOT_INODE_ID: u32 = 0x0000_0214;
const STATE_SUPERBLOCK_BITMAP_START_BLOCK: u32 = 0x0000_0244;
const STATE_SUPERBLOCK_BITMAP_BLOCK_COUNT: u32 = 0x0000_0248;

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

pub(crate) unsafe fn root_inode_id() -> u32 {
    unsafe { read_u32(STATE_SUPERBLOCK_ROOT_INODE_ID) }
}

pub(crate) unsafe fn store_partition(partition: crate::kfs::partition::KfsPartition) {
    unsafe {
        write_u32(STATE_PARTITION_START_LBA, partition.start_lba);
        write_u32(STATE_PARTITION_BLOCK_COUNT, partition.block_count);
    }
}

pub(crate) unsafe fn store_superblock(superblock: crate::kfs::superblock::KfsSuperblock) {
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
    }
}

unsafe fn read_u32(address: u32) -> u32 {
    unsafe { core::ptr::read_volatile(address as usize as *const u32) }
}

unsafe fn write_u32(address: u32, value: u32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u32, value) }
}
