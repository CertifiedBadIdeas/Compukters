#![cfg(feature = "host-test")]

use kraft_std::debug;

#[test]
fn debug_marker_delegates_to_runtime_syscall_surface() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::DEBUG_MARKER_RETURN);

    let returned = debug::marker();

    assert_eq!(
        k16_rt::host_test::syscall_number(),
        k16_rt::host_test::DEBUG_MARKER
    );
    assert_eq!(returned, k16_rt::host_test::DEBUG_MARKER_RETURN);
}

#[test]
fn debug_write_byte_delegates_to_runtime_syscall_surface() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::STATUS_OK);

    let returned = debug::write_byte(0x21);

    assert_eq!(
        k16_rt::host_test::syscall_number(),
        k16_rt::host_test::DEBUG_WRITE_BYTE
    );
    assert_eq!(k16_rt::host_test::syscall_arg0(), 0x21);
    assert_eq!(returned, k16_rt::host_test::STATUS_OK);
}
