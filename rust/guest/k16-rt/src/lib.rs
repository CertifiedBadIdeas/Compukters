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
}
