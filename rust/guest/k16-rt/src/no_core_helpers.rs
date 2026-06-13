#![feature(no_core, lang_items)]
#![no_core]
#![no_main]

// Guest runtime source. Host tooling may compile this file, but must not own it.

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
impl Copy for u32 {}
impl Copy for i32 {}
impl Copy for u64 {}
impl Copy for i64 {}
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

impl Add for u64 {
    type Output = u64;

    fn add(self, rhs: u64) -> u64 {
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

impl Sub for u64 {
    type Output = u64;

    fn sub(self, rhs: u64) -> u64 {
        self - rhs
    }
}

impl Sub for i64 {
    type Output = i64;

    fn sub(self, rhs: i64) -> i64 {
        self - rhs
    }
}

#[lang = "eq"]
pub trait PartialEq<Rhs = Self> {
    fn eq(&self, other: &Rhs) -> bool;
    fn ne(&self, other: &Rhs) -> bool;
}

impl PartialEq for bool {
    fn eq(&self, other: &bool) -> bool {
        (*self) == (*other)
    }

    fn ne(&self, other: &bool) -> bool {
        (*self) != (*other)
    }
}

impl PartialEq for u64 {
    fn eq(&self, other: &u64) -> bool {
        (*self) == (*other)
    }

    fn ne(&self, other: &u64) -> bool {
        (*self) != (*other)
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

impl PartialOrd for u32 {
    fn lt(&self, other: &u32) -> bool {
        *self < *other
    }

    fn gt(&self, other: &u32) -> bool {
        *self > *other
    }
}

impl PartialOrd for u64 {
    fn lt(&self, other: &u64) -> bool {
        *self < *other
    }

    fn gt(&self, other: &u64) -> bool {
        *self > *other
    }
}

impl PartialOrd for i64 {
    fn lt(&self, other: &i64) -> bool {
        *self < *other
    }

    fn gt(&self, other: &i64) -> bool {
        *self > *other
    }
}

#[lang = "bitand"]
pub trait BitAnd<Rhs = Self> {
    type Output;

    fn bitand(self, rhs: Rhs) -> Self::Output;
}

impl BitAnd for u64 {
    type Output = u64;

    fn bitand(self, rhs: u64) -> u64 {
        self & rhs
    }
}

#[lang = "bitor"]
pub trait BitOr<Rhs = Self> {
    type Output;

    fn bitor(self, rhs: Rhs) -> Self::Output;
}

impl BitOr for u64 {
    type Output = u64;

    fn bitor(self, rhs: u64) -> u64 {
        self | rhs
    }
}

impl BitOr for u32 {
    type Output = u32;

    fn bitor(self, rhs: u32) -> u32 {
        self | rhs
    }
}

#[lang = "shl"]
pub trait Shl<Rhs = Self> {
    type Output;

    fn shl(self, rhs: Rhs) -> Self::Output;
}

impl Shl<usize> for u64 {
    type Output = u64;

    fn shl(self, rhs: usize) -> u64 {
        self << rhs
    }
}

impl Shl<usize> for u32 {
    type Output = u32;

    fn shl(self, rhs: usize) -> u32 {
        self << rhs
    }
}

#[lang = "shr"]
pub trait Shr<Rhs = Self> {
    type Output;

    fn shr(self, rhs: Rhs) -> Self::Output;
}

impl Shr<usize> for u64 {
    type Output = u64;

    fn shr(self, rhs: usize) -> u64 {
        self >> rhs
    }
}

impl Shr<usize> for u32 {
    type Output = u32;

    fn shr(self, rhs: usize) -> u32 {
        self >> rhs
    }
}

impl Shr<usize> for i32 {
    type Output = i32;

    fn shr(self, rhs: usize) -> i32 {
        self >> rhs
    }
}

#[no_mangle]
pub unsafe extern "C" fn __k16_memcpy(dst: *mut u8, src: *const u8, n: usize) -> *mut u8 {
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
pub fn __udivdi3(lhs: u64, rhs: u64) -> u64 {
    k16_udiv64(lhs, rhs)
}

#[no_mangle]
pub fn __umoddi3(lhs: u64, rhs: u64) -> u64 {
    k16_umod64(lhs, rhs)
}

#[no_mangle]
pub fn __ashldi3(value: u64, shift: u32) -> u64 {
    let mut count = shift as usize;
    while count > 63usize {
        count = count - 64usize;
    }
    if count < 1usize {
        return value;
    }

    let (lo, hi) = k16_split_u64(value);
    if count < 32usize {
        k16_pack_u64(lo << count, (hi << count) | (lo >> (32usize - count)))
    } else {
        k16_pack_u64(0u32, lo << (count - 32usize))
    }
}

#[no_mangle]
pub fn __lshrdi3(value: u64, shift: u32) -> u64 {
    let mut count = shift as usize;
    while count > 63usize {
        count = count - 64usize;
    }
    if count < 1usize {
        return value;
    }

    let (lo, hi) = k16_split_u64(value);
    if count < 32usize {
        k16_pack_u64((lo >> count) | (hi << (32usize - count)), hi >> count)
    } else {
        k16_pack_u64(hi >> (count - 32usize), 0u32)
    }
}

#[no_mangle]
pub fn __ashrdi3(value: i64, shift: u32) -> i64 {
    let mut count = shift as usize;
    while count > 63usize {
        count = count - 64usize;
    }
    if count < 1usize {
        return value;
    }

    let (lo, hi) = k16_split_u64(value as u64);
    let signed_hi = hi as i32;
    if count < 32usize {
        k16_pack_u64(
            (lo >> count) | (hi << (32usize - count)),
            (signed_hi >> count) as u32,
        ) as i64
    } else {
        let fill = if hi < 0x80000000u32 {
            0u32
        } else {
            4294967295u32
        };
        k16_pack_u64((signed_hi >> (count - 32usize)) as u32, fill) as i64
    }
}

#[no_mangle]
pub fn __divdi3(lhs: i64, rhs: i64) -> i64 {
    k16_div64(lhs, rhs)
}

#[no_mangle]
pub fn __moddi3(lhs: i64, rhs: i64) -> i64 {
    k16_mod64(lhs, rhs)
}

fn k16_udiv64(lhs: u64, rhs: u64) -> u64 {
    k16_udivmod64(lhs, rhs).0
}

fn k16_umod64(lhs: u64, rhs: u64) -> u64 {
    k16_udivmod64(lhs, rhs).1
}

fn k16_div64(lhs: i64, rhs: i64) -> i64 {
    let quotient = k16_udiv64(k16_i64_abs_bits(lhs), k16_i64_abs_bits(rhs));
    if lhs < 0i64 {
        if rhs < 0i64 {
            quotient as i64
        } else {
            k16_negate_u64_bits(quotient)
        }
    } else if rhs < 0i64 {
        k16_negate_u64_bits(quotient)
    } else {
        quotient as i64
    }
}

fn k16_mod64(lhs: i64, rhs: i64) -> i64 {
    let remainder = k16_umod64(k16_i64_abs_bits(lhs), k16_i64_abs_bits(rhs));
    if lhs < 0i64 {
        k16_negate_u64_bits(remainder)
    } else {
        remainder as i64
    }
}

fn k16_udivmod64(lhs: u64, rhs: u64) -> (u64, u64) {
    if rhs == 0u64 {
        return (0u64, lhs);
    }

    let mut quotient = 0u64;
    let mut remainder = 0u64;
    let mut bit_index = 64usize;
    while bit_index > 0usize {
        bit_index = bit_index - 1usize;
        remainder = (remainder << 1usize) | ((lhs >> bit_index) & 1u64);
        if remainder < rhs {
        } else {
            remainder = remainder - rhs;
            quotient = quotient | (1u64 << bit_index);
        }
    }
    (quotient, remainder)
}

fn k16_i64_abs_bits(value: i64) -> u64 {
    let bits = value as u64;
    if value < 0i64 {
        0u64 - bits
    } else {
        bits
    }
}

fn k16_negate_u64_bits(value: u64) -> i64 {
    (0u64 - value) as i64
}

fn k16_split_u64(value: u64) -> (u32, u32) {
    unsafe {
        let lo = (&value as *const u64) as *const u32;
        let hi = ((lo as usize) + 4usize) as *const u32;
        (*lo, *hi)
    }
}

fn k16_pack_u64(lo: u32, hi: u32) -> u64 {
    let mut value = 0u64;
    unsafe {
        let lo_out = (&mut value as *mut u64) as *mut u32;
        let hi_out = ((lo_out as usize) + 4usize) as *mut u32;
        *lo_out = lo;
        *hi_out = hi;
    }
    value
}

#[no_mangle]
pub unsafe extern "C" fn __k16_memset(dst: *mut u8, value: i32, n: usize) -> *mut u8 {
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
pub unsafe extern "C" fn __k16_memmove(dst: *mut u8, src: *const u8, n: usize) -> *mut u8 {
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
