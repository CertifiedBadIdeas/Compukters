pub const SCRATCH_ADDR: u32 = 0x0000_0600;
pub const BLOCK_SIZE: u32 = 512;

use crate::kfs::error::StorageError;
use crate::kfs::types::{FileMetadata, PathKind, PathMetadata, KFS_MAX_INLINE_EXTENTS};

const KFS_BLOCK_CACHE_SLOTS: usize = 16;

const STATE_PARTITION_START_LBA: u32 = 0x0000_0200;
const STATE_PARTITION_BLOCK_COUNT: u32 = 0x0000_0204;
const STATE_SUPERBLOCK_TOTAL_BLOCKS: u32 = 0x0000_0208;
const STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK: u32 = 0x0000_020c;
const STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT: u32 = 0x0000_0210;
const STATE_SUPERBLOCK_ROOT_INODE_ID: u32 = 0x0000_0214;
const STATE_INODE_STATE: u32 = 0x0000_0218;
const STATE_INODE_SIZE_BYTES: u32 = 0x0000_021c;
const STATE_INODE_EXTENT_COUNT: u32 = 0x0000_0220;
const STATE_INODE_EXTENT_START_BLOCKS: u32 = 0x0000_0224;
const STATE_INODE_EXTENT_BLOCK_COUNTS: u32 = 0x0000_0234;
const STATE_SUPERBLOCK_BITMAP_START_BLOCK: u32 = 0x0000_0244;
const STATE_SUPERBLOCK_BITMAP_BLOCK_COUNT: u32 = 0x0000_0248;
const STATE_SELECTED_INODE_ID: u32 = 0x0000_024c;
pub(crate) const INODE_STATE_REGULAR: u8 = 1;
pub(crate) const INODE_STATE_DIRECTORY: u8 = 2;

struct KernelKfsBlockCache {
    cache: core::cell::UnsafeCell<crate::kfs::block_cache::KfsBlockCache<KFS_BLOCK_CACHE_SLOTS>>,
}

unsafe impl Sync for KernelKfsBlockCache {}

impl KernelKfsBlockCache {
    const fn new() -> Self {
        Self {
            cache: core::cell::UnsafeCell::new(crate::kfs::block_cache::KfsBlockCache::new()),
        }
    }

    unsafe fn get(&self) -> &mut crate::kfs::block_cache::KfsBlockCache<KFS_BLOCK_CACHE_SLOTS> {
        unsafe { &mut *self.cache.get() }
    }
}

static KFS_BLOCK_CACHE: KernelKfsBlockCache = KernelKfsBlockCache::new();

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

pub(crate) unsafe fn selected_inode_state() -> u8 {
    unsafe { read_u32(STATE_INODE_STATE) as u8 }
}

pub(crate) unsafe fn selected_inode_size() -> u32 {
    unsafe { read_u32(STATE_INODE_SIZE_BYTES) }
}

pub(crate) unsafe fn selected_inode_extent_count() -> u32 {
    unsafe { read_u32(STATE_INODE_EXTENT_COUNT) }
}

pub(crate) unsafe fn selected_inode_extent_start_block(index: usize) -> u32 {
    unsafe { read_u32(STATE_INODE_EXTENT_START_BLOCKS + index as u32 * 4) }
}

pub(crate) unsafe fn selected_inode_extent_block_count(index: usize) -> u32 {
    unsafe { read_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + index as u32 * 4) }
}

pub(crate) unsafe fn selected_inode_id() -> u32 {
    unsafe { read_u32(STATE_SELECTED_INODE_ID) }
}

pub unsafe fn select_inode_metadata_for_cache(
    inode_id: u32,
) -> Result<crate::kfs::cache::CachedPathMetadata, StorageError> {
    unsafe { read_inode(inode_id)? };
    unsafe { selected_metadata_for_cache() }
}

pub unsafe fn selected_directory_entry_inode(name: &[u8]) -> Result<u32, StorageError> {
    unsafe { crate::kfs::path::find_directory_entry(name) }
}

pub unsafe fn select_directory_inode(path: &[&[u8]]) -> Result<u32, StorageError> {
    unsafe { crate::kfs::path::find_directory_inode(path)? };
    Ok(unsafe { read_u32(STATE_SELECTED_INODE_ID) })
}

