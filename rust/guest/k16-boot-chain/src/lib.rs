#![no_std]

pub const SCRATCH_ADDR: u32 = k16_storage::SCRATCH_ADDR;

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

    const fn from_image(_error: k16_image::K16ImageError) -> Self {
        Self::INVALID_EXECUTABLE
    }
}

#[derive(Clone, Copy)]
pub struct LoadedImage {
    pub entry_pc: u32,
    pub load_addr: u32,
    pub load_end: u32,
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
        k16_storage::copy_selected_file_range_to_ram(
            0,
            SCRATCH_ADDR,
            k16_image::FIXED_K16E_V1_HEADER_SIZE,
        )
        .map_err(LoadError::from_storage)?
    };

    let header = unsafe {
        core::slice::from_raw_parts(
            SCRATCH_ADDR as usize as *const u8,
            k16_image::FIXED_K16E_V1_HEADER_SIZE as usize,
        )
    };
    let plan =
        k16_image::parse_fixed_k16e_v1(header, expected_abi_kind.into_image_kind(), unsafe {
            k16_storage::selected_file_size()
        })
        .map_err(LoadError::from_image)?;

    unsafe {
        k16_storage::copy_selected_file_range_to_ram(
            k16_image::FIXED_K16E_V1_PAYLOAD_OFFSET,
            plan.load_addr,
            plan.file_size,
        )
        .map_err(LoadError::from_storage)?
    };
    unsafe { zero_fill_ram(plan.zero_fill_addr, plan.zero_fill_len) };
    Ok(LoadedImage {
        entry_pc: plan.entry_pc,
        load_addr: plan.load_addr,
        load_end: plan.load_addr + plan.memory_size,
    })
}

impl K16eAbiKind {
    const fn into_image_kind(self) -> k16_image::K16eAbiKind {
        match self {
            Self::Bootloader => k16_image::K16eAbiKind::Bootloader,
            Self::Kernel => k16_image::K16eAbiKind::Kernel,
            Self::Program => k16_image::K16eAbiKind::Program,
        }
    }
}

unsafe fn zero_fill_ram(dst_addr: u32, len: u32) {
    let mut offset = 0;
    while offset < len {
        unsafe { write_u8(dst_addr + offset, 0) };
        offset += 1;
    }
}

unsafe fn write_u8(address: u32, value: u8) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u8, value) }
}
