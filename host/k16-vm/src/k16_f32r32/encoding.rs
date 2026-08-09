/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

fn register(register: u8) -> u32 {
    assert!(register < 32, "K16-F32R32 register must be in 0..32");
    u32::from(register)
}

fn rrr(opcode: u8, dst: u8, lhs: u8, rhs: u8) -> u32 {
    (u32::from(opcode) << 24) | (register(dst) << 19) | (register(lhs) << 14) | (register(rhs) << 9)
}

fn ri14(opcode: u8, dst: u8, src: u8, immediate: i32) -> u32 {
    assert!((-8192..=8191).contains(&immediate));
    (u32::from(opcode) << 24)
        | (register(dst) << 19)
        | (register(src) << 14)
        | (immediate as u32 & 0x3fff)
}

fn br19(opcode: u8, src: u8, offset: i32) -> u32 {
    assert!((-(1 << 18)..(1 << 18)).contains(&offset));
    (u32::from(opcode) << 24) | (register(src) << 19) | (offset as u32 & 0x0007_ffff)
}

fn j24(opcode: u8, offset: i32) -> u32 {
    assert!((-(1 << 23)..(1 << 23)).contains(&offset));
    (u32::from(opcode) << 24) | (offset as u32 & 0x00ff_ffff)
}

pub fn nop() -> u32 {
    0x00 << 24
}
pub fn halt() -> u32 {
    0x01 << 24
}
pub fn yield_now() -> u32 {
    0x02 << 24
}
pub fn lui(dst: u8, immediate: u32) -> u32 {
    assert!(immediate < (1 << 19));
    (0x10 << 24) | (register(dst) << 19) | immediate
}
pub fn addi(dst: u8, src: u8, immediate: i32) -> u32 {
    ri14(0x11, dst, src, immediate)
}
pub fn add(dst: u8, lhs: u8, rhs: u8) -> u32 {
    rrr(0x20, dst, lhs, rhs)
}
pub fn sub(dst: u8, lhs: u8, rhs: u8) -> u32 {
    rrr(0x21, dst, lhs, rhs)
}
pub fn mul(dst: u8, lhs: u8, rhs: u8) -> u32 {
    rrr(0x22, dst, lhs, rhs)
}
pub fn and(dst: u8, lhs: u8, rhs: u8) -> u32 {
    rrr(0x23, dst, lhs, rhs)
}
pub fn or(dst: u8, lhs: u8, rhs: u8) -> u32 {
    rrr(0x24, dst, lhs, rhs)
}
pub fn xor(dst: u8, lhs: u8, rhs: u8) -> u32 {
    rrr(0x25, dst, lhs, rhs)
}
pub fn shl(dst: u8, lhs: u8, rhs: u8) -> u32 {
    rrr(0x26, dst, lhs, rhs)
}
pub fn shr(dst: u8, lhs: u8, rhs: u8) -> u32 {
    rrr(0x27, dst, lhs, rhs)
}
pub fn sar(dst: u8, lhs: u8, rhs: u8) -> u32 {
    rrr(0x28, dst, lhs, rhs)
}
pub fn eq(dst: u8, lhs: u8, rhs: u8) -> u32 {
    rrr(0x29, dst, lhs, rhs)
}
pub fn ne(dst: u8, lhs: u8, rhs: u8) -> u32 {
    rrr(0x2a, dst, lhs, rhs)
}
pub fn ltu(dst: u8, lhs: u8, rhs: u8) -> u32 {
    rrr(0x2b, dst, lhs, rhs)
}
pub fn lt_s(dst: u8, lhs: u8, rhs: u8) -> u32 {
    rrr(0x2c, dst, lhs, rhs)
}
pub fn load8(dst: u8, base: u8, offset: i32) -> u32 {
    ri14(0x30, dst, base, offset)
}
pub fn load16(dst: u8, base: u8, offset: i32) -> u32 {
    ri14(0x31, dst, base, offset)
}
pub fn load32(dst: u8, base: u8, offset: i32) -> u32 {
    ri14(0x32, dst, base, offset)
}
pub fn store8(base: u8, src: u8, offset: i32) -> u32 {
    ri14(0x38, base, src, offset)
}
pub fn store16(base: u8, src: u8, offset: i32) -> u32 {
    ri14(0x39, base, src, offset)
}
pub fn store32(base: u8, src: u8, offset: i32) -> u32 {
    ri14(0x3a, base, src, offset)
}
pub fn branchz(src: u8, offset: i32) -> u32 {
    br19(0x40, src, offset)
}
pub fn branchnz(src: u8, offset: i32) -> u32 {
    br19(0x41, src, offset)
}
pub fn jump(offset: i32) -> u32 {
    j24(0x42, offset)
}
pub fn call(offset: i32) -> u32 {
    j24(0x43, offset)
}
pub fn ret() -> u32 {
    0x44 << 24
}
pub fn branch_ltu(lhs: u8, rhs: u8, offset: i32) -> u32 {
    ri14(0x45, lhs, rhs, offset)
}
pub fn branch_uge(lhs: u8, rhs: u8, offset: i32) -> u32 {
    ri14(0x46, lhs, rhs, offset)
}

pub fn materialize(dst: u8, value: u32) -> [u32; 2] {
    let upper = ((u64::from(value) + 0x1000) >> 13) as u32 & 0x0007_ffff;
    let lower = value.wrapping_sub(upper << 13) as i32;
    debug_assert!((-8192..=8191).contains(&lower));
    [lui(dst, upper), addi(dst, dst, lower)]
}
