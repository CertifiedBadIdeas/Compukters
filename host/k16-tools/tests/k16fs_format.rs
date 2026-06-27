use k16_tools::k16fs::{
    decode_inode, decode_superblock, format_empty_filesystem, validate_filesystem, InodeState,
    K16FS_BLOCK_SIZE, K16FS_DEFAULT_INODE_COUNT, K16FS_INODE_SIZE,
};

#[test]
fn k16fs_formats_empty_extent_filesystem_with_root_directory() {
    let image = format_empty_filesystem(128).expect("filesystem formats");

    assert_eq!(image.len(), 128 * K16FS_BLOCK_SIZE);
    assert_eq!(&image[0..5], b"K16FS");
    let superblock = decode_superblock(&image).expect("superblock decodes");
    assert_eq!(superblock.total_blocks, 128);
    assert_eq!(superblock.block_size, K16FS_BLOCK_SIZE as u32);
    assert_eq!(superblock.bitmap_start_block, 1);
    assert_eq!(superblock.bitmap_block_count, 1);
    assert_eq!(superblock.inode_table_start_block, 2);
    assert_eq!(
        superblock.inode_table_block_count,
        (K16FS_DEFAULT_INODE_COUNT * K16FS_INODE_SIZE).div_ceil(K16FS_BLOCK_SIZE as u32)
    );
    assert_eq!(superblock.root_inode_id, 1);

    let root = decode_inode(&image, &superblock, superblock.root_inode_id).expect("root decodes");
    assert_eq!(root.state, InodeState::Directory);
    assert_eq!(root.size_bytes, 0);
    assert_eq!(root.extents.len(), 1);
    assert_eq!(root.extents[0].start_block, 10);
    assert_eq!(root.extents[0].block_count, 1);

    validate_filesystem(&image).expect("filesystem validates");
}

#[test]
fn k16fs_rejects_bad_magic_and_out_of_bounds_metadata() {
    let mut bad_magic = format_empty_filesystem(128).expect("filesystem formats");
    bad_magic[0] = b'X';
    assert!(validate_filesystem(&bad_magic)
        .unwrap_err()
        .contains("invalid K16FS magic"));

    let mut out_of_bounds = format_empty_filesystem(128).expect("filesystem formats");
    out_of_bounds[0x18..0x1c].copy_from_slice(&120_u32.to_le_bytes());
    out_of_bounds[0x1c..0x20].copy_from_slice(&16_u32.to_le_bytes());
    assert!(validate_filesystem(&out_of_bounds)
        .unwrap_err()
        .contains("inode table outside filesystem"));

    let mut bitmap_over_superblock = format_empty_filesystem(128).expect("filesystem formats");
    bitmap_over_superblock[0x10..0x14].copy_from_slice(&0_u32.to_le_bytes());
    assert!(validate_filesystem(&bitmap_over_superblock)
        .unwrap_err()
        .contains("bitmap overlaps superblock"));
}

#[test]
fn k16fs_rejects_invalid_root_inode() {
    let mut image = format_empty_filesystem(128).expect("filesystem formats");
    let superblock = decode_superblock(&image).expect("superblock decodes");
    let root_offset = (superblock.inode_table_start_block as usize * K16FS_BLOCK_SIZE)
        + K16FS_INODE_SIZE as usize;
    image[root_offset] = InodeState::File as u8;

    assert!(validate_filesystem(&image)
        .unwrap_err()
        .contains("root inode is not a directory"));
}
