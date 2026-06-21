use k16_tools::k16e;
use std::fs;
use std::path::PathBuf;
use std::process::Command;

#[test]
fn k16_ld_links_rustc_style_bios_args_with_archive_input() {
    let object_path = temp_file("main.o");
    let archive_path = temp_file("libcore.rlib");
    let output_path = temp_file("bios.kflash");
    fs::write(&object_path, k16_object_with_text_relocation(1)).expect("object writes");
    fs::write(&archive_path, ar_archive_with_metadata_and_k16_object()).expect("archive writes");

    let output = Command::new(k16_ld_binary())
        .args([
            "-flavor",
            "gnu",
            object_path.to_str().unwrap(),
            "--as-needed",
            "-Bstatic",
            archive_path.to_str().unwrap(),
            "-Bdynamic",
            "--eh-frame-hdr",
            "-z",
            "noexecstack",
            "--gc-sections",
            "-O1",
            "--strip-debug",
            "--k16-target=bios",
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16-ld runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_linker_output_is_executable(&output_path);
    let bytes = fs::read(output_path).expect("BIOS output reads");
    assert_eq!(&bytes[..2], &[0x01, 0xef]);
    assert!(
        bytes.ends_with(&[0x01, 0x00]),
        "BIOS payload should include archive object"
    );
}

#[test]
fn k16_ld_links_program_k16e_from_rustc_style_args() {
    let object_path = temp_file("program.o");
    let output_path = temp_file("program.kx");
    fs::write(&object_path, k16_object_with_text_relocation(1)).expect("object writes");

    let output = Command::new(k16_ld_binary())
        .args([
            "-flavor",
            "gnu",
            object_path.to_str().unwrap(),
            "--k16-target",
            "program",
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16-ld runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_linker_output_is_executable(&output_path);
    let bytes = fs::read(output_path).expect("program output reads");
    let executable = k16e::decode_k16_executable(&bytes).expect("K16E decodes");
    assert_eq!(executable.abi_kind, k16e::K16eAbiKind::Program);
}

#[test]
fn k16_ld_writes_retained_section_map_from_rustc_style_args() {
    let object_path = temp_file("program-map.o");
    let output_path = temp_file("program-map.kx");
    let map_path = temp_file("program-map.map");
    fs::write(&object_path, k16_object_with_text_relocation(1)).expect("object writes");

    let output = Command::new(k16_ld_binary())
        .args([
            "-flavor",
            "gnu",
            object_path.to_str().unwrap(),
            "--k16-target",
            "program",
            "--map",
            map_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16-ld runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_linker_output_is_executable(&output_path);
    let bytes = fs::read(output_path).expect("program output reads");
    let executable = k16e::decode_k16_executable(&bytes).expect("K16E decodes");
    assert_eq!(executable.abi_kind, k16e::K16eAbiKind::Program);

    let map = fs::read_to_string(map_path).expect("link map reads");
    assert_eq!(
        map,
        format!(
            "K16 link map target=program load_addr=0x00015000 payload_bytes=8 memory_bytes=8 retained_sections=1\n\
section offset=0x00000000 class=text file_bytes=8 memory_bytes=8 object={} name=.text.k16\n",
            object_path.display()
        )
    );
}

#[test]
fn k16_ld_selects_first_archive_member_that_resolves_a_symbol() {
    let object_path = temp_file("archive-selection-main.o");
    let first_archive_path = temp_file("libk16_rt.rlib");
    let second_archive_path = temp_file("libcompiler_builtins.rlib");
    let output_path = temp_file("archive-selection.kx");
    fs::write(
        &object_path,
        k16_object_with_undefined_text_relocation("__helper"),
    )
    .expect("object writes");
    fs::write(
        &first_archive_path,
        ar_archive_with_k16_object(
            "first.o",
            &k16_object_with_text_relocation_and_symbol(1, "__helper"),
        ),
    )
    .expect("first archive writes");
    fs::write(
        &second_archive_path,
        ar_archive_with_k16_object(
            "second.o",
            &k16_object_with_text_relocation_and_symbol(1, "__helper"),
        ),
    )
    .expect("second archive writes");

    let output = Command::new(k16_ld_binary())
        .args([
            object_path.to_str().unwrap(),
            first_archive_path.to_str().unwrap(),
            second_archive_path.to_str().unwrap(),
            "--k16-target=program",
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16-ld runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
}

#[test]
fn k16_ld_emits_dynamic_import_metadata_from_rustc_style_args() {
    let object_path = temp_file("import-main.o");
    let archive_path = temp_file("libfoo.rlib");
    let output_path = temp_file("import-main.kx");
    fs::write(
        &object_path,
        k16_object_with_undefined_text_relocation("foo"),
    )
    .expect("object writes");
    fs::write(
        &archive_path,
        ar_archive_with_k16_object(
            "foo.o",
            &k16_object_with_text_relocation_and_symbol(1, "foo"),
        ),
    )
    .expect("archive writes");

    let output = Command::new(k16_ld_binary())
        .args([
            "-flavor",
            "gnu",
            object_path.to_str().unwrap(),
            archive_path.to_str().unwrap(),
            "--k16-target=program-dynamic",
            "--k16-import",
            "libfoo.k16so:foo",
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16-ld runs");

    assert!(
        output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_linker_output_is_executable(&output_path);
    let bytes = fs::read(output_path).expect("program output reads");
    let executable = k16e::decode_dynamic_k16_program(&bytes).expect("K16E decodes");
    assert_eq!(executable.needed_libraries, vec!["libfoo.k16so"]);
    assert_eq!(
        executable.import_relocations,
        vec![k16e::K16eImportRelocation {
            offset: 2,
            kind: k16e::K16eRelocationKind::Abs32,
            library_index: 0,
            symbol: "foo".to_string(),
        }]
    );
}

#[test]
fn k16_ld_rejects_missing_target_without_host_linker_fallback() {
    let object_path = temp_file("missing-target.o");
    let output_path = temp_file("missing-target.kx");
    fs::write(&object_path, k16_object_with_text_relocation(1)).expect("object writes");

    let output = Command::new(k16_ld_binary())
        .args([
            object_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16-ld runs");

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        stderr.contains("k16-ld requires explicit --k16-target"),
        "stderr: {stderr}"
    );
    assert!(!output_path.exists());
}

fn k16_object_with_text_relocation(relocation_type: u32) -> Vec<u8> {
    k16_object_with_text_relocation_and_symbol(relocation_type, "_start")
}

fn k16_object_with_text_relocation_and_symbol(relocation_type: u32, symbol_name: &str) -> Vec<u8> {
    let text = [0x01, 0xe4, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00];
    let shstrtab = b"\0.text.k16\0.rela.text.k16\0.symtab\0.strtab\0.shstrtab\0";
    let mut strtab = Vec::from([0]);
    let start_name = push_string(&mut strtab, symbol_name);
    let mut symtab = Vec::new();
    symtab.extend([0u8; 16]);
    write_symbol(&mut symtab, start_name, 0, text.len() as u32, 0x12, 1);

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
        text_offset,
        text.len() as u32,
        0,
        0,
        2,
        0,
    );
    section(&mut bytes, 13, 4, 0, rela_offset, 12, 3, 1, 4, 12);
    section(
        &mut bytes,
        31,
        2,
        0,
        symtab_offset,
        symtab.len() as u32,
        4,
        1,
        4,
        16,
    );
    section(
        &mut bytes,
        39,
        3,
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
        shstrtab_offset,
        shstrtab.len() as u32,
        0,
        0,
        1,
        0,
    );
    bytes
}

fn k16_object_with_undefined_text_relocation(symbol_name: &str) -> Vec<u8> {
    let text = [0x01, 0xe4, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00];
    let shstrtab = b"\0.text.k16\0.rela.text.k16\0.symtab\0.strtab\0.shstrtab\0";
    let mut strtab = Vec::from([0]);
    let start_name = push_string(&mut strtab, "_start");
    let helper_name = push_string(&mut strtab, symbol_name);
    let mut symtab = Vec::new();
    symtab.extend([0u8; 16]);
    write_symbol(&mut symtab, start_name, 0, text.len() as u32, 0x12, 1);
    write_symbol(&mut symtab, helper_name, 0, 0, 0x10, 0);

    let mut rela = Vec::new();
    write_u32(&mut rela, 2);
    write_u32(&mut rela, (2 << 8) | 1);
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
        text_offset,
        text.len() as u32,
        0,
        0,
        2,
        0,
    );
    section(&mut bytes, 13, 4, 0, rela_offset, 12, 3, 1, 4, 12);
    section(
        &mut bytes,
        31,
        2,
        0,
        symtab_offset,
        symtab.len() as u32,
        4,
        1,
        4,
        16,
    );
    section(
        &mut bytes,
        39,
        3,
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
        shstrtab_offset,
        shstrtab.len() as u32,
        0,
        0,
        1,
        0,
    );
    bytes
}

fn ar_archive_with_metadata_and_k16_object() -> Vec<u8> {
    let mut bytes = b"!<arch>\n".to_vec();
    ar_member(&mut bytes, "lib.rmeta", b"metadata");
    ar_member(
        &mut bytes,
        "member.o",
        &k16_object_with_text_relocation_and_symbol(1, "__archive_helper"),
    );
    bytes
}

fn ar_archive_with_k16_object(name: &str, object: &[u8]) -> Vec<u8> {
    let mut bytes = b"!<arch>\n".to_vec();
    ar_member(&mut bytes, name, object);
    bytes
}

fn ar_member(bytes: &mut Vec<u8>, name: &str, content: &[u8]) {
    let header = format!(
        "{:<16}{:<12}{:<6}{:<6}{:<8}{:<10}`\n",
        format!("{name}/"),
        0,
        0,
        0,
        0o100644,
        content.len()
    );
    bytes.extend_from_slice(header.as_bytes());
    bytes.extend_from_slice(content);
    if content.len() % 2 != 0 {
        bytes.push(b'\n');
    }
}

#[allow(clippy::too_many_arguments)]
fn section(
    bytes: &mut Vec<u8>,
    name: u32,
    kind: u32,
    flags: u32,
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
    write_u32(bytes, 0);
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
    let path = std::env::temp_dir().join(format!(
        "k16-linker-driver-cli-{}-{name}",
        std::process::id()
    ));
    let _ = fs::remove_file(&path);
    path
}

fn k16_ld_binary() -> String {
    std::env::var("CARGO_BIN_EXE_k16-ld").expect("Cargo exposes k16-ld binary path")
}

#[cfg(unix)]
fn assert_linker_output_is_executable(path: &std::path::Path) {
    use std::os::unix::fs::PermissionsExt;

    let mode = fs::metadata(path)
        .expect("output metadata reads")
        .permissions()
        .mode();
    assert_ne!(mode & 0o111, 0, "k16-ld output should be executable");
}

#[cfg(not(unix))]
fn assert_linker_output_is_executable(_path: &std::path::Path) {}

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
