pub const RUXVOL_MAGIC: &[u8; 8] = b"RUXVOL1\0";
pub const RUXVOL_HEADER_SIZE: usize = 24;
pub const RUXVOL_BOOT_OFFSET: usize = 512;

pub fn create_empty_volume(size: usize) -> Result<Vec<u8>, String> {
    if size < RUXVOL_BOOT_OFFSET {
        return Err(format!(
            "ruxvol size must be at least {RUXVOL_BOOT_OFFSET} bytes"
        ));
    }
    let volume_size =
        u32::try_from(size).map_err(|_| "ruxvol size does not fit u32".to_string())?;
    let mut bytes = vec![0_u8; size];
    bytes[..8].copy_from_slice(RUXVOL_MAGIC);
    write_u32(&mut bytes, 8, volume_size);
    write_u32(&mut bytes, 12, 0);
    write_u32(&mut bytes, 16, 0);
    write_u32(&mut bytes, 20, 0);
    Ok(bytes)
}

pub fn put_boot(volume: &mut [u8], boot: &[u8]) -> Result<(), String> {
    validate_volume_header(volume)?;
    if boot.is_empty() {
        return Err("boot artifact is empty".to_string());
    }
    let boot_end = RUXVOL_BOOT_OFFSET
        .checked_add(boot.len())
        .ok_or_else(|| "boot artifact range overflows".to_string())?;
    if boot_end > volume.len() {
        return Err(format!(
            "boot artifact needs {boot_end} bytes but ruxvol has {} bytes",
            volume.len()
        ));
    }
    let boot_size =
        u32::try_from(boot.len()).map_err(|_| "boot artifact size does not fit u32".to_string())?;

    volume[RUXVOL_BOOT_OFFSET..boot_end].copy_from_slice(boot);
    write_u32(volume, 12, RUXVOL_BOOT_OFFSET as u32);
    write_u32(volume, 16, boot_size);
    write_u32(volume, 20, checksum(boot));
    Ok(())
}

fn validate_volume_header(volume: &[u8]) -> Result<(), String> {
    if volume.len() < RUXVOL_HEADER_SIZE {
        return Err("ruxvol is smaller than the header".to_string());
    }
    if &volume[..8] != RUXVOL_MAGIC {
        return Err("invalid ruxvol magic".to_string());
    }
    let declared_size = read_u32(volume, 8)? as usize;
    if declared_size != volume.len() {
        return Err(format!(
            "ruxvol header declares {declared_size} bytes but file has {} bytes",
            volume.len()
        ));
    }
    Ok(())
}

fn checksum(bytes: &[u8]) -> u32 {
    bytes
        .iter()
        .fold(0_u32, |acc, byte| acc.wrapping_add(u32::from(*byte)))
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, String> {
    let value = bytes
        .get(offset..offset + 4)
        .ok_or_else(|| "ruxvol header is truncated".to_string())?;
    Ok(u32::from_le_bytes(value.try_into().unwrap()))
}

fn write_u32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}
