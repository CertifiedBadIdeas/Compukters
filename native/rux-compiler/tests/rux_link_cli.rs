use rux_compiler::ruxe;
use std::fs;
use std::path::PathBuf;
use std::process::Command;

#[test]
fn rux_link_converts_rux16_object_with_abs32_relocation_to_program_ruxe() {
    let object_path = temp_file("abs32.o");
    let output_path = temp_file("abs32.ruxe");
    fs::write(&object_path, rux16_object_with_text_relocation(1)).expect("object writes");

    let output = Command::new(rux_binary())
        .args([
            "link",
            "--target",
            "program",
            object_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux link runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let bytes = fs::read(output_path).expect("RUXE output reads");
    let executable = ruxe::decode_rux16_executable(&bytes).expect("linked RUXE decodes");
    assert_eq!(executable.abi_kind, ruxe::RuxeAbiKind::Program);
    assert_eq!(executable.entry_pc, 0x8000);
    assert_eq!(executable.load_addr, 0x8000);
    assert_eq!(&bytes[0..4], b"RUXE");
    assert_eq!(u32_at(&bytes, 12), 0x8000);
    assert_eq!(u32_at(&bytes, 24), 3);
    assert_eq!(u32_at(&bytes, 36), 0x8000);
    assert_eq!(
        &bytes[52..],
        &[0x01, 0xe4, 0x00, 0x80, 0x00, 0x00, 0x01, 0x00]
    );
}

#[test]
fn rux_link_rejects_unsupported_relocation_without_raw_fallback() {
    let object_path = temp_file("bad-reloc.o");
    let output_path = temp_file("bad-reloc.ruxe");
    fs::write(&object_path, rux16_object_with_text_relocation(99)).expect("object writes");

    let output = Command::new(rux_binary())
        .args([
            "link",
            "--target",
            "program",
            object_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux link runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        stderr.contains("unsupported Rux16 relocation 99"),
        "stderr: {stderr}"
    );
    assert!(!output_path.exists());
}

#[test]
fn rux_link_rejects_unsupported_alloc_section_without_guessing() {
    let object_path = temp_file("bad-section.o");
    let output_path = temp_file("bad-section.ruxe");
    fs::write(&object_path, rux16_object_with_unsupported_alloc_section()).expect("object writes");

    let output = Command::new(rux_binary())
        .args([
            "link",
            "--target",
            "program",
            object_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux link runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        stderr.contains("unsupported alloc section `.oops.rux16`"),
        "stderr: {stderr}"
    );
    assert!(!output_path.exists());
}

fn rux16_object_with_text_relocation(relocation_type: u32) -> Vec<u8> {
    let text = [0x01, 0xe4, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00];
    let shstrtab = b"\0.text.rux16\0.rela.text.rux16\0.symtab\0.strtab\0.shstrtab\0";
    let strtab = b"\0_start\0";
    let mut symtab = Vec::new();
    symtab.extend([0u8; 16]);
    write_u32(&mut symtab, 1);
    write_u32(&mut symtab, 0);
    write_u32(&mut symtab, text.len() as u32);
    symtab.push(0x12);
    symtab.push(0);
    write_u16(&mut symtab, 1);

    let mut rela = Vec::new();
    write_u32(&mut rela, 2);
    write_u32(&mut rela, (1 << 8) | relocation_type);
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
    bytes.extend(symtab);
    pad_to(&mut bytes, strtab_offset);
    bytes.extend(strtab);
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
    section(&mut bytes, 31, 2, 0, 0, symtab_offset, 32, 4, 1, 4, 16);
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

fn rux16_object_with_unsupported_alloc_section() -> Vec<u8> {
    let mut bytes = rux16_object_with_text_relocation(1);
    let position = bytes
        .windows(b".text.rux16".len())
        .position(|window| window == b".text.rux16")
        .expect("section name exists");
    bytes[position..position + b".oops.rux16".len()].copy_from_slice(b".oops.rux16");
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

fn temp_file(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("rux-link-cli-{}-{name}", std::process::id()));
    let _ = fs::remove_file(&path);
    path
}

fn rux_binary() -> String {
    std::env::var("CARGO_BIN_EXE_rux").expect("Cargo exposes rux binary path")
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
