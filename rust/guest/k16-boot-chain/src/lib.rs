#![no_std]

use k16_abi::computer::storage0;

pub const SCRATCH_ADDR: u32 = 0x0000_0600;

const BLOCK_SIZE: u32 = 512;
const K16PT_MAGIC: &[u8; 5] = b"K16PT";
const K16PT_VERSION: u8 = 1;
const K16PT_HEADER_SIZE: u32 = 16;
const K16PT_ENTRY_SIZE: u32 = 32;
const K16PT_MAX_ENTRIES: u8 = 15;

const K16FS_MAGIC: &[u8; 5] = b"K16FS";
const K16FS_VERSION: u8 = 1;
const K16FS_INODE_SIZE: u32 = 64;
const K16FS_DIRECTORY_ENTRY_SIZE: u32 = 64;
const K16FS_MAX_NAME_BYTES: usize = 56;
const K16FS_MAX_INLINE_EXTENTS: usize = 4;

const K16E_PAYLOAD_OFFSET: u32 = 52;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum K16eAbiKind {
    Bootloader = 1,
    Kernel = 2,
    Program = 3,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct LoadError {
    code: i32,
}

impl LoadError {
    pub const STORAGE_VERSION: Self = Self { code: 10 };
    pub const INVALID_PARTITION_TABLE: Self = Self { code: 11 };
    pub const PARTITION_NOT_FOUND: Self = Self { code: 12 };
    pub const INVALID_FILESYSTEM: Self = Self { code: 13 };
    pub const PATH_NOT_FOUND: Self = Self { code: 14 };
    pub const INVALID_EXECUTABLE: Self = Self { code: 15 };
    pub const STORAGE_TRANSFER: Self = Self { code: 16 };
    pub const STORAGE_BLOCK_SIZE: Self = Self { code: 17 };
    pub const STORAGE_MEDIA: Self = Self { code: 18 };

    pub const fn code(self) -> i32 {
        self.code
    }
}

#[derive(Clone, Copy)]
pub struct LoadedImage {
    pub entry_pc: u32,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
struct K16eLoadPlan {
    entry_pc: u32,
    load_addr: u32,
    file_size: u32,
    zero_fill_addr: u32,
    zero_fill_len: u32,
}

const STATE_PARTITION_START_LBA: u32 = 0x0000_0200;
const STATE_PARTITION_BLOCK_COUNT: u32 = 0x0000_0204;
const STATE_SUPERBLOCK_TOTAL_BLOCKS: u32 = 0x0000_0208;
const STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK: u32 = 0x0000_020c;
const STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT: u32 = 0x0000_0210;
const STATE_SUPERBLOCK_ROOT_INODE_ID: u32 = 0x0000_0214;
const STATE_INODE_STATE: u32 = 0x0000_0218;
const STATE_INODE_SIZE_BYTES: u32 = 0x0000_021c;
const STATE_INODE_EXTENT_COUNT: u32 = 0x0000_0220;
const STATE_INODE_EXTENT_START_BLOCKS: u32 = 0x0000_0224;
const STATE_INODE_EXTENT_BLOCK_COUNTS: u32 = 0x0000_0234;

pub unsafe fn load_k16e_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
    expected_abi_kind: K16eAbiKind,
) -> Result<LoadedImage, LoadError> {
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };
    unsafe { find_file_inode(path)? };
    unsafe { load_k16e_file(expected_abi_kind) }
}

pub unsafe fn enter_loaded_image(image: LoadedImage) -> ! {
    let entry: extern "C" fn() -> ! =
        unsafe { core::mem::transmute::<usize, extern "C" fn() -> !>(image.entry_pc as usize) };
    entry()
}

