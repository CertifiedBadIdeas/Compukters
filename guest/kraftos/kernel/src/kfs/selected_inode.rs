use crate::kfs::error::StorageError;
use crate::kfs::types::{FileMetadata, PathKind, PathMetadata, KFS_MAX_INLINE_EXTENTS};

pub(crate) const INODE_STATE_REGULAR: u8 = 1;
pub(crate) const INODE_STATE_DIRECTORY: u8 = 2;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SelectedInodeState {
    inode_id: u32,
    state: u8,
    size_bytes: u32,
    extent_count: u32,
    extent_start_blocks: [u32; KFS_MAX_INLINE_EXTENTS],
    extent_block_counts: [u32; KFS_MAX_INLINE_EXTENTS],
}

impl SelectedInodeState {
    pub const fn new() -> Self {
        Self {
            inode_id: 0,
            state: 0,
            size_bytes: 0,
            extent_count: 0,
            extent_start_blocks: [0; KFS_MAX_INLINE_EXTENTS],
            extent_block_counts: [0; KFS_MAX_INLINE_EXTENTS],
        }
    }

    pub const fn state(&self) -> u8 {
        self.state
    }

    pub const fn size(&self) -> u32 {
        self.size_bytes
    }

    pub const fn extent_count(&self) -> u32 {
        self.extent_count
    }

    pub const fn extent_start_block(&self, index: usize) -> u32 {
        self.extent_start_blocks[index]
    }

    pub const fn extent_block_count(&self, index: usize) -> u32 {
        self.extent_block_counts[index]
    }

    pub const fn inode_id(&self) -> u32 {
        self.inode_id
    }

    pub fn metadata_for_cache(
        &self,
    ) -> Result<crate::kfs::cache::CachedPathMetadata, StorageError> {
        let metadata = self.path_metadata()?;
        Ok(crate::kfs::cache::CachedPathMetadata {
            file_type: metadata.kind as u32,
            size_bytes: metadata.size_bytes,
        })
    }

    pub fn path_metadata(&self) -> Result<PathMetadata, StorageError> {
        let kind = match self.state {
            INODE_STATE_REGULAR => PathKind::Regular,
            INODE_STATE_DIRECTORY => PathKind::Directory,
            _ => return Err(StorageError::INVALID_FILESYSTEM),
        };
        Ok(PathMetadata {
            kind,
            size_bytes: self.size_bytes,
        })
    }

    pub fn file_metadata(&self) -> FileMetadata {
        FileMetadata {
            inode_id: self.inode_id,
            size_bytes: self.size_bytes,
            extent_count: self.extent_count,
            extent_start_blocks: self.extent_start_blocks,
            extent_block_counts: self.extent_block_counts,
        }
    }

    pub fn store_loaded_inode(
        &mut self,
        inode_id: u32,
        state: u8,
        size_bytes: u32,
        extent_count: usize,
        extent_start_blocks: &[u32; KFS_MAX_INLINE_EXTENTS],
        extent_block_counts: &[u32; KFS_MAX_INLINE_EXTENTS],
    ) {
        self.inode_id = inode_id;
        self.state = state;
        self.size_bytes = size_bytes;
        self.extent_count = extent_count as u32;
        self.extent_start_blocks = *extent_start_blocks;
        self.extent_block_counts = *extent_block_counts;
    }
}

pub unsafe fn select_inode_metadata_for_cache(
    volume: &mut crate::kfs::volume::KfsVolume,
    inode_id: u32,
) -> Result<crate::kfs::cache::CachedPathMetadata, StorageError> {
    unsafe { crate::kfs::inode::load_inode(volume, inode_id)? };
    volume.selected_inode.metadata_for_cache()
}

pub unsafe fn select_file_metadata(
    volume: &mut crate::kfs::volume::KfsVolume,
    metadata: FileMetadata,
) -> Result<(), StorageError> {
    unsafe { crate::kfs::inode::load_inode(volume, metadata.inode_id)? };
    if volume.selected_inode.state() != INODE_STATE_REGULAR {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    if volume.selected_inode.file_metadata() != metadata {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(())
}