pub unsafe fn selected_metadata_for_cache(
) -> Result<crate::kfs::cache::CachedPathMetadata, StorageError> {
    let metadata = unsafe { selected_path_metadata()? };
    Ok(crate::kfs::cache::CachedPathMetadata {
        file_type: metadata.kind as u32,
        size_bytes: metadata.size_bytes,
    })
}

pub unsafe fn stat_path_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
) -> Result<PathMetadata, StorageError> {
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };
    unsafe { crate::kfs::path::find_path_inode(path)? };
    unsafe { selected_path_metadata() }
}

pub unsafe fn copy_ram_to_file_range(
    metadata: FileMetadata,
    file_offset: u32,
    src_addr: u32,
    len: u32,
) -> Result<FileMetadata, StorageError> {
    let range = crate::kfs::file::validate_write_range(file_offset, len)?;
    let range_end = range.end;
    let mut updated = metadata;
    if range_end > crate::kfs::file::file_capacity_bytes(updated)? {
        updated = unsafe { grow_file_capacity(updated, range_end)? };
    }

    let mut copied = 0;
    let mut extent_file_start: u32 = 0;
    let mut extent_index = 0;
    while extent_index < updated.extent_count as usize && copied < len {
        let extent_start_block = updated.extent_start_blocks[extent_index];
        let extent_block_count = updated.extent_block_counts[extent_index];
        let extent_overlap = crate::kfs::file::extent_overlap(
            file_offset,
            range_end,
            extent_file_start,
            extent_start_block,
            extent_block_count,
        )?;
        if let Some(overlap) = extent_overlap {
            let mut cursor = overlap.copy_start;
            while cursor < overlap.copy_end {
                let within_extent = cursor - overlap.extent_file_start;
                let block_delta = within_extent / BLOCK_SIZE;
                let block_offset = within_extent % BLOCK_SIZE;
                let available = min_u32(BLOCK_SIZE - block_offset, overlap.copy_end - cursor);
                unsafe { read_fs_block(overlap.extent_start_block + block_delta)? };
                unsafe {
                    copy_ram_to_ram(src_addr + copied, SCRATCH_ADDR + block_offset, available)
                };
                unsafe { write_fs_block(overlap.extent_start_block + block_delta)? };
                copied += available;
                cursor += available;
            }
            extent_file_start = overlap.extent_file_end;
        } else {
            extent_file_start =
                crate::kfs::file::extent_file_end(extent_file_start, extent_block_count)?;
        }
        extent_index += 1;
    }

    if copied != len {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    if range_end > updated.size_bytes {
        updated.size_bytes = range_end;
    }
    unsafe { crate::kfs::inode_mutation::encode_file_inode(updated)? };
    Ok(updated)
}

pub unsafe fn selected_file_size() -> u32 {
    unsafe { read_u32(STATE_INODE_SIZE_BYTES) }
}

pub unsafe fn selected_path_metadata() -> Result<PathMetadata, StorageError> {
    let kind = match unsafe { read_u32(STATE_INODE_STATE) as u8 } {
        1 => PathKind::Regular,
        2 => PathKind::Directory,
        _ => return Err(StorageError::INVALID_FILESYSTEM),
    };
    Ok(PathMetadata {
        kind,
        size_bytes: unsafe { selected_file_size() },
    })
}

pub unsafe fn selected_file_metadata() -> FileMetadata {
    let mut extent_start_blocks = [0; KFS_MAX_INLINE_EXTENTS];
    let mut extent_block_counts = [0; KFS_MAX_INLINE_EXTENTS];
    let extent_count = unsafe { read_u32(STATE_INODE_EXTENT_COUNT) };
    let mut index = 0;
    while index < KFS_MAX_INLINE_EXTENTS {
        extent_start_blocks[index] =
            unsafe { read_u32(STATE_INODE_EXTENT_START_BLOCKS + index as u32 * 4) };
        extent_block_counts[index] =
            unsafe { read_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + index as u32 * 4) };
        index += 1;
    }
    FileMetadata {
        inode_id: unsafe { read_u32(STATE_SELECTED_INODE_ID) },
        size_bytes: unsafe { selected_file_size() },
        extent_count,
        extent_start_blocks,
        extent_block_counts,
    }
}

