pub const RUXPT_MAGIC: &[u8; 5] = b"RUXPT";
pub const RUXPT_VERSION: u8 = 1;
pub const RUXPT_BLOCK_SIZE: usize = 512;
pub const RUXPT_HEADER_SIZE: usize = 16;
pub const RUXPT_ENTRY_SIZE: usize = 32;
pub const RUXPT_TABLE_LBA: u32 = 0;
pub const RUXPT_TABLE_BLOCKS: u32 = 1;
pub const RUXPT_MAX_ENTRIES: usize = (RUXPT_BLOCK_SIZE - RUXPT_HEADER_SIZE) / RUXPT_ENTRY_SIZE;
pub const RUXPT_DEFAULT_BOOT_BLOCKS: u32 = 32;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PartitionType {
    Boot,
    Root,
}

impl PartitionType {
    pub fn tag(self) -> &'static str {
        match self {
            PartitionType::Boot => "BOOT",
            PartitionType::Root => "ROOT",
        }
    }

    fn bytes(self) -> &'static [u8; 4] {
        match self {
            PartitionType::Boot => b"BOOT",
            PartitionType::Root => b"ROOT",
        }
    }

    fn from_bytes(bytes: &[u8]) -> Result<Self, String> {
        match bytes {
            b"BOOT" => Ok(PartitionType::Boot),
            b"ROOT" => Ok(PartitionType::Root),
            _ => Err(format!(
                "unsupported RUXPT partition type `{}`",
                String::from_utf8_lossy(bytes)
            )),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PartitionEntry {
    pub partition_type: PartitionType,
    pub flags: u32,
    pub start_lba: u32,
    pub block_count: u32,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuxPartitionTable {
    pub entries: Vec<PartitionEntry>,
}

pub fn default_boot_root_table(total_blocks: u32) -> Result<RuxPartitionTable, String> {
    let root_start = RUXPT_TABLE_BLOCKS
        .checked_add(RUXPT_DEFAULT_BOOT_BLOCKS)
        .ok_or_else(|| "default BOOT partition range overflows".to_string())?;
    if total_blocks <= root_start {
        return Err(format!(
            "RUXPT init requires more than {root_start} blocks for table, BOOT, and ROOT"
        ));
    }
    let table = RuxPartitionTable {
        entries: vec![
            PartitionEntry {
                partition_type: PartitionType::Boot,
                flags: 0,
                start_lba: RUXPT_TABLE_BLOCKS,
                block_count: RUXPT_DEFAULT_BOOT_BLOCKS,
                name: "boot".to_string(),
            },
            PartitionEntry {
                partition_type: PartitionType::Root,
                flags: 0,
                start_lba: root_start,
                block_count: total_blocks - root_start,
                name: "root".to_string(),
            },
        ],
    };
    validate_partition_table(&table, total_blocks)?;
    Ok(table)
}

pub fn encode_partition_table(table: &RuxPartitionTable) -> Result<Vec<u8>, String> {
    if table.entries.len() > RUXPT_MAX_ENTRIES {
        return Err(format!(
            "RUXPT supports at most {RUXPT_MAX_ENTRIES} entries"
        ));
    }
    let mut bytes = vec![0_u8; RUXPT_BLOCK_SIZE];
    bytes[0..5].copy_from_slice(RUXPT_MAGIC);
    bytes[5] = RUXPT_VERSION;
    bytes[6] = u8::try_from(table.entries.len()).map_err(|_| "RUXPT entry count overflows u8")?;
    bytes[7] = 0;
    write_u32(&mut bytes, 8, RUXPT_TABLE_LBA);
    write_u32(&mut bytes, 12, RUXPT_TABLE_BLOCKS);

    for (index, entry) in table.entries.iter().enumerate() {
        let offset = RUXPT_HEADER_SIZE + index * RUXPT_ENTRY_SIZE;
        let name = entry.name.as_bytes();
        if name.is_empty() || name.len() > 16 {
            return Err("RUXPT partition name must be 1..16 bytes".to_string());
        }
        if name.iter().any(|byte| *byte == 0) {
            return Err("RUXPT partition name must not contain NUL bytes".to_string());
        }
        bytes[offset..offset + 4].copy_from_slice(entry.partition_type.bytes());
        write_u32(&mut bytes, offset + 4, entry.flags);
        write_u32(&mut bytes, offset + 8, entry.start_lba);
        write_u32(&mut bytes, offset + 12, entry.block_count);
        bytes[offset + 16..offset + 16 + name.len()].copy_from_slice(name);
    }
    Ok(bytes)
}

pub fn decode_partition_table(bytes: &[u8]) -> Result<RuxPartitionTable, String> {
    if bytes.len() < RUXPT_BLOCK_SIZE {
        return Err("RUXPT block is truncated".to_string());
    }
    if &bytes[0..5] != RUXPT_MAGIC {
        return Err("invalid RUXPT magic".to_string());
    }
    let version = bytes[5];
    if version != RUXPT_VERSION {
        return Err(format!("unsupported RUXPT version {version}"));
    }
    let entry_count = bytes[6] as usize;
    if bytes[7] != 0 {
        return Err("RUXPT reserved header byte must be zero".to_string());
    }
    if entry_count > RUXPT_MAX_ENTRIES {
        return Err(format!(
            "RUXPT supports at most {RUXPT_MAX_ENTRIES} entries"
        ));
    }
    let table_lba = read_u32(bytes, 8)?;
    if table_lba != RUXPT_TABLE_LBA {
        return Err(format!("RUXPT table_lba must be {RUXPT_TABLE_LBA}"));
    }
    let table_blocks = read_u32(bytes, 12)?;
    if table_blocks != RUXPT_TABLE_BLOCKS {
        return Err(format!("RUXPT table_blocks must be {RUXPT_TABLE_BLOCKS}"));
    }

    let mut entries = Vec::with_capacity(entry_count);
    for index in 0..entry_count {
        let offset = RUXPT_HEADER_SIZE + index * RUXPT_ENTRY_SIZE;
        let partition_type = PartitionType::from_bytes(&bytes[offset..offset + 4])?;
        let flags = read_u32(bytes, offset + 4)?;
        let start_lba = read_u32(bytes, offset + 8)?;
        let block_count = read_u32(bytes, offset + 12)?;
        let name_bytes = &bytes[offset + 16..offset + 32];
        let name_len = name_bytes
            .iter()
            .position(|byte| *byte == 0)
            .unwrap_or(name_bytes.len());
        if name_len == 0 {
            return Err("RUXPT partition name must not be empty".to_string());
        }
        let name = std::str::from_utf8(&name_bytes[..name_len])
            .map_err(|_| "RUXPT partition name must be UTF-8".to_string())?
            .to_string();
        entries.push(PartitionEntry {
            partition_type,
            flags,
            start_lba,
            block_count,
            name,
        });
    }
    Ok(RuxPartitionTable { entries })
}

pub fn validate_partition_table(
    table: &RuxPartitionTable,
    total_blocks: u32,
) -> Result<(), String> {
    if table.entries.len() > RUXPT_MAX_ENTRIES {
        return Err(format!(
            "RUXPT supports at most {RUXPT_MAX_ENTRIES} entries"
        ));
    }
    let mut ranges = Vec::with_capacity(table.entries.len());
    for entry in &table.entries {
        if entry.block_count == 0 {
            return Err(format!("RUXPT partition `{}` has zero size", entry.name));
        }
        if entry.start_lba < RUXPT_TABLE_BLOCKS {
            return Err(format!(
                "RUXPT partition `{}` starts inside reserved table area",
                entry.name
            ));
        }
        let end = entry
            .start_lba
            .checked_add(entry.block_count)
            .ok_or_else(|| format!("RUXPT partition `{}` range overflows", entry.name))?;
        if end > total_blocks {
            return Err(format!(
                "RUXPT partition `{}` is outside media bounds",
                entry.name
            ));
        }
        ranges.push((entry.start_lba, end, entry.name.as_str()));
    }
    ranges.sort_by_key(|range| range.0);
    for pair in ranges.windows(2) {
        let (_, left_end, left_name) = pair[0];
        let (right_start, _, right_name) = pair[1];
        if left_end > right_start {
            return Err(format!(
                "RUXPT partition `{left_name}` overlaps partition `{right_name}`"
            ));
        }
    }
    Ok(())
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, String> {
    let value = bytes
        .get(offset..offset + 4)
        .ok_or_else(|| "RUXPT block is truncated".to_string())?;
    Ok(u32::from_le_bytes(value.try_into().unwrap()))
}

fn write_u32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}
