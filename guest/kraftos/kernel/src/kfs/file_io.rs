use crate::kfs::error::StorageError;
use crate::kfs::types::{FileMetadata, FileReadProfileFile, FileReadProfileKind};
use crate::kfs::{block_io, selected_inode};

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
        unsafe { selected_inode::selected_inode_size() },
        file_offset,
        len,
    )?;
    let range_end = range.end;

    let mut copied = 0;
    let mut extent_file_start: u32 = 0;
    let mut extent_index = 0;
    while extent_index < unsafe { selected_inode::selected_inode_extent_count() as usize }
        && copied < len
    {
        let extent_start_block =
            unsafe { selected_inode::selected_inode_extent_start_block(extent_index) };
        let extent_block_count =
            unsafe { selected_inode::selected_inode_extent_block_count(extent_index) };
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
        let block_delta = within_extent / block_io::BLOCK_SIZE;
        let block_offset = within_extent % block_io::BLOCK_SIZE;
        if block_offset == 0 {
            let full_block_count = (copy_end - cursor) / block_io::BLOCK_SIZE;
            if full_block_count > 0 {
                let batch_bytes = match full_block_count.checked_mul(block_io::BLOCK_SIZE) {
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
                unsafe { block_io::read_fs_blocks_to_ram(block, full_block_count, dst)? };
                record_profiled_file_data_read(profile_kind, batch_bytes);
                *copied = match (*copied).checked_add(batch_bytes) {
                    Some(value) => value,
                    None => return Err(StorageError::INVALID_FILESYSTEM),
                };
                cursor += batch_bytes;
                continue;
            }
        }

        let available = min_u32(block_io::BLOCK_SIZE - block_offset, copy_end - cursor);
        let block = match extent_start_block.checked_add(block_delta) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        unsafe { block_io::read_fs_block(block)? };
        record_profiled_file_data_read(profile_kind, available);
        let dst = match dst_addr.checked_add(*copied) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        unsafe {
            block_io::copy_ram_to_ram(block_io::SCRATCH_ADDR + block_offset, dst, available);
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

fn min_u32(left: u32, right: u32) -> u32 {
    if left < right {
        left
    } else {
        right
    }
}
