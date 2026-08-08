use crate::kfs::block_cache::KfsBlockCache;
use crate::kfs::block_io::KFS_BLOCK_CACHE_SLOTS;
use crate::kfs::cache::KfsCache;
use crate::kfs::device::KfsDevice;
use crate::kfs::filesystem_state::KfsFilesystemState;
use crate::kfs::mount::MountedKfs;
use crate::kfs::selected_inode::SelectedInodeState;

pub struct KfsVolume {
    pub(crate) device: KfsDevice,
    pub(crate) read_only: bool,
    pub(crate) mounted_partition_type: Option<[u8; 4]>,
    pub(crate) mounted: Option<MountedKfs>,
    pub(crate) filesystem: KfsFilesystemState,
    pub(crate) selected_inode: SelectedInodeState,
    pub(crate) cache: KfsCache,
    pub(crate) block_cache: KfsBlockCache<KFS_BLOCK_CACHE_SLOTS>,
}

impl KfsVolume {
    pub const fn new(device: KfsDevice, read_only: bool) -> Self {
        Self {
            device,
            read_only,
            mounted_partition_type: None,
            mounted: None,
            filesystem: KfsFilesystemState::new(),
            selected_inode: SelectedInodeState::new(),
            cache: KfsCache::new(),
            block_cache: KfsBlockCache::new(),
        }
    }

    pub const fn is_read_only(&self) -> bool {
        self.read_only
    }

    #[cfg(test)]
    fn test_store_selected_inode(&mut self, inode_id: u32, size_bytes: u32) {
        self.selected_inode.store_loaded_inode(
            inode_id,
            crate::kfs::selected_inode::INODE_STATE_REGULAR,
            size_bytes,
            0,
            &[0; crate::kfs::types::KFS_MAX_INLINE_EXTENTS],
            &[0; crate::kfs::types::KFS_MAX_INLINE_EXTENTS],
        );
    }

    #[cfg(test)]
    fn test_selected_inode_size(&self) -> u32 {
        self.selected_inode.size()
    }

    #[cfg(test)]
    fn test_put_clean_block(
        &mut self,
        block: u32,
        bytes: &[u8; crate::kfs::block_io::BLOCK_SIZE as usize],
    ) {
        self.block_cache.put_clean(block, bytes);
    }

    #[cfg(test)]
    fn test_cached_block(
        &mut self,
        block: u32,
    ) -> Option<[u8; crate::kfs::block_io::BLOCK_SIZE as usize]> {
        self.block_cache.get(block).copied()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kfs::device::KfsDevice;

    #[test]
    fn volumes_keep_mount_inode_and_cache_state_independent() {
        let mut root = KfsVolume::new(KfsDevice::storage0(), false);
        let mut sdk = KfsVolume::new(KfsDevice::storage1(), true);
        root.test_store_selected_inode(7, 111);
        sdk.test_store_selected_inode(7, 222);
        root.test_put_clean_block(3, &[0x11; 512]);
        sdk.test_put_clean_block(3, &[0x22; 512]);

        assert_eq!(root.test_selected_inode_size(), 111);
        assert_eq!(sdk.test_selected_inode_size(), 222);
        assert_eq!(root.test_cached_block(3), Some([0x11; 512]));
        assert_eq!(sdk.test_cached_block(3), Some([0x22; 512]));
    }
}