pub unsafe fn select_file_metadata(metadata: FileMetadata) -> Result<(), StorageError> {
    unsafe { read_inode(metadata.inode_id)? };
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 1 {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    if unsafe { selected_file_metadata() } != metadata {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(())
}

pub(crate) unsafe fn read_partition(
    partition_type: &[u8; 4],
) -> Result<crate::kfs::partition::KfsPartition, StorageError> {
    unsafe { read_storage_block(0)? };
    let block = unsafe { scratch_block_bytes() };
    let capacity_low = unsafe { crate::kfs::device::capacity_blocks_u32()? };
    let partition = crate::kfs::partition::KfsPartition::decode_from_k16pt(
        &block,
        partition_type,
        capacity_low,
    )?;
    let old_start_lba = unsafe { read_u32(STATE_PARTITION_START_LBA) };
    let old_block_count = unsafe { read_u32(STATE_PARTITION_BLOCK_COUNT) };
    if old_start_lba != partition.start_lba || old_block_count != partition.block_count {
        unsafe { invalidate_block_cache() };
    }
    unsafe {
        write_u32(STATE_PARTITION_START_LBA, partition.start_lba);
        write_u32(STATE_PARTITION_BLOCK_COUNT, partition.block_count);
    }
    Ok(partition)
}

pub(crate) unsafe fn read_superblock() -> Result<crate::kfs::superblock::KfsSuperblock, StorageError>
{
    unsafe { read_fs_block(0)? };
    let block = unsafe { scratch_block_bytes() };
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
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2 {
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
    unsafe { read_fs_block(inode_block)? };

    let size_high = scratch_u32(inode_offset + 0x0c);
    let extent_count = scratch_u8(inode_offset + 0x10) as usize;
    if size_high != 0 || extent_count > KFS_MAX_INLINE_EXTENTS {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    unsafe {
        write_u32(STATE_SELECTED_INODE_ID, inode_id);
        write_u32(STATE_INODE_STATE, scratch_u8(inode_offset) as u32);
        write_u32(STATE_INODE_SIZE_BYTES, scratch_u32(inode_offset + 0x08));
        write_u32(STATE_INODE_EXTENT_COUNT, extent_count as u32);
    }
    let mut index = 0;
    while index < extent_count {
        let offset = inode_offset + 0x20 + index as u32 * 8;
        let start_block = scratch_u32(offset);
        let block_count = scratch_u32(offset + 4);
        validate_extent(start_block, block_count, unsafe {
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
        })?;
        unsafe {
            write_u32(
                STATE_INODE_EXTENT_START_BLOCKS + index as u32 * 4,
                start_block,
            );
            write_u32(
                STATE_INODE_EXTENT_BLOCK_COUNTS + index as u32 * 4,
                block_count,
            );
        }
        index += 1;
    }

    Ok(())
}

unsafe fn grow_file_capacity(
    metadata: FileMetadata,
    required_size: u32,
) -> Result<FileMetadata, StorageError> {
    let plan = crate::kfs::file::plan_file_growth(metadata, required_size)?;
    let mut can_extend_last_extent =
        plan.grow_end <= unsafe { read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS) };
    let mut block = plan.grow_start;
    while can_extend_last_extent && block < plan.grow_end {
        if unsafe { crate::kfs::allocation::is_block_allocated(block)? } {
            can_extend_last_extent = false;
        } else {
            block += 1;
        }
    }

    if can_extend_last_extent {
        block = plan.grow_start;
        while block < plan.grow_end {
            unsafe { crate::kfs::allocation::mark_block_allocated(block)? };
            unsafe { clear_scratch_block() };
            unsafe { write_fs_block(block)? };
            block += 1;
        }

        return crate::kfs::file::apply_extended_last_extent(metadata, plan);
    }

    let new_extent_start =
        unsafe { crate::kfs::allocation::allocate_contiguous_blocks(plan.additional_blocks)? };
    block = new_extent_start;
    let new_extent_end = match new_extent_start.checked_add(plan.additional_blocks) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    while block < new_extent_end {
        unsafe { clear_scratch_block() };
        unsafe { write_fs_block(block)? };
        block += 1;
    }

    crate::kfs::file::apply_new_extent(metadata, new_extent_start, plan)
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

#[inline(always)]
pub(crate) unsafe fn read_fs_block(block: u32) -> Result<(), StorageError> {
    if block >= unsafe { read_u32(STATE_PARTITION_BLOCK_COUNT) } {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    if unsafe { read_fs_block_from_cache(block) } {
        return Ok(());
    }
    let lba = match unsafe { read_u32(STATE_PARTITION_START_LBA) }.checked_add(block) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    unsafe { read_storage_block(lba)? };
    unsafe { store_scratch_block_in_cache(block) };
    Ok(())
}

#[inline(always)]
pub(crate) unsafe fn read_fs_blocks_to_ram(
    start_block: u32,
    block_count: u32,
    dst_addr: u32,
) -> Result<(), StorageError> {
    if block_count == 0 {
        return Ok(());
    }
    let end_block = match start_block.checked_add(block_count) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    if end_block > unsafe { read_u32(STATE_PARTITION_BLOCK_COUNT) } {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    unsafe { read_fs_blocks_to_ram_cached(start_block, block_count, dst_addr) }
}

#[inline(always)]
pub(crate) unsafe fn write_fs_block(block: u32) -> Result<(), StorageError> {
    if block >= unsafe { read_u32(STATE_PARTITION_BLOCK_COUNT) } {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let lba = match unsafe { read_u32(STATE_PARTITION_START_LBA) }.checked_add(block) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    unsafe { write_storage_block(lba)? };
    unsafe { store_scratch_block_in_cache(block) };
    Ok(())
}

#[inline(always)]
unsafe fn read_storage_block(lba: u32) -> Result<(), StorageError> {
    unsafe { crate::kfs::device::read_storage_block_to_scratch(lba) }
}

#[inline(always)]
unsafe fn read_storage_blocks_to_ram(
    lba: u32,
    block_count: u32,
    dst_addr: u32,
) -> Result<(), StorageError> {
    unsafe { crate::kfs::device::read_storage_blocks_to_ram(lba, block_count, dst_addr) }
}

#[inline(always)]
unsafe fn write_storage_block(lba: u32) -> Result<(), StorageError> {
    unsafe { crate::kfs::device::write_scratch_block_to_storage(lba) }
}

pub(crate) unsafe fn clear_scratch_block() {
    let mut offset = 0;
    while offset < BLOCK_SIZE {
        unsafe { write_u8(SCRATCH_ADDR + offset, 0) };
        offset += 1;
    }
}

unsafe fn read_fs_blocks_to_ram_cached(
    start_block: u32,
    block_count: u32,
    dst_addr: u32,
) -> Result<(), StorageError> {
    let mut cursor = 0;
    while cursor < block_count {
        let block = start_block + cursor;
        let dst_cursor = match cursor
            .checked_mul(BLOCK_SIZE)
            .and_then(|offset| dst_addr.checked_add(offset))
        {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        if unsafe { copy_cached_block_to_ram(block, dst_cursor) } {
            crate::os_stats::record_block_cache_hit();
            cursor += 1;
            continue;
        }

        crate::os_stats::record_block_cache_miss();
        let mut miss_count = 1;
        while cursor + miss_count < block_count {
            let miss_block = start_block + cursor + miss_count;
            if unsafe { is_fs_block_cached(miss_block) } {
                break;
            }
            crate::os_stats::record_block_cache_miss();
            miss_count += 1;
        }

        let lba = match unsafe { read_u32(STATE_PARTITION_START_LBA) }.checked_add(block) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        unsafe { read_storage_blocks_to_ram(lba, miss_count, dst_cursor)? };
        crate::os_stats::record_block_cache_batch_read();
        unsafe { store_ram_blocks_in_cache(block, miss_count, dst_cursor)? };
        cursor += miss_count;
    }
    Ok(())
}

unsafe fn read_fs_block_from_cache(block: u32) -> bool {
    match unsafe { KFS_BLOCK_CACHE.get() }.get(block) {
        Some(bytes) => {
            unsafe { write_cached_block_to_scratch(bytes) };
            crate::os_stats::record_block_cache_hit();
            true
        }
        None => {
            crate::os_stats::record_block_cache_miss();
            false
        }
    }
}

unsafe fn store_scratch_block_in_cache(block: u32) {
    let bytes = unsafe { scratch_block_bytes() };
    unsafe { KFS_BLOCK_CACHE.get() }.put_clean(block, &bytes);
}

unsafe fn store_ram_blocks_in_cache(
    start_block: u32,
    block_count: u32,
    start_addr: u32,
) -> Result<(), StorageError> {
    let mut cursor = 0;
    while cursor < block_count {
        let block = start_block + cursor;
        let addr = match cursor
            .checked_mul(BLOCK_SIZE)
            .and_then(|offset| start_addr.checked_add(offset))
        {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        let bytes = unsafe { ram_block_bytes(addr) };
        unsafe { KFS_BLOCK_CACHE.get() }.put_clean(block, &bytes);
        cursor += 1;
    }
    Ok(())
}

unsafe fn is_fs_block_cached(block: u32) -> bool {
    unsafe { KFS_BLOCK_CACHE.get() }.contains(block)
}

unsafe fn invalidate_block_cache() {
    unsafe { KFS_BLOCK_CACHE.get() }.invalidate_all();
}

unsafe fn copy_cached_block_to_ram(block: u32, dst_addr: u32) -> bool {
    match unsafe { KFS_BLOCK_CACHE.get() }.get(block) {
        Some(bytes) => {
            unsafe { write_cached_block_to_ram(bytes, dst_addr) };
            true
        }
        None => false,
    }
}

unsafe fn write_cached_block_to_scratch(bytes: &crate::kfs::block_cache::KfsBlockBytes) {
    unsafe { write_cached_block_to_ram(bytes, SCRATCH_ADDR) };
}

unsafe fn write_cached_block_to_ram(bytes: &crate::kfs::block_cache::KfsBlockBytes, dst_addr: u32) {
    let mut offset = 0;
    while offset < BLOCK_SIZE {
        unsafe { write_u8(dst_addr + offset, bytes[offset as usize]) };
        offset += 1;
    }
}

pub(crate) fn scratch_bytes_eq(offset: u32, expected: &[u8]) -> bool {
    let mut index = 0;
    while index < expected.len() {
        if scratch_u8(offset + index as u32) != expected[index] {
            return false;
        }
        index += 1;
    }
    true
}

pub(crate) fn scratch_u8(offset: u32) -> u8 {
    unsafe { read_u8(SCRATCH_ADDR + offset) }
}

pub(crate) unsafe fn write_scratch_u8(offset: u32, value: u8) {
    unsafe { write_u8(SCRATCH_ADDR + offset, value) }
}

pub(crate) unsafe fn write_scratch_u32(offset: u32, value: u32) {
    unsafe { write_u32(SCRATCH_ADDR + offset, value) }
}

pub(crate) fn scratch_u32(offset: u32) -> u32 {
    unsafe { read_u32(SCRATCH_ADDR + offset) }
}

unsafe fn scratch_block_bytes() -> [u8; BLOCK_SIZE as usize] {
    unsafe { ram_block_bytes(SCRATCH_ADDR) }
}

unsafe fn ram_block_bytes(addr: u32) -> [u8; BLOCK_SIZE as usize] {
    let mut block = [0_u8; BLOCK_SIZE as usize];
    let mut index = 0;
    while index < BLOCK_SIZE {
        block[index as usize] = unsafe { read_u8(addr + index) };
        index += 1;
    }
    block
}

pub(crate) unsafe fn copy_ram_to_ram(src_addr: u32, dst_addr: u32, len: u32) {
    let mut offset = 0;
    while offset < len {
        let byte = unsafe { read_u8(src_addr + offset) };
        unsafe { write_u8(dst_addr + offset, byte) };
        offset += 1;
    }
}

fn min_u32(left: u32, right: u32) -> u32 {
    if left < right {
        left
    } else {
        right
    }
}

unsafe fn read_u32(address: u32) -> u32 {
    unsafe { core::ptr::read_volatile(address as usize as *const u32) }
}

unsafe fn read_u8(address: u32) -> u8 {
    unsafe { core::ptr::read_volatile(address as usize as *const u8) }
}

unsafe fn write_u32(address: u32, value: u32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u32, value) }
}

unsafe fn write_u8(address: u32, value: u8) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u8, value) }
}

#[cfg(test)]
mod tests {
    use super::*;

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
