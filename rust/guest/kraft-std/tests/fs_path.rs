#![cfg(feature = "host-test")]

use kraft_std::{fs, path};

#[test]
fn path_ref_accepts_nonempty_bounded_utf8_path() {
    let path = path::PathRef::try_from_str("/bin/shell.kx").unwrap();

    assert_eq!(path.as_str(), "/bin/shell.kx");
    assert_eq!(path.as_bytes(), b"/bin/shell.kx");
}

#[test]
fn path_ref_rejects_empty_and_overlong_paths() {
    assert_eq!(
        path::PathRef::try_from_str(""),
        Err(path::PathError::Invalid)
    );

    let long = "a".repeat(path::MAX_PATH_BYTES + 1);

    assert_eq!(
        path::PathRef::try_from_str(&long),
        Err(path::PathError::TooLong)
    );
}

#[test]
fn fs_metadata_path_delegates_to_stat_syscall() {
    let path = path::PathRef::try_from_str("/bin/shell.kx").unwrap();
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::ERROR_NO_ENTRY);

    let metadata = fs::metadata_path(path);

    assert_eq!(
        metadata,
        Err(fs::Error::Syscall(k16_rt::host_test::ERROR_NO_ENTRY))
    );
    assert_eq!(k16_rt::host_test::syscall_number(), k16_rt::host_test::STAT);
    assert_eq!(
        k16_rt::host_test::syscall_arg0(),
        path.as_str().as_ptr() as usize as u32
    );
    assert_eq!(k16_rt::host_test::syscall_arg1(), 13);
    assert_ne!(k16_rt::host_test::syscall_arg2(), 0);
}
