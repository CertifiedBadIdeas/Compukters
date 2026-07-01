use std::ops::Range;

pub const KFS_MAGIC: &[u8; 5] = b"KFS\0\0";
pub const KFS_VERSION: u8 = 1;
pub const KFS_BLOCK_SIZE: usize = 512;
pub const KFS_INODE_SIZE: u32 = 64;
pub const KFS_DEFAULT_INODE_COUNT: u32 = 64;
pub const KFS_MAX_INLINE_EXTENTS: usize = 4;
pub const KFS_DIRECTORY_ENTRY_SIZE: usize = 64;
pub const KFS_MAX_NAME_BYTES: usize = 56;
const KFS_NEW_DIRECTORY_BLOCKS: u32 = 2;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KfsSuperblock {
    pub block_size: u32,
    pub total_blocks: u32,
    pub bitmap_start_block: u32,
    pub bitmap_block_count: u32,
    pub inode_table_start_block: u32,
    pub inode_table_block_count: u32,
    pub root_inode_id: u32,
    pub flags: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InodeState {
    Free = 0,
    File = 1,
    Directory = 2,
    Deleted = 3,
}

impl InodeState {
    fn from_byte(value: u8) -> Result<Self, String> {
        match value {
            0 => Ok(InodeState::Free),
            1 => Ok(InodeState::File),
            2 => Ok(InodeState::Directory),
            3 => Ok(InodeState::Deleted),
            _ => Err(format!("unsupported KFS inode state {value}")),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KfsExtent {
    pub start_block: u32,
    pub block_count: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KfsInode {
    pub state: InodeState,
    pub flags: u32,
    pub size_bytes: u64,
    pub extents: Vec<KfsExtent>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct DirectoryEntry {
    inode_id: u32,
    name: String,
}

struct ParsedChildPath<'a> {
    parent_components: Vec<&'a str>,
    name: &'a str,
}

pub fn format_empty_filesystem(total_blocks: u32) -> Result<Vec<u8>, String> {
    if total_blocks < 16 {
        return Err("KFS requires at least 16 blocks".to_string());
    }
    let bitmap_block_count = bitmap_blocks_for(total_blocks)?;
    let inode_table_block_count = inode_table_blocks_for(KFS_DEFAULT_INODE_COUNT)?;
    let bitmap_start_block = 1;
    let inode_table_start_block = bitmap_start_block + bitmap_block_count;
    let root_dir_block = inode_table_start_block
        .checked_add(inode_table_block_count)
        .ok_or_else(|| "KFS metadata range overflows".to_string())?;
    if root_dir_block >= total_blocks {
        return Err("KFS metadata does not fit in filesystem".to_string());
    }
    let superblock = KfsSuperblock {
        block_size: KFS_BLOCK_SIZE as u32,
        total_blocks,
        bitmap_start_block,
        bitmap_block_count,
        inode_table_start_block,
        inode_table_block_count,
        root_inode_id: 1,
        flags: 0,
    };
    let total_len = filesystem_len(total_blocks)?;
    let mut image = vec![0_u8; total_len];
    encode_superblock(&mut image, &superblock)?;
    mark_allocated(&mut image, &superblock, 0)?;
    for block in bitmap_start_block..bitmap_start_block + bitmap_block_count {
        mark_allocated(&mut image, &superblock, block)?;
    }
    for block in inode_table_start_block..inode_table_start_block + inode_table_block_count {
        mark_allocated(&mut image, &superblock, block)?;
    }
    mark_allocated(&mut image, &superblock, root_dir_block)?;
    let root_inode = KfsInode {
        state: InodeState::Directory,
        flags: 0,
        size_bytes: 0,
        extents: vec![KfsExtent {
            start_block: root_dir_block,
            block_count: 1,
        }],
    };
    encode_inode(
        &mut image,
        &superblock,
        superblock.root_inode_id,
        &root_inode,
    )?;
    validate_filesystem(&image)?;
    Ok(image)
}

pub fn create_directory(image: &mut [u8], path: &str) -> Result<(), String> {
    let parsed = parse_parent_path(path)?;
    let superblock = decode_superblock(image)?;
    validate_filesystem(image)?;
    let parent_inode_id = find_directory_inode(image, &superblock, &parsed.parent_components)?;
    ensure_missing_entry(image, &superblock, parent_inode_id, parsed.name)?;
    let inode_id = allocate_inode(image, &superblock)?;
    let directory_block = allocate_contiguous_blocks(image, &superblock, KFS_NEW_DIRECTORY_BLOCKS)?;
    let directory_range = block_range(directory_block, KFS_NEW_DIRECTORY_BLOCKS)?;
    image[directory_range].fill(0);
    let inode = KfsInode {
        state: InodeState::Directory,
        flags: 0,
        size_bytes: 0,
        extents: vec![KfsExtent {
            start_block: directory_block,
            block_count: KFS_NEW_DIRECTORY_BLOCKS,
        }],
    };
    encode_inode(image, &superblock, inode_id, &inode)?;
    append_directory_entry(image, &superblock, parent_inode_id, inode_id, parsed.name)?;
    validate_filesystem(image)
}

pub fn write_file(image: &mut [u8], path: &str, contents: &[u8]) -> Result<(), String> {
    let parsed = parse_parent_path(path)?;
    let superblock = decode_superblock(image)?;
    validate_filesystem(image)?;
    let parent_inode_id = find_directory_inode(image, &superblock, &parsed.parent_components)?;
    ensure_missing_entry(image, &superblock, parent_inode_id, parsed.name)?;
    let block_count = blocks_for_len(contents.len())?;
    let inode_id = allocate_inode(image, &superblock)?;
    let extents = allocate_file_extents(image, &superblock, block_count)?;
    let mut copied = 0;
    for extent in &extents {
        let file_range = block_range(extent.start_block, extent.block_count)?;
        image[file_range.clone()].fill(0);
        let available = file_range.len().min(contents.len().saturating_sub(copied));
        image[file_range.start..file_range.start + available]
            .copy_from_slice(&contents[copied..copied + available]);
        copied += available;
    }
    let inode = KfsInode {
        state: InodeState::File,
        flags: 0,
        size_bytes: contents.len() as u64,
        extents,
    };
    encode_inode(image, &superblock, inode_id, &inode)?;
    append_directory_entry(image, &superblock, parent_inode_id, inode_id, parsed.name)?;
    validate_filesystem(image)
}

pub fn read_file(image: &[u8], path: &str) -> Result<Vec<u8>, String> {
    let parsed = parse_absolute_path(path)?;
    if parsed.is_empty() {
        return Err("KFS file path must not be root".to_string());
    }
    let superblock = decode_superblock(image)?;
    validate_filesystem(image)?;
    let inode_id = find_path_inode(image, &superblock, &parsed)?;
    let inode = decode_inode(image, &superblock, inode_id)?;
    if inode.state != InodeState::File {
        return Err(format!("KFS path `{path}` is not a file"));
    }
    read_inode_bytes(image, &inode)
}

pub fn delete_file(image: &mut [u8], path: &str) -> Result<(), String> {
    let parsed = parse_parent_path(path)?;
    let superblock = decode_superblock(image)?;
    validate_filesystem(image)?;
    let parent_inode_id = find_directory_inode(image, &superblock, &parsed.parent_components)?;
    let entry = find_directory_entry_slot(image, &superblock, parent_inode_id, parsed.name)?;
    let inode = decode_inode(image, &superblock, entry.entry.inode_id)?;
    if inode.state != InodeState::File {
        return Err(format!("KFS path `{path}` is not a file"));
    }

    for extent in &inode.extents {
        let range = block_range(extent.start_block, extent.block_count)?;
        image[range].fill(0);
        for block in extent.start_block..extent.start_block + extent.block_count {
            mark_free(image, &superblock, block)?;
        }
    }
    encode_inode(
        image,
        &superblock,
        entry.entry.inode_id,
        &KfsInode {
            state: InodeState::Deleted,
            flags: 0,
            size_bytes: 0,
            extents: Vec::new(),
        },
    )?;
    encode_deleted_directory_entry(image, entry.slot.image_offset)?;
    validate_filesystem(image)
}

pub fn list_directory(image: &[u8], path: &str) -> Result<Vec<String>, String> {
    let components = parse_absolute_path(path)?;
    let superblock = decode_superblock(image)?;
    validate_filesystem(image)?;
    let inode_id = find_directory_inode(image, &superblock, &components)?;
    let entries = read_directory_entries(image, &superblock, inode_id)?;
    Ok(entries.into_iter().map(|entry| entry.name).collect())
}

pub fn decode_superblock(image: &[u8]) -> Result<KfsSuperblock, String> {
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
    Ok(KfsSuperblock {
        block_size: read_u32(image, 0x08)?,
        total_blocks: read_u32(image, 0x0c)?,
        bitmap_start_block: read_u32(image, 0x10)?,
        bitmap_block_count: read_u32(image, 0x14)?,
        inode_table_start_block: read_u32(image, 0x18)?,
        inode_table_block_count: read_u32(image, 0x1c)?,
        root_inode_id: read_u32(image, 0x20)?,
        flags: read_u32(image, 0x24)?,
    })
}

pub fn decode_inode(
    image: &[u8],
    superblock: &KfsSuperblock,
    inode_id: u32,
) -> Result<KfsInode, String> {
    let offset = inode_offset(superblock, inode_id)?;
    let bytes = image
        .get(offset..offset + KFS_INODE_SIZE as usize)
        .ok_or_else(|| format!("KFS inode {inode_id} is outside filesystem"))?;
    let state = InodeState::from_byte(bytes[0])?;
    let flags = read_u32(bytes, 0x04)?;
    let size_bytes = read_u64(bytes, 0x08)?;
    let extent_count = bytes[0x10] as usize;
    if extent_count > KFS_MAX_INLINE_EXTENTS {
        return Err(format!("KFS inode {inode_id} has unsupported extent count"));
    }
    let mut extents = Vec::with_capacity(extent_count);
    for index in 0..extent_count {
        let offset = 0x20 + index * 8;
        let start_block = read_u32(bytes, offset)?;
        let block_count = read_u32(bytes, offset + 4)?;
        extents.push(KfsExtent {
            start_block,
            block_count,
        });
    }
    Ok(KfsInode {
        state,
        flags,
        size_bytes,
        extents,
    })
}

pub fn validate_filesystem(image: &[u8]) -> Result<(), String> {
    let superblock = decode_superblock(image)?;
    if superblock.block_size != KFS_BLOCK_SIZE as u32 {
        return Err(format!(
            "unsupported KFS block size {}",
            superblock.block_size
        ));
    }
    let expected_len = filesystem_len(superblock.total_blocks)?;
    if image.len() != expected_len {
        return Err(format!(
            "KFS superblock declares {} blocks but image has {} bytes",
            superblock.total_blocks,
            image.len()
        ));
    }
    validate_metadata_range(
        superblock.bitmap_start_block,
        superblock.bitmap_block_count,
        superblock.total_blocks,
        "bitmap",
    )?;
    validate_metadata_range(
        superblock.inode_table_start_block,
        superblock.inode_table_block_count,
        superblock.total_blocks,
        "inode table",
    )?;
    if ranges_overlap(
        superblock.bitmap_start_block,
        superblock.bitmap_block_count,
        superblock.inode_table_start_block,
        superblock.inode_table_block_count,
    ) {
        return Err("KFS bitmap overlaps inode table".to_string());
    }
    let inode_capacity = inode_capacity(&superblock)?;
    if superblock.root_inode_id >= inode_capacity {
        return Err("KFS root inode is outside inode table".to_string());
    }
    let root = decode_inode(image, &superblock, superblock.root_inode_id)?;
    if root.state != InodeState::Directory {
        return Err("KFS root inode is not a directory".to_string());
    }
    validate_allocated_inodes(image, &superblock)?;
    Ok(())
}

fn validate_allocated_inodes(image: &[u8], superblock: &KfsSuperblock) -> Result<(), String> {
    let inode_capacity = inode_capacity(superblock)?;
    for inode_id in 0..inode_capacity {
        let inode = decode_inode(image, superblock, inode_id)?;
        match inode.state {
            InodeState::Free | InodeState::Deleted => {
                if inode.size_bytes != 0 || !inode.extents.is_empty() {
                    return Err(format!("KFS inactive inode {inode_id} carries data"));
                }
            }
            InodeState::File | InodeState::Directory => {
                validate_inode_extents(&inode, superblock, &format!("inode {inode_id}"))?;
                validate_inode_size(&inode, inode_id)?;
            }
        }
        if inode.state == InodeState::Directory {
            validate_directory_entries(image, superblock, inode_id)?;
        }
    }
    Ok(())
}

fn validate_inode_size(inode: &KfsInode, inode_id: u32) -> Result<(), String> {
    let capacity = inode_extent_capacity(inode)?;
    if inode.size_bytes > capacity {
        return Err(format!("KFS inode {inode_id} size exceeds extents"));
    }
    Ok(())
}

fn validate_directory_entries(
    image: &[u8],
    superblock: &KfsSuperblock,
    inode_id: u32,
) -> Result<(), String> {
    let entries = read_directory_entries(image, superblock, inode_id)?;
    let mut names = Vec::new();
    let inode_capacity = inode_capacity(superblock)?;
    for entry in entries {
        if names.iter().any(|name| name == &entry.name) {
            return Err(format!(
                "KFS directory inode {inode_id} has duplicate entry `{}`",
                entry.name
            ));
        }
        names.push(entry.name);
        if entry.inode_id >= inode_capacity {
            return Err(format!(
                "KFS directory inode {inode_id} entry points outside inode table"
            ));
        }
        let target = decode_inode(image, superblock, entry.inode_id)?;
        if matches!(target.state, InodeState::Free | InodeState::Deleted) {
            return Err(format!(
                "KFS directory inode {inode_id} entry points to inactive inode"
            ));
        }
    }
    Ok(())
}

fn encode_superblock(image: &mut [u8], superblock: &KfsSuperblock) -> Result<(), String> {
    if image.len() < KFS_BLOCK_SIZE {
        return Err("KFS image is too small for superblock".to_string());
    }
    image[0..5].copy_from_slice(KFS_MAGIC);
    image[5] = KFS_VERSION;
    image[6] = 0;
    image[7] = 0;
    write_u32(image, 0x08, superblock.block_size);
    write_u32(image, 0x0c, superblock.total_blocks);
    write_u32(image, 0x10, superblock.bitmap_start_block);
    write_u32(image, 0x14, superblock.bitmap_block_count);
    write_u32(image, 0x18, superblock.inode_table_start_block);
    write_u32(image, 0x1c, superblock.inode_table_block_count);
    write_u32(image, 0x20, superblock.root_inode_id);
    write_u32(image, 0x24, superblock.flags);
    Ok(())
}

fn encode_inode(
    image: &mut [u8],
    superblock: &KfsSuperblock,
    inode_id: u32,
    inode: &KfsInode,
) -> Result<(), String> {
    if inode.extents.len() > KFS_MAX_INLINE_EXTENTS {
        return Err(format!(
            "KFS supports at most {KFS_MAX_INLINE_EXTENTS} inline extents"
        ));
    }
    let offset = inode_offset(superblock, inode_id)?;
    let bytes = image
        .get_mut(offset..offset + KFS_INODE_SIZE as usize)
        .ok_or_else(|| format!("KFS inode {inode_id} is outside filesystem"))?;
    bytes[0] = inode.state as u8;
    bytes[1..4].fill(0);
    write_u32(bytes, 0x04, inode.flags);
    write_u64(bytes, 0x08, inode.size_bytes);
    bytes[0x10] = inode.extents.len() as u8;
    bytes[0x11..0x20].fill(0);
    for (index, extent) in inode.extents.iter().enumerate() {
        let offset = 0x20 + index * 8;
        write_u32(bytes, offset, extent.start_block);
        write_u32(bytes, offset + 4, extent.block_count);
    }
    Ok(())
}

fn parse_parent_path(path: &str) -> Result<ParsedChildPath<'_>, String> {
    let mut components = parse_absolute_path(path)?;
    let Some(name) = components.pop() else {
        return Err("KFS path must name an entry".to_string());
    };
    Ok(ParsedChildPath {
        parent_components: components,
        name,
    })
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

fn find_directory_inode(
    image: &[u8],
    superblock: &KfsSuperblock,
    components: &[&str],
) -> Result<u32, String> {
    let inode_id = find_path_inode(image, superblock, components)?;
    let inode = decode_inode(image, superblock, inode_id)?;
    if inode.state != InodeState::Directory {
        return Err("KFS path component is not a directory".to_string());
    }
    Ok(inode_id)
}

fn find_directory_entry(
    image: &[u8],
    superblock: &KfsSuperblock,
    directory_inode_id: u32,
    name: &str,
) -> Result<DirectoryEntry, String> {
    Ok(find_directory_entry_slot(image, superblock, directory_inode_id, name)?.entry)
}

struct DirectoryEntrySlot {
    entry: DirectoryEntry,
    slot: DirectorySlot,
}

fn find_directory_entry_slot(
    image: &[u8],
    superblock: &KfsSuperblock,
    directory_inode_id: u32,
    name: &str,
) -> Result<DirectoryEntrySlot, String> {
    let directory = decode_inode(image, superblock, directory_inode_id)?;
    if directory.state != InodeState::Directory {
        return Err(format!("KFS inode {directory_inode_id} is not a directory"));
    }
    if directory.size_bytes % KFS_DIRECTORY_ENTRY_SIZE as u64 != 0 {
        return Err(format!(
            "KFS directory inode {directory_inode_id} has unaligned size"
        ));
    }
    let mut remaining = directory.size_bytes as usize;
    let mut directory_offset = 0;
    for extent in &directory.extents {
        for block in extent.start_block..extent.start_block + extent.block_count {
            let mut block_offset = block as usize * KFS_BLOCK_SIZE;
            for _ in 0..KFS_BLOCK_SIZE / KFS_DIRECTORY_ENTRY_SIZE {
                if remaining == 0 {
                    return Err(format!("KFS directory entry `{name}` not found"));
                }
                let bytes = image
                    .get(block_offset..block_offset + KFS_DIRECTORY_ENTRY_SIZE)
                    .ok_or_else(|| "KFS directory entry is truncated".to_string())?;
                if let Some(entry) = decode_directory_entry(bytes)? {
                    if entry.name == name {
                        return Ok(DirectoryEntrySlot {
                            entry,
                            slot: DirectorySlot {
                                image_offset: block_offset,
                                directory_offset,
                            },
                        });
                    }
                }
                remaining -= KFS_DIRECTORY_ENTRY_SIZE;
                block_offset += KFS_DIRECTORY_ENTRY_SIZE;
                directory_offset += KFS_DIRECTORY_ENTRY_SIZE;
            }
        }
    }
    Err(format!(
        "KFS directory inode {directory_inode_id} size exceeds extents"
    ))
}

fn ensure_missing_entry(
    image: &[u8],
    superblock: &KfsSuperblock,
    directory_inode_id: u32,
    name: &str,
) -> Result<(), String> {
    if read_directory_entries(image, superblock, directory_inode_id)?
        .iter()
        .any(|entry| entry.name == name)
    {
        return Err(format!("KFS directory entry `{name}` already exists"));
    }
    Ok(())
}

fn append_directory_entry(
    image: &mut [u8],
    superblock: &KfsSuperblock,
    directory_inode_id: u32,
    target_inode_id: u32,
    name: &str,
) -> Result<(), String> {
    validate_name(name)?;
    let mut directory = decode_inode(image, superblock, directory_inode_id)?;
    if directory.state != InodeState::Directory {
        return Err("KFS parent inode is not a directory".to_string());
    }
    let slot =
        find_or_grow_free_directory_slot(image, superblock, directory_inode_id, &mut directory)?;
    encode_directory_entry(image, slot.image_offset, target_inode_id, name)?;
    directory.size_bytes = directory
        .size_bytes
        .max((slot.directory_offset + KFS_DIRECTORY_ENTRY_SIZE) as u64);
    encode_inode(image, superblock, directory_inode_id, &directory)
}

struct DirectorySlot {
    image_offset: usize,
    directory_offset: usize,
}

fn find_or_grow_free_directory_slot(
    image: &mut [u8],
    superblock: &KfsSuperblock,
    directory_inode_id: u32,
    directory: &mut KfsInode,
) -> Result<DirectorySlot, String> {
    match find_free_directory_slot(image, directory) {
        Ok(slot) => Ok(slot),
        Err(error) if error == "KFS directory has no free entries" => {
            let directory_offset = directory_extent_capacity(directory)?;
            let block = grow_directory_capacity(image, superblock, directory_inode_id, directory)?;
            Ok(DirectorySlot {
                image_offset: block as usize * KFS_BLOCK_SIZE,
                directory_offset,
            })
        }
        Err(error) => Err(error),
    }
}

fn find_free_directory_slot(image: &[u8], directory: &KfsInode) -> Result<DirectorySlot, String> {
    let mut directory_offset = 0;
    for extent in &directory.extents {
        for block in extent.start_block..extent.start_block + extent.block_count {
            let block_offset = block as usize * KFS_BLOCK_SIZE;
            for entry_offset in (0..KFS_BLOCK_SIZE).step_by(KFS_DIRECTORY_ENTRY_SIZE) {
                let image_offset = block_offset + entry_offset;
                let state = *image
                    .get(image_offset)
                    .ok_or_else(|| "KFS directory extent is truncated".to_string())?;
                if state != 1 {
                    return Ok(DirectorySlot {
                        image_offset,
                        directory_offset,
                    });
                }
                directory_offset += KFS_DIRECTORY_ENTRY_SIZE;
            }
        }
    }
    Err("KFS directory has no free entries".to_string())
}

fn grow_directory_capacity(
    image: &mut [u8],
    superblock: &KfsSuperblock,
    directory_inode_id: u32,
    directory: &mut KfsInode,
) -> Result<u32, String> {
    let Some(last_extent) = directory.extents.last_mut() else {
        return Err("KFS directory inode has no extents".to_string());
    };
    let grow_block = last_extent
        .start_block
        .checked_add(last_extent.block_count)
        .ok_or_else(|| "KFS directory extent range overflows".to_string())?;
    if grow_block < superblock.total_blocks && !is_block_allocated(image, superblock, grow_block)? {
        mark_allocated(image, superblock, grow_block)?;
        zero_blocks(image, grow_block, 1)?;
        last_extent.block_count = last_extent
            .block_count
            .checked_add(1)
            .ok_or_else(|| "KFS directory extent range overflows".to_string())?;
        encode_inode(image, superblock, directory_inode_id, directory)?;
        return Ok(grow_block);
    }

    if directory.extents.len() >= KFS_MAX_INLINE_EXTENTS {
        return Err(format!(
            "KFS supports at most {KFS_MAX_INLINE_EXTENTS} inline extents"
        ));
    }
    let new_block = allocate_contiguous_blocks(image, superblock, 1)?;
    zero_blocks(image, new_block, 1)?;
    directory.extents.push(KfsExtent {
        start_block: new_block,
        block_count: 1,
    });
    encode_inode(image, superblock, directory_inode_id, directory)?;
    Ok(new_block)
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
            let mut block_offset = block as usize * KFS_BLOCK_SIZE;
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
    let state = bytes[0];
    match state {
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
        _ => Err(format!("unsupported KFS directory entry state {state}")),
    }
}

fn encode_directory_entry(
    image: &mut [u8],
    offset: usize,
    inode_id: u32,
    name: &str,
) -> Result<(), String> {
    validate_name(name)?;
    let bytes = image
        .get_mut(offset..offset + KFS_DIRECTORY_ENTRY_SIZE)
        .ok_or_else(|| "KFS directory entry is outside filesystem".to_string())?;
    bytes.fill(0);
    bytes[0] = 1;
    bytes[1] = name.len() as u8;
    write_u32(bytes, 0x04, inode_id);
    bytes[0x08..0x08 + name.len()].copy_from_slice(name.as_bytes());
    Ok(())
}

fn encode_deleted_directory_entry(image: &mut [u8], offset: usize) -> Result<(), String> {
    let bytes = image
        .get_mut(offset..offset + KFS_DIRECTORY_ENTRY_SIZE)
        .ok_or_else(|| "KFS directory entry is outside filesystem".to_string())?;
    bytes.fill(0);
    bytes[0] = 2;
    Ok(())
}

fn allocate_inode(image: &[u8], superblock: &KfsSuperblock) -> Result<u32, String> {
    let inode_capacity = inode_capacity(superblock)?;
    for inode_id in 1..inode_capacity {
        let inode = decode_inode(image, superblock, inode_id)?;
        if matches!(inode.state, InodeState::Free | InodeState::Deleted) {
            return Ok(inode_id);
        }
    }
    Err("KFS inode table is full".to_string())
}

fn allocate_contiguous_blocks(
    image: &mut [u8],
    superblock: &KfsSuperblock,
    count: u32,
) -> Result<u32, String> {
    if count == 0 {
        return Err("KFS allocation range is empty".to_string());
    }
    let mut run_start = None;
    let mut run_count = 0;
    for block in 1..superblock.total_blocks {
        if is_block_allocated(image, superblock, block)? {
            run_start = None;
            run_count = 0;
            continue;
        }
        if run_start.is_none() {
            run_start = Some(block);
        }
        run_count += 1;
        if run_count == count {
            let start = run_start.expect("run has a start");
            for allocated_block in start..start + count {
                mark_allocated(image, superblock, allocated_block)?;
            }
            return Ok(start);
        }
    }
    Err(format!("KFS cannot allocate {count} contiguous blocks"))
}

fn allocate_file_extents(
    image: &mut [u8],
    superblock: &KfsSuperblock,
    block_count: u32,
) -> Result<Vec<KfsExtent>, String> {
    let extents = plan_file_extents(image, superblock, block_count)?;
    for extent in &extents {
        for block in extent.start_block..extent.start_block + extent.block_count {
            mark_allocated(image, superblock, block)?;
        }
    }
    Ok(extents)
}

fn plan_file_extents(
    image: &[u8],
    superblock: &KfsSuperblock,
    block_count: u32,
) -> Result<Vec<KfsExtent>, String> {
    if block_count == 0 {
        return Err("KFS allocation range is empty".to_string());
    }
    if let Some(run) = find_free_run_at_least(image, superblock, block_count)? {
        return Ok(vec![run]);
    }

    let mut extents = Vec::new();
    let mut remaining = block_count;
    for run in find_free_runs(image, superblock)? {
        if remaining == 0 {
            break;
        }
        if extents.len() == KFS_MAX_INLINE_EXTENTS {
            return Err(format!(
                "KFS cannot allocate {block_count} blocks within {KFS_MAX_INLINE_EXTENTS} inline extents"
            ));
        }
        let allocated = run.block_count.min(remaining);
        extents.push(KfsExtent {
            start_block: run.start_block,
            block_count: allocated,
        });
        remaining -= allocated;
    }

    if remaining != 0 {
        return Err(format!("KFS cannot allocate {block_count} blocks"));
    }
    Ok(extents)
}

fn find_free_run_at_least(
    image: &[u8],
    superblock: &KfsSuperblock,
    block_count: u32,
) -> Result<Option<KfsExtent>, String> {
    Ok(find_free_runs(image, superblock)?
        .into_iter()
        .find(|run| run.block_count >= block_count)
        .map(|run| KfsExtent {
            start_block: run.start_block,
            block_count,
        }))
}

fn find_free_runs(image: &[u8], superblock: &KfsSuperblock) -> Result<Vec<KfsExtent>, String> {
    let mut runs = Vec::new();
    let mut run_start = None;
    let mut run_count = 0;
    for block in 1..superblock.total_blocks {
        if is_block_allocated(image, superblock, block)? {
            if let Some(start_block) = run_start {
                runs.push(KfsExtent {
                    start_block,
                    block_count: run_count,
                });
            }
            run_start = None;
            run_count = 0;
            continue;
        }
        if run_start.is_none() {
            run_start = Some(block);
        }
        run_count += 1;
    }
    if let Some(start_block) = run_start {
        runs.push(KfsExtent {
            start_block,
            block_count: run_count,
        });
    }
    Ok(runs)
}

fn is_block_allocated(
    image: &[u8],
    superblock: &KfsSuperblock,
    block: u32,
) -> Result<bool, String> {
    if block >= superblock.total_blocks {
        return Err(format!("KFS block {block} is outside filesystem"));
    }
    let bitmap_offset = superblock.bitmap_start_block as usize * KFS_BLOCK_SIZE;
    let byte_offset = bitmap_offset + (block as usize / 8);
    let bit = block as u8 % 8;
    let byte = image
        .get(byte_offset)
        .ok_or_else(|| "KFS bitmap is outside filesystem".to_string())?;
    Ok((byte & (1_u8 << bit)) != 0)
}

fn zero_blocks(image: &mut [u8], start_block: u32, block_count: u32) -> Result<(), String> {
    let range = block_range(start_block, block_count)?;
    image[range].fill(0);
    Ok(())
}

fn blocks_for_len(len: usize) -> Result<u32, String> {
    let block_count = len.div_ceil(KFS_BLOCK_SIZE).max(1);
    u32::try_from(block_count).map_err(|_| "KFS file is too large".to_string())
}

fn read_inode_bytes(image: &[u8], inode: &KfsInode) -> Result<Vec<u8>, String> {
    let mut result = Vec::new();
    let mut remaining = inode.size_bytes as usize;
    for extent in &inode.extents {
        let range = block_range(extent.start_block, extent.block_count)?;
        let bytes = image
            .get(range)
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

fn inode_extent_capacity(inode: &KfsInode) -> Result<u64, String> {
    let mut capacity = 0_u64;
    for extent in &inode.extents {
        let bytes = u64::from(extent.block_count)
            .checked_mul(KFS_BLOCK_SIZE as u64)
            .ok_or_else(|| "KFS extent capacity overflows".to_string())?;
        capacity = capacity
            .checked_add(bytes)
            .ok_or_else(|| "KFS inode extent capacity overflows".to_string())?;
    }
    Ok(capacity)
}

fn directory_extent_capacity(directory: &KfsInode) -> Result<usize, String> {
    usize::try_from(inode_extent_capacity(directory)?)
        .map_err(|_| "KFS directory extent capacity overflows".to_string())
}

fn block_range(start_block: u32, block_count: u32) -> Result<Range<usize>, String> {
    let start = start_block
        .checked_mul(KFS_BLOCK_SIZE as u32)
        .ok_or_else(|| "KFS block range overflows".to_string())? as usize;
    let byte_count = block_count
        .checked_mul(KFS_BLOCK_SIZE as u32)
        .ok_or_else(|| "KFS block range overflows".to_string())? as usize;
    let end = start
        .checked_add(byte_count)
        .ok_or_else(|| "KFS block range overflows".to_string())?;
    Ok(start..end)
}

fn mark_allocated(image: &mut [u8], superblock: &KfsSuperblock, block: u32) -> Result<(), String> {
    if block >= superblock.total_blocks {
        return Err(format!("KFS block {block} is outside filesystem"));
    }
    let bitmap_offset = superblock.bitmap_start_block as usize * KFS_BLOCK_SIZE;
    let byte_offset = bitmap_offset + (block as usize / 8);
    let bit = block as u8 % 8;
    let byte = image
        .get_mut(byte_offset)
        .ok_or_else(|| "KFS bitmap is outside filesystem".to_string())?;
    *byte |= 1_u8 << bit;
    Ok(())
}

fn mark_free(image: &mut [u8], superblock: &KfsSuperblock, block: u32) -> Result<(), String> {
    if block >= superblock.total_blocks {
        return Err(format!("KFS block {block} is outside filesystem"));
    }
    if range_overlaps_metadata(block, 1, superblock) {
        return Err("KFS cannot free metadata block".to_string());
    }
    let bitmap_offset = superblock.bitmap_start_block as usize * KFS_BLOCK_SIZE;
    let byte_offset = bitmap_offset + (block as usize / 8);
    let bit = block as u8 % 8;
    let byte = image
        .get_mut(byte_offset)
        .ok_or_else(|| "KFS bitmap is outside filesystem".to_string())?;
    *byte &= !(1_u8 << bit);
    Ok(())
}

fn bitmap_blocks_for(total_blocks: u32) -> Result<u32, String> {
    let bits_per_block = (KFS_BLOCK_SIZE * 8) as u32;
    Ok(total_blocks.div_ceil(bits_per_block).max(1))
}

fn inode_table_blocks_for(inode_count: u32) -> Result<u32, String> {
    let bytes = inode_count
        .checked_mul(KFS_INODE_SIZE)
        .ok_or_else(|| "KFS inode table size overflows".to_string())?;
    Ok(bytes.div_ceil(KFS_BLOCK_SIZE as u32))
}

fn inode_capacity(superblock: &KfsSuperblock) -> Result<u32, String> {
    let bytes = superblock
        .inode_table_block_count
        .checked_mul(KFS_BLOCK_SIZE as u32)
        .ok_or_else(|| "KFS inode table size overflows".to_string())?;
    Ok(bytes / KFS_INODE_SIZE)
}

fn inode_offset(superblock: &KfsSuperblock, inode_id: u32) -> Result<usize, String> {
    let capacity = inode_capacity(superblock)?;
    if inode_id >= capacity {
        return Err(format!("KFS inode {inode_id} is outside inode table"));
    }
    let base = superblock.inode_table_start_block as usize * KFS_BLOCK_SIZE;
    Ok(base + inode_id as usize * KFS_INODE_SIZE as usize)
}

fn validate_inode_extents(
    inode: &KfsInode,
    superblock: &KfsSuperblock,
    label: &str,
) -> Result<(), String> {
    for extent in &inode.extents {
        if extent.block_count == 0 {
            return Err(format!("KFS {label} has zero-sized extent"));
        }
        validate_range(
            extent.start_block,
            extent.block_count,
            superblock.total_blocks,
            label,
        )?;
        if range_overlaps_metadata(extent.start_block, extent.block_count, superblock) {
            return Err(format!("KFS {label} extent overlaps metadata"));
        }
    }
    Ok(())
}

fn range_overlaps_metadata(start: u32, count: u32, superblock: &KfsSuperblock) -> bool {
    ranges_overlap(start, count, 0, 1)
        || ranges_overlap(
            start,
            count,
            superblock.bitmap_start_block,
            superblock.bitmap_block_count,
        )
        || ranges_overlap(
            start,
            count,
            superblock.inode_table_start_block,
            superblock.inode_table_block_count,
        )
}

fn validate_range(start: u32, count: u32, total_blocks: u32, label: &str) -> Result<(), String> {
    if count == 0 {
        return Err(format!("KFS {label} range is empty"));
    }
    let end = start
        .checked_add(count)
        .ok_or_else(|| format!("KFS {label} range overflows"))?;
    if end > total_blocks {
        return Err(format!("KFS {label} outside filesystem"));
    }
    Ok(())
}

fn validate_metadata_range(
    start: u32,
    count: u32,
    total_blocks: u32,
    label: &str,
) -> Result<(), String> {
    validate_range(start, count, total_blocks, label)?;
    if ranges_overlap(start, count, 0, 1) {
        return Err(format!("KFS {label} overlaps superblock"));
    }
    Ok(())
}

fn ranges_overlap(left_start: u32, left_count: u32, right_start: u32, right_count: u32) -> bool {
    let left_end = left_start.saturating_add(left_count);
    let right_end = right_start.saturating_add(right_count);
    left_start < right_end && right_start < left_end
}

fn filesystem_len(total_blocks: u32) -> Result<usize, String> {
    total_blocks
        .checked_mul(KFS_BLOCK_SIZE as u32)
        .map(|bytes| bytes as usize)
        .ok_or_else(|| "KFS image size overflows".to_string())
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, String> {
    let value = bytes
        .get(offset..offset + 4)
        .ok_or_else(|| "KFS structure is truncated".to_string())?;
    Ok(u32::from_le_bytes(value.try_into().unwrap()))
}

fn read_u64(bytes: &[u8], offset: usize) -> Result<u64, String> {
    let value = bytes
        .get(offset..offset + 8)
        .ok_or_else(|| "KFS structure is truncated".to_string())?;
    Ok(u64::from_le_bytes(value.try_into().unwrap()))
}

fn write_u32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

fn write_u64(bytes: &mut [u8], offset: usize, value: u64) {
    bytes[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}
