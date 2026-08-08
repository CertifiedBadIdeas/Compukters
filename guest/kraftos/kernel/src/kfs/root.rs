use crate::kfs::cache::{CachedName, CachedPathMetadata};
use crate::kfs::error::StorageError;
use crate::kfs::types::{DirectoryListingSink, FileMetadata};

impl crate::kfs::volume::KfsVolume {
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
            crate::kfs::selected_inode::select_inode_metadata_for_cache(self, directory_inode_id)?
        };
        if selected_metadata.file_type != k16_abi::syscall::FILE_TYPE_DIRECTORY {
            return Err(StorageError::PATH_NOT_FOUND);
        }
        self.cache
            .store_inode(directory_inode_id, selected_metadata);
        unsafe {
            crate::kfs::directory_listing::copy_selected_directory_listing_into_cached(self, sink)
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
            unsafe { crate::kfs::selected_inode::select_inode_metadata_for_cache(self, inode_id)? };
        if selected_metadata.file_type != k16_abi::syscall::FILE_TYPE_REGULAR {
            return Err(StorageError::PATH_NOT_FOUND);
        }
        self.cache.store_inode(inode_id, selected_metadata);
        Ok(self.selected_inode.file_metadata())
    }

    pub unsafe fn open_file_for_write(
        &mut self,
        partition_type: &[u8; 4],
        path: &[&[u8]],
        create: bool,
        truncate: bool,
    ) -> Result<FileMetadata, StorageError> {
        if self.read_only {
            return Err(StorageError::READ_ONLY);
        }
        unsafe { self.ensure_mounted(partition_type)? };
        let metadata = unsafe {
            crate::kfs::namespace_mutation::open_file_for_write(self, path, create, truncate)?
        };
        self.invalidate_all();
        Ok(metadata)
    }

    pub unsafe fn remove_file(
        &mut self,
        partition_type: &[u8; 4],
        path: &[&[u8]],
        source_inode_is_busy: impl FnOnce(u32) -> bool,
    ) -> Result<(), StorageError> {
        if self.read_only {
            return Err(StorageError::READ_ONLY);
        }
        unsafe { self.ensure_mounted(partition_type)? };
        unsafe { crate::kfs::namespace_mutation::remove_file(self, path, source_inode_is_busy)? };
        self.invalidate_all();
        Ok(())
    }

    pub unsafe fn rename_file(
        &mut self,
        partition_type: &[u8; 4],
        old_path: &[&[u8]],
        new_path: &[&[u8]],
        source_inode_is_busy: impl FnOnce(u32) -> bool,
    ) -> Result<(), StorageError> {
        if self.read_only {
            return Err(StorageError::READ_ONLY);
        }
        unsafe { self.ensure_mounted(partition_type)? };
        unsafe {
            crate::kfs::namespace_mutation::rename_file(
                self,
                old_path,
                new_path,
                source_inode_is_busy,
            )?
        };
        self.invalidate_all();
        Ok(())
    }

    pub unsafe fn create_directory(
        &mut self,
        partition_type: &[u8; 4],
        path: &[&[u8]],
    ) -> Result<(), StorageError> {
        if self.read_only {
            return Err(StorageError::READ_ONLY);
        }
        unsafe { self.ensure_mounted(partition_type)? };
        unsafe { crate::kfs::namespace_mutation::create_directory(self, path)? };
        self.invalidate_all();
        Ok(())
    }

    pub unsafe fn remove_directory(
        &mut self,
        partition_type: &[u8; 4],
        path: &[&[u8]],
    ) -> Result<(), StorageError> {
        if self.read_only {
            return Err(StorageError::READ_ONLY);
        }
        unsafe { self.ensure_mounted(partition_type)? };
        unsafe { crate::kfs::namespace_mutation::remove_directory(self, path)? };
        self.invalidate_all();
        Ok(())
    }

    unsafe fn ensure_mounted(&mut self, partition_type: &[u8; 4]) -> Result<(), StorageError> {
        if self.mounted.is_some() && self.mounted_partition_type == Some(*partition_type) {
            return Ok(());
        }
        let mounted =
            unsafe { crate::kfs::mount::mount_root_partition_superblock(self, partition_type)? };
        self.mounted_partition_type = Some(*partition_type);
        self.mounted = Some(mounted);
        Ok(())
    }

    unsafe fn resolve_path(
        &mut self,
        path: &[&[u8]],
    ) -> Result<(u32, CachedPathMetadata), StorageError> {
        crate::os_stats::record_path_lookup();
        let mut inode_id = self.filesystem.root_inode_id();
        let mut metadata = match self.cache.lookup_inode(inode_id) {
            Some(metadata) => metadata,
            None => {
                let metadata = unsafe {
                    crate::kfs::selected_inode::select_inode_metadata_for_cache(self, inode_id)?
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
                        crate::kfs::selected_inode::select_inode_metadata_for_cache(self, inode_id)?
                    };
                    let child_inode_id =
                        unsafe { selected_directory_entry_inode(self, component)? };
                    let child_metadata = unsafe {
                        crate::kfs::selected_inode::select_inode_metadata_for_cache(
                            self,
                            child_inode_id,
                        )?
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

pub unsafe fn select_boot_or_test_file_from_storage0(
    volume: &mut crate::kfs::volume::KfsVolume,
    partition_type: &[u8; 4],
    path: &[&[u8]],
) -> Result<(), StorageError> {
    unsafe { crate::kfs::mount::read_partition(volume, partition_type)? };
    unsafe { crate::kfs::mount::read_superblock(volume)? };
    unsafe { crate::kfs::path::find_file_inode(volume, path)? };
    Ok(())
}

unsafe fn selected_directory_entry_inode(
    volume: &mut crate::kfs::volume::KfsVolume,
    name: &[u8],
) -> Result<u32, StorageError> {
    unsafe { crate::kfs::path::find_directory_entry(volume, name) }
}
