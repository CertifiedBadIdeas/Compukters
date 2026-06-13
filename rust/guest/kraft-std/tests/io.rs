#![cfg(feature = "host-test")]

use kraft_std::io;

#[test]
fn stdout_write_all_delegates_to_write_syscall() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(3);
    let bytes = *b"abc";

    let written = io::stdout().write_all(&bytes);

    assert_eq!(written, Ok(()));
    assert_eq!(
        k16_rt::host_test::syscall_number(),
        k16_rt::host_test::WRITE
    );
    assert_eq!(
        k16_rt::host_test::syscall_arg0(),
        k16_rt::host_test::FD_STDOUT
    );
    assert_eq!(
        k16_rt::host_test::syscall_arg1(),
        bytes.as_ptr() as usize as u32
    );
    assert_eq!(k16_rt::host_test::syscall_arg2(), 3);
}

#[test]
fn stderr_write_all_uses_stderr_fd() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(3);
    let bytes = *b"err";

    let written = io::stderr().write_all(&bytes);

    assert_eq!(written, Ok(()));
    assert_eq!(
        k16_rt::host_test::syscall_number(),
        k16_rt::host_test::WRITE
    );
    assert_eq!(
        k16_rt::host_test::syscall_arg0(),
        k16_rt::host_test::FD_STDERR
    );
    assert_eq!(
        k16_rt::host_test::syscall_arg1(),
        bytes.as_ptr() as usize as u32
    );
    assert_eq!(k16_rt::host_test::syscall_arg2(), 3);
}

#[test]
fn stdin_read_delegates_to_read_syscall() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(3);
    let mut bytes = [0u8; 4];

    let read = io::stdin().read(&mut bytes);

    assert_eq!(read, Ok(3));
    assert_eq!(k16_rt::host_test::syscall_number(), k16_rt::host_test::READ);
    assert_eq!(
        k16_rt::host_test::syscall_arg0(),
        k16_rt::host_test::FD_STDIN
    );
    assert_eq!(
        k16_rt::host_test::syscall_arg1(),
        bytes.as_mut_ptr() as usize as u32
    );
    assert_eq!(k16_rt::host_test::syscall_arg2(), 4);
}

#[test]
fn read_reports_negative_syscall_status_as_error() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::ERROR_BAD_FD);
    let mut bytes = [0u8; 4];

    let read = io::stdin().read(&mut bytes);

    assert_eq!(
        read,
        Err(io::Error::Syscall(k16_rt::host_test::ERROR_BAD_FD))
    );
}

#[test]
fn write_all_reports_short_write_as_error() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(2);
    let bytes = *b"abc";

    let written = io::stdout().write_all(&bytes);

    assert_eq!(written, Err(io::Error::ShortWrite));
}

#[test]
fn write_all_reports_negative_syscall_status_as_error() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::ERROR_BAD_FD);
    let bytes = *b"abc";

    let written = io::stdout().write_all(&bytes);

    assert_eq!(
        written,
        Err(io::Error::Syscall(k16_rt::host_test::ERROR_BAD_FD))
    );
}
