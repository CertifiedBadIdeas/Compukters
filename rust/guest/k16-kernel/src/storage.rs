use k16_abi::computer::storage0;

pub const SCRATCH_ADDR: u32 = 0x0000_0600;
pub const BLOCK_SIZE: u32 = 512;

const K16PT_MAGIC: &[u8; 5] = b"K16PT";
const K16PT_VERSION: u8 = 1;
const K16PT_HEADER_SIZE: u32 = 16;
const K16PT_ENTRY_SIZE: u32 = 32;
const K16PT_MAX_ENTRIES: u8 = 15;

const K16FS_MAGIC: &[u8; 5] = b"K16FS";
const K16FS_VERSION: u8 = 1;
const K16FS_INODE_SIZE: u32 = 64;
const K16FS_DIRECTORY_ENTRY_SIZE: u32 = 64;
const K16FS_MAX_NAME_BYTES: usize = 56;
const K16FS_MAX_INLINE_EXTENTS: usize = 4;

#[derive(Clone, Copy, PartialEq, Eq)]
pub struct FileMetadata {
    pub inode_id: u32,
    pub size_bytes: u32,
    pub extent_count: u32,
    pub extent_start_blocks: [u32; K16FS_MAX_INLINE_EXTENTS],
    pub extent_block_counts: [u32; K16FS_MAX_INLINE_EXTENTS],
}

#[repr(u32)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PathKind {
    Regular = k16_abi::syscall::FILE_TYPE_REGULAR,
    Directory = k16_abi::syscall::FILE_TYPE_DIRECTORY,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PathMetadata {
    pub kind: PathKind,
    pub size_bytes: u32,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct StorageError {
    code: i32,
}

impl StorageError {
    pub const STORAGE_VERSION: Self = Self { code: 10 };
    pub const INVALID_PARTITION_TABLE: Self = Self { code: 11 };
    pub const PARTITION_NOT_FOUND: Self = Self { code: 12 };
    pub const INVALID_FILESYSTEM: Self = Self { code: 13 };
    pub const PATH_NOT_FOUND: Self = Self { code: 14 };
    pub const STORAGE_TRANSFER: Self = Self { code: 16 };
    pub const STORAGE_BLOCK_SIZE: Self = Self { code: 17 };
    pub const STORAGE_MEDIA: Self = Self { code: 18 };
    pub const OUTPUT_BUFFER_TOO_SMALL: Self = Self { code: 19 };
    pub const OUTPUT_TRANSFER: Self = Self { code: 20 };
    pub const PATH_NOT_EMPTY: Self = Self { code: 21 };
    pub const PATH_EXISTS: Self = Self { code: 22 };

    pub const fn code(self) -> i32 {
        self.code
    }
}

pub trait DirectoryListingSink {
    unsafe fn push_byte(&mut self, byte: u8) -> Result<(), StorageError>;
    fn written(&self) -> u32;
}

pub struct RamDirectoryListingSink {
    dst_addr: u32,
    len: u32,
    written: u32,
}

impl RamDirectoryListingSink {
    pub const fn new(dst_addr: u32, len: u32) -> Self {
        Self {
            dst_addr,
            len,
            written: 0,
        }
    }
}

impl DirectoryListingSink for RamDirectoryListingSink {
    unsafe fn push_byte(&mut self, byte: u8) -> Result<(), StorageError> {
        if self.written >= self.len {
            return Err(StorageError::OUTPUT_BUFFER_TOO_SMALL);
        }
        unsafe { write_u8(self.dst_addr + self.written, byte) };
        self.written += 1;
        Ok(())
    }

    fn written(&self) -> u32 {
        self.written
    }
}

const STATE_PARTITION_START_LBA: u32 = 0x0000_0200;
const STATE_PARTITION_BLOCK_COUNT: u32 = 0x0000_0204;
const STATE_SUPERBLOCK_TOTAL_BLOCKS: u32 = 0x0000_0208;
const STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK: u32 = 0x0000_020c;
const STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT: u32 = 0x0000_0210;
const STATE_SUPERBLOCK_ROOT_INODE_ID: u32 = 0x0000_0214;
const STATE_INODE_STATE: u32 = 0x0000_0218;
const STATE_INODE_SIZE_BYTES: u32 = 0x0000_021c;
const STATE_INODE_EXTENT_COUNT: u32 = 0x0000_0220;
const STATE_INODE_EXTENT_START_BLOCKS: u32 = 0x0000_0224;
const STATE_INODE_EXTENT_BLOCK_COUNTS: u32 = 0x0000_0234;
const STATE_SUPERBLOCK_BITMAP_START_BLOCK: u32 = 0x0000_0244;
const STATE_SUPERBLOCK_BITMAP_BLOCK_COUNT: u32 = 0x0000_0248;
const STATE_SELECTED_INODE_ID: u32 = 0x0000_024c;
const STATE_DIRECTORY_SLOT_BLOCK: u32 = 0x0000_0250;
const STATE_DIRECTORY_SLOT_OFFSET: u32 = 0x0000_0254;
const STATE_DIRECTORY_SLOT_DIRECTORY_OFFSET: u32 = 0x0000_0258;

pub unsafe fn open_file_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
) -> Result<(), StorageError> {
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };
    unsafe { find_file_inode(path)? };
    Ok(())
}

pub unsafe fn read_directory_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
    dst_addr: u32,
    len: u32,
) -> Result<u32, StorageError> {
    let mut sink = RamDirectoryListingSink::new(dst_addr, len);
    unsafe { read_directory_from_storage0_into(partition_type, path, &mut sink) }
}

pub unsafe fn read_directory_from_storage0_into<S: DirectoryListingSink>(
    partition_type: &[u8; 4],
    path: &[&[u8]],
    sink: &mut S,
) -> Result<u32, StorageError> {
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };
    unsafe { find_directory_inode(path)? };
    unsafe { copy_selected_directory_listing_into(sink) }
}

pub unsafe fn stat_path_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
) -> Result<PathMetadata, StorageError> {
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };
    unsafe { find_path_inode(path)? };
    unsafe { selected_path_metadata() }
}

pub unsafe fn open_file_for_write_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
    create: bool,
    truncate: bool,
) -> Result<FileMetadata, StorageError> {
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };
    match unsafe { find_file_inode(path) } {
        Ok(()) => {
            if truncate {
                unsafe { truncate_selected_file()? };
            }
            Ok(unsafe { selected_file_metadata() })
        }
        Err(error) if error == StorageError::PATH_NOT_FOUND && create => unsafe {
            create_empty_file(path)
        },
        Err(error) => Err(error),
    }
}

pub unsafe fn remove_file_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
) -> Result<(), StorageError> {
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };
    let parent_len = path.len() - 1;
    unsafe { find_directory_inode(&path[..parent_len])? };
    let (inode_id, slot_block, slot_offset) =
        unsafe { find_directory_entry_slot(path[parent_len])? };
    unsafe { read_inode(inode_id)? };
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 1 {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    let metadata = unsafe { selected_file_metadata() };
    let mut extent_index = 0;
    while extent_index < metadata.extent_count as usize {
        let start_block = metadata.extent_start_blocks[extent_index];
        let block_count = metadata.extent_block_counts[extent_index];
        validate_extent(start_block, block_count, unsafe {
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
        })?;
        let mut block = start_block;
        while block < start_block + block_count {
            unsafe { clear_scratch_block() };
            unsafe { write_fs_block(block)? };
            unsafe { mark_block_free(block)? };
            block += 1;
        }
        extent_index += 1;
    }
    let deleted = FileMetadata {
        inode_id,
        size_bytes: 0,
        extent_count: 0,
        extent_start_blocks: [0; K16FS_MAX_INLINE_EXTENTS],
        extent_block_counts: [0; K16FS_MAX_INLINE_EXTENTS],
    };
    unsafe { encode_deleted_file_inode(deleted)? };
    unsafe { encode_deleted_directory_entry_at(slot_block, slot_offset) }
}

