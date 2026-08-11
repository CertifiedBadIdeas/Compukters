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

const ELF32_HEADER_SIZE: usize = 52;
const ELF32_PROGRAM_HEADER_SIZE: usize = 32;
const PAGE_SIZE: usize = 4096;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LoadSegment {
    virtual_address: u32,
    bytes: Vec<u8>,
    memory_size: u32,
    flags: u32,
}

impl LoadSegment {
    pub fn rx(virtual_address: u32, bytes: impl Into<Vec<u8>>) -> Self {
        let bytes = bytes.into();
        Self {
            virtual_address,
            memory_size: bytes.len() as u32,
            bytes,
            flags: 0b101,
        }
    }

    pub fn rw_with_mem_size(
        virtual_address: u32,
        bytes: impl Into<Vec<u8>>,
        memory_size: u32,
    ) -> Self {
        Self {
            virtual_address,
            bytes: bytes.into(),
            memory_size,
            flags: 0b110,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Elf32Builder {
    entry_point: u32,
    segments: Vec<LoadSegment>,
}

impl Elf32Builder {
    pub fn new(entry_point: u32) -> Self {
        Self {
            entry_point,
            segments: Vec::new(),
        }
    }

    pub fn load(mut self, segment: LoadSegment) -> Self {
        self.segments.push(segment);
        self
    }

    pub fn finish(self) -> Vec<u8> {
        let headers_end = ELF32_HEADER_SIZE + self.segments.len() * ELF32_PROGRAM_HEADER_SIZE;
        let mut next_file_offset = align_up(headers_end, PAGE_SIZE);
        let mut segment_offsets = Vec::with_capacity(self.segments.len());
        for segment in &self.segments {
            let page_offset = segment.virtual_address as usize % PAGE_SIZE;
            next_file_offset = align_up(next_file_offset, PAGE_SIZE) + page_offset;
            segment_offsets.push(next_file_offset);
            next_file_offset += segment.bytes.len();
        }

        let mut elf = vec![0_u8; next_file_offset];
        elf[0..4].copy_from_slice(b"\x7fELF");
        elf[4] = 1;
        elf[5] = 1;
        elf[6] = 1;
        put_u16(&mut elf, 16, 2);
        put_u16(&mut elf, 18, 243);
        put_u32(&mut elf, 20, 1);
        put_u32(&mut elf, 24, self.entry_point);
        put_u32(&mut elf, 28, ELF32_HEADER_SIZE as u32);
        put_u16(&mut elf, 40, ELF32_HEADER_SIZE as u16);
        put_u16(&mut elf, 42, ELF32_PROGRAM_HEADER_SIZE as u16);
        put_u16(&mut elf, 44, self.segments.len() as u16);

        for (index, (segment, file_offset)) in self
            .segments
            .iter()
            .zip(segment_offsets.iter().copied())
            .enumerate()
        {
            let header = ELF32_HEADER_SIZE + index * ELF32_PROGRAM_HEADER_SIZE;
            put_u32(&mut elf, header, 1);
            put_u32(&mut elf, header + 4, file_offset as u32);
            put_u32(&mut elf, header + 8, segment.virtual_address);
            put_u32(&mut elf, header + 12, segment.virtual_address);
            put_u32(&mut elf, header + 16, segment.bytes.len() as u32);
            put_u32(&mut elf, header + 20, segment.memory_size);
            put_u32(&mut elf, header + 24, segment.flags);
            put_u32(&mut elf, header + 28, PAGE_SIZE as u32);
            elf[file_offset..file_offset + segment.bytes.len()].copy_from_slice(&segment.bytes);
        }
        elf
    }
}

pub fn halting_machine_elf(marker: u8) -> Vec<u8> {
    use compukter_vm::rv32im::encoding::{addi, lui, sb, sw};

    let words = [
        lui(1, 0x10000),
        addi(2, 1, 0x100),
        addi(3, 0, i32::from(marker)),
        sb(2, 3, 0),
        sw(1, 0, 8),
        addi(4, 0, 3),
        sw(1, 4, 0),
    ];
    machine_program_elf(&words)
}

pub fn machine_program_elf(words: &[u32]) -> Vec<u8> {
    let code = words
        .iter()
        .copied()
        .flat_map(u32::to_le_bytes)
        .collect::<Vec<_>>();
    Elf32Builder::new(0x1000)
        .load(LoadSegment::rx(0x1000, code))
        .load(LoadSegment::rw_with_mem_size(0x3000, [], 0x1000))
        .finish()
}

fn align_up(value: usize, alignment: usize) -> usize {
    value.div_ceil(alignment) * alignment
}

fn put_u16(bytes: &mut [u8], offset: usize, value: u16) {
    bytes[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
}

fn put_u32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}