unsafe fn read_partition(partition_type: &[u8; 4]) -> Result<(), LoadError> {
    unsafe { read_storage_block(0)? };
    if !scratch_eq(0, K16PT_MAGIC) || scratch_u8(5) != K16PT_VERSION || scratch_u8(7) != 0 {
        return Err(LoadError::INVALID_PARTITION_TABLE);
    }
    let entry_count = scratch_u8(6);
    if entry_count > K16PT_MAX_ENTRIES || scratch_u32(8) != 0 || scratch_u32(12) != 1 {
        return Err(LoadError::INVALID_PARTITION_TABLE);
    }

    let capacity_high = unsafe { read_u32(storage0::CAPACITY_BLOCKS_HIGH) };
    let capacity_low = unsafe { read_u32(storage0::CAPACITY_BLOCKS_LOW) };
    if capacity_high != 0 {
        return Err(LoadError::INVALID_PARTITION_TABLE);
    }

    let mut index = 0;
    while index < entry_count as u32 {
        let offset = K16PT_HEADER_SIZE + index * K16PT_ENTRY_SIZE;
        let start_lba = scratch_u32(offset + 8);
        let block_count = scratch_u32(offset + 12);
        if scratch_u32(offset + 4) != 0 || start_lba < 1 || block_count == 0 {
            return Err(LoadError::INVALID_PARTITION_TABLE);
        }
        let end_lba = match start_lba.checked_add(block_count) {
            Some(value) => value,
            None => return Err(LoadError::INVALID_PARTITION_TABLE),
        };
        if end_lba > capacity_low {
            return Err(LoadError::INVALID_PARTITION_TABLE);
        }
        if scratch_eq(offset, partition_type) {
            unsafe {
                write_u32(STATE_PARTITION_START_LBA, start_lba);
                write_u32(STATE_PARTITION_BLOCK_COUNT, block_count);
            }
            return Ok(());
        }
        index += 1;
    }
    Err(LoadError::PARTITION_NOT_FOUND)
}

unsafe fn read_superblock() -> Result<(), LoadError> {
    unsafe { read_fs_block(0)? };
    if !scratch_eq(0, K16FS_MAGIC)
        || scratch_u8(5) != K16FS_VERSION
        || scratch_u8(6) != 0
        || scratch_u8(7) != 0
        || unsafe { read_u32(SCRATCH_ADDR + 0x08) } != BLOCK_SIZE
    {
        return Err(LoadError::INVALID_FILESYSTEM);
    }

    let total_blocks = unsafe { read_u32(SCRATCH_ADDR + 0x0c) };
    if total_blocks == 0 || total_blocks > unsafe { read_u32(STATE_PARTITION_BLOCK_COUNT) } {
        return Err(LoadError::INVALID_FILESYSTEM);
    }

    let inode_table_start_block = unsafe { read_u32(SCRATCH_ADDR + 0x18) };
    let inode_table_block_count = unsafe { read_u32(SCRATCH_ADDR + 0x1c) };
    let root_inode_id = unsafe { read_u32(SCRATCH_ADDR + 0x20) };
    unsafe {
        write_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS, total_blocks);
        write_u32(
            STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK,
            inode_table_start_block,
        );
        write_u32(
            STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT,
            inode_table_block_count,
        );
        write_u32(STATE_SUPERBLOCK_ROOT_INODE_ID, root_inode_id);
        read_inode(root_inode_id)?;
    }
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2 {
        return Err(LoadError::INVALID_FILESYSTEM);
    }
    Ok(())
}

unsafe fn find_file_inode(path: &[&[u8]]) -> Result<(), LoadError> {
    if path.is_empty() {
        return Err(LoadError::PATH_NOT_FOUND);
    }

    let mut inode_id = unsafe { read_u32(STATE_SUPERBLOCK_ROOT_INODE_ID) };
    let mut index = 0;
    while index < path.len() {
        let component = path[index];
        unsafe { read_inode(inode_id)? };
        if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2 {
            return Err(LoadError::PATH_NOT_FOUND);
        }
        inode_id = unsafe { find_directory_entry(component)? };
        index += 1;
    }

    unsafe { read_inode(inode_id)? };
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 1 {
        return Err(LoadError::PATH_NOT_FOUND);
    }
    Ok(())
}

unsafe fn find_directory_entry(name: &[u8]) -> Result<u32, LoadError> {
    if name.is_empty()
        || name.len() > K16FS_MAX_NAME_BYTES
        || unsafe { read_u32(STATE_INODE_SIZE_BYTES) } % K16FS_DIRECTORY_ENTRY_SIZE != 0
    {
        return Err(LoadError::INVALID_FILESYSTEM);
    }

    let mut remaining = unsafe { read_u32(STATE_INODE_SIZE_BYTES) };
    let mut extent_index = 0;
    while extent_index < unsafe { read_u32(STATE_INODE_EXTENT_COUNT) as usize } {
        let extent_start_block =
            unsafe { read_u32(STATE_INODE_EXTENT_START_BLOCKS + extent_index as u32 * 4) };
        let extent_block_count =
            unsafe { read_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + extent_index as u32 * 4) };
        validate_extent(extent_start_block, extent_block_count, unsafe {
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
        })?;
        let mut block_index = 0;
        while block_index < extent_block_count {
            unsafe { read_fs_block(extent_start_block + block_index)? };
            let mut offset = 0;
            while offset < BLOCK_SIZE && remaining > 0 {
                match scratch_u8(offset) {
                    0 | 2 => {}
                    1 => {
                        let name_len = scratch_u8(offset + 1) as usize;
                        if name_len == 0
                            || name_len > K16FS_MAX_NAME_BYTES
                            || scratch_u8(offset + 2) != 0
                            || scratch_u8(offset + 3) != 0
                        {
                            return Err(LoadError::INVALID_FILESYSTEM);
                        }
                        if name_len == name.len() && scratch_bytes_eq(offset + 8, name) {
                            return Ok(scratch_u32(offset + 4));
                        }
                    }
                    _ => return Err(LoadError::INVALID_FILESYSTEM),
                }
                remaining -= K16FS_DIRECTORY_ENTRY_SIZE;
                offset += K16FS_DIRECTORY_ENTRY_SIZE;
            }
            block_index += 1;
        }
        extent_index += 1;
    }

    if remaining != 0 {
        return Err(LoadError::INVALID_FILESYSTEM);
    }
    Err(LoadError::PATH_NOT_FOUND)
}