pub unsafe fn rename_file_from_storage0(
    partition_type: &[u8; 4],
    old_path: &[&[u8]],
    new_path: &[&[u8]],
) -> Result<(), StorageError> {
    if old_path.is_empty() || new_path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };

    let old_parent_len = old_path.len() - 1;
    unsafe { find_directory_inode(&old_path[..old_parent_len])? };
    let (inode_id, old_slot_block, old_slot_offset) =
        unsafe { find_directory_entry_slot(old_path[old_parent_len])? };
    unsafe { read_inode(inode_id)? };
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 1 {
        return Err(StorageError::PATH_NOT_FOUND);
    }

    let new_parent_len = new_path.len() - 1;
    let new_name = new_path[new_parent_len];
    unsafe { find_directory_inode(&new_path[..new_parent_len])? };
    match unsafe { find_directory_entry(new_name) } {
        Ok(_) => return Err(StorageError::PATH_EXISTS),
        Err(error) if error == StorageError::PATH_NOT_FOUND => {}
        Err(error) => return Err(error),
    }
    unsafe { find_selected_directory_free_slot()? };
    let new_parent_inode_id = unsafe { read_u32(STATE_SELECTED_INODE_ID) };
    let new_slot_block = unsafe { read_u32(STATE_DIRECTORY_SLOT_BLOCK) };
    let new_slot_offset = unsafe { read_u32(STATE_DIRECTORY_SLOT_OFFSET) };
    let new_slot_directory_offset = unsafe { read_u32(STATE_DIRECTORY_SLOT_DIRECTORY_OFFSET) };

    unsafe { encode_directory_entry_at(new_slot_block, new_slot_offset, inode_id, new_name)? };
    unsafe { read_inode(new_parent_inode_id)? };
    let new_size = max_u32(
        unsafe { read_u32(STATE_INODE_SIZE_BYTES) },
        new_slot_directory_offset + K16FS_DIRECTORY_ENTRY_SIZE,
    );
    unsafe { encode_selected_inode_size(new_parent_inode_id, new_size)? };
    unsafe { encode_deleted_directory_entry_at(old_slot_block, old_slot_offset) }
}

pub unsafe fn create_directory_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
) -> Result<(), StorageError> {
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };
    unsafe { create_empty_directory(path) }
}

pub unsafe fn remove_directory_from_storage0(
    partition_type: &[u8; 4],
    path: &[&[u8]],
) -> Result<(), StorageError> {
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { read_partition(partition_type)? };
    unsafe { read_superblock()? };
    let parent_len = path.len() - 1;
    unsafe { find_directory_inode(&path[..parent_len])? };
    let (inode_id, slot_block, slot_offset) =
        unsafe { find_directory_entry_slot(path[parent_len])? };
    unsafe { read_inode(inode_id)? };
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2 {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    unsafe { ensure_selected_directory_is_empty()? };
    let metadata = unsafe { selected_file_metadata() };
    let mut extent_index = 0;
    while extent_index < metadata.extent_count as usize {
        let start_block = metadata.extent_start_blocks[extent_index];
        let block_count = metadata.extent_block_counts[extent_index];
        validate_extent(start_block, block_count, unsafe {
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
        })?;
        let mut block = start_block;
        while block < start_block + block_count {
            unsafe { clear_scratch_block() };
            unsafe { write_fs_block(block)? };
            unsafe { mark_block_free(block)? };
            block += 1;
        }
        extent_index += 1;
    }
    unsafe { encode_deleted_directory_inode(metadata)? };
    unsafe { encode_deleted_directory_entry_at(slot_block, slot_offset) }
}

pub unsafe fn copy_ram_to_file_range(
    metadata: FileMetadata,
    file_offset: u32,
    src_addr: u32,
    len: u32,
) -> Result<FileMetadata, StorageError> {
    let range_end = match file_offset.checked_add(len) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    let mut updated = metadata;
    if range_end > file_capacity_bytes(updated)? {
        updated = unsafe { grow_file_capacity(updated, range_end)? };
    }

    let mut copied = 0;
    let mut extent_file_start: u32 = 0;
    let mut extent_index = 0;
    while extent_index < updated.extent_count as usize && copied < len {
        let extent_start_block = updated.extent_start_blocks[extent_index];
        let extent_block_count = updated.extent_block_counts[extent_index];
        let extent_bytes = match extent_block_count.checked_mul(BLOCK_SIZE) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        let extent_file_end = match extent_file_start.checked_add(extent_bytes) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };

        if range_end > extent_file_start && file_offset < extent_file_end {
            let copy_start = max_u32(file_offset, extent_file_start);
            let copy_end = min_u32(range_end, extent_file_end);
            let mut cursor = copy_start;
            while cursor < copy_end {
                let within_extent = cursor - extent_file_start;
                let block_delta = within_extent / BLOCK_SIZE;
                let block_offset = within_extent % BLOCK_SIZE;
                let available = min_u32(BLOCK_SIZE - block_offset, copy_end - cursor);
                unsafe { read_fs_block(extent_start_block + block_delta)? };
                unsafe {
                    copy_ram_to_ram(src_addr + copied, SCRATCH_ADDR + block_offset, available)
                };
                unsafe { write_fs_block(extent_start_block + block_delta)? };
                copied += available;
                cursor += available;
            }
        }

        extent_file_start = extent_file_end;
        extent_index += 1;
    }

    if copied != len {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    if range_end > updated.size_bytes {
        updated.size_bytes = range_end;
    }
    unsafe { encode_file_inode(updated)? };
    Ok(updated)
}

pub unsafe fn selected_file_size() -> u32 {
    unsafe { read_u32(STATE_INODE_SIZE_BYTES) }
}

pub unsafe fn selected_path_metadata() -> Result<PathMetadata, StorageError> {
    let kind = match unsafe { read_u32(STATE_INODE_STATE) as u8 } {
        1 => PathKind::Regular,
        2 => PathKind::Directory,
        _ => return Err(StorageError::INVALID_FILESYSTEM),
    };
    Ok(PathMetadata {
        kind,
        size_bytes: unsafe { selected_file_size() },
    })
}

pub unsafe fn selected_file_metadata() -> FileMetadata {
    let mut extent_start_blocks = [0; K16FS_MAX_INLINE_EXTENTS];
    let mut extent_block_counts = [0; K16FS_MAX_INLINE_EXTENTS];
    let extent_count = unsafe { read_u32(STATE_INODE_EXTENT_COUNT) };
    let mut index = 0;
    while index < K16FS_MAX_INLINE_EXTENTS {
        extent_start_blocks[index] =
            unsafe { read_u32(STATE_INODE_EXTENT_START_BLOCKS + index as u32 * 4) };
        extent_block_counts[index] =
            unsafe { read_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + index as u32 * 4) };
        index += 1;
    }
    FileMetadata {
        inode_id: unsafe { read_u32(STATE_SELECTED_INODE_ID) },
        size_bytes: unsafe { selected_file_size() },
        extent_count,
        extent_start_blocks,
        extent_block_counts,
    }
}

