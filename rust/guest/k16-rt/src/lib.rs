#![no_std]

#[cfg(test)]
extern crate std;

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
pub extern "C" fn __udivdi3(lhs: u64, rhs: u64) -> u64 {
    k16_udiv64(lhs, rhs)
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn __umoddi3(lhs: u64, rhs: u64) -> u64 {
    k16_umod64(lhs, rhs)
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn __divdi3(lhs: i64, rhs: i64) -> i64 {
    k16_div64(lhs, rhs)
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn __moddi3(lhs: i64, rhs: i64) -> i64 {
    k16_mod64(lhs, rhs)
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
            assert_eq!(__udivdi3(lhs, rhs), lhs / rhs, "__udivdi3({lhs}, {rhs})");
            assert_eq!(__umoddi3(lhs, rhs), lhs % rhs, "__umoddi3({lhs}, {rhs})");
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
            assert_eq!(
                __divdi3(lhs, rhs),
                lhs.wrapping_div(rhs),
                "__divdi3({lhs}, {rhs})"
            );
            assert_eq!(
                __moddi3(lhs, rhs),
                lhs.wrapping_rem(rhs),
                "__moddi3({lhs}, {rhs})"
            );
        }
    }
}
