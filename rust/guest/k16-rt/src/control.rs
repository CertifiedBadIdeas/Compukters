#[cfg(not(test))]
const CONTROL_YIELD: u32 = 0x1000_000c;

#[cfg(not(test))]
extern "C" {
    fn __k16_halt_once();
}

#[cfg(test)]
use core::sync::atomic::{AtomicU64, Ordering};

#[cfg(test)]
static TEST_YIELD_COUNT: AtomicU64 = AtomicU64::new(0);

#[cfg(not(test))]
#[inline(always)]
pub fn halt_once() {
    unsafe {
        __k16_halt_once();
    }
}

#[cfg(not(test))]
#[inline(always)]
pub fn yield_once() {
    unsafe {
        core::ptr::write_volatile(CONTROL_YIELD as usize as *mut i32, 1);
    }
}

#[cfg(test)]
pub fn halt_once() {}

#[cfg(test)]
pub fn yield_once() {
    TEST_YIELD_COUNT.fetch_add(1, Ordering::Relaxed);
    crate::time::increment_test_timer0_game_ticks();
}

pub fn halt_forever() -> ! {
    loop {
        halt_once();
    }
}

#[cfg(test)]
pub(crate) fn reset_test_yield_count() {
    TEST_YIELD_COUNT.store(0, Ordering::Relaxed);
}

#[cfg(test)]
pub(crate) fn test_yield_count() -> u64 {
    TEST_YIELD_COUNT.load(Ordering::Relaxed)
}
