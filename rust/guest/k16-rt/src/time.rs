#[cfg(not(any(test, feature = "host-test")))]
const TIMER0_GAME_TICKS_LOW: u32 = 0x1000_0604;
#[cfg(not(any(test, feature = "host-test")))]
const TIMER0_GAME_TICKS_HIGH: u32 = 0x1000_0608;
#[cfg(not(any(test, feature = "host-test")))]
const TIMER0_MONOTONIC_NANOS_LOW: u32 = 0x1000_060c;
#[cfg(not(any(test, feature = "host-test")))]
const TIMER0_MONOTONIC_NANOS_HIGH: u32 = 0x1000_0610;

#[cfg(any(test, feature = "host-test"))]
use core::sync::atomic::{AtomicU64, Ordering};

#[cfg(any(test, feature = "host-test"))]
static TEST_TIMER0_GAME_TICKS: AtomicU64 = AtomicU64::new(0);
#[cfg(any(test, feature = "host-test"))]
static TEST_TIMER0_MONOTONIC_NANOS: AtomicU64 = AtomicU64::new(0);

#[cfg(not(any(test, feature = "host-test")))]
pub fn timer0_game_ticks() -> u64 {
    read_split_u64(TIMER0_GAME_TICKS_LOW, TIMER0_GAME_TICKS_HIGH)
}

#[cfg(any(test, feature = "host-test"))]
pub fn timer0_game_ticks() -> u64 {
    TEST_TIMER0_GAME_TICKS.load(Ordering::Relaxed)
}

#[cfg(not(any(test, feature = "host-test")))]
pub fn timer0_monotonic_nanos() -> u64 {
    read_split_u64(TIMER0_MONOTONIC_NANOS_LOW, TIMER0_MONOTONIC_NANOS_HIGH)
}

#[cfg(any(test, feature = "host-test"))]
pub fn timer0_monotonic_nanos() -> u64 {
    TEST_TIMER0_MONOTONIC_NANOS.load(Ordering::Relaxed)
}

pub fn yield_frames(frames: u64) {
    let mut remaining = frames;
    while remaining > 0 {
        crate::control::yield_once();
        remaining -= 1;
    }
}

pub fn sleep_ticks(ticks: u64) {
    let target = timer0_game_ticks().saturating_add(ticks);
    while timer0_game_ticks() < target {
        crate::control::yield_once();
    }
}

#[cfg(not(any(test, feature = "host-test")))]
fn read_split_u64(low_addr: u32, high_addr: u32) -> u64 {
    loop {
        let high_before = read_mmio_u32(high_addr);
        let low = read_mmio_u32(low_addr);
        let high_after = read_mmio_u32(high_addr);
        if high_before == high_after {
            return (u64::from(high_after) << 32) | u64::from(low);
        }
    }
}

#[cfg(not(any(test, feature = "host-test")))]
fn read_mmio_u32(address: u32) -> u32 {
    unsafe { core::ptr::read_volatile(address as usize as *const u32) }
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn reset_test_timer0() {
    TEST_TIMER0_GAME_TICKS.store(0, Ordering::Relaxed);
    TEST_TIMER0_MONOTONIC_NANOS.store(0, Ordering::Relaxed);
    crate::control::reset_test_yield_count();
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn set_test_timer0_game_ticks(value: u64) {
    TEST_TIMER0_GAME_TICKS.store(value, Ordering::Relaxed);
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn set_test_timer0_monotonic_nanos(value: u64) {
    TEST_TIMER0_MONOTONIC_NANOS.store(value, Ordering::Relaxed);
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn increment_test_timer0_game_ticks() {
    TEST_TIMER0_GAME_TICKS.fetch_add(1, Ordering::Relaxed);
}
