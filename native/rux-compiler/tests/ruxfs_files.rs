use rux_compiler::ruxfs::{
    create_directory, format_empty_filesystem, list_directory, read_file, validate_filesystem,
    write_file,
};

#[test]
fn ruxfs_writes_lists_and_reads_root_file() {
    let mut image = format_empty_filesystem(128).expect("filesystem formats");

    write_file(&mut image, "/kernel.ruxe", b"KERNEL").expect("file writes");

    assert_eq!(
        read_file(&image, "/kernel.ruxe").expect("file reads"),
        b"KERNEL"
    );
    assert_eq!(
        list_directory(&image, "/").expect("root lists"),
        vec!["kernel.ruxe".to_string()]
    );
    validate_filesystem(&image).expect("filesystem validates");
}

#[test]
fn ruxfs_creates_directory_and_writes_nested_file() {
    let mut image = format_empty_filesystem(128).expect("filesystem formats");

    create_directory(&mut image, "/boot").expect("directory creates");
    write_file(&mut image, "/boot/loader.ruxe", b"BOOT").expect("nested file writes");

    assert_eq!(
        list_directory(&image, "/").expect("root lists"),
        vec!["boot".to_string()]
    );
    assert_eq!(
        list_directory(&image, "/boot").expect("boot lists"),
        vec!["loader.ruxe".to_string()]
    );
    assert_eq!(
        read_file(&image, "/boot/loader.ruxe").expect("nested file reads"),
        b"BOOT"
    );
}

#[test]
fn ruxfs_rejects_invalid_paths_duplicate_directories_and_missing_files() {
    let mut image = format_empty_filesystem(128).expect("filesystem formats");

    assert!(write_file(&mut image, "relative", b"data")
        .unwrap_err()
        .contains("path must be absolute"));
    assert!(write_file(&mut image, "/missing/file", b"data")
        .unwrap_err()
        .contains("directory entry `missing` not found"));

    create_directory(&mut image, "/boot").expect("directory creates");
    assert!(create_directory(&mut image, "/boot")
        .unwrap_err()
        .contains("directory entry `boot` already exists"));
    assert!(read_file(&image, "/boot/missing.ruxe")
        .unwrap_err()
        .contains("directory entry `missing.ruxe` not found"));
}
