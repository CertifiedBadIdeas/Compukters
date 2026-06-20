#![cfg(feature = "host-test")]

use kraft_std::fs;

#[test]
fn fs_open_delegates_to_open_syscall_read_only() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(3);

    let file = fs::open("/etc/motd");

    assert_eq!(file.map(|file| file.raw()), Ok(3));
    assert_eq!(k16_rt::host_test::syscall_number(), k16_rt::host_test::OPEN);
    assert_eq!(
        k16_rt::host_test::syscall_arg0(),
        "/etc/motd".as_ptr() as usize as u32
    );
    assert_eq!(k16_rt::host_test::syscall_arg1(), 9);
    assert_eq!(k16_rt::host_test::syscall_arg2(), 0);
}

#[test]
fn fs_create_delegates_to_open_syscall_write_create_truncate() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(3);

    let file = fs::create("/etc/user.txt");

    assert_eq!(file.map(|file| file.raw()), Ok(3));
    assert_eq!(k16_rt::host_test::syscall_number(), k16_rt::host_test::OPEN);
    assert_eq!(
        k16_rt::host_test::syscall_arg0(),
        "/etc/user.txt".as_ptr() as usize as u32
    );
    assert_eq!(k16_rt::host_test::syscall_arg1(), 13);
    assert_eq!(
        k16_rt::host_test::syscall_arg2(),
        k16_abi::syscall::OPEN_WRITE_ONLY
            | k16_abi::syscall::OPEN_CREATE
            | k16_abi::syscall::OPEN_TRUNCATE,
    );
}

#[test]
fn fs_append_delegates_to_open_syscall_write_create_append() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(3);

    let file = fs::append("/etc/user.txt");

    assert_eq!(file.map(|file| file.raw()), Ok(3));
    assert_eq!(k16_rt::host_test::syscall_number(), k16_rt::host_test::OPEN);
    assert_eq!(
        k16_rt::host_test::syscall_arg0(),
        "/etc/user.txt".as_ptr() as usize as u32
    );
    assert_eq!(k16_rt::host_test::syscall_arg1(), 13);
    assert_eq!(
        k16_rt::host_test::syscall_arg2(),
        k16_abi::syscall::OPEN_WRITE_ONLY
            | k16_abi::syscall::OPEN_CREATE
            | k16_abi::syscall::OPEN_APPEND,
    );
}

#[test]
fn file_read_delegates_to_read_syscall() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(4);
    let file = fs::File::from_raw(3);
    let mut bytes = [0u8; 8];

    let read = file.read(&mut bytes);

    assert_eq!(read, Ok(4));
    assert_eq!(k16_rt::host_test::syscall_number(), k16_rt::host_test::READ);
    assert_eq!(k16_rt::host_test::syscall_arg0(), 3);
    assert_eq!(
        k16_rt::host_test::syscall_arg1(),
        bytes.as_mut_ptr() as usize as u32
    );
    assert_eq!(k16_rt::host_test::syscall_arg2(), 8);
}

#[test]
fn file_write_all_delegates_to_write_syscall() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(5);
    let file = fs::File::from_raw(3);
    let bytes = b"hello";

    let written = file.write_all(bytes);

    assert_eq!(written, Ok(()));
    assert_eq!(
        k16_rt::host_test::syscall_number(),
        k16_rt::host_test::WRITE
    );
    assert_eq!(k16_rt::host_test::syscall_arg0(), 3);
    assert_eq!(
        k16_rt::host_test::syscall_arg1(),
        bytes.as_ptr() as usize as u32
    );
    assert_eq!(k16_rt::host_test::syscall_arg2(), 5);
}

#[test]
fn file_seek_start_delegates_to_seek_syscall() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(2);
    let file = fs::File::from_raw(3);

    let offset = file.seek_start(2);

    assert_eq!(offset, Ok(2));
    assert_eq!(k16_rt::host_test::syscall_number(), k16_rt::host_test::SEEK);
    assert_eq!(k16_rt::host_test::syscall_arg0(), 3);
    assert_eq!(k16_rt::host_test::syscall_arg1(), 2);
    assert_eq!(
        k16_rt::host_test::syscall_arg2(),
        k16_abi::syscall::SEEK_SET
    );
}

#[test]
fn file_seek_end_delegates_to_seek_syscall() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(11);
    let file = fs::File::from_raw(3);

    let offset = file.seek_end();

    assert_eq!(offset, Ok(11));
    assert_eq!(k16_rt::host_test::syscall_number(), k16_rt::host_test::SEEK);
    assert_eq!(k16_rt::host_test::syscall_arg0(), 3);
    assert_eq!(k16_rt::host_test::syscall_arg1(), 0);
    assert_eq!(
        k16_rt::host_test::syscall_arg2(),
        k16_abi::syscall::SEEK_END
    );
}

