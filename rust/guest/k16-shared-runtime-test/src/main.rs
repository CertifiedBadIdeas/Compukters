#![no_std]
#![no_main]

use core::panic::PanicInfo;

extern "C" {
    fn __k16_halt_once();
    fn __k16_syscall1(number: u32, arg0: u32) -> u32;
    fn k16_shared_memcmp(lhs: *const u8, rhs: *const u8, n: usize) -> i32;
}

static EXPECTED: &[u8; 9] = b"K16SHARED";
static SAME: &[u8; 9] = b"K16SHARED";
static DIFFERENT: &[u8; 9] = b"K16SHAREE";

#[no_mangle]
pub extern "C" fn main() -> ! {
    let same = unsafe { k16_shared_memcmp(EXPECTED.as_ptr(), SAME.as_ptr(), EXPECTED.len()) };
    let different =
        unsafe { k16_shared_memcmp(EXPECTED.as_ptr(), DIFFERENT.as_ptr(), EXPECTED.len()) };
    let exit_code = if same == 0 && different < 0 { 42 } else { 1 };
    exit_syscall(exit_code)
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    exit_syscall(1)
}

fn exit_syscall(status: u32) -> ! {
    unsafe {
        let _ = __k16_syscall1(k16_abi::syscall::EXIT, status);
        loop {
            __k16_halt_once();
        }
    }
}