unsafe fn load_k16e_file(expected_abi_kind: K16eAbiKind) -> Result<LoadedImage, LoadError> {
    unsafe { copy_file_range_to_ram(0, SCRATCH_ADDR, K16E_PAYLOAD_OFFSET)? };
    if !scratch_eq(0, b"K16E")
        || scratch_u16(4) != 1
        || scratch_u16(6) != 32
        || scratch_u16(8) != 1
        || scratch_u16(10) != 0
        || scratch_u32(16) != 32
        || scratch_u32(20) != 1
        || scratch_u32(24) != expected_abi_kind as u32
        || scratch_u32(28) != 0
        || scratch_u32(32) != 1
        || scratch_u32(40) != K16E_PAYLOAD_OFFSET
    {
        return Err(LoadError::INVALID_EXECUTABLE);
    }

    let entry_pc = scratch_u32(12);
    let load_addr = scratch_u32(36);
    let file_size = scratch_u32(44);
    let memory_size = scratch_u32(48);
    let plan = k16e_load_plan(entry_pc, load_addr, file_size, memory_size)?;
    let file_end = match K16E_PAYLOAD_OFFSET.checked_add(file_size) {
        Some(value) => value,
        None => return Err(LoadError::INVALID_EXECUTABLE),
    };
    if file_end > unsafe { read_u32(STATE_INODE_SIZE_BYTES) } {
        return Err(LoadError::INVALID_EXECUTABLE);
    }

    unsafe { copy_file_range_to_ram(K16E_PAYLOAD_OFFSET, plan.load_addr, plan.file_size)? };
    unsafe { zero_fill_ram(plan.zero_fill_addr, plan.zero_fill_len) };
    Ok(LoadedImage {
        entry_pc: plan.entry_pc,
    })
}

fn k16e_load_plan(
    entry_pc: u32,
    load_addr: u32,
    file_size: u32,
    memory_size: u32,
) -> Result<K16eLoadPlan, LoadError> {
    if file_size == 0 || memory_size < file_size || file_size % 2 != 0 || memory_size % 2 != 0 {
        return Err(LoadError::INVALID_EXECUTABLE);
    }
    let load_end = match load_addr.checked_add(memory_size) {
        Some(value) => value,
        None => return Err(LoadError::INVALID_EXECUTABLE),
    };
    if entry_pc < load_addr || entry_pc >= load_end || entry_pc % 2 != 0 {
        return Err(LoadError::INVALID_EXECUTABLE);
    }
    let zero_fill_addr = match load_addr.checked_add(file_size) {
        Some(value) => value,
        None => return Err(LoadError::INVALID_EXECUTABLE),
    };
    Ok(K16eLoadPlan {
        entry_pc,
        load_addr,
        file_size,
        zero_fill_addr,
        zero_fill_len: memory_size - file_size,
    })
}

