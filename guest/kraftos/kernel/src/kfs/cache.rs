const INODE_CACHE_SLOTS: usize = 32;
const DIRECTORY_CACHE_SLOTS: usize = 32;
const KFS_MAX_NAME_BYTES: usize = 56;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CachedPathMetadata {
    pub file_type: u32,
    pub size_bytes: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CachedDirectoryLookup {
    pub inode_id: u32,
    pub metadata: CachedPathMetadata,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CachedName {
    len: u8,
    bytes: [u8; KFS_MAX_NAME_BYTES],
}

impl CachedName {
    pub fn from_bytes(name: &[u8]) -> Option<Self> {
        if name.is_empty() || name.len() > KFS_MAX_NAME_BYTES {
            return None;
        }
        let mut bytes = [0_u8; KFS_MAX_NAME_BYTES];
        let mut index = 0;
        while index < name.len() {
            bytes[index] = name[index];
            index += 1;
        }
        Some(Self {
            len: name.len() as u8,
            bytes,
        })
    }

    fn matches(self, name: &[u8]) -> bool {
        if self.len as usize != name.len() {
            return false;
        }
        let mut index = 0;
        while index < name.len() {
            if self.bytes[index] != name[index] {
                return false;
            }
            index += 1;
        }
        true
    }
}

#[derive(Clone, Copy)]
struct InodeCacheEntry {
    inode_id: u32,
    metadata: CachedPathMetadata,
    valid: bool,
}

impl InodeCacheEntry {
    const EMPTY: Self = Self {
        inode_id: 0,
        metadata: CachedPathMetadata {
            file_type: 0,
            size_bytes: 0,
        },
        valid: false,
    };
}

#[derive(Clone, Copy)]
struct DirectoryCacheEntry {
    parent_inode_id: u32,
    name: CachedName,
    lookup: CachedDirectoryLookup,
    valid: bool,
}

impl DirectoryCacheEntry {
    const EMPTY: Self = Self {
        parent_inode_id: 0,
        name: CachedName {
            len: 0,
            bytes: [0; KFS_MAX_NAME_BYTES],
        },
        lookup: CachedDirectoryLookup {
            inode_id: 0,
            metadata: CachedPathMetadata {
                file_type: 0,
                size_bytes: 0,
            },
        },
        valid: false,
    };
}

pub struct KfsCache {
    inode_entries: [InodeCacheEntry; INODE_CACHE_SLOTS],
    directory_entries: [DirectoryCacheEntry; DIRECTORY_CACHE_SLOTS],
    next_inode_slot: usize,
    next_directory_slot: usize,
}

impl KfsCache {
    pub const fn new() -> Self {
        Self {
            inode_entries: [InodeCacheEntry::EMPTY; INODE_CACHE_SLOTS],
            directory_entries: [DirectoryCacheEntry::EMPTY; DIRECTORY_CACHE_SLOTS],
            next_inode_slot: 0,
            next_directory_slot: 0,
        }
    }

    pub fn lookup_inode(&self, inode_id: u32) -> Option<CachedPathMetadata> {
        let mut index = 0;
        while index < INODE_CACHE_SLOTS {
            let entry = self.inode_entries[index];
            if entry.valid && entry.inode_id == inode_id {
                return Some(entry.metadata);
            }
            index += 1;
        }
        None
    }

    pub fn store_inode(&mut self, inode_id: u32, metadata: CachedPathMetadata) {
        let slot = self.find_inode_slot(inode_id);
        self.inode_entries[slot] = InodeCacheEntry {
            inode_id,
            metadata,
            valid: true,
        };
    }

    pub fn invalidate_inode(&mut self, inode_id: u32) {
        let mut index = 0;
        while index < INODE_CACHE_SLOTS {
            if self.inode_entries[index].valid && self.inode_entries[index].inode_id == inode_id {
                self.inode_entries[index].valid = false;
            }
            index += 1;
        }
        let mut directory_index = 0;
        while directory_index < DIRECTORY_CACHE_SLOTS {
            if self.directory_entries[directory_index].valid
                && self.directory_entries[directory_index].lookup.inode_id == inode_id
            {
                self.directory_entries[directory_index].valid = false;
            }
            directory_index += 1;
        }
    }

    pub fn lookup_directory(
        &self,
        parent_inode_id: u32,
        name: &[u8],
    ) -> Option<CachedDirectoryLookup> {
        let mut index = 0;
        while index < DIRECTORY_CACHE_SLOTS {
            let entry = self.directory_entries[index];
            if entry.valid && entry.parent_inode_id == parent_inode_id && entry.name.matches(name) {
                return Some(entry.lookup);
            }
            index += 1;
        }
        None
    }

    pub fn store_directory_lookup(
        &mut self,
        parent_inode_id: u32,
        name: CachedName,
        inode_id: u32,
        metadata: CachedPathMetadata,
    ) {
        let slot = self.find_directory_slot(parent_inode_id, name);
        self.directory_entries[slot] = DirectoryCacheEntry {
            parent_inode_id,
            name,
            lookup: CachedDirectoryLookup { inode_id, metadata },
            valid: true,
        };
    }

    pub fn invalidate_directory_parent(&mut self, parent_inode_id: u32) {
        let mut index = 0;
        while index < DIRECTORY_CACHE_SLOTS {
            if self.directory_entries[index].valid
                && self.directory_entries[index].parent_inode_id == parent_inode_id
            {
                self.directory_entries[index].valid = false;
            }
            index += 1;
        }
    }

    pub fn invalidate_all(&mut self) {
        let mut index = 0;
        while index < INODE_CACHE_SLOTS {
            self.inode_entries[index].valid = false;
            index += 1;
        }
        let mut directory_index = 0;
        while directory_index < DIRECTORY_CACHE_SLOTS {
            self.directory_entries[directory_index].valid = false;
            directory_index += 1;
        }
    }

    fn find_inode_slot(&mut self, inode_id: u32) -> usize {
        let mut index = 0;
        while index < INODE_CACHE_SLOTS {
            if self.inode_entries[index].valid && self.inode_entries[index].inode_id == inode_id {
                return index;
            }
            index += 1;
        }
        let slot = self.next_inode_slot;
        self.next_inode_slot = (self.next_inode_slot + 1) % INODE_CACHE_SLOTS;
        slot
    }

    fn find_directory_slot(&mut self, parent_inode_id: u32, name: CachedName) -> usize {
        let mut index = 0;
        while index < DIRECTORY_CACHE_SLOTS {
            let entry = self.directory_entries[index];
            if entry.valid && entry.parent_inode_id == parent_inode_id && entry.name == name {
                return index;
            }
            index += 1;
        }
        let slot = self.next_directory_slot;
        self.next_directory_slot = (self.next_directory_slot + 1) % DIRECTORY_CACHE_SLOTS;
        slot
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inode_cache_reuses_metadata_by_inode_id() {
        let mut cache = KfsCache::new();
        let metadata = CachedPathMetadata {
            file_type: k16_abi::syscall::FILE_TYPE_REGULAR,
            size_bytes: 42,
        };

        assert_eq!(cache.lookup_inode(7), None);
        cache.store_inode(7, metadata);
        assert_eq!(cache.lookup_inode(7), Some(metadata));
    }

    #[test]
    fn directory_lookup_cache_invalidates_parent_entries() {
        let mut cache = KfsCache::new();
        let name = CachedName::from_bytes(b"ls.kx").expect("valid name");
        let metadata = CachedPathMetadata {
            file_type: k16_abi::syscall::FILE_TYPE_REGULAR,
            size_bytes: 12,
        };

        cache.store_directory_lookup(2, name, 9, metadata);
        assert_eq!(
            cache.lookup_directory(2, b"ls.kx"),
            Some(CachedDirectoryLookup {
                inode_id: 9,
                metadata,
            }),
        );

        cache.invalidate_directory_parent(2);
        assert_eq!(cache.lookup_directory(2, b"ls.kx"), None);
    }

    #[test]
    fn inode_invalidation_removes_inode_and_directory_lookup() {
        let mut cache = KfsCache::new();
        let name = CachedName::from_bytes(b"cat.kx").expect("valid name");
        let metadata = CachedPathMetadata {
            file_type: k16_abi::syscall::FILE_TYPE_REGULAR,
            size_bytes: 128,
        };

        cache.store_inode(11, metadata);
        cache.store_directory_lookup(2, name, 11, metadata);

        cache.invalidate_inode(11);

        assert_eq!(cache.lookup_inode(11), None);
        assert_eq!(cache.lookup_directory(2, b"cat.kx"), None);
    }

    #[test]
    fn full_invalidation_removes_all_cached_metadata() {
        let mut cache = KfsCache::new();
        let name = CachedName::from_bytes(b"bin").expect("valid name");
        let metadata = CachedPathMetadata {
            file_type: k16_abi::syscall::FILE_TYPE_DIRECTORY,
            size_bytes: 64,
        };

        cache.store_inode(1, metadata);
        cache.store_directory_lookup(0, name, 1, metadata);

        cache.invalidate_all();

        assert_eq!(cache.lookup_inode(1), None);
        assert_eq!(cache.lookup_directory(0, b"bin"), None);
    }
}
