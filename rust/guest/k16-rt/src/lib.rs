#![no_std]

#[cfg(test)]
extern crate std;

#[cfg(not(test))]
const CONTROL_YIELD: u32 = 0x1000_000c;
#[cfg(not(test))]
const TIMER0_GAME_TICKS_LOW: u32 = 0x1000_0604;
#[cfg(not(test))]
const TIMER0_GAME_TICKS_HIGH: u32 = 0x1000_0608;
#[cfg(not(test))]
const TIMER0_MONOTONIC_NANOS_LOW: u32 = 0x1000_060c;
#[cfg(not(test))]
const TIMER0_MONOTONIC_NANOS_HIGH: u32 = 0x1000_0610;

#[cfg(test)]
use core::sync::atomic::{AtomicU64, Ordering};

#[cfg(test)]
static TEST_TIMER0_GAME_TICKS: AtomicU64 = AtomicU64::new(0);
#[cfg(test)]
static TEST_TIMER0_MONOTONIC_NANOS: AtomicU64 = AtomicU64::new(0);
#[cfg(test)]
static TEST_YIELD_COUNT: AtomicU64 = AtomicU64::new(0);

#[cfg(not(test))]
extern "C" {
    fn __k16_halt_once();
}

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
    TEST_TIMER0_GAME_TICKS.fetch_add(1, Ordering::Relaxed);
}

#[cfg(not(test))]
pub fn timer0_game_ticks() -> u64 {
    read_split_u64(TIMER0_GAME_TICKS_LOW, TIMER0_GAME_TICKS_HIGH)
}

#[cfg(test)]
pub fn timer0_game_ticks() -> u64 {
    TEST_TIMER0_GAME_TICKS.load(Ordering::Relaxed)
}

#[cfg(not(test))]
pub fn timer0_monotonic_nanos() -> u64 {
    read_split_u64(TIMER0_MONOTONIC_NANOS_LOW, TIMER0_MONOTONIC_NANOS_HIGH)
}

#[cfg(test)]
pub fn timer0_monotonic_nanos() -> u64 {
    TEST_TIMER0_MONOTONIC_NANOS.load(Ordering::Relaxed)
}

pub fn yield_frames(frames: u64) {
    let mut remaining = frames;
    while remaining > 0 {
        yield_once();
        remaining -= 1;
    }
}

pub fn sleep_ticks(ticks: u64) {
    let target = timer0_game_ticks().saturating_add(ticks);
    while timer0_game_ticks() < target {
        yield_once();
    }
}

#[cfg(not(test))]
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

#[cfg(not(test))]
fn read_mmio_u32(address: u32) -> u32 {
    unsafe { core::ptr::read_volatile(address as usize as *const u32) }
}

#[cfg(test)]
fn reset_test_timer0() {
    TEST_TIMER0_GAME_TICKS.store(0, Ordering::Relaxed);
    TEST_TIMER0_MONOTONIC_NANOS.store(0, Ordering::Relaxed);
    TEST_YIELD_COUNT.store(0, Ordering::Relaxed);
}

#[cfg(test)]
fn set_test_timer0_game_ticks(value: u64) {
    TEST_TIMER0_GAME_TICKS.store(value, Ordering::Relaxed);
}

#[cfg(test)]
fn set_test_timer0_monotonic_nanos(value: u64) {
    TEST_TIMER0_MONOTONIC_NANOS.store(value, Ordering::Relaxed);
}

#[cfg(test)]
fn test_yield_count() -> u64 {
    TEST_YIELD_COUNT.load(Ordering::Relaxed)
}

pub fn halt_forever() -> ! {
    loop {
        halt_once();
    }
}

pub unsafe fn k16_memcpy(dst: *mut u8, src: *const u8, n: usize) -> *mut u8 {
    let mut index = 0;
    while index < n {
        unsafe {
            dst.add(index).write(src.add(index).read());
        }
        index += 1;
    }
    dst
}

