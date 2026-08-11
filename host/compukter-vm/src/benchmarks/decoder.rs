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

use crate::bus::MachineBus;
use crate::rv32im::encoding as e;
use crate::rv32im::{
    decode_eager_reference, decode_product_word, BoundedCachedRv32imProgram, DecodedInstruction,
    Rv32ResolvedInstruction,
};
use std::hint::black_box;
use std::mem::size_of;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DecoderBenchmarkImplementation {
    Eager,
    Product,
}

impl DecoderBenchmarkImplementation {
    pub const fn all() -> &'static [Self] {
        &[Self::Eager, Self::Product]
    }

    pub const fn name(self) -> &'static str {
        match self {
            Self::Eager => "eager",
            Self::Product => "opcode-first",
        }
    }

    fn decoder(self) -> fn(u32) -> Result<DecodedInstruction, String> {
        match self {
            Self::Eager => decode_eager_reference,
            Self::Product => decode_product_word,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DecoderBenchmarkScenario {
    LegalDecode,
    BoundedCacheForcedMiss,
}

impl DecoderBenchmarkScenario {
    pub const fn all() -> &'static [Self] {
        &[Self::LegalDecode, Self::BoundedCacheForcedMiss]
    }

    pub const fn name(self) -> &'static str {
        match self {
            Self::LegalDecode => "legal-decode",
            Self::BoundedCacheForcedMiss => "bounded-cache-forced-miss",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DecoderBenchmarkObservation {
    pub implementation: DecoderBenchmarkImplementation,
    pub scenario: DecoderBenchmarkScenario,
    pub operations: u32,
    pub checksum: u64,
    pub retained_bytes: usize,
    pub cache_misses: u64,
}

pub struct PreparedDecoderBenchmark {
    implementation: DecoderBenchmarkImplementation,
    scenario: DecoderBenchmarkScenario,
    operations: u32,
    words: Vec<u32>,
    bus: MachineBus,
    cache: BoundedCachedRv32imProgram,
}

impl PreparedDecoderBenchmark {
    pub fn new(
        implementation: DecoderBenchmarkImplementation,
        scenario: DecoderBenchmarkScenario,
        operations: u32,
    ) -> Result<Self, String> {
        if operations == 0 {
            return Err("decoder benchmark operations must be positive".to_string());
        }
        let words = legal_words();
        let mut bus = MachineBus::new(12).map_err(|error| error.to_string())?;
        for (index, word) in words.iter().take(3).enumerate() {
            bus.store_i32((index * 4) as u32, *word as i32)
                .map_err(|error| error.to_string())?;
        }
        Ok(Self {
            implementation,
            scenario,
            operations,
            words,
            bus,
            cache: BoundedCachedRv32imProgram::new(1)?,
        })
    }

    pub fn execute(&mut self) -> Result<DecoderBenchmarkObservation, String> {
        let decoder = self.implementation.decoder();
        let (checksum, cache_misses) = match self.scenario {
            DecoderBenchmarkScenario::LegalDecode => {
                let mut checksum = 0_u64;
                for index in 0..self.operations {
                    let word = self.words[index as usize % self.words.len()];
                    let instruction = decoder(word)?;
                    black_box(instruction);
                    checksum = checksum.rotate_left(7) ^ u64::from(word);
                    checksum = checksum.wrapping_add(1);
                }
                (checksum, 0)
            }
            DecoderBenchmarkScenario::BoundedCacheForcedMiss => {
                self.cache.reset_for_benchmark();
                let mut checksum = 0_u64;
                for index in 0..self.operations {
                    let pc = (index % 3) * 4;
                    let resolved = self
                        .cache
                        .resolve_with_decoder(pc, &self.bus, decoder)
                        .map_err(|error| error.to_string())?;
                    let word = match black_box(resolved) {
                        Rv32ResolvedInstruction::Valid { word, .. } => word,
                        Rv32ResolvedInstruction::Invalid { word } => {
                            return Err(format!(
                                "legal forced-miss word decoded as illegal: {word:#010x}"
                            ));
                        }
                    };
                    checksum = checksum.rotate_left(7) ^ u64::from(word);
                    checksum = checksum.wrapping_add(1);
                }
                (checksum, self.cache.stats().misses)
            }
        };
        Ok(DecoderBenchmarkObservation {
            implementation: self.implementation,
            scenario: self.scenario,
            operations: self.operations,
            checksum,
            retained_bytes: self.words.capacity() * size_of::<u32>()
                + self.bus.memory().len()
                + self.cache.retained_bytes(),
            cache_misses,
        })
    }
}

fn legal_words() -> Vec<u32> {
    vec![
        e::lui(1, 0x12345),
        e::auipc(2, 0x23456),
        e::jal(3, 1024),
        e::jalr(4, 5, -128),
        e::beq(6, 7, 64),
        e::bne(7, 8, -64),
        e::blt(8, 9, 32),
        e::bgeu(9, 10, -32),
        e::lb(11, 12, 7),
        e::lh(12, 13, -8),
        e::lw(13, 14, 12),
        e::lbu(14, 15, 13),
        e::lhu(15, 16, -14),
        e::sb(17, 18, 15),
        e::sh(18, 19, -16),
        e::sw(19, 20, 20),
        e::addi(20, 21, -21),
        e::sltiu(21, 22, 22),
        e::xori(22, 23, 0x155),
        e::andi(23, 24, 0x2aa),
        e::slli(24, 25, 7),
        e::srai(25, 26, 11),
        e::add(26, 27, 28),
        e::sub(27, 28, 29),
        e::mul(28, 29, 30),
        e::divu(29, 30, 31),
        e::fence(),
        e::fence_i(),
        e::lr_w(30, 29, true, false),
        e::sc_w(31, 30, 29, false, true),
        e::amoadd_w(1, 2, 3, true, true),
        e::csrrs(2, 0xc00, 3),
    ]
}
