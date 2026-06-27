#[cfg(not(any(test, feature = "host-test")))]
extern "C" {
    fn __k16_halt_once();
    fn __k16_wait_once();
    fn __k16_yield_once();
}

#[cfg(any(test, feature = "host-test"))]
use std::cell::Cell;

#[cfg(any(test, feature = "host-test"))]
std::thread_local! {
    static TEST_YIELD_COUNT: Cell<u64> = const { Cell::new(0) };
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn halt_once() {
    unsafe {
        __k16_halt_once();
    }
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn wait_once() {
    unsafe {
        __k16_wait_once();
    }
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn yield_once() {
    unsafe {
        __k16_yield_once();
    }
}

#[cfg(any(test, feature = "host-test"))]
pub fn halt_once() {}

#[cfg(any(test, feature = "host-test"))]
pub fn wait_once() {}

#[cfg(any(test, feature = "host-test"))]
pub fn yield_once() {
    TEST_YIELD_COUNT.with(|cell| cell.set(cell.get() + 1));
    crate::time::increment_test_timer0_game_ticks();
}

pub fn halt_forever() -> ! {
    loop {
        halt_once();
    }
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn reset_test_yield_count() {
    TEST_YIELD_COUNT.with(|cell| cell.set(0));
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn test_yield_count() -> u64 {
    TEST_YIELD_COUNT.with(|cell| cell.get())
}
