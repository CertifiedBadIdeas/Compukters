use crate::kfs::error::StorageError;
use crate::kfs::storage::BLOCK_SIZE;
use crate::kfs::types::{FileMetadata, KFS_MAX_INLINE_EXTENTS};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KfsFileRange {
    pub end: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KfsFileExtentOverlap {
    pub extent_start_block: u32,
    pub extent_file_start: u32,
    pub extent_file_end: u32,
    pub copy_start: u32,
    pub copy_end: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KfsFileGrowthPlan {
    pub last_extent_index: usize,
    pub new_extent_index: usize,
    pub current_capacity: u32,
    pub additional_blocks: u32,
    pub grow_start: u32,
    pub grow_end: u32,
}

pub fn validate_read_range(
    size_bytes: u32,
    file_offset: u32,
    len: u32,
) -> Result<KfsFileRange, StorageError> {
    let range = validate_write_range(file_offset, len)?;
    if range.end > size_bytes {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(range)
}

pub fn validate_write_range(file_offset: u32, len: u32) -> Result<KfsFileRange, StorageError> {
    let end = match file_offset.checked_add(len) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    Ok(KfsFileRange { end })
}

pub fn file_capacity_bytes(metadata: FileMetadata) -> Result<u32, StorageError> {
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

pub fn extent_file_end(
    extent_file_start: u32,
    extent_block_count: u32,
) -> Result<u32, StorageError> {
    let extent_bytes = match extent_block_count.checked_mul(BLOCK_SIZE) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    match extent_file_start.checked_add(extent_bytes) {
        Some(value) => Ok(value),
        None => Err(StorageError::INVALID_FILESYSTEM),
    }
}

pub fn extent_overlap(
    file_offset: u32,
    range_end: u32,
    extent_file_start: u32,
    extent_start_block: u32,
    extent_block_count: u32,
) -> Result<Option<KfsFileExtentOverlap>, StorageError> {
    let extent_file_end = extent_file_end(extent_file_start, extent_block_count)?;
    if range_end <= extent_file_start || file_offset >= extent_file_end {
        return Ok(None);
    }
    Ok(Some(KfsFileExtentOverlap {
        extent_start_block,
        extent_file_start,
        extent_file_end,
        copy_start: max_u32(file_offset, extent_file_start),
        copy_end: min_u32(range_end, extent_file_end),
    }))
}

pub fn plan_file_growth(
    metadata: FileMetadata,
    required_size: u32,
) -> Result<KfsFileGrowthPlan, StorageError> {
    if metadata.extent_count == 0 || metadata.extent_count as usize > KFS_MAX_INLINE_EXTENTS {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let current_capacity = file_capacity_bytes(metadata)?;
    if required_size <= current_capacity {
        return Err(StorageError::INVALID_FILESYSTEM);
    }

    let last_extent_index = metadata.extent_count as usize - 1;
    let last_start = metadata.extent_start_blocks[last_extent_index];
    let last_count = metadata.extent_block_counts[last_extent_index];
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
    Ok(KfsFileGrowthPlan {
        last_extent_index,
        new_extent_index: metadata.extent_count as usize,
        current_capacity,
        additional_blocks,
        grow_start,
        grow_end,
    })
}

pub fn apply_extended_last_extent(
    mut metadata: FileMetadata,
    plan: KfsFileGrowthPlan,
) -> Result<FileMetadata, StorageError> {
    let block_count = metadata.extent_block_counts[plan.last_extent_index];
    metadata.extent_block_counts[plan.last_extent_index] =
        match block_count.checked_add(plan.additional_blocks) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
    Ok(metadata)
}

pub fn apply_new_extent(
    mut metadata: FileMetadata,
    new_extent_start: u32,
    plan: KfsFileGrowthPlan,
) -> Result<FileMetadata, StorageError> {
    if plan.new_extent_index >= KFS_MAX_INLINE_EXTENTS {
        return Err(StorageError::OUTPUT_BUFFER_TOO_SMALL);
    }
    metadata.extent_start_blocks[plan.new_extent_index] = new_extent_start;
    metadata.extent_block_counts[plan.new_extent_index] = plan.additional_blocks;
    metadata.extent_count = match metadata.extent_count.checked_add(1) {
        Some(value) => value,
        None => return Err(StorageError::INVALID_FILESYSTEM),
    };
    Ok(metadata)
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

const fn min_u32(left: u32, right: u32) -> u32 {
    if left < right {
        left
    } else {
        right
    }
}

const fn max_u32(left: u32, right: u32) -> u32 {
    if left > right {
        left
    } else {
        right
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn metadata(extent_count: u32, starts: &[u32], counts: &[u32]) -> FileMetadata {
        let mut extent_start_blocks = [0; KFS_MAX_INLINE_EXTENTS];
        let mut extent_block_counts = [0; KFS_MAX_INLINE_EXTENTS];
        let mut index = 0;
        while index < starts.len() {
            extent_start_blocks[index] = starts[index];
            index += 1;
        }
        index = 0;
        while index < counts.len() {
            extent_block_counts[index] = counts[index];
            index += 1;
        }
        FileMetadata {
            inode_id: 7,
            size_bytes: 0,
            extent_count,
            extent_start_blocks,
            extent_block_counts,
        }
    }

    #[test]
    fn validate_ranges_reject_overflow_and_reads_past_file_size() {
        assert_eq!(validate_write_range(10, 5), Ok(KfsFileRange { end: 15 }));
        assert_eq!(
            validate_write_range(u32::MAX, 1),
            Err(StorageError::INVALID_FILESYSTEM),
        );
        assert_eq!(
            validate_read_range(20, 10, 10),
            Ok(KfsFileRange { end: 20 })
        );
        assert_eq!(
            validate_read_range(20, 10, 11),
            Err(StorageError::INVALID_FILESYSTEM),
        );
    }

    #[test]
    fn file_capacity_bytes_sums_extent_capacity() {
        assert_eq!(
            file_capacity_bytes(metadata(2, &[4, 9], &[2, 3])),
            Ok(5 * BLOCK_SIZE),
        );
        assert_eq!(
            file_capacity_bytes(metadata(1, &[4], &[u32::MAX])),
            Err(StorageError::INVALID_FILESYSTEM),
        );
    }

    #[test]
    fn extent_overlap_reports_only_intersecting_file_ranges() {
        assert_eq!(extent_overlap(0, 10, 512, 20, 1), Ok(None));
        assert_eq!(
            extent_overlap(500, 700, 512, 20, 1),
            Ok(Some(KfsFileExtentOverlap {
                extent_start_block: 20,
                extent_file_start: 512,
                extent_file_end: 1024,
                copy_start: 512,
                copy_end: 700,
            })),
        );
    }

    #[test]
    fn plan_file_growth_extends_from_current_capacity() {
        assert_eq!(
            plan_file_growth(metadata(1, &[10], &[2]), 1300),
            Ok(KfsFileGrowthPlan {
                last_extent_index: 0,
                new_extent_index: 1,
                current_capacity: 1024,
                additional_blocks: 1,
                grow_start: 12,
                grow_end: 13,
            }),
        );
    }

    #[test]
    fn apply_growth_updates_last_or_new_extent() {
        let existing = metadata(1, &[10], &[2]);
        let plan = plan_file_growth(existing, 1300).unwrap();

        assert_eq!(
            apply_extended_last_extent(existing, plan)
                .unwrap()
                .extent_block_counts[0],
            3,
        );
        let appended = apply_new_extent(existing, 30, plan).unwrap();
        assert_eq!(appended.extent_count, 2);
        assert_eq!(appended.extent_start_blocks[1], 30);
        assert_eq!(appended.extent_block_counts[1], 1);
    }
}
