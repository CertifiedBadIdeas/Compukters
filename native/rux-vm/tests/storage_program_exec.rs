use rux_vm::{ruxe, storage_image};

#[test]
fn runtime_reader_loads_program_ruxe_from_root_ruxfs() {
    let init = encode_ruxe(3, 0x8000, 0x8000, &[0x01, 0x00]);
    let root = rootfs_with_file("/bin/init.ruxe", &init);
    let storage0 = storage0_media_with_root(root);

    let loaded = storage_image::read_ruxfs_file_from_partition(&storage0, "ROOT", "/bin/init.ruxe")
        .expect("program reads from ROOT RuxFS");
    let executable =
        ruxe::decode_program_rux16_executable(&loaded).expect("program RUXE validates for exec");

    assert_eq!(loaded, init);
    assert_eq!(executable.entry_pc, 0x8000);
    assert_eq!(executable.load_addr, 0x8000);
    assert_eq!(executable.payload, vec![0x01, 0x00]);
}

#[test]
fn program_exec_rejects_kernel_ruxe_without_fallback() {
    let kernel = encode_ruxe(2, 0x4000, 0x4000, &[0x01, 0x00]);

    let error = ruxe::decode_program_rux16_executable(&kernel).unwrap_err();

    assert!(error.contains("expected RUXE program ABI kind"), "{error}");
}

#[test]
fn runtime_reader_reports_missing_partition_or_path_without_fallback() {
    let root = rootfs_with_file("/bin/init.ruxe", &[0x01, 0x00]);
    let storage0 = storage0_media_with_root(root);

    assert!(
        storage_image::read_ruxfs_file_from_partition(&storage0, "DATA", "/bin/init.ruxe")
            .unwrap_err()
            .contains("RUXPT partition `DATA` not found")
    );
    assert!(
        storage_image::read_ruxfs_file_from_partition(&storage0, "ROOT", "/sbin/init.ruxe")
            .unwrap_err()
            .contains("RuxFS directory entry `sbin` not found")
    );
}

fn storage0_media_with_root(root: Vec<u8>) -> Vec<u8> {
    const BLOCK_SIZE: usize = 512;
    const STORAGE_BLOCKS: usize = 128;
    const ROOT_START_LBA: usize = 33;
    let mut media = vec![0_u8; STORAGE_BLOCKS * BLOCK_SIZE];
    encode_ruxpt(&mut media[..BLOCK_SIZE]);
    let root_offset = ROOT_START_LBA * BLOCK_SIZE;
    media[root_offset..root_offset + root.len()].copy_from_slice(&root);
    media
}

fn encode_ruxpt(block: &mut [u8]) {
    block[0..5].copy_from_slice(b"RUXPT");
    block[5] = 1;
    block[6] = 2;
    write_u32(block, 8, 0);
    write_u32(block, 12, 1);
    encode_partition_entry(block, 16, b"BOOT", 1, 32, "boot");
    encode_partition_entry(block, 48, b"ROOT", 33, 95, "root");
}

fn encode_partition_entry(
    block: &mut [u8],
    offset: usize,
    kind: &[u8; 4],
    start_lba: u32,
    block_count: u32,
    name: &str,
) {
    block[offset..offset + 4].copy_from_slice(kind);
    write_u32(block, offset + 8, start_lba);
    write_u32(block, offset + 12, block_count);
    block[offset + 16..offset + 16 + name.len()].copy_from_slice(name.as_bytes());
}

fn rootfs_with_file(path: &str, contents: &[u8]) -> Vec<u8> {
    assert_eq!(path, "/bin/init.ruxe");
    const BLOCK_SIZE: usize = 512;
    const TOTAL_BLOCKS: usize = 95;
    let mut image = vec![0_u8; TOTAL_BLOCKS * BLOCK_SIZE];
    encode_ruxfs_superblock(&mut image);
    encode_inode(&mut image, 1, 2, 64, 10, 1);
    encode_inode(&mut image, 2, 2, 64, 11, 1);
    encode_inode(&mut image, 3, 1, contents.len() as u64, 12, 1);
    encode_directory_entry(&mut image, 10 * BLOCK_SIZE, 2, "bin");
    encode_directory_entry(&mut image, 11 * BLOCK_SIZE, 3, "init.ruxe");
    image[12 * BLOCK_SIZE..12 * BLOCK_SIZE + contents.len()].copy_from_slice(contents);
    image
}

fn encode_ruxfs_superblock(image: &mut [u8]) {
    image[0..5].copy_from_slice(b"RUXFS");
    image[5] = 1;
    write_u32(image, 0x08, 512);
    write_u32(image, 0x0c, 95);
    write_u32(image, 0x10, 1);
    write_u32(image, 0x14, 1);
    write_u32(image, 0x18, 2);
    write_u32(image, 0x1c, 8);
    write_u32(image, 0x20, 1);
}

fn encode_inode(
    image: &mut [u8],
    inode_id: usize,
    state: u8,
    size_bytes: u64,
    start_block: u32,
    block_count: u32,
) {
    let offset = 2 * 512 + inode_id * 64;
    image[offset] = state;
    write_u64(image, offset + 0x08, size_bytes);
    image[offset + 0x10] = 1;
    write_u32(image, offset + 0x20, start_block);
    write_u32(image, offset + 0x24, block_count);
}

fn encode_directory_entry(image: &mut [u8], offset: usize, inode_id: u32, name: &str) {
    image[offset] = 1;
    image[offset + 1] = name.len() as u8;
    write_u32(image, offset + 0x04, inode_id);
    image[offset + 0x08..offset + 0x08 + name.len()].copy_from_slice(name.as_bytes());
}

fn encode_ruxe(abi_kind: u32, entry_pc: u32, load_addr: u32, payload: &[u8]) -> Vec<u8> {
    let mut bytes = Vec::new();
    bytes.extend_from_slice(b"RUXE");
    bytes.extend_from_slice(&1_u16.to_le_bytes());
    bytes.extend_from_slice(&32_u16.to_le_bytes());
    bytes.extend_from_slice(&1_u16.to_le_bytes());
    bytes.extend_from_slice(&0_u16.to_le_bytes());
    bytes.extend_from_slice(&entry_pc.to_le_bytes());
    bytes.extend_from_slice(&32_u32.to_le_bytes());
    bytes.extend_from_slice(&1_u32.to_le_bytes());
    bytes.extend_from_slice(&abi_kind.to_le_bytes());
    bytes.extend_from_slice(&0_u32.to_le_bytes());
    bytes.extend_from_slice(&1_u32.to_le_bytes());
    bytes.extend_from_slice(&load_addr.to_le_bytes());
    bytes.extend_from_slice(&52_u32.to_le_bytes());
    bytes.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    bytes.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    bytes.extend_from_slice(payload);
    bytes
}

fn write_u32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

fn write_u64(bytes: &mut [u8], offset: usize, value: u64) {
    bytes[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}
