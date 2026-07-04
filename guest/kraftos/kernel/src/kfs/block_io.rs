use crate::kfs::error::StorageError;

pub const SCRATCH_ADDR: u32 = 0x0000_0600;
pub const BLOCK_SIZE: u32 = 512;

const KFS_BLOCK_CACHE_SLOTS: usize = 16;

struct KernelKfsBlockCache {
    cache: core::cell::UnsafeCell<crate::kfs::block_cache::KfsBlockCache<KFS_BLOCK_CACHE_SLOTS>>,
}

unsafe impl Sync for KernelKfsBlockCache {}

impl KernelKfsBlockCache {
    const fn new() -> Self {
        Self {
            cache: core::cell::UnsafeCell::new(crate::kfs::block_cache::KfsBlockCache::new()),
        }
    }

    unsafe fn get(&self) -> &mut crate::kfs::block_cache::KfsBlockCache<KFS_BLOCK_CACHE_SLOTS> {
        unsafe { &mut *self.cache.get() }
    }
}

static KFS_BLOCK_CACHE: KernelKfsBlockCache = KernelKfsBlockCache::new();

#[inline(always)]
pub(crate) unsafe fn read_fs_block(block: u32) -> Result<(), StorageError> {
    if block >= unsafe { crate::kfs::storage::partition_block_count() } {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    if unsafe { read_fs_block_from_cache(block) } {
        return Ok(());
    }
    let lba = match unsafe { crate::kfs::storage::partition_start_lba() }.checked_add(block) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    unsafe { read_storage_block(lba)? };
    unsafe { store_scratch_block_in_cache(block) };
    Ok(())
}

#[inline(always)]
pub(crate) unsafe fn read_fs_blocks_to_ram(
    start_block: u32,
    block_count: u32,
    dst_addr: u32,
) -> Result<(), StorageError> {
    if block_count == 0 {
        return Ok(());
    }
    let end_block = match start_block.checked_add(block_count) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    if end_block > unsafe { crate::kfs::storage::partition_block_count() } {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    unsafe { read_fs_blocks_to_ram_cached(start_block, block_count, dst_addr) }
}

#[inline(always)]
pub(crate) unsafe fn write_fs_block(block: u32) -> Result<(), StorageError> {
    if block >= unsafe { crate::kfs::storage::partition_block_count() } {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let lba = match unsafe { crate::kfs::storage::partition_start_lba() }.checked_add(block) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    unsafe { write_storage_block(lba)? };
    unsafe { store_scratch_block_in_cache(block) };
    Ok(())
}

#[inline(always)]
pub(crate) unsafe fn read_storage_block(lba: u32) -> Result<(), StorageError> {
    unsafe { crate::kfs::device::read_storage_block_to_scratch(lba) }
}

#[inline(always)]
unsafe fn read_storage_blocks_to_ram(
    lba: u32,
    block_count: u32,
    dst_addr: u32,
) -> Result<(), StorageError> {
    unsafe { crate::kfs::device::read_storage_blocks_to_ram(lba, block_count, dst_addr) }
}

#[inline(always)]
unsafe fn write_storage_block(lba: u32) -> Result<(), StorageError> {
    unsafe { crate::kfs::device::write_scratch_block_to_storage(lba) }
}

pub(crate) unsafe fn clear_scratch_block() {
    let mut offset = 0;
    while offset < BLOCK_SIZE {
        unsafe { write_u8(SCRATCH_ADDR + offset, 0) };
        offset += 1;
    }
}

unsafe fn read_fs_blocks_to_ram_cached(
    start_block: u32,
    block_count: u32,
    dst_addr: u32,
) -> Result<(), StorageError> {
    let mut cursor = 0;
    while cursor < block_count {
        let block = start_block + cursor;
        let dst_cursor = match cursor
            .checked_mul(BLOCK_SIZE)
            .and_then(|offset| dst_addr.checked_add(offset))
        {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        if unsafe { copy_cached_block_to_ram(block, dst_cursor) } {
            crate::os_stats::record_block_cache_hit();
            cursor += 1;
            continue;
        }

        crate::os_stats::record_block_cache_miss();
        let mut miss_count = 1;
        while cursor + miss_count < block_count {
            let miss_block = start_block + cursor + miss_count;
            if unsafe { is_fs_block_cached(miss_block) } {
                break;
            }
            crate::os_stats::record_block_cache_miss();
            miss_count += 1;
        }

        let lba = match unsafe { crate::kfs::storage::partition_start_lba() }.checked_add(block) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        unsafe { read_storage_blocks_to_ram(lba, miss_count, dst_cursor)? };
        crate::os_stats::record_block_cache_batch_read();
        unsafe { store_ram_blocks_in_cache(block, miss_count, dst_cursor)? };
        cursor += miss_count;
    }
    Ok(())
}

unsafe fn read_fs_block_from_cache(block: u32) -> bool {
    match unsafe { KFS_BLOCK_CACHE.get() }.get(block) {
        Some(bytes) => {
            unsafe { write_cached_block_to_scratch(bytes) };
            crate::os_stats::record_block_cache_hit();
            true
        }
        None => {
            crate::os_stats::record_block_cache_miss();
            false
        }
    }
}

unsafe fn store_scratch_block_in_cache(block: u32) {
    let bytes = unsafe { scratch_block_bytes() };
    unsafe { KFS_BLOCK_CACHE.get() }.put_clean(block, &bytes);
}

unsafe fn store_ram_blocks_in_cache(
    start_block: u32,
    block_count: u32,
    start_addr: u32,
) -> Result<(), StorageError> {
    let mut cursor = 0;
    while cursor < block_count {
        let block = start_block + cursor;
        let addr = match cursor
            .checked_mul(BLOCK_SIZE)
            .and_then(|offset| start_addr.checked_add(offset))
        {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        let bytes = unsafe { ram_block_bytes(addr) };
        unsafe { KFS_BLOCK_CACHE.get() }.put_clean(block, &bytes);
        cursor += 1;
    }
    Ok(())
}

unsafe fn is_fs_block_cached(block: u32) -> bool {
    unsafe { KFS_BLOCK_CACHE.get() }.contains(block)
}

pub(crate) unsafe fn invalidate_block_cache() {
    unsafe { KFS_BLOCK_CACHE.get() }.invalidate_all();
}

unsafe fn copy_cached_block_to_ram(block: u32, dst_addr: u32) -> bool {
    match unsafe { KFS_BLOCK_CACHE.get() }.get(block) {
        Some(bytes) => {
            unsafe { write_cached_block_to_ram(bytes, dst_addr) };
            true
        }
        None => false,
    }
}

unsafe fn write_cached_block_to_scratch(bytes: &crate::kfs::block_cache::KfsBlockBytes) {
    unsafe { write_cached_block_to_ram(bytes, SCRATCH_ADDR) };
}

unsafe fn write_cached_block_to_ram(bytes: &crate::kfs::block_cache::KfsBlockBytes, dst_addr: u32) {
    let mut offset = 0;
    while offset < BLOCK_SIZE {
        unsafe { write_u8(dst_addr + offset, bytes[offset as usize]) };
        offset += 1;
    }
}

pub(crate) fn scratch_bytes_eq(offset: u32, expected: &[u8]) -> bool {
    let mut index = 0;
    while index < expected.len() {
        if scratch_u8(offset + index as u32) != expected[index] {
            return false;
        }
        index += 1;
    }
    true
}

pub(crate) fn scratch_u8(offset: u32) -> u8 {
    unsafe { read_u8(SCRATCH_ADDR + offset) }
}

pub(crate) unsafe fn write_scratch_u8(offset: u32, value: u8) {
    unsafe { write_u8(SCRATCH_ADDR + offset, value) }
}

pub(crate) unsafe fn write_scratch_u32(offset: u32, value: u32) {
    unsafe { write_u32(SCRATCH_ADDR + offset, value) }
}

pub(crate) fn scratch_u32(offset: u32) -> u32 {
    unsafe { read_u32(SCRATCH_ADDR + offset) }
}

pub(crate) unsafe fn scratch_block_bytes() -> [u8; BLOCK_SIZE as usize] {
    unsafe { ram_block_bytes(SCRATCH_ADDR) }
}

unsafe fn ram_block_bytes(addr: u32) -> [u8; BLOCK_SIZE as usize] {
    let mut block = [0_u8; BLOCK_SIZE as usize];
    let mut index = 0;
    while index < BLOCK_SIZE {
        block[index as usize] = unsafe { read_u8(addr + index) };
        index += 1;
    }
    block
}

pub(crate) unsafe fn copy_ram_to_ram(src_addr: u32, dst_addr: u32, len: u32) {
    let mut offset = 0;
    while offset < len {
        let byte = unsafe { read_u8(src_addr + offset) };
        unsafe { write_u8(dst_addr + offset, byte) };
        offset += 1;
    }
}

unsafe fn read_u32(address: u32) -> u32 {
    unsafe { core::ptr::read_volatile(address as usize as *const u32) }
}

unsafe fn read_u8(address: u32) -> u8 {
    unsafe { core::ptr::read_volatile(address as usize as *const u8) }
}

unsafe fn write_u32(address: u32, value: u32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u32, value) }
}

unsafe fn write_u8(address: u32, value: u8) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u8, value) }
}
