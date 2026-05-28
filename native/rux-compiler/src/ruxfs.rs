pub const RUXFS_MAGIC: &[u8; 5] = b"RUXFS";
pub const RUXFS_VERSION: u8 = 1;
pub const RUXFS_BLOCK_SIZE: usize = 512;
pub const RUXFS_INODE_SIZE: u32 = 64;
pub const RUXFS_DEFAULT_INODE_COUNT: u32 = 64;
pub const RUXFS_MAX_INLINE_EXTENTS: usize = 4;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuxFsSuperblock {
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
            _ => Err(format!("unsupported RuxFS inode state {value}")),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RuxFsExtent {
    pub start_block: u32,
    pub block_count: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuxFsInode {
    pub state: InodeState,
    pub flags: u32,
    pub size_bytes: u64,
    pub extents: Vec<RuxFsExtent>,
}

pub fn format_empty_filesystem(total_blocks: u32) -> Result<Vec<u8>, String> {
    if total_blocks < 16 {
        return Err("RuxFS requires at least 16 blocks".to_string());
    }
    let bitmap_block_count = bitmap_blocks_for(total_blocks)?;
    let inode_table_block_count = inode_table_blocks_for(RUXFS_DEFAULT_INODE_COUNT)?;
    let bitmap_start_block = 1;
    let inode_table_start_block = bitmap_start_block + bitmap_block_count;
    let root_dir_block = inode_table_start_block
        .checked_add(inode_table_block_count)
        .ok_or_else(|| "RuxFS metadata range overflows".to_string())?;
    if root_dir_block >= total_blocks {
        return Err("RuxFS metadata does not fit in filesystem".to_string());
    }
    let superblock = RuxFsSuperblock {
        block_size: RUXFS_BLOCK_SIZE as u32,
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
    let root_inode = RuxFsInode {
        state: InodeState::Directory,
        flags: 0,
        size_bytes: 0,
        extents: vec![RuxFsExtent {
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

pub fn decode_superblock(image: &[u8]) -> Result<RuxFsSuperblock, String> {
    if image.len() < RUXFS_BLOCK_SIZE {
        return Err("RuxFS superblock is truncated".to_string());
    }
    if &image[0..5] != RUXFS_MAGIC {
        return Err("invalid RUXFS magic".to_string());
    }
    let version = image[5];
    if version != RUXFS_VERSION {
        return Err(format!("unsupported RUXFS version {version}"));
    }
    if image[6] != 0 || image[7] != 0 {
        return Err("RuxFS reserved superblock bytes must be zero".to_string());
    }
    Ok(RuxFsSuperblock {
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
    superblock: &RuxFsSuperblock,
    inode_id: u32,
) -> Result<RuxFsInode, String> {
    let offset = inode_offset(superblock, inode_id)?;
    let bytes = image
        .get(offset..offset + RUXFS_INODE_SIZE as usize)
        .ok_or_else(|| format!("RuxFS inode {inode_id} is outside filesystem"))?;
    let state = InodeState::from_byte(bytes[0])?;
    let flags = read_u32(bytes, 0x04)?;
    let size_bytes = read_u64(bytes, 0x08)?;
    let extent_count = bytes[0x10] as usize;
    if extent_count > RUXFS_MAX_INLINE_EXTENTS {
        return Err(format!(
            "RuxFS inode {inode_id} has unsupported extent count"
        ));
    }
    let mut extents = Vec::with_capacity(extent_count);
    for index in 0..extent_count {
        let offset = 0x20 + index * 8;
        let start_block = read_u32(bytes, offset)?;
        let block_count = read_u32(bytes, offset + 4)?;
        extents.push(RuxFsExtent {
            start_block,
            block_count,
        });
    }
    Ok(RuxFsInode {
        state,
        flags,
        size_bytes,
        extents,
    })
}

pub fn validate_filesystem(image: &[u8]) -> Result<(), String> {
    let superblock = decode_superblock(image)?;
    if superblock.block_size != RUXFS_BLOCK_SIZE as u32 {
        return Err(format!(
            "unsupported RuxFS block size {}",
            superblock.block_size
        ));
    }
    let expected_len = filesystem_len(superblock.total_blocks)?;
    if image.len() != expected_len {
        return Err(format!(
            "RuxFS superblock declares {} blocks but image has {} bytes",
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
        return Err("RuxFS bitmap overlaps inode table".to_string());
    }
    let inode_capacity = inode_capacity(&superblock)?;
    if superblock.root_inode_id >= inode_capacity {
        return Err("RuxFS root inode is outside inode table".to_string());
    }
    let root = decode_inode(image, &superblock, superblock.root_inode_id)?;
    if root.state != InodeState::Directory {
        return Err("RuxFS root inode is not a directory".to_string());
    }
    validate_inode_extents(&root, &superblock, "root inode")?;
    Ok(())
}

fn encode_superblock(image: &mut [u8], superblock: &RuxFsSuperblock) -> Result<(), String> {
    if image.len() < RUXFS_BLOCK_SIZE {
        return Err("RuxFS image is too small for superblock".to_string());
    }
    image[0..5].copy_from_slice(RUXFS_MAGIC);
    image[5] = RUXFS_VERSION;
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
    superblock: &RuxFsSuperblock,
    inode_id: u32,
    inode: &RuxFsInode,
) -> Result<(), String> {
    if inode.extents.len() > RUXFS_MAX_INLINE_EXTENTS {
        return Err(format!(
            "RuxFS supports at most {RUXFS_MAX_INLINE_EXTENTS} inline extents"
        ));
    }
    let offset = inode_offset(superblock, inode_id)?;
    let bytes = image
        .get_mut(offset..offset + RUXFS_INODE_SIZE as usize)
        .ok_or_else(|| format!("RuxFS inode {inode_id} is outside filesystem"))?;
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

fn mark_allocated(
    image: &mut [u8],
    superblock: &RuxFsSuperblock,
    block: u32,
) -> Result<(), String> {
    if block >= superblock.total_blocks {
        return Err(format!("RuxFS block {block} is outside filesystem"));
    }
    let bitmap_offset = superblock.bitmap_start_block as usize * RUXFS_BLOCK_SIZE;
    let byte_offset = bitmap_offset + (block as usize / 8);
    let bit = block as u8 % 8;
    let byte = image
        .get_mut(byte_offset)
        .ok_or_else(|| "RuxFS bitmap is outside filesystem".to_string())?;
    *byte |= 1_u8 << bit;
    Ok(())
}

fn bitmap_blocks_for(total_blocks: u32) -> Result<u32, String> {
    let bits_per_block = (RUXFS_BLOCK_SIZE * 8) as u32;
    Ok(total_blocks.div_ceil(bits_per_block).max(1))
}

fn inode_table_blocks_for(inode_count: u32) -> Result<u32, String> {
    let bytes = inode_count
        .checked_mul(RUXFS_INODE_SIZE)
        .ok_or_else(|| "RuxFS inode table size overflows".to_string())?;
    Ok(bytes.div_ceil(RUXFS_BLOCK_SIZE as u32))
}

fn inode_capacity(superblock: &RuxFsSuperblock) -> Result<u32, String> {
    let bytes = superblock
        .inode_table_block_count
        .checked_mul(RUXFS_BLOCK_SIZE as u32)
        .ok_or_else(|| "RuxFS inode table size overflows".to_string())?;
    Ok(bytes / RUXFS_INODE_SIZE)
}

fn inode_offset(superblock: &RuxFsSuperblock, inode_id: u32) -> Result<usize, String> {
    let capacity = inode_capacity(superblock)?;
    if inode_id >= capacity {
        return Err(format!("RuxFS inode {inode_id} is outside inode table"));
    }
    let base = superblock.inode_table_start_block as usize * RUXFS_BLOCK_SIZE;
    Ok(base + inode_id as usize * RUXFS_INODE_SIZE as usize)
}

fn validate_inode_extents(
    inode: &RuxFsInode,
    superblock: &RuxFsSuperblock,
    label: &str,
) -> Result<(), String> {
    for extent in &inode.extents {
        if extent.block_count == 0 {
            return Err(format!("RuxFS {label} has zero-sized extent"));
        }
        validate_range(
            extent.start_block,
            extent.block_count,
            superblock.total_blocks,
            label,
        )?;
        if range_overlaps_metadata(extent.start_block, extent.block_count, superblock) {
            return Err(format!("RuxFS {label} extent overlaps metadata"));
        }
    }
    Ok(())
}

fn range_overlaps_metadata(start: u32, count: u32, superblock: &RuxFsSuperblock) -> bool {
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
        return Err(format!("RuxFS {label} range is empty"));
    }
    let end = start
        .checked_add(count)
        .ok_or_else(|| format!("RuxFS {label} range overflows"))?;
    if end > total_blocks {
        return Err(format!("RuxFS {label} outside filesystem"));
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
        return Err(format!("RuxFS {label} overlaps superblock"));
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
        .checked_mul(RUXFS_BLOCK_SIZE as u32)
        .map(|bytes| bytes as usize)
        .ok_or_else(|| "RuxFS image size overflows".to_string())
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, String> {
    let value = bytes
        .get(offset..offset + 4)
        .ok_or_else(|| "RuxFS structure is truncated".to_string())?;
    Ok(u32::from_le_bytes(value.try_into().unwrap()))
}

fn read_u64(bytes: &[u8], offset: usize) -> Result<u64, String> {
    let value = bytes
        .get(offset..offset + 8)
        .ok_or_else(|| "RuxFS structure is truncated".to_string())?;
    Ok(u64::from_le_bytes(value.try_into().unwrap()))
}

fn write_u32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

fn write_u64(bytes: &mut [u8], offset: usize, value: u64) {
    bytes[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}
