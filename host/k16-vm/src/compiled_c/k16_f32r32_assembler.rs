/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

use std::collections::BTreeMap;

use crate::k16_f32r32::encoding;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AssembledK16F32R32 {
    pub image: Vec<u8>,
    pub entry_offset: u32,
    pub stop_offset: u32,
    pub instruction_count: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum ParsedLine {
    Label(String),
    Instruction {
        line: usize,
        mnemonic: String,
        operands: Vec<String>,
    },
}

pub fn assemble_k16_f32r32(
    source: &str,
    base: u32,
    entry: &str,
) -> Result<AssembledK16F32R32, String> {
    if !base.is_multiple_of(4) {
        return Err(format!(
            "K16-F32R32 base {base:#010x} is not four-byte aligned"
        ));
    }
    let parsed = parse(source)?;
    let (labels, instruction_count) = assign_addresses(&parsed, base)?;
    let entry_address = labels
        .get(entry)
        .copied()
        .ok_or_else(|| format!("undefined entry label {entry:?}"))?;
    let mut words = Vec::with_capacity(instruction_count as usize + 1);
    let mut address = base;
    for record in &parsed {
        if let ParsedLine::Instruction {
            line,
            mnemonic,
            operands,
        } = record
        {
            let encoded = encode_instruction(*line, mnemonic, operands, address, &labels)?;
            address = address
                .checked_add(encoded.len() as u32 * 4)
                .ok_or_else(|| format!("line {line}: K16-F32R32 image address overflow"))?;
            words.extend(encoded);
        }
    }
    let stop_offset = instruction_count
        .checked_mul(4)
        .ok_or("K16-F32R32 image size overflow")?;
    words.push(encoding::halt());
    Ok(AssembledK16F32R32 {
        image: words.into_iter().flat_map(u32::to_le_bytes).collect(),
        entry_offset: entry_address - base,
        stop_offset,
        instruction_count,
    })
}

fn parse(source: &str) -> Result<Vec<ParsedLine>, String> {
    let mut parsed = Vec::new();
    for (index, original) in source.lines().enumerate() {
        let line_number = index + 1;
        let line = original
            .split_once('#')
            .map_or(original, |(code, _)| code)
            .trim();
        if line.is_empty() {
            continue;
        }
        if let Some(label) = line.strip_suffix(':') {
            let label = label.trim();
            if !valid_symbol(label) {
                return Err(format!("line {line_number}: invalid label {label:?}"));
            }
            parsed.push(ParsedLine::Label(label.to_string()));
            continue;
        }
        if line.starts_with('.') {
            let directive = line.split_whitespace().next().unwrap();
            if !matches!(
                directive,
                ".file" | ".section" | ".globl" | ".p2align" | ".type" | ".size"
            ) {
                return Err(format!(
                    "line {line_number}: unknown K16-F32R32 assembler directive {directive:?}"
                ));
            }
            continue;
        }
        let (mnemonic, operand_text) = line
            .split_once(char::is_whitespace)
            .map_or((line, ""), |(mnemonic, operands)| {
                (mnemonic, operands.trim())
            });
        let operands = if operand_text.is_empty() {
            Vec::new()
        } else {
            operand_text
                .split(',')
                .map(str::trim)
                .map(str::to_string)
                .collect()
        };
        parsed.push(ParsedLine::Instruction {
            line: line_number,
            mnemonic: mnemonic.to_string(),
            operands,
        });
    }
    Ok(parsed)
}

fn assign_addresses(
    parsed: &[ParsedLine],
    base: u32,
) -> Result<(BTreeMap<String, u32>, u32), String> {
    let mut labels = BTreeMap::new();
    let mut address = base;
    let mut instruction_count = 0_u32;
    for record in parsed {
        match record {
            ParsedLine::Label(label) => {
                if labels.insert(label.clone(), address).is_some() {
                    return Err(format!("duplicate label {label:?}"));
                }
            }
            ParsedLine::Instruction { line, mnemonic, .. } => {
                let emitted = u32::from(mnemonic == "const32") + 1;
                instruction_count = instruction_count
                    .checked_add(emitted)
                    .ok_or_else(|| format!("line {line}: instruction-count overflow"))?;
                address = address
                    .checked_add(emitted * 4)
                    .ok_or_else(|| format!("line {line}: K16-F32R32 image address overflow"))?;
            }
        }
    }
    Ok((labels, instruction_count))
}

fn encode_instruction(
    line: usize,
    mnemonic: &str,
    operands: &[String],
    address: u32,
    labels: &BTreeMap<String, u32>,
) -> Result<Vec<u32>, String> {
    let fail = |message: String| format!("line {line}: {message}");
    let encoded = match mnemonic {
        "const32" => {
            require_operands(line, mnemonic, operands, 2)?;
            let dst = parse_register(&operands[0]).map_err(&fail)?;
            let value = parse_u32_bits(&operands[1]).map_err(&fail)?;
            return Ok(encoding::materialize(dst, value).to_vec());
        }
        "addi" => {
            require_operands(line, mnemonic, operands, 3)?;
            encoding::addi(
                parse_register(&operands[0]).map_err(&fail)?,
                parse_register(&operands[1]).map_err(&fail)?,
                parse_i14(&operands[2], "immediate").map_err(&fail)?,
            )
        }
        "add" | "sub" | "mul" | "and" | "or" | "xor" | "shl" | "shr" | "sar" | "eq" | "ne"
        | "ltu" | "lt_s" => {
            require_operands(line, mnemonic, operands, 3)?;
            let dst = parse_register(&operands[0]).map_err(&fail)?;
            let lhs = parse_register(&operands[1]).map_err(&fail)?;
            let rhs = parse_register(&operands[2]).map_err(&fail)?;
            match mnemonic {
                "add" => encoding::add(dst, lhs, rhs),
                "sub" => encoding::sub(dst, lhs, rhs),
                "mul" => encoding::mul(dst, lhs, rhs),
                "and" => encoding::and(dst, lhs, rhs),
                "or" => encoding::or(dst, lhs, rhs),
                "xor" => encoding::xor(dst, lhs, rhs),
                "shl" => encoding::shl(dst, lhs, rhs),
                "shr" => encoding::shr(dst, lhs, rhs),
                "sar" => encoding::sar(dst, lhs, rhs),
                "eq" => encoding::eq(dst, lhs, rhs),
                "ne" => encoding::ne(dst, lhs, rhs),
                "ltu" => encoding::ltu(dst, lhs, rhs),
                "lt_s" => encoding::lt_s(dst, lhs, rhs),
                _ => unreachable!(),
            }
        }
        "load8" | "load16" | "load32" => {
            require_operands(line, mnemonic, operands, 2)?;
            let dst = parse_register(&operands[0]).map_err(&fail)?;
            let (base, offset) = parse_memory(&operands[1]).map_err(&fail)?;
            match mnemonic {
                "load8" => encoding::load8(dst, base, offset),
                "load16" => encoding::load16(dst, base, offset),
                "load32" => encoding::load32(dst, base, offset),
                _ => unreachable!(),
            }
        }
        "store8" | "store16" | "store32" => {
            require_operands(line, mnemonic, operands, 2)?;
            let (base, offset) = parse_memory(&operands[0]).map_err(&fail)?;
            let src = parse_register(&operands[1]).map_err(&fail)?;
            match mnemonic {
                "store8" => encoding::store8(base, src, offset),
                "store16" => encoding::store16(base, src, offset),
                "store32" => encoding::store32(base, src, offset),
                _ => unreachable!(),
            }
        }
        "br" | "brz" | "brnz" | "call32" => {
            let (register, target) = if matches!(mnemonic, "brz" | "brnz") {
                require_operands(line, mnemonic, operands, 2)?;
                (
                    Some(parse_register(&operands[0]).map_err(&fail)?),
                    &operands[1],
                )
            } else {
                require_operands(line, mnemonic, operands, 1)?;
                (None, &operands[0])
            };
            let bits = if matches!(mnemonic, "brz" | "brnz") {
                19
            } else {
                24
            };
            let offset = relative_offset(target, address, labels, bits).map_err(&fail)?;
            match mnemonic {
                "br" => encoding::jump(offset),
                "brz" => encoding::branchz(register.unwrap(), offset),
                "brnz" => encoding::branchnz(register.unwrap(), offset),
                "call32" => encoding::call(offset),
                _ => unreachable!(),
            }
        }
        "brltu" | "bruge" => {
            require_operands(line, mnemonic, operands, 3)?;
            let lhs = parse_register(&operands[0]).map_err(&fail)?;
            let rhs = parse_register(&operands[1]).map_err(&fail)?;
            let offset = relative_offset(&operands[2], address, labels, 14).map_err(&fail)?;
            if mnemonic == "brltu" {
                encoding::branch_ltu(lhs, rhs, offset)
            } else {
                encoding::branch_uge(lhs, rhs, offset)
            }
        }
        "ret" => {
            require_operands(line, mnemonic, operands, 0)?;
            encoding::ret()
        }
        _ => return Err(fail(format!("unknown K16-F32R32 opcode {mnemonic:?}"))),
    };
    Ok(vec![encoded])
}

fn require_operands(
    line: usize,
    mnemonic: &str,
    operands: &[String],
    expected: usize,
) -> Result<(), String> {
    if operands.len() == expected && operands.iter().all(|operand| !operand.is_empty()) {
        Ok(())
    } else {
        Err(format!(
            "line {line}: {mnemonic} expects {expected} operands, got {}",
            operands.len()
        ))
    }
}

fn parse_register(text: &str) -> Result<u8, String> {
    let number = text
        .strip_prefix('r')
        .ok_or_else(|| format!("invalid register {text:?}"))?
        .parse::<u8>()
        .map_err(|_| format!("invalid register {text:?}"))?;
    if number < 32 {
        Ok(number)
    } else {
        Err(format!("register {text:?} is outside r0..r31"))
    }
}

fn parse_i14(text: &str, kind: &str) -> Result<i32, String> {
    let value = parse_integer(text).map_err(|error| format!("invalid {kind}: {error}"))?;
    if (-8192..=8191).contains(&value) {
        Ok(value as i32)
    } else {
        Err(format!("{kind} {text:?} does not fit signed 14-bit"))
    }
}

fn parse_u32_bits(text: &str) -> Result<u32, String> {
    let value = parse_integer(text).map_err(|error| format!("invalid immediate: {error}"))?;
    if (i64::from(i32::MIN)..=i64::from(u32::MAX)).contains(&value) {
        Ok(value as u32)
    } else {
        Err(format!("immediate {text:?} does not fit 32 bits"))
    }
}

fn parse_integer(text: &str) -> Result<i64, String> {
    let (negative, digits) = text
        .strip_prefix('-')
        .map_or((false, text), |digits| (true, digits));
    let (radix, digits) = digits
        .strip_prefix("0x")
        .map_or((10, digits), |digits| (16, digits));
    let magnitude =
        i64::from_str_radix(digits, radix).map_err(|_| format!("invalid integer {text:?}"))?;
    Ok(if negative { -magnitude } else { magnitude })
}

fn parse_memory(text: &str) -> Result<(u8, i32), String> {
    let inner = text
        .strip_prefix('[')
        .and_then(|value| value.strip_suffix(']'))
        .ok_or_else(|| format!("invalid memory operand {text:?}"))?
        .trim();
    let pieces = inner.split_whitespace().collect::<Vec<_>>();
    match pieces.as_slice() {
        [base] => Ok((parse_register(base)?, 0)),
        [base, "+", offset] => Ok((parse_register(base)?, parse_i14(offset, "memory offset")?)),
        [base, "-", offset] => {
            let value = parse_integer(offset)
                .map_err(|error| format!("invalid memory offset: {error}"))?
                .checked_neg()
                .ok_or_else(|| format!("memory offset {offset:?} cannot be negated"))?;
            if (-8192..=8191).contains(&value) {
                Ok((parse_register(base)?, value as i32))
            } else {
                Err(format!(
                    "memory offset -{offset} does not fit signed 14-bit"
                ))
            }
        }
        _ => Err(format!("invalid memory operand {text:?}")),
    }
}

fn relative_offset(
    target: &str,
    address: u32,
    labels: &BTreeMap<String, u32>,
    bits: u32,
) -> Result<i32, String> {
    let target_address = labels
        .get(target)
        .copied()
        .ok_or_else(|| format!("undefined label {target:?}"))?;
    let next_address = address
        .checked_add(4)
        .ok_or_else(|| "relative target address overflow".to_string())?;
    let byte_delta = i64::from(target_address) - i64::from(next_address);
    if byte_delta % 4 != 0 {
        return Err(format!(
            "relative target {target:?} is not four-byte aligned"
        ));
    }
    let offset = byte_delta / 4;
    let minimum = -(1_i64 << (bits - 1));
    let maximum = (1_i64 << (bits - 1)) - 1;
    if !(minimum..=maximum).contains(&offset) {
        return Err(format!(
            "relative target {target:?} offset {offset} does not fit signed {bits} bits"
        ));
    }
    Ok(offset as i32)
}

fn valid_symbol(symbol: &str) -> bool {
    let mut chars = symbol.chars();
    chars
        .next()
        .is_some_and(|first| first.is_ascii_alphabetic() || matches!(first, '_' | '.'))
        && chars.all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '_' | '.' | '$')
        })
}
