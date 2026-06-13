#![no_std]

pub const SCRATCH_ADDR: u32 = k16_storage::SCRATCH_ADDR;

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

    const fn from_storage(error: k16_storage::StorageError) -> Self {
        Self { code: error.code() }
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

pub unsafe fn load_k16e_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
    expected_abi_kind: K16eAbiKind,
) -> Result<LoadedImage, LoadError> {
    unsafe {
        k16_storage::open_file_from_storage0(partition_type, path)
            .map_err(LoadError::from_storage)?
    };
    unsafe { load_k16e_file(expected_abi_kind) }
}

pub unsafe fn enter_loaded_image(image: LoadedImage) -> ! {
    let entry: extern "C" fn() -> ! =
        unsafe { core::mem::transmute::<usize, extern "C" fn() -> !>(image.entry_pc as usize) };
    entry()
}

unsafe fn load_k16e_file(expected_abi_kind: K16eAbiKind) -> Result<LoadedImage, LoadError> {
    unsafe {
        k16_storage::copy_selected_file_range_to_ram(0, SCRATCH_ADDR, K16E_PAYLOAD_OFFSET)
            .map_err(LoadError::from_storage)?
    };
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
    if file_end > unsafe { k16_storage::selected_file_size() } {
        return Err(LoadError::INVALID_EXECUTABLE);
    }

    unsafe {
        k16_storage::copy_selected_file_range_to_ram(
            K16E_PAYLOAD_OFFSET,
            plan.load_addr,
            plan.file_size,
        )
        .map_err(LoadError::from_storage)?
    };
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

fn scratch_eq(offset: u32, expected: &[u8]) -> bool {
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

unsafe fn zero_fill_ram(dst_addr: u32, len: u32) {
    let mut offset = 0;
    while offset < len {
        unsafe { write_u8(dst_addr + offset, 0) };
        offset += 1;
    }
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