unsafe fn copy_file_range_to_ram(
    file_offset: u32,
    dst_addr: u32,
    len: u32,
) -> Result<(), LoadError> {
    let range_end = match file_offset.checked_add(len) {
        Some(value) => value,
        None => return Err(LoadError::INVALID_FILESYSTEM),
    };
    if range_end > unsafe { read_u32(STATE_INODE_SIZE_BYTES) } {
        return Err(LoadError::INVALID_FILESYSTEM);
    }

    let mut copied = 0;
    let mut extent_file_start: u32 = 0;
    let mut extent_index = 0;
    while extent_index < unsafe { read_u32(STATE_INODE_EXTENT_COUNT) as usize } && copied < len {
        let extent_start_block =
            unsafe { read_u32(STATE_INODE_EXTENT_START_BLOCKS + extent_index as u32 * 4) };
        let extent_block_count =
            unsafe { read_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + extent_index as u32 * 4) };
        let extent_bytes = match extent_block_count.checked_mul(BLOCK_SIZE) {
            Some(value) => value,
            None => return Err(LoadError::INVALID_FILESYSTEM),
        };
        let extent_file_end = match extent_file_start.checked_add(extent_bytes) {
            Some(value) => value,
            None => return Err(LoadError::INVALID_FILESYSTEM),
        };

        if range_end > extent_file_start && file_offset < extent_file_end {
            let copy_start = max_u32(file_offset, extent_file_start);
            let copy_end = min_u32(range_end, extent_file_end);
            let mut cursor = copy_start;
            while cursor < copy_end {
                let within_extent = cursor - extent_file_start;
                let block_delta = within_extent / BLOCK_SIZE;
                let block_offset = within_extent % BLOCK_SIZE;
                let available = min_u32(BLOCK_SIZE - block_offset, copy_end - cursor);
                unsafe { read_fs_block(extent_start_block + block_delta)? };
                unsafe {
                    copy_ram_to_ram(SCRATCH_ADDR + block_offset, dst_addr + copied, available);
                }
                copied += available;
                cursor += available;
            }
        }

        extent_file_start = extent_file_end;
        extent_index += 1;
    }

    if copied != len {
        return Err(LoadError::INVALID_FILESYSTEM);
    }
    Ok(())
}

#[inline(always)]
unsafe fn read_inode(inode_id: u32) -> Result<(), LoadError> {
    let inodes_per_block = BLOCK_SIZE / K16FS_INODE_SIZE;
    let inode_capacity = match unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT) }
        .checked_mul(inodes_per_block)
    {
        Some(value) => value,
        None => return Err(LoadError::INVALID_FILESYSTEM),
    };
    if inode_id >= inode_capacity {
        return Err(LoadError::INVALID_FILESYSTEM);
    }
    let inode_block =
        unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK) } + inode_id / inodes_per_block;
    let inode_offset = (inode_id % inodes_per_block) * K16FS_INODE_SIZE;
    unsafe { read_fs_block(inode_block)? };

    let size_high = scratch_u32(inode_offset + 0x0c);
    let extent_count = scratch_u8(inode_offset + 0x10) as usize;
    if size_high != 0 || extent_count > K16FS_MAX_INLINE_EXTENTS {
        return Err(LoadError::INVALID_FILESYSTEM);
    }

    unsafe {
        write_u32(STATE_INODE_STATE, scratch_u8(inode_offset) as u32);
        write_u32(STATE_INODE_SIZE_BYTES, scratch_u32(inode_offset + 0x08));
        write_u32(STATE_INODE_EXTENT_COUNT, extent_count as u32);
    }
    let mut index = 0;
    while index < extent_count {
        let offset = inode_offset + 0x20 + index as u32 * 8;
        let start_block = scratch_u32(offset);
        let block_count = scratch_u32(offset + 4);
        validate_extent(start_block, block_count, unsafe {
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
        })?;
        unsafe {
            write_u32(
                STATE_INODE_EXTENT_START_BLOCKS + index as u32 * 4,
                start_block,
            );
            write_u32(
                STATE_INODE_EXTENT_BLOCK_COUNTS + index as u32 * 4,
                block_count,
            );
        }
        index += 1;
    }

    Ok(())
}

#[inline(always)]
fn validate_extent(start_block: u32, block_count: u32, total_blocks: u32) -> Result<(), LoadError> {
    let end = match start_block.checked_add(block_count) {
        Some(value) => value,
        None => return Err(LoadError::INVALID_FILESYSTEM),
    };
    if block_count == 0 || end > total_blocks {
        return Err(LoadError::INVALID_FILESYSTEM);
    }
    Ok(())
}

#[inline(always)]
unsafe fn read_fs_block(block: u32) -> Result<(), LoadError> {
    if block >= unsafe { read_u32(STATE_PARTITION_BLOCK_COUNT) } {
        return Err(LoadError::INVALID_FILESYSTEM);
    }
    let lba = match unsafe { read_u32(STATE_PARTITION_START_LBA) }.checked_add(block) {
        Some(value) => value,
        None => return Err(LoadError::INVALID_FILESYSTEM),
    };
    unsafe { read_storage_block(lba) }
}

