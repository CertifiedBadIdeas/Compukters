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
pub(crate) enum Op {
    Add,
    Sub,
    Sll,
    Slt,
    Sltu,
    Xor,
    Srl,
    Sra,
    Or,
    And,
    Mul,
    Mulh,
    Mulhsu,
    Mulhu,
    Div,
    Divu,
    Rem,
    Remu,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ImmOp {
    Add,
    Slt,
    Sltu,
    Xor,
    Or,
    And,
    Sll,
    Srl,
    Sra,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Branch {
    Eq,
    Ne,
    Lt,
    Ge,
    Ltu,
    Geu,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Load {
    Byte,
    Half,
    Word,
    ByteU,
    HalfU,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Store {
    Byte,
    Half,
    Word,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CsrOperation {
    Write,
    Set,
    Clear,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CsrSource {
    Register(usize),
    Immediate(u8),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum DecodedInstruction {
    Lui {
        rd: usize,
        value: u32,
    },
    Auipc {
        rd: usize,
        value: u32,
    },
    Jal {
        rd: usize,
        offset: i32,
    },
    Jalr {
        rd: usize,
        rs1: usize,
        immediate: i32,
    },
    Branch {
        kind: Branch,
        rs1: usize,
        rs2: usize,
        offset: i32,
    },
    Load {
        kind: Load,
        rd: usize,
        rs1: usize,
        immediate: i32,
    },
    Store {
        kind: Store,
        rs1: usize,
        rs2: usize,
        immediate: i32,
    },
    Immediate {
        op: ImmOp,
        rd: usize,
        rs1: usize,
        immediate: i32,
    },
    Register {
        op: Op,
        rd: usize,
        rs1: usize,
        rs2: usize,
    },
    Csr {
        operation: CsrOperation,
        rd: usize,
        csr: u16,
        source: CsrSource,
    },
    Ecall,
    Ebreak,
    Mret,
}

pub(crate) fn decode(word: u32) -> Result<DecodedInstruction, String> {
    let opcode = word & 0x7f;
    let rd = ((word >> 7) & 31) as usize;
    let funct3 = (word >> 12) & 7;
    let rs1 = ((word >> 15) & 31) as usize;
    let rs2 = ((word >> 20) & 31) as usize;
    let funct7 = (word >> 25) & 0x7f;
    let i_imm = (word as i32) >> 20;
    let s_raw = ((word >> 7) & 0x1f) | ((word >> 25) << 5);
    let s_imm = ((s_raw << 20) as i32) >> 20;
    let b_raw = ((word >> 31) << 12)
        | (((word >> 7) & 1) << 11)
        | (((word >> 25) & 0x3f) << 5)
        | (((word >> 8) & 0x0f) << 1);
    let b_imm = ((b_raw << 19) as i32) >> 19;
    let j_raw = ((word >> 31) << 20)
        | (((word >> 12) & 0xff) << 12)
        | (((word >> 20) & 1) << 11)
        | (((word >> 21) & 0x3ff) << 1);
    let j_imm = ((j_raw << 11) as i32) >> 11;
    let illegal = || Err(format!("illegal RV32IM instruction {word:#010x}"));
    match opcode {
        0x37 => Ok(DecodedInstruction::Lui {
            rd,
            value: word & 0xffff_f000,
        }),
        0x17 => Ok(DecodedInstruction::Auipc {
            rd,
            value: word & 0xffff_f000,
        }),
        0x6f => Ok(DecodedInstruction::Jal { rd, offset: j_imm }),
        0x67 if funct3 == 0 => Ok(DecodedInstruction::Jalr {
            rd,
            rs1,
            immediate: i_imm,
        }),
        0x63 => {
            let kind = match funct3 {
                0 => Branch::Eq,
                1 => Branch::Ne,
                4 => Branch::Lt,
                5 => Branch::Ge,
                6 => Branch::Ltu,
                7 => Branch::Geu,
                _ => return illegal(),
            };
            Ok(DecodedInstruction::Branch {
                kind,
                rs1,
                rs2,
                offset: b_imm,
            })
        }
        0x03 => {
            let kind = match funct3 {
                0 => Load::Byte,
                1 => Load::Half,
                2 => Load::Word,
                4 => Load::ByteU,
                5 => Load::HalfU,
                _ => return illegal(),
            };
            Ok(DecodedInstruction::Load {
                kind,
                rd,
                rs1,
                immediate: i_imm,
            })
        }
        0x23 => {
            let kind = match funct3 {
                0 => Store::Byte,
                1 => Store::Half,
                2 => Store::Word,
                _ => return illegal(),
            };
            Ok(DecodedInstruction::Store {
                kind,
                rs1,
                rs2,
                immediate: s_imm,
            })
        }
        0x13 => {
            let (op, immediate) = match funct3 {
                0 => (ImmOp::Add, i_imm),
                2 => (ImmOp::Slt, i_imm),
                3 => (ImmOp::Sltu, i_imm),
                4 => (ImmOp::Xor, i_imm),
                6 => (ImmOp::Or, i_imm),
                7 => (ImmOp::And, i_imm),
                1 if funct7 == 0 => (ImmOp::Sll, rs2 as i32),
                5 if funct7 == 0 => (ImmOp::Srl, rs2 as i32),
                5 if funct7 == 0x20 => (ImmOp::Sra, rs2 as i32),
                _ => return illegal(),
            };
            Ok(DecodedInstruction::Immediate {
                op,
                rd,
                rs1,
                immediate,
            })
        }
        0x33 => {
            let op = match (funct7, funct3) {
                (0, 0) => Op::Add,
                (0x20, 0) => Op::Sub,
                (0, 1) => Op::Sll,
                (0, 2) => Op::Slt,
                (0, 3) => Op::Sltu,
                (0, 4) => Op::Xor,
                (0, 5) => Op::Srl,
                (0x20, 5) => Op::Sra,
                (0, 6) => Op::Or,
                (0, 7) => Op::And,
                (1, 0) => Op::Mul,
                (1, 1) => Op::Mulh,
                (1, 2) => Op::Mulhsu,
                (1, 3) => Op::Mulhu,
                (1, 4) => Op::Div,
                (1, 5) => Op::Divu,
                (1, 6) => Op::Rem,
                (1, 7) => Op::Remu,
                _ => return illegal(),
            };
            Ok(DecodedInstruction::Register { op, rd, rs1, rs2 })
        }
        0x73 if word == 0x0000_0073 => Ok(DecodedInstruction::Ecall),
        0x73 if word == 0x0010_0073 => Ok(DecodedInstruction::Ebreak),
        0x73 if word == 0x3020_0073 => Ok(DecodedInstruction::Mret),
        0x73 => {
            let operation = match funct3 {
                1 | 5 => CsrOperation::Write,
                2 | 6 => CsrOperation::Set,
                3 | 7 => CsrOperation::Clear,
                _ => return illegal(),
            };
            let source = if funct3 >= 5 {
                CsrSource::Immediate(rs1 as u8)
            } else {
                CsrSource::Register(rs1)
            };
            Ok(DecodedInstruction::Csr {
                operation,
                rd,
                csr: (word >> 20) as u16,
                source,
            })
        }
        _ => illegal(),
    }
}
