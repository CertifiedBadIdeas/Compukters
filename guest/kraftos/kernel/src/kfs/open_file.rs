use crate::kfs::error::StorageError;
use crate::kfs::file::{validate_read_range, validate_write_range};
use crate::kfs::types::FileMetadata;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KfsOpenFile {
    metadata: FileMetadata,
    offset: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KfsOpenFileRange {
    pub offset: u32,
    pub len: u32,
}

impl KfsOpenFile {
    pub fn regular_file(metadata: FileMetadata, append: bool) -> Self {
        let offset = if append { metadata.size_bytes } else { 0 };
        Self { metadata, offset }
    }

    pub const fn metadata(&self) -> FileMetadata {
        self.metadata
    }

    pub const fn offset(&self) -> u32 {
        self.offset
    }

    pub const fn inode_id(&self) -> u32 {
        self.metadata.inode_id
    }

    pub fn read_plan(&self, len: u32) -> KfsOpenFileRange {
        let remaining = self.metadata.size_bytes.saturating_sub(self.offset);
        KfsOpenFileRange {
            offset: self.offset,
            len: min_u32(len, remaining),
        }
    }

    pub fn finish_read(&mut self, len: u32) -> Result<(), StorageError> {
        validate_read_range(self.metadata.size_bytes, self.offset, len)?;
        self.offset = match self.offset.checked_add(len) {
            Some(value) => value,
            None => return Err(StorageError::INVALID_FILESYSTEM),
        };
        Ok(())
    }

    pub fn write_plan(&self, len: u32) -> Result<KfsOpenFileRange, StorageError> {
        validate_write_range(self.offset, len)?;
        Ok(KfsOpenFileRange {
            offset: self.offset,
            len,
        })
    }

    pub fn finish_write(&mut self, metadata: FileMetadata, len: u32) -> Result<(), StorageError> {
        let range = validate_write_range(self.offset, len)?;
        self.metadata = metadata;
        self.offset = range.end;
        Ok(())
    }

    pub fn seek_set(&mut self, offset: u32) -> Result<u32, StorageError> {
        if offset > self.metadata.size_bytes {
            return Err(StorageError::INVALID_FILESYSTEM);
        }
        self.offset = offset;
        Ok(offset)
    }

    pub fn seek_end(&mut self) -> u32 {
        self.offset = self.metadata.size_bytes;
        self.offset
    }
}

const fn min_u32(left: u32, right: u32) -> u32 {
    if left < right {
        left
    } else {
        right
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn metadata(size_bytes: u32) -> FileMetadata {
        FileMetadata {
            inode_id: 42,
            size_bytes,
            extent_count: 1,
            extent_start_blocks: [7, 0, 0, 0],
            extent_block_counts: [1, 0, 0, 0],
        }
    }

    #[test]
    fn regular_file_starts_at_zero_or_end_for_append() {
        assert_eq!(KfsOpenFile::regular_file(metadata(11), false).offset(), 0);
        assert_eq!(KfsOpenFile::regular_file(metadata(11), true).offset(), 11);
    }

    #[test]
    fn read_plan_clamps_to_eof_and_finish_read_advances() {
        let mut file = KfsOpenFile::regular_file(metadata(7), false);

        assert_eq!(file.read_plan(4), KfsOpenFileRange { offset: 0, len: 4 });
        file.finish_read(4).expect("read advances");
        assert_eq!(file.read_plan(4), KfsOpenFileRange { offset: 4, len: 3 });
        file.finish_read(3).expect("second read advances");
        assert_eq!(file.read_plan(4), KfsOpenFileRange { offset: 7, len: 0 });
        assert_eq!(file.finish_read(1), Err(StorageError::INVALID_FILESYSTEM));
    }

    #[test]
    fn write_plan_and_finish_write_refresh_metadata_after_success() {
        let mut file = KfsOpenFile::regular_file(metadata(3), false);

        assert_eq!(
            file.write_plan(5).expect("write plans"),
            KfsOpenFileRange { offset: 0, len: 5 }
        );
        file.finish_write(metadata(5), 5)
            .expect("write completion advances");

        assert_eq!(file.offset(), 5);
        assert_eq!(file.metadata().size_bytes, 5);
    }

    #[test]
    fn seek_accepts_offsets_within_current_size() {
        let mut file = KfsOpenFile::regular_file(metadata(11), true);

        assert_eq!(file.seek_set(0), Ok(0));
        assert_eq!(file.offset(), 0);
        assert_eq!(file.seek_end(), 11);
        assert_eq!(file.offset(), 11);
        assert_eq!(file.seek_set(12), Err(StorageError::INVALID_FILESYSTEM));
    }
}
