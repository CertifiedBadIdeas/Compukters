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
fn fs_reports_negative_syscall_status_as_error() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::ERROR_NO_ENTRY);

    let file = fs::open("/missing.txt");

    assert_eq!(
        file,
        Err(fs::Error::Syscall(k16_rt::host_test::ERROR_NO_ENTRY))
    );
}
