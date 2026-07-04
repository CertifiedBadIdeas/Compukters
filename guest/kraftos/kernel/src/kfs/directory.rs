use crate::kfs::block_io::BLOCK_SIZE;
use crate::kfs::error::StorageError;

pub const KFS_DIRECTORY_ENTRY_SIZE: u32 = 64;
pub const KFS_MAX_NAME_BYTES: usize = 56;
pub const KFS_DIRECTORY_ENTRIES_PER_BLOCK: usize = (BLOCK_SIZE / KFS_DIRECTORY_ENTRY_SIZE) as usize;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KfsDirectoryEntryHeader {
    Free,
    Live { inode_id: u32, name_len: usize },
    Deleted,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KfsDirectoryEntryRecord {
    pub bytes: [u8; KFS_DIRECTORY_ENTRY_SIZE as usize],
}

pub fn directory_size_is_aligned(size_bytes: u32) -> bool {
    size_bytes % KFS_DIRECTORY_ENTRY_SIZE == 0
}

pub fn validate_name(name: &[u8]) -> Result<(), StorageError> {
    if name.is_empty() || name.len() > KFS_MAX_NAME_BYTES {
        return Err(StorageError::INVALID_FILESYSTEM);
    }
    Ok(())
}

pub fn decode_entry_header(
    state: u8,
    name_len: u8,
    reserved0: u8,
    reserved1: u8,
    inode_id: u32,
) -> Result<KfsDirectoryEntryHeader, StorageError> {
    match state {
        0 => Ok(KfsDirectoryEntryHeader::Free),
        1 => {
            let name_len = name_len as usize;
            if name_len == 0 || name_len > KFS_MAX_NAME_BYTES || reserved0 != 0 || reserved1 != 0 {
                return Err(StorageError::INVALID_FILESYSTEM);
            }
            Ok(KfsDirectoryEntryHeader::Live { inode_id, name_len })
        }
        2 => Ok(KfsDirectoryEntryHeader::Deleted),
        _ => Err(StorageError::INVALID_FILESYSTEM),
    }
}

pub fn encode_entry(inode_id: u32, name: &[u8]) -> Result<KfsDirectoryEntryRecord, StorageError> {
    validate_name(name)?;

    let mut bytes = [0_u8; KFS_DIRECTORY_ENTRY_SIZE as usize];
    bytes[0] = 1;
    bytes[1] = name.len() as u8;
    bytes[4..8].copy_from_slice(&inode_id.to_le_bytes());
    bytes[8..8 + name.len()].copy_from_slice(name);
    Ok(KfsDirectoryEntryRecord { bytes })
}

pub fn encode_deleted_entry() -> KfsDirectoryEntryRecord {
    let mut bytes = [0_u8; KFS_DIRECTORY_ENTRY_SIZE as usize];
    bytes[0] = 2;
    KfsDirectoryEntryRecord { bytes }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn directory_size_alignment_uses_directory_record_size() {
        assert!(directory_size_is_aligned(0));
        assert!(directory_size_is_aligned(64));
        assert!(!directory_size_is_aligned(63));
        assert!(!directory_size_is_aligned(65));
    }

    #[test]
    fn decode_entry_header_accepts_free_live_and_deleted_entries() {
        assert_eq!(
            decode_entry_header(0, 99, 1, 2, 7),
            Ok(KfsDirectoryEntryHeader::Free),
        );
        assert_eq!(
            decode_entry_header(1, 3, 0, 0, 7),
            Ok(KfsDirectoryEntryHeader::Live {
                inode_id: 7,
                name_len: 3,
            }),
        );
        assert_eq!(
            decode_entry_header(2, 99, 1, 2, 7),
            Ok(KfsDirectoryEntryHeader::Deleted),
        );
    }

    #[test]
    fn decode_entry_header_rejects_invalid_live_entry_headers() {
        assert_eq!(
            decode_entry_header(1, 0, 0, 0, 7),
            Err(StorageError::INVALID_FILESYSTEM),
        );
        assert_eq!(
            decode_entry_header(1, 57, 0, 0, 7),
            Err(StorageError::INVALID_FILESYSTEM),
        );
        assert_eq!(
            decode_entry_header(1, 3, 1, 0, 7),
            Err(StorageError::INVALID_FILESYSTEM),
        );
        assert_eq!(
            decode_entry_header(9, 3, 0, 0, 7),
            Err(StorageError::INVALID_FILESYSTEM),
        );
    }

    #[test]
    fn encode_entry_writes_kfs_directory_wire_layout() {
        let record = encode_entry(0x1122_3344, b"bin").unwrap();

        assert_eq!(record.bytes[0], 1);
        assert_eq!(record.bytes[1], 3);
        assert_eq!(&record.bytes[2..4], &[0, 0]);
        assert_eq!(&record.bytes[4..8], &[0x44, 0x33, 0x22, 0x11]);
        assert_eq!(&record.bytes[8..11], b"bin");
        assert!(record.bytes[11..].iter().all(|byte| *byte == 0));
    }

    #[test]
    fn encode_entry_rejects_invalid_names() {
        assert_eq!(encode_entry(1, b""), Err(StorageError::INVALID_FILESYSTEM));
        assert_eq!(
            encode_entry(1, &[b'a'; KFS_MAX_NAME_BYTES + 1]),
            Err(StorageError::INVALID_FILESYSTEM),
        );
    }

    #[test]
    fn encode_deleted_entry_writes_deleted_marker_only() {
        let record = encode_deleted_entry();

        assert_eq!(record.bytes[0], 2);
        assert!(record.bytes[1..].iter().all(|byte| *byte == 0));
    }
}
