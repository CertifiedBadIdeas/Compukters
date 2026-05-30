#![feature(no_core, lang_items)]
#![no_core]
#![no_main]

#[lang = "sized"]
pub trait Sized: MetaSized {}

#[lang = "meta_sized"]
pub trait MetaSized: PointeeSized {}

#[lang = "pointee_sized"]
pub trait PointeeSized {}

#[lang = "legacy_receiver"]
pub trait LegacyReceiver {}

impl<T: PointeeSized> LegacyReceiver for &T {}
impl<T: PointeeSized> LegacyReceiver for &mut T {}

#[lang = "copy"]
pub trait Copy {}

impl Copy for bool {}
impl Copy for usize {}
impl Copy for i32 {}
impl Copy for u8 {}
impl<T: PointeeSized> Copy for *const T {}
impl<T: PointeeSized> Copy for *mut T {}

#[lang = "add"]
pub trait Add<Rhs = Self> {
    type Output;

    fn add(self, rhs: Rhs) -> Self::Output;
}

impl Add for usize {
    type Output = usize;

    fn add(self, rhs: usize) -> usize {
        self + rhs
    }
}

#[lang = "sub"]
pub trait Sub<Rhs = Self> {
    type Output;

    fn sub(self, rhs: Rhs) -> Self::Output;
}

impl Sub for usize {
    type Output = usize;

    fn sub(self, rhs: usize) -> usize {
        self - rhs
    }
}

#[lang = "partial_ord"]
pub trait PartialOrd<Rhs = Self> {
    fn lt(&self, other: &Rhs) -> bool;
    fn gt(&self, other: &Rhs) -> bool;
}

impl PartialOrd for usize {
    fn lt(&self, other: &usize) -> bool {
        *self < *other
    }

    fn gt(&self, other: &usize) -> bool {
        *self > *other
    }
}

#[no_mangle]
pub unsafe extern "C" fn __rux16_memcpy(dst: *mut u8, src: *const u8, n: usize) -> *mut u8 {
    let dst_addr = dst as usize;
    let src_addr = src as usize;
    let mut index: usize = 0;
    while index < n {
        unsafe {
            *((dst_addr + index) as *mut u8) = *((src_addr + index) as *const u8);
        }
        index = index + 1;
    }
    dst
}

#[no_mangle]
pub unsafe extern "C" fn __rux16_memset(dst: *mut u8, value: i32, n: usize) -> *mut u8 {
    let dst_addr = dst as usize;
    let byte = value as u8;
    let mut index: usize = 0;
    while index < n {
        unsafe {
            *((dst_addr + index) as *mut u8) = byte;
        }
        index = index + 1;
    }
    dst
}

#[no_mangle]
pub unsafe extern "C" fn __rux16_memmove(dst: *mut u8, src: *const u8, n: usize) -> *mut u8 {
    let dst_addr = dst as usize;
    let src_addr = src as usize;
    if dst_addr < src_addr {
        let mut index: usize = 0;
        while index < n {
            unsafe {
                *((dst_addr + index) as *mut u8) = *((src_addr + index) as *const u8);
            }
            index = index + 1;
        }
    } else {
        let mut remaining = n;
        while remaining > 0usize {
            remaining = remaining - 1;
            unsafe {
                *((dst_addr + remaining) as *mut u8) = *((src_addr + remaining) as *const u8);
            }
        }
    }
    dst
}
