pub const BLOCK_SIZE_BYTES: usize = crate::kfs::storage::BLOCK_SIZE as usize;

pub type KfsBlockBytes = [u8; BLOCK_SIZE_BYTES];

#[derive(Clone, Copy)]
struct KfsBlockCacheSlot {
    lba: u32,
    bytes: KfsBlockBytes,
    dirty: bool,
    valid: bool,
    last_used: u32,
}

impl KfsBlockCacheSlot {
    const EMPTY: Self = Self {
        lba: 0,
        bytes: [0; BLOCK_SIZE_BYTES],
        dirty: false,
        valid: false,
        last_used: 0,
    };
}

pub struct KfsBlockCache<const SLOTS: usize> {
    slots: [KfsBlockCacheSlot; SLOTS],
    clock: u32,
}

impl<const SLOTS: usize> KfsBlockCache<SLOTS> {
    pub const fn new() -> Self {
        Self {
            slots: [KfsBlockCacheSlot::EMPTY; SLOTS],
            clock: 0,
        }
    }

    pub fn get(&mut self, lba: u32) -> Option<&KfsBlockBytes> {
        let slot = self.find_valid_slot(lba)?;
        let last_used = self.next_clock();
        self.slots[slot].last_used = last_used;
        Some(&self.slots[slot].bytes)
    }

    pub fn is_dirty(&self, lba: u32) -> Option<bool> {
        self.find_valid_slot(lba).map(|slot| self.slots[slot].dirty)
    }

    pub fn invalidate_all(&mut self) {
        let mut index = 0;
        while index < SLOTS {
            self.slots[index].valid = false;
            self.slots[index].dirty = false;
            index += 1;
        }
    }

    pub fn put_clean(&mut self, lba: u32, bytes: &KfsBlockBytes) {
        self.put(lba, bytes, false);
    }

    pub fn put_dirty(&mut self, lba: u32, bytes: &KfsBlockBytes) {
        self.put(lba, bytes, true);
    }

    fn put(&mut self, lba: u32, bytes: &KfsBlockBytes, dirty: bool) {
        let slot = match self.find_valid_slot(lba) {
            Some(slot) => slot,
            None => self.replacement_slot(),
        };
        self.slots[slot] = KfsBlockCacheSlot {
            lba,
            bytes: *bytes,
            dirty,
            valid: true,
            last_used: self.next_clock(),
        };
    }

    fn find_valid_slot(&self, lba: u32) -> Option<usize> {
        let mut index = 0;
        while index < SLOTS {
            let slot = self.slots[index];
            if slot.valid && slot.lba == lba {
                return Some(index);
            }
            index += 1;
        }
        None
    }

    fn replacement_slot(&self) -> usize {
        let mut index = 0;
        while index < SLOTS {
            if !self.slots[index].valid {
                return index;
            }
            index += 1;
        }

        let mut oldest_index = 0;
        let mut oldest_clock = self.slots[0].last_used;
        let mut used_index = 1;
        while used_index < SLOTS {
            let slot = self.slots[used_index];
            if slot.last_used < oldest_clock {
                oldest_clock = slot.last_used;
                oldest_index = used_index;
            }
            used_index += 1;
        }
        oldest_index
    }

    fn next_clock(&mut self) -> u32 {
        self.clock = self.clock.wrapping_add(1);
        if self.clock == 0 {
            self.renormalize_clock();
        }
        self.clock
    }

    fn renormalize_clock(&mut self) {
        let mut index = 0;
        while index < SLOTS {
            if self.slots[index].valid {
                self.slots[index].last_used = 1;
            }
            index += 1;
        }
        self.clock = 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn block_with(byte: u8) -> [u8; crate::kfs::storage::BLOCK_SIZE as usize] {
        [byte; crate::kfs::storage::BLOCK_SIZE as usize]
    }

    #[test]
    fn block_cache_returns_recently_inserted_block() {
        let mut cache = KfsBlockCache::<2>::new();
        let block = block_with(0x11);

        assert_eq!(cache.get(7), None);
        cache.put_clean(7, &block);

        assert_eq!(cache.get(7), Some(&block));
        assert_eq!(cache.is_dirty(7), Some(false));
    }

    #[test]
    fn block_cache_replaces_oldest_slot_when_full() {
        let mut cache = KfsBlockCache::<2>::new();
        let first = block_with(0x01);
        let second = block_with(0x02);
        let third = block_with(0x03);

        cache.put_clean(1, &first);
        cache.put_clean(2, &second);
        cache.put_clean(3, &third);

        assert_eq!(cache.get(1), None);
        assert_eq!(cache.get(2), Some(&second));
        assert_eq!(cache.get(3), Some(&third));
    }

    #[test]
    fn block_cache_updates_existing_lba_without_consuming_new_slot() {
        let mut cache = KfsBlockCache::<2>::new();
        let first = block_with(0x01);
        let replacement = block_with(0x12);
        let second = block_with(0x02);
        let third = block_with(0x03);

        cache.put_clean(1, &first);
        cache.put_dirty(1, &replacement);
        cache.put_clean(2, &second);
        cache.put_clean(3, &third);

        assert_eq!(cache.get(1), None);
        assert_eq!(cache.get(2), Some(&second));
        assert_eq!(cache.get(3), Some(&third));

        let mut cache = KfsBlockCache::<2>::new();
        cache.put_clean(1, &first);
        cache.put_dirty(1, &replacement);
        cache.put_clean(2, &second);

        assert_eq!(cache.get(1), Some(&replacement));
        assert_eq!(cache.is_dirty(1), Some(true));
    }

    #[test]
    fn block_cache_full_invalidation_removes_cached_blocks() {
        let mut cache = KfsBlockCache::<2>::new();
        let block = block_with(0x44);

        cache.put_dirty(1, &block);
        cache.invalidate_all();

        assert_eq!(cache.get(1), None);
        assert_eq!(cache.is_dirty(1), None);
    }
}
