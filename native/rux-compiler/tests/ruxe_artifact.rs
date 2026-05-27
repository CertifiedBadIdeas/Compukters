use rux_compiler::ruxe;

#[test]
fn ruxe_encodes_single_rux16_load_segment() {
    let bytes = ruxe::encode_rux16_executable(&[0x01, 0x00], 0, 0).expect("RUXE encodes");

    assert_eq!(&bytes[0..4], b"RUXE");
    assert_eq!(u16_at(&bytes, 4), 1);
    assert_eq!(u16_at(&bytes, 6), 32);
    assert_eq!(u16_at(&bytes, 8), 1);
    assert_eq!(u16_at(&bytes, 10), 0);
    assert_eq!(u32_at(&bytes, 12), 0);
    assert_eq!(u32_at(&bytes, 16), 32);
    assert_eq!(u32_at(&bytes, 20), 1);
    assert_eq!(u32_at(&bytes, 24), 0);
    assert_eq!(u32_at(&bytes, 28), 0);
    assert_eq!(u32_at(&bytes, 32), 1);
    assert_eq!(u32_at(&bytes, 36), 0);
    assert_eq!(u32_at(&bytes, 40), 52);
    assert_eq!(u32_at(&bytes, 44), 2);
    assert_eq!(u32_at(&bytes, 48), 2);
    assert_eq!(&bytes[52..], &[0x01, 0x00]);
}

#[test]
fn ruxe_decodes_single_rux16_load_segment() {
    let bytes = ruxe::encode_rux16_executable(&[0x01, 0x00], 0x100, 0x100).expect("RUXE encodes");

    let executable = ruxe::decode_rux16_executable(&bytes).expect("RUXE decodes");

    assert_eq!(executable.entry_pc, 0x100);
    assert_eq!(executable.load_addr, 0x100);
    assert_eq!(executable.payload, vec![0x01, 0x00]);
}

#[test]
fn ruxe_rejects_malformed_magic_without_raw_fallback() {
    let error = ruxe::decode_rux16_executable(&[0x01, 0x00]).unwrap_err();

    assert_eq!(error, "invalid RUXE magic");
}

fn u16_at(bytes: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes(bytes[offset..offset + 2].try_into().unwrap())
}

fn u32_at(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
}
