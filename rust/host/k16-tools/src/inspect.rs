use crate::k16e;
use crate::k16fs;
use crate::partition;
use crate::volume;

pub fn inspect_blob(bytes: &[u8]) -> Result<String, String> {
    if bytes.starts_with(volume::K16VOL_MAGIC) {
        return Ok(format!("kind=K16VOL\n{}", volume::inspect(bytes)?));
    }
    if bytes.starts_with(partition::K16PT_MAGIC) {
        return inspect_partitioned_media(bytes);
    }
    if bytes.starts_with(k16fs::K16FS_MAGIC) {
        return inspect_k16fs(bytes);
    }
    if bytes.starts_with(k16e::K16E_MAGIC) {
        return inspect_k16e(bytes);
    }
    Err(format!(
        "unrecognized K16 blob magic: {}",
        first_bytes_hex(bytes)
    ))
}

fn inspect_partitioned_media(bytes: &[u8]) -> Result<String, String> {
    if bytes.len() % partition::K16PT_BLOCK_SIZE != 0 {
        return Err("K16PT media size is not block-aligned".to_string());
    }
    let total_blocks = u32::try_from(bytes.len() / partition::K16PT_BLOCK_SIZE)
        .map_err(|_| "K16PT media block count does not fit u32".to_string())?;
    let table_block = bytes
        .get(..partition::K16PT_BLOCK_SIZE)
        .ok_or_else(|| "K16PT media is too small for the table block".to_string())?;
    let table = partition::decode_partition_table(table_block)?;
    partition::validate_partition_table(&table, total_blocks)?;

    let mut output = format!(
        "kind=K16PT\nK16PT v{} entries={} media_blocks={total_blocks}\n",
        partition::K16PT_VERSION,
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

fn inspect_k16fs(bytes: &[u8]) -> Result<String, String> {
    let superblock = k16fs::decode_superblock(bytes)?;
    Ok(format!(
        "kind=K16FS\nK16FS v{} blocks={} block_size={} root_inode={} inode_table_blocks={}\n",
        k16fs::K16FS_VERSION,
        superblock.total_blocks,
        superblock.block_size,
        superblock.root_inode_id,
        superblock.inode_table_block_count
    ))
}

fn inspect_k16e(bytes: &[u8]) -> Result<String, String> {
    match k16e_version(bytes)? {
        k16e::K16E_VERSION => inspect_fixed_k16e(bytes),
        k16e::K16E_DYNAMIC_VERSION
        | k16e::K16E_DYNAMIC_RUNTIME_VERSION
        | k16e::K16E_DYNAMIC_IMPORTS_VERSION => inspect_dynamic_k16e(bytes),
        k16e::K16E_SHARED_OBJECT_VERSION => inspect_shared_object_k16e(bytes),
        version => Err(format!("unsupported K16E version {version}")),
    }
}

fn inspect_fixed_k16e(bytes: &[u8]) -> Result<String, String> {
    let executable = k16e::decode_k16_executable(bytes)?;
    Ok(format!(
        "kind=K16E\nK16E abi={} entry_pc={:#010x} load_addr={:#010x} payload_bytes={}\n",
        k16e_abi_name(executable.abi_kind),
        executable.entry_pc,
        executable.load_addr,
        executable.payload.len()
    ))
}

fn inspect_dynamic_k16e(bytes: &[u8]) -> Result<String, String> {
    let program = k16e::decode_dynamic_k16_program(bytes)?;
    let relocation_bytes = program
        .relocations
        .len()
        .checked_mul(k16e::K16E_RELOCATION_RECORD_SIZE as usize)
        .ok_or_else(|| "K16E relocation byte count overflows".to_string())?;
    let Some(cpu_helper_runtime) = program.cpu_helper_runtime else {
        if !program.needed_libraries.is_empty() || !program.import_relocations.is_empty() {
            let import_bytes = program
                .import_relocations
                .iter()
                .try_fold(0usize, |total, relocation| {
                    total
                        .checked_add(k16e::K16E_IMPORT_RELOCATION_RECORD_SIZE as usize)
                        .and_then(|value| value.checked_add(relocation.symbol.len()))
                        .and_then(|value| value.checked_add(1))
                })
                .ok_or_else(|| "K16E import relocation byte count overflows".to_string())?;
            let import_bytes = if import_bytes % 2 == 0 {
                import_bytes
            } else {
                import_bytes + 1
            };
            return Ok(format!(
                "kind=K16E\nK16E abi=program dynamic=true entry_offset={:#010x} payload_bytes={} memory_bytes={} relocations={} relocation_bytes={} needed={} imports={} import_bytes={}\n",
                program.entry_offset,
                program.payload.len(),
                program.memory_size,
                program.relocations.len(),
                relocation_bytes,
                program.needed_libraries.len(),
                program.import_relocations.len(),
                import_bytes
            ));
        }
        return Ok(format!(
            "kind=K16E\nK16E abi=program dynamic=true entry_offset={:#010x} payload_bytes={} memory_bytes={} relocations={} relocation_bytes={}\n",
            program.entry_offset,
            program.payload.len(),
            program.memory_size,
            program.relocations.len(),
            relocation_bytes
        ));
    };
    let cpu_helper_relocation_bytes = program
        .cpu_helper_relocations
        .len()
        .checked_mul(k16e::K16E_CPU_HELPER_RELOCATION_RECORD_SIZE as usize)
        .ok_or_else(|| "K16E CPU helper relocation byte count overflows".to_string())?;
    Ok(format!(
        "kind=K16E\nK16E abi=program dynamic=true entry_offset={:#010x} payload_bytes={} memory_bytes={} relocations={} relocation_bytes={} cpu_helper_runtime_abi={} cpu_helper_table={} cpu_helper_relocations={} cpu_helper_relocation_bytes={}\n",
        program.entry_offset,
        program.payload.len(),
        program.memory_size,
        program.relocations.len(),
        relocation_bytes,
        cpu_helper_runtime.abi_version,
        cpu_helper_runtime.helper_table_version,
        program.cpu_helper_relocations.len(),
        cpu_helper_relocation_bytes
    ))
}

fn inspect_shared_object_k16e(bytes: &[u8]) -> Result<String, String> {
    let shared = k16e::decode_k16_shared_object(bytes)?;
    let export_bytes = shared
        .exports
        .iter()
        .try_fold(0usize, |total, export| {
            total
                .checked_add(k16e::K16E_SHARED_EXPORT_RECORD_SIZE as usize)
                .and_then(|value| value.checked_add(export.name.len()))
                .and_then(|value| value.checked_add(1))
        })
        .ok_or_else(|| "K16E shared export byte count overflows".to_string())?;
    let export_bytes = if export_bytes % 2 == 0 {
        export_bytes
    } else {
        export_bytes + 1
    };
    Ok(format!(
        "kind=K16E\nK16E abi=shared-object dynamic=true payload_bytes={} memory_bytes={} exports={} export_bytes={}\n",
        shared.payload.len(),
        shared.memory_size,
        shared.exports.len(),
        export_bytes
    ))
}

fn k16e_abi_name(abi_kind: k16e::K16eAbiKind) -> &'static str {
    match abi_kind {
        k16e::K16eAbiKind::Bootloader => "bootloader",
        k16e::K16eAbiKind::Kernel => "kernel",
        k16e::K16eAbiKind::Program => "program",
        k16e::K16eAbiKind::SharedObject => "shared-object",
    }
}

fn partition_bytes(blocks: u32) -> Result<usize, String> {
    let bytes = blocks
        .checked_mul(partition::K16PT_BLOCK_SIZE as u32)
        .ok_or_else(|| "K16PT partition byte range overflows".to_string())?;
    usize::try_from(bytes).map_err(|_| "K16PT partition byte range does not fit usize".to_string())
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

fn k16e_version(bytes: &[u8]) -> Result<u16, String> {
    let version = bytes
        .get(4..6)
        .ok_or_else(|| "K16E version field is truncated".to_string())?;
    Ok(u16::from_le_bytes(version.try_into().unwrap()))
}
