#![cfg(feature = "host-test")]

use kraft_std::thread;

#[test]
fn thread_yield_now_delegates_to_runtime_syscall_surface() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::STATUS_OK);

    thread::yield_now();

    assert_eq!(
        k16_rt::host_test::syscall_number(),
        k16_rt::host_test::YIELD
    );
}
