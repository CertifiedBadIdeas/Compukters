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

pub(crate) fn store32(addr: u8, src: u8) -> u16 {
    assert_register(addr);
    assert_register(src);
    0x5002 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn assert_register(register: u8) {
    assert!(
        register <= 0x0f,
        "Rux16 register index {register} is outside 0..=15"
    );
}
