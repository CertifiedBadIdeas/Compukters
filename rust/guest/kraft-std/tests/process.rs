#![cfg(feature = "host-test")]

#[test]
fn process_exit_has_never_returning_signature() {
    let _exit: fn(u32) -> ! = kraft_std::process::exit;
}

#[test]
fn process_run_delegates_to_runtime_run_syscall() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(23);

    let status = kraft_std::process::run("/bin/hello.kx");

    assert_eq!(status, Ok(23));
    assert_eq!(k16_rt::host_test::syscall_number(), k16_rt::host_test::RUN);
    assert_eq!(
        k16_rt::host_test::syscall_arg0(),
        "/bin/hello.kx".as_ptr() as usize as u32
    );
    assert_eq!(k16_rt::host_test::syscall_arg1(), 13);
}

#[test]
fn process_run_reports_negative_syscall_status_as_error() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::ERROR_NO_ENTRY);

    let status = kraft_std::process::run("/bin/missing.kx");

    assert_eq!(
        status,
        Err(kraft_std::process::Error::Syscall(
            k16_rt::host_test::ERROR_NO_ENTRY
        ))
    );
}
