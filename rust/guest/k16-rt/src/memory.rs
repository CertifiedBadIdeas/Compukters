pub unsafe fn k16_memcpy(dst: *mut u8, src: *const u8, n: usize) -> *mut u8 {
    unsafe { k16_memory::k16_memcpy(dst, src, n) }
}

pub unsafe fn k16_memmove(dst: *mut u8, src: *const u8, n: usize) -> *mut u8 {
    unsafe { k16_memory::k16_memmove(dst, src, n) }
}

pub unsafe fn k16_memset(dst: *mut u8, value: i32, n: usize) -> *mut u8 {
    unsafe { k16_memory::k16_memset(dst, value, n) }
}

pub unsafe fn k16_memcmp(lhs: *const u8, rhs: *const u8, n: usize) -> i32 {
    unsafe { k16_memory::k16_memcmp(lhs, rhs, n) }
}
