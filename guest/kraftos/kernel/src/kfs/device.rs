use k16_abi::computer::storage;

use crate::kfs::block_io::{BLOCK_SIZE, SCRATCH_ADDR};
use crate::kfs::error::StorageError;

pub const COMMAND_OFFSET: u32 = storage::COMMAND_OFFSET;
pub const MEDIA_STATUS_OFFSET: u32 = storage::MEDIA_STATUS_OFFSET;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KfsDevice {
    base: u32,
    read_only: bool,
}

impl KfsDevice {
    pub const fn new(base: u32, read_only: bool) -> Self {
        Self { base, read_only }
    }

    pub const fn storage0() -> Self {
        Self::new(k16_abi::computer::storage0::BASE, false)
    }

    pub const fn storage1() -> Self {
        Self::new(k16_abi::computer::storage1::BASE, true)
    }

    pub const fn register(self, offset: u32) -> u32 {
        self.base + offset
    }

    pub const fn is_read_only(self) -> bool {
        self.read_only
    }
}

pub unsafe fn capacity_blocks_u32(device: KfsDevice) -> Result<u32, StorageError> {
    let capacity_high = unsafe { read_u32(device.register(storage::CAPACITY_BLOCKS_HIGH_OFFSET)) };
    let capacity_low = unsafe { read_u32(device.register(storage::CAPACITY_BLOCKS_LOW_OFFSET)) };
    if capacity_high != 0 {
        return Err(StorageError::INVALID_PARTITION_TABLE);
    }
    Ok(capacity_low)
}

pub unsafe fn flush_storage(device: KfsDevice) -> Result<(), StorageError> {
    if device.is_read_only() {
        return Ok(());
    }
    let version = unsafe { read_i32(device.register(storage::VERSION_OFFSET)) };
    if version != storage::STORAGE_VERSION {
        return Err(StorageError::STORAGE_VERSION);
    }
    let media = unsafe { read_i32(device.register(storage::MEDIA_STATUS_OFFSET)) };
    if media != storage::MEDIA_PRESENT && media != storage::MEDIA_READ_ONLY {
        return Err(StorageError::STORAGE_MEDIA);
    }
    unsafe {
        write_i32(
            device.register(storage::COMMAND_OFFSET),
            storage::COMMAND_FLUSH,
        );
    }
    if unsafe { read_i32(device.register(storage::STATUS_OFFSET)) } != storage::STATUS_DONE
        || unsafe { read_i32(device.register(storage::ERROR_OFFSET)) } != storage::ERROR_NONE
    {
        return Err(StorageError::STORAGE_TRANSFER);
    }
    Ok(())
}

#[inline(always)]
pub unsafe fn read_storage_block_to_scratch(
    device: KfsDevice,
    lba: u32,
) -> Result<(), StorageError> {
    unsafe { read_storage_blocks_to_ram(device, lba, 1, SCRATCH_ADDR) }
}

#[inline(always)]
pub unsafe fn read_storage_blocks_to_ram(
    device: KfsDevice,
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
    let version = unsafe { read_i32(device.register(storage::VERSION_OFFSET)) };
    if version != storage::STORAGE_VERSION {
        return Err(StorageError::STORAGE_VERSION);
    }
    let block_size = unsafe { read_i32(device.register(storage::BLOCK_SIZE_OFFSET)) };
    if block_size != BLOCK_SIZE as i32 {
        return Err(StorageError::STORAGE_BLOCK_SIZE);
    }
    let media = unsafe { read_i32(device.register(storage::MEDIA_STATUS_OFFSET)) };
    if media != storage::MEDIA_PRESENT && media != storage::MEDIA_READ_ONLY {
        return Err(StorageError::STORAGE_MEDIA);
    }
    unsafe {
        write_u32(device.register(storage::LBA_LOW_OFFSET), lba);
        write_u32(device.register(storage::LBA_HIGH_OFFSET), 0);
        write_u32(device.register(storage::BLOCK_COUNT_OFFSET), block_count);
        write_u32(device.register(storage::BUFFER_ADDR_OFFSET), dst_addr);
        write_i32(
            device.register(storage::COMMAND_OFFSET),
            storage::COMMAND_READ_BLOCKS,
        );
    }
    if unsafe { read_i32(device.register(storage::STATUS_OFFSET)) } != storage::STATUS_DONE
        || unsafe { read_i32(device.register(storage::ERROR_OFFSET)) } != storage::ERROR_NONE
        || unsafe { read_u32(device.register(storage::BYTES_DONE_OFFSET)) } != bytes_done
    {
        return Err(StorageError::STORAGE_TRANSFER);
    }
    Ok(())
}

#[inline(always)]
pub unsafe fn write_scratch_block_to_storage(
    device: KfsDevice,
    lba: u32,
) -> Result<(), StorageError> {
    if device.is_read_only() {
        return Err(StorageError::STORAGE_MEDIA);
    }
    let version = unsafe { read_i32(device.register(storage::VERSION_OFFSET)) };
    if version != storage::STORAGE_VERSION {
        return Err(StorageError::STORAGE_VERSION);
    }
    let block_size = unsafe { read_i32(device.register(storage::BLOCK_SIZE_OFFSET)) };
    if block_size != BLOCK_SIZE as i32 {
        return Err(StorageError::STORAGE_BLOCK_SIZE);
    }
    let media = unsafe { read_i32(device.register(storage::MEDIA_STATUS_OFFSET)) };
    if media != storage::MEDIA_PRESENT {
        return Err(StorageError::STORAGE_MEDIA);
    }
    unsafe {
        write_u32(device.register(storage::LBA_LOW_OFFSET), lba);
        write_u32(device.register(storage::LBA_HIGH_OFFSET), 0);
        write_u32(device.register(storage::BLOCK_COUNT_OFFSET), 1);
        write_u32(device.register(storage::BUFFER_ADDR_OFFSET), SCRATCH_ADDR);
        write_i32(
            device.register(storage::COMMAND_OFFSET),
            storage::COMMAND_WRITE_BLOCKS,
        );
    }
    if unsafe { read_i32(device.register(storage::STATUS_OFFSET)) } != storage::STATUS_DONE
        || unsafe { read_i32(device.register(storage::ERROR_OFFSET)) } != storage::ERROR_NONE
        || unsafe { read_u32(device.register(storage::BYTES_DONE_OFFSET)) } != BLOCK_SIZE
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kfs_device_offsets_are_relative_to_the_selected_controller() {
        let storage1 = KfsDevice::new(0x1000_0900, true);

        assert_eq!(storage1.register(COMMAND_OFFSET), 0x1000_090c);
        assert_eq!(storage1.register(MEDIA_STATUS_OFFSET), 0x1000_0938);
        assert!(storage1.is_read_only());
        assert_eq!(unsafe { flush_storage(storage1) }, Ok(()));
        assert_eq!(
            unsafe { write_scratch_block_to_storage(storage1, 0) },
            Err(StorageError::STORAGE_MEDIA),
        );
    }
}