unsafe fn read_partition(partition_type: &[u8; 4]) -> Result<(), StorageError> {
    unsafe { read_storage_block(0)? };
    if !scratch_eq(0, K16PT_MAGIC) || scratch_u8(5) != K16PT_VERSION || scratch_u8(7) != 0 {
        return Err(StorageError::INVALID_PARTITION_TABLE);
    }
    let entry_count = scratch_u8(6);
    if entry_count > K16PT_MAX_ENTRIES || scratch_u32(8) != 0 || scratch_u32(12) != 1 {
        return Err(StorageError::INVALID_PARTITION_TABLE);
    }

    let capacity_high = unsafe { read_u32(storage0::CAPACITY_BLOCKS_HIGH) };
    let capacity_low = unsafe { read_u32(storage0::CAPACITY_BLOCKS_LOW) };
    if capacity_high != 0 {
        return Err(StorageError::INVALID_PARTITION_TABLE);
    }

    let mut index = 0;
    while index < entry_count as u32 {
        let offset = K16PT_HEADER_SIZE + index * K16PT_ENTRY_SIZE;
        let start_lba = scratch_u32(offset + 8);
        let block_count = scratch_u32(offset + 12);
        if scratch_u32(offset + 4) != 0 || start_lba < 1 || block_count == 0 {
            return Err(StorageError::INVALID_PARTITION_TABLE);
        }
        let end_lba = match start_lba.checked_add(block_count) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_PARTITION_TABLE),
        };
        if end_lba > capacity_low {
            return Err(StorageError::INVALID_PARTITION_TABLE);
        }
        if scratch_eq(offset, partition_type) {
            unsafe {
                write_u32(STATE_PARTITION_START_LBA, start_lba);
                write_u32(STATE_PARTITION_BLOCK_COUNT, block_count);
            }
            return Ok(());
        }
        index += 1;
    }
    Err(StorageError::PARTITION_NOT_FOUND)
}

