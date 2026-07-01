use k16_abi::computer::storage0;

use crate::kfs::storage::{StorageError, BLOCK_SIZE, SCRATCH_ADDR};

pub unsafe fn capacity_blocks_u32() -> Result<u32, StorageError> {
    let capacity_high = unsafe { read_u32(storage0::CAPACITY_BLOCKS_HIGH) };
    let capacity_low = unsafe { read_u32(storage0::CAPACITY_BLOCKS_LOW) };
    if capacity_high != 0 {
        return Err(StorageError::INVALID_PARTITION_TABLE);
    }
    Ok(capacity_low)
}

pub unsafe fn flush_storage0() -> Result<(), StorageError> {
    let version = unsafe { read_i32(storage0::VERSION) };
    if version != storage0::STORAGE_VERSION {
        return Err(StorageError::STORAGE_VERSION);
    }
    let media = unsafe { read_i32(storage0::MEDIA_STATUS) };
    if media != storage0::MEDIA_PRESENT && media != storage0::MEDIA_READ_ONLY {
        return Err(StorageError::STORAGE_MEDIA);
    }
    unsafe {
        write_i32(storage0::COMMAND, storage0::COMMAND_FLUSH);
    }
    if unsafe { read_i32(storage0::STATUS) } != storage0::STATUS_DONE
        || unsafe { read_i32(storage0::ERROR) } != storage0::ERROR_NONE
    {
        return Err(StorageError::STORAGE_TRANSFER);
    }
    Ok(())
}

#[inline(always)]
pub unsafe fn read_storage_block_to_scratch(lba: u32) -> Result<(), StorageError> {
    unsafe { read_storage_blocks_to_ram(lba, 1, SCRATCH_ADDR) }
}

#[inline(always)]
pub unsafe fn read_storage_blocks_to_ram(
    lba: u32,
    block_count: u32,
    dst_addr: u32,
) -> Result<(), StorageError> {
    if block_count == 0 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let bytes_done = match block_count.checked_mul(BLOCK_SIZE) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    let version = unsafe { read_i32(storage0::VERSION) };
    if version != storage0::STORAGE_VERSION {
        return Err(StorageError::STORAGE_VERSION);
    }
    let block_size = unsafe { read_i32(storage0::BLOCK_SIZE) };
    if block_size != BLOCK_SIZE as i32 {
        return Err(StorageError::STORAGE_BLOCK_SIZE);
    }
    let media = unsafe { read_i32(storage0::MEDIA_STATUS) };
    if media != storage0::MEDIA_PRESENT && media != storage0::MEDIA_READ_ONLY {
        return Err(StorageError::STORAGE_MEDIA);
    }
    unsafe {
        write_u32(storage0::LBA_LOW, lba);
        write_u32(storage0::LBA_HIGH, 0);
        write_u32(storage0::BLOCK_COUNT, block_count);
        write_u32(storage0::BUFFER_ADDR, dst_addr);
        write_i32(storage0::COMMAND, storage0::COMMAND_READ_BLOCKS);
    }
    if unsafe { read_i32(storage0::STATUS) } != storage0::STATUS_DONE
        || unsafe { read_i32(storage0::ERROR) } != storage0::ERROR_NONE
        || unsafe { read_u32(storage0::BYTES_DONE) } != bytes_done
    {
        return Err(StorageError::STORAGE_TRANSFER);
    }
    Ok(())
}

#[inline(always)]
pub unsafe fn write_scratch_block_to_storage(lba: u32) -> Result<(), StorageError> {
    let version = unsafe { read_i32(storage0::VERSION) };
    if version != storage0::STORAGE_VERSION {
        return Err(StorageError::STORAGE_VERSION);
    }
    let block_size = unsafe { read_i32(storage0::BLOCK_SIZE) };
    if block_size != BLOCK_SIZE as i32 {
        return Err(StorageError::STORAGE_BLOCK_SIZE);
    }
    let media = unsafe { read_i32(storage0::MEDIA_STATUS) };
    if media != storage0::MEDIA_PRESENT {
        return Err(StorageError::STORAGE_MEDIA);
    }
    unsafe {
        write_u32(storage0::LBA_LOW, lba);
        write_u32(storage0::LBA_HIGH, 0);
        write_u32(storage0::BLOCK_COUNT, 1);
        write_u32(storage0::BUFFER_ADDR, SCRATCH_ADDR);
        write_i32(storage0::COMMAND, storage0::COMMAND_WRITE_BLOCKS);
    }
    if unsafe { read_i32(storage0::STATUS) } != storage0::STATUS_DONE
        || unsafe { read_i32(storage0::ERROR) } != storage0::ERROR_NONE
        || unsafe { read_u32(storage0::BYTES_DONE) } != BLOCK_SIZE
    {
        return Err(StorageError::STORAGE_TRANSFER);
    }
    Ok(())
}

unsafe fn read_i32(address: u32) -> i32 {
    unsafe { core::ptr::read_volatile(address as usize as *const i32) }
}

unsafe fn read_u32(address: u32) -> u32 {
    unsafe { core::ptr::read_volatile(address as usize as *const u32) }
}

unsafe fn write_i32(address: u32, value: i32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut i32, value) }
}

unsafe fn write_u32(address: u32, value: u32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u32, value) }
}
