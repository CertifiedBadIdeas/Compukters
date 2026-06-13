use k16_tools::artifact::K16ArtifactTarget;
use k16_tools::k16e;

#[test]
fn k16e_program_slot_targets_keep_init_and_child_in_separate_ranges() {
    assert_eq!(
        K16ArtifactTarget::parse("program-init"),
        Ok(K16ArtifactTarget::ProgramInit)
    );
    assert_eq!(
        K16ArtifactTarget::parse("program-child"),
        Ok(K16ArtifactTarget::ProgramChild)
    );

    assert_eq!(K16ArtifactTarget::ProgramInit.base_address(), 0x8000);
    assert_eq!(
        K16ArtifactTarget::ProgramInit.payload_end_limit(),
        Some(0xc000)
    );
    assert_eq!(K16ArtifactTarget::ProgramInit.stack_top(), Some(0xc000));
    assert_eq!(
        K16ArtifactTarget::ProgramInit.fixed_image_abi_kind(),
        Some(k16e::K16eAbiKind::Program)
    );

    assert_eq!(K16ArtifactTarget::ProgramChild.base_address(), 0xc000);
    assert_eq!(
        K16ArtifactTarget::ProgramChild.payload_end_limit(),
        Some(0x1_0000)
    );
    assert_eq!(K16ArtifactTarget::ProgramChild.stack_top(), Some(0x1_0000));
    assert_eq!(
        K16ArtifactTarget::ProgramChild.fixed_image_abi_kind(),
        Some(k16e::K16eAbiKind::Program)
    );
}

#[test]
fn k16e_standalone_program_target_keeps_existing_full_user_range() {
    assert_eq!(K16ArtifactTarget::Program.base_address(), 0x8000);
    assert_eq!(
        K16ArtifactTarget::Program.payload_end_limit(),
        Some(0x1_0000)
    );
    assert_eq!(K16ArtifactTarget::Program.stack_top(), Some(0x1_0000));
}

#[test]
fn k16e_encodes_single_k16_load_segment() {
    let bytes =
        k16e::encode_k16_executable(&[0x01, 0x00], k16e::K16eAbiKind::Bootloader, 0x800, 0x800)
            .expect("K16E encodes");

    assert_eq!(&bytes[0..4], b"K16E");
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
fn k16e_decodes_single_k16_load_segment() {
    let bytes =
        k16e::encode_k16_executable(&[0x01, 0x00], k16e::K16eAbiKind::Kernel, 0x4000, 0x4000)
            .expect("K16E encodes");

    let executable = k16e::decode_k16_executable(&bytes).expect("K16E decodes");

    assert_eq!(executable.abi_kind, k16e::K16eAbiKind::Kernel);
    assert_eq!(executable.entry_pc, 0x4000);
    assert_eq!(executable.load_addr, 0x4000);
    assert_eq!(executable.memory_size, 2);
    assert_eq!(executable.payload, vec![0x01, 0x00]);
}

#[test]
fn k16e_encodes_and_decodes_zero_fill_tail() {
    let bytes = k16e::encode_k16_executable_with_memory_size(
        &[0x01, 0x00],
        8,
        k16e::K16eAbiKind::Program,
        0x8000,
        0x8000,
    )
    .expect("K16E encodes zero-fill tail");

    assert_eq!(u32_at(&bytes, 44), 2);
    assert_eq!(u32_at(&bytes, 48), 8);
    assert_eq!(&bytes[52..], &[0x01, 0x00]);

    let executable = k16e::decode_k16_executable(&bytes).expect("K16E decodes zero-fill tail");

    assert_eq!(executable.abi_kind, k16e::K16eAbiKind::Program);
    assert_eq!(executable.entry_pc, 0x8000);
    assert_eq!(executable.load_addr, 0x8000);
    assert_eq!(executable.memory_size, 8);
    assert_eq!(executable.payload, vec![0x01, 0x00]);
}

#[test]
fn k16e_decodes_user_space_program_abi_kind() {
    let bytes =
        k16e::encode_k16_executable(&[0x01, 0x00], k16e::K16eAbiKind::Program, 0x8000, 0x8000)
            .expect("K16E encodes");

    assert_eq!(u32_at(&bytes, 24), 3);
    let executable = k16e::decode_k16_executable(&bytes).expect("K16E decodes");

    assert_eq!(executable.abi_kind, k16e::K16eAbiKind::Program);
    assert_eq!(executable.entry_pc, 0x8000);
    assert_eq!(executable.load_addr, 0x8000);
}

#[test]
fn k16e_rejects_unknown_abi_kind_without_user_space_fallback() {
    let mut bytes =
        k16e::encode_k16_executable(&[0x01, 0x00], k16e::K16eAbiKind::Bootloader, 0x800, 0x800)
            .expect("K16E encodes");
    bytes[24..28].copy_from_slice(&99u32.to_le_bytes());

    let error = k16e::decode_k16_executable(&bytes).unwrap_err();

    assert_eq!(error, "unsupported K16E ABI kind 99");
}

#[test]
fn k16e_rejects_malformed_magic_without_raw_fallback() {
    let error = k16e::decode_k16_executable(&[0x01, 0x00]).unwrap_err();

    assert_eq!(error, "invalid K16E magic");
}

fn u16_at(bytes: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes(bytes[offset..offset + 2].try_into().unwrap())
}

fn u32_at(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
}
