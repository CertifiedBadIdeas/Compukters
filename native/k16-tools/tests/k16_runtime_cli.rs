use k16_tools::k16e;
use k16_vm::computer_machine::ComputerMachine;
use k16_vm::k16::K16Signal;
use k16_vm::k16_computer::K16ComputerHandle;
use std::fs;
use std::path::PathBuf;
use std::process::Command;

#[test]
fn k16_runtime_startup_links_returning_main_and_exposes_return_byte() {
    let startup_path = temp_file("startup.o");
    let main_path = temp_file("main.o");
    let output_path = temp_file("program.k16e");
    fs::write(&main_path, k16_main_returning_42_object()).expect("main object writes");

    let runtime_output = Command::new(k16_binary())
        .args([
            "runtime",
            "k16-startup",
            "-o",
            startup_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 runtime runs");
    assert!(
        runtime_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&runtime_output.stderr)
    );

    let link_output = Command::new(k16_binary())
        .args([
            "link",
            "--target",
            "program",
            startup_path.to_str().unwrap(),
            main_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 link runs");
    assert!(
        link_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&link_output.stderr)
    );

    let program = fs::read(output_path).expect("linked program reads");
    let executable = k16e::decode_k16_executable(&program).expect("linked K16E decodes");
    assert_eq!(executable.entry_pc, 0x8000);

    let mut handle = K16ComputerHandle::create_k16_bios_flash(&[0x01, 0x00], 64 * 1024, 1_000_000)
        .expect("K16 computer creates");
    handle
        .exec_k16e_program_from_bytes(&program, 1_000_000)
        .expect("program installs");
    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert_eq!(handle.debug_output_bytes(), &[42]);
    assert_eq!(handle.control().status, ComputerMachine::STATUS_HALTED);
}

#[test]
fn k16_run_executes_program_k16e_and_prints_debug_output_hex() {
    let startup_path = temp_file("run-startup.o");
    let main_path = temp_file("run-main.o");
    let output_path = temp_file("run-program.k16e");
    fs::write(&main_path, k16_main_returning_42_object()).expect("main object writes");

    let runtime_output = Command::new(k16_binary())
        .args([
            "runtime",
            "k16-startup",
            "-o",
            startup_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 runtime runs");
    assert!(
        runtime_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&runtime_output.stderr)
    );

    let link_output = Command::new(k16_binary())
        .args([
            "link",
            "--target",
            "program",
            startup_path.to_str().unwrap(),
            main_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 link runs");
    assert!(
        link_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&link_output.stderr)
    );

    let run_output = Command::new(k16_binary())
        .args(["run", output_path.to_str().unwrap()])
        .output()
        .expect("k16 run runs");

    assert!(
        run_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&run_output.stderr)
    );
    assert_eq!(
        String::from_utf8_lossy(&run_output.stdout),
        "signal=halt debug_bytes=2a\n"
    );
}

#[test]
fn k16_runtime_startup_does_not_hide_missing_helper_symbols() {
    let startup_path = temp_file("startup-helper-missing.o");
    let main_path = temp_file("main-needs-helper.o");
    let output_path = temp_file("missing-helper.k16e");
    fs::write(
        &main_path,
        k16_main_calling_undefined_helper("__k16_memcpy"),
    )
    .expect("main object writes");

    let runtime_output = Command::new(k16_binary())
        .args([
            "runtime",
            "k16-startup",
            "-o",
            startup_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 runtime runs");
    assert!(
        runtime_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&runtime_output.stderr)
    );

    let link_output = Command::new(k16_binary())
        .args([
            "link",
            "--target",
            "program",
            startup_path.to_str().unwrap(),
            main_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("k16 link runs");

    assert!(!link_output.status.success());
    let stderr = String::from_utf8_lossy(&link_output.stderr);
    assert!(
        stderr.contains("unresolved K16 symbol `__k16_memcpy`"),
        "stderr: {stderr}"
    );
    assert!(!output_path.exists());
}

#[test]
fn k16_runtime_memory_helpers_require_custom_k16_rustc() {
    let helper_path = temp_file("memory-helpers.o");

    let helper_output = Command::new(k16_binary())
        .args([
            "runtime",
            "k16-memory-helpers",
            "-o",
            helper_path.to_str().unwrap(),
        ])
        .env_remove("K16_RUSTC")
        .env_remove("K16_RUST_TARGET_JSON")
        .output()
        .expect("k16 runtime helpers runs");

    assert!(!helper_output.status.success());
    let stderr = String::from_utf8_lossy(&helper_output.stderr);
    assert!(
        stderr.contains("K16_RUSTC must point to a custom K16 rustc"),
        "stderr: {stderr}"
    );
    assert!(!helper_path.exists());
}

fn k16_main_returning_42_object() -> Vec<u8> {
    k16_object("main", &[0x01, 0xe0, 42, 0, 0, 0, 0x00, 0x90], None)
}

fn k16_main_calling_undefined_helper(helper: &str) -> Vec<u8> {
    k16_object(
        "main",
        &[0x01, 0xee, 0, 0, 0, 0, 0x00, 0x8e, 0x00, 0x90],
        Some((2, 2, helper)),
    )
}

fn k16_object(defined_symbol: &str, text: &[u8], relocation: Option<(u32, u32, &str)>) -> Vec<u8> {
    let shstrtab = b"\0.text.k16\0.rela.text.k16\0.symtab\0.strtab\0.shstrtab\0";
    let mut strtab = Vec::from([0]);
    let defined_name_offset = strtab.len() as u32;
    strtab.extend_from_slice(defined_symbol.as_bytes());
    strtab.push(0);
    let undefined_name_offset = if let Some((_, _, name)) = relocation {
        let offset = strtab.len() as u32;
        strtab.extend_from_slice(name.as_bytes());
        strtab.push(0);
        Some(offset)
    } else {
        None
    };

    let mut symtab = Vec::new();
    symtab.extend([0u8; 16]);
    write_symbol(
        &mut symtab,
        defined_name_offset,
        0,
        text.len() as u32,
        0x12,
        1,
    );
    if let Some(name_offset) = undefined_name_offset {
        write_symbol(&mut symtab, name_offset, 0, 0, 0x12, 0);
    }

    let mut rela = Vec::new();
    if let Some((offset, relocation_type, _)) = relocation {
        write_u32(&mut rela, offset);
        write_u32(&mut rela, (2 << 8) | relocation_type);
        write_u32(&mut rela, 0);
    }

    elf_object(text, &rela, &symtab, &strtab, shstrtab)
}

fn elf_object(text: &[u8], rela: &[u8], symtab: &[u8], strtab: &[u8], shstrtab: &[u8]) -> Vec<u8> {
    let text_offset = 52u32;
    let rela_offset = align(text_offset + text.len() as u32, 4);
    let symtab_offset = align(rela_offset + rela.len() as u32, 4);
    let strtab_offset = align(symtab_offset + symtab.len() as u32, 4);
    let shstrtab_offset = align(strtab_offset + strtab.len() as u32, 4);
    let shoff = align(shstrtab_offset + shstrtab.len() as u32, 4);
    let section_count = if rela.is_empty() { 5 } else { 6 };
    let shstrndx = section_count - 1;

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
    write_u16(&mut bytes, section_count as u16);
    write_u16(&mut bytes, shstrndx as u16);

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
        text_offset,
        text.len() as u32,
        0,
        0,
        2,
        0,
    );
    if !rela.is_empty() {
        section(
            &mut bytes,
            13,
            4,
            0,
            rela_offset,
            rela.len() as u32,
            3,
            1,
            4,
            12,
        );
    }
    let symtab_link = if rela.is_empty() { 3 } else { 4 };
    section(
        &mut bytes,
        31,
        2,
        0,
        symtab_offset,
        symtab.len() as u32,
        symtab_link,
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

fn write_symbol(bytes: &mut Vec<u8>, name: u32, value: u32, size: u32, info: u8, section: u16) {
    write_u32(bytes, name);
    write_u32(bytes, value);
    write_u32(bytes, size);
    bytes.push(info);
    bytes.push(0);
    write_u16(bytes, section);
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

fn temp_file(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("k16-runtime-cli-{}-{name}", std::process::id()));
    let _ = fs::remove_file(&path);
    path
}

fn k16_binary() -> String {
    std::env::var("CARGO_BIN_EXE_k16").expect("Cargo exposes k16 binary path")
}

fn write_u16(bytes: &mut Vec<u8>, value: u16) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_u32(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}
