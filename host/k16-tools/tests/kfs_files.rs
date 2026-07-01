use k16_tools::kfs::{
    create_directory, delete_file, format_empty_filesystem, list_directory, read_file,
    validate_filesystem, write_file,
};

#[test]
fn kfs_writes_lists_and_reads_root_file() {
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
fn kfs_creates_directory_and_writes_nested_file() {
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
fn kfs_grows_host_directory_when_initial_slots_are_full() {
    let mut image = format_empty_filesystem(128).expect("filesystem formats");

    for index in 0..8 {
        create_directory(&mut image, &format!("/d{index}")).expect("initial directory creates");
    }

    create_directory(&mut image, "/d8").expect("directory grows for ninth entry");

    let entries = list_directory(&image, "/").expect("root lists");
    assert!(entries.contains(&"d0".to_string()), "entries: {entries:?}");
    assert!(entries.contains(&"d8".to_string()), "entries: {entries:?}");
    validate_filesystem(&image).expect("filesystem validates");
}

#[test]
fn kfs_writes_host_file_across_fragmented_inline_extents() {
    let mut image = format_empty_filesystem(19).expect("filesystem formats");

    for index in 0..8 {
        write_file(&mut image, &format!("/f{index}"), b"x").expect("fragment seed writes");
    }
    for index in [1, 3, 5, 7] {
        delete_file(&mut image, &format!("/f{index}")).expect("fragment seed deletes");
    }

    let payload = vec![b'z'; 2 * 512];
    write_file(&mut image, "/big", &payload).expect("fragmented file writes");

    assert_eq!(
        read_file(&image, "/big").expect("fragmented file reads"),
        payload
    );
    validate_filesystem(&image).expect("filesystem validates");
}

#[test]
fn kfs_rejects_invalid_paths_duplicate_directories_and_missing_files() {
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
fn kfs_deletes_file_and_allows_recreating_same_path() {
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
fn kfs_delete_rejects_missing_paths_and_directories() {
    let mut image = format_empty_filesystem(128).expect("filesystem formats");
    create_directory(&mut image, "/boot").expect("directory creates");

    assert!(delete_file(&mut image, "/boot/missing.kx")
        .unwrap_err()
        .contains("directory entry `missing.kx` not found"));
    assert!(delete_file(&mut image, "/boot")
        .unwrap_err()
        .contains("is not a file"));
}
