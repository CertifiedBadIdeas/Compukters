use crate::artifact::Rux16ArtifactTarget;
use crate::ruxe;
use std::collections::BTreeSet;

pub fn disassemble_artifact(bytes: &[u8], target: Rux16ArtifactTarget) -> Result<String, String> {
    let (bytes, base_address) = match target {
        Rux16ArtifactTarget::Boot | Rux16ArtifactTarget::Kernel | Rux16ArtifactTarget::Program => {
            let executable = ruxe::decode_rux16_executable(bytes)?;
            let expected = target.fixed_image_abi_kind().unwrap();
            if executable.abi_kind != expected {
                return Err(format!(
                    "RUXE ABI kind {:?} does not match requested {:?} target",
                    executable.abi_kind, target
                ));
            }
            (executable.payload, executable.load_addr)
        }
        Rux16ArtifactTarget::Bios => (bytes.to_vec(), target.base_address()),
    };
    if bytes.len() % 2 != 0 {
        return Err("Rux16 artifact byte length must be even".to_string());
    }
    let words = decode_words(&bytes);
    let labels = collect_branch_labels(&words, base_address)?;
    let mut output = String::new();
    let mut index = 0;
    while index < words.len() {
        let pc = base_address
            .checked_add((index as u32) * 2)
            .ok_or_else(|| "Rux16 artifact address overflows u32".to_string())?;
        if labels.contains(&pc) {
            output.push_str(&format!("L_{pc:08x}:\n"));
        }
        let (text, width) = disassemble_instruction(&words, index, pc);
        let raw_words = format_raw_words(&words, index, width);
        output.push_str(&format!("{pc:08x}: {raw_words}  {text}\n"));
        index += width;
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
            .ok_or_else(|| "Rux16 artifact address overflows u32".to_string())?;
        let word = words[index];
        let op = (word >> 12) & 0x0f;
        let b = ((word >> 4) & 0x0f) as u8;
        let c = (word & 0x0f) as u8;
        if op == 0x6 && (b == 0x0 || b == 0x1) {
            labels.insert(relative_branch_target(pc, c)?);
        }
        index += instruction_width(words, index);
    }
    Ok(labels)
}

fn instruction_width(words: &[u16], index: usize) -> usize {
    let word = words[index];
    let op = (word >> 12) & 0x0f;
    let b = ((word >> 4) & 0x0f) as u8;
    let c = (word & 0x0f) as u8;
    match op {
        0x3 if index + 1 < words.len() && (c == 0x1 || (b == 0x0 && (c == 0x0 || c == 0x2))) => 2,
        0xe if index + 2 < words.len() && b == 0x0 && c == 0x1 => 3,
        _ => 1,
    }
}

fn format_raw_words(words: &[u16], index: usize, width: usize) -> String {
    words[index..index + width]
        .iter()
        .map(|word| format!("{word:04x}"))
        .collect::<Vec<_>>()
        .join(" ")
}

fn disassemble_instruction(words: &[u16], index: usize, pc: u32) -> (String, usize) {
    let word = words[index];
    let op = (word >> 12) & 0x0f;
    let a = ((word >> 8) & 0x0f) as u8;
    let b = ((word >> 4) & 0x0f) as u8;
    let c = (word & 0x0f) as u8;
    match op {
        0x0 => match word & 0x0fff {
            0x000 => ("nop".to_string(), 1),
            0x001 => ("halt".to_string(), 1),
            _ if c == 0x2 => (format!("read_csr r{a}, {b}"), 1),
            _ if c == 0x3 => (format!("write_csr {a}, r{b}"), 1),
            _ => (format!(".word 0x{word:04x}"), 1),
        },
        0x1 => (format!("const4 r{a}, {c}"), 1),
        0x2 => (format!("add r{a}, r{b}, r{c}"), 1),
        0x3 => disassemble_extended(words, index, a, b, c, word),
        0x4 => match c {
            0x0 => (format!("load8 r{a}, [r{b}]"), 1),
            0x2 => (format!("load32 r{a}, [r{b}]"), 1),
            _ => (format!(".word 0x{word:04x}"), 1),
        },
        0x5 => match c {
            0x0 => (format!("store8 [r{a}], r{b}"), 1),
            0x2 => (format!("store32 [r{a}], r{b}"), 1),
            _ => (format!(".word 0x{word:04x}"), 1),
        },
        0x6 => match b {
            0x0 => (
                format!(
                    "branch_if_zero r{a}, L_{:08x}",
                    relative_branch_target(pc, c).unwrap()
                ),
                1,
            ),
            0x1 => (
                format!(
                    "branch_if_nonzero r{a}, L_{:08x}",
                    relative_branch_target(pc, c).unwrap()
                ),
                1,
            ),
            _ => (format!(".word 0x{word:04x}"), 1),
        },
        0x7 => (format!("jump r{a}"), 1),
        0x8 => match (b, c) {
            (0x0, 0x0) => (format!("call r{a}"), 1),
            _ => (format!(".word 0x{word:04x}"), 1),
        },
        0x9 => match (a, b, c) {
            (0x0, 0x0, 0x0) => ("ret".to_string(), 1),
            _ => (format!(".word 0x{word:04x}"), 1),
        },
        0xe => disassemble_const32(words, index, a, word),
        _ => (format!(".word 0x{word:04x}"), 1),
    }
}

fn disassemble_extended(
    words: &[u16],
    index: usize,
    a: u8,
    b: u8,
    c: u8,
    word: u16,
) -> (String, usize) {
    let Some(extension) = words.get(index + 1).copied() else {
        return (format!(".word 0x{word:04x} ; missing extension"), 1);
    };
    match c {
        0x0 if b == 0 => {
            let lhs = ((extension >> 4) & 0x0f) as u8;
            let rhs = (extension & 0x0f) as u8;
            (format!("eq r{a}, r{lhs}, r{rhs}"), 2)
        }
        0x1 => (format!("test_bits r{a}, r{b}, 0x{extension:04x}"), 2),
        0x2 if b == 0 => {
            let lhs = ((extension >> 4) & 0x0f) as u8;
            let rhs = (extension & 0x0f) as u8;
            (format!("ltu r{a}, r{lhs}, r{rhs}"), 2)
        }
        _ => (format!(".word 0x{word:04x}"), 1),
    }
}

fn disassemble_const32(words: &[u16], index: usize, register: u8, word: u16) -> (String, usize) {
    let Some(lo) = words.get(index + 1).copied() else {
        return (format!(".word 0x{word:04x} ; missing const32 lo"), 1);
    };
    let Some(hi) = words.get(index + 2).copied() else {
        return (format!(".word 0x{word:04x} ; missing const32 hi"), 1);
    };
    let value = u32::from(lo) | (u32::from(hi) << 16);
    (format!("const32 r{register}, 0x{value:08x}"), 3)
}

fn relative_branch_target(pc: u32, offset_nibble: u8) -> Result<u32, String> {
    let next_pc = pc
        .checked_add(2)
        .ok_or_else(|| "Rux16 branch next pc overflows u32".to_string())?;
    let offset_words = sign_extend_nibble(offset_nibble);
    let target = i64::from(next_pc) + i64::from(offset_words * 2);
    if !(0..=i64::from(u32::MAX)).contains(&target) {
        return Err(format!(
            "Rux16 branch from {pc:#010x} with offset {offset_words} words leaves address space"
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