unsafe fn read_superblock() -> Result<(), StorageError> {
    unsafe { read_fs_block(0)? };
    if !scratch_eq(0, K16FS_MAGIC)
        || scratch_u8(5) != K16FS_VERSION
        || scratch_u8(6) != 0
        || scratch_u8(7) != 0
        || unsafe { read_u32(SCRATCH_ADDR + 0x08) } != BLOCK_SIZE
    {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let total_blocks = unsafe { read_u32(SCRATCH_ADDR + 0x0c) };
    if total_blocks == 0 || total_blocks > unsafe { read_u32(STATE_PARTITION_BLOCK_COUNT) } {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let bitmap_start_block = unsafe { read_u32(SCRATCH_ADDR + 0x10) };
    let bitmap_block_count = unsafe { read_u32(SCRATCH_ADDR + 0x14) };
    let inode_table_start_block = unsafe { read_u32(SCRATCH_ADDR + 0x18) };
    let inode_table_block_count = unsafe { read_u32(SCRATCH_ADDR + 0x1c) };
    let root_inode_id = unsafe { read_u32(SCRATCH_ADDR + 0x20) };
    unsafe {
        write_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS, total_blocks);
        write_u32(STATE_SUPERBLOCK_BITMAP_START_BLOCK, bitmap_start_block);
        write_u32(STATE_SUPERBLOCK_BITMAP_BLOCK_COUNT, bitmap_block_count);
        write_u32(
            STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK,
            inode_table_start_block,
        );
        write_u32(
            STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT,
            inode_table_block_count,
        );
        write_u32(STATE_SUPERBLOCK_ROOT_INODE_ID, root_inode_id);
        read_inode(root_inode_id)?;
    }
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(())
}

unsafe fn create_empty_file(path: &[&[u8]]) -> Result<FileMetadata, StorageError> {
    let parent_len = path.len() - 1;
    let name = path[parent_len];
    unsafe { find_directory_inode(&path[..parent_len])? };
    match unsafe { find_directory_entry(name) } {
        Ok(_) => return Err(StorageError::INVALID_FILESYSTEM),
        Err(error) if error == StorageError::PATH_NOT_FOUND => {}
        Err(error) => return Err(error),
    }
    unsafe { find_selected_directory_free_slot()? };
    let parent_inode_id = unsafe { read_u32(STATE_SELECTED_INODE_ID) };
    let slot_block = unsafe { read_u32(STATE_DIRECTORY_SLOT_BLOCK) };
    let slot_offset = unsafe { read_u32(STATE_DIRECTORY_SLOT_OFFSET) };
    let slot_directory_offset = unsafe { read_u32(STATE_DIRECTORY_SLOT_DIRECTORY_OFFSET) };
    let inode_id = unsafe { allocate_inode()? };
    let start_block = unsafe { allocate_contiguous_blocks(1)? };
    unsafe { clear_scratch_block() };
    unsafe { write_fs_block(start_block)? };
    let mut extent_start_blocks = [0; K16FS_MAX_INLINE_EXTENTS];
    let mut extent_block_counts = [0; K16FS_MAX_INLINE_EXTENTS];
    extent_start_blocks[0] = start_block;
    extent_block_counts[0] = 1;
    let metadata = FileMetadata {
        inode_id,
        size_bytes: 0,
        extent_count: 1,
        extent_start_blocks,
        extent_block_counts,
    };
    unsafe { encode_file_inode(metadata)? };
    unsafe { encode_directory_entry_at(slot_block, slot_offset, inode_id, name)? };
    unsafe { read_inode(parent_inode_id)? };
    let new_size = max_u32(
        unsafe { read_u32(STATE_INODE_SIZE_BYTES) },
        slot_directory_offset + K16FS_DIRECTORY_ENTRY_SIZE,
    );
    unsafe { encode_selected_inode_size(parent_inode_id, new_size)? };
    unsafe { read_inode(inode_id)? };
    Ok(unsafe { selected_file_metadata() })
}

unsafe fn create_empty_directory(path: &[&[u8]]) -> Result<(), StorageError> {
    let parent_len = path.len() - 1;
    let name = path[parent_len];
    unsafe { find_directory_inode(&path[..parent_len])? };
    match unsafe { find_directory_entry(name) } {
        Ok(_) => return Err(StorageError::INVALID_FILESYSTEM),
        Err(error) if error == StorageError::PATH_NOT_FOUND => {}
        Err(error) => return Err(error),
    }
    unsafe { find_selected_directory_free_slot()? };
    let parent_inode_id = unsafe { read_u32(STATE_SELECTED_INODE_ID) };
    let slot_block = unsafe { read_u32(STATE_DIRECTORY_SLOT_BLOCK) };
    let slot_offset = unsafe { read_u32(STATE_DIRECTORY_SLOT_OFFSET) };
    let slot_directory_offset = unsafe { read_u32(STATE_DIRECTORY_SLOT_DIRECTORY_OFFSET) };
    let inode_id = unsafe { allocate_inode()? };
    let start_block = unsafe { allocate_contiguous_blocks(1)? };
    unsafe { clear_scratch_block() };
    unsafe { write_fs_block(start_block)? };
    let mut extent_start_blocks = [0; K16FS_MAX_INLINE_EXTENTS];
    let mut extent_block_counts = [0; K16FS_MAX_INLINE_EXTENTS];
    extent_start_blocks[0] = start_block;
    extent_block_counts[0] = 1;
    let metadata = FileMetadata {
        inode_id,
        size_bytes: 0,
        extent_count: 1,
        extent_start_blocks,
        extent_block_counts,
    };
    unsafe { encode_directory_inode(metadata)? };
    unsafe { encode_directory_entry_at(slot_block, slot_offset, inode_id, name)? };
    unsafe { read_inode(parent_inode_id)? };
    let new_size = max_u32(
        unsafe { read_u32(STATE_INODE_SIZE_BYTES) },
        slot_directory_offset + K16FS_DIRECTORY_ENTRY_SIZE,
    );
    unsafe { encode_selected_inode_size(parent_inode_id, new_size) }
}

unsafe fn truncate_selected_file() -> Result<(), StorageError> {
    let mut metadata = unsafe { selected_file_metadata() };
    if metadata.extent_count == 0 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    metadata.size_bytes = 0;
    unsafe { encode_file_inode(metadata) }
}

unsafe fn find_file_inode(path: &[&[u8]]) -> Result<(), StorageError> {
    if path.is_empty() {
        return Err(StorageError::PATH_NOT_FOUND);
    }

    let mut inode_id = unsafe { read_u32(STATE_SUPERBLOCK_ROOT_INODE_ID) };
    let mut index = 0;
    while index < path.len() {
        let component = path[index];
        unsafe { read_inode(inode_id)? };
        if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2 {
            return Err(StorageError::PATH_NOT_FOUND);
        }
        inode_id = unsafe { find_directory_entry(component)? };
        index += 1;
    }

    unsafe { read_inode(inode_id)? };
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 1 {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    Ok(())
}

unsafe fn find_directory_inode(path: &[&[u8]]) -> Result<(), StorageError> {
    let mut inode_id = unsafe { read_u32(STATE_SUPERBLOCK_ROOT_INODE_ID) };
    let mut index = 0;
    while index < path.len() {
        unsafe { read_inode(inode_id)? };
        if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2 {
            return Err(StorageError::PATH_NOT_FOUND);
        }
        inode_id = unsafe { find_directory_entry(path[index])? };
        index += 1;
    }

    unsafe { read_inode(inode_id)? };
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2 {
        return Err(StorageError::PATH_NOT_FOUND);
    }
    Ok(())
}

unsafe fn find_path_inode(path: &[&[u8]]) -> Result<(), StorageError> {
    let mut inode_id = unsafe { read_u32(STATE_SUPERBLOCK_ROOT_INODE_ID) };
    if path.is_empty() {
        unsafe { read_inode(inode_id)? };
        return Ok(());
    }

    let mut index = 0;
    while index < path.len() {
        unsafe { read_inode(inode_id)? };
        if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2 {
            return Err(StorageError::PATH_NOT_FOUND);
        }
        inode_id = unsafe { find_directory_entry(path[index])? };
        index += 1;
    }

    unsafe { read_inode(inode_id) }
}

unsafe fn find_directory_entry(name: &[u8]) -> Result<u32, StorageError> {
    let (inode_id, _, _) = unsafe { find_directory_entry_slot(name)? };
    Ok(inode_id)
}

unsafe fn find_directory_entry_slot(name: &[u8]) -> Result<(u32, u32, u32), StorageError> {
    if name.is_empty()
        || name.len() > K16FS_MAX_NAME_BYTES
        || unsafe { read_u32(STATE_INODE_SIZE_BYTES) } % K16FS_DIRECTORY_ENTRY_SIZE != 0
    {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let mut remaining = unsafe { read_u32(STATE_INODE_SIZE_BYTES) };
    let mut extent_index = 0;
    while extent_index < unsafe { read_u32(STATE_INODE_EXTENT_COUNT) as usize } {
        let extent_start_block =
            unsafe { read_u32(STATE_INODE_EXTENT_START_BLOCKS + extent_index as u32 * 4) };
        let extent_block_count =
            unsafe { read_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + extent_index as u32 * 4) };
        validate_extent(extent_start_block, extent_block_count, unsafe {
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
        })?;
        let mut block_index = 0;
        while block_index < extent_block_count {
            unsafe { read_fs_block(extent_start_block + block_index)? };
            let mut offset = 0;
            while offset < BLOCK_SIZE && remaining > 0 {
                match scratch_u8(offset) {
                    0 | 2 => {}
                    1 => {
                        let name_len = scratch_u8(offset + 1) as usize;
                        if name_len == 0
                            || name_len > K16FS_MAX_NAME_BYTES
                            || scratch_u8(offset + 2) != 0
                            || scratch_u8(offset + 3) != 0
                        {
                            return Err(StorageError::INVALID_FILESYSTEM);
                        }
                        if name_len == name.len() && scratch_bytes_eq(offset + 8, name) {
                            return Ok((
                                scratch_u32(offset + 4),
                                extent_start_block + block_index,
                                offset,
                            ));
                        }
                    }
                    _ => return Err(StorageError::INVALID_FILESYSTEM),
                }
                remaining -= K16FS_DIRECTORY_ENTRY_SIZE;
                offset += K16FS_DIRECTORY_ENTRY_SIZE;
            }
            block_index += 1;
        }
        extent_index += 1;
    }

    if remaining != 0 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Err(StorageError::PATH_NOT_FOUND)
}

unsafe fn find_selected_directory_free_slot() -> Result<(), StorageError> {
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let mut directory_offset = 0;
    let mut extent_index = 0;
    while extent_index < unsafe { read_u32(STATE_INODE_EXTENT_COUNT) as usize } {
        let extent_start_block =
            unsafe { read_u32(STATE_INODE_EXTENT_START_BLOCKS + extent_index as u32 * 4) };
        let extent_block_count =
            unsafe { read_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + extent_index as u32 * 4) };
        validate_extent(extent_start_block, extent_block_count, unsafe {
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
        })?;
        let mut block_index = 0;
        while block_index < extent_block_count {
            unsafe { read_fs_block(extent_start_block + block_index)? };
            let mut offset = 0;
            while offset < BLOCK_SIZE {
                match scratch_u8(offset) {
                    0 | 2 => {
                        unsafe {
                            write_u32(STATE_DIRECTORY_SLOT_BLOCK, extent_start_block + block_index);
                            write_u32(STATE_DIRECTORY_SLOT_OFFSET, offset);
                            write_u32(STATE_DIRECTORY_SLOT_DIRECTORY_OFFSET, directory_offset);
                        }
                        return Ok(());
                    }
                    1 => {}
                    _ => return Err(StorageError::INVALID_FILESYSTEM),
                }
                offset += K16FS_DIRECTORY_ENTRY_SIZE;
                directory_offset += K16FS_DIRECTORY_ENTRY_SIZE;
            }
            block_index += 1;
        }
        extent_index += 1;
    }
    let slot_block = unsafe { grow_selected_directory_capacity()? };
    unsafe {
        write_u32(STATE_DIRECTORY_SLOT_BLOCK, slot_block);
        write_u32(STATE_DIRECTORY_SLOT_OFFSET, 0);
        write_u32(STATE_DIRECTORY_SLOT_DIRECTORY_OFFSET, directory_offset);
    }
    Ok(())
}

unsafe fn grow_selected_directory_capacity() -> Result<u32, StorageError> {
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let mut metadata = unsafe { selected_file_metadata() };
    if metadata.extent_count == 0 || metadata.extent_count as usize > K16FS_MAX_INLINE_EXTENTS {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let last_extent_index = metadata.extent_count as usize - 1;
    let last_start = metadata.extent_start_blocks[last_extent_index];
    let last_count = metadata.extent_block_counts[last_extent_index];
    let grow_block = match last_start.checked_add(last_count) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    if grow_block < unsafe { read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS) }
        && !unsafe { is_block_allocated(grow_block)? }
    {
        unsafe { mark_block_allocated(grow_block)? };
        unsafe { clear_scratch_block() };
        unsafe { write_fs_block(grow_block)? };
        metadata.extent_block_counts[last_extent_index] = match last_count.checked_add(1) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        unsafe { encode_directory_inode(metadata)? };
        return Ok(grow_block);
    }

    let new_extent_index = metadata.extent_count as usize;
    if new_extent_index >= K16FS_MAX_INLINE_EXTENTS {
        return Err(StorageError::OUTPUT_BUFFER_TOO_SMALL);
    }
    let new_extent_block = unsafe { allocate_contiguous_blocks(1)? };
    unsafe { clear_scratch_block() };
    unsafe { write_fs_block(new_extent_block)? };
    metadata.extent_start_blocks[new_extent_index] = new_extent_block;
    metadata.extent_block_counts[new_extent_index] = 1;
    metadata.extent_count = match metadata.extent_count.checked_add(1) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    unsafe { encode_directory_inode(metadata)? };
    Ok(new_extent_block)
}

pub unsafe fn copy_selected_directory_listing_to_ram(
    dst_addr: u32,
    len: u32,
) -> Result<u32, StorageError> {
    let mut sink = RamDirectoryListingSink::new(dst_addr, len);
    unsafe { copy_selected_directory_listing_into(&mut sink) }
}

pub unsafe fn copy_selected_directory_listing_into<S: DirectoryListingSink>(
    sink: &mut S,
) -> Result<u32, StorageError> {
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2
        || unsafe { read_u32(STATE_INODE_SIZE_BYTES) } % K16FS_DIRECTORY_ENTRY_SIZE != 0
    {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let mut remaining = unsafe { read_u32(STATE_INODE_SIZE_BYTES) };
    let mut extent_index = 0;
    while extent_index < unsafe { read_u32(STATE_INODE_EXTENT_COUNT) as usize } {
        let extent_start_block =
            unsafe { read_u32(STATE_INODE_EXTENT_START_BLOCKS + extent_index as u32 * 4) };
        let extent_block_count =
            unsafe { read_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + extent_index as u32 * 4) };
        validate_extent(extent_start_block, extent_block_count, unsafe {
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
        })?;
        let mut block_index = 0;
        while block_index < extent_block_count {
            unsafe { read_fs_block(extent_start_block + block_index)? };
            let mut offset = 0;
            while offset < BLOCK_SIZE && remaining > 0 {
                match scratch_u8(offset) {
                    0 | 2 => {}
                    1 => {
                        let name_len = scratch_u8(offset + 1) as u32;
                        if name_len == 0
                            || name_len as usize > K16FS_MAX_NAME_BYTES
                            || scratch_u8(offset + 2) != 0
                            || scratch_u8(offset + 3) != 0
                        {
                            return Err(StorageError::INVALID_FILESYSTEM);
                        }
                        let mut name_offset = 0;
                        while name_offset < name_len {
                            unsafe {
                                sink.push_byte(scratch_u8(offset + 8 + name_offset))?;
                            }
                            name_offset += 1;
                        }
                        unsafe { sink.push_byte(b'\n')? };
                    }
                    _ => return Err(StorageError::INVALID_FILESYSTEM),
                }
                remaining -= K16FS_DIRECTORY_ENTRY_SIZE;
                offset += K16FS_DIRECTORY_ENTRY_SIZE;
            }
            block_index += 1;
        }
        extent_index += 1;
    }

    if remaining != 0 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(sink.written())
}

unsafe fn ensure_selected_directory_is_empty() -> Result<(), StorageError> {
    if unsafe { read_u32(STATE_INODE_STATE) as u8 } != 2
        || unsafe { read_u32(STATE_INODE_SIZE_BYTES) } % K16FS_DIRECTORY_ENTRY_SIZE != 0
    {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let mut remaining = unsafe { read_u32(STATE_INODE_SIZE_BYTES) };
    let mut extent_index = 0;
    while extent_index < unsafe { read_u32(STATE_INODE_EXTENT_COUNT) as usize } {
        let extent_start_block =
            unsafe { read_u32(STATE_INODE_EXTENT_START_BLOCKS + extent_index as u32 * 4) };
        let extent_block_count =
            unsafe { read_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + extent_index as u32 * 4) };
        validate_extent(extent_start_block, extent_block_count, unsafe {
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
        })?;
        let mut block_index = 0;
        while block_index < extent_block_count {
            unsafe { read_fs_block(extent_start_block + block_index)? };
            let mut offset = 0;
            while offset < BLOCK_SIZE && remaining > 0 {
                match scratch_u8(offset) {
                    0 | 2 => {}
                    1 => return Err(StorageError::PATH_NOT_EMPTY),
                    _ => return Err(StorageError::INVALID_FILESYSTEM),
                }
                remaining -= K16FS_DIRECTORY_ENTRY_SIZE;
                offset += K16FS_DIRECTORY_ENTRY_SIZE;
            }
            block_index += 1;
        }
        extent_index += 1;
    }

    if remaining != 0 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(())
}

pub unsafe fn copy_selected_file_range_to_ram(
    file_offset: u32,
    dst_addr: u32,
    len: u32,
) -> Result<(), StorageError> {
    let range_end = match file_offset.checked_add(len) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    if range_end > unsafe { read_u32(STATE_INODE_SIZE_BYTES) } {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let mut copied = 0;
    let mut extent_file_start: u32 = 0;
    let mut extent_index = 0;
    while extent_index < unsafe { read_u32(STATE_INODE_EXTENT_COUNT) as usize } && copied < len {
        let extent_start_block =
            unsafe { read_u32(STATE_INODE_EXTENT_START_BLOCKS + extent_index as u32 * 4) };
        let extent_block_count =
            unsafe { read_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + extent_index as u32 * 4) };
        let extent_bytes = match extent_block_count.checked_mul(BLOCK_SIZE) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        let extent_file_end = match extent_file_start.checked_add(extent_bytes) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };

        if range_end > extent_file_start && file_offset < extent_file_end {
            let copy_start = max_u32(file_offset, extent_file_start);
            let copy_end = min_u32(range_end, extent_file_end);
            let mut cursor = copy_start;
            while cursor < copy_end {
                let within_extent = cursor - extent_file_start;
                let block_delta = within_extent / BLOCK_SIZE;
                let block_offset = within_extent % BLOCK_SIZE;
                let available = min_u32(BLOCK_SIZE - block_offset, copy_end - cursor);
                unsafe { read_fs_block(extent_start_block + block_delta)? };
                unsafe {
                    copy_ram_to_ram(SCRATCH_ADDR + block_offset, dst_addr + copied, available);
                }
                copied += available;
                cursor += available;
            }
        }

        extent_file_start = extent_file_end;
        extent_index += 1;
    }

    if copied != len {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(())
}

pub unsafe fn copy_file_range_to_ram(
    metadata: FileMetadata,
    file_offset: u32,
    dst_addr: u32,
    len: u32,
) -> Result<(), StorageError> {
    let range_end = match file_offset.checked_add(len) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    if range_end > metadata.size_bytes {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let mut copied = 0;
    let mut extent_file_start: u32 = 0;
    let mut extent_index = 0;
    while extent_index < metadata.extent_count as usize && copied < len {
        let extent_start_block = metadata.extent_start_blocks[extent_index];
        let extent_block_count = metadata.extent_block_counts[extent_index];
        let extent_bytes = match extent_block_count.checked_mul(BLOCK_SIZE) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        let extent_file_end = match extent_file_start.checked_add(extent_bytes) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };

        if range_end > extent_file_start && file_offset < extent_file_end {
            let copy_start = max_u32(file_offset, extent_file_start);
            let copy_end = min_u32(range_end, extent_file_end);
            let mut cursor = copy_start;
            while cursor < copy_end {
                let within_extent = cursor - extent_file_start;
                let block_delta = within_extent / BLOCK_SIZE;
                let block_offset = within_extent % BLOCK_SIZE;
                let available = min_u32(BLOCK_SIZE - block_offset, copy_end - cursor);
                unsafe { read_fs_block(extent_start_block + block_delta)? };
                unsafe {
                    copy_ram_to_ram(SCRATCH_ADDR + block_offset, dst_addr + copied, available);
                }
                copied += available;
                cursor += available;
            }
        }

        extent_file_start = extent_file_end;
        extent_index += 1;
    }

    if copied != len {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(())
}

#[inline(always)]
unsafe fn read_inode(inode_id: u32) -> Result<(), StorageError> {
    let inodes_per_block = BLOCK_SIZE / K16FS_INODE_SIZE;
    let inode_capacity = match unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT) }
        .checked_mul(inodes_per_block)
    {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    if inode_id >= inode_capacity {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let inode_block =
        unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK) } + inode_id / inodes_per_block;
    let inode_offset = (inode_id % inodes_per_block) * K16FS_INODE_SIZE;
    unsafe { read_fs_block(inode_block)? };

    let size_high = scratch_u32(inode_offset + 0x0c);
    let extent_count = scratch_u8(inode_offset + 0x10) as usize;
    if size_high != 0 || extent_count > K16FS_MAX_INLINE_EXTENTS {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    unsafe {
        write_u32(STATE_SELECTED_INODE_ID, inode_id);
        write_u32(STATE_INODE_STATE, scratch_u8(inode_offset) as u32);
        write_u32(STATE_INODE_SIZE_BYTES, scratch_u32(inode_offset + 0x08));
        write_u32(STATE_INODE_EXTENT_COUNT, extent_count as u32);
    }
    let mut index = 0;
    while index < extent_count {
        let offset = inode_offset + 0x20 + index as u32 * 8;
        let start_block = scratch_u32(offset);
        let block_count = scratch_u32(offset + 4);
        validate_extent(start_block, block_count, unsafe {
            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS)
        })?;
        unsafe {
            write_u32(
                STATE_INODE_EXTENT_START_BLOCKS + index as u32 * 4,
                start_block,
            );
            write_u32(
                STATE_INODE_EXTENT_BLOCK_COUNTS + index as u32 * 4,
                block_count,
            );
        }
        index += 1;
    }

    Ok(())
}

