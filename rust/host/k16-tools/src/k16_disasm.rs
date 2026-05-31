use crate::artifact::K16ArtifactTarget;
use crate::k16e;
use std::collections::BTreeSet;

pub fn disassemble_artifact(bytes: &[u8], target: K16ArtifactTarget) -> Result<String, String> {
    let (bytes, base_address) = match target {
        K16ArtifactTarget::Boot | K16ArtifactTarget::Kernel | K16ArtifactTarget::Program => {
            let executable = k16e::decode_k16_executable(bytes)?;
            let expected = target.fixed_image_abi_kind().unwrap();
            if executable.abi_kind != expected {
                return Err(format!(
                    "K16E ABI kind {:?} does not match requested {:?} target",
                    executable.abi_kind, target
                ));
            }
            (executable.payload, executable.load_addr)
        }
        K16ArtifactTarget::Bios => (bytes.to_vec(), target.base_address()),
    };
    if bytes.len() % 2 != 0 {
        return Err("K16 artifact byte length must be even".to_string());
    }
    let words = decode_words(&bytes);
    let labels = collect_branch_labels(&words, base_address)?;
    let mut output = String::new();
    let mut index = 0;
    while index < words.len() {
        let pc = base_address
            .checked_add((index as u32) * 2)
            .ok_or_else(|| "K16 artifact address overflows u32".to_string())?;
        if labels.contains(&pc) {
            output.push_str(&format!("L_{pc:08x}:\n"));
        }
        let instruction = disassemble_instruction(&words, index, pc)?;
        let raw_words = format_raw_words(&words, index, instruction.width);
        output.push_str(&format!("{pc:08x}: {raw_words}  {}\n", instruction.text));
        index += instruction.width;
    }
    Ok(output)
}

fn decode_words(bytes: &[u8]) -> Vec<u16> {
    bytes
        .chunks_exact(2)
        .map(|chunk| u16::from_le_bytes([chunk[0], chunk[1]]))
        .collect()
}

fn collect_branch_labels(words: &[u16], base_address: u32) -> Result<BTreeSet<u32>, String> {
    let mut labels = BTreeSet::new();
    let mut index = 0;
    while index < words.len() {
        let pc = base_address
            .checked_add((index as u32) * 2)
            .ok_or_else(|| "K16 artifact address overflows u32".to_string())?;
        let instruction = disassemble_instruction(words, index, pc)?;
        if let Some(target) = instruction.branch_target {
            labels.insert(target);
        }
        index += instruction.width;
    }
    Ok(labels)
}

fn format_raw_words(words: &[u16], index: usize, width: usize) -> String {
    words[index..index + width]
        .iter()
        .map(|word| format!("{word:04x}"))
        .collect::<Vec<_>>()
        .join(" ")
}

struct DisassembledInstruction {
    text: String,
    width: usize,
    branch_target: Option<u32>,
}

impl DisassembledInstruction {
    fn single(text: String) -> Self {
        Self {
            text,
            width: 1,
            branch_target: None,
        }
    }

    fn multi(text: String, width: usize) -> Self {
        Self {
            text,
            width,
            branch_target: None,
        }
    }

    fn branch(text: String, target: u32) -> Self {
        Self {
            text,
            width: 1,
            branch_target: Some(target),
        }
    }
}

fn disassemble_instruction(
    words: &[u16],
    index: usize,
    pc: u32,
) -> Result<DisassembledInstruction, String> {
    let word = words[index];
    let op = (word >> 12) & 0x0f;
    let a = ((word >> 8) & 0x0f) as u8;
    let b = ((word >> 4) & 0x0f) as u8;
    let c = (word & 0x0f) as u8;
    let instruction = match op {
        0x0 => match word & 0x0fff {
            0x000 => DisassembledInstruction::single("nop".to_string()),
            0x001 => DisassembledInstruction::single("halt".to_string()),
            _ if c == 0x2 => DisassembledInstruction::single(format!("read_csr r{a}, {b}")),
            _ if c == 0x3 => DisassembledInstruction::single(format!("write_csr {a}, r{b}")),
            _ => return Err(invalid_instruction(pc, word, "unknown system instruction")),
        },
        0x1 => {
            if b != 0 {
                return Err(invalid_instruction(
                    pc,
                    word,
                    "const4 reserved bits are set",
                ));
            }
            DisassembledInstruction::single(format!("const4 r{a}, {c}"))
        }
        0x2 => disassemble_alu_rrr(words, index, pc, a, b, c, word)?,
        0x3 => disassemble_extended(words, index, pc, a, b, c, word)?,
        0x4 => match c {
            0x0 => DisassembledInstruction::single(format!("load8 r{a}, [r{b}]")),
            0x1 => DisassembledInstruction::single(format!("load16 r{a}, [r{b}]")),
            0x2 => DisassembledInstruction::single(format!("load32 r{a}, [r{b}]")),
            _ => return Err(invalid_instruction(pc, word, "invalid load width")),
        },
        0x5 => match c {
            0x0 => DisassembledInstruction::single(format!("store8 [r{a}], r{b}")),
            0x1 => DisassembledInstruction::single(format!("store16 [r{a}], r{b}")),
            0x2 => DisassembledInstruction::single(format!("store32 [r{a}], r{b}")),
            _ => return Err(invalid_instruction(pc, word, "invalid store width")),
        },
        0x6 => match b {
            0x0 => {
                let target = relative_branch_target(pc, c)?;
                DisassembledInstruction::branch(
                    format!("branch_if_zero r{a}, L_{target:08x}"),
                    target,
                )
            }
            0x1 => {
                let target = relative_branch_target(pc, c)?;
                DisassembledInstruction::branch(
                    format!("branch_if_nonzero r{a}, L_{target:08x}"),
                    target,
                )
            }
            _ => return Err(invalid_instruction(pc, word, "invalid branch predicate")),
        },
        0x7 => {
            if b != 0 || c != 0 {
                return Err(invalid_instruction(pc, word, "jump reserved bits are set"));
            }
            DisassembledInstruction::single(format!("jump r{a}"))
        }
        0x8 => {
            if b != 0 || c != 0 {
                return Err(invalid_instruction(pc, word, "call reserved bits are set"));
            }
            DisassembledInstruction::single(format!("call r{a}"))
        }
        0x9 => {
            if a != 0 || b != 0 || c != 0 {
                return Err(invalid_instruction(pc, word, "ret reserved bits are set"));
            }
            DisassembledInstruction::single("ret".to_string())
        }
        0xe => disassemble_const32(words, index, pc, a, b, c, word)?,
        _ => return Err(invalid_instruction(pc, word, "unknown opcode")),
    };
    Ok(instruction)
}

