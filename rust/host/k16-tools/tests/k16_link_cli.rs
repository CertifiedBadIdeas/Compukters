use k16_tools::k16e;
use std::fs;
use std::path::PathBuf;
use std::process::Command;

#[test]
fn k16_link_converts_k16_object_with_abs32_relocation_to_program_k16e() {
    let object_path = temp_file("abs32.o");
    let output_path = temp_file("abs32.k16e");
    fs::write(&object_path, k16_object_with_text_relocation(1)).expect("object writes");

    let output = Command::new(k16_binary())
        .args([
            "link",
            "--target",
            "program",
            object_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 link runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let bytes = fs::read(output_path).expect("K16E output reads");
    let executable = k16e::decode_k16_executable(&bytes).expect("linked K16E decodes");
    assert_eq!(executable.abi_kind, k16e::K16eAbiKind::Program);
    assert_eq!(executable.entry_pc, 0x8000);
    assert_eq!(executable.load_addr, 0x8000);
    assert_eq!(&bytes[0..4], b"K16E");
    assert_eq!(u32_at(&bytes, 12), 0x8000);
    assert_eq!(u32_at(&bytes, 24), 3);
    assert_eq!(u32_at(&bytes, 36), 0x8000);
    assert_eq!(
        &bytes[52..],
        &[0x01, 0xe4, 0x00, 0x80, 0x00, 0x00, 0x01, 0x00]
    );
}

#[test]
fn k16_link_converts_k16_object_to_raw_bios_flash() {
    let object_path = temp_file("bios.o");
    let output_path = temp_file("bios.kflash");
    fs::write(&object_path, k16_object_with_text_relocation(1)).expect("object writes");

    let output = Command::new(k16_binary())
        .args([
            "link",
            "--target",
            "bios",
            object_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 link runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let bytes = fs::read(output_path).expect("BIOS flash output reads");
    assert_eq!(
        bytes,
        [0x01, 0xe4, 0x00, 0x00, 0xf0, 0xff, 0x01, 0x00],
        "BIOS flash output should be raw linked K16 bytes, not K16E"
    );
}

#[test]
fn k16_link_ignores_absolute_file_symbols_from_llvm_objects() {
    let object_path = temp_file("llvm-file-symbol.o");
    let output_path = temp_file("llvm-file-symbol.k16e");
    fs::write(&object_path, k16_object_with_absolute_file_symbol()).expect("object writes");

    let output = Command::new(k16_binary())
        .args([
            "link",
            "--target",
            "program",
            object_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 link runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let bytes = fs::read(output_path).expect("K16E output reads");
    let executable = k16e::decode_k16_executable(&bytes).expect("linked K16E decodes");
    assert_eq!(executable.entry_pc, 0x8000);
    assert_eq!(
        &bytes[52..],
        &[0x01, 0xe4, 0x00, 0x80, 0x00, 0x00, 0x01, 0x00]
    );
}

#[test]
fn k16_link_rejects_unsupported_relocation_without_raw_fallback() {
    let object_path = temp_file("bad-reloc.o");
    let output_path = temp_file("bad-reloc.k16e");
    fs::write(&object_path, k16_object_with_text_relocation(99)).expect("object writes");

    let output = Command::new(k16_binary())
        .args([
            "link",
            "--target",
            "program",
            object_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 link runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        stderr.contains("unsupported K16 relocation 99"),
        "stderr: {stderr}"
    );
    assert!(!output_path.exists());
}

#[test]
fn k16_link_rejects_unsupported_alloc_section_without_guessing() {
    let object_path = temp_file("bad-section.o");
    let output_path = temp_file("bad-section.k16e");
    fs::write(&object_path, k16_object_with_unsupported_alloc_section()).expect("object writes");

    let output = Command::new(k16_binary())
        .args([
            "link",
            "--target",
            "program",
            object_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 link runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        stderr.contains("unsupported alloc section `.oops.k16`"),
        "stderr: {stderr}"
    );
    assert!(!output_path.exists());
}

fn k16_object_with_text_relocation(relocation_type: u32) -> Vec<u8> {
    k16_object_with_text_relocation_config(relocation_type, false)
}

fn k16_object_with_absolute_file_symbol() -> Vec<u8> {
    k16_object_with_text_relocation_config(1, true)
}

fn k16_object_with_text_relocation_config(
    relocation_type: u32,
    include_absolute_file_symbol: bool,
) -> Vec<u8> {
    let text = [0x01, 0xe4, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00];
    let shstrtab = b"\0.text.k16\0.rela.text.k16\0.symtab\0.strtab\0.shstrtab\0";
    let mut strtab = Vec::from([0]);
    let mut local_symbol_count = 1u32;
    let file_name = include_absolute_file_symbol.then(|| push_string(&mut strtab, "<stdin>"));
    let start_name = push_string(&mut strtab, "_start");
    let mut symtab = Vec::new();
    symtab.extend([0u8; 16]);
    if let Some(file_name) = file_name {
        write_symbol(&mut symtab, file_name, 0, 0, 0x04, 0xfff1);
        local_symbol_count += 1;
    }
    let start_symbol_index = local_symbol_count;
    write_symbol(&mut symtab, start_name, 0, text.len() as u32, 0x12, 1);

    let mut rela = Vec::new();
    write_u32(&mut rela, 2);
    write_u32(&mut rela, (start_symbol_index << 8) | relocation_type);
    write_u32(&mut rela, 0);

    let text_offset = 52u32;
    let rela_offset = align(text_offset + text.len() as u32, 4);
    let symtab_offset = align(rela_offset + rela.len() as u32, 4);
    let strtab_offset = align(symtab_offset + symtab.len() as u32, 4);
    let shstrtab_offset = align(strtab_offset + strtab.len() as u32, 4);
    let shoff = align(shstrtab_offset + shstrtab.len() as u32, 4);

    let mut bytes = Vec::new();
    bytes.extend([0x7f, b'E', b'L', b'F', 1, 1, 1, 0]);
    bytes.extend([0u8; 8]);
    write_u16(&mut bytes, 1);
    write_u16(&mut bytes, 0x5258);
    write_u32(&mut bytes, 1);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, shoff);
    write_u32(&mut bytes, 0);
    write_u16(&mut bytes, 52);
    write_u16(&mut bytes, 0);
    write_u16(&mut bytes, 0);
    write_u16(&mut bytes, 40);
    write_u16(&mut bytes, 6);
    write_u16(&mut bytes, 5);

    pad_to(&mut bytes, text_offset);
    bytes.extend(text);
    pad_to(&mut bytes, rela_offset);
    bytes.extend(rela);
    pad_to(&mut bytes, symtab_offset);
    bytes.extend_from_slice(&symtab);
    pad_to(&mut bytes, strtab_offset);
    bytes.extend_from_slice(&strtab);
    pad_to(&mut bytes, shstrtab_offset);
    bytes.extend(shstrtab);
    pad_to(&mut bytes, shoff);

    bytes.extend([0u8; 40]);
    section(
        &mut bytes,
        1,
        1,
        0x6,
        0,
        text_offset,
        text.len() as u32,
        0,
        0,
        2,
        0,
    );
    section(&mut bytes, 13, 4, 0, 0, rela_offset, 12, 3, 1, 4, 12);
    section(
        &mut bytes,
        31,
        2,
        0,
        0,
        symtab_offset,
        symtab.len() as u32,
        4,
        local_symbol_count,
        4,
        16,
    );
    section(
        &mut bytes,
        39,
        3,
        0,
        0,
        strtab_offset,
        strtab.len() as u32,
        0,
        0,
        1,
        0,
    );
    section(
        &mut bytes,
        47,
        3,
        0,
        0,
        shstrtab_offset,
        shstrtab.len() as u32,
        0,
        0,
        1,
        0,
    );

    bytes
}

fn k16_object_with_unsupported_alloc_section() -> Vec<u8> {
    let mut bytes = k16_object_with_text_relocation(1);
    let position = bytes
        .windows(b".text.k16".len())
        .position(|window| window == b".text.k16")
        .expect("section name exists");
    bytes[position..position + b".oops.k16".len()].copy_from_slice(b".oops.k16");
    bytes
}

#[allow(clippy::too_many_arguments)]
fn section(
    bytes: &mut Vec<u8>,
    name: u32,
    kind: u32,
    flags: u32,
    addr: u32,
    offset: u32,
    size: u32,
    link: u32,
    info: u32,
    addralign: u32,
    entsize: u32,
) {
    write_u32(bytes, name);
    write_u32(bytes, kind);
    write_u32(bytes, flags);
    write_u32(bytes, addr);
    write_u32(bytes, offset);
    write_u32(bytes, size);
    write_u32(bytes, link);
    write_u32(bytes, info);
    write_u32(bytes, addralign);
    write_u32(bytes, entsize);
}

fn align(value: u32, alignment: u32) -> u32 {
    value.div_ceil(alignment) * alignment
}

fn pad_to(bytes: &mut Vec<u8>, offset: u32) {
    bytes.resize(offset as usize, 0);
}

fn push_string(bytes: &mut Vec<u8>, value: &str) -> u32 {
    let offset = bytes.len() as u32;
    bytes.extend_from_slice(value.as_bytes());
    bytes.push(0);
    offset
}

fn temp_file(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("k16-link-cli-{}-{name}", std::process::id()));
    let _ = fs::remove_file(&path);
    path
}

fn k16_binary() -> String {
    std::env::var("CARGO_BIN_EXE_k16").expect("Cargo exposes k16 binary path")
}

fn u32_at(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
}

fn write_u16(bytes: &mut Vec<u8>, value: u16) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_u32(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_symbol(bytes: &mut Vec<u8>, name: u32, value: u32, size: u32, info: u8, section: u16) {
    write_u32(bytes, name);
    write_u32(bytes, value);
    write_u32(bytes, size);
    bytes.push(info);
    bytes.push(0);
    write_u16(bytes, section);
}