#[test]
fn fs_remove_file_delegates_to_unlink_syscall() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::STATUS_OK);

    let removed = fs::remove_file("/etc/user.txt");

    assert_eq!(removed, Ok(()));
    assert_eq!(
        k16_rt::host_test::syscall_number(),
        k16_rt::host_test::UNLINK
    );
    assert_eq!(
        k16_rt::host_test::syscall_arg0(),
        "/etc/user.txt".as_ptr() as usize as u32
    );
    assert_eq!(k16_rt::host_test::syscall_arg1(), 13);
    assert_eq!(k16_rt::host_test::syscall_arg2(), 0);
}

#[test]
fn fs_create_dir_delegates_to_mkdir_syscall() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::STATUS_OK);

    let created = fs::create_dir("/etc/user");

    assert_eq!(created, Ok(()));
    assert_eq!(
        k16_rt::host_test::syscall_number(),
        k16_rt::host_test::MKDIR
    );
    assert_eq!(
        k16_rt::host_test::syscall_arg0(),
        "/etc/user".as_ptr() as usize as u32
    );
    assert_eq!(k16_rt::host_test::syscall_arg1(), 9);
    assert_eq!(k16_rt::host_test::syscall_arg2(), 0);
}

#[test]
fn fs_remove_dir_delegates_to_rmdir_syscall() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::STATUS_OK);

    let removed = fs::remove_dir("/etc/user");

    assert_eq!(removed, Ok(()));
    assert_eq!(
        k16_rt::host_test::syscall_number(),
        k16_rt::host_test::RMDIR
    );
    assert_eq!(
        k16_rt::host_test::syscall_arg0(),
        "/etc/user".as_ptr() as usize as u32
    );
    assert_eq!(k16_rt::host_test::syscall_arg1(), 9);
    assert_eq!(k16_rt::host_test::syscall_arg2(), 0);
}

#[test]
fn file_close_delegates_to_close_syscall() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::STATUS_OK);
    let file = fs::File::from_raw(3);

    let closed = file.close();

    assert_eq!(closed, Ok(()));
    assert_eq!(
        k16_rt::host_test::syscall_number(),
        k16_rt::host_test::CLOSE
    );
    assert_eq!(k16_rt::host_test::syscall_arg0(), 3);
}

#[test]
fn fs_read_dir_delegates_to_read_dir_syscall() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(24);
    let mut bytes = [0u8; 128];

    let read = fs::read_dir("/bin", &mut bytes);

    assert_eq!(read, Ok(24));
    assert_eq!(
        k16_rt::host_test::syscall_number(),
        k16_rt::host_test::READ_DIR
    );
    assert_ne!(k16_rt::host_test::syscall_arg0(), 0);
    assert_eq!(k16_rt::host_test::syscall_arg1(), 20);
    assert_eq!(k16_rt::host_test::syscall_arg2(), 0);
}

#[test]
fn fs_read_dir_rejects_too_long_path_before_syscall() {
    k16_rt::host_test::reset_syscalls();
    let mut bytes = [0u8; 128];
    let path = "x".repeat(k16_abi::syscall::MAX_READ_DIR_PATH_BYTES + 1);

    let read = fs::read_dir(&path, &mut bytes);

    assert_eq!(read, Err(fs::Error::InvalidArgument));
    assert_eq!(k16_rt::host_test::syscall_number(), 0);
}

#[test]
fn fs_metadata_delegates_to_stat_syscall_and_reports_negative_status() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::ERROR_NO_ENTRY);

    let metadata = fs::metadata("/bin/cat.kx");

    assert_eq!(
        metadata,
        Err(fs::Error::Syscall(k16_rt::host_test::ERROR_NO_ENTRY))
    );
    assert_eq!(k16_rt::host_test::syscall_number(), k16_rt::host_test::STAT);
    assert_eq!(
        k16_rt::host_test::syscall_arg0(),
        "/bin/cat.kx".as_ptr() as usize as u32
    );
    assert_eq!(k16_rt::host_test::syscall_arg1(), 11);
    assert_ne!(k16_rt::host_test::syscall_arg2(), 0);
}

#[test]
fn fs_metadata_rejects_too_long_path_before_syscall() {
    k16_rt::host_test::reset_syscalls();
    let path = "x".repeat(k16_abi::syscall::MAX_STAT_PATH_BYTES + 1);

    let metadata = fs::metadata(&path);

    assert_eq!(metadata, Err(fs::Error::InvalidArgument));
    assert_eq!(k16_rt::host_test::syscall_number(), 0);
}

#[test]
fn fs_reports_negative_syscall_status_as_error() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::ERROR_NO_ENTRY);

    let file = fs::open("/missing.txt");

    assert_eq!(
        file,
        Err(fs::Error::Syscall(k16_rt::host_test::ERROR_NO_ENTRY))
    );
}
