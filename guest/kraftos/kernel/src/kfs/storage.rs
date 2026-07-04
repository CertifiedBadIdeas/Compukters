pub const SCRATCH_ADDR: u32 = 0x0000_0600;
pub const BLOCK_SIZE: u32 = 512;

use crate::kfs::directory::{
    KfsDirectoryEntryHeader, KFS_DIRECTORY_ENTRIES_PER_BLOCK, KFS_DIRECTORY_ENTRY_SIZE,
    KFS_MAX_NAME_BYTES,
};
use crate::kfs::error::StorageError;
use crate::kfs::types::{
    DirectoryListingSink, FileMetadata, FileReadProfileFile, FileReadProfileKind, PathKind,
    PathMetadata, RamDirectoryListingSink, KFS_MAX_INLINE_EXTENTS,
};

const KFS_BLOCK_CACHE_SLOTS: usize = 16;
const INVALID_CACHED_INODE_BLOCK: u32 = u32::MAX;

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

pub unsafe fn read_directory_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
    dst_addr: u32,
    len: u32,
) -> Result<u32, StorageError> {
    let mut sink = RamDirectoryListingSink::new(dst_addr, len);
    unsafe { read_directory_from_storage0_into(partition_type, path, &mut sink) }
}

pub unsafe fn read_directory_from_storage0_into<S: DirectoryListingSink>(
    partition_type: &[u8; 4],
    path: &[&[u8]],
    sink: &mut S,
) -> Result<u32, StorageError> {
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };
    unsafe { crate::kfs::path::find_directory_inode(path)? };
    unsafe { copy_selected_directory_listing_into(sink) }
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

