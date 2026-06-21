#![no_std]
#![no_main]

use core::panic::PanicInfo;

extern "C" {
    fn __k16_halt_once();
    fn __k16_syscall1(number: u32, arg0: u32) -> u32;
    fn k16rt_memcpy(dst: *mut u8, src: *const u8, n: usize) -> *mut u8;
    fn k16rt_memset(dst: *mut u8, value: i32, n: usize) -> *mut u8;
    fn k16rt_memmove(dst: *mut u8, src: *const u8, n: usize) -> *mut u8;
    fn k16rt_memcmp(lhs: *const u8, rhs: *const u8, n: usize) -> i32;
}

static EXPECTED: &[u8; 9] = b"K16SHARED";
static SAME: &[u8; 9] = b"K16SHARED";
static DIFFERENT: &[u8; 9] = b"K16SHAREE";

#[no_mangle]
pub extern "C" fn main() -> ! {
    let mut copied = [0u8; 9];
    let mut filled = [0u8; 4];
    let mut moved = *b"ABCDE";

    unsafe {
        k16rt_memcpy(copied.as_mut_ptr(), EXPECTED.as_ptr(), EXPECTED.len());
        k16rt_memset(filled.as_mut_ptr(), 0x41, filled.len());
        k16rt_memmove(moved.as_mut_ptr().add(1), moved.as_ptr(), 4);
    }

    let same = unsafe { k16rt_memcmp(copied.as_ptr(), SAME.as_ptr(), SAME.len()) };
    let different = unsafe { k16rt_memcmp(EXPECTED.as_ptr(), DIFFERENT.as_ptr(), EXPECTED.len()) };
    let copied_ok = same == 0;
    let filled_ok = filled == *b"AAAA";
    let moved_ok = moved == *b"AABCD";
    let compare_ok = different < 0;
    let exit_code = if copied_ok && filled_ok && moved_ok && compare_ok {
        42
    } else {
        1
    };
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
