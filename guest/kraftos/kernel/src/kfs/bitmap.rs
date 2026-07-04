use crate::kfs::error::StorageError;
use crate::kfs::storage::BLOCK_SIZE;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KfsBitmapLayout {
    pub total_blocks: u32,
    pub bitmap_start_block: u32,
    pub bitmap_block_count: u32,
    pub inode_table_start_block: u32,
    pub inode_table_block_count: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KfsBitmapLocation {
    pub bitmap_block_index: u32,
    pub bitmap_block: u32,
    pub byte_offset: u32,
    pub mask: u8,
}

pub const fn bits_per_bitmap_block() -> u32 {
    BLOCK_SIZE * 8
}

pub fn locate_block(
    block: u32,
    layout: KfsBitmapLayout,
) -> Result<KfsBitmapLocation, StorageError> {
    if block >= layout.total_blocks {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let bitmap_block_index = block / bits_per_bitmap_block();
    if bitmap_block_index >= layout.bitmap_block_count {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let bitmap_block = match layout.bitmap_start_block.checked_add(bitmap_block_index) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    let byte_offset = (block / 8) % BLOCK_SIZE;
    let mask = 1_u8 << (block % 8) as u8;
    Ok(KfsBitmapLocation {
        bitmap_block_index,
        bitmap_block,
        byte_offset,
        mask,
    })
}

pub fn byte_marks_allocated(value: u8, location: KfsBitmapLocation) -> bool {
    (value & location.mask) != 0
}

pub fn mark_byte_allocated(value: u8, location: KfsBitmapLocation) -> u8 {
    value | location.mask
}

pub fn mark_byte_free(value: u8, location: KfsBitmapLocation) -> u8 {
    value & !location.mask
}

pub fn block_is_metadata(block: u32, layout: KfsBitmapLayout) -> Result<bool, StorageError> {
    if block == 0 {
        return Ok(true);
    }
    if block_in_range(block, layout.bitmap_start_block, layout.bitmap_block_count)? {
        return Ok(true);
    }
    block_in_range(
        block,
        layout.inode_table_start_block,
        layout.inode_table_block_count,
    )
}

fn block_in_range(block: u32, start: u32, count: u32) -> Result<bool, StorageError> {
    let end = match start.checked_add(count) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    Ok(block >= start && block < end)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn layout() -> KfsBitmapLayout {
        KfsBitmapLayout {
            total_blocks: 9000,
            bitmap_start_block: 3,
            bitmap_block_count: 3,
            inode_table_start_block: 6,
            inode_table_block_count: 4,
        }
    }

    #[test]
    fn locate_block_maps_fs_block_to_bitmap_block_byte_and_mask() {
        assert_eq!(
            locate_block(0, layout()),
            Ok(KfsBitmapLocation {
                bitmap_block_index: 0,
                bitmap_block: 3,
                byte_offset: 0,
                mask: 0x01,
            }),
        );
        assert_eq!(
            locate_block(9, layout()),
            Ok(KfsBitmapLocation {
                bitmap_block_index: 0,
                bitmap_block: 3,
                byte_offset: 1,
                mask: 0x02,
            }),
        );
        assert_eq!(
            locate_block(4096, layout()),
            Ok(KfsBitmapLocation {
                bitmap_block_index: 1,
                bitmap_block: 4,
                byte_offset: 0,
                mask: 0x01,
            }),
        );
    }

    #[test]
    fn locate_block_rejects_out_of_layout_and_bitmap_address_overflow() {
        assert_eq!(
            locate_block(9000, layout()),
            Err(StorageError::INVALID_FILESYSTEM),
        );
        assert_eq!(
            locate_block(
                4096,
                KfsBitmapLayout {
                    bitmap_start_block: u32::MAX,
                    ..layout()
                },
            ),
            Err(StorageError::INVALID_FILESYSTEM),
        );
        assert_eq!(
            locate_block(
                4096,
                KfsBitmapLayout {
                    bitmap_block_count: 1,
                    ..layout()
                },
            ),
            Err(StorageError::INVALID_FILESYSTEM),
        );
    }

    #[test]
    fn byte_mutation_uses_located_bitmap_mask() {
        let location = locate_block(9, layout()).unwrap();

        assert!(!byte_marks_allocated(0, location));
        assert_eq!(mark_byte_allocated(0, location), 0x02);
        assert!(byte_marks_allocated(0x02, location));
        assert_eq!(mark_byte_free(0xff, location), 0xfd);
    }

    #[test]
    fn block_is_metadata_matches_superblock_bitmap_and_inode_ranges() {
        assert_eq!(block_is_metadata(0, layout()), Ok(true));
        assert_eq!(block_is_metadata(3, layout()), Ok(true));
        assert_eq!(block_is_metadata(5, layout()), Ok(true));
        assert_eq!(block_is_metadata(6, layout()), Ok(true));
        assert_eq!(block_is_metadata(9, layout()), Ok(true));
        assert_eq!(block_is_metadata(10, layout()), Ok(false));
    }

    #[test]
    fn block_is_metadata_rejects_overflowed_ranges() {
        assert_eq!(
            block_is_metadata(
                u32::MAX,
                KfsBitmapLayout {
                    bitmap_start_block: u32::MAX,
                    bitmap_block_count: 1,
                    ..layout()
                },
            ),
            Err(StorageError::INVALID_FILESYSTEM),
        );
    }
}
