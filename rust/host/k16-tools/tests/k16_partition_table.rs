use k16_tools::partition::{
    decode_partition_table, encode_partition_table, validate_partition_table, K16PartitionTable,
    PartitionEntry, PartitionType, K16PT_BLOCK_SIZE, K16PT_ENTRY_SIZE, K16PT_HEADER_SIZE,
};

#[test]
fn k16pt_encodes_boot_and_root_entries_in_lba0_block() {
    let table = K16PartitionTable {
        entries: vec![
            PartitionEntry {
                partition_type: PartitionType::Boot,
                flags: 0,
                start_lba: 1,
                block_count: 32,
                name: "boot".to_string(),
            },
            PartitionEntry {
                partition_type: PartitionType::Root,
                flags: 0,
                start_lba: 33,
                block_count: 95,
                name: "root".to_string(),
            },
        ],
    };

    let bytes = encode_partition_table(&table).expect("table encodes");

    assert_eq!(bytes.len(), K16PT_BLOCK_SIZE);
    assert_eq!(&bytes[0..5], b"K16PT");
    assert_eq!(bytes[5], 1);
    assert_eq!(bytes[6], 2);
    assert_eq!(bytes[7], 0);
    assert_eq!(u32::from_le_bytes(bytes[8..12].try_into().unwrap()), 0);
    assert_eq!(u32::from_le_bytes(bytes[12..16].try_into().unwrap()), 1);

    let boot_offset = K16PT_HEADER_SIZE;
    assert_eq!(&bytes[boot_offset..boot_offset + 4], b"BOOT");
    assert_eq!(
        u32::from_le_bytes(bytes[boot_offset + 8..boot_offset + 12].try_into().unwrap()),
        1
    );
    assert_eq!(
        u32::from_le_bytes(
            bytes[boot_offset + 12..boot_offset + 16]
                .try_into()
                .unwrap()
        ),
        32
    );
    assert_eq!(&bytes[boot_offset + 16..boot_offset + 20], b"boot");

    let root_offset = K16PT_HEADER_SIZE + K16PT_ENTRY_SIZE;
    assert_eq!(&bytes[root_offset..root_offset + 4], b"ROOT");
    assert_eq!(
        u32::from_le_bytes(bytes[root_offset + 8..root_offset + 12].try_into().unwrap()),
        33
    );
    assert_eq!(
        u32::from_le_bytes(
            bytes[root_offset + 12..root_offset + 16]
                .try_into()
                .unwrap()
        ),
        95
    );
    assert_eq!(&bytes[root_offset + 16..root_offset + 20], b"root");
}

#[test]
fn k16pt_decodes_and_validates_non_overlapping_entries() {
    let table = K16PartitionTable {
        entries: vec![
            PartitionEntry {
                partition_type: PartitionType::Boot,
                flags: 0,
                start_lba: 1,
                block_count: 32,
                name: "boot".to_string(),
            },
            PartitionEntry {
                partition_type: PartitionType::Root,
                flags: 0,
                start_lba: 33,
                block_count: 95,
                name: "root".to_string(),
            },
        ],
    };
    let bytes = encode_partition_table(&table).expect("table encodes");

    let decoded = decode_partition_table(&bytes).expect("table decodes");

    assert_eq!(decoded, table);
    validate_partition_table(&decoded, 128).expect("table validates");
}

#[test]
fn k16pt_rejects_overlap_out_of_bounds_and_reserved_lba() {
    let overlap = K16PartitionTable {
        entries: vec![
            PartitionEntry {
                partition_type: PartitionType::Boot,
                flags: 0,
                start_lba: 1,
                block_count: 32,
                name: "boot".to_string(),
            },
            PartitionEntry {
                partition_type: PartitionType::Root,
                flags: 0,
                start_lba: 32,
                block_count: 10,
                name: "root".to_string(),
            },
        ],
    };
    assert!(validate_partition_table(&overlap, 128)
        .unwrap_err()
        .contains("overlaps"));

    let out_of_bounds = K16PartitionTable {
        entries: vec![PartitionEntry {
            partition_type: PartitionType::Root,
            flags: 0,
            start_lba: 120,
            block_count: 16,
            name: "root".to_string(),
        }],
    };
    assert!(validate_partition_table(&out_of_bounds, 128)
        .unwrap_err()
        .contains("outside media"));

    let reserved_lba = K16PartitionTable {
        entries: vec![PartitionEntry {
            partition_type: PartitionType::Boot,
            flags: 0,
            start_lba: 0,
            block_count: 1,
            name: "boot".to_string(),
        }],
    };
    assert!(validate_partition_table(&reserved_lba, 128)
        .unwrap_err()
        .contains("reserved"));
}