pub unsafe fn copy_selected_directory_listing_into_cached<S: DirectoryListingSink>(
    sink: &mut S,
    cache: &mut crate::kfs::cache::KfsCache,
) -> Result<u32, StorageError> {
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2
        || !crate::kfs::directory::directory_size_is_aligned(unsafe {
            read_u32(STATE_INODE_SIZE_BYTES)
        })
    {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let directory = unsafe { selected_file_metadata() };
    if directory.extent_count as usize > KFS_MAX_INLINE_EXTENTS {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let mut remaining = directory.size_bytes;
    let mut extent_index = 0;
    while extent_index < directory.extent_count as usize {
        let extent_start_block = directory.extent_start_blocks[extent_index];
        let extent_block_count = directory.extent_block_counts[extent_index];
        validate_extent(extent_start_block, extent_block_count, unsafe {
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
        })?;
        let mut block_index = 0;
        while block_index < extent_block_count && remaining > 0 {
            let fs_block = extent_start_block + block_index;
            unsafe { read_fs_block(fs_block)? };
            crate::os_stats::record_read_dir_data_read(min_u32(BLOCK_SIZE, remaining));
            let mut entry_inode_ids = [0_u32; KFS_DIRECTORY_ENTRIES_PER_BLOCK];
            let mut entry_name_lengths = [0_u8; KFS_DIRECTORY_ENTRIES_PER_BLOCK];
            let mut entry_names = [[0_u8; KFS_MAX_NAME_BYTES]; KFS_DIRECTORY_ENTRIES_PER_BLOCK];
            let mut entry_count = 0_usize;
            let mut offset = 0;
            while offset < BLOCK_SIZE && remaining > 0 {
                crate::os_stats::record_dir_entry_scan();
                match crate::kfs::directory::decode_entry_header(
                    scratch_u8(offset),
                    scratch_u8(offset + 1),
                    scratch_u8(offset + 2),
                    scratch_u8(offset + 3),
                    scratch_u32(offset + 4),
                )? {
                    KfsDirectoryEntryHeader::Free | KfsDirectoryEntryHeader::Deleted => {}
                    KfsDirectoryEntryHeader::Live { inode_id, name_len } => {
                        entry_inode_ids[entry_count] = inode_id;
                        entry_name_lengths[entry_count] = name_len as u8;
                        let mut name_offset = 0;
                        while name_offset < name_len {
                            entry_names[entry_count][name_offset] =
                                scratch_u8(offset + 8 + name_offset as u32);
                            name_offset += 1;
                        }
                        entry_count += 1;
                    }
                }
                remaining -= KFS_DIRECTORY_ENTRY_SIZE;
                offset += KFS_DIRECTORY_ENTRY_SIZE;
            }

            let mut cached_inode_block = INVALID_CACHED_INODE_BLOCK;
            let mut entry_index = 0;
            while entry_index < entry_count {
                let inode_id = entry_inode_ids[entry_index];
                let child = match cache.lookup_inode(inode_id) {
                    Some(metadata) => metadata,
                    None => {
                        let metadata = unsafe {
                            read_inode_path_metadata_cached(inode_id, &mut cached_inode_block)?
                        };
                        cache.store_inode(inode_id, metadata);
                        metadata
                    }
                };
                let name_len = entry_name_lengths[entry_index] as usize;
                unsafe {
                    push_directory_entry(
                        sink,
                        child.file_type,
                        &entry_names[entry_index][..name_len],
                        child.size_bytes,
                    )?;
                }
                entry_index += 1;
            }
            block_index += 1;
        }
        extent_index += 1;
    }

    if remaining != 0 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(sink.written())
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

pub unsafe fn open_file_for_write_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
    create: bool,
    truncate: bool,
) -> Result<FileMetadata, StorageError> {
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };
    match unsafe { crate::kfs::path::find_file_inode(path) } {
        Ok(()) => {
            if truncate {
                unsafe { truncate_selected_file()? };
            }
            Ok(unsafe { selected_file_metadata() })
        }
        Err(error) if error == StorageError::PATH_NOT_FOUND && create => unsafe {
            create_empty_file(path)
        },
        Err(error) => Err(error),
    }
}

pub unsafe fn remove_file_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
) -> Result<(), StorageError> {
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };
    let parent_len = path.len() - 1;
    unsafe { crate::kfs::path::find_directory_inode(&path[..parent_len])? };
    let slot = unsafe { crate::kfs::path::find_directory_entry_slot(path[parent_len])? };
    let inode_id = slot.inode_id;
    unsafe { read_inode(inode_id)? };
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 1 {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    let metadata = unsafe { selected_file_metadata() };
    let mut extent_index = 0;
    while extent_index < metadata.extent_count as usize {
        let start_block = metadata.extent_start_blocks[extent_index];
        let block_count = metadata.extent_block_counts[extent_index];
        validate_extent(start_block, block_count, unsafe {
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
        })?;
        let mut block = start_block;
        while block < start_block + block_count {
            unsafe { clear_scratch_block() };
            unsafe { write_fs_block(block)? };
            unsafe { crate::kfs::allocation::mark_block_free(block)? };
            block += 1;
        }
        extent_index += 1;
    }
    let deleted = FileMetadata {
        inode_id,
        size_bytes: 0,
        extent_count: 0,
        extent_start_blocks: [0; KFS_MAX_INLINE_EXTENTS],
        extent_block_counts: [0; KFS_MAX_INLINE_EXTENTS],
    };
    unsafe { encode_deleted_file_inode(deleted)? };
    unsafe { encode_deleted_directory_entry_at(slot.block, slot.offset) }
}

pub unsafe fn rename_file_from_storage0(
    partition_type: &[u8; 4],
    old_path: &[&[u8]],
    new_path: &[&[u8]],
    source_inode_is_busy: impl FnOnce(u32) -> bool,
) -> Result<(), StorageError> {
    if old_path.is_empty() || new_path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };

    let old_parent_len = old_path.len() - 1;
    unsafe { crate::kfs::path::find_directory_inode(&old_path[..old_parent_len])? };
    let old_slot =
        unsafe { crate::kfs::path::find_directory_entry_slot(old_path[old_parent_len])? };
    let inode_id = old_slot.inode_id;
    unsafe { read_inode(inode_id)? };
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 1 {
        return Err(StorageError::PATH_NOT_REGULAR);
    }
    if source_inode_is_busy(inode_id) {
        return Err(StorageError::PATH_BUSY);
    }

    let new_parent_len = new_path.len() - 1;
    let new_name = new_path[new_parent_len];
    unsafe { crate::kfs::path::find_directory_inode(&new_path[..new_parent_len])? };
    match unsafe { crate::kfs::path::find_directory_entry(new_name) } {
        Ok(_) => return Err(StorageError::PATH_EXISTS),
        Err(error) if error == StorageError::PATH_NOT_FOUND => {}
        Err(error) => return Err(error),
    }
    let new_slot = unsafe { crate::kfs::directory_mutation::find_selected_directory_free_slot()? };
    let new_parent_inode_id = unsafe { read_u32(STATE_SELECTED_INODE_ID) };

    unsafe { encode_directory_entry_at(new_slot.block, new_slot.offset, inode_id, new_name)? };
    unsafe { read_inode(new_parent_inode_id)? };
    let new_size = max_u32(
        unsafe { read_u32(STATE_INODE_SIZE_BYTES) },
        new_slot.directory_offset + KFS_DIRECTORY_ENTRY_SIZE,
    );
    unsafe { encode_selected_inode_size(new_parent_inode_id, new_size)? };
    unsafe { encode_deleted_directory_entry_at(old_slot.block, old_slot.offset) }
}

