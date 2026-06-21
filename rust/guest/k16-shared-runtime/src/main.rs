#![no_std]
#![no_main]

use core::panic::PanicInfo;

#[no_mangle]
pub unsafe extern "C" fn k16rt_memcpy(dst: *mut u8, src: *const u8, n: usize) -> *mut u8 {
    unsafe { k16_memory::k16_memcpy(dst, src, n) }
}

#[no_mangle]
pub unsafe extern "C" fn k16rt_memset(dst: *mut u8, value: i32, n: usize) -> *mut u8 {
    unsafe { k16_memory::k16_memset(dst, value, n) }
}

#[no_mangle]
pub unsafe extern "C" fn k16rt_memmove(dst: *mut u8, src: *const u8, n: usize) -> *mut u8 {
    unsafe { k16_memory::k16_memmove(dst, src, n) }
}

#[no_mangle]
pub unsafe extern "C" fn k16rt_memcmp(lhs: *const u8, rhs: *const u8, n: usize) -> i32 {
    unsafe { k16_memory::k16_memcmp(lhs, rhs, n) }
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    loop {}
}
