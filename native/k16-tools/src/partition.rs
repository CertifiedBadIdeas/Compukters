pub const K16PT_MAGIC: &[u8; 5] = b"K16PT";
pub const K16PT_VERSION: u8 = 1;
pub const K16PT_BLOCK_SIZE: usize = 512;
pub const K16PT_HEADER_SIZE: usize = 16;
pub const K16PT_ENTRY_SIZE: usize = 32;
pub const K16PT_TABLE_LBA: u32 = 0;
pub const K16PT_TABLE_BLOCKS: u32 = 1;
pub const K16PT_MAX_ENTRIES: usize = (K16PT_BLOCK_SIZE - K16PT_HEADER_SIZE) / K16PT_ENTRY_SIZE;
pub const K16PT_DEFAULT_BOOT_BLOCKS: u32 = 32;

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
                "unsupported K16PT partition type `{}`",
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
pub struct K16PartitionTable {
    pub entries: Vec<PartitionEntry>,
}

pub fn default_boot_root_table(total_blocks: u32) -> Result<K16PartitionTable, String> {
    let root_start = K16PT_TABLE_BLOCKS
        .checked_add(K16PT_DEFAULT_BOOT_BLOCKS)
        .ok_or_else(|| "default BOOT partition range overflows".to_string())?;
    if total_blocks <= root_start {
        return Err(format!(
            "K16PT init requires more than {root_start} blocks for table, BOOT, and ROOT"
        ));
    }
    let table = K16PartitionTable {
        entries: vec![
            PartitionEntry {
                partition_type: PartitionType::Boot,
                flags: 0,
                start_lba: K16PT_TABLE_BLOCKS,
                block_count: K16PT_DEFAULT_BOOT_BLOCKS,
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

pub fn encode_partition_table(table: &K16PartitionTable) -> Result<Vec<u8>, String> {
    if table.entries.len() > K16PT_MAX_ENTRIES {
        return Err(format!(
            "K16PT supports at most {K16PT_MAX_ENTRIES} entries"
        ));
    }
    let mut bytes = vec![0_u8; K16PT_BLOCK_SIZE];
    bytes[0..5].copy_from_slice(K16PT_MAGIC);
    bytes[5] = K16PT_VERSION;
    bytes[6] = u8::try_from(table.entries.len()).map_err(|_| "K16PT entry count overflows u8")?;
    bytes[7] = 0;
    write_u32(&mut bytes, 8, K16PT_TABLE_LBA);
    write_u32(&mut bytes, 12, K16PT_TABLE_BLOCKS);

    for (index, entry) in table.entries.iter().enumerate() {
        let offset = K16PT_HEADER_SIZE + index * K16PT_ENTRY_SIZE;
        let name = entry.name.as_bytes();
        if name.is_empty() || name.len() > 16 {
            return Err("K16PT partition name must be 1..16 bytes".to_string());
        }
        if name.iter().any(|byte| *byte == 0) {
            return Err("K16PT partition name must not contain NUL bytes".to_string());
        }
        bytes[offset..offset + 4].copy_from_slice(entry.partition_type.bytes());
        write_u32(&mut bytes, offset + 4, entry.flags);
        write_u32(&mut bytes, offset + 8, entry.start_lba);
        write_u32(&mut bytes, offset + 12, entry.block_count);
        bytes[offset + 16..offset + 16 + name.len()].copy_from_slice(name);
    }
    Ok(bytes)
}

pub fn decode_partition_table(bytes: &[u8]) -> Result<K16PartitionTable, String> {
    if bytes.len() < K16PT_BLOCK_SIZE {
        return Err("K16PT block is truncated".to_string());
    }
    if &bytes[0..5] != K16PT_MAGIC {
        return Err("invalid K16PT magic".to_string());
    }
    let version = bytes[5];
    if version != K16PT_VERSION {
        return Err(format!("unsupported K16PT version {version}"));
    }
    let entry_count = bytes[6] as usize;
    if bytes[7] != 0 {
        return Err("K16PT reserved header byte must be zero".to_string());
    }
    if entry_count > K16PT_MAX_ENTRIES {
        return Err(format!(
            "K16PT supports at most {K16PT_MAX_ENTRIES} entries"
        ));
    }
    let table_lba = read_u32(bytes, 8)?;
    if table_lba != K16PT_TABLE_LBA {
        return Err(format!("K16PT table_lba must be {K16PT_TABLE_LBA}"));
    }
    let table_blocks = read_u32(bytes, 12)?;
    if table_blocks != K16PT_TABLE_BLOCKS {
        return Err(format!("K16PT table_blocks must be {K16PT_TABLE_BLOCKS}"));
    }

    let mut entries = Vec::with_capacity(entry_count);
    for index in 0..entry_count {
        let offset = K16PT_HEADER_SIZE + index * K16PT_ENTRY_SIZE;
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
            return Err("K16PT partition name must not be empty".to_string());
        }
        let name = std::str::from_utf8(&name_bytes[..name_len])
            .map_err(|_| "K16PT partition name must be UTF-8".to_string())?
            .to_string();
        entries.push(PartitionEntry {
            partition_type,
            flags,
            start_lba,
            block_count,
            name,
        });
    }
    Ok(K16PartitionTable { entries })
}

pub fn validate_partition_table(
    table: &K16PartitionTable,
    total_blocks: u32,
) -> Result<(), String> {
    if table.entries.len() > K16PT_MAX_ENTRIES {
        return Err(format!(
            "K16PT supports at most {K16PT_MAX_ENTRIES} entries"
        ));
    }
    let mut ranges = Vec::with_capacity(table.entries.len());
    for entry in &table.entries {
        if entry.block_count == 0 {
            return Err(format!("K16PT partition `{}` has zero size", entry.name));
        }
        if entry.start_lba < K16PT_TABLE_BLOCKS {
            return Err(format!(
                "K16PT partition `{}` starts inside reserved table area",
                entry.name
            ));
        }
        let end = entry
            .start_lba
            .checked_add(entry.block_count)
            .ok_or_else(|| format!("K16PT partition `{}` range overflows", entry.name))?;
        if end > total_blocks {
            return Err(format!(
                "K16PT partition `{}` is outside media bounds",
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
                "K16PT partition `{left_name}` overlaps partition `{right_name}`"
            ));
        }
    }
    Ok(())
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, String> {
    let value = bytes
        .get(offset..offset + 4)
        .ok_or_else(|| "K16PT block is truncated".to_string())?;
    Ok(u32::from_le_bytes(value.try_into().unwrap()))
}

fn write_u32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}
