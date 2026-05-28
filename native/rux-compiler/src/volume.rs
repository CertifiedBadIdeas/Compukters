use crate::partition;
use crate::ruxe;
use crate::ruxfs;

pub const RUXVOL_MAGIC: &[u8; 6] = b"RUXVOL";
pub const RUXVOL_VERSION: u16 = 1;
pub const RUXVOL_HEADER_SIZE: usize = 16;
pub const RUXVOL_BOOT_RECORD_OFFSET: usize = 0;
pub const RUXVOL_BOOT_PAYLOAD_OFFSET: usize = 512;
pub const RUXVOL_RAW_BOOT_AREA_END: usize = 8192;

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

pub fn create_initialized_volume(size: usize) -> Result<Vec<u8>, String> {
    if size % partition::RUXPT_BLOCK_SIZE != 0 {
        return Err(format!(
            "ruxvol init size must be a multiple of {} bytes",
            partition::RUXPT_BLOCK_SIZE
        ));
    }
    let total_blocks = u32::try_from(size / partition::RUXPT_BLOCK_SIZE)
        .map_err(|_| "ruxvol init block count does not fit u32".to_string())?;
    let table = partition::default_boot_root_table(total_blocks)?;
    let table_bytes = partition::encode_partition_table(&table)?;
    let mut bytes = create_empty_volume(size)?;
    let table_start = RUXVOL_HEADER_SIZE;
    let table_end = table_start + table_bytes.len();
    bytes[table_start..table_end].copy_from_slice(&table_bytes);
    Ok(bytes)
}

pub fn put_boot(volume: &mut [u8], boot: &[u8]) -> Result<(), String> {
    let payload_range = validate_volume_header(volume)?;
    let boot = ruxe::decode_rux16_executable(boot)?;
    if boot.abi_kind != ruxe::RuxeAbiKind::Bootloader {
        return Err("boot media requires RUXE bootloader ABI kind".to_string());
    }
    let block_count = boot.payload.len().div_ceil(512);
    let payload_len = payload_range.len();
    let payload = &mut volume[payload_range];
    let boot_layout = boot_layout(payload)?;
    let boot_end = boot_layout
        .payload_offset
        .checked_add(boot.payload.len())
        .ok_or_else(|| "boot artifact range overflows".to_string())?;
    if boot_end > payload_len {
        return Err(format!(
            "boot artifact needs {boot_end} payload bytes but ruxvol payload has {payload_len} bytes",
        ));
    }
    if boot_end > boot_layout.end_offset {
        return Err(format!(
            "boot artifact exceeds boot area ending at byte {}",
            boot_layout.end_offset,
        ));
    }
    let block_count = u32::try_from(block_count)
        .map_err(|_| "boot artifact block count does not fit u32".to_string())?;

    payload[boot_layout.record_offset..boot_layout.record_offset + 4].copy_from_slice(b"RUXB");
    write_u32(payload, boot_layout.record_offset + 4, boot.entry_pc);
    write_u32(payload, boot_layout.record_offset + 8, boot.load_addr);
    write_u32(payload, boot_layout.record_offset + 12, block_count);
    write_u32(
        payload,
        boot_layout.record_offset + 16,
        boot_layout.payload_lba,
    );
    payload[boot_layout.payload_offset..boot_end].copy_from_slice(&boot.payload);
    Ok(())
}

pub fn put_kernel(volume: &mut [u8], kernel: &[u8]) -> Result<(), String> {
    let payload_range = validate_volume_header(volume)?;
    let executable = ruxe::decode_rux16_executable(kernel)?;
    if executable.abi_kind != ruxe::RuxeAbiKind::Kernel {
        return Err("kernel media requires RUXE kernel ABI kind".to_string());
    }
    let payload = &mut volume[payload_range];
    let root_range = partition_payload_range(payload, "ROOT")?;
    let root = &mut payload[root_range];
    ensure_boot_directory(root)?;
    ruxfs::write_file(root, "/boot/kernel.ruxe", kernel)
}

pub fn extract_partition(volume: &[u8], selector: &str) -> Result<Vec<u8>, String> {
    let payload_range = validate_volume_header(volume)?;
    let payload = &volume[payload_range];
    let partition_range = partition_payload_range(payload, selector)?;
    Ok(payload[partition_range].to_vec())
}