pub unsafe fn k16_memmove(dst: *mut u8, src: *const u8, n: usize) -> *mut u8 {
    let dst_addr = dst as usize;
    let src_addr = src as usize;

    if dst_addr <= src_addr || dst_addr >= src_addr.saturating_add(n) {
        unsafe {
            k16_memcpy(dst, src, n);
        }
        return dst;
    }

    let mut index = n;
    while index > 0 {
        index -= 1;
        unsafe {
            dst.add(index).write(src.add(index).read());
        }
    }
    dst
}

pub unsafe fn k16_memset(dst: *mut u8, value: i32, n: usize) -> *mut u8 {
    let byte = value as u8;
    let mut index = 0;
    while index < n {
        unsafe {
            dst.add(index).write(byte);
        }
        index += 1;
    }
    dst
}

pub unsafe fn k16_memcmp(lhs: *const u8, rhs: *const u8, n: usize) -> i32 {
    let mut index = 0;
    while index < n {
        let left = unsafe { lhs.add(index).read() };
        let right = unsafe { rhs.add(index).read() };
        if left != right {
            return left as i32 - right as i32;
        }
        index += 1;
    }
    0
}

pub fn k16_udiv64(lhs: u64, rhs: u64) -> u64 {
    k16_udivmod64(lhs, rhs).0
}

pub fn k16_umod64(lhs: u64, rhs: u64) -> u64 {
    k16_udivmod64(lhs, rhs).1
}

pub fn k16_div64(lhs: i64, rhs: i64) -> i64 {
    let quotient = k16_udiv64(k16_i64_abs_bits(lhs), k16_i64_abs_bits(rhs));
    if (lhs < 0) == (rhs < 0) {
        quotient as i64
    } else {
        k16_negate_u64_bits(quotient)
    }
}

pub fn k16_mod64(lhs: i64, rhs: i64) -> i64 {
    let remainder = k16_umod64(k16_i64_abs_bits(lhs), k16_i64_abs_bits(rhs));
    if lhs < 0 {
        k16_negate_u64_bits(remainder)
    } else {
        remainder as i64
    }
}

fn k16_udivmod64(lhs: u64, rhs: u64) -> (u64, u64) {
    if rhs == 0 {
        return (0, lhs);
    }

    let mut quotient = 0u64;
    let mut remainder = 0u64;
    let mut bit_index = 64usize;
    while bit_index > 0 {
        bit_index -= 1;
        remainder = (remainder << 1) | ((lhs >> bit_index) & 1);
        if remainder >= rhs {
            remainder -= rhs;
            quotient |= 1u64 << bit_index;
        }
    }
    (quotient, remainder)
}

fn k16_i64_abs_bits(value: i64) -> u64 {
    let bits = value as u64;
    if value < 0 {
        0u64.wrapping_sub(bits)
    } else {
        bits
    }
}

fn k16_negate_u64_bits(value: u64) -> i64 {
    0u64.wrapping_sub(value) as i64
}

#[cfg_attr(not(test), no_mangle)]
pub unsafe extern "C" fn memcpy(dst: *mut u8, src: *const u8, n: usize) -> *mut u8 {
    unsafe { k16_memcpy(dst, src, n) }
}

#[cfg_attr(not(test), no_mangle)]
pub unsafe extern "C" fn memmove(dst: *mut u8, src: *const u8, n: usize) -> *mut u8 {
    unsafe { k16_memmove(dst, src, n) }
}

#[cfg_attr(not(test), no_mangle)]
pub unsafe extern "C" fn memset(dst: *mut u8, value: i32, n: usize) -> *mut u8 {
    unsafe { k16_memset(dst, value, n) }
}