unsafe fn encode_file_inode(metadata: FileMetadata) -> Result<(), StorageError> {
    unsafe {
        encode_inode(
            metadata.inode_id,
            1,
            metadata.size_bytes,
            metadata.extent_count,
            &metadata.extent_start_blocks,
            &metadata.extent_block_counts,
        )
    }
}

unsafe fn encode_directory_inode(metadata: FileMetadata) -> Result<(), StorageError> {
    unsafe {
        encode_inode(
            metadata.inode_id,
            2,
            metadata.size_bytes,
            metadata.extent_count,
            &metadata.extent_start_blocks,
            &metadata.extent_block_counts,
        )
    }
}

unsafe fn encode_deleted_file_inode(metadata: FileMetadata) -> Result<(), StorageError> {
    unsafe {
        encode_inode(
            metadata.inode_id,
            3,
            metadata.size_bytes,
            metadata.extent_count,
            &metadata.extent_start_blocks,
            &metadata.extent_block_counts,
        )
    }
}

unsafe fn encode_deleted_directory_inode(metadata: FileMetadata) -> Result<(), StorageError> {
    unsafe {
        encode_inode(
            metadata.inode_id,
            3,
            0,
            0,
            &[0; K16FS_MAX_INLINE_EXTENTS],
            &[0; K16FS_MAX_INLINE_EXTENTS],
        )
    }
}

