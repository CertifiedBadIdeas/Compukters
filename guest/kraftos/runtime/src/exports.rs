use crate::control::halt_forever;
use crate::memory::{k16_memcmp, k16_memcpy, k16_memmove, k16_memset};

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
