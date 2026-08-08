use crate::kfs::error::StorageError;
use crate::kfs::types::{FileMetadata, KFS_MAX_INLINE_EXTENTS};
use crate::kfs::{block_io, selected_inode};

pub unsafe fn encode_file_inode(
    volume: &mut crate::kfs::volume::KfsVolume,
    metadata: FileMetadata,
) -> Result<(), StorageError> {
    unsafe {
        encode_inode(
            volume,
            metadata.inode_id,
            selected_inode::INODE_STATE_REGULAR,
            metadata.size_bytes,
            metadata.extent_count,
            &metadata.extent_start_blocks,
            &metadata.extent_block_counts,
        )
    }
}

pub unsafe fn encode_directory_inode(
    volume: &mut crate::kfs::volume::KfsVolume,
    metadata: FileMetadata,
) -> Result<(), StorageError> {
    unsafe {
        encode_inode(
            volume,
            metadata.inode_id,
            selected_inode::INODE_STATE_DIRECTORY,
            metadata.size_bytes,
            metadata.extent_count,
            &metadata.extent_start_blocks,
            &metadata.extent_block_counts,
        )
    }
}

pub unsafe fn encode_deleted_file_inode(
    volume: &mut crate::kfs::volume::KfsVolume,
    metadata: FileMetadata,
) -> Result<(), StorageError> {
    unsafe {
        encode_inode(
            volume,
            metadata.inode_id,
            3,
            metadata.size_bytes,
            metadata.extent_count,
            &metadata.extent_start_blocks,
            &metadata.extent_block_counts,
        )
    }
}

pub unsafe fn encode_deleted_directory_inode(
    volume: &mut crate::kfs::volume::KfsVolume,
    metadata: FileMetadata,
) -> Result<(), StorageError> {
    unsafe {
        encode_inode(
            volume,
            metadata.inode_id,
            3,
            0,
            0,
            &[0; KFS_MAX_INLINE_EXTENTS],
            &[0; KFS_MAX_INLINE_EXTENTS],
        )
    }
}

pub unsafe fn encode_selected_inode_size(
    volume: &mut crate::kfs::volume::KfsVolume,
    inode_id: u32,
    size_bytes: u32,
) -> Result<(), StorageError> {
    let metadata = volume.selected_inode.file_metadata();
    unsafe {
        encode_inode(
            volume,
            inode_id,
            volume.selected_inode.state(),
            size_bytes,
            metadata.extent_count,
            &metadata.extent_start_blocks,
            &metadata.extent_block_counts,
        )
    }
}

unsafe fn encode_inode(
    volume: &mut crate::kfs::volume::KfsVolume,
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
        volume.filesystem.superblock_inode_table_start_block(),
        volume.filesystem.superblock_inode_table_block_count(),
    )?;
    let inode_block = location.block;
    let inode_offset = location.offset;
    unsafe { block_io::read_fs_block(volume, inode_block)? };
    let mut offset = 0;
    while offset < crate::kfs::inode::KFS_INODE_SIZE {
        unsafe { block_io::write_scratch_u8(inode_offset + offset, 0) };
        offset += 1;
    }
    unsafe {
        block_io::write_scratch_u8(inode_offset, state);
        block_io::write_scratch_u32(inode_offset + 0x08, size_bytes);
        block_io::write_scratch_u32(inode_offset + 0x0c, 0);
        block_io::write_scratch_u8(inode_offset + 0x10, extent_count as u8);
    }
    let mut index = 0;
    while index < extent_count as usize {
        let offset = inode_offset + 0x20 + index as u32 * 8;
        unsafe {
            block_io::write_scratch_u32(offset, extent_start_blocks[index]);
            block_io::write_scratch_u32(offset + 4, extent_block_counts[index]);
        }
        index += 1;
    }
    unsafe { block_io::write_fs_block(volume, inode_block) }
}
