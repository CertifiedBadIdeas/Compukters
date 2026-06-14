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
fn process_run_with_args_encodes_argv_request_for_runtime_run_syscall() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(17);

    let status = kraft_std::process::run_with_args("/bin/cat.kx", &["/etc/motd", "--verbose"]);

    assert_eq!(status, Ok(17));
    assert_eq!(k16_rt::host_test::syscall_number(), k16_rt::host_test::RUN);
    let request_ptr = k16_rt::host_test::syscall_arg0();
    let request_len = k16_rt::host_test::syscall_arg1();
    assert_ne!(request_ptr, 0);
    assert!(request_len > "/bin/cat.kx".len() as u32);
    assert_eq!(
        k16_rt::host_test::syscall_arg2(),
        k16_rt::host_test::RUN_FORMAT_ARGV
    );
}

#[test]
fn process_run_with_args_encodes_multiple_argument_request_bytes() {
    let request =
        kraft_std::process::host_test::encode_run_argv_request("/bin/cat.kx", &["/etc/motd", "-n"])
            .unwrap();
    let bytes = &request.bytes[..request.len];

    assert_eq!(read_u32(bytes, 0), k16_abi::syscall::RUN_ARGV_MAGIC);
    assert_eq!(read_u32(bytes, 4), 11);
    assert_eq!(read_u32(bytes, 8), 2);
    assert_eq!(read_u32(bytes, 12), 9);
    assert_eq!(read_u32(bytes, 16), 2);
    assert_eq!(&bytes[20..31], b"/bin/cat.kx");
    assert_eq!(&bytes[31..40], b"/etc/motd");
    assert_eq!(&bytes[40..42], b"-n");
}

#[test]
fn process_run_with_args_requires_at_least_one_and_at_most_max_arguments() {
    k16_rt::host_test::reset_syscalls();

    let missing = kraft_std::process::run_with_args("/bin/cat.kx", &[]);
    let extra =
        kraft_std::process::run_with_args("/bin/cat.kx", &["one", "two", "three", "four", "five"]);

    assert_eq!(missing, Err(kraft_std::process::Error::InvalidArgument));
    assert_eq!(extra, Err(kraft_std::process::Error::InvalidArgument));
    assert_eq!(k16_rt::host_test::syscall_number(), 0);
}

fn read_u32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}

#[test]
fn argv_reader_returns_child_argument_bytes_from_raw_table() {
    let first = b"/etc/motd";
    let second = b"--verbose";
    let raw = [
        kraft_std::process::Arg::from_slice(first),
        kraft_std::process::Arg::from_slice(second),
    ];

    let args = unsafe { kraft_std::process::Argv::from_raw(2, raw.as_ptr()) };

    assert_eq!(args.len(), 2);
    assert_eq!(args.get(0), Some(first.as_slice()));
    assert_eq!(args.get(1), Some(second.as_slice()));
    assert_eq!(args.get(2), None);
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
