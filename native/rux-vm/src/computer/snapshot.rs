use crate::computer::profile::ComputerMachineProfile;

pub const COMPUTER_SNAPSHOT_V1_MAGIC: &[u8; 8] = b"RUXSNAP\0";
pub const COMPUTER_SNAPSHOT_V1_VERSION: u16 = 1;
pub const COMPUTER_SNAPSHOT_V1_HEADER_SIZE: usize = 32;
const NO_BOOT_CPU: u32 = u32::MAX;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ComputerMachineSnapshotHeader {
    pub version: u16,
    pub header_size: u16,
    pub flags: u32,
    pub ram_size: u64,
    pub cpu_count: u32,
    pub boot_cpu_id: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ComputerMachineSnapshot<'a> {
    pub header: ComputerMachineSnapshotHeader,
    pub ram: &'a [u8],
}

pub fn encode_snapshot_v1(
    ram: &[u8],
    cpu_count: usize,
    boot_cpu_id: Option<usize>,
) -> Result<Vec<u8>, String> {
    let ram_size =
        u64::try_from(ram.len()).map_err(|_| "snapshot RAM size does not fit u64".to_string())?;
    let cpu_count =
        u32::try_from(cpu_count).map_err(|_| "snapshot CPU count does not fit u32".to_string())?;
    let boot_cpu_id = match boot_cpu_id {
        Some(id) => {
            u32::try_from(id).map_err(|_| "snapshot boot CPU id does not fit u32".to_string())?
        }
        None => NO_BOOT_CPU,
    };
    let capacity = COMPUTER_SNAPSHOT_V1_HEADER_SIZE
        .checked_add(ram.len())
        .ok_or_else(|| "snapshot size overflows usize".to_string())?;
    let mut bytes = Vec::with_capacity(capacity);
    bytes.extend_from_slice(COMPUTER_SNAPSHOT_V1_MAGIC);
    write_u16(&mut bytes, COMPUTER_SNAPSHOT_V1_VERSION);
    write_u16(&mut bytes, COMPUTER_SNAPSHOT_V1_HEADER_SIZE as u16);
    write_u32(&mut bytes, 0);
    write_u64(&mut bytes, ram_size);
    write_u32(&mut bytes, cpu_count);
    write_u32(&mut bytes, boot_cpu_id);
    bytes.extend_from_slice(ram);
    Ok(bytes)
}

pub fn decode_snapshot_v1(bytes: &[u8]) -> Result<ComputerMachineSnapshot<'_>, String> {
    if bytes.len() < COMPUTER_SNAPSHOT_V1_HEADER_SIZE {
        return Err("ComputerMachine snapshot is smaller than the v1 header".to_string());
    }
    if &bytes[..8] != COMPUTER_SNAPSHOT_V1_MAGIC {
        return Err("invalid ComputerMachine snapshot magic".to_string());
    }
    let version = read_u16(bytes, 8)?;
    if version != COMPUTER_SNAPSHOT_V1_VERSION {
        return Err(format!(
            "unsupported ComputerMachine snapshot version {version}"
        ));
    }
    let header_size = read_u16(bytes, 10)?;
    if usize::from(header_size) != COMPUTER_SNAPSHOT_V1_HEADER_SIZE {
        return Err(format!(
            "unsupported ComputerMachine snapshot header size {header_size}"
        ));
    }
    let flags = read_u32(bytes, 12)?;
    if flags != 0 {
        return Err(format!(
            "unsupported ComputerMachine snapshot flags {flags:#010x}"
        ));
    }
    let ram_size = read_u64(bytes, 16)?;
    let cpu_count = read_u32(bytes, 24)?;
    let boot_cpu_id = match read_u32(bytes, 28)? {
        NO_BOOT_CPU => None,
        id => Some(id),
    };
    let ram_len = usize::try_from(ram_size)
        .map_err(|_| "ComputerMachine snapshot RAM size does not fit usize".to_string())?;
    let expected_len = COMPUTER_SNAPSHOT_V1_HEADER_SIZE
        .checked_add(ram_len)
        .ok_or_else(|| "ComputerMachine snapshot size overflows usize".to_string())?;
    if bytes.len() != expected_len {
        return Err(format!(
            "ComputerMachine snapshot declares {ram_len} RAM bytes but file has {} RAM bytes",
            bytes.len().saturating_sub(COMPUTER_SNAPSHOT_V1_HEADER_SIZE)
        ));
    }
    Ok(ComputerMachineSnapshot {
        header: ComputerMachineSnapshotHeader {
            version,
            header_size,
            flags,
            ram_size,
            cpu_count,
            boot_cpu_id,
        },
        ram: &bytes[COMPUTER_SNAPSHOT_V1_HEADER_SIZE..],
    })
}

pub(crate) fn validate_snapshot_ram_matches_profile(
    profile: &ComputerMachineProfile,
    snapshot: &ComputerMachineSnapshot<'_>,
) -> Result<(), String> {
    if snapshot.ram.len() != profile.memory_size {
        return Err(format!(
            "ComputerMachine snapshot RAM size {} does not match profile memory size {}",
            snapshot.ram.len(),
            profile.memory_size
        ));
    }
    Ok(())
}

fn write_u16(bytes: &mut Vec<u8>, value: u16) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_u32(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_u64(bytes: &mut Vec<u8>, value: u64) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn read_u16(bytes: &[u8], offset: usize) -> Result<u16, String> {
    let raw = bytes
        .get(offset..offset + 2)
        .ok_or_else(|| "ComputerMachine snapshot header is truncated".to_string())?;
    Ok(u16::from_le_bytes(raw.try_into().unwrap()))
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, String> {
    let raw = bytes
        .get(offset..offset + 4)
        .ok_or_else(|| "ComputerMachine snapshot header is truncated".to_string())?;
    Ok(u32::from_le_bytes(raw.try_into().unwrap()))
}

fn read_u64(bytes: &[u8], offset: usize) -> Result<u64, String> {
    let raw = bytes
        .get(offset..offset + 8)
        .ok_or_else(|| "ComputerMachine snapshot header is truncated".to_string())?;
    Ok(u64::from_le_bytes(raw.try_into().unwrap()))
}
