#![cfg(feature = "host-test")]

use kraft_std::{fs, status};

#[test]
fn syscall_status_name_covers_current_userland_errors() {
    assert_eq!(
        status::syscall_status_name(k16_abi::syscall::ERROR_NO_ENTRY),
        b"NOENT"
    );
    assert_eq!(
        status::syscall_status_name(k16_abi::syscall::ERROR_INVALID),
        b"INVAL"
    );
    assert_eq!(
        status::syscall_status_name(k16_abi::syscall::ERROR_BAD_FD),
        b"BADFD"
    );
    assert_eq!(
        status::syscall_status_name(k16_abi::syscall::ERROR_BUSY),
        b"BUSY"
    );
    assert_eq!(
        status::syscall_status_name(k16_abi::syscall::ERROR_NO_MEMORY),
        b"NOMEM"
    );
    assert_eq!(
        status::syscall_status_name(k16_abi::syscall::ERROR_FAULT),
        b"FAULT"
    );
    assert_eq!(
        status::syscall_status_name(k16_abi::syscall::ERROR_NOT_EMPTY),
        b"NOTEMPTY"
    );
}

#[test]
fn fs_error_name_wraps_invalid_argument_and_syscall_status() {
    assert_eq!(status::fs_error_name(fs::Error::InvalidArgument), b"INVAL");
    assert_eq!(
        status::fs_error_name(fs::Error::Syscall(k16_abi::syscall::ERROR_BUSY)),
        b"BUSY"
    );
}

#[test]
fn syscall_status_name_uses_supplied_fallback_for_unknown_status() {
    assert_eq!(status::syscall_status_name_or(0xffff_ff80, b"IO"), b"IO");
}