#[inline(always)]
unsafe fn read_storage_block(lba: u32) -> Result<(), LoadError> {
    let version = unsafe { read_i32(storage0::VERSION) };
    if version != storage0::STORAGE_VERSION {
        return Err(LoadError::STORAGE_VERSION);
    }
    let block_size = unsafe { read_i32(storage0::BLOCK_SIZE) };
    if block_size != BLOCK_SIZE as i32 {
        return Err(LoadError::STORAGE_BLOCK_SIZE);
    }
    let media = unsafe { read_i32(storage0::MEDIA_STATUS) };
    if media != storage0::MEDIA_PRESENT && media != storage0::MEDIA_READ_ONLY {
        return Err(LoadError::STORAGE_MEDIA);
    }
    unsafe {
        write_u32(storage0::LBA_LOW, lba);
        write_u32(storage0::LBA_HIGH, 0);
        write_u32(storage0::BLOCK_COUNT, 1);
        write_u32(storage0::BUFFER_ADDR, SCRATCH_ADDR);
        write_i32(storage0::COMMAND, storage0::COMMAND_READ_BLOCKS);
    }
    if unsafe { read_i32(storage0::STATUS) } != storage0::STATUS_DONE
        || unsafe { read_i32(storage0::ERROR) } != storage0::ERROR_NONE
        || unsafe { read_u32(storage0::BYTES_DONE) } != BLOCK_SIZE
    {
        return Err(LoadError::STORAGE_TRANSFER);
    }
    Ok(())
}

fn scratch_eq(offset: u32, expected: &[u8]) -> bool {
    scratch_bytes_eq(offset, expected)
}

fn scratch_bytes_eq(offset: u32, expected: &[u8]) -> bool {
    let mut index = 0;
    while index < expected.len() {
        if scratch_u8(offset + index as u32) != expected[index] {
            return false;
        }
        index += 1;
    }
    true
}

fn scratch_u8(offset: u32) -> u8 {
    unsafe { read_u8(SCRATCH_ADDR + offset) }
}

fn scratch_u16(offset: u32) -> u16 {
    unsafe { read_u16(SCRATCH_ADDR + offset) }
}

fn scratch_u32(offset: u32) -> u32 {
    unsafe { read_u32(SCRATCH_ADDR + offset) }
}

unsafe fn copy_ram_to_ram(src_addr: u32, dst_addr: u32, len: u32) {
    let mut offset = 0;
    while offset < len {
        let byte = unsafe { read_u8(src_addr + offset) };
        unsafe { write_u8(dst_addr + offset, byte) };
        offset += 1;
    }
}

unsafe fn zero_fill_ram(dst_addr: u32, len: u32) {
    let mut offset = 0;
    while offset < len {
        unsafe { write_u8(dst_addr + offset, 0) };
        offset += 1;
    }
}

fn min_u32(left: u32, right: u32) -> u32 {
    if left < right {
        left
    } else {
        right
    }
}

fn max_u32(left: u32, right: u32) -> u32 {
    if left > right {
        left
    } else {
        right
    }
}

unsafe fn read_i32(address: u32) -> i32 {
    unsafe { core::ptr::read_volatile(address as usize as *const i32) }
}

unsafe fn read_u32(address: u32) -> u32 {
    unsafe { core::ptr::read_volatile(address as usize as *const u32) }
}

unsafe fn read_u16(address: u32) -> u16 {
    unsafe { core::ptr::read_volatile(address as usize as *const u16) }
}

unsafe fn read_u8(address: u32) -> u8 {
    unsafe { core::ptr::read_volatile(address as usize as *const u8) }
}

unsafe fn write_i32(address: u32, value: i32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut i32, value) }
}

unsafe fn write_u32(address: u32, value: u32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u32, value) }
}

unsafe fn write_u8(address: u32, value: u8) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u8, value) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn k16e_load_plan_accepts_zero_fill_tail() {
        let plan = k16e_load_plan(0x8000, 0x8000, 2, 8).expect("plan validates");

        assert_eq!(
            plan,
            K16eLoadPlan {
                entry_pc: 0x8000,
                load_addr: 0x8000,
                file_size: 2,
                zero_fill_addr: 0x8002,
                zero_fill_len: 6,
            }
        );
    }

    #[test]
    fn k16e_load_plan_rejects_memory_smaller_than_file() {
        assert_eq!(
            k16e_load_plan(0x8000, 0x8000, 8, 2),
            Err(LoadError::INVALID_EXECUTABLE)
        );
    }
}
