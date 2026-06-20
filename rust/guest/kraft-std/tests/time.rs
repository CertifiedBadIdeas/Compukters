#![cfg(feature = "host-test")]

use kraft_std::time;

#[test]
fn time_game_ticks_reads_runtime_timer0_value() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_timer0_game_ticks(0x0000_0001_0000_002a);
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::STATUS_OK);

    assert_eq!(time::game_ticks(), Ok(0x0000_0001_0000_002a));
    assert_eq!(
        k16_rt::host_test::syscall_number(),
        k16_rt::host_test::GAME_TICKS
    );
}

#[test]
fn time_game_ticks_parts_reads_runtime_timer0_parts() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_timer0_game_ticks(0x0000_0001_0000_002a);
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::STATUS_OK);

    let parts = time::game_ticks_parts().unwrap();

    assert_eq!(parts.high, 1);
    assert_eq!(parts.low, 42);
    assert_eq!(
        k16_rt::host_test::syscall_number(),
        k16_rt::host_test::GAME_TICKS
    );
}

#[test]
fn time_game_ticks_bytes_reads_runtime_timer0_bytes() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_timer0_game_ticks(0x0000_0001_0000_002a);
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::STATUS_OK);
    let mut bytes = [0u8; k16_abi::syscall::GAME_TICKS_BYTES];

    assert_eq!(time::game_ticks_bytes(&mut bytes), Ok(()));

    assert_eq!(bytes, [42, 0, 0, 0, 1, 0, 0, 0]);
    assert_eq!(
        k16_rt::host_test::syscall_number(),
        k16_rt::host_test::GAME_TICKS
    );
}

#[test]
fn time_game_ticks_parts_returns_syscall_error() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(k16_abi::syscall::ERROR_FAULT);

    assert_eq!(
        time::game_ticks_parts(),
        Err(time::Error::Syscall(k16_abi::syscall::ERROR_FAULT))
    );
}
