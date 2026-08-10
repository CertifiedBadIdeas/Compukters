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

fn r(funct7: u8, rs2: u8, rs1: u8, funct3: u8, rd: u8) -> u32 {
    (u32::from(funct7) << 25)
        | (u32::from(rs2 & 31) << 20)
        | (u32::from(rs1 & 31) << 15)
        | (u32::from(funct3 & 7) << 12)
        | (u32::from(rd & 31) << 7)
        | 0x33
}
fn i(opcode: u8, immediate: i32, rs1: u8, funct3: u8, rd: u8) -> u32 {
    assert!((-2048..=2047).contains(&immediate));
    ((immediate as u32 & 0xfff) << 20)
        | (u32::from(rs1 & 31) << 15)
        | (u32::from(funct3 & 7) << 12)
        | (u32::from(rd & 31) << 7)
        | u32::from(opcode)
}
fn s(immediate: i32, rs2: u8, rs1: u8, funct3: u8) -> u32 {
    assert!((-2048..=2047).contains(&immediate));
    let immediate = immediate as u32 & 0xfff;
    ((immediate >> 5) << 25)
        | (u32::from(rs2 & 31) << 20)
        | (u32::from(rs1 & 31) << 15)
        | (u32::from(funct3 & 7) << 12)
        | ((immediate & 0x1f) << 7)
        | 0x23
}
fn b(offset: i32, rs2: u8, rs1: u8, funct3: u8) -> u32 {
    assert_eq!(offset & 1, 0);
    assert!((-4096..=4094).contains(&offset));
    let immediate = offset as u32 & 0x1fff;
    (((immediate >> 12) & 1) << 31)
        | (((immediate >> 5) & 0x3f) << 25)
        | (u32::from(rs2 & 31) << 20)
        | (u32::from(rs1 & 31) << 15)
        | (u32::from(funct3 & 7) << 12)
        | (((immediate >> 1) & 0x0f) << 8)
        | (((immediate >> 11) & 1) << 7)
        | 0x63
}
fn system(csr: u16, source: u8, funct3: u8, rd: u8) -> u32 {
    (u32::from(csr & 0x0fff) << 20)
        | (u32::from(source & 31) << 15)
        | (u32::from(funct3 & 7) << 12)
        | (u32::from(rd & 31) << 7)
        | 0x73
}

