use crate::ruxe;

pub const RUXVOL_MAGIC: &[u8; 6] = b"RUXVOL";
pub const RUXVOL_VERSION: u16 = 1;
pub const RUXVOL_HEADER_SIZE: usize = 16;
pub const RUXVOL_BOOT_RECORD_OFFSET: usize = 0;
pub const RUXVOL_BOOT_PAYLOAD_OFFSET: usize = 512;
pub const RUXVOL_KERNEL_RECORD_OFFSET: usize = 8192;
pub const RUXVOL_KERNEL_PAYLOAD_OFFSET: usize = 8704;
pub const RUXVOL_KERNEL_RECORD_LBA: u32 = 16;
pub const RUXVOL_KERNEL_PAYLOAD_LBA: u32 = 17;

pub fn create_empty_volume(size: usize) -> Result<Vec<u8>, String> {
    if size < RUXVOL_BOOT_PAYLOAD_OFFSET {
        return Err(format!(
            "ruxvol size must be at least {RUXVOL_BOOT_PAYLOAD_OFFSET} bytes"
        ));
    }
    let volume_size =
        u64::try_from(size).map_err(|_| "ruxvol size does not fit u64".to_string())?;
    let mut bytes = vec![0_u8; RUXVOL_HEADER_SIZE + size];
    bytes[..6].copy_from_slice(RUXVOL_MAGIC);
    bytes[6..8].copy_from_slice(&RUXVOL_VERSION.to_le_bytes());
    write_u64(&mut bytes, 8, volume_size);
    Ok(bytes)
}

pub fn put_boot(volume: &mut [u8], boot: &[u8]) -> Result<(), String> {
    let payload_range = validate_volume_header(volume)?;
    let boot = ruxe::decode_rux16_executable(boot)?;
    if boot.abi_kind != ruxe::RuxeAbiKind::Bootloader {
        return Err("boot media requires RUXE bootloader ABI kind".to_string());
    }
    let block_count = boot.payload.len().div_ceil(512);
    let boot_end = RUXVOL_BOOT_PAYLOAD_OFFSET
        .checked_add(boot.payload.len())
        .ok_or_else(|| "boot artifact range overflows".to_string())?;
    let payload_len = payload_range.len();
    if boot_end > payload_len {
        return Err(format!(
            "boot artifact needs {boot_end} payload bytes but ruxvol payload has {payload_len} bytes",
        ));
    }
    if boot_end > RUXVOL_KERNEL_RECORD_OFFSET {
        return Err(format!(
            "boot artifact overlaps reserved kernel record area at byte {RUXVOL_KERNEL_RECORD_OFFSET}",
        ));
    }
    let block_count = u32::try_from(block_count)
        .map_err(|_| "boot artifact block count does not fit u32".to_string())?;

    let payload = &mut volume[payload_range];
    payload[RUXVOL_BOOT_RECORD_OFFSET..RUXVOL_BOOT_RECORD_OFFSET + 4].copy_from_slice(b"RUXB");
    write_u32(payload, 4, boot.entry_pc);
    write_u32(payload, 8, boot.load_addr);
    write_u32(payload, 12, block_count);
    write_u32(payload, 16, 1);
    payload[RUXVOL_BOOT_PAYLOAD_OFFSET..boot_end].copy_from_slice(&boot.payload);
    Ok(())
}

pub fn put_kernel(volume: &mut [u8], kernel: &[u8]) -> Result<(), String> {
    let payload_range = validate_volume_header(volume)?;
    let kernel = ruxe::decode_rux16_executable(kernel)?;
    if kernel.abi_kind != ruxe::RuxeAbiKind::Kernel {
        return Err("kernel media requires RUXE kernel ABI kind".to_string());
    }
    let block_count = kernel.payload.len().div_ceil(512);
    let kernel_end = RUXVOL_KERNEL_PAYLOAD_OFFSET
        .checked_add(kernel.payload.len())
        .ok_or_else(|| "kernel artifact range overflows".to_string())?;
    let payload_len = payload_range.len();
    if kernel_end > payload_len {
        return Err(format!(
            "kernel artifact needs {kernel_end} payload bytes but ruxvol payload has {payload_len} bytes",
        ));
    }
    let block_count = u32::try_from(block_count)
        .map_err(|_| "kernel artifact block count does not fit u32".to_string())?;

    let payload = &mut volume[payload_range];
    payload[RUXVOL_KERNEL_RECORD_OFFSET..RUXVOL_KERNEL_RECORD_OFFSET + 4].copy_from_slice(b"RUXK");
    write_u32(payload, RUXVOL_KERNEL_RECORD_OFFSET + 4, kernel.entry_pc);
    write_u32(payload, RUXVOL_KERNEL_RECORD_OFFSET + 8, kernel.load_addr);
    write_u32(payload, RUXVOL_KERNEL_RECORD_OFFSET + 12, block_count);
    write_u32(
        payload,
        RUXVOL_KERNEL_RECORD_OFFSET + 16,
        RUXVOL_KERNEL_PAYLOAD_LBA,
    );
    payload[RUXVOL_KERNEL_PAYLOAD_OFFSET..kernel_end].copy_from_slice(&kernel.payload);
    Ok(())
}

fn validate_volume_header(volume: &[u8]) -> Result<std::ops::Range<usize>, String> {
    if volume.len() < RUXVOL_HEADER_SIZE {
        return Err("ruxvol is smaller than the header".to_string());
    }
    if &volume[..6] != RUXVOL_MAGIC {
        return Err("invalid ruxvol magic".to_string());
    }
    let version = u16::from_le_bytes(volume[6..8].try_into().unwrap());
    if version != RUXVOL_VERSION {
        return Err(format!("unsupported ruxvol version {version}"));
    }
    let declared_size = read_u64(volume, 8)?;
    let declared_size = usize::try_from(declared_size)
        .map_err(|_| "ruxvol logical size does not fit usize".to_string())?;
    let expected_size = RUXVOL_HEADER_SIZE
        .checked_add(declared_size)
        .ok_or_else(|| "ruxvol file size overflows usize".to_string())?;
    if expected_size != volume.len() {
        return Err(format!(
            "ruxvol header declares {declared_size} payload bytes but file has {} bytes",
            volume.len()
        ));
    }
    Ok(RUXVOL_HEADER_SIZE..expected_size)
}

fn read_u64(bytes: &[u8], offset: usize) -> Result<u64, String> {
    let value = bytes
        .get(offset..offset + 8)
        .ok_or_else(|| "ruxvol header is truncated".to_string())?;
    Ok(u64::from_le_bytes(value.try_into().unwrap()))
}

fn write_u32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

fn write_u64(bytes: &mut [u8], offset: usize, value: u64) {
    bytes[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}
