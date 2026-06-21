#![no_std]

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
