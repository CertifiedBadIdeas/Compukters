use crate::kfs::cache::{CachedName, CachedPathMetadata, KfsCache};
use crate::kfs::error::StorageError;
use crate::kfs::types::{DirectoryListingSink, FileMetadata};

pub struct KfsRootFs {
    cache: KfsCache,
    mounted_partition_type: Option<[u8; 4]>,
    mounted: Option<crate::kfs::mount::MountedKfs>,
}

impl KfsRootFs {
    pub const fn new() -> Self {
        Self {
            cache: KfsCache::new(),
            mounted_partition_type: None,
            mounted: None,
        }
    }

    pub fn invalidate_all(&mut self) {
        self.cache.invalidate_all();
    }

    pub unsafe fn stat_path(
        &mut self,
        partition_type: &[u8; 4],
        path: &[&[u8]],
    ) -> Result<CachedPathMetadata, StorageError> {
        unsafe { self.ensure_mounted(partition_type)? };
        let (_, metadata) = unsafe { self.resolve_path(path)? };
        Ok(metadata)
    }

    pub unsafe fn read_directory_into<S: DirectoryListingSink>(
        &mut self,
        partition_type: &[u8; 4],
        path: &[&[u8]],
        sink: &mut S,
    ) -> Result<u32, StorageError> {
        unsafe { self.ensure_mounted(partition_type)? };
        let (directory_inode_id, metadata) = unsafe { self.resolve_path(path)? };
        if metadata.file_type != k16_abi::syscall::FILE_TYPE_DIRECTORY {
            return Err(StorageError::PATH_NOT_FOUND);
        }
        let selected_metadata = unsafe {
            crate::kfs::selected_inode::select_inode_metadata_for_cache(directory_inode_id)?
        };
        if selected_metadata.file_type != k16_abi::syscall::FILE_TYPE_DIRECTORY {
            return Err(StorageError::PATH_NOT_FOUND);
        }
        self.cache
            .store_inode(directory_inode_id, selected_metadata);
        unsafe {
            crate::kfs::directory_listing::copy_selected_directory_listing_into_cached(
                sink,
                &mut self.cache,
            )
        }
    }

    pub unsafe fn open_file(
        &mut self,
        partition_type: &[u8; 4],
        path: &[&[u8]],
    ) -> Result<FileMetadata, StorageError> {
        unsafe { self.ensure_mounted(partition_type)? };
        let (inode_id, metadata) = unsafe { self.resolve_path(path)? };
        if metadata.file_type != k16_abi::syscall::FILE_TYPE_REGULAR {
            return Err(StorageError::PATH_NOT_FOUND);
        }
        let selected_metadata =
            unsafe { crate::kfs::selected_inode::select_inode_metadata_for_cache(inode_id)? };
        if selected_metadata.file_type != k16_abi::syscall::FILE_TYPE_REGULAR {
            return Err(StorageError::PATH_NOT_FOUND);
        }
        self.cache.store_inode(inode_id, selected_metadata);
        Ok(unsafe { crate::kfs::selected_inode::selected_file_metadata() })
    }

    unsafe fn ensure_mounted(&mut self, partition_type: &[u8; 4]) -> Result<(), StorageError> {
        if self.mounted.is_some() && self.mounted_partition_type == Some(*partition_type) {
            return Ok(());
        }
        let mounted =
            unsafe { crate::kfs::storage::mount_root_partition_superblock(partition_type)? };
        self.mounted_partition_type = Some(*partition_type);
        self.mounted = Some(mounted);
        Ok(())
    }

    unsafe fn resolve_path(
        &mut self,
        path: &[&[u8]],
    ) -> Result<(u32, CachedPathMetadata), StorageError> {
        crate::os_stats::record_path_lookup();
        let mut inode_id = unsafe { crate::kfs::storage::root_inode_id() };
        let mut metadata = match self.cache.lookup_inode(inode_id) {
            Some(metadata) => metadata,
            None => {
                let metadata = unsafe {
                    crate::kfs::selected_inode::select_inode_metadata_for_cache(inode_id)?
                };
                self.cache.store_inode(inode_id, metadata);
                metadata
            }
        };

        let mut index = 0;
        while index < path.len() {
            if metadata.file_type != k16_abi::syscall::FILE_TYPE_DIRECTORY {
                return Err(StorageError::PATH_NOT_FOUND);
            }
            let component = path[index];
            match self.cache.lookup_directory(inode_id, component) {
                Some(lookup) => {
                    inode_id = lookup.inode_id;
                    metadata = lookup.metadata;
                }
                None => {
                    unsafe {
                        crate::kfs::selected_inode::select_inode_metadata_for_cache(inode_id)?
                    };
                    let child_inode_id =
                        unsafe { crate::kfs::storage::selected_directory_entry_inode(component)? };
                    let child_metadata = unsafe {
                        crate::kfs::selected_inode::select_inode_metadata_for_cache(child_inode_id)?
                    };
                    self.cache.store_inode(child_inode_id, child_metadata);
                    if let Some(name) = CachedName::from_bytes(component) {
                        self.cache.store_directory_lookup(
                            inode_id,
                            name,
                            child_inode_id,
                            child_metadata,
                        );
                    }
                    inode_id = child_inode_id;
                    metadata = child_metadata;
                }
            }
            index += 1;
        }

        Ok((inode_id, metadata))
    }
}
