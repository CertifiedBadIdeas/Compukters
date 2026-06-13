#![cfg(feature = "host-test")]

use kraft_std::time;

#[test]
fn time_game_ticks_reads_runtime_timer0_value() {
    k16_rt::host_test::reset_timer0();
    k16_rt::host_test::set_timer0_game_ticks(0x0000_0001_0000_002a);

    assert_eq!(time::game_ticks(), 0x0000_0001_0000_002a);
}

#[test]
fn time_game_ticks_parts_reads_runtime_timer0_parts() {
    k16_rt::host_test::reset_timer0();
    k16_rt::host_test::set_timer0_game_ticks(0x0000_0001_0000_002a);

    let parts = time::game_ticks_parts();

    assert_eq!(parts.high, 1);
    assert_eq!(parts.low, 42);
}
