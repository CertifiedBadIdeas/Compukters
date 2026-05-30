use crate::partition;
use crate::ruxe;
use crate::ruxfs;
use crate::volume;

pub fn inspect_blob(bytes: &[u8]) -> Result<String, String> {
    if bytes.starts_with(volume::K16VOL_MAGIC) {
        return Ok(format!("kind=K16VOL\n{}", volume::inspect(bytes)?));
    }
    if bytes.starts_with(partition::RUXPT_MAGIC) {
        return inspect_partitioned_media(bytes);
    }
    if bytes.starts_with(ruxfs::RUXFS_MAGIC) {
        return inspect_ruxfs(bytes);
    }
    if bytes.starts_with(ruxe::RUXE_MAGIC) {
        return inspect_ruxe(bytes);
    }
    Err(format!(
        "unrecognized Rux blob magic: {}",
        first_bytes_hex(bytes)
    ))
}

fn inspect_partitioned_media(bytes: &[u8]) -> Result<String, String> {
    if bytes.len() % partition::RUXPT_BLOCK_SIZE != 0 {
        return Err("RUXPT media size is not block-aligned".to_string());
    }
    let total_blocks = u32::try_from(bytes.len() / partition::RUXPT_BLOCK_SIZE)
        .map_err(|_| "RUXPT media block count does not fit u32".to_string())?;
    let table_block = bytes
        .get(..partition::RUXPT_BLOCK_SIZE)
        .ok_or_else(|| "RUXPT media is too small for the table block".to_string())?;
    let table = partition::decode_partition_table(table_block)?;
    partition::validate_partition_table(&table, total_blocks)?;

    let mut output = format!(
        "kind=RUXPT\nRUXPT v{} entries={} media_blocks={total_blocks}\n",
        partition::RUXPT_VERSION,
        table.entries.len()
    );
    for entry in table.entries {
        let bytes = partition_bytes(entry.block_count)?;
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

fn inspect_ruxfs(bytes: &[u8]) -> Result<String, String> {
    let superblock = ruxfs::decode_superblock(bytes)?;
    Ok(format!(
        "kind=RUXFS\nRUXFS v{} blocks={} block_size={} root_inode={} inode_table_blocks={}\n",
        ruxfs::RUXFS_VERSION,
        superblock.total_blocks,
        superblock.block_size,
        superblock.root_inode_id,
        superblock.inode_table_block_count
    ))
}

fn inspect_ruxe(bytes: &[u8]) -> Result<String, String> {
    let executable = ruxe::decode_rux16_executable(bytes)?;
    Ok(format!(
        "kind=RUXE\nRUXE abi={} entry_pc={:#010x} load_addr={:#010x} payload_bytes={}\n",
        ruxe_abi_name(executable.abi_kind),
        executable.entry_pc,
        executable.load_addr,
        executable.payload.len()
    ))
}

fn ruxe_abi_name(abi_kind: ruxe::RuxeAbiKind) -> &'static str {
    match abi_kind {
        ruxe::RuxeAbiKind::Bootloader => "bootloader",
        ruxe::RuxeAbiKind::Kernel => "kernel",
        ruxe::RuxeAbiKind::Program => "program",
    }
}

fn partition_bytes(blocks: u32) -> Result<usize, String> {
    let bytes = blocks
        .checked_mul(partition::RUXPT_BLOCK_SIZE as u32)
        .ok_or_else(|| "RUXPT partition byte range overflows".to_string())?;
    usize::try_from(bytes).map_err(|_| "RUXPT partition byte range does not fit usize".to_string())
}

fn first_bytes_hex(bytes: &[u8]) -> String {
    let preview = bytes
        .iter()
        .take(8)
        .map(|byte| format!("{byte:02x}"))
        .collect::<Vec<_>>();
    if preview.is_empty() {
        "<empty>".to_string()
    } else {
        preview.join(" ")
    }
}