pub fn replace_partition(
    volume: &mut [u8],
    selector: &str,
    partition_bytes: &[u8],
) -> Result<(), String> {
    let payload_range = validate_volume_header(volume)?;
    let payload = &volume[payload_range.clone()];
    let partition_range = partition_payload_range(payload, selector)?;
    let expected_len = partition_range.len();
    if partition_bytes.len() != expected_len {
        return Err(format!(
            "partition `{selector}` is {expected_len} bytes but input has {} bytes",
            partition_bytes.len()
        ));
    }
    let payload = &mut volume[payload_range];
    payload[partition_range].copy_from_slice(partition_bytes);
    Ok(())
}

pub fn inspect(volume: &[u8]) -> Result<String, String> {
    let payload_range = validate_volume_header(volume)?;
    let payload_size = payload_range.len();
    let payload = &volume[payload_range];
    let table = decode_payload_partition_table(payload)?;

    let mut output = format!("RUXVOL v{RUXVOL_VERSION} payload={payload_size}\n");
    output.push_str(&format!(
        "RUXPT v{} entries={}\n",
        partition::RUXPT_VERSION,
        table.entries.len()
    ));
    for entry in table.entries {
        let bytes = partition_byte_offset(entry.block_count)?;
        output.push_str(&format!(
            "{} start_lba={} blocks={} bytes={} name={}\n",
            entry.partition_type.tag(),
            entry.start_lba,
            entry.block_count,
            bytes,
            entry.name
        ));
    }
    Ok(output)
}

pub fn inspect_boot_chain(volume: &[u8]) -> Result<String, String> {
    let payload_range = validate_volume_header(volume)?;
    let payload = &volume[payload_range];
    let table = decode_payload_partition_table(payload)?;
    let boot_entry = partition_entry_by_type(&table, partition::PartitionType::Boot)?;
    let root_entry = partition_entry_by_type(&table, partition::PartitionType::Root)?;

    let boot_record_offset = partition_byte_offset(boot_entry.start_lba)?;
    let boot_record = payload
        .get(boot_record_offset..boot_record_offset + 20)
        .ok_or_else(|| "BOOT partition boot record is outside media bounds".to_string())?;
    if &boot_record[0..4] != b"RUXB" {
        return Err("BOOT partition does not contain a RUXB boot record".to_string());
    }
    let boot_entry_pc = read_u32(boot_record, 4)?;
    let boot_load_addr = read_u32(boot_record, 8)?;
    let boot_blocks = read_u32(boot_record, 12)?;
    let boot_payload_lba = read_u32(boot_record, 16)?;

    let root_range = partition_entry_payload_range(payload, root_entry)?;
    let root = &payload[root_range];
    let kernel_bytes = ruxfs::read_file(root, "/boot/kernel.ruxe")
        .map_err(|error| format!("ROOT/RuxFS /boot/kernel.ruxe is not readable: {error}"))?;
    let kernel = ruxe::decode_rux16_executable(&kernel_bytes)?;
    if kernel.abi_kind != ruxe::RuxeAbiKind::Kernel {
        return Err("ROOT/RuxFS /boot/kernel.ruxe is not a kernel RUXE".to_string());
    }

    let root_bytes = partition_byte_offset(root_entry.block_count)?;
    let mut output = "RUXVOL boot-chain\n".to_string();
    output.push_str(&format!(
        "BOOT record entry_pc={boot_entry_pc:#010x} load_addr={boot_load_addr:#010x} blocks={boot_blocks} payload_lba={boot_payload_lba}\n"
    ));
    output.push_str(&format!(
        "ROOT partition start_lba={} blocks={} bytes={} name={}\n",
        root_entry.start_lba, root_entry.block_count, root_bytes, root_entry.name
    ));
    output.push_str(&format!(
        "ROOT RuxFS /boot/kernel.ruxe file_bytes={}\n",
        kernel_bytes.len()
    ));
    output.push_str(&format!(
        "KERNEL RUXE abi=kernel entry_pc={:#010x} load_addr={:#010x} payload_bytes={}\n",
        kernel.entry_pc,
        kernel.load_addr,
        kernel.payload.len()
    ));
    Ok(output)
}

fn partition_payload_range(
    payload: &[u8],
    selector: &str,
) -> Result<std::ops::Range<usize>, String> {
    let table = decode_payload_partition_table(payload)?;
    let entry = table
        .entries
        .iter()
        .find(|entry| entry.partition_type.tag() == selector || entry.name == selector)
        .ok_or_else(|| format!("RUXPT partition `{selector}` not found"))?;
    partition_entry_payload_range(payload, entry)
}

