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
const ARG0_REGISTER: u8 = 1;

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
    emit_const32(&mut text, ARG0_REGISTER, k16_abi::syscall::EXIT);
    emit_const32(&mut text, SCRATCH_REGISTER, 0);
    let copy_return_to_syscall_arg0 = add(2, RETURN_REGISTER, SCRATCH_REGISTER);
    emit_word(&mut text, copy_return_to_syscall_arg0[0]);
    emit_word(&mut text, copy_return_to_syscall_arg0[1]);
    emit_word(&mut text, syscall(ARG0_REGISTER));
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

pub fn k16_cpu_helpers_object() -> Vec<u8> {
    let mut text = Vec::new();
    let mut strtab = Vec::from([0]);
    let mut symtab = Vec::new();
    symtab.extend([0u8; 16]);

    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_halt_once",
        &[halt()],
    );
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_wait_once",
        &[wait(), ret()],
    );
    let yield_words = [
        emit_const32_word(RETURN_REGISTER),
        k16_vm::computer_abi::CONTROL_YIELD as u16,
        (k16_vm::computer_abi::CONTROL_YIELD >> 16) as u16,
        const4(ARG0_REGISTER, 1),
        store32(RETURN_REGISTER, ARG0_REGISTER),
        ret(),
    ];
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_yield_once",
        &yield_words,
    );
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_iret_once",
        &[iret()],
    );
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_write_trap_vector",
        &[
            write_csr(k16_vm::k16::K16_CSR_TRAP_VECTOR, ARG0_REGISTER),
            ret(),
        ],
    );
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_read_trap_cause",
        &[
            read_csr(RETURN_REGISTER, k16_vm::k16::K16_CSR_TRAP_CAUSE),
            ret(),
        ],
    );
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_read_trap_pc",
        &[
            read_csr(RETURN_REGISTER, k16_vm::k16::K16_CSR_TRAP_PC),
            ret(),
        ],
    );
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_read_trap_value",
        &[
            read_csr(RETURN_REGISTER, k16_vm::k16::K16_CSR_TRAP_VALUE),
            ret(),
        ],
    );
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_read_trap_arg0",
        &[
            read_csr(RETURN_REGISTER, k16_vm::k16::K16_CSR_TRAP_ARG0),
            ret(),
        ],
    );
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_read_trap_arg1",
        &[
            read_csr(RETURN_REGISTER, k16_vm::k16::K16_CSR_TRAP_ARG1),
            ret(),
        ],
    );
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_read_trap_arg2",
        &[
            read_csr(RETURN_REGISTER, k16_vm::k16::K16_CSR_TRAP_ARG2),
            ret(),
        ],
    );
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_syscall_once",
        &[syscall(ARG0_REGISTER), ret()],
    );
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_syscall0",
        &[syscall(ARG0_REGISTER), ret()],
    );
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_syscall1",
        &[syscall(ARG0_REGISTER), ret()],
    );
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_syscall3",
        &[syscall(ARG0_REGISTER), ret()],
    );
    let copy_arg0_to_return = add(RETURN_REGISTER, ARG0_REGISTER, SCRATCH_REGISTER);
    let iret_with_r0_words = [
        const4(SCRATCH_REGISTER, 0),
        copy_arg0_to_return[0],
        copy_arg0_to_return[1],
        iret(),
    ];
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_iret_with_r0",
        &iret_with_r0_words,
    );
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_write_interrupt_enable",
        &[
            write_csr(k16_vm::k16::K16_CSR_INTERRUPT_ENABLE, ARG0_REGISTER),
            ret(),
        ],
    );
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_write_interrupt_mask",
        &[
            write_csr(k16_vm::k16::K16_CSR_INTERRUPT_MASK, ARG0_REGISTER),
            ret(),
        ],
    );
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_read_interrupt_pending",
        &[
            read_csr(RETURN_REGISTER, k16_vm::k16::K16_CSR_INTERRUPT_PENDING),
            ret(),
        ],
    );

    elf_object(&text, &[], &symtab, &strtab)
}

fn emit_symbol_function(
    text: &mut Vec<u8>,
    strtab: &mut Vec<u8>,
    symtab: &mut Vec<u8>,
    name: &str,
    words: &[u16],
) {
    let name_offset = push_string(strtab, name);
    let value = text.len() as u32;
    for word in words {
        emit_word(text, *word);
    }
    write_symbol(
        symtab,
        name_offset,
        value,
        text.len() as u32 - value,
        0x12,
        1,
    );
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
    emit_word(bytes, emit_const32_word(register));
    emit_word(bytes, (value & 0xffff) as u16);
    emit_word(bytes, (value >> 16) as u16);
}

fn emit_const32_word(register: u8) -> u16 {
    0xe001 | (u16::from(register) << 8)
}

fn const4(dst: u8, value: u8) -> u16 {
    0x1000 | (u16::from(dst) << 8) | u16::from(value & 0x0f)
}

fn add(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x0, lhs, rhs)
}

fn alu_rrr(dst: u8, subop: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x2000 | (u16::from(dst) << 8) | u16::from(subop),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

fn call(register: u8) -> u16 {
    0x8000 | (u16::from(register) << 8)
}

fn store32(addr: u8, src: u8) -> u16 {
    0x5002 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn halt() -> u16 {
    0x0001
}

fn wait() -> u16 {
    0x0006
}

fn iret() -> u16 {
    0x0004
}

fn syscall(register: u8) -> u16 {
    0x0005 | (u16::from(register) << 8)
}

fn ret() -> u16 {
    0x9000
}

fn read_csr(dst: u8, csr: u32) -> u16 {
    0x0002 | (u16::from(dst) << 8) | ((csr as u16) << 4)
}

fn write_csr(csr: u32, src: u8) -> u16 {
    0x0003 | ((csr as u16) << 8) | (u16::from(src) << 4)
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
