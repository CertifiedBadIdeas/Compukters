#![no_std]
#![no_main]

use core::panic::PanicInfo;

extern "C" {
    fn __k16_halt_once();
    fn __k16_syscall1(number: u32, arg0: u32) -> u32;
    fn __k16_write_syscall(fd: u32, ptr: u32, len: u32) -> u32;
}

#[no_mangle]
pub unsafe extern "C" fn kraft_write_all(fd: u32, ptr: *const u8, len: usize) -> u32 {
    unsafe { __k16_write_syscall(fd, ptr as usize as u32, len as u32) }
}

#[no_mangle]
pub extern "C" fn kraft_exit(status: u32) -> ! {
    unsafe {
        let _ = __k16_syscall1(k16_abi::syscall::EXIT, status);
    }
    loop {
        unsafe {
            __k16_halt_once();
        }
    }
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    loop {}
}
