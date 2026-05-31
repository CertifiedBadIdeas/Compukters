use k16_tools::k16fs::{
    create_directory, delete_file, format_empty_filesystem, list_directory, read_file,
    validate_filesystem, write_file,
};

#[test]
fn k16fs_writes_lists_and_reads_root_file() {
    let mut image = format_empty_filesystem(128).expect("filesystem formats");

    write_file(&mut image, "/kernel.kx", b"KERNEL").expect("file writes");

    assert_eq!(
        read_file(&image, "/kernel.kx").expect("file reads"),
        b"KERNEL"
    );
    assert_eq!(
        list_directory(&image, "/").expect("root lists"),
        vec!["kernel.kx".to_string()]
    );
    validate_filesystem(&image).expect("filesystem validates");
}

#[test]
fn k16fs_creates_directory_and_writes_nested_file() {
    let mut image = format_empty_filesystem(128).expect("filesystem formats");

    create_directory(&mut image, "/boot").expect("directory creates");
    write_file(&mut image, "/boot/loader.kb", b"BOOT").expect("nested file writes");

    assert_eq!(
        list_directory(&image, "/").expect("root lists"),
        vec!["boot".to_string()]
    );
    assert_eq!(
        list_directory(&image, "/boot").expect("boot lists"),
        vec!["loader.kb".to_string()]
    );
    assert_eq!(
        read_file(&image, "/boot/loader.kb").expect("nested file reads"),
        b"BOOT"
    );
}

#[test]
fn k16fs_rejects_invalid_paths_duplicate_directories_and_missing_files() {
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
    assert!(read_file(&image, "/boot/missing.kx")
        .unwrap_err()
        .contains("directory entry `missing.kx` not found"));
}

#[test]
fn k16fs_deletes_file_and_allows_recreating_same_path() {
    let mut image = format_empty_filesystem(128).expect("filesystem formats");
    create_directory(&mut image, "/boot").expect("directory creates");
    write_file(&mut image, "/boot/loader.kb", b"OLD").expect("file writes");

    delete_file(&mut image, "/boot/loader.kb").expect("file deletes");

    assert_eq!(
        list_directory(&image, "/boot").expect("boot lists after delete"),
        Vec::<String>::new()
    );
    assert!(read_file(&image, "/boot/loader.kb")
        .unwrap_err()
        .contains("directory entry `loader.kb` not found"));
    validate_filesystem(&image).expect("filesystem validates after delete");

    write_file(&mut image, "/boot/loader.kb", b"NEW").expect("path can be recreated");
    assert_eq!(
        read_file(&image, "/boot/loader.kb").expect("recreated file reads"),
        b"NEW"
    );
}

#[test]
fn k16fs_delete_rejects_missing_paths_and_directories() {
    let mut image = format_empty_filesystem(128).expect("filesystem formats");
    create_directory(&mut image, "/boot").expect("directory creates");

    assert!(delete_file(&mut image, "/boot/missing.kx")
        .unwrap_err()
        .contains("directory entry `missing.kx` not found"));
    assert!(delete_file(&mut image, "/boot")
        .unwrap_err()
        .contains("is not a file"));
}
