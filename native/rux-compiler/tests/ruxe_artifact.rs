use rux_compiler::ruxe;

#[test]
fn ruxe_encodes_single_rux16_load_segment() {
    let bytes =
        ruxe::encode_rux16_executable(&[0x01, 0x00], ruxe::RuxeAbiKind::Bootloader, 0x800, 0x800)
            .expect("RUXE encodes");

    assert_eq!(&bytes[0..4], b"RUXE");
    assert_eq!(u16_at(&bytes, 4), 1);
    assert_eq!(u16_at(&bytes, 6), 32);
    assert_eq!(u16_at(&bytes, 8), 1);
    assert_eq!(u16_at(&bytes, 10), 0);
    assert_eq!(u32_at(&bytes, 12), 0x800);
    assert_eq!(u32_at(&bytes, 16), 32);
    assert_eq!(u32_at(&bytes, 20), 1);
    assert_eq!(u32_at(&bytes, 24), 1);
    assert_eq!(u32_at(&bytes, 28), 0);
    assert_eq!(u32_at(&bytes, 32), 1);
    assert_eq!(u32_at(&bytes, 36), 0x800);
    assert_eq!(u32_at(&bytes, 40), 52);
    assert_eq!(u32_at(&bytes, 44), 2);
    assert_eq!(u32_at(&bytes, 48), 2);
    assert_eq!(&bytes[52..], &[0x01, 0x00]);
}

#[test]
fn ruxe_decodes_single_rux16_load_segment() {
    let bytes =
        ruxe::encode_rux16_executable(&[0x01, 0x00], ruxe::RuxeAbiKind::Kernel, 0x4000, 0x4000)
            .expect("RUXE encodes");

    let executable = ruxe::decode_rux16_executable(&bytes).expect("RUXE decodes");

    assert_eq!(executable.abi_kind, ruxe::RuxeAbiKind::Kernel);
    assert_eq!(executable.entry_pc, 0x4000);
    assert_eq!(executable.load_addr, 0x4000);
    assert_eq!(executable.payload, vec![0x01, 0x00]);
}

#[test]
fn ruxe_decodes_user_space_program_abi_kind() {
    let bytes =
        ruxe::encode_rux16_executable(&[0x01, 0x00], ruxe::RuxeAbiKind::Program, 0x8000, 0x8000)
            .expect("RUXE encodes");

    assert_eq!(u32_at(&bytes, 24), 3);
    let executable = ruxe::decode_rux16_executable(&bytes).expect("RUXE decodes");

    assert_eq!(executable.abi_kind, ruxe::RuxeAbiKind::Program);
    assert_eq!(executable.entry_pc, 0x8000);
    assert_eq!(executable.load_addr, 0x8000);
}

#[test]
fn ruxe_rejects_unknown_abi_kind_without_user_space_fallback() {
    let mut bytes =
        ruxe::encode_rux16_executable(&[0x01, 0x00], ruxe::RuxeAbiKind::Bootloader, 0x800, 0x800)
            .expect("RUXE encodes");
    bytes[24..28].copy_from_slice(&99u32.to_le_bytes());

    let error = ruxe::decode_rux16_executable(&bytes).unwrap_err();

    assert_eq!(error, "unsupported RUXE ABI kind 99");
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
