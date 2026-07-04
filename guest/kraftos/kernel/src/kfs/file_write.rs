use crate::kfs::error::StorageError;
use crate::kfs::storage;
use crate::kfs::types::FileMetadata;

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
                let block_delta = within_extent / storage::BLOCK_SIZE;
                let block_offset = within_extent % storage::BLOCK_SIZE;
                let available = min_u32(
                    storage::BLOCK_SIZE - block_offset,
                    overlap.copy_end - cursor,
                );
                unsafe { storage::read_fs_block(overlap.extent_start_block + block_delta)? };
                unsafe {
                    storage::copy_ram_to_ram(
                        src_addr + copied,
                        storage::SCRATCH_ADDR + block_offset,
                        available,
                    )
                };
                unsafe { storage::write_fs_block(overlap.extent_start_block + block_delta)? };
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

unsafe fn grow_file_capacity(
    metadata: FileMetadata,
    required_size: u32,
) -> Result<FileMetadata, StorageError> {
    let plan = crate::kfs::file::plan_file_growth(metadata, required_size)?;
    let mut can_extend_last_extent = plan.grow_end <= unsafe { storage::superblock_total_blocks() };
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
            unsafe { storage::clear_scratch_block() };
            unsafe { storage::write_fs_block(block)? };
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
        unsafe { storage::clear_scratch_block() };
        unsafe { storage::write_fs_block(block)? };
        block += 1;
    }

    crate::kfs::file::apply_new_extent(metadata, new_extent_start, plan)
}

fn min_u32(left: u32, right: u32) -> u32 {
    if left < right {
        left
    } else {
        right
    }
}
