pub(crate) const SECONDARY_SCRATCH_REGISTER: u8 = 13;
pub(crate) const SCRATCH_REGISTER: u8 = 14;
pub(crate) const STACK_POINTER_REGISTER: u8 = 15;
pub(crate) const FRAME_POINTER_REGISTER: u8 = 12;
pub(crate) const RETURN_REGISTER: u8 = 0;
pub(crate) const ARGUMENT_REGISTERS: [u8; 3] = [1, 2, 3];

pub(crate) fn encode_words(words: &[u16]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(words.len() * 2);
    for word in words {
        bytes.extend_from_slice(&word.to_le_bytes());
    }
    bytes
}

pub(crate) fn halt() -> u16 {
    0x0001
}

pub(crate) fn const32(register: u8, value: u32) -> [u16; 3] {
    assert_register(register);
    [
        0xe001 | (u16::from(register) << 8),
        (value & 0xffff) as u16,
        (value >> 16) as u16,
    ]
}

pub(crate) fn add(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x0, lhs, rhs)
}

pub(crate) fn sub(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x1, lhs, rhs)
}

pub(crate) fn and(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x2, lhs, rhs)
}

pub(crate) fn or(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x3, lhs, rhs)
}

pub(crate) fn xor(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x4, lhs, rhs)
}

pub(crate) fn shl(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x5, lhs, rhs)
}

pub(crate) fn shr(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x6, lhs, rhs)
}

pub(crate) fn sar(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x7, lhs, rhs)
}

pub(crate) fn eq(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x8, lhs, rhs)
}

pub(crate) fn ne(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x9, lhs, rhs)
}

pub(crate) fn ltu(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0xa, lhs, rhs)
}

pub(crate) fn lt_s(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0xb, lhs, rhs)
}

fn alu_rrr(dst: u8, subop: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    assert_register(dst);
    assert_register(lhs);
    assert_register(rhs);
    [
        0x2000 | (u16::from(dst) << 8) | u16::from(subop),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

pub(crate) fn load32(dst: u8, addr: u8) -> u16 {
    assert_register(dst);
    assert_register(addr);
    0x4002 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

#[allow(dead_code)]
pub(crate) fn load16(dst: u8, addr: u8) -> u16 {
    assert_register(dst);
    assert_register(addr);
    0x4001 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

pub(crate) fn load8(dst: u8, addr: u8) -> u16 {
    assert_register(dst);
    assert_register(addr);
    0x4000 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

pub(crate) fn store32(addr: u8, src: u8) -> u16 {
    assert_register(addr);
    assert_register(src);
    0x5002 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

#[allow(dead_code)]
pub(crate) fn store16(addr: u8, src: u8) -> u16 {
    assert_register(addr);
    assert_register(src);
    0x5001 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

pub(crate) fn store8(addr: u8, src: u8) -> u16 {
    assert_register(addr);
    assert_register(src);
    0x5000 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

pub(crate) fn branch_if_nonzero(register: u8, offset_words: i8) -> u16 {
    assert_register(register);
    0x6000 | (u16::from(register) << 8) | 0x0010 | encode_signed_nibble(offset_words)
}

#[allow(dead_code)]
pub(crate) fn branch_if_zero(register: u8, offset_words: i8) -> u16 {
    assert_register(register);
    0x6000 | (u16::from(register) << 8) | encode_signed_nibble(offset_words)
}

pub(crate) fn jmp(register: u8) -> u16 {
    assert_register(register);
    0x7000 | (u16::from(register) << 8)
}

pub(crate) fn call(register: u8) -> u16 {
    assert_register(register);
    0x8000 | (u16::from(register) << 8)
}

pub(crate) fn ret() -> u16 {
    0x9000
}

pub(crate) fn read_csr(dst: u8, csr: u8) -> u16 {
    assert_register(dst);
    assert_csr(csr);
    0x0002 | (u16::from(dst) << 8) | (u16::from(csr) << 4)
}

pub(crate) fn write_csr(csr: u8, src: u8) -> u16 {
    assert_csr(csr);
    assert_register(src);
    0x0003 | (u16::from(csr) << 8) | (u16::from(src) << 4)
}

fn assert_register(register: u8) {
    assert!(
        register <= STACK_POINTER_REGISTER,
        "Rux16 register index {register} is outside 0..=15"
    );
}

fn assert_csr(csr: u8) {
    assert!(csr <= 15, "Rux16 CSR index {csr} is outside 0..=15");
}

fn encode_signed_nibble(value: i8) -> u16 {
    assert!(
        (-8..=7).contains(&value),
        "Rux16 branch offset {value} is outside -8..=7 words"
    );
    u16::from((value as i16 & 0x000f) as u8)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn assembler_encodes_branch_helpers_for_both_zero_predicates() {
        assert_eq!(branch_if_zero(1, -1), 0x610f);
        assert_eq!(branch_if_nonzero(1, -1), 0x611f);
    }

    #[test]
    fn assembler_encodes_complete_llvm_readiness_surface() {
        assert_eq!(halt(), 0x0001);
        assert_eq!(read_csr(1, 2), 0x0122);
        assert_eq!(write_csr(3, 1), 0x0313);
        assert_eq!(const32(1, 0x1234_5678), [0xe101, 0x5678, 0x1234]);
        assert_eq!(add(2, 1, 3), [0x2200, 0x0013]);
        assert_eq!(sub(2, 1, 3), [0x2201, 0x0013]);
        assert_eq!(and(2, 1, 3), [0x2202, 0x0013]);
        assert_eq!(or(2, 1, 3), [0x2203, 0x0013]);
        assert_eq!(xor(2, 1, 3), [0x2204, 0x0013]);
        assert_eq!(shl(2, 1, 3), [0x2205, 0x0013]);
        assert_eq!(shr(2, 1, 3), [0x2206, 0x0013]);
        assert_eq!(sar(2, 1, 3), [0x2207, 0x0013]);
        assert_eq!(eq(2, 1, 3), [0x2208, 0x0013]);
        assert_eq!(ne(2, 1, 3), [0x2209, 0x0013]);
        assert_eq!(ltu(2, 1, 3), [0x220a, 0x0013]);
        assert_eq!(lt_s(2, 1, 3), [0x220b, 0x0013]);
        assert_eq!(load8(4, 5), 0x4450);
        assert_eq!(load16(4, 5), 0x4451);
        assert_eq!(load32(4, 5), 0x4452);
        assert_eq!(store8(4, 5), 0x5450);
        assert_eq!(store16(4, 5), 0x5451);
        assert_eq!(store32(4, 5), 0x5452);
        assert_eq!(jmp(6), 0x7600);
        assert_eq!(call(6), 0x8600);
        assert_eq!(ret(), 0x9000);
    }
}
