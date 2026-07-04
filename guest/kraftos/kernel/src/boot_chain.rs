use k16_abi::computer::profile;

pub const SCRATCH_ADDR: u32 = crate::kfs::block_io::SCRATCH_ADDR;

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
    pub const INVALID_BOOT_INFO: Self = Self { code: 19 };

    pub const fn code(self) -> i32 {
        self.code
    }

    const fn from_storage(error: crate::kfs::error::StorageError) -> Self {
        Self { code: error.code() }
    }

    const fn from_image(_error: crate::image::K16ImageError) -> Self {
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
        crate::kfs::storage::open_file_from_storage0(partition_type, path)
            .map_err(LoadError::from_storage)?
    };
    unsafe { load_k16e_file(expected_abi_kind) }
}

pub unsafe fn enter_loaded_image(image: LoadedImage) -> ! {
    let entry: extern "C" fn() -> ! =
        unsafe { core::mem::transmute::<usize, extern "C" fn() -> !>(image.entry_pc as usize) };
    entry()
}

pub fn user_memory_end_from_boot_info(
    boot_info: profile::BootInfo,
    image: LoadedImage,
) -> Result<u32, LoadError> {
    if boot_info.page_size == 0 || image.load_addr >= image.load_end {
        return Err(LoadError::INVALID_BOOT_INFO);
    }
    if boot_info.ram_size <= image.load_end {
        return Err(LoadError::INVALID_BOOT_INFO);
    }
    Ok(boot_info.ram_size)
}

pub unsafe fn user_memory_end_from_current_boot_info(image: LoadedImage) -> Result<u32, LoadError> {
    let boot_info = unsafe { profile::read_boot_info() }.ok_or(LoadError::INVALID_BOOT_INFO)?;
    user_memory_end_from_boot_info(boot_info, image)
}

unsafe fn load_k16e_file(expected_abi_kind: K16eAbiKind) -> Result<LoadedImage, LoadError> {
    unsafe {
        crate::kfs::file_io::copy_selected_file_range_to_ram(
            0,
            SCRATCH_ADDR,
            crate::image::FIXED_K16E_V1_HEADER_SIZE,
        )
        .map_err(LoadError::from_storage)?
    };

    let header = unsafe {
        core::slice::from_raw_parts(
            SCRATCH_ADDR as usize as *const u8,
            crate::image::FIXED_K16E_V1_HEADER_SIZE as usize,
        )
    };
    let plan =
        crate::image::parse_fixed_k16e_v1(header, expected_abi_kind.into_image_kind(), unsafe {
            crate::kfs::selected_inode::selected_file_size()
        })
        .map_err(LoadError::from_image)?;

    unsafe {
        crate::kfs::file_io::copy_selected_file_range_to_ram(
            crate::image::FIXED_K16E_V1_PAYLOAD_OFFSET,
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
    const fn into_image_kind(self) -> crate::image::K16eAbiKind {
        match self {
            Self::Bootloader => crate::image::K16eAbiKind::Bootloader,
            Self::Kernel => crate::image::K16eAbiKind::Kernel,
            Self::Program => crate::image::K16eAbiKind::Program,
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

#[cfg(test)]
mod tests {
    use super::*;
    use k16_abi::computer::profile::BootInfo;

    fn boot_info(ram_size: u32) -> BootInfo {
        BootInfo {
            ram_size,
            page_size: 256,
            program_base: 0x0000_0100,
            hardware_table_addr: 28,
            hardware_count: 0,
        }
    }

    #[test]
    fn boot_info_ram_size_defines_user_memory_end() {
        let image = LoadedImage {
            entry_pc: 0x0001_3000,
            load_addr: 0x0001_3000,
            load_end: 0x0001_4100,
        };

        assert_eq!(
            user_memory_end_from_boot_info(boot_info(0x0003_0000), image),
            Ok(0x0003_0000)
        );
    }

    #[test]
    fn boot_info_ram_size_must_contain_loaded_user_image() {
        let image = LoadedImage {
            entry_pc: 0x0001_3000,
            load_addr: 0x0001_3000,
            load_end: 0x0001_4100,
        };

        assert_eq!(
            user_memory_end_from_boot_info(boot_info(0x0001_4000), image),
            Err(LoadError::INVALID_BOOT_INFO)
        );
    }
}
