use k16_tools::artifact::K16ArtifactTarget;
use k16_tools::k16e;

#[test]
fn k16e_program_dynamic_target_has_no_fixed_physical_base() {
    assert_eq!(
        K16ArtifactTarget::parse("program-dynamic"),
        Ok(K16ArtifactTarget::ProgramDynamic)
    );
    assert_eq!(K16ArtifactTarget::ProgramDynamic.base_address(), 0);
    assert_eq!(K16ArtifactTarget::ProgramDynamic.payload_end_limit(), None);
    assert_eq!(
        K16ArtifactTarget::ProgramDynamic.fixed_image_abi_kind(),
        None
    );
}

#[test]
fn k16e_boot_and_kernel_targets_do_not_cap_payloads_at_neighboring_windows() {
    assert_eq!(K16ArtifactTarget::Boot.payload_end_limit(), None);
    assert_eq!(K16ArtifactTarget::Kernel.payload_end_limit(), None);
    assert_eq!(
        K16ArtifactTarget::Program.payload_end_limit(),
        Some(K16ArtifactTarget::PROGRAM_STACK_TOP)
    );
    assert_eq!(K16ArtifactTarget::ProgramDynamic.payload_end_limit(), None);
}

#[test]
fn k16e_dynamic_program_encodes_relocation_records_without_fixed_load_base() {
    let bytes = k16e::encode_dynamic_k16_program(
        &[0x01, 0xe1, 0x00, 0x00, 0x00, 0x00, 0x00, 0x90],
        12,
        0,
        &[k16e::K16eRelocation {
            offset: 2,
            kind: k16e::K16eRelocationKind::Abs32,
        }],
    )
    .expect("dynamic K16E encodes");

    assert_eq!(&bytes[0..4], b"K16E");
    assert_eq!(u16_at(&bytes, 4), 2);
    assert_eq!(u32_at(&bytes, 12), 0);
    assert_eq!(u32_at(&bytes, 36), 0);

    let executable = k16e::decode_dynamic_k16_program(&bytes).expect("dynamic K16E decodes");

    assert_eq!(executable.entry_offset, 0);
    assert_eq!(executable.memory_size, 12);
    assert_eq!(
        executable.payload,
        vec![0x01, 0xe1, 0x00, 0x00, 0x00, 0x00, 0x00, 0x90]
    );
    assert_eq!(
        executable.relocations,
        vec![k16e::K16eRelocation {
            offset: 2,
            kind: k16e::K16eRelocationKind::Abs32,
        }]
    );
}

#[test]
fn k16e_dynamic_program_v3_encodes_shared_cpu_helper_metadata() {
    let bytes = k16e::encode_dynamic_k16_program_with_cpu_helpers(
        &[0x01, 0xe1, 0x00, 0x00, 0x00, 0x00, 0x00, 0x90],
        12,
        0,
        &[],
        k16e::K16eCpuHelperRuntimeRequirement {
            abi_version: 1,
            helper_table_version: 1,
        },
        &[k16e::K16eCpuHelperRelocation {
            offset: 2,
            kind: k16e::K16eCpuHelperRelocationKind::Call32,
            helper: k16e::K16eCpuHelper::Syscall0,
        }],
    )
    .expect("dynamic shared-helper K16E encodes");

    assert_eq!(&bytes[0..4], b"K16E");
    assert_eq!(u16_at(&bytes, 4), 3);
    assert_eq!(u32_at(&bytes, 20), 4);

    let executable =
        k16e::decode_dynamic_k16_program(&bytes).expect("dynamic shared-helper K16E decodes");

    assert_eq!(executable.entry_offset, 0);
    assert_eq!(executable.memory_size, 12);
    assert_eq!(
        executable.cpu_helper_runtime,
        Some(k16e::K16eCpuHelperRuntimeRequirement {
            abi_version: 1,
            helper_table_version: 1,
        })
    );
    assert_eq!(
        executable.cpu_helper_relocations,
        vec![k16e::K16eCpuHelperRelocation {
            offset: 2,
            kind: k16e::K16eCpuHelperRelocationKind::Call32,
            helper: k16e::K16eCpuHelper::Syscall0,
        }]
    );
}

#[test]
fn k16e_shared_object_encodes_exports_without_entrypoint() {
    let bytes = k16e::encode_k16_shared_object(
        &[0x01, 0xe1, 0x00, 0x00],
        8,
        &[k16e::K16eRelocation {
            offset: 2,
            kind: k16e::K16eRelocationKind::Abs32,
        }],
        &[k16e::K16eSharedExport {
            name: "k16rt.syscall0".to_string(),
            offset: 2,
        }],
    )
    .expect("shared object K16E encodes");

    assert_eq!(&bytes[0..4], b"K16E");
    assert_eq!(u16_at(&bytes, 4), 4);
    assert_eq!(u32_at(&bytes, 12), 0);
    assert_eq!(u32_at(&bytes, 20), 3);
    assert_eq!(u32_at(&bytes, 24), 4);

    let shared = k16e::decode_k16_shared_object(&bytes).expect("shared object K16E decodes");

    assert_eq!(shared.payload, vec![0x01, 0xe1, 0x00, 0x00]);
    assert_eq!(shared.memory_size, 8);
    assert_eq!(
        shared.relocations,
        vec![k16e::K16eRelocation {
            offset: 2,
            kind: k16e::K16eRelocationKind::Abs32,
        }]
    );
    assert_eq!(
        shared.exports,
        vec![k16e::K16eSharedExport {
            name: "k16rt.syscall0".to_string(),
            offset: 2,
        }]
    );
}

#[test]
fn k16e_dynamic_program_encodes_shared_library_imports() {
    let bytes = k16e::encode_dynamic_k16_program_with_imports(
        &[0x01, 0xe1, 0x00, 0x00],
        8,
        0,
        &[],
        &["libk16rt.so".to_string()],
        &[k16e::K16eImportRelocation {
            offset: 2,
            kind: k16e::K16eRelocationKind::Call32,
            library_index: 0,
            symbol: "k16rt.syscall0".to_string(),
        }],
    )
    .expect("dynamic imported K16E encodes");

    assert_eq!(&bytes[0..4], b"K16E");
    assert_eq!(u16_at(&bytes, 4), 5);
    assert_eq!(u32_at(&bytes, 20), 4);
    assert_eq!(u32_at(&bytes, 24), 3);

    let executable = k16e::decode_dynamic_k16_program(&bytes).expect("dynamic K16E decodes");

    assert_eq!(executable.needed_libraries, vec!["libk16rt.so".to_string()]);
    assert_eq!(
        executable.import_relocations,
        vec![k16e::K16eImportRelocation {
            offset: 2,
            kind: k16e::K16eRelocationKind::Call32,
            library_index: 0,
            symbol: "k16rt.syscall0".to_string(),
        }]
    );
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
fn k16e_rejects_shared_object_abi_kind_in_fixed_image_v1() {
    let mut bytes =
        k16e::encode_k16_executable(&[0x01, 0x00], k16e::K16eAbiKind::Program, 0x8000, 0x8000)
            .expect("K16E encodes");
    bytes[24..28].copy_from_slice(&k16e::K16eAbiKind::SharedObject.code().to_le_bytes());

    let error = k16e::decode_k16_executable(&bytes).unwrap_err();

    assert_eq!(
        error,
        "shared object ABI kind requires K16E shared object version"
    );
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
