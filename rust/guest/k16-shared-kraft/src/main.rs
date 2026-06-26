#![no_std]
#![no_main]

use core::panic::PanicInfo;

extern "C" {
    fn __k16_halt_once();
    fn __k16_syscall1(number: u32, arg0: u32) -> u32;
    fn __k16_syscall3(number: u32, arg0: u32, arg1: u32, arg2: u32) -> u32;
    fn __k16_close_syscall(fd: u32) -> u32;
    fn __k16_open_syscall(ptr: u32, len: u32, flags: u32) -> u32;
    fn __k16_read_syscall(fd: u32, ptr: u32, len: u32) -> u32;
    fn __k16_sbrk_syscall(delta: u32) -> u32;
    fn __k16_write_syscall(fd: u32, ptr: u32, len: u32) -> u32;
}

#[no_mangle]
pub unsafe extern "C" fn open(path: *const u8, len: usize, flags: u32) -> u32 {
    unsafe { __k16_open_syscall(path as usize as u32, len as u32, flags) }
}

#[no_mangle]
pub unsafe extern "C" fn read(fd: u32, ptr: *mut u8, len: usize) -> u32 {
    unsafe { __k16_read_syscall(fd, ptr as usize as u32, len as u32) }
}

#[no_mangle]
pub unsafe extern "C" fn write(fd: u32, ptr: *const u8, len: usize) -> u32 {
    unsafe { __k16_write_syscall(fd, ptr as usize as u32, len as u32) }
}

#[no_mangle]
pub extern "C" fn close(fd: u32) -> u32 {
    unsafe { __k16_close_syscall(fd) }
}

#[no_mangle]
pub unsafe extern "C" fn read_dir(request: *const u8, len: usize) -> u32 {
    unsafe {
        __k16_syscall3(
            k16_abi::syscall::READ_DIR,
            request as usize as u32,
            len as u32,
            0,
        )
    }
}

#[no_mangle]
pub unsafe extern "C" fn stat(path: *const u8, len: usize, metadata: *mut u8) -> u32 {
    unsafe {
        __k16_syscall3(
            k16_abi::syscall::STAT,
            path as usize as u32,
            len as u32,
            metadata as usize as u32,
        )
    }
}

#[no_mangle]
pub unsafe extern "C" fn rename(request: *const u8, len: usize) -> u32 {
    unsafe {
        __k16_syscall3(
            k16_abi::syscall::RENAME,
            request as usize as u32,
            len as u32,
            0,
        )
    }
}

#[no_mangle]
pub unsafe extern "C" fn spawn(request: *const u8, len: usize) -> u32 {
    unsafe {
        __k16_syscall3(
            k16_abi::syscall::SPAWN,
            request as usize as u32,
            len as u32,
            0,
        )
    }
}

#[no_mangle]
pub unsafe extern "C" fn wait(pid: u32, status: *mut u32) -> u32 {
    unsafe { __k16_syscall3(k16_abi::syscall::WAIT, pid, status as usize as u32, 0) }
}

#[no_mangle]
pub unsafe extern "C" fn mkdir(path: *const u8, len: usize) -> u32 {
    unsafe { __k16_syscall3(k16_abi::syscall::MKDIR, path as usize as u32, len as u32, 0) }
}

#[no_mangle]
pub unsafe extern "C" fn rmdir(path: *const u8, len: usize) -> u32 {
    unsafe { __k16_syscall3(k16_abi::syscall::RMDIR, path as usize as u32, len as u32, 0) }
}

#[no_mangle]
pub unsafe extern "C" fn unlink(path: *const u8, len: usize) -> u32 {
    unsafe {
        __k16_syscall3(
            k16_abi::syscall::UNLINK,
            path as usize as u32,
            len as u32,
            0,
        )
    }
}

#[no_mangle]
pub extern "C" fn sbrk(delta: u32) -> u32 {
    unsafe { __k16_sbrk_syscall(delta) }
}

#[no_mangle]
pub extern "C" fn _exit(status: u32) -> ! {
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
