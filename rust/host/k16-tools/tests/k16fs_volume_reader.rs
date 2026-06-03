use k16_tools::{k16e, k16fs, k16fs_volume, volume};

const TEST_VOLUME_SIZE: usize = 1_048_576;
const TEST_ROOT_BLOCKS: u32 = 1_791;

#[test]
fn k16fs_volume_reader_loads_kernel_file_from_root_partition() {
    let kernel_bytes =
        k16e::encode_k16_executable(&[0x01, 0x00], k16e::K16eAbiKind::Kernel, 0x4000, 0x4000)
            .expect("kernel K16E encodes");
    let mut root = k16fs::format_empty_filesystem(TEST_ROOT_BLOCKS).expect("root formats");
    k16fs::create_directory(&mut root, "/boot").expect("boot directory creates");
    k16fs::write_file(&mut root, "/boot/kernel.kx", &kernel_bytes).expect("kernel writes");

    let mut storage0 = volume::create_initialized_volume(TEST_VOLUME_SIZE).expect("volume initializes");
    volume::replace_partition(&mut storage0, "ROOT", &root).expect("ROOT partition replaces");

    let loaded = k16fs_volume::read_file_from_partition(&storage0, "ROOT", "/boot/kernel.kx")
        .expect("kernel reads from ROOT partition");

    assert_eq!(loaded, kernel_bytes);
}

#[test]
fn k16fs_volume_reader_reports_missing_partition_or_path_without_fallback() {
    let mut root = k16fs::format_empty_filesystem(TEST_ROOT_BLOCKS).expect("root formats");
    k16fs::create_directory(&mut root, "/boot").expect("boot directory creates");
    let mut storage0 = volume::create_initialized_volume(TEST_VOLUME_SIZE).expect("volume initializes");
    volume::replace_partition(&mut storage0, "ROOT", &root).expect("ROOT partition replaces");

    assert!(
        k16fs_volume::read_file_from_partition(&storage0, "DATA", "/boot/kernel.kx")
            .unwrap_err()
            .contains("K16PT partition `DATA` not found")
    );
    assert!(
        k16fs_volume::read_file_from_partition(&storage0, "ROOT", "/boot/kernel.kx")
            .unwrap_err()
            .contains("directory entry `kernel.kx` not found")
    );
}
