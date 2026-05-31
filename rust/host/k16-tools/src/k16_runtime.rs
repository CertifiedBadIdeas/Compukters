use crate::artifact::K16ArtifactTarget;

const EM_K16: u16 = 0x5258;

const SHT_PROGBITS: u32 = 1;
const SHT_SYMTAB: u32 = 2;
const SHT_STRTAB: u32 = 3;
const SHT_RELA: u32 = 4;

const SHF_ALLOC: u32 = 0x2;
const SHF_EXECINSTR: u32 = 0x4;

const R_K16_CALL32: u32 = 2;

const SCRATCH_REGISTER: u8 = 14;
const STACK_POINTER_REGISTER: u8 = 15;
const RETURN_REGISTER: u8 = 0;

pub const STARTUP_SYMBOL: &str = "_start";
pub const MAIN_SYMBOL: &str = "main";

pub fn k16_startup_object() -> Vec<u8> {
    let mut text = Vec::new();
    emit_const32(
        &mut text,
        STACK_POINTER_REGISTER,
        K16ArtifactTarget::PROGRAM_STACK_TOP,
    );
    emit_const32(&mut text, SCRATCH_REGISTER, 0);
    emit_word(&mut text, call(SCRATCH_REGISTER));
    emit_const32(
        &mut text,
        SCRATCH_REGISTER,
        k16_vm::computer_abi::DEBUG_WRITE,
    );
    emit_word(&mut text, store8(SCRATCH_REGISTER, RETURN_REGISTER));
    emit_word(&mut text, halt());

    let mut strtab = Vec::from([0]);
    let start_name = push_string(&mut strtab, STARTUP_SYMBOL);
    let main_name = push_string(&mut strtab, MAIN_SYMBOL);

    let mut symtab = Vec::new();
    symtab.extend([0u8; 16]);
    write_symbol(&mut symtab, start_name, 0, text.len() as u32, 0x12, 1);
    write_symbol(&mut symtab, main_name, 0, 0, 0x12, 0);

    let mut rela = Vec::new();
    write_u32(&mut rela, 8);
    write_u32(&mut rela, (2 << 8) | R_K16_CALL32);
    write_u32(&mut rela, 0);

    elf_object(&text, &rela, &symtab, &strtab)
}

fn elf_object(text: &[u8], rela: &[u8], symtab: &[u8], strtab: &[u8]) -> Vec<u8> {
    let shstrtab = b"\0.text.k16\0.rela.text.k16\0.symtab\0.strtab\0.shstrtab\0";
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
    write_u16(&mut bytes, EM_K16);
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
        SHT_PROGBITS,
        SHF_ALLOC | SHF_EXECINSTR,
        text_offset,
        text.len() as u32,
        0,
        0,
        2,
        0,
    );
    section(
        &mut bytes,
        13,
        SHT_RELA,
        0,
        rela_offset,
        rela.len() as u32,
        3,
        1,
        4,
        12,
    );
    section(
        &mut bytes,
        31,
        SHT_SYMTAB,
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
        SHT_STRTAB,
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
        SHT_STRTAB,
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

fn emit_const32(bytes: &mut Vec<u8>, register: u8, value: u32) {
    emit_word(bytes, 0xe001 | (u16::from(register) << 8));
    emit_word(bytes, (value & 0xffff) as u16);
    emit_word(bytes, (value >> 16) as u16);
}

fn call(register: u8) -> u16 {
    0x8000 | (u16::from(register) << 8)
}

fn store8(addr: u8, src: u8) -> u16 {
    0x5000 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn halt() -> u16 {
    0x0001
}

fn emit_word(bytes: &mut Vec<u8>, word: u16) {
    bytes.extend_from_slice(&word.to_le_bytes());
}

fn push_string(bytes: &mut Vec<u8>, value: &str) -> u32 {
    let offset = bytes.len() as u32;
    bytes.extend_from_slice(value.as_bytes());
    bytes.push(0);
    offset
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

fn write_u16(bytes: &mut Vec<u8>, value: u16) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_u32(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}
