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

#![allow(
    dead_code,
    reason = "the planner is introduced before the machine-owned JIT dispatcher"
)]

use crate::rv32im::{DecodedInstruction, ImmOp, Op, Rv32ResolvedInstruction};

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct JitBlockSlot {
    pc: u32,
    word: u32,
    instruction: DecodedInstruction,
}

impl JitBlockSlot {
    pub(crate) fn pc(&self) -> u32 {
        self.pc
    }

    pub(crate) fn word(&self) -> u32 {
        self.word
    }

    pub(crate) fn instruction(&self) -> DecodedInstruction {
        self.instruction
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct JitBlockInput {
    start_pc: u32,
    source_page: u32,
    slots: Vec<JitBlockSlot>,
}

impl JitBlockInput {
    pub(crate) fn from_resolved(
        start_pc: u32,
        slots: Vec<Rv32ResolvedInstruction>,
    ) -> Result<Self, String> {
        if slots.is_empty() {
            return Err("RV32 JIT block input cannot be empty".to_string());
        }
        let mut owned_slots = Vec::with_capacity(slots.len());
        for (index, slot) in slots.into_iter().enumerate() {
            let pc = start_pc.wrapping_add((index as u32) * 4);
            let Rv32ResolvedInstruction::Valid { word, instruction } = slot else {
                return Err(format!(
                    "RV32 JIT block at {pc:#010x} contains an invalid instruction"
                ));
            };
            owned_slots.push(JitBlockSlot {
                pc,
                word,
                instruction,
            });
        }
        Ok(Self {
            start_pc,
            source_page: start_pc & !0xfff,
            slots: owned_slots,
        })
    }

    pub(crate) fn start_pc(&self) -> u32 {
        self.start_pc
    }

    pub(crate) fn source_page(&self) -> u32 {
        self.source_page
    }

    pub(crate) fn slots(&self) -> &[JitBlockSlot] {
        &self.slots
    }

    pub(crate) fn is_supported(&self) -> bool {
        self.slots.iter().all(|slot| {
            matches!(
                slot.instruction,
                DecodedInstruction::Immediate { op: ImmOp::Add, .. }
                    | DecodedInstruction::Register { op: Op::Add, .. }
            )
        })
    }
}

#[cfg(test)]
mod tests {
    use super::JitBlockInput;
    use crate::rv32im::{decode_product_word, encoding::addi, Rv32ResolvedInstruction};

    #[test]
    fn block_input_keeps_each_slot_pc_word_and_decoded_instruction() {
        let word = addi(7, 7, 1);
        let input = JitBlockInput::from_resolved(
            0x1000,
            vec![Rv32ResolvedInstruction::Valid {
                word,
                instruction: decode_product_word(word).unwrap(),
            }],
        )
        .unwrap();

        assert_eq!(input.start_pc(), 0x1000);
        assert_eq!(input.source_page(), 0x1000);
        assert_eq!(input.slots().len(), 1);
        assert_eq!(input.slots()[0].pc(), 0x1000);
        assert_eq!(input.slots()[0].word(), word);
        assert_eq!(
            input.slots()[0].instruction(),
            decode_product_word(word).unwrap()
        );
        assert!(input.is_supported());
    }
}
