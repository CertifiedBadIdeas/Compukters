use crate::partition;
use crate::ruxe;
use crate::ruxfs;

pub const K16VOL_MAGIC: &[u8; 6] = b"K16VOL";
pub const K16VOL_VERSION: u16 = 1;
pub const K16VOL_HEADER_SIZE: usize = 16;
pub const K16VOL_BOOT_PAYLOAD_OFFSET: usize = 512;

pub fn create_empty_volume(size: usize) -> Result<Vec<u8>, String> {
    if size < K16VOL_BOOT_PAYLOAD_OFFSET {
        return Err(format!(
            "k16vol size must be at least {K16VOL_BOOT_PAYLOAD_OFFSET} bytes"
        ));
    }
    let volume_size =
        u64::try_from(size).map_err(|_| "k16vol size does not fit u64".to_string())?;
    let mut bytes = vec![0_u8; K16VOL_HEADER_SIZE + size];
    bytes[..6].copy_from_slice(K16VOL_MAGIC);
    bytes[6..8].copy_from_slice(&K16VOL_VERSION.to_le_bytes());
    write_u64(&mut bytes, 8, volume_size);
    Ok(bytes)
}

pub fn create_initialized_volume(size: usize) -> Result<Vec<u8>, String> {
    if size % partition::RUXPT_BLOCK_SIZE != 0 {
        return Err(format!(
            "k16vol init size must be a multiple of {} bytes",
            partition::RUXPT_BLOCK_SIZE
        ));
    }
    let total_blocks = u32::try_from(size / partition::RUXPT_BLOCK_SIZE)
        .map_err(|_| "k16vol init block count does not fit u32".to_string())?;
    let table = partition::default_boot_root_table(total_blocks)?;
    let table_bytes = partition::encode_partition_table(&table)?;
    let mut bytes = create_empty_volume(size)?;
    let table_start = K16VOL_HEADER_SIZE;
    let table_end = table_start + table_bytes.len();
    bytes[table_start..table_end].copy_from_slice(&table_bytes);
    let root_entry = partition_entry_by_type(&table, partition::PartitionType::Root)?;
    let root_start = K16VOL_HEADER_SIZE
        .checked_add(partition_byte_offset(root_entry.start_lba)?)
        .ok_or_else(|| "ROOT partition byte range overflows".to_string())?;
    let root_len = partition_byte_offset(root_entry.block_count)?;
    let root_end = root_start
        .checked_add(root_len)
        .ok_or_else(|| "ROOT partition byte range overflows".to_string())?;
    let root = ruxfs::format_empty_filesystem(root_entry.block_count)?;
    bytes[root_start..root_end].copy_from_slice(&root);
    Ok(bytes)
}

