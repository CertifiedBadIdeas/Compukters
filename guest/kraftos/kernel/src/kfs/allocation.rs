use crate::kfs::error::StorageError;
use crate::kfs::{block_io, selected_inode, storage};

pub unsafe fn allocate_inode() -> Result<u32, StorageError> {
    let inode_capacity = crate::kfs::inode::inode_capacity(unsafe {
        storage::superblock_inode_table_block_count()
    })?;
    let mut inode_id = 1;
    while inode_id < inode_capacity {
        unsafe { storage::read_inode(inode_id)? };
        match unsafe { selected_inode::selected_inode_state() } {
            0 | 3 => return Ok(inode_id),
            selected_inode::INODE_STATE_REGULAR | selected_inode::INODE_STATE_DIRECTORY => {}
            _ => return Err(StorageError::INVALID_FILESYSTEM),
        }
        inode_id += 1;
    }
    Err(StorageError::OUTPUT_BUFFER_TOO_SMALL)
}

fn selected_bitmap_layout() -> crate::kfs::bitmap::KfsBitmapLayout {
    crate::kfs::bitmap::KfsBitmapLayout {
        total_blocks: unsafe { storage::superblock_total_blocks() },
        bitmap_start_block: unsafe { storage::superblock_bitmap_start_block() },
        bitmap_block_count: unsafe { storage::superblock_bitmap_block_count() },
        inode_table_start_block: unsafe { storage::superblock_inode_table_start_block() },
        inode_table_block_count: unsafe { storage::superblock_inode_table_block_count() },
    }
}

pub unsafe fn allocate_contiguous_blocks(count: u32) -> Result<u32, StorageError> {
    if count == 0 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let layout = selected_bitmap_layout();
    let mut run_start = 0;
    let mut run_count = 0;
    let mut loaded_bitmap_block_index = u32::MAX;
    let mut block = 1;
    while block < layout.total_blocks {
        let location = crate::kfs::bitmap::locate_block(block, layout)?;
        if loaded_bitmap_block_index != location.bitmap_block_index {
            unsafe { block_io::read_fs_block(location.bitmap_block)? };
            loaded_bitmap_block_index = location.bitmap_block_index;
        }

        if crate::kfs::bitmap::byte_marks_allocated(
            block_io::scratch_u8(location.byte_offset),
            location,
        ) {
            run_start = 0;
            run_count = 0;
        } else {
            if run_count == 0 {
                run_start = block;
            }
            run_count += 1;
            if run_count == count {
                unsafe { mark_contiguous_blocks_allocated(run_start, count)? };
                return Ok(run_start);
            }
        }
        block += 1;
    }
    Err(StorageError::OUTPUT_BUFFER_TOO_SMALL)
}

unsafe fn mark_contiguous_blocks_allocated(
    start_block: u32,
    count: u32,
) -> Result<(), StorageError> {
    let end_block = match start_block.checked_add(count) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    let layout = selected_bitmap_layout();
    if count == 0 || end_block > layout.total_blocks {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let mut block = start_block;
    while block < end_block {
        let location = crate::kfs::bitmap::locate_block(block, layout)?;
        unsafe { block_io::read_fs_block(location.bitmap_block)? };

        let next_bitmap_block_start = match location.bitmap_block_index.checked_add(1) {
            Some(value) => match value.checked_mul(crate::kfs::bitmap::bits_per_bitmap_block()) {
                Some(next_start) => next_start,
                None => return Err(StorageError::INVALID_FILESYSTEM),
            },
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        let chunk_end = min_u32(end_block, next_bitmap_block_start);
        while block < chunk_end {
            let location = crate::kfs::bitmap::locate_block(block, layout)?;
            let value = crate::kfs::bitmap::mark_byte_allocated(
                block_io::scratch_u8(location.byte_offset),
                location,
            );
            unsafe { block_io::write_scratch_u8(location.byte_offset, value) };
            block += 1;
        }
        unsafe { block_io::write_fs_block(location.bitmap_block)? };
    }
    Ok(())
}

pub unsafe fn is_block_allocated(block: u32) -> Result<bool, StorageError> {
    let layout = selected_bitmap_layout();
    let location = crate::kfs::bitmap::locate_block(block, layout)?;
    unsafe { block_io::read_fs_block(location.bitmap_block)? };
    Ok(crate::kfs::bitmap::byte_marks_allocated(
        block_io::scratch_u8(location.byte_offset),
        location,
    ))
}

pub unsafe fn mark_block_allocated(block: u32) -> Result<(), StorageError> {
    let layout = selected_bitmap_layout();
    let location = crate::kfs::bitmap::locate_block(block, layout)?;
    unsafe { block_io::read_fs_block(location.bitmap_block)? };
    let value = crate::kfs::bitmap::mark_byte_allocated(
        block_io::scratch_u8(location.byte_offset),
        location,
    );
    unsafe { block_io::write_scratch_u8(location.byte_offset, value) };
    unsafe { block_io::write_fs_block(location.bitmap_block) }
}

pub unsafe fn mark_block_free(block: u32) -> Result<(), StorageError> {
    let layout = selected_bitmap_layout();
    if crate::kfs::bitmap::block_is_metadata(block, layout)? {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let location = crate::kfs::bitmap::locate_block(block, layout)?;
    unsafe { block_io::read_fs_block(location.bitmap_block)? };
    let value =
        crate::kfs::bitmap::mark_byte_free(block_io::scratch_u8(location.byte_offset), location);
    unsafe { block_io::write_scratch_u8(location.byte_offset, value) };
    unsafe { block_io::write_fs_block(location.bitmap_block) }
}

fn min_u32(a: u32, b: u32) -> u32 {
    if a < b {
        a
    } else {
        b
    }
}
