use rux_compiler::ruxe;
use rux_vm::computer_machine::ComputerMachine;
use rux_vm::rux16::Rux16Signal;
use rux_vm::rux_computer::RuxComputerHandle;
use std::fs;
use std::path::PathBuf;
use std::process::Command;

#[test]
fn rux_runtime_startup_links_returning_main_and_exposes_return_byte() {
    let startup_path = temp_file("startup.o");
    let main_path = temp_file("main.o");
    let output_path = temp_file("program.ruxe");
    fs::write(&main_path, rux16_main_returning_42_object()).expect("main object writes");

    let runtime_output = Command::new(rux_binary())
        .args([
            "runtime",
            "rux16-startup",
            "-o",
            startup_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux runtime runs");
    assert!(
        runtime_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&runtime_output.stderr)
    );

    let link_output = Command::new(rux_binary())
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
        .expect("rux link runs");
    assert!(
        link_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&link_output.stderr)
    );

    let program = fs::read(output_path).expect("linked program reads");
    let executable = ruxe::decode_rux16_executable(&program).expect("linked RUXE decodes");
    assert_eq!(executable.entry_pc, 0x8000);

    let mut handle =
        RuxComputerHandle::create_rux16_bios_flash(&[0x01, 0x00], 64 * 1024, 1_000_000)
            .expect("Rux16 computer creates");
    handle
        .exec_ruxe_program_from_bytes(&program, 1_000_000)
        .expect("program installs");
    assert_eq!(handle.run_rux16_until_signal().unwrap(), Rux16Signal::Halt);
    assert_eq!(handle.debug_output_bytes(), &[42]);
    assert_eq!(handle.control().status, ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux_run_executes_program_ruxe_and_prints_debug_output_hex() {
    let startup_path = temp_file("run-startup.o");
    let main_path = temp_file("run-main.o");
    let output_path = temp_file("run-program.ruxe");
    fs::write(&main_path, rux16_main_returning_42_object()).expect("main object writes");

    let runtime_output = Command::new(rux_binary())
        .args([
            "runtime",
            "rux16-startup",
            "-o",
            startup_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux runtime runs");
    assert!(
        runtime_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&runtime_output.stderr)
    );

    let link_output = Command::new(rux_binary())
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
        .expect("rux link runs");
    assert!(
        link_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&link_output.stderr)
    );

    let run_output = Command::new(rux_binary())
        .args(["run", output_path.to_str().unwrap()])
        .output()
        .expect("rux run runs");

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
fn rux_runtime_startup_does_not_hide_missing_helper_symbols() {
    let startup_path = temp_file("startup-helper-missing.o");
    let main_path = temp_file("main-needs-helper.o");
    let output_path = temp_file("missing-helper.ruxe");
    fs::write(
        &main_path,
        rux16_main_calling_undefined_helper("__rux16_memcpy"),
    )
    .expect("main object writes");

    let runtime_output = Command::new(rux_binary())
        .args([
            "runtime",
            "rux16-startup",
            "-o",
            startup_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux runtime runs");
    assert!(
        runtime_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&runtime_output.stderr)
    );

    let link_output = Command::new(rux_binary())
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
        .expect("rux link runs");

    assert!(!link_output.status.success());
    let stderr = String::from_utf8_lossy(&link_output.stderr);
    assert!(
        stderr.contains("unresolved Rux16 symbol `__rux16_memcpy`"),
        "stderr: {stderr}"
    );
    assert!(!output_path.exists());
}

#[test]
fn rux_runtime_memory_helpers_resolve_memcpy_and_execute_copy() {
    let startup_path = temp_file("startup-helper-backed.o");
    let helper_path = temp_file("memory-helpers.o");
    let main_path = temp_file("main-calls-memcpy.o");
    let output_path = temp_file("helper-backed.ruxe");
    fs::write(
        &main_path,
        rux16_main_copying_three_bytes_with_memcpy_helper(),
    )
    .expect("main object writes");

    let startup_output = Command::new(rux_binary())
        .args([
            "runtime",
            "rux16-startup",
            "-o",
            startup_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux runtime startup runs");
    assert!(
        startup_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&startup_output.stderr)
    );

    let helper_output = Command::new(rux_binary())
        .args([
            "runtime",
            "rux16-memory-helpers",
            "-o",
            helper_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux runtime helpers runs");
    assert!(
        helper_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&helper_output.stderr)
    );

    let link_output = Command::new(rux_binary())
        .args([
            "link",
            "--target",
            "program",
            startup_path.to_str().unwrap(),
            main_path.to_str().unwrap(),
            helper_path.to_str().unwrap(),
            "-o",
            output_path.to_str().unwrap(),
        ])
        .output()
        .expect("rux link runs");
    assert!(
        link_output.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&link_output.stderr)
    );

    let run_output = Command::new(rux_binary())
        .args(["run", output_path.to_str().unwrap()])
        .output()
        .expect("rux run runs");

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

fn rux16_main_returning_42_object() -> Vec<u8> {
    rux16_object("main", &[0x01, 0xe0, 42, 0, 0, 0, 0x00, 0x90], None)
}

fn rux16_main_calling_undefined_helper(helper: &str) -> Vec<u8> {
    rux16_object(
        "main",
        &[0x01, 0xee, 0, 0, 0, 0, 0x00, 0x8e, 0x00, 0x90],
        Some((2, 2, helper)),
    )
}

fn rux16_main_copying_three_bytes_with_memcpy_helper() -> Vec<u8> {
    let mut words = Vec::new();
    words.extend(const32(4, 0x9000));
    words.extend(const32(5, 10));
    words.push(store8(4, 5));
    words.extend(const32(4, 0x9001));
    words.extend(const32(5, 20));
    words.push(store8(4, 5));
    words.extend(const32(4, 0x9002));
    words.extend(const32(5, 12));
    words.push(store8(4, 5));

    words.extend(const32(1, 0x9010));
    words.extend(const32(2, 0x9000));
    words.extend(const32(3, 3));
    let helper_relocation_offset = byte_len(&words) + 2;
    words.extend(const32(14, 0));
    words.push(call(14));

    words.extend(const32(4, 0x9010));
    words.push(load8(4, 4));
    words.extend(const32(5, 0x9011));
    words.push(load8(5, 5));
    words.extend(const32(6, 0x9012));
    words.push(load8(6, 6));
    words.extend(add(0, 4, 5));
    words.extend(add(0, 0, 6));
    words.push(ret());

    rux16_object(
        "main",
        &encode_words(&words),
        Some((helper_relocation_offset, 2, "__rux16_memcpy")),
    )
}

fn rux16_object(
    defined_symbol: &str,
    text: &[u8],
    relocation: Option<(u32, u32, &str)>,
) -> Vec<u8> {
    let shstrtab = b"\0.text.rux16\0.rela.text.rux16\0.symtab\0.strtab\0.shstrtab\0";
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

fn encode_words(words: &[u16]) -> Vec<u8> {
    words
        .iter()
        .flat_map(|word| word.to_le_bytes())
        .collect::<Vec<_>>()
}

fn byte_len(words: &[u16]) -> u32 {
    u32::try_from(words.len() * 2).expect("test object is small")
}

fn const32(register: u8, value: u32) -> [u16; 3] {
    [
        0xe001 | (u16::from(register) << 8),
        (value & 0xffff) as u16,
        (value >> 16) as u16,
    ]
}

fn add(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x2000 | (u16::from(dst) << 8),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

fn load8(dst: u8, addr: u8) -> u16 {
    0x4000 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn store8(addr: u8, src: u8) -> u16 {
    0x5000 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn call(register: u8) -> u16 {
    0x8000 | (u16::from(register) << 8)
}

fn ret() -> u16 {
    0x9000
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
    let path = std::env::temp_dir().join(format!("rux-runtime-cli-{}-{name}", std::process::id()));
    let _ = fs::remove_file(&path);
    path
}

fn rux_binary() -> String {
    std::env::var("CARGO_BIN_EXE_rux").expect("Cargo exposes rux binary path")
}

fn write_u16(bytes: &mut Vec<u8>, value: u16) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_u32(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}