pub unsafe fn create_directory_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
) -> Result<(), StorageError> {
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };
    unsafe { create_empty_directory(path) }
}

pub unsafe fn remove_directory_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
) -> Result<(), StorageError> {
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };
    let parent_len = path.len() - 1;
    unsafe { crate::kfs::path::find_directory_inode(&path[..parent_len])? };
    let slot = unsafe { crate::kfs::path::find_directory_entry_slot(path[parent_len])? };
    let inode_id = slot.inode_id;
    unsafe { read_inode(inode_id)? };
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2 {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { ensure_selected_directory_is_empty()? };
    let metadata = unsafe { selected_file_metadata() };
    let mut extent_index = 0;
    while extent_index < metadata.extent_count as usize {
        let start_block = metadata.extent_start_blocks[extent_index];
        let block_count = metadata.extent_block_counts[extent_index];
        validate_extent(start_block, block_count, unsafe {
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
        })?;
        let mut block = start_block;
        while block < start_block + block_count {
            unsafe { clear_scratch_block() };
            unsafe { write_fs_block(block)? };
            unsafe { crate::kfs::allocation::mark_block_free(block)? };
            block += 1;
        }
        extent_index += 1;
    }
    unsafe { encode_deleted_directory_inode(metadata)? };
    unsafe { encode_deleted_directory_entry_at(slot.block, slot.offset) }
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
    unsafe { encode_file_inode(updated)? };
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

unsafe fn read_partition(
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

unsafe fn read_superblock() -> Result<crate::kfs::superblock::KfsSuperblock, StorageError> {
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

unsafe fn create_empty_file(path: &[&[u8]]) -> Result<FileMetadata, StorageError> {
    let parent_len = path.len() - 1;
    let name = path[parent_len];
    unsafe { crate::kfs::path::find_directory_inode(&path[..parent_len])? };
    match unsafe { crate::kfs::path::find_directory_entry(name) } {
        Ok(_) => return Err(StorageError::INVALID_FILESYSTEM),
        Err(error) if error == StorageError::PATH_NOT_FOUND => {}
        Err(error) => return Err(error),
    }
    let slot = unsafe { crate::kfs::directory_mutation::find_selected_directory_free_slot()? };
    let parent_inode_id = unsafe { read_u32(STATE_SELECTED_INODE_ID) };
    let inode_id = unsafe { crate::kfs::allocation::allocate_inode()? };
    let start_block = unsafe { crate::kfs::allocation::allocate_contiguous_blocks(1)? };
    unsafe { clear_scratch_block() };
    unsafe { write_fs_block(start_block)? };
    let mut extent_start_blocks = [0; KFS_MAX_INLINE_EXTENTS];
    let mut extent_block_counts = [0; KFS_MAX_INLINE_EXTENTS];
    extent_start_blocks[0] = start_block;
    extent_block_counts[0] = 1;
    let metadata = FileMetadata {
        inode_id,
        size_bytes: 0,
        extent_count: 1,
        extent_start_blocks,
        extent_block_counts,
    };
    unsafe { encode_file_inode(metadata)? };
    unsafe { encode_directory_entry_at(slot.block, slot.offset, inode_id, name)? };
    unsafe { read_inode(parent_inode_id)? };
    let new_size = max_u32(
        unsafe { read_u32(STATE_INODE_SIZE_BYTES) },
        slot.directory_offset + KFS_DIRECTORY_ENTRY_SIZE,
    );
    unsafe { encode_selected_inode_size(parent_inode_id, new_size)? };
    unsafe { read_inode(inode_id)? };
    Ok(unsafe { selected_file_metadata() })
}

unsafe fn create_empty_directory(path: &[&[u8]]) -> Result<(), StorageError> {
    let parent_len = path.len() - 1;
    let name = path[parent_len];
    unsafe { crate::kfs::path::find_directory_inode(&path[..parent_len])? };
    match unsafe { crate::kfs::path::find_directory_entry(name) } {
        Ok(_) => return Err(StorageError::INVALID_FILESYSTEM),
        Err(error) if error == StorageError::PATH_NOT_FOUND => {}
        Err(error) => return Err(error),
    }
    let slot = unsafe { crate::kfs::directory_mutation::find_selected_directory_free_slot()? };
    let parent_inode_id = unsafe { read_u32(STATE_SELECTED_INODE_ID) };
    let inode_id = unsafe { crate::kfs::allocation::allocate_inode()? };
    let start_block = unsafe { crate::kfs::allocation::allocate_contiguous_blocks(1)? };
    unsafe { clear_scratch_block() };
    unsafe { write_fs_block(start_block)? };
    let mut extent_start_blocks = [0; KFS_MAX_INLINE_EXTENTS];
    let mut extent_block_counts = [0; KFS_MAX_INLINE_EXTENTS];
    extent_start_blocks[0] = start_block;
    extent_block_counts[0] = 1;
    let metadata = FileMetadata {
        inode_id,
        size_bytes: 0,
        extent_count: 1,
        extent_start_blocks,
        extent_block_counts,
    };
    unsafe { encode_directory_inode(metadata)? };
    unsafe { encode_directory_entry_at(slot.block, slot.offset, inode_id, name)? };
    unsafe { read_inode(parent_inode_id)? };
    let new_size = max_u32(
        unsafe { read_u32(STATE_INODE_SIZE_BYTES) },
        slot.directory_offset + KFS_DIRECTORY_ENTRY_SIZE,
    );
    unsafe { encode_selected_inode_size(parent_inode_id, new_size) }
}

unsafe fn truncate_selected_file() -> Result<(), StorageError> {
    let mut metadata = unsafe { selected_file_metadata() };
    if metadata.extent_count == 0 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    metadata.size_bytes = 0;
    unsafe { encode_file_inode(metadata) }
}

pub unsafe fn copy_selected_directory_listing_to_ram(
    dst_addr: u32,
    len: u32,
) -> Result<u32, StorageError> {
    let mut sink = RamDirectoryListingSink::new(dst_addr, len);
    unsafe { copy_selected_directory_listing_into(&mut sink) }
}

pub unsafe fn copy_selected_directory_listing_into<S: DirectoryListingSink>(
    sink: &mut S,
) -> Result<u32, StorageError> {
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2
        || !crate::kfs::directory::directory_size_is_aligned(unsafe {
            read_u32(STATE_INODE_SIZE_BYTES)
        })
    {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let directory = unsafe { selected_file_metadata() };
    if directory.extent_count as usize > KFS_MAX_INLINE_EXTENTS {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let mut remaining = directory.size_bytes;
    let mut extent_index = 0;
    while extent_index < directory.extent_count as usize {
        let extent_start_block = directory.extent_start_blocks[extent_index];
        let extent_block_count = directory.extent_block_counts[extent_index];
        validate_extent(extent_start_block, extent_block_count, unsafe {
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
        })?;
        let mut block_index = 0;
        while block_index < extent_block_count && remaining > 0 {
            let fs_block = extent_start_block + block_index;
            let mut block_loaded = false;
            let mut offset = 0;
            while offset < BLOCK_SIZE && remaining > 0 {
                if !block_loaded {
                    unsafe { read_fs_block(fs_block)? };
                    block_loaded = true;
                }
                crate::os_stats::record_dir_entry_scan();
                match crate::kfs::directory::decode_entry_header(
                    scratch_u8(offset),
                    scratch_u8(offset + 1),
                    scratch_u8(offset + 2),
                    scratch_u8(offset + 3),
                    scratch_u32(offset + 4),
                )? {
                    KfsDirectoryEntryHeader::Free | KfsDirectoryEntryHeader::Deleted => {}
                    KfsDirectoryEntryHeader::Live { inode_id, name_len } => {
                        let mut name = [0_u8; KFS_MAX_NAME_BYTES];
                        let mut name_offset = 0;
                        while name_offset < name_len {
                            name[name_offset] = scratch_u8(offset + 8 + name_offset as u32);
                            name_offset += 1;
                        }
                        unsafe { read_inode(inode_id)? };
                        let child = unsafe { selected_path_metadata()? };
                        unsafe {
                            push_directory_entry(
                                sink,
                                child.kind as u32,
                                &name[..name_len],
                                child.size_bytes,
                            )?;
                        }
                        block_loaded = false;
                    }
                }
                remaining -= KFS_DIRECTORY_ENTRY_SIZE;
                offset += KFS_DIRECTORY_ENTRY_SIZE;
            }
            block_index += 1;
        }
        extent_index += 1;
    }

    if remaining != 0 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(sink.written())
}

unsafe fn push_directory_entry<S: DirectoryListingSink>(
    sink: &mut S,
    file_type: u32,
    name: &[u8],
    size_bytes: u32,
) -> Result<(), StorageError> {
    unsafe {
        push_u32_le(sink, file_type)?;
        push_u32_le(sink, name.len() as u32)?;
    }
    for byte in name {
        unsafe { sink.push_byte(*byte)? };
    }
    unsafe { push_u32_le(sink, size_bytes) }
}

unsafe fn read_inode_path_metadata_cached(
    inode_id: u32,
    cached_inode_block: &mut u32,
) -> Result<crate::kfs::cache::CachedPathMetadata, StorageError> {
    let location = crate::kfs::inode::locate_inode(
        inode_id,
        unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK) },
        unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT) },
    )?;
    let inode_block = location.block;
    let inode_offset = location.offset;
    if *cached_inode_block != inode_block {
        crate::os_stats::record_inode_load();
        unsafe { read_fs_block(inode_block)? };
        *cached_inode_block = inode_block;
    }
    let size_high = scratch_u32(inode_offset + 0x0c);
    let extent_count = scratch_u8(inode_offset + 0x10) as usize;
    if size_high != 0 || extent_count > KFS_MAX_INLINE_EXTENTS {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let mut index = 0;
    while index < extent_count {
        let offset = inode_offset + 0x20 + index as u32 * 8;
        let start_block = scratch_u32(offset);
        let block_count = scratch_u32(offset + 4);
        validate_extent(start_block, block_count, unsafe {
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
        })?;
        index += 1;
    }
    let file_type = match scratch_u8(inode_offset) {
        1 => k16_abi::syscall::FILE_TYPE_REGULAR,
        2 => k16_abi::syscall::FILE_TYPE_DIRECTORY,
        _ => return Err(StorageError::INVALID_FILESYSTEM),
    };
    Ok(crate::kfs::cache::CachedPathMetadata {
        file_type,
        size_bytes: scratch_u32(inode_offset + 0x08),
    })
}

unsafe fn push_u32_le<S: DirectoryListingSink>(
    sink: &mut S,
    value: u32,
) -> Result<(), StorageError> {
    unsafe {
        sink.push_byte((value & 0xff) as u8)?;
        sink.push_byte(((value >> 8) & 0xff) as u8)?;
        sink.push_byte(((value >> 16) & 0xff) as u8)?;
        sink.push_byte(((value >> 24) & 0xff) as u8)
    }
}

unsafe fn ensure_selected_directory_is_empty() -> Result<(), StorageError> {
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2
        || !crate::kfs::directory::directory_size_is_aligned(unsafe {
            read_u32(STATE_INODE_SIZE_BYTES)
        })
    {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let mut remaining = unsafe { read_u32(STATE_INODE_SIZE_BYTES) };
    let mut extent_index = 0;
    while extent_index < unsafe { read_u32(STATE_INODE_EXTENT_COUNT) as usize } {
        let extent_start_block =
            unsafe { read_u32(STATE_INODE_EXTENT_START_BLOCKS + extent_index as u32 * 4) };
        let extent_block_count =
            unsafe { read_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + extent_index as u32 * 4) };
        validate_extent(extent_start_block, extent_block_count, unsafe {
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
        })?;
        let mut block_index = 0;
        while block_index < extent_block_count {
            unsafe { read_fs_block(extent_start_block + block_index)? };
            let mut offset = 0;
            while offset < BLOCK_SIZE && remaining > 0 {
                match crate::kfs::directory::decode_entry_header(
                    scratch_u8(offset),
                    scratch_u8(offset + 1),
                    scratch_u8(offset + 2),
                    scratch_u8(offset + 3),
                    scratch_u32(offset + 4),
                )? {
                    KfsDirectoryEntryHeader::Free | KfsDirectoryEntryHeader::Deleted => {}
                    KfsDirectoryEntryHeader::Live { .. } => {
                        return Err(StorageError::PATH_NOT_EMPTY);
                    }
                }
                remaining -= KFS_DIRECTORY_ENTRY_SIZE;
                offset += KFS_DIRECTORY_ENTRY_SIZE;
            }
            block_index += 1;
        }
        extent_index += 1;
    }

    if remaining != 0 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(())
}

pub unsafe fn copy_selected_file_range_to_ram(
    file_offset: u32,
    dst_addr: u32,
    len: u32,
) -> Result<(), StorageError> {
    unsafe {
        copy_selected_file_range_to_ram_profiled(
            file_offset,
            dst_addr,
            len,
            FileReadProfileKind::GenericFile,
        )
    }
}

pub unsafe fn copy_selected_file_range_to_ram_profiled(
    file_offset: u32,
    dst_addr: u32,
    len: u32,
    profile_kind: FileReadProfileKind,
) -> Result<(), StorageError> {
    let range = crate::kfs::file::validate_read_range(
        unsafe { read_u32(STATE_INODE_SIZE_BYTES) },
        file_offset,
        len,
    )?;
    let range_end = range.end;

    let mut copied = 0;
    let mut extent_file_start: u32 = 0;
    let mut extent_index = 0;
    while extent_index < unsafe { read_u32(STATE_INODE_EXTENT_COUNT) as usize } && copied < len {
        let extent_start_block =
            unsafe { read_u32(STATE_INODE_EXTENT_START_BLOCKS + extent_index as u32 * 4) };
        let extent_block_count =
            unsafe { read_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + extent_index as u32 * 4) };
        let extent_overlap = crate::kfs::file::extent_overlap(
            file_offset,
            range_end,
            extent_file_start,
            extent_start_block,
            extent_block_count,
        )?;
        if let Some(overlap) = extent_overlap {
            unsafe {
                copy_extent_range_to_ram(
                    overlap.extent_start_block,
                    overlap.extent_file_start,
                    overlap.copy_start,
                    overlap.copy_end,
                    dst_addr,
                    &mut copied,
                    profile_kind,
                )?
            };
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
    Ok(())
}

pub unsafe fn copy_file_range_to_ram(
    metadata: FileMetadata,
    file_offset: u32,
    dst_addr: u32,
    len: u32,
) -> Result<(), StorageError> {
    unsafe {
        copy_file_range_to_ram_profiled(
            metadata,
            file_offset,
            dst_addr,
            len,
            FileReadProfileKind::GenericFile,
        )
    }
}

pub unsafe fn copy_file_range_to_ram_profiled(
    metadata: FileMetadata,
    file_offset: u32,
    dst_addr: u32,
    len: u32,
    profile_kind: FileReadProfileKind,
) -> Result<(), StorageError> {
    let range = crate::kfs::file::validate_read_range(metadata.size_bytes, file_offset, len)?;
    let range_end = range.end;

    let mut copied = 0;
    let mut extent_file_start: u32 = 0;
    let mut extent_index = 0;
    while extent_index < metadata.extent_count as usize && copied < len {
        let extent_start_block = metadata.extent_start_blocks[extent_index];
        let extent_block_count = metadata.extent_block_counts[extent_index];
        let extent_overlap = crate::kfs::file::extent_overlap(
            file_offset,
            range_end,
            extent_file_start,
            extent_start_block,
            extent_block_count,
        )?;
        if let Some(overlap) = extent_overlap {
            unsafe {
                copy_extent_range_to_ram(
                    overlap.extent_start_block,
                    overlap.extent_file_start,
                    overlap.copy_start,
                    overlap.copy_end,
                    dst_addr,
                    &mut copied,
                    profile_kind,
                )?
            };
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
    Ok(())
}

unsafe fn copy_extent_range_to_ram(
    extent_start_block: u32,
    extent_file_start: u32,
    copy_start: u32,
    copy_end: u32,
    dst_addr: u32,
    copied: &mut u32,
    profile_kind: FileReadProfileKind,
) -> Result<(), StorageError> {
    let mut cursor = copy_start;
    while cursor < copy_end {
        let within_extent = cursor - extent_file_start;
        let block_delta = within_extent / BLOCK_SIZE;
        let block_offset = within_extent % BLOCK_SIZE;
        if block_offset == 0 {
            let full_block_count = (copy_end - cursor) / BLOCK_SIZE;
            if full_block_count > 0 {
                let batch_bytes = match full_block_count.checked_mul(BLOCK_SIZE) {
                    Some(value) => value,
                    None => return Err(StorageError::INVALID_FILESYSTEM),
                };
                let block = match extent_start_block.checked_add(block_delta) {
                    Some(value) => value,
                    None => return Err(StorageError::INVALID_FILESYSTEM),
                };
                let dst = match dst_addr.checked_add(*copied) {
                    Some(value) => value,
                    None => return Err(StorageError::INVALID_FILESYSTEM),
                };
                unsafe { read_fs_blocks_to_ram(block, full_block_count, dst)? };
                record_profiled_file_data_read(profile_kind, batch_bytes);
                *copied = match (*copied).checked_add(batch_bytes) {
                    Some(value) => value,
                    None => return Err(StorageError::INVALID_FILESYSTEM),
                };
                cursor += batch_bytes;
                continue;
            }
        }

        let available = min_u32(BLOCK_SIZE - block_offset, copy_end - cursor);
        let block = match extent_start_block.checked_add(block_delta) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        unsafe { read_fs_block(block)? };
        record_profiled_file_data_read(profile_kind, available);
        let dst = match dst_addr.checked_add(*copied) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        unsafe {
            copy_ram_to_ram(SCRATCH_ADDR + block_offset, dst, available);
        }
        *copied = match (*copied).checked_add(available) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        cursor += available;
    }
    Ok(())
}

fn record_profiled_file_data_read(kind: FileReadProfileKind, bytes: u32) {
    match kind {
        FileReadProfileKind::GenericFile => crate::os_stats::record_generic_file_data_read(bytes),
        FileReadProfileKind::Program(file) => {
            crate::os_stats::record_program_data_read(bytes);
            record_profiled_file_path_data_read(file, bytes);
        }
        FileReadProfileKind::DynamicImport(file) => {
            record_profiled_file_path_data_read(file, bytes);
            crate::os_stats::record_dynamic_import_data_read(bytes)
        }
        FileReadProfileKind::Library(file) => {
            crate::os_stats::record_library_data_read(bytes);
            record_profiled_file_path_data_read(file, bytes);
        }
    }
}

fn record_profiled_file_path_data_read(file: FileReadProfileFile, bytes: u32) {
    match file {
        FileReadProfileFile::Generic => {}
        FileReadProfileFile::InitProgram => {
            crate::os_stats::record_init_program_file_data_read(bytes)
        }
        FileReadProfileFile::ShellProgram => {
            crate::os_stats::record_shell_program_file_data_read(bytes)
        }
        FileReadProfileFile::OtherProgram => {
            crate::os_stats::record_other_program_file_data_read(bytes)
        }
        FileReadProfileFile::LibkraftLibrary => {
            crate::os_stats::record_libkraft_library_file_data_read(bytes)
        }
        FileReadProfileFile::OtherLibrary => {
            crate::os_stats::record_other_library_file_data_read(bytes)
        }
    }
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

unsafe fn encode_file_inode(metadata: FileMetadata) -> Result<(), StorageError> {
    unsafe {
        encode_inode(
            metadata.inode_id,
            1,
            metadata.size_bytes,
            metadata.extent_count,
            &metadata.extent_start_blocks,
            &metadata.extent_block_counts,
        )
    }
}

pub(crate) unsafe fn encode_directory_inode(metadata: FileMetadata) -> Result<(), StorageError> {
    unsafe {
        encode_inode(
            metadata.inode_id,
            2,
            metadata.size_bytes,
            metadata.extent_count,
            &metadata.extent_start_blocks,
            &metadata.extent_block_counts,
        )
    }
}

unsafe fn encode_deleted_file_inode(metadata: FileMetadata) -> Result<(), StorageError> {
    unsafe {
        encode_inode(
            metadata.inode_id,
            3,
            metadata.size_bytes,
            metadata.extent_count,
            &metadata.extent_start_blocks,
            &metadata.extent_block_counts,
        )
    }
}

unsafe fn encode_deleted_directory_inode(metadata: FileMetadata) -> Result<(), StorageError> {
    unsafe {
        encode_inode(
            metadata.inode_id,
            3,
            0,
            0,
            &[0; KFS_MAX_INLINE_EXTENTS],
            &[0; KFS_MAX_INLINE_EXTENTS],
        )
    }
}

unsafe fn encode_selected_inode_size(inode_id: u32, size_bytes: u32) -> Result<(), StorageError> {
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
    unsafe {
        encode_inode(
            inode_id,
            read_u32(STATE_INODE_STATE) as u8,
            size_bytes,
            extent_count,
            &extent_start_blocks,
            &extent_block_counts,
        )
    }
}

unsafe fn encode_inode(
    inode_id: u32,
    state: u8,
    size_bytes: u32,
    extent_count: u32,
    extent_start_blocks: &[u32; KFS_MAX_INLINE_EXTENTS],
    extent_block_counts: &[u32; KFS_MAX_INLINE_EXTENTS],
) -> Result<(), StorageError> {
    if extent_count as usize > KFS_MAX_INLINE_EXTENTS {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let location = crate::kfs::inode::locate_inode(
        inode_id,
        unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK) },
        unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT) },
    )?;
    let inode_block = location.block;
    let inode_offset = location.offset;
    unsafe { read_fs_block(inode_block)? };
    let mut offset = 0;
    while offset < crate::kfs::inode::KFS_INODE_SIZE {
        unsafe { write_u8(SCRATCH_ADDR + inode_offset + offset, 0) };
        offset += 1;
    }
    unsafe {
        write_u8(SCRATCH_ADDR + inode_offset, state);
        write_u32(SCRATCH_ADDR + inode_offset + 0x08, size_bytes);
        write_u32(SCRATCH_ADDR + inode_offset + 0x0c, 0);
        write_u8(SCRATCH_ADDR + inode_offset + 0x10, extent_count as u8);
    }
    let mut index = 0;
    while index < extent_count as usize {
        let offset = SCRATCH_ADDR + inode_offset + 0x20 + index as u32 * 8;
        unsafe {
            write_u32(offset, extent_start_blocks[index]);
            write_u32(offset + 4, extent_block_counts[index]);
        }
        index += 1;
    }
    unsafe { write_fs_block(inode_block) }
}

unsafe fn encode_directory_entry_at(
    block: u32,
    offset: u32,
    inode_id: u32,
    name: &[u8],
) -> Result<(), StorageError> {
    let record = crate::kfs::directory::encode_entry(inode_id, name)?;
    unsafe { read_fs_block(block)? };
    let mut cursor = 0;
    while cursor < KFS_DIRECTORY_ENTRY_SIZE {
        unsafe {
            write_u8(
                SCRATCH_ADDR + offset + cursor,
                record.bytes[cursor as usize],
            )
        };
        cursor += 1;
    }
    unsafe { write_fs_block(block) }
}

unsafe fn encode_deleted_directory_entry_at(block: u32, offset: u32) -> Result<(), StorageError> {
    let record = crate::kfs::directory::encode_deleted_entry();
    unsafe { read_fs_block(block)? };
    let mut cursor = 0;
    while cursor < KFS_DIRECTORY_ENTRY_SIZE {
        unsafe {
            write_u8(
                SCRATCH_ADDR + offset + cursor,
                record.bytes[cursor as usize],
            )
        };
        cursor += 1;
    }
    unsafe { write_fs_block(block) }
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
unsafe fn read_fs_blocks_to_ram(
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

unsafe fn copy_ram_to_ram(src_addr: u32, dst_addr: u32, len: u32) {
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

fn max_u32(left: u32, right: u32) -> u32 {
    if left > right {
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
