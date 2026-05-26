pub const RUXVOL_MAGIC: &[u8; 6] = b"RUXVOL";
pub const RUXVOL_VERSION: u16 = 1;
pub const RUXVOL_HEADER_SIZE: usize = 16;
pub const RUXVOL_BOOT_RECORD_OFFSET: usize = 0;
pub const RUXVOL_BOOT_PAYLOAD_OFFSET: usize = 512;
pub const RUX16_BOOT_ENTRY_PC: u32 = 2048;
pub const RUX16_BOOT_LOAD_ADDR: u32 = 2048;

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
    if boot.is_empty() {
        return Err("boot artifact is empty".to_string());
    }
    let block_count = boot.len().div_ceil(512);
    let boot_end = RUXVOL_BOOT_PAYLOAD_OFFSET
        .checked_add(boot.len())
        .ok_or_else(|| "boot artifact range overflows".to_string())?;
    let payload_len = payload_range.len();
    if boot_end > payload_len {
        return Err(format!(
            "boot artifact needs {boot_end} payload bytes but ruxvol payload has {payload_len} bytes",
        ));
    }
    let block_count = u32::try_from(block_count)
        .map_err(|_| "boot artifact block count does not fit u32".to_string())?;

    let payload = &mut volume[payload_range];
    payload[RUXVOL_BOOT_RECORD_OFFSET..RUXVOL_BOOT_RECORD_OFFSET + 4].copy_from_slice(b"RUXB");
    write_u32(payload, 4, RUX16_BOOT_ENTRY_PC);
    write_u32(payload, 8, RUX16_BOOT_LOAD_ADDR);
    write_u32(payload, 12, block_count);
    write_u32(payload, 16, 1);
    payload[RUXVOL_BOOT_PAYLOAD_OFFSET..boot_end].copy_from_slice(boot);
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
