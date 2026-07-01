const K16PT_MAGIC: &[u8; 5] = b"K16PT";
const K16PT_VERSION: u8 = 1;
const K16PT_BLOCK_SIZE: usize = 512;
const K16PT_HEADER_SIZE: usize = 16;
const K16PT_ENTRY_SIZE: usize = 32;
const K16PT_MAX_ENTRIES: usize = (K16PT_BLOCK_SIZE - K16PT_HEADER_SIZE) / K16PT_ENTRY_SIZE;

const KFS_MAGIC: &[u8; 5] = b"KFS\0\0";
const KFS_VERSION: u8 = 1;
const KFS_BLOCK_SIZE: usize = 512;
const KFS_INODE_SIZE: usize = 64;
const KFS_DIRECTORY_ENTRY_SIZE: usize = 64;
const KFS_MAX_NAME_BYTES: usize = 56;

#[derive(Debug, Clone, PartialEq, Eq)]
struct PartitionEntry {
    partition_type: String,
    start_lba: u32,
    block_count: u32,
    name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct KfsSuperblock {
    inode_table_start_block: u32,
    inode_table_block_count: u32,
    root_inode_id: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum InodeState {
    Free,
    File,
    Directory,
    Deleted,
}

impl InodeState {
    fn decode(value: u8) -> Result<Self, String> {
        match value {
            0 => Ok(Self::Free),
            1 => Ok(Self::File),
            2 => Ok(Self::Directory),
            3 => Ok(Self::Deleted),
            _ => Err(format!("unsupported KFS inode state {value}")),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct KfsExtent {
    start_block: u32,
    block_count: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct KfsInode {
    state: InodeState,
    size_bytes: u64,
    extents: Vec<KfsExtent>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct DirectoryEntry {
    inode_id: u32,
    name: String,
}

pub fn read_kfs_file_from_partition(
    storage_media: &[u8],
    partition: &str,
    path: &str,
) -> Result<Vec<u8>, String> {
    let partition = partition_payload(storage_media, partition)?;
    read_kfs_file(partition, path)
}

fn partition_payload<'a>(storage_media: &'a [u8], selector: &str) -> Result<&'a [u8], String> {
    if storage_media.len() % K16PT_BLOCK_SIZE != 0 {
        return Err("storage media size is not block-aligned".to_string());
    }
    let table_block = storage_media
        .get(..K16PT_BLOCK_SIZE)
        .ok_or_else(|| "storage media is too small for K16PT".to_string())?;
    let entries = decode_partition_table(table_block)?;
    let total_blocks = u32::try_from(storage_media.len() / K16PT_BLOCK_SIZE)
        .map_err(|_| "storage media block count does not fit u32".to_string())?;
    validate_partition_table(&entries, total_blocks)?;
    let entry = entries
        .iter()
        .find(|entry| entry.partition_type == selector || entry.name == selector)
        .ok_or_else(|| format!("K16PT partition `{selector}` not found"))?;
    let start = block_offset(entry.start_lba)?;
    let len = block_offset(entry.block_count)?;
    let end = start
        .checked_add(len)
        .ok_or_else(|| format!("K16PT partition `{}` byte range overflows", entry.name))?;
    storage_media
        .get(start..end)
        .ok_or_else(|| format!("K16PT partition `{}` is outside media bounds", entry.name))
}

fn decode_partition_table(bytes: &[u8]) -> Result<Vec<PartitionEntry>, String> {
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
    if read_u32(bytes, 8)? != 0 {
        return Err("K16PT table_lba must be 0".to_string());
    }
    if read_u32(bytes, 12)? != 1 {
        return Err("K16PT table_blocks must be 1".to_string());
    }

    let mut entries = Vec::with_capacity(entry_count);
    for index in 0..entry_count {
        let offset = K16PT_HEADER_SIZE + index * K16PT_ENTRY_SIZE;
        let partition_type = std::str::from_utf8(&bytes[offset..offset + 4])
            .map_err(|_| "K16PT partition type must be UTF-8".to_string())?
            .to_string();
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
            start_lba,
            block_count,
            name,
        });
    }
    Ok(entries)
}

fn validate_partition_table(entries: &[PartitionEntry], total_blocks: u32) -> Result<(), String> {
    let mut ranges = Vec::with_capacity(entries.len());
    for entry in entries {
        if entry.block_count == 0 {
            return Err(format!("K16PT partition `{}` has zero size", entry.name));
        }
        if entry.start_lba < 1 {
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

fn read_kfs_file(image: &[u8], path: &str) -> Result<Vec<u8>, String> {
    let components = parse_absolute_path(path)?;
    if components.is_empty() {
        return Err("KFS file path must not be root".to_string());
    }
    let superblock = decode_superblock(image)?;
    let inode_id = find_path_inode(image, &superblock, &components)?;
    let inode = decode_inode(image, &superblock, inode_id)?;
    if inode.state != InodeState::File {
        return Err(format!("KFS path `{path}` is not a file"));
    }
    read_inode_bytes(image, &inode)
}

fn decode_superblock(image: &[u8]) -> Result<KfsSuperblock, String> {
    if image.len() < KFS_BLOCK_SIZE {
        return Err("KFS superblock is truncated".to_string());
    }
    if &image[0..5] != KFS_MAGIC {
        return Err("invalid KFS magic".to_string());
    }
    let version = image[5];
    if version != KFS_VERSION {
        return Err(format!("unsupported KFS version {version}"));
    }
    if image[6] != 0 || image[7] != 0 {
        return Err("KFS reserved superblock bytes must be zero".to_string());
    }
    let block_size = read_u32(image, 0x08)?;
    if block_size != KFS_BLOCK_SIZE as u32 {
        return Err(format!("unsupported KFS block size {block_size}"));
    }
    let total_blocks = read_u32(image, 0x0c)?;
    let expected_len = block_offset(total_blocks)?;
    if image.len() != expected_len {
        return Err(format!(
            "KFS superblock declares {total_blocks} blocks but image has {} bytes",
            image.len()
        ));
    }
    let superblock = KfsSuperblock {
        inode_table_start_block: read_u32(image, 0x18)?,
        inode_table_block_count: read_u32(image, 0x1c)?,
        root_inode_id: read_u32(image, 0x20)?,
    };
    let root = decode_inode(image, &superblock, superblock.root_inode_id)?;
    if root.state != InodeState::Directory {
        return Err("KFS root inode is not a directory".to_string());
    }
    Ok(superblock)
}

fn parse_absolute_path(path: &str) -> Result<Vec<&str>, String> {
    if !path.starts_with('/') {
        return Err("KFS path must be absolute".to_string());
    }
    if path == "/" {
        return Ok(Vec::new());
    }
    if path.ends_with('/') {
        return Err("KFS path must not end with `/`".to_string());
    }
    let mut components = Vec::new();
    for component in path[1..].split('/') {
        if component.is_empty() {
            return Err("KFS path contains an empty component".to_string());
        }
        if component == "." || component == ".." {
            return Err("KFS path component `.` or `..` is unsupported".to_string());
        }
        validate_name(component)?;
        components.push(component);
    }
    Ok(components)
}

fn validate_name(name: &str) -> Result<(), String> {
    if name.is_empty() {
        return Err("KFS name must not be empty".to_string());
    }
    if name.len() > KFS_MAX_NAME_BYTES {
        return Err(format!(
            "KFS name `{name}` is longer than {KFS_MAX_NAME_BYTES} bytes"
        ));
    }
    Ok(())
}

fn find_path_inode(
    image: &[u8],
    superblock: &KfsSuperblock,
    components: &[&str],
) -> Result<u32, String> {
    let mut inode_id = superblock.root_inode_id;
    for component in components {
        let entry = find_directory_entry(image, superblock, inode_id, component)?;
        inode_id = entry.inode_id;
    }
    Ok(inode_id)
}

fn find_directory_entry(
    image: &[u8],
    superblock: &KfsSuperblock,
    directory_inode_id: u32,
    name: &str,
) -> Result<DirectoryEntry, String> {
    read_directory_entries(image, superblock, directory_inode_id)?
        .into_iter()
        .find(|entry| entry.name == name)
        .ok_or_else(|| format!("KFS directory entry `{name}` not found"))
}

fn read_directory_entries(
    image: &[u8],
    superblock: &KfsSuperblock,
    directory_inode_id: u32,
) -> Result<Vec<DirectoryEntry>, String> {
    let directory = decode_inode(image, superblock, directory_inode_id)?;
    if directory.state != InodeState::Directory {
        return Err(format!("KFS inode {directory_inode_id} is not a directory"));
    }
    if directory.size_bytes % KFS_DIRECTORY_ENTRY_SIZE as u64 != 0 {
        return Err(format!(
            "KFS directory inode {directory_inode_id} has unaligned size"
        ));
    }
    let mut entries = Vec::new();
    let mut remaining = directory.size_bytes as usize;
    for extent in &directory.extents {
        for block in extent.start_block..extent.start_block + extent.block_count {
            let mut block_offset = block_offset(block)?;
            for _ in 0..KFS_BLOCK_SIZE / KFS_DIRECTORY_ENTRY_SIZE {
                if remaining == 0 {
                    return Ok(entries);
                }
                let bytes = image
                    .get(block_offset..block_offset + KFS_DIRECTORY_ENTRY_SIZE)
                    .ok_or_else(|| "KFS directory entry is truncated".to_string())?;
                if let Some(entry) = decode_directory_entry(bytes)? {
                    entries.push(entry);
                }
                remaining -= KFS_DIRECTORY_ENTRY_SIZE;
                block_offset += KFS_DIRECTORY_ENTRY_SIZE;
            }
        }
    }
    if remaining != 0 {
        return Err(format!(
            "KFS directory inode {directory_inode_id} size exceeds extents"
        ));
    }
    Ok(entries)
}

fn decode_directory_entry(bytes: &[u8]) -> Result<Option<DirectoryEntry>, String> {
    match bytes[0] {
        0 | 2 => Ok(None),
        1 => {
            let name_len = bytes[1] as usize;
            if name_len == 0 || name_len > KFS_MAX_NAME_BYTES {
                return Err("KFS directory entry has invalid name length".to_string());
            }
            if bytes[2] != 0 || bytes[3] != 0 {
                return Err("KFS directory entry reserved bytes must be zero".to_string());
            }
            let inode_id = read_u32(bytes, 0x04)?;
            let name_bytes = &bytes[0x08..0x08 + name_len];
            let name = std::str::from_utf8(name_bytes)
                .map_err(|_| "KFS directory entry name is not UTF-8".to_string())?
                .to_string();
            validate_name(&name)?;
            Ok(Some(DirectoryEntry { inode_id, name }))
        }
        state => Err(format!("unsupported KFS directory entry state {state}")),
    }
}

fn decode_inode(
    image: &[u8],
    superblock: &KfsSuperblock,
    inode_id: u32,
) -> Result<KfsInode, String> {
    let offset = inode_offset(superblock, inode_id)?;
    let bytes = image
        .get(offset..offset + KFS_INODE_SIZE)
        .ok_or_else(|| format!("KFS inode {inode_id} is outside filesystem"))?;
    let extent_count = bytes[0x10] as usize;
    if extent_count > 4 {
        return Err(format!("KFS inode {inode_id} has unsupported extent count"));
    }
    let mut extents = Vec::with_capacity(extent_count);
    for index in 0..extent_count {
        let offset = 0x20 + index * 8;
        extents.push(KfsExtent {
            start_block: read_u32(bytes, offset)?,
            block_count: read_u32(bytes, offset + 4)?,
        });
    }
    Ok(KfsInode {
        state: InodeState::decode(bytes[0])?,
        size_bytes: read_u64(bytes, 0x08)?,
        extents,
    })
}

fn read_inode_bytes(image: &[u8], inode: &KfsInode) -> Result<Vec<u8>, String> {
    let mut result = Vec::new();
    let mut remaining = inode.size_bytes as usize;
    for extent in &inode.extents {
        let range_start = block_offset(extent.start_block)?;
        let range_len = block_offset(extent.block_count)?;
        let range_end = range_start
            .checked_add(range_len)
            .ok_or_else(|| "KFS block range overflows".to_string())?;
        let bytes = image
            .get(range_start..range_end)
            .ok_or_else(|| "KFS file extent is outside filesystem".to_string())?;
        let take = remaining.min(bytes.len());
        result.extend_from_slice(&bytes[..take]);
        remaining -= take;
        if remaining == 0 {
            return Ok(result);
        }
    }
    Err("KFS file size exceeds extents".to_string())
}

fn inode_offset(superblock: &KfsSuperblock, inode_id: u32) -> Result<usize, String> {
    let capacity = superblock
        .inode_table_block_count
        .checked_mul((KFS_BLOCK_SIZE / KFS_INODE_SIZE) as u32)
        .ok_or_else(|| "KFS inode table size overflows".to_string())?;
    if inode_id >= capacity {
        return Err(format!("KFS inode {inode_id} is outside inode table"));
    }
    let base = block_offset(superblock.inode_table_start_block)?;
    base.checked_add(inode_id as usize * KFS_INODE_SIZE)
        .ok_or_else(|| "KFS inode offset overflows".to_string())
}

fn block_offset(block: u32) -> Result<usize, String> {
    usize::try_from(block)
        .map_err(|_| "block index does not fit usize".to_string())?
        .checked_mul(KFS_BLOCK_SIZE)
        .ok_or_else(|| "block byte offset overflows".to_string())
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, String> {
    let value = bytes
        .get(offset..offset + 4)
        .ok_or_else(|| "u32 field is truncated".to_string())?;
    Ok(u32::from_le_bytes(value.try_into().unwrap()))
}

fn read_u64(bytes: &[u8], offset: usize) -> Result<u64, String> {
    let value = bytes
        .get(offset..offset + 8)
        .ok_or_else(|| "u64 field is truncated".to_string())?;
    Ok(u64::from_le_bytes(value.try_into().unwrap()))
}
