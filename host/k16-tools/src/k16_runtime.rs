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
const ARG1_REGISTER: u8 = 2;
const ARG2_REGISTER: u8 = 3;
const ALIGNMENT_PAD_REGISTER: u8 = 4;
const SYSCALL_ARG2_REGISTER: u8 = 4;

const TRAP_FRAME_RESUME_PC_OFFSET: u32 = 16 * 4;
const TRAP_FRAME_STACK_POINTER_OFFSET: u32 = TRAP_FRAME_RESUME_PC_OFFSET + 4;
const TRAP_FRAME_INTERRUPT_ENABLE_OFFSET: u32 = TRAP_FRAME_STACK_POINTER_OFFSET + 4;

pub const STARTUP_SYMBOL: &str = "_start";
pub const MAIN_SYMBOL: &str = "main";

pub fn k16_startup_object() -> Vec<u8> {
    k16_startup_object_for_target(K16ArtifactTarget::Program).expect("program startup target")
}

pub fn k16_startup_object_for_target(target: K16ArtifactTarget) -> Result<Vec<u8>, String> {
    let fixed_stack_top = match target {
        K16ArtifactTarget::Program => Some(K16ArtifactTarget::PROGRAM_INITIAL_STACK_POINTER),
        K16ArtifactTarget::ProgramDynamic => None,
        other => {
            return Err(format!(
                "k16-startup target {other:?} is not a user program target"
            ));
        }
    };
    let mut text = Vec::new();
    if let Some(stack_top) = fixed_stack_top {
        emit_const32(&mut text, STACK_POINTER_REGISTER, stack_top);
    }
    let main_relocation_offset =
        text.len()
            .checked_add(14)
            .ok_or_else(|| "K16 startup relocation offset overflows".to_string())? as u32;
    emit_const32(&mut text, ARG2_REGISTER, 0);
    emit_word(&mut text, const4(ALIGNMENT_PAD_REGISTER, 4));
    let reserve_alignment_pad = sub(
        STACK_POINTER_REGISTER,
        STACK_POINTER_REGISTER,
        ALIGNMENT_PAD_REGISTER,
    );
    emit_word(&mut text, reserve_alignment_pad[0]);
    emit_word(&mut text, reserve_alignment_pad[1]);
    emit_const32(&mut text, SCRATCH_REGISTER, 0);
    emit_word(&mut text, call(SCRATCH_REGISTER));
    emit_word(&mut text, const4(ALIGNMENT_PAD_REGISTER, 4));
    let release_alignment_pad = add(
        STACK_POINTER_REGISTER,
        STACK_POINTER_REGISTER,
        ALIGNMENT_PAD_REGISTER,
    );
    emit_word(&mut text, release_alignment_pad[0]);
    emit_word(&mut text, release_alignment_pad[1]);
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
    write_u32(&mut rela, main_relocation_offset);
    write_u32(&mut rela, (2 << 8) | R_K16_CALL32);
    write_u32(&mut rela, 0);

    Ok(elf_object(&text, &rela, &symtab, &strtab))
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
    let save_trap_frame_words = save_trap_frame_words();
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_save_trap_frame",
        &save_trap_frame_words,
    );
    let restore_trap_frame_words = restore_trap_frame_words();
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_restore_trap_frame",
        &restore_trap_frame_words,
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
    let abort_words = [
        emit_const32_word(ARG0_REGISTER),
        k16_abi::syscall::EXIT as u16,
        (k16_abi::syscall::EXIT >> 16) as u16,
        const4(ARG1_REGISTER, 1),
        syscall(ARG0_REGISTER),
        halt(),
    ];
    emit_symbol_function(&mut text, &mut strtab, &mut symtab, "abort", &abort_words);
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
    let stack_arg0_addr = add(SCRATCH_REGISTER, STACK_POINTER_REGISTER, SCRATCH_REGISTER);
    let syscall3_words = [
        const4(SCRATCH_REGISTER, 4),
        stack_arg0_addr[0],
        stack_arg0_addr[1],
        load32(SYSCALL_ARG2_REGISTER, SCRATCH_REGISTER),
        syscall(ARG0_REGISTER),
        ret(),
    ];
    emit_symbol_function(
        &mut text,
        &mut strtab,
        &mut symtab,
        "__k16_syscall3",
        &syscall3_words,
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

pub fn assemble_k16_object_source(source: &str) -> Result<Vec<u8>, String> {
    let mut functions = Vec::<(String, Vec<u16>)>::new();
    let mut current_name: Option<String> = None;
    let mut current_words = Vec::<u16>::new();

    for (line_index, raw_line) in source.lines().enumerate() {
        let line_number = line_index + 1;
        let line = raw_line
            .split_once('#')
            .map(|(before, _)| before)
            .unwrap_or(raw_line)
            .trim();
        if line.is_empty() {
            continue;
        }
        if let Some(name) = line.strip_prefix(".function ") {
            finish_assembled_function(&mut functions, &mut current_name, &mut current_words)?;
            let name = name.trim();
            if name.is_empty() {
                return Err(format!("line {line_number}: missing function name"));
            }
            current_name = Some(name.to_string());
            continue;
        }
        if current_name.is_none() {
            return Err(format!("line {line_number}: instruction outside .function"));
        }
        current_words.extend(
            assemble_instruction(line).map_err(|error| format!("line {line_number}: {error}"))?,
        );
    }
    finish_assembled_function(&mut functions, &mut current_name, &mut current_words)?;
    if functions.is_empty() {
        return Err("K16 asm source does not define any functions".to_string());
    }

    let mut text = Vec::new();
    let mut strtab = Vec::from([0]);
    let mut symtab = Vec::new();
    symtab.extend([0u8; 16]);
    for (name, words) in functions {
        emit_symbol_function(&mut text, &mut strtab, &mut symtab, &name, &words);
    }
    Ok(elf_object(&text, &[], &symtab, &strtab))
}

fn finish_assembled_function(
    functions: &mut Vec<(String, Vec<u16>)>,
    current_name: &mut Option<String>,
    current_words: &mut Vec<u16>,
) -> Result<(), String> {
    let Some(name) = current_name.take() else {
        return Ok(());
    };
    if current_words.is_empty() {
        return Err(format!("function {name} has no instructions"));
    }
    functions.push((name, core::mem::take(current_words)));
    Ok(())
}

fn assemble_instruction(line: &str) -> Result<Vec<u16>, String> {
    let tokens = line
        .replace(',', " ")
        .replace('[', " ")
        .replace(']', " ")
        .split_whitespace()
        .map(ToString::to_string)
        .collect::<Vec<_>>();
    let Some(opcode) = tokens.first().map(String::as_str) else {
        return Ok(Vec::new());
    };
    match opcode {
        "halt" if tokens.len() == 1 => Ok(vec![halt()]),
        "wait" if tokens.len() == 1 => Ok(vec![wait()]),
        "iret" if tokens.len() == 1 => Ok(vec![iret()]),
        "ret" if tokens.len() == 1 => Ok(vec![ret()]),
        "syscall" if tokens.len() == 2 => Ok(vec![syscall(parse_register(&tokens[1])?)]),
        "const4" if tokens.len() == 3 => Ok(vec![const4(
            parse_register(&tokens[1])?,
            parse_u8_immediate(&tokens[2], 0x0f)?,
        )]),
        "const32" if tokens.len() == 3 => Ok(const32_words(
            parse_register(&tokens[1])?,
            parse_u32_immediate(&tokens[2])?,
        )
        .to_vec()),
        "pcadd32" if tokens.len() == 3 => Ok(pcadd32_words(
            parse_register(&tokens[1])?,
            parse_u32_immediate(&tokens[2])?,
        )
        .to_vec()),
        "add" if tokens.len() == 4 => Ok(add(
            parse_register(&tokens[1])?,
            parse_register(&tokens[2])?,
            parse_register(&tokens[3])?,
        )
        .to_vec()),
        "sub" if tokens.len() == 4 => Ok(sub(
            parse_register(&tokens[1])?,
            parse_register(&tokens[2])?,
            parse_register(&tokens[3])?,
        )
        .to_vec()),
        "addi" if tokens.len() == 4 => Ok(addi(
            parse_register(&tokens[1])?,
            parse_register(&tokens[2])?,
            parse_i16_immediate(&tokens[3])?,
        )
        .to_vec()),
        "load8" => assemble_load_or_load_offset(&tokens, 0x0, load8, load8_offset),
        "load16" => assemble_load_or_load_offset(&tokens, 0x1, load16, load16_offset),
        "load32" => assemble_load_or_load_offset(&tokens, 0x2, load32, load32_offset),
        "store8" => assemble_store_or_store_offset(&tokens, 0x0, store8, store8_offset),
        "store16" => assemble_store_or_store_offset(&tokens, 0x1, store16, store16_offset),
        "store32" => assemble_store_or_store_offset(&tokens, 0x2, store32, store32_offset),
        "read_csr" if tokens.len() == 3 => Ok(vec![read_csr(
            parse_register(&tokens[1])?,
            parse_u32_immediate(&tokens[2])?,
        )]),
        "write_csr" if tokens.len() == 3 => Ok(vec![write_csr(
            parse_u32_immediate(&tokens[1])?,
            parse_register(&tokens[2])?,
        )]),
        _ => Err(format!("unsupported K16 asm instruction `{line}`")),
    }
}

fn assemble_load_or_load_offset(
    tokens: &[String],
    _width: u8,
    direct: fn(u8, u8) -> u16,
    offset: fn(u8, u8, i16) -> [u16; 2],
) -> Result<Vec<u16>, String> {
    match tokens.len() {
        3 => Ok(vec![direct(
            parse_register(&tokens[1])?,
            parse_register(&tokens[2])?,
        )]),
        5 => Ok(offset(
            parse_register(&tokens[1])?,
            parse_register(&tokens[2])?,
            parse_signed_offset(&tokens[3], &tokens[4])?,
        )
        .to_vec()),
        _ => Err(format!(
            "unsupported K16 asm instruction `{}`",
            tokens.join(" ")
        )),
    }
}

fn assemble_store_or_store_offset(
    tokens: &[String],
    _width: u8,
    direct: fn(u8, u8) -> u16,
    offset: fn(u8, u8, i16) -> [u16; 2],
) -> Result<Vec<u16>, String> {
    match tokens.len() {
        3 => Ok(vec![direct(
            parse_register(&tokens[1])?,
            parse_register(&tokens[2])?,
        )]),
        5 => Ok(offset(
            parse_register(&tokens[1])?,
            parse_register(&tokens[4])?,
            parse_signed_offset(&tokens[2], &tokens[3])?,
        )
        .to_vec()),
        _ => Err(format!(
            "unsupported K16 asm instruction `{}`",
            tokens.join(" ")
        )),
    }
}

fn parse_register(value: &str) -> Result<u8, String> {
    let register = value
        .strip_prefix('r')
        .ok_or_else(|| format!("expected register, got `{value}`"))?
        .parse::<u8>()
        .map_err(|error| format!("invalid register `{value}`: {error}"))?;
    if register > 15 {
        return Err(format!("register `{value}` is outside r0..r15"));
    }
    Ok(register)
}

fn parse_u8_immediate(value: &str, max: u8) -> Result<u8, String> {
    let immediate = parse_u32_immediate(value)?;
    if immediate > u32::from(max) {
        return Err(format!("immediate `{value}` is larger than {max}"));
    }
    Ok(immediate as u8)
}

fn parse_signed_offset(sign: &str, value: &str) -> Result<i16, String> {
    let magnitude = parse_i16_immediate(value)?;
    match sign {
        "+" => Ok(magnitude),
        "-" => magnitude
            .checked_neg()
            .ok_or_else(|| format!("immediate `-{value}` is smaller than {}", i16::MIN)),
        _ => Err(format!("expected `+` or `-`, got `{sign}`")),
    }
}

fn parse_i16_immediate(value: &str) -> Result<i16, String> {
    let immediate = parse_i32_immediate(value)?;
    if !(i32::from(i16::MIN)..=i32::from(i16::MAX)).contains(&immediate) {
        return Err(format!(
            "immediate `{value}` is outside {}..{}",
            i16::MIN,
            i16::MAX
        ));
    }
    Ok(immediate as i16)
}

fn parse_i32_immediate(value: &str) -> Result<i32, String> {
    if let Some(hex) = value.strip_prefix("-0x") {
        i32::from_str_radix(hex, 16)
            .ok()
            .and_then(|parsed| parsed.checked_neg())
            .ok_or_else(|| format!("invalid immediate `{value}`"))
    } else if let Some(hex) = value.strip_prefix("0x") {
        i32::from_str_radix(hex, 16)
            .map_err(|error| format!("invalid immediate `{value}`: {error}"))
    } else {
        value
            .parse::<i32>()
            .map_err(|error| format!("invalid immediate `{value}`: {error}"))
    }
}

fn parse_u32_immediate(value: &str) -> Result<u32, String> {
    if let Some(hex) = value.strip_prefix("0x") {
        u32::from_str_radix(hex, 16)
    } else {
        value.parse::<u32>()
    }
    .map_err(|error| format!("invalid immediate `{value}`: {error}"))
}

fn save_trap_frame_words() -> Vec<u16> {
    let mut words = Vec::new();
    for register in 0..16 {
        words.push(const4(SCRATCH_REGISTER, register));
        words.push(write_csr(
            k16_vm::k16::K16_CSR_TRAP_FRAME_INDEX,
            SCRATCH_REGISTER,
        ));
        words.push(read_csr(
            RETURN_REGISTER,
            k16_vm::k16::K16_CSR_TRAP_FRAME_REGISTER,
        ));
        words.extend(store_return_to_arg0_offset(register as u32 * 4));
    }
    words.push(read_csr(
        RETURN_REGISTER,
        k16_vm::k16::K16_CSR_TRAP_RESUME_PC,
    ));
    words.extend(store_return_to_arg0_offset(TRAP_FRAME_RESUME_PC_OFFSET));
    words.push(read_csr(
        RETURN_REGISTER,
        k16_vm::k16::K16_CSR_TRAP_STACK_POINTER,
    ));
    words.extend(store_return_to_arg0_offset(TRAP_FRAME_STACK_POINTER_OFFSET));
    words.push(read_csr(
        RETURN_REGISTER,
        k16_vm::k16::K16_CSR_TRAP_INTERRUPT_ENABLE,
    ));
    words.extend(store_return_to_arg0_offset(
        TRAP_FRAME_INTERRUPT_ENABLE_OFFSET,
    ));
    words.push(ret());
    words
}

fn restore_trap_frame_words() -> Vec<u16> {
    let mut words = Vec::new();
    for register in 1..16 {
        words.push(const4(SCRATCH_REGISTER, register));
        words.push(write_csr(
            k16_vm::k16::K16_CSR_TRAP_FRAME_INDEX,
            SCRATCH_REGISTER,
        ));
        words.extend(load_return_from_arg0_offset(register as u32 * 4));
        words.push(write_csr(
            k16_vm::k16::K16_CSR_TRAP_FRAME_REGISTER,
            RETURN_REGISTER,
        ));
    }
    words.extend(load_return_from_arg0_offset(TRAP_FRAME_RESUME_PC_OFFSET));
    words.push(write_csr(
        k16_vm::k16::K16_CSR_TRAP_RESUME_PC,
        RETURN_REGISTER,
    ));
    words.extend(load_return_from_arg0_offset(
        TRAP_FRAME_STACK_POINTER_OFFSET,
    ));
    words.push(write_csr(
        k16_vm::k16::K16_CSR_TRAP_STACK_POINTER,
        RETURN_REGISTER,
    ));
    words.extend(load_return_from_arg0_offset(
        TRAP_FRAME_INTERRUPT_ENABLE_OFFSET,
    ));
    words.push(write_csr(
        k16_vm::k16::K16_CSR_TRAP_INTERRUPT_ENABLE,
        RETURN_REGISTER,
    ));
    words.extend(load_return_from_arg0_offset(0));
    words.push(ret());
    words
}

fn store_return_to_arg0_offset(offset: u32) -> Vec<u16> {
    let mut words = Vec::new();
    words.extend(const32_words(SCRATCH_REGISTER, offset));
    words.extend(add(SCRATCH_REGISTER, ARG0_REGISTER, SCRATCH_REGISTER));
    words.push(store32(SCRATCH_REGISTER, RETURN_REGISTER));
    words
}

fn load_return_from_arg0_offset(offset: u32) -> Vec<u16> {
    let mut words = Vec::new();
    words.extend(const32_words(SCRATCH_REGISTER, offset));
    words.extend(add(SCRATCH_REGISTER, ARG0_REGISTER, SCRATCH_REGISTER));
    words.push(load32(RETURN_REGISTER, SCRATCH_REGISTER));
    words
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

fn const32_words(register: u8, value: u32) -> [u16; 3] {
    [
        emit_const32_word(register),
        (value & 0xffff) as u16,
        (value >> 16) as u16,
    ]
}

fn emit_const32_word(register: u8) -> u16 {
    0xe001 | (u16::from(register) << 8)
}

fn pcadd32_words(register: u8, value: u32) -> [u16; 3] {
    [
        emit_pcadd32_word(register),
        (value & 0xffff) as u16,
        (value >> 16) as u16,
    ]
}

fn emit_pcadd32_word(register: u8) -> u16 {
    0xe002 | (u16::from(register) << 8)
}

fn const4(dst: u8, value: u8) -> u16 {
    0x1000 | (u16::from(dst) << 8) | u16::from(value & 0x0f)
}

fn add(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x0, lhs, rhs)
}

fn sub(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x1, lhs, rhs)
}

