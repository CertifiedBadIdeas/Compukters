pub(crate) const SECONDARY_SCRATCH_REGISTER: u8 = 13;
pub(crate) const SCRATCH_REGISTER: u8 = 14;
pub(crate) const STACK_POINTER_REGISTER: u8 = 15;

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

pub(crate) fn add(dst: u8, lhs: u8, rhs: u8) -> u16 {
    assert_register(dst);
    assert_register(lhs);
    assert_register(rhs);
    0x2000 | (u16::from(dst) << 8) | (u16::from(lhs) << 4) | u16::from(rhs)
}

pub(crate) fn eq(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    assert_register(dst);
    assert_register(lhs);
    assert_register(rhs);
    [
        0x3000 | (u16::from(dst) << 8),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

pub(crate) fn ltu(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    assert_register(dst);
    assert_register(lhs);
    assert_register(rhs);
    [
        0x3002 | (u16::from(dst) << 8),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

pub(crate) fn load32(dst: u8, addr: u8) -> u16 {
    assert_register(dst);
    assert_register(addr);
    0x4002 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
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

pub(crate) fn store8(addr: u8, src: u8) -> u16 {
    assert_register(addr);
    assert_register(src);
    0x5000 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

pub(crate) fn branch_if_nonzero(register: u8, offset_words: i8) -> u16 {
    assert_register(register);
    0x6000 | (u16::from(register) << 8) | 0x0010 | encode_signed_nibble(offset_words)
}

pub(crate) fn jmp(register: u8) -> u16 {
    assert_register(register);
    0x7000 | (u16::from(register) << 8)
}

fn assert_register(register: u8) {
    assert!(
        register <= STACK_POINTER_REGISTER,
        "Rux16 register index {register} is outside 0..=15"
    );
}

fn encode_signed_nibble(value: i8) -> u16 {
    assert!(
        (-8..=7).contains(&value),
        "Rux16 branch offset {value} is outside -8..=7 words"
    );
    u16::from((value as i16 & 0x000f) as u8)
}
