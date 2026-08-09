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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DecodedInstruction {
    Nop,
    Halt,
    Yield,
    Lui {
        dst: usize,
        immediate: u32,
    },
    AddI {
        dst: usize,
        src: usize,
        immediate: i32,
    },
    Add {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    Sub {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    Mul {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    And {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    Or {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    Xor {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    Shl {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    Shr {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    Sar {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    Eq {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    Ne {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    Ltu {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    LtS {
        dst: usize,
        lhs: usize,
        rhs: usize,
    },
    Load8 {
        dst: usize,
        base: usize,
        offset: i32,
    },
    Load16 {
        dst: usize,
        base: usize,
        offset: i32,
    },
    Load32 {
        dst: usize,
        base: usize,
        offset: i32,
    },
    Store8 {
        base: usize,
        src: usize,
        offset: i32,
    },
    Store16 {
        base: usize,
        src: usize,
        offset: i32,
    },
    Store32 {
        base: usize,
        src: usize,
        offset: i32,
    },
    BranchZ {
        src: usize,
        offset: i32,
    },
    BranchNz {
        src: usize,
        offset: i32,
    },
    Jump {
        offset: i32,
    },
    Call {
        offset: i32,
    },
    Ret,
    BranchLtu {
        lhs: usize,
        rhs: usize,
        offset: i32,
    },
    BranchUge {
        lhs: usize,
        rhs: usize,
        offset: i32,
    },
}

pub fn decode(word: u32) -> Result<DecodedInstruction, String> {
    let opcode = (word >> 24) as u8;
    let a = ((word >> 20) & 0x0f) as usize;
    let b = ((word >> 16) & 0x0f) as usize;
    let c = ((word >> 12) & 0x0f) as usize;
    let imm16 = word as u16 as i16 as i32;
    let offset20 = ((word << 12) as i32) >> 12;
    let rrr = |instruction| {
        if word & 0x0000_0fff == 0 {
            Ok(instruction)
        } else {
            Err(format!(
                "reserved K16-F32 RRR bits are non-zero in {word:#010x}"
            ))
        }
    };
    let none = |instruction| {
        if word & 0x00ff_ffff == 0 {
            Ok(instruction)
        } else {
            Err(format!(
                "reserved K16-F32 NONE bits are non-zero in {word:#010x}"
            ))
        }
    };
    let unconditional = |instruction| {
        if a == 0 {
            Ok(instruction)
        } else {
            Err(format!(
                "reserved K16-F32 branch register is non-zero in {word:#010x}"
            ))
        }
    };

    match opcode {
        0x00 => none(DecodedInstruction::Nop),
        0x01 => none(DecodedInstruction::Halt),
        0x02 => none(DecodedInstruction::Yield),
        0x10 => Ok(DecodedInstruction::Lui {
            dst: a,
            immediate: word & 0x000f_ffff,
        }),
        0x11 => Ok(DecodedInstruction::AddI {
            dst: a,
            src: b,
            immediate: imm16,
        }),
        0x20 => rrr(DecodedInstruction::Add {
            dst: a,
            lhs: b,
            rhs: c,
        }),
        0x21 => rrr(DecodedInstruction::Sub {
            dst: a,
            lhs: b,
            rhs: c,
        }),
        0x22 => rrr(DecodedInstruction::Mul {
            dst: a,
            lhs: b,
            rhs: c,
        }),
        0x23 => rrr(DecodedInstruction::And {
            dst: a,
            lhs: b,
            rhs: c,
        }),
        0x24 => rrr(DecodedInstruction::Or {
            dst: a,
            lhs: b,
            rhs: c,
        }),
        0x25 => rrr(DecodedInstruction::Xor {
            dst: a,
            lhs: b,
            rhs: c,
        }),
        0x26 => rrr(DecodedInstruction::Shl {
            dst: a,
            lhs: b,
            rhs: c,
        }),
        0x27 => rrr(DecodedInstruction::Shr {
            dst: a,
            lhs: b,
            rhs: c,
        }),
        0x28 => rrr(DecodedInstruction::Sar {
            dst: a,
            lhs: b,
            rhs: c,
        }),
        0x29 => rrr(DecodedInstruction::Eq {
            dst: a,
            lhs: b,
            rhs: c,
        }),
        0x2a => rrr(DecodedInstruction::Ne {
            dst: a,
            lhs: b,
            rhs: c,
        }),
        0x2b => rrr(DecodedInstruction::Ltu {
            dst: a,
            lhs: b,
            rhs: c,
        }),
        0x2c => rrr(DecodedInstruction::LtS {
            dst: a,
            lhs: b,
            rhs: c,
        }),
        0x30 => Ok(DecodedInstruction::Load8 {
            dst: a,
            base: b,
            offset: imm16,
        }),
        0x31 => Ok(DecodedInstruction::Load16 {
            dst: a,
            base: b,
            offset: imm16,
        }),
        0x32 => Ok(DecodedInstruction::Load32 {
            dst: a,
            base: b,
            offset: imm16,
        }),
        0x38 => Ok(DecodedInstruction::Store8 {
            base: a,
            src: b,
            offset: imm16,
        }),
        0x39 => Ok(DecodedInstruction::Store16 {
            base: a,
            src: b,
            offset: imm16,
        }),
        0x3a => Ok(DecodedInstruction::Store32 {
            base: a,
            src: b,
            offset: imm16,
        }),
        0x40 => Ok(DecodedInstruction::BranchZ {
            src: a,
            offset: offset20,
        }),
        0x41 => Ok(DecodedInstruction::BranchNz {
            src: a,
            offset: offset20,
        }),
        0x42 => unconditional(DecodedInstruction::Jump { offset: offset20 }),
        0x43 => unconditional(DecodedInstruction::Call { offset: offset20 }),
        0x44 => none(DecodedInstruction::Ret),
        0x45 => Ok(DecodedInstruction::BranchLtu {
            lhs: a,
            rhs: b,
            offset: imm16,
        }),
        0x46 => Ok(DecodedInstruction::BranchUge {
            lhs: a,
            rhs: b,
            offset: imm16,
        }),
        _ => Err(format!(
            "illegal K16-F32 opcode {opcode:#04x} in {word:#010x}"
        )),
    }
}
