use rux_compiler::{ruxe, ruxfs, ruxfs_volume, volume};

#[test]
fn ruxfs_volume_reader_loads_kernel_file_from_root_partition() {
    let kernel_bytes =
        ruxe::encode_rux16_executable(&[0x01, 0x00], ruxe::RuxeAbiKind::Kernel, 0x4000, 0x4000)
            .expect("kernel RUXE encodes");
    let mut root = ruxfs::format_empty_filesystem(95).expect("root formats");
    ruxfs::create_directory(&mut root, "/boot").expect("boot directory creates");
    ruxfs::write_file(&mut root, "/boot/kernel.ruxe", &kernel_bytes).expect("kernel writes");

    let mut storage0 = volume::create_initialized_volume(65536).expect("volume initializes");
    volume::replace_partition(&mut storage0, "ROOT", &root).expect("ROOT partition replaces");

    let loaded = ruxfs_volume::read_file_from_partition(&storage0, "ROOT", "/boot/kernel.ruxe")
        .expect("kernel reads from ROOT partition");

    assert_eq!(loaded, kernel_bytes);
}

#[test]
fn ruxfs_volume_reader_reports_missing_partition_or_path_without_fallback() {
    let mut root = ruxfs::format_empty_filesystem(95).expect("root formats");
    ruxfs::create_directory(&mut root, "/boot").expect("boot directory creates");
    let mut storage0 = volume::create_initialized_volume(65536).expect("volume initializes");
    volume::replace_partition(&mut storage0, "ROOT", &root).expect("ROOT partition replaces");

    assert!(
        ruxfs_volume::read_file_from_partition(&storage0, "DATA", "/boot/kernel.ruxe")
            .unwrap_err()
            .contains("RUXPT partition `DATA` not found")
    );
    assert!(
        ruxfs_volume::read_file_from_partition(&storage0, "ROOT", "/boot/kernel.ruxe")
            .unwrap_err()
            .contains("directory entry `kernel.ruxe` not found")
    );
}