unsafe fn encode_selected_inode_size(inode_id: u32, size_bytes: u32) -> Result<(), StorageError> {
    let mut extent_start_blocks = [0; K16FS_MAX_INLINE_EXTENTS];
    let mut extent_block_counts = [0; K16FS_MAX_INLINE_EXTENTS];
    let extent_count = unsafe { read_u32(STATE_INODE_EXTENT_COUNT) };
    let mut index = 0;
    while index < K16FS_MAX_INLINE_EXTENTS {
        extent_start_blocks[index] =
            unsafe { read_u32(STATE_INODE_EXTENT_START_BLOCKS + index as u32 * 4) };
        extent_block_counts[index] =
            unsafe { read_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + index as u32 * 4) };
        index += 1;
    }
    unsafe {
        encode_inode(
            inode_id,
            read_u32(STATE_INODE_STATE) as u8,
            size_bytes,
            extent_count,
            &extent_start_blocks,
            &extent_block_counts,
        )
    }
}

unsafe fn encode_inode(
    inode_id: u32,
    state: u8,
    size_bytes: u32,
    extent_count: u32,
    extent_start_blocks: &[u32; K16FS_MAX_INLINE_EXTENTS],
    extent_block_counts: &[u32; K16FS_MAX_INLINE_EXTENTS],
) -> Result<(), StorageError> {
    if extent_count as usize > K16FS_MAX_INLINE_EXTENTS {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let inodes_per_block = BLOCK_SIZE / K16FS_INODE_SIZE;
    let inode_capacity = match unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT) }
        .checked_mul(inodes_per_block)
    {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    if inode_id >= inode_capacity {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let inode_block =
        unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK) } + inode_id / inodes_per_block;
    let inode_offset = (inode_id % inodes_per_block) * K16FS_INODE_SIZE;
    unsafe { read_fs_block(inode_block)? };
    let mut offset = 0;
    while offset < K16FS_INODE_SIZE {
        unsafe { write_u8(SCRATCH_ADDR + inode_offset + offset, 0) };
        offset += 1;
    }
    unsafe {
        write_u8(SCRATCH_ADDR + inode_offset, state);
        write_u32(SCRATCH_ADDR + inode_offset + 0x08, size_bytes);
        write_u32(SCRATCH_ADDR + inode_offset + 0x0c, 0);
        write_u8(SCRATCH_ADDR + inode_offset + 0x10, extent_count as u8);
    }
    let mut index = 0;
    while index < extent_count as usize {
        let offset = SCRATCH_ADDR + inode_offset + 0x20 + index as u32 * 8;
        unsafe {
            write_u32(offset, extent_start_blocks[index]);
            write_u32(offset + 4, extent_block_counts[index]);
        }
        index += 1;
    }
    unsafe { write_fs_block(inode_block) }
}

unsafe fn allocate_inode() -> Result<u32, StorageError> {
    let inodes_per_block = BLOCK_SIZE / K16FS_INODE_SIZE;
    let inode_capacity = match unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT) }
        .checked_mul(inodes_per_block)
    {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    let mut inode_id = 1;
    while inode_id < inode_capacity {
        unsafe { read_inode(inode_id)? };
        match unsafe { read_u32(STATE_INODE_STATE) as u8 } {
            0 | 3 => return Ok(inode_id),
            1 | 2 => {}
            _ => return Err(StorageError::INVALID_FILESYSTEM),
        }
        inode_id += 1;
    }
    Err(StorageError::OUTPUT_BUFFER_TOO_SMALL)
}

unsafe fn allocate_contiguous_blocks(count: u32) -> Result<u32, StorageError> {
    if count == 0 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let total_blocks = unsafe { read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS) };
    let mut run_start = 0;
    let mut run_count = 0;
    let mut block = 1;
    while block < total_blocks {
        if unsafe { is_block_allocated(block)? } {
            run_start = 0;
            run_count = 0;
        } else {
            if run_count == 0 {
                run_start = block;
            }
            run_count += 1;
            if run_count == count {
                let mut allocated = run_start;
                while allocated < run_start + count {
                    unsafe { mark_block_allocated(allocated)? };
                    allocated += 1;
                }
                return Ok(run_start);
            }
        }
        block += 1;
    }
    Err(StorageError::OUTPUT_BUFFER_TOO_SMALL)
}

