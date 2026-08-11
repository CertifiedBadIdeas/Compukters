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

use super::atomic::{AtomicOp, MemoryOrdering};

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
    Fence,
    FenceI,
    LoadReserved {
        rd: usize,
        rs1: usize,
        ordering: MemoryOrdering,
    },
    StoreConditional {
        rd: usize,
        rs1: usize,
        rs2: usize,
        ordering: MemoryOrdering,
    },
    Atomic {
        operation: AtomicOp,
        rd: usize,
        rs1: usize,
        rs2: usize,
        ordering: MemoryOrdering,
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

pub(crate) fn decode_eager_reference(word: u32) -> Result<DecodedInstruction, String> {
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
        0x0f if funct3 == 0 => Ok(DecodedInstruction::Fence),
        0x0f if funct3 == 1 => Ok(DecodedInstruction::FenceI),
        0x2f if funct3 == 0b010 => {
            let ordering = MemoryOrdering {
                acquire: word & (1 << 26) != 0,
                release: word & (1 << 25) != 0,
            };
            match word >> 27 {
                0b00010 if rs2 == 0 => Ok(DecodedInstruction::LoadReserved { rd, rs1, ordering }),
                0b00011 => Ok(DecodedInstruction::StoreConditional {
                    rd,
                    rs1,
                    rs2,
                    ordering,
                }),
                funct5 => {
                    let operation = match funct5 {
                        0b00001 => AtomicOp::Swap,
                        0b00000 => AtomicOp::Add,
                        0b00100 => AtomicOp::Xor,
                        0b01100 => AtomicOp::And,
                        0b01000 => AtomicOp::Or,
                        0b10000 => AtomicOp::Min,
                        0b10100 => AtomicOp::Max,
                        0b11000 => AtomicOp::MinU,
                        0b11100 => AtomicOp::MaxU,
                        _ => return illegal(),
                    };
                    Ok(DecodedInstruction::Atomic {
                        operation,
                        rd,
                        rs1,
                        rs2,
                        ordering,
                    })
                }
            }
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

#[inline]
fn rd(word: u32) -> usize {
    ((word >> 7) & 31) as usize
}

#[inline]
fn funct3(word: u32) -> u32 {
    (word >> 12) & 7
}

#[inline]
fn rs1(word: u32) -> usize {
    ((word >> 15) & 31) as usize
}

#[inline]
fn rs2(word: u32) -> usize {
    ((word >> 20) & 31) as usize
}

#[inline]
fn funct7(word: u32) -> u32 {
    (word >> 25) & 0x7f
}

#[inline]
fn i_immediate(word: u32) -> i32 {
    (word as i32) >> 20
}

#[inline]
fn s_immediate(word: u32) -> i32 {
    let raw = ((word >> 7) & 0x1f) | ((word >> 25) << 5);
    ((raw << 20) as i32) >> 20
}

#[inline]
fn b_immediate(word: u32) -> i32 {
    let raw = ((word >> 31) << 12)
        | (((word >> 7) & 1) << 11)
        | (((word >> 25) & 0x3f) << 5)
        | (((word >> 8) & 0x0f) << 1);
    ((raw << 19) as i32) >> 19
}

#[inline]
fn j_immediate(word: u32) -> i32 {
    let raw = ((word >> 31) << 20)
        | (((word >> 12) & 0xff) << 12)
        | (((word >> 20) & 1) << 11)
        | (((word >> 21) & 0x3ff) << 1);
    ((raw << 11) as i32) >> 11
}

pub(crate) fn decode(word: u32) -> Result<DecodedInstruction, String> {
    let opcode = word & 0x7f;
    let illegal = || Err(format!("illegal RV32IM instruction {word:#010x}"));
    match opcode {
        0x37 => Ok(DecodedInstruction::Lui {
            rd: rd(word),
            value: word & 0xffff_f000,
        }),
        0x17 => Ok(DecodedInstruction::Auipc {
            rd: rd(word),
            value: word & 0xffff_f000,
        }),
        0x6f => Ok(DecodedInstruction::Jal {
            rd: rd(word),
            offset: j_immediate(word),
        }),
        0x67 => {
            if funct3(word) != 0 {
                return illegal();
            }
            Ok(DecodedInstruction::Jalr {
                rd: rd(word),
                rs1: rs1(word),
                immediate: i_immediate(word),
            })
        }
        0x63 => {
            let kind = match funct3(word) {
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
                rs1: rs1(word),
                rs2: rs2(word),
                offset: b_immediate(word),
            })
        }
        0x03 => {
            let kind = match funct3(word) {
                0 => Load::Byte,
                1 => Load::Half,
                2 => Load::Word,
                4 => Load::ByteU,
                5 => Load::HalfU,
                _ => return illegal(),
            };
            Ok(DecodedInstruction::Load {
                kind,
                rd: rd(word),
                rs1: rs1(word),
                immediate: i_immediate(word),
            })
        }
        0x23 => {
            let kind = match funct3(word) {
                0 => Store::Byte,
                1 => Store::Half,
                2 => Store::Word,
                _ => return illegal(),
            };
            Ok(DecodedInstruction::Store {
                kind,
                rs1: rs1(word),
                rs2: rs2(word),
                immediate: s_immediate(word),
            })
        }
        0x13 => {
            let instruction_funct3 = funct3(word);
            let (op, immediate) = match instruction_funct3 {
                0 => (ImmOp::Add, i_immediate(word)),
                2 => (ImmOp::Slt, i_immediate(word)),
                3 => (ImmOp::Sltu, i_immediate(word)),
                4 => (ImmOp::Xor, i_immediate(word)),
                6 => (ImmOp::Or, i_immediate(word)),
                7 => (ImmOp::And, i_immediate(word)),
                1 if funct7(word) == 0 => (ImmOp::Sll, rs2(word) as i32),
                5 if funct7(word) == 0 => (ImmOp::Srl, rs2(word) as i32),
                5 if funct7(word) == 0x20 => (ImmOp::Sra, rs2(word) as i32),
                _ => return illegal(),
            };
            Ok(DecodedInstruction::Immediate {
                op,
                rd: rd(word),
                rs1: rs1(word),
                immediate,
            })
        }
        0x33 => {
            let op = match (funct7(word), funct3(word)) {
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
            Ok(DecodedInstruction::Register {
                op,
                rd: rd(word),
                rs1: rs1(word),
                rs2: rs2(word),
            })
        }
        0x0f => match funct3(word) {
            0 => Ok(DecodedInstruction::Fence),
            1 => Ok(DecodedInstruction::FenceI),
            _ => illegal(),
        },
        0x2f => {
            if funct3(word) != 0b010 {
                return illegal();
            }
            let ordering = MemoryOrdering {
                acquire: word & (1 << 26) != 0,
                release: word & (1 << 25) != 0,
            };
            let instruction_rs2 = rs2(word);
            match word >> 27 {
                0b00010 if instruction_rs2 == 0 => Ok(DecodedInstruction::LoadReserved {
                    rd: rd(word),
                    rs1: rs1(word),
                    ordering,
                }),
                0b00011 => Ok(DecodedInstruction::StoreConditional {
                    rd: rd(word),
                    rs1: rs1(word),
                    rs2: instruction_rs2,
                    ordering,
                }),
                funct5 => {
                    let operation = match funct5 {
                        0b00001 => AtomicOp::Swap,
                        0b00000 => AtomicOp::Add,
                        0b00100 => AtomicOp::Xor,
                        0b01100 => AtomicOp::And,
                        0b01000 => AtomicOp::Or,
                        0b10000 => AtomicOp::Min,
                        0b10100 => AtomicOp::Max,
                        0b11000 => AtomicOp::MinU,
                        0b11100 => AtomicOp::MaxU,
                        _ => return illegal(),
                    };
                    Ok(DecodedInstruction::Atomic {
                        operation,
                        rd: rd(word),
                        rs1: rs1(word),
                        rs2: instruction_rs2,
                        ordering,
                    })
                }
            }
        }
        0x73 if word == 0x0000_0073 => Ok(DecodedInstruction::Ecall),
        0x73 if word == 0x0010_0073 => Ok(DecodedInstruction::Ebreak),
        0x73 if word == 0x3020_0073 => Ok(DecodedInstruction::Mret),
        0x73 => {
            let instruction_funct3 = funct3(word);
            let operation = match instruction_funct3 {
                1 | 5 => CsrOperation::Write,
                2 | 6 => CsrOperation::Set,
                3 | 7 => CsrOperation::Clear,
                _ => return illegal(),
            };
            let instruction_rs1 = rs1(word);
            let source = if instruction_funct3 >= 5 {
                CsrSource::Immediate(instruction_rs1 as u8)
            } else {
                CsrSource::Register(instruction_rs1)
            };
            Ok(DecodedInstruction::Csr {
                operation,
                rd: rd(word),
                csr: (word >> 20) as u16,
                source,
            })
        }
        _ => illegal(),
    }
}

#[cfg(test)]
mod tests {
    use super::super::encoding as e;
    use super::{decode, decode_eager_reference, DecodedInstruction};

    fn normalized(result: Result<DecodedInstruction, String>) -> Result<DecodedInstruction, ()> {
        result.map_err(|_| ())
    }

    fn assert_matches_eager(word: u32) {
        assert_eq!(
            normalized(decode(word)),
            normalized(decode_eager_reference(word)),
            "decoder mismatch for {word:#010x}",
        );
    }

    fn architectural_words() -> Vec<u32> {
        let mut words = vec![
            e::lui(0, 0),
            e::lui(31, 0x000f_ffff),
            e::auipc(0, 0),
            e::auipc(31, 0x000f_ffff),
            e::jal(0, -(1 << 20)),
            e::jal(31, (1 << 20) - 2),
            e::jalr(0, 31, -2048),
            e::jalr(31, 0, 2047),
            e::beq(0, 31, -4096),
            e::bne(31, 0, 4094),
            e::blt(1, 30, -2),
            e::bge(2, 29, 2),
            e::bltu(3, 28, -4),
            e::bgeu(4, 27, 4),
            e::lb(0, 31, -2048),
            e::lh(31, 0, 2047),
            e::lw(1, 30, -1),
            e::lbu(30, 1, 1),
            e::lhu(2, 29, 0),
            e::sb(0, 31, -2048),
            e::sh(31, 0, 2047),
            e::sw(1, 30, -1),
            e::addi(0, 31, -2048),
            e::slti(31, 0, 2047),
            e::sltiu(1, 30, -1),
            e::xori(30, 1, 1),
            e::ori(2, 29, 0),
            e::andi(29, 2, 0x555),
            e::slli(0, 31, 0),
            e::srli(31, 0, 31),
            e::srai(1, 30, 17),
            e::add(0, 31, 1),
            e::sub(31, 0, 30),
            e::sll(1, 30, 2),
            e::slt(30, 1, 29),
            e::sltu(2, 29, 3),
            e::xor(29, 2, 28),
            e::srl(3, 28, 4),
            e::sra(28, 3, 27),
            e::or(4, 27, 5),
            e::and(27, 4, 26),
            e::mul(5, 26, 6),
            e::mulh(26, 5, 25),
            e::mulhsu(6, 25, 7),
            e::mulhu(25, 6, 24),
            e::div(7, 24, 8),
            e::divu(24, 7, 23),
            e::rem(8, 23, 9),
            e::remu(23, 8, 22),
            e::ecall(),
            e::ebreak(),
            e::csrrw(0, 0, 31),
            e::csrrs(31, 0x0fff, 0),
            e::csrrc(1, 0x0555, 30),
            e::csrrwi(30, 0x0aaa, 31),
            e::csrrsi(2, 1, 0),
            e::csrrci(29, 0x0ffe, 17),
            e::mret(),
            e::fence(),
            e::fence_i(),
            0,
            u32::MAX,
            e::jalr(1, 2, 3) | (1 << 12),
            e::add(1, 2, 3) | (2 << 25),
            e::lr_w(1, 2, false, false) | (1 << 20),
        ];

        let atomic_encoders: [fn(u8, u8, u8, bool, bool) -> u32; 10] = [
            e::sc_w,
            e::amoswap_w,
            e::amoadd_w,
            e::amoxor_w,
            e::amoand_w,
            e::amoor_w,
            e::amomin_w,
            e::amomax_w,
            e::amominu_w,
            e::amomaxu_w,
        ];
        for acquire in [false, true] {
            for release in [false, true] {
                words.push(e::lr_w(31, 30, acquire, release));
                for encode in atomic_encoders {
                    words.push(encode(31, 30, 29, acquire, release));
                }
            }
        }
        words.extend(e::materialize(31, 0x8000_07ff));
        words
    }

    #[test]
    fn eager_reference_matches_architectural_words() {
        for word in architectural_words() {
            assert_matches_eager(word);
        }
    }

    #[test]
    fn eager_reference_matches_the_product_decoder_over_stratified_words() {
        let mut state = 0x6a09_e667_f3bc_c909_u64;
        for opcode in 0_u32..128 {
            for _ in 0..2048 {
                state ^= state >> 12;
                state ^= state << 25;
                state ^= state >> 27;
                let upper = state.wrapping_mul(0x2545_f491_4f6c_dd1d) as u32;
                let word = upper & !0x7f | opcode;
                assert_matches_eager(word);
            }
        }
    }
}