pub fn put_boot(volume: &mut [u8], boot: &[u8]) -> Result<(), String> {
    let payload_range = validate_volume_header(volume)?;
    let executable = ruxe::decode_rux16_executable(boot)?;
    if executable.abi_kind != ruxe::RuxeAbiKind::Bootloader {
        return Err("boot media requires RUXE bootloader ABI kind".to_string());
    }
    let payload = &mut volume[payload_range];
    if payload.get(..5) != Some(partition::RUXPT_MAGIC) {
        return Err("put-boot requires a RUXPT partitioned volume".to_string());
    }
    let boot_range = partition_payload_range(payload, "BOOT")?;
    let boot_blocks = u32::try_from(boot_range.len() / partition::RUXPT_BLOCK_SIZE)
        .map_err(|_| "BOOT partition block count does not fit u32".to_string())?;
    let mut boot_fs = ruxfs::format_empty_filesystem(boot_blocks)?;
    ruxfs::create_directory(&mut boot_fs, "/boot")?;
    ruxfs::write_file(&mut boot_fs, "/boot/loader.kb", boot)?;
    payload[boot_range].copy_from_slice(&boot_fs);
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
    ruxfs::write_file(root, "/boot/kernel.kx", kernel)
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

    let mut output = format!("K16VOL v{K16VOL_VERSION} payload={payload_size}\n");
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

    let boot_range = partition_entry_payload_range(payload, boot_entry)?;
    let boot_fs = &payload[boot_range];
    let bootloader_bytes = ruxfs::read_file(boot_fs, "/boot/loader.kb")
        .map_err(|error| format!("BOOT/RuxFS /boot/loader.kb is not readable: {error}"))?;
    let bootloader = ruxe::decode_rux16_executable(&bootloader_bytes)?;
    if bootloader.abi_kind != ruxe::RuxeAbiKind::Bootloader {
        return Err("BOOT/RuxFS /boot/loader.kb is not a bootloader RUXE".to_string());
    }

    let root_range = partition_entry_payload_range(payload, root_entry)?;
    let root = &payload[root_range];
    let kernel_bytes = ruxfs::read_file(root, "/boot/kernel.kx")
        .map_err(|error| format!("ROOT/RuxFS /boot/kernel.kx is not readable: {error}"))?;
    let kernel = ruxe::decode_rux16_executable(&kernel_bytes)?;
    if kernel.abi_kind != ruxe::RuxeAbiKind::Kernel {
        return Err("ROOT/RuxFS /boot/kernel.kx is not a kernel RUXE".to_string());
    }

    let root_bytes = partition_byte_offset(root_entry.block_count)?;
    let boot_bytes = partition_byte_offset(boot_entry.block_count)?;
    let mut output = "K16VOL boot-chain\n".to_string();
    output.push_str(&format!(
        "BOOT partition start_lba={} blocks={} bytes={} name={}\n",
        boot_entry.start_lba, boot_entry.block_count, boot_bytes, boot_entry.name
    ));
    output.push_str(&format!(
        "BOOT RuxFS /boot/loader.kb file_bytes={}\n",
        bootloader_bytes.len()
    ));
    output.push_str(&format!(
        "BOOTLOADER RUXE abi=bootloader entry_pc={:#010x} load_addr={:#010x} payload_bytes={}\n",
        bootloader.entry_pc,
        bootloader.load_addr,
        bootloader.payload.len()
    ));
    output.push_str(&format!(
        "ROOT partition start_lba={} blocks={} bytes={} name={}\n",
        root_entry.start_lba, root_entry.block_count, root_bytes, root_entry.name
    ));
    output.push_str(&format!(
        "ROOT RuxFS /boot/kernel.kx file_bytes={}\n",
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
        return Err("k16vol payload size is not block-aligned".to_string());
    }
    let table_bytes = payload
        .get(..partition::RUXPT_BLOCK_SIZE)
        .ok_or_else(|| "k16vol payload is too small for RUXPT".to_string())?;
    let table = partition::decode_partition_table(table_bytes)?;
    let total_blocks = u32::try_from(payload.len() / partition::RUXPT_BLOCK_SIZE)
        .map_err(|_| "k16vol block count does not fit u32".to_string())?;
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
    if volume.len() < K16VOL_HEADER_SIZE {
        return Err("k16vol is smaller than the header".to_string());
    }
    if &volume[..6] != K16VOL_MAGIC {
        return Err("invalid k16vol magic".to_string());
    }
    let version = u16::from_le_bytes(volume[6..8].try_into().unwrap());
    if version != K16VOL_VERSION {
        return Err(format!("unsupported k16vol version {version}"));
    }
    let declared_size = read_u64(volume, 8)?;
    let declared_size = usize::try_from(declared_size)
        .map_err(|_| "k16vol logical size does not fit usize".to_string())?;
    let expected_size = K16VOL_HEADER_SIZE
        .checked_add(declared_size)
        .ok_or_else(|| "k16vol file size overflows usize".to_string())?;
    if expected_size != volume.len() {
        return Err(format!(
            "k16vol header declares {declared_size} payload bytes but file has {} bytes",
            volume.len()
        ));
    }
    Ok(K16VOL_HEADER_SIZE..expected_size)
}

fn read_u64(bytes: &[u8], offset: usize) -> Result<u64, String> {
    let value = bytes
        .get(offset..offset + 8)
        .ok_or_else(|| "k16vol header is truncated".to_string())?;
    Ok(u64::from_le_bytes(value.try_into().unwrap()))
}

fn write_u64(bytes: &mut [u8], offset: usize, value: u64) {
    bytes[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}