unsafe fn is_block_allocated(block: u32) -> Result<bool, StorageError> {
    if block >= unsafe { read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS) } {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let bits_per_block = BLOCK_SIZE * 8;
    let bitmap_block_index = block / bits_per_block;
    if bitmap_block_index >= unsafe { read_u32(STATE_SUPERBLOCK_BITMAP_BLOCK_COUNT) } {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let bitmap_block =
        unsafe { read_u32(STATE_SUPERBLOCK_BITMAP_START_BLOCK) } + bitmap_block_index;
    let byte_offset = (block / 8) % BLOCK_SIZE;
    let bit = (block % 8) as u8;
    unsafe { read_fs_block(bitmap_block)? };
    Ok((scratch_u8(byte_offset) & (1_u8 << bit)) != 0)
}

unsafe fn mark_block_allocated(block: u32) -> Result<(), StorageError> {
    if block >= unsafe { read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS) } {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let bits_per_block = BLOCK_SIZE * 8;
    let bitmap_block_index = block / bits_per_block;
    if bitmap_block_index >= unsafe { read_u32(STATE_SUPERBLOCK_BITMAP_BLOCK_COUNT) } {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let bitmap_block =
        unsafe { read_u32(STATE_SUPERBLOCK_BITMAP_START_BLOCK) } + bitmap_block_index;
    let byte_offset = (block / 8) % BLOCK_SIZE;
    let bit = (block % 8) as u8;
    unsafe { read_fs_block(bitmap_block)? };
    let value = scratch_u8(byte_offset) | (1_u8 << bit);
    unsafe { write_u8(SCRATCH_ADDR + byte_offset, value) };
    unsafe { write_fs_block(bitmap_block) }
}

unsafe fn mark_block_free(block: u32) -> Result<(), StorageError> {
    if block >= unsafe { read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS) }
        || unsafe { block_is_metadata(block)? }
    {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let bits_per_block = BLOCK_SIZE * 8;
    let bitmap_block_index = block / bits_per_block;
    if bitmap_block_index >= unsafe { read_u32(STATE_SUPERBLOCK_BITMAP_BLOCK_COUNT) } {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let bitmap_block =
        unsafe { read_u32(STATE_SUPERBLOCK_BITMAP_START_BLOCK) } + bitmap_block_index;
    let byte_offset = (block / 8) % BLOCK_SIZE;
    let bit = (block % 8) as u8;
    unsafe { read_fs_block(bitmap_block)? };
    let value = scratch_u8(byte_offset) & !(1_u8 << bit);
    unsafe { write_u8(SCRATCH_ADDR + byte_offset, value) };
    unsafe { write_fs_block(bitmap_block) }
}

unsafe fn block_is_metadata(block: u32) -> Result<bool, StorageError> {
    if block == 0 {
        return Ok(true);
    }
    if block_in_range(
        block,
        unsafe { read_u32(STATE_SUPERBLOCK_BITMAP_START_BLOCK) },
        unsafe { read_u32(STATE_SUPERBLOCK_BITMAP_BLOCK_COUNT) },
    )? {
        return Ok(true);
    }
    block_in_range(
        block,
        unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK) },
        unsafe { read_u32(STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT) },
    )
}

fn block_in_range(block: u32, start: u32, count: u32) -> Result<bool, StorageError> {
    let end = match start.checked_add(count) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    Ok(block >= start && block < end)
}

unsafe fn encode_directory_entry_at(
    block: u32,
    offset: u32,
    inode_id: u32,
    name: &[u8],
) -> Result<(), StorageError> {
    if name.is_empty() || name.len() > K16FS_MAX_NAME_BYTES {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    unsafe { read_fs_block(block)? };
    let mut cursor = 0;
    while cursor < K16FS_DIRECTORY_ENTRY_SIZE {
        unsafe { write_u8(SCRATCH_ADDR + offset + cursor, 0) };
        cursor += 1;
    }
    unsafe {
        write_u8(SCRATCH_ADDR + offset, 1);
        write_u8(SCRATCH_ADDR + offset + 1, name.len() as u8);
        write_u32(SCRATCH_ADDR + offset + 4, inode_id);
    }
    let mut index = 0;
    while index < name.len() {
        unsafe { write_u8(SCRATCH_ADDR + offset + 8 + index as u32, name[index]) };
        index += 1;
    }
    unsafe { write_fs_block(block) }
}

unsafe fn encode_deleted_directory_entry_at(block: u32, offset: u32) -> Result<(), StorageError> {
    unsafe { read_fs_block(block)? };
    let mut cursor = 0;
    while cursor < K16FS_DIRECTORY_ENTRY_SIZE {
        unsafe { write_u8(SCRATCH_ADDR + offset + cursor, 0) };
        cursor += 1;
    }
    unsafe { write_u8(SCRATCH_ADDR + offset, 2) };
    unsafe { write_fs_block(block) }
}

fn file_capacity_bytes(metadata: FileMetadata) -> Result<u32, StorageError> {
    let mut capacity: u32 = 0;
    let mut index = 0;
    while index < metadata.extent_count as usize {
        let bytes = match metadata.extent_block_counts[index].checked_mul(BLOCK_SIZE) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        capacity = match capacity.checked_add(bytes) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        index += 1;
    }
    Ok(capacity)
}

unsafe fn grow_file_capacity(
    mut metadata: FileMetadata,
    required_size: u32,
) -> Result<FileMetadata, StorageError> {
    if metadata.extent_count == 0
        || metadata.extent_count as usize > K16FS_MAX_INLINE_EXTENTS
        || required_size <= file_capacity_bytes(metadata)?
    {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let last_extent_index = metadata.extent_count as usize - 1;
    let last_start = metadata.extent_start_blocks[last_extent_index];
    let last_count = metadata.extent_block_counts[last_extent_index];
    let current_capacity = file_capacity_bytes(metadata)?;
    let additional_bytes = required_size - current_capacity;
    let additional_blocks = div_ceil_u32(additional_bytes, BLOCK_SIZE)?;
    let grow_start = match last_start.checked_add(last_count) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    let grow_end = match grow_start.checked_add(additional_blocks) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    let mut can_extend_last_extent = grow_end <= unsafe { read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS) };
    let mut block = grow_start;
    while can_extend_last_extent && block < grow_end {
        if unsafe { is_block_allocated(block)? } {
            can_extend_last_extent = false;
        } else {
            block += 1;
        }
    }

    if can_extend_last_extent {
        block = grow_start;
        while block < grow_end {
            unsafe { mark_block_allocated(block)? };
            unsafe { clear_scratch_block() };
            unsafe { write_fs_block(block)? };
            block += 1;
        }

        metadata.extent_block_counts[last_extent_index] =
            match last_count.checked_add(additional_blocks) {
                Some(value) => value,
                None => return Err(StorageError::INVALID_FILESYSTEM),
            };
        return Ok(metadata);
    }

    let new_extent_index = metadata.extent_count as usize;
    if new_extent_index >= K16FS_MAX_INLINE_EXTENTS {
        return Err(StorageError::OUTPUT_BUFFER_TOO_SMALL);
    }
    let new_extent_start = unsafe { allocate_contiguous_blocks(additional_blocks)? };
    block = new_extent_start;
    let new_extent_end = match new_extent_start.checked_add(additional_blocks) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    while block < new_extent_end {
        unsafe { clear_scratch_block() };
        unsafe { write_fs_block(block)? };
        block += 1;
    }

    metadata.extent_start_blocks[new_extent_index] = new_extent_start;
    metadata.extent_block_counts[new_extent_index] = additional_blocks;
    metadata.extent_count = match metadata.extent_count.checked_add(1) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    Ok(metadata)
}

pub unsafe fn flush_storage0() -> Result<(), StorageError> {
    let version = unsafe { read_i32(storage0::VERSION) };
    if version != storage0::STORAGE_VERSION {
        return Err(StorageError::STORAGE_VERSION);
    }
    let media = unsafe { read_i32(storage0::MEDIA_STATUS) };
    if media != storage0::MEDIA_PRESENT && media != storage0::MEDIA_READ_ONLY {
        return Err(StorageError::STORAGE_MEDIA);
    }
    unsafe {
        write_i32(storage0::COMMAND, storage0::COMMAND_FLUSH);
    }
    if unsafe { read_i32(storage0::STATUS) } != storage0::STATUS_DONE
        || unsafe { read_i32(storage0::ERROR) } != storage0::ERROR_NONE
    {
        return Err(StorageError::STORAGE_TRANSFER);
    }
    Ok(())
}

fn div_ceil_u32(value: u32, divisor: u32) -> Result<u32, StorageError> {
    if divisor == 0 {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let adjusted = match value.checked_add(divisor - 1) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    Ok(adjusted / divisor)
}

#[inline(always)]
fn validate_extent(
    start_block: u32,
    block_count: u32,
    total_blocks: u32,
) -> Result<(), StorageError> {
    let end = match start_block.checked_add(block_count) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    if block_count == 0 || end > total_blocks {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(())
}

#[inline(always)]
unsafe fn read_fs_block(block: u32) -> Result<(), StorageError> {
    if block >= unsafe { read_u32(STATE_PARTITION_BLOCK_COUNT) } {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let lba = match unsafe { read_u32(STATE_PARTITION_START_LBA) }.checked_add(block) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    unsafe { read_storage_block(lba) }
}

#[inline(always)]
unsafe fn write_fs_block(block: u32) -> Result<(), StorageError> {
    if block >= unsafe { read_u32(STATE_PARTITION_BLOCK_COUNT) } {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    let lba = match unsafe { read_u32(STATE_PARTITION_START_LBA) }.checked_add(block) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    unsafe { write_storage_block(lba) }
}

#[inline(always)]
unsafe fn read_storage_block(lba: u32) -> Result<(), StorageError> {
    let version = unsafe { read_i32(storage0::VERSION) };
    if version != storage0::STORAGE_VERSION {
        return Err(StorageError::STORAGE_VERSION);
    }
    let block_size = unsafe { read_i32(storage0::BLOCK_SIZE) };
    if block_size != BLOCK_SIZE as i32 {
        return Err(StorageError::STORAGE_BLOCK_SIZE);
    }
    let media = unsafe { read_i32(storage0::MEDIA_STATUS) };
    if media != storage0::MEDIA_PRESENT && media != storage0::MEDIA_READ_ONLY {
        return Err(StorageError::STORAGE_MEDIA);
    }
    unsafe {
        write_u32(storage0::LBA_LOW, lba);
        write_u32(storage0::LBA_HIGH, 0);
        write_u32(storage0::BLOCK_COUNT, 1);
        write_u32(storage0::BUFFER_ADDR, SCRATCH_ADDR);
        write_i32(storage0::COMMAND, storage0::COMMAND_READ_BLOCKS);
    }
    if unsafe { read_i32(storage0::STATUS) } != storage0::STATUS_DONE
        || unsafe { read_i32(storage0::ERROR) } != storage0::ERROR_NONE
        || unsafe { read_u32(storage0::BYTES_DONE) } != BLOCK_SIZE
    {
        return Err(StorageError::STORAGE_TRANSFER);
    }
    Ok(())
}

#[inline(always)]
unsafe fn write_storage_block(lba: u32) -> Result<(), StorageError> {
    let version = unsafe { read_i32(storage0::VERSION) };
    if version != storage0::STORAGE_VERSION {
        return Err(StorageError::STORAGE_VERSION);
    }
    let block_size = unsafe { read_i32(storage0::BLOCK_SIZE) };
    if block_size != BLOCK_SIZE as i32 {
        return Err(StorageError::STORAGE_BLOCK_SIZE);
    }
    let media = unsafe { read_i32(storage0::MEDIA_STATUS) };
    if media != storage0::MEDIA_PRESENT {
        return Err(StorageError::STORAGE_MEDIA);
    }
    unsafe {
        write_u32(storage0::LBA_LOW, lba);
        write_u32(storage0::LBA_HIGH, 0);
        write_u32(storage0::BLOCK_COUNT, 1);
        write_u32(storage0::BUFFER_ADDR, SCRATCH_ADDR);
        write_i32(storage0::COMMAND, storage0::COMMAND_WRITE_BLOCKS);
    }
    if unsafe { read_i32(storage0::STATUS) } != storage0::STATUS_DONE
        || unsafe { read_i32(storage0::ERROR) } != storage0::ERROR_NONE
        || unsafe { read_u32(storage0::BYTES_DONE) } != BLOCK_SIZE
    {
        return Err(StorageError::STORAGE_TRANSFER);
    }
    Ok(())
}

unsafe fn clear_scratch_block() {
    let mut offset = 0;
    while offset < BLOCK_SIZE {
        unsafe { write_u8(SCRATCH_ADDR + offset, 0) };
        offset += 1;
    }
}

fn scratch_eq(offset: u32, expected: &[u8]) -> bool {
    scratch_bytes_eq(offset, expected)
}

fn scratch_bytes_eq(offset: u32, expected: &[u8]) -> bool {
    let mut index = 0;
    while index < expected.len() {
        if scratch_u8(offset + index as u32) != expected[index] {
            return false;
        }
        index += 1;
    }
    true
}

fn scratch_u8(offset: u32) -> u8 {
    unsafe { read_u8(SCRATCH_ADDR + offset) }
}

fn scratch_u32(offset: u32) -> u32 {
    unsafe { read_u32(SCRATCH_ADDR + offset) }
}

unsafe fn copy_ram_to_ram(src_addr: u32, dst_addr: u32, len: u32) {
    let mut offset = 0;
    while offset < len {
        let byte = unsafe { read_u8(src_addr + offset) };
        unsafe { write_u8(dst_addr + offset, byte) };
        offset += 1;
    }
}

fn min_u32(left: u32, right: u32) -> u32 {
    if left < right {
        left
    } else {
        right
    }
}

fn max_u32(left: u32, right: u32) -> u32 {
    if left > right {
        left
    } else {
        right
    }
}

unsafe fn read_i32(address: u32) -> i32 {
    unsafe { core::ptr::read_volatile(address as usize as *const i32) }
}

unsafe fn read_u32(address: u32) -> u32 {
    unsafe { core::ptr::read_volatile(address as usize as *const u32) }
}

unsafe fn read_u8(address: u32) -> u8 {
    unsafe { core::ptr::read_volatile(address as usize as *const u8) }
}

unsafe fn write_i32(address: u32, value: i32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut i32, value) }
}

unsafe fn write_u32(address: u32, value: u32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u32, value) }
}

unsafe fn write_u8(address: u32, value: u8) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u8, value) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn storage_error_code_is_public_for_boot_chain_mapping() {
        assert_eq!(StorageError::STORAGE_VERSION.code(), 10);
        assert_eq!(StorageError::OUTPUT_BUFFER_TOO_SMALL.code(), 19);
        assert_eq!(StorageError::PATH_EXISTS.code(), 22);
    }

    #[test]
    fn path_metadata_kind_values_are_stable_for_kernel_stat_abi() {
        assert_eq!(
            PathKind::Regular as u32,
            k16_abi::syscall::FILE_TYPE_REGULAR
        );
        assert_eq!(
            PathKind::Directory as u32,
            k16_abi::syscall::FILE_TYPE_DIRECTORY
        );

        let metadata = PathMetadata {
            kind: PathKind::Regular,
            size_bytes: 42,
        };

        assert_eq!(metadata.kind, PathKind::Regular);
        assert_eq!(metadata.size_bytes, 42);
    }
}