fn addi(dst: u8, src: u8, immediate: i16) -> [u16; 2] {
    extended_imm16(dst, src, 0x2, immediate)
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

fn store8(addr: u8, src: u8) -> u16 {
    0x5000 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn store16(addr: u8, src: u8) -> u16 {
    0x5001 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn store32(addr: u8, src: u8) -> u16 {
    0x5002 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn store8_offset(base: u8, src: u8, offset: i16) -> [u16; 2] {
    extended_imm16(base, src, 0x6, offset)
}

fn store16_offset(base: u8, src: u8, offset: i16) -> [u16; 2] {
    extended_imm16(base, src, 0x7, offset)
}

fn store32_offset(base: u8, src: u8, offset: i16) -> [u16; 2] {
    extended_imm16(base, src, 0x8, offset)
}

fn load8(dst: u8, addr: u8) -> u16 {
    0x4000 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn load16(dst: u8, addr: u8) -> u16 {
    0x4001 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn load32(dst: u8, addr: u8) -> u16 {
    0x4002 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn load8_offset(dst: u8, base: u8, offset: i16) -> [u16; 2] {
    extended_imm16(dst, base, 0x3, offset)
}

fn load16_offset(dst: u8, base: u8, offset: i16) -> [u16; 2] {
    extended_imm16(dst, base, 0x4, offset)
}

fn load32_offset(dst: u8, base: u8, offset: i16) -> [u16; 2] {
    extended_imm16(dst, base, 0x5, offset)
}

fn extended_imm16(a: u8, b: u8, subop: u8, immediate: i16) -> [u16; 2] {
    [
        0x3000 | (u16::from(a) << 8) | (u16::from(b) << 4) | u16::from(subop),
        immediate as u16,
    ]
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn assembler_encodes_extended_immediate_and_offset_instructions() {
        assert_eq!(
            assemble_instruction("addi r2, r1, -4").unwrap(),
            vec![0x3212, 0xfffc]
        );
        assert_eq!(
            assemble_instruction("load32 r3, [r4 + 12]").unwrap(),
            vec![0x3345, 0x000c]
        );
        assert_eq!(
            assemble_instruction("store32 [r5 - 8], r6").unwrap(),
            vec![0x3568, 0xfff8]
        );
    }

    #[test]
    fn assembler_rejects_out_of_range_extended_immediate() {
        let error = assemble_instruction("addi r2, r1, 32768").unwrap_err();

        assert!(error.contains("outside -32768..32767"), "{error}");
    }
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
