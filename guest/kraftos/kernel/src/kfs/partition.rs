use crate::kfs::storage::StorageError;

const K16PT_MAGIC: &[u8; 5] = b"K16PT";
const K16PT_VERSION: u8 = 1;
const K16PT_HEADER_SIZE: usize = 16;
const K16PT_ENTRY_SIZE: usize = 32;
const K16PT_MAX_ENTRIES: u8 = 15;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KfsPartition {
    pub start_lba: u32,
    pub block_count: u32,
}

impl KfsPartition {
    pub fn decode_from_k16pt(
        block: &[u8; crate::kfs::storage::BLOCK_SIZE as usize],
        partition_type: &[u8; 4],
        capacity_blocks: u32,
    ) -> Result<Self, StorageError> {
        if !bytes_eq(&block[0..5], K16PT_MAGIC) || block[5] != K16PT_VERSION || block[7] != 0 {
            return Err(StorageError::INVALID_PARTITION_TABLE);
        }
        let entry_count = block[6];
        if entry_count > K16PT_MAX_ENTRIES || read_u32(block, 8) != 0 || read_u32(block, 12) != 1 {
            return Err(StorageError::INVALID_PARTITION_TABLE);
        }

        let mut index = 0;
        while index < entry_count as usize {
            let offset = K16PT_HEADER_SIZE + index * K16PT_ENTRY_SIZE;
            let start_lba = read_u32(block, offset + 8);
            let block_count = read_u32(block, offset + 12);
            if read_u32(block, offset + 4) != 0 || start_lba < 1 || block_count == 0 {
                return Err(StorageError::INVALID_PARTITION_TABLE);
            }
            let end_lba = match start_lba.checked_add(block_count) {
                Some(value) => value,
                None => return Err(StorageError::INVALID_PARTITION_TABLE),
            };
            if end_lba > capacity_blocks {
                return Err(StorageError::INVALID_PARTITION_TABLE);
            }
            if bytes_eq(&block[offset..offset + 4], partition_type) {
                return Ok(Self {
                    start_lba,
                    block_count,
                });
            }
            index += 1;
        }

        Err(StorageError::PARTITION_NOT_FOUND)
    }
}

fn read_u32(bytes: &[u8; crate::kfs::storage::BLOCK_SIZE as usize], offset: usize) -> u32 {
    u32::from_le_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}

fn bytes_eq(left: &[u8], right: &[u8]) -> bool {
    if left.len() != right.len() {
        return false;
    }
    let mut index = 0;
    while index < left.len() {
        if left[index] != right[index] {
            return false;
        }
        index += 1;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write_u32(
        block: &mut [u8; crate::kfs::storage::BLOCK_SIZE as usize],
        offset: usize,
        value: u32,
    ) {
        block[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
    }

    fn partition_block(
        partition_type: &[u8; 4],
        start_lba: u32,
        block_count: u32,
    ) -> [u8; crate::kfs::storage::BLOCK_SIZE as usize] {
        let mut block = [0_u8; crate::kfs::storage::BLOCK_SIZE as usize];
        block[0..5].copy_from_slice(b"K16PT");
        block[5] = 1;
        block[6] = 1;
        write_u32(&mut block, 12, 1);
        block[16..20].copy_from_slice(partition_type);
        write_u32(&mut block, 24, start_lba);
        write_u32(&mut block, 28, block_count);
        block
    }

    #[test]
    fn k16pt_decode_selects_matching_partition() {
        let block = partition_block(b"ROOT", 3, 42);

        assert_eq!(
            KfsPartition::decode_from_k16pt(&block, b"ROOT", 64),
            Ok(KfsPartition {
                start_lba: 3,
                block_count: 42,
            }),
        );
    }

    #[test]
    fn k16pt_decode_rejects_invalid_magic() {
        let mut block = partition_block(b"ROOT", 3, 42);
        block[0] = b'X';

        assert_eq!(
            KfsPartition::decode_from_k16pt(&block, b"ROOT", 64),
            Err(crate::kfs::storage::StorageError::INVALID_PARTITION_TABLE),
        );
    }

    #[test]
    fn k16pt_decode_rejects_out_of_bounds_partition() {
        let block = partition_block(b"ROOT", 60, 8);

        assert_eq!(
            KfsPartition::decode_from_k16pt(&block, b"ROOT", 64),
            Err(crate::kfs::storage::StorageError::INVALID_PARTITION_TABLE),
        );
    }
}