pub fn lui(rd: u8, immediate: u32) -> u32 {
    (immediate & 0xfffff) << 12 | u32::from(rd & 31) << 7 | 0x37
}
pub fn auipc(rd: u8, immediate: u32) -> u32 {
    (immediate & 0xfffff) << 12 | u32::from(rd & 31) << 7 | 0x17
}
pub fn jal(rd: u8, offset: i32) -> u32 {
    assert_eq!(offset & 1, 0);
    assert!((-(1 << 20)..(1 << 20)).contains(&offset));
    let imm = offset as u32 & 0x1f_ffff;
    ((imm >> 20) & 1) << 31
        | ((imm >> 1) & 0x3ff) << 21
        | ((imm >> 11) & 1) << 20
        | ((imm >> 12) & 0xff) << 12
        | u32::from(rd & 31) << 7
        | 0x6f
}
pub fn jalr(rd: u8, rs1: u8, immediate: i32) -> u32 {
    i(0x67, immediate, rs1, 0, rd)
}
pub fn beq(rs1: u8, rs2: u8, offset: i32) -> u32 {
    b(offset, rs2, rs1, 0)
}
pub fn bne(rs1: u8, rs2: u8, offset: i32) -> u32 {
    b(offset, rs2, rs1, 1)
}
pub fn blt(rs1: u8, rs2: u8, offset: i32) -> u32 {
    b(offset, rs2, rs1, 4)
}
pub fn bge(rs1: u8, rs2: u8, offset: i32) -> u32 {
    b(offset, rs2, rs1, 5)
}
pub fn bltu(rs1: u8, rs2: u8, offset: i32) -> u32 {
    b(offset, rs2, rs1, 6)
}
pub fn bgeu(rs1: u8, rs2: u8, offset: i32) -> u32 {
    b(offset, rs2, rs1, 7)
}
pub fn lb(rd: u8, rs1: u8, immediate: i32) -> u32 {
    i(0x03, immediate, rs1, 0, rd)
}
pub fn lh(rd: u8, rs1: u8, immediate: i32) -> u32 {
    i(0x03, immediate, rs1, 1, rd)
}
pub fn lw(rd: u8, rs1: u8, immediate: i32) -> u32 {
    i(0x03, immediate, rs1, 2, rd)
}
pub fn lbu(rd: u8, rs1: u8, immediate: i32) -> u32 {
    i(0x03, immediate, rs1, 4, rd)
}
pub fn lhu(rd: u8, rs1: u8, immediate: i32) -> u32 {
    i(0x03, immediate, rs1, 5, rd)
}
pub fn sb(rs1: u8, rs2: u8, immediate: i32) -> u32 {
    s(immediate, rs2, rs1, 0)
}
pub fn sh(rs1: u8, rs2: u8, immediate: i32) -> u32 {
    s(immediate, rs2, rs1, 1)
}
pub fn sw(rs1: u8, rs2: u8, immediate: i32) -> u32 {
    s(immediate, rs2, rs1, 2)
}
pub fn addi(rd: u8, rs1: u8, immediate: i32) -> u32 {
    i(0x13, immediate, rs1, 0, rd)
}
pub fn slti(rd: u8, rs1: u8, immediate: i32) -> u32 {
    i(0x13, immediate, rs1, 2, rd)
}
pub fn sltiu(rd: u8, rs1: u8, immediate: i32) -> u32 {
    i(0x13, immediate, rs1, 3, rd)
}
pub fn xori(rd: u8, rs1: u8, immediate: i32) -> u32 {
    i(0x13, immediate, rs1, 4, rd)
}
pub fn ori(rd: u8, rs1: u8, immediate: i32) -> u32 {
    i(0x13, immediate, rs1, 6, rd)
}
pub fn andi(rd: u8, rs1: u8, immediate: i32) -> u32 {
    i(0x13, immediate, rs1, 7, rd)
}
pub fn slli(rd: u8, rs1: u8, shift: u8) -> u32 {
    i(0x13, i32::from(shift & 31), rs1, 1, rd)
}
pub fn srli(rd: u8, rs1: u8, shift: u8) -> u32 {
    i(0x13, i32::from(shift & 31), rs1, 5, rd)
}
pub fn srai(rd: u8, rs1: u8, shift: u8) -> u32 {
    i(0x13, i32::from(shift & 31) | 0x400, rs1, 5, rd)
}
pub fn add(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(0, rs2, rs1, 0, rd)
}
pub fn sub(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(0x20, rs2, rs1, 0, rd)
}
pub fn sll(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(0, rs2, rs1, 1, rd)
}
pub fn slt(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(0, rs2, rs1, 2, rd)
}
pub fn sltu(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(0, rs2, rs1, 3, rd)
}
pub fn xor(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(0, rs2, rs1, 4, rd)
}
pub fn srl(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(0, rs2, rs1, 5, rd)
}
pub fn sra(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(0x20, rs2, rs1, 5, rd)
}
pub fn or(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(0, rs2, rs1, 6, rd)
}
pub fn and(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(0, rs2, rs1, 7, rd)
}
pub fn mul(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(1, rs2, rs1, 0, rd)
}
pub fn mulh(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(1, rs2, rs1, 1, rd)
}
pub fn mulhsu(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(1, rs2, rs1, 2, rd)
}
pub fn mulhu(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(1, rs2, rs1, 3, rd)
}
pub fn div(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(1, rs2, rs1, 4, rd)
}
pub fn divu(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(1, rs2, rs1, 5, rd)
}
pub fn rem(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(1, rs2, rs1, 6, rd)
}
pub fn remu(rd: u8, rs1: u8, rs2: u8) -> u32 {
    r(1, rs2, rs1, 7, rd)
}
pub fn ecall() -> u32 {
    0x0000_0073
}
pub fn ebreak() -> u32 {
    0x0010_0073
}
pub fn csrrw(rd: u8, csr: u16, rs1: u8) -> u32 {
    system(csr, rs1, 1, rd)
}
pub fn csrrs(rd: u8, csr: u16, rs1: u8) -> u32 {
    system(csr, rs1, 2, rd)
}
pub fn csrrc(rd: u8, csr: u16, rs1: u8) -> u32 {
    system(csr, rs1, 3, rd)
}
pub fn csrrwi(rd: u8, csr: u16, immediate: u8) -> u32 {
    system(csr, immediate, 5, rd)
}
pub fn csrrsi(rd: u8, csr: u16, immediate: u8) -> u32 {
    system(csr, immediate, 6, rd)
}
pub fn csrrci(rd: u8, csr: u16, immediate: u8) -> u32 {
    system(csr, immediate, 7, rd)
}
pub fn mret() -> u32 {
    0x3020_0073
}

pub fn materialize(rd: u8, value: u32) -> [u32; 2] {
    let upper = ((u64::from(value) + 0x800) >> 12) as u32 & 0xfffff;
    let lower = value.wrapping_sub(upper << 12) as i32;
    [lui(rd, upper), addi(rd, rd, lower)]
}
