use crate::k16fs_cache::K16FsCache;

pub struct K16RootFs {
    cache: K16FsCache,
}

impl K16RootFs {
    pub const fn new() -> Self {
        Self {
            cache: K16FsCache::new(),
        }
    }

    pub fn invalidate_all(&mut self) {
        self.cache.invalidate_all();
    }
}