fn partition_entry_payload_range(
    payload: &[u8],
    entry: &partition::PartitionEntry,
) -> Result<std::ops::Range<usize>, String> {
    let start = partition_byte_offset(entry.start_lba)?;
    let len = partition_byte_offset(entry.block_count)?;
    let end = start
        .checked_add(len)
        .ok_or_else(|| format!("RUXPT partition `{}` byte range overflows", entry.name))?;
    if end > payload.len() {
        return Err(format!(
            "RUXPT partition `{}` is outside media bounds",
            entry.name
        ));
    }
    Ok(start..end)
}

fn decode_payload_partition_table(payload: &[u8]) -> Result<partition::RuxPartitionTable, String> {
    if payload.len() % partition::RUXPT_BLOCK_SIZE != 0 {
        return Err("ruxvol payload size is not block-aligned".to_string());
    }
    let table_bytes = payload
        .get(..partition::RUXPT_BLOCK_SIZE)
        .ok_or_else(|| "ruxvol payload is too small for RUXPT".to_string())?;
    let table = partition::decode_partition_table(table_bytes)?;
    let total_blocks = u32::try_from(payload.len() / partition::RUXPT_BLOCK_SIZE)
        .map_err(|_| "ruxvol block count does not fit u32".to_string())?;
    partition::validate_partition_table(&table, total_blocks)?;
    Ok(table)
}

fn partition_entry_by_type(
    table: &partition::RuxPartitionTable,
    partition_type: partition::PartitionType,
) -> Result<&partition::PartitionEntry, String> {
    table
        .entries
        .iter()
        .find(|entry| entry.partition_type == partition_type)
        .ok_or_else(|| format!("RUXPT {} partition not found", partition_type.tag()))
}

struct BootLayout {
    record_offset: usize,
    payload_offset: usize,
    end_offset: usize,
    payload_lba: u32,
}

fn boot_layout(payload: &[u8]) -> Result<BootLayout, String> {
    if payload.get(..5) == Some(partition::RUXPT_MAGIC) {
        let table_bytes = payload
            .get(..partition::RUXPT_BLOCK_SIZE)
            .ok_or_else(|| "ruxvol payload is too small for RUXPT".to_string())?;
        let table = partition::decode_partition_table(table_bytes)?;
        let total_blocks = u32::try_from(payload.len() / partition::RUXPT_BLOCK_SIZE)
            .map_err(|_| "ruxvol block count does not fit u32".to_string())?;
        partition::validate_partition_table(&table, total_blocks)?;
        let entry = table
            .entries
            .iter()
            .find(|entry| entry.partition_type == partition::PartitionType::Boot)
            .ok_or_else(|| "RUXPT BOOT partition not found".to_string())?;
        let record_offset = partition_byte_offset(entry.start_lba)?;
        let payload_lba = entry
            .start_lba
            .checked_add(1)
            .ok_or_else(|| "BOOT payload LBA overflows".to_string())?;
        let payload_offset = partition_byte_offset(payload_lba)?;
        let end_lba = entry
            .start_lba
            .checked_add(entry.block_count)
            .ok_or_else(|| "BOOT partition range overflows".to_string())?;
        let end_offset = partition_byte_offset(end_lba)?;
        return Ok(BootLayout {
            record_offset,
            payload_offset,
            end_offset,
            payload_lba,
        });
    }

    Ok(BootLayout {
        record_offset: RUXVOL_BOOT_RECORD_OFFSET,
        payload_offset: RUXVOL_BOOT_PAYLOAD_OFFSET,
        end_offset: RUXVOL_RAW_BOOT_AREA_END,
        payload_lba: 1,
    })
}

fn ensure_boot_directory(root: &mut [u8]) -> Result<(), String> {
    match ruxfs::list_directory(root, "/boot") {
        Ok(_) => Ok(()),
        Err(error) if error.contains("directory entry `boot` not found") => {
            ruxfs::create_directory(root, "/boot")
        }
        Err(error) => Err(error),
    }
}

fn partition_byte_offset(blocks: u32) -> Result<usize, String> {
    let bytes = blocks
        .checked_mul(partition::RUXPT_BLOCK_SIZE as u32)
        .ok_or_else(|| "RUXPT partition byte range overflows".to_string())?;
    usize::try_from(bytes).map_err(|_| "RUXPT partition byte range does not fit usize".to_string())
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

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, String> {
    let value = bytes
        .get(offset..offset + 4)
        .ok_or_else(|| "ruxvol field is truncated".to_string())?;
    Ok(u32::from_le_bytes(value.try_into().unwrap()))
}

fn write_u32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

fn write_u64(bytes: &mut [u8], offset: usize, value: u64) {
    bytes[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}