#[cfg_attr(not(test), no_mangle)]
pub unsafe extern "C" fn memcmp(lhs: *const u8, rhs: *const u8, n: usize) -> i32 {
    unsafe { k16_memcmp(lhs, rhs, n) }
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn abort() -> ! {
    halt_forever()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn memcpy_copies_bytes_without_touching_return_value() {
        let mut dst = [0u8; 5];
        let src = [1u8, 2, 3, 4, 5];

        let returned = unsafe { k16_memcpy(dst.as_mut_ptr(), src.as_ptr(), src.len()) };

        assert_eq!(returned, dst.as_mut_ptr());
        assert_eq!(dst, src);
    }

    #[test]
    fn memmove_handles_forward_overlap() {
        let mut bytes = *b"abcdef";

        unsafe {
            k16_memmove(bytes.as_mut_ptr().add(1), bytes.as_ptr(), 5);
        }

        assert_eq!(&bytes, b"aabcde");
    }

    #[test]
    fn memmove_handles_backward_overlap() {
        let mut bytes = *b"abcdef";

        unsafe {
            k16_memmove(bytes.as_mut_ptr(), bytes.as_ptr().add(1), 5);
        }

        assert_eq!(&bytes, b"bcdeff");
    }

    #[test]
    fn memset_writes_low_byte_of_value() {
        let mut bytes = [0u8; 4];

        let returned = unsafe { k16_memset(bytes.as_mut_ptr(), 0x12ab, bytes.len()) };

        assert_eq!(returned, bytes.as_mut_ptr());
        assert_eq!(bytes, [0xab; 4]);
    }

    #[test]
    fn memcmp_returns_lexicographic_byte_difference() {
        let lhs = [1u8, 2, 4];
        let rhs = [1u8, 2, 7];

        assert!(unsafe { k16_memcmp(lhs.as_ptr(), rhs.as_ptr(), lhs.len()) } < 0);
        assert!(unsafe { k16_memcmp(rhs.as_ptr(), lhs.as_ptr(), lhs.len()) } > 0);
        assert_eq!(
            unsafe { k16_memcmp(lhs.as_ptr(), lhs.as_ptr(), lhs.len()) },
            0
        );
    }

    #[test]
    fn abort_helper_has_c_abi_signature() {
        let _abort: extern "C" fn() -> ! = abort;
    }

    #[test]
    fn unsigned_i64_division_helpers_match_rust_for_nonzero_divisors() {
        let cases = [
            (0u64, 1u64),
            (1, 1),
            (42, 5),
            (u32::MAX as u64 + 17, 19),
            (u64::MAX, u32::MAX as u64),
            (u64::MAX, u64::MAX),
        ];

        for (lhs, rhs) in cases {
            assert_eq!(k16_udiv64(lhs, rhs), lhs / rhs, "{lhs} / {rhs}");
            assert_eq!(k16_umod64(lhs, rhs), lhs % rhs, "{lhs} % {rhs}");
        }
    }

    #[test]
    fn signed_i64_division_helpers_match_wrapping_rust_cases() {
        let cases = [
            (42i64, 5i64),
            (-42, 5),
            (42, -5),
            (-42, -5),
            (i64::MIN, 1),
            (i64::MIN, -1),
            (i64::MIN, 3),
            (i64::MAX, -7),
        ];

        for (lhs, rhs) in cases {
            assert_eq!(k16_div64(lhs, rhs), lhs.wrapping_div(rhs), "{lhs} / {rhs}");
            assert_eq!(k16_mod64(lhs, rhs), lhs.wrapping_rem(rhs), "{lhs} % {rhs}");
        }
    }

    #[test]
    fn timer0_helpers_read_test_counters() {
        reset_test_timer0();
        set_test_timer0_game_ticks(42);
        set_test_timer0_monotonic_nanos(9001);

        assert_eq!(timer0_game_ticks(), 42);
        assert_eq!(timer0_monotonic_nanos(), 9001);
    }

    #[test]
    fn yield_frames_and_sleep_ticks_use_yield_boundaries() {
        reset_test_timer0();

        yield_frames(2);

        assert_eq!(test_yield_count(), 2);
        assert_eq!(timer0_game_ticks(), 2);

        sleep_ticks(3);

        assert_eq!(test_yield_count(), 5);
        assert_eq!(timer0_game_ticks(), 5);
    }
}