fn disassemble_alu_rrr(
    words: &[u16],
    index: usize,
    pc: u32,
    dst: u8,
    category: u8,
    subop: u8,
    word: u16,
) -> Result<DisassembledInstruction, String> {
    if category != 0 {
        return Err(invalid_instruction(pc, word, "ALU reserved bits are set"));
    }
    let Some(extension) = words.get(index + 1).copied() else {
        return Err(invalid_instruction(pc, word, "truncated ALU operands"));
    };
    if extension & 0xff00 != 0 {
        return Err(invalid_instruction(
            pc,
            word,
            "ALU extension reserved bits are set",
        ));
    }
    let mnemonic = match subop {
        0x0 => "add",
        0x1 => "sub",
        0x2 => "and",
        0x3 => "or",
        0x4 => "xor",
        0x5 => "shl",
        0x6 => "shr",
        0x7 => "sar",
        0x8 => "eq",
        0x9 => "ne",
        0xa => "ltu",
        0xb => "lt_s",
        0xc => "mul",
        0xd => "mulh_u",
        0xe => "mulh_s",
        _ => return Err(invalid_instruction(pc, word, "unknown ALU subop")),
    };
    let lhs = ((extension >> 4) & 0x0f) as u8;
    let rhs = (extension & 0x0f) as u8;
    Ok(DisassembledInstruction::multi(
        format!("{mnemonic} r{dst}, r{lhs}, r{rhs}"),
        2,
    ))
}

fn disassemble_extended(
    words: &[u16],
    index: usize,
    pc: u32,
    a: u8,
    b: u8,
    c: u8,
    word: u16,
) -> Result<DisassembledInstruction, String> {
    let Some(extension) = words.get(index + 1).copied() else {
        return Err(invalid_instruction(
            pc,
            word,
            "truncated extended instruction",
        ));
    };
    let instruction = match c {
        0x1 => {
            DisassembledInstruction::multi(format!("test_bits r{a}, r{b}, 0x{extension:04x}"), 2)
        }
        _ => {
            return Err(invalid_instruction(
                pc,
                word,
                "unknown extended instruction",
            ))
        }
    };
    Ok(instruction)
}

fn disassemble_const32(
    words: &[u16],
    index: usize,
    pc: u32,
    register: u8,
    reserved: u8,
    subop: u8,
    word: u16,
) -> Result<DisassembledInstruction, String> {
    if reserved != 0 || subop != 1 {
        return Err(invalid_instruction(
            pc,
            word,
            "const32 reserved bits are set",
        ));
    }
    let Some(lo) = words.get(index + 1).copied() else {
        return Err(invalid_instruction(pc, word, "truncated const32 low word"));
    };
    let Some(hi) = words.get(index + 2).copied() else {
        return Err(invalid_instruction(pc, word, "truncated const32 high word"));
    };
    let value = u32::from(lo) | (u32::from(hi) << 16);
    Ok(DisassembledInstruction::multi(
        format!("const32 r{register}, 0x{value:08x}"),
        3,
    ))
}

fn invalid_instruction(pc: u32, word: u16, reason: &str) -> String {
    format!("invalid K16 instruction 0x{word:04x} at 0x{pc:08x}: {reason}")
}

fn relative_branch_target(pc: u32, offset_nibble: u8) -> Result<u32, String> {
    let next_pc = pc
        .checked_add(2)
        .ok_or_else(|| "K16 branch next pc overflows u32".to_string())?;
    let offset_words = sign_extend_nibble(offset_nibble);
    let target = i64::from(next_pc) + i64::from(offset_words * 2);
    if !(0..=i64::from(u32::MAX)).contains(&target) {
        return Err(format!(
            "K16 branch from {pc:#010x} with offset {offset_words} words leaves address space"
        ));
    }
    Ok(target as u32)
}

fn sign_extend_nibble(value: u8) -> i32 {
    let raw = i32::from(value & 0x0f);
    if raw & 0x08 == 0 {
        raw
    } else {
        raw - 16
    }
}
