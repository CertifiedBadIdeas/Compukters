#![no_std]
#![no_main]

use core::panic::PanicInfo;

#[no_mangle]
pub unsafe extern "C" fn k16_shared_memcmp(lhs: *const u8, rhs: *const u8, n: usize) -> i32 {
    let mut index = 0;
    while index < n {
        let lhs_byte = unsafe { *lhs.add(index) };
        let rhs_byte = unsafe { *rhs.add(index) };
        if lhs_byte != rhs_byte {
            return i32::from(lhs_byte) - i32::from(rhs_byte);
        }
        index += 1;
    }
    0
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    loop {}
}
