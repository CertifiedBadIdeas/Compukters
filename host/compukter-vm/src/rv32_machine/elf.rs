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

use std::ops::Range;
use thiserror::Error;

const ELF32_HEADER_SIZE: usize = 52;
const ELF32_PROGRAM_HEADER_SIZE: usize = 32;
const ELFCLASS32: u8 = 1;
const ELFDATA2LSB: u8 = 1;
const EV_CURRENT: u8 = 1;
const ET_EXEC: u16 = 2;
const EM_RISCV: u16 = 243;
const PT_NULL: u32 = 0;
const PT_LOAD: u32 = 1;
const PT_PHDR: u32 = 6;
const PT_GNU_STACK: u32 = 0x6474_e551;
const PF_X: u32 = 1;
const PF_W: u32 = 2;
const PF_R: u32 = 4;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rv32PagePermissions(u8);

impl Rv32PagePermissions {
    pub const NONE: Self = Self(0);
    pub const READ: Self = Self(0b001);
    pub const READ_WRITE: Self = Self(0b011);
    pub const READ_EXECUTE: Self = Self(0b101);

    pub const fn readable(self) -> bool {
        self.0 & Self::READ.0 != 0
    }

    pub const fn writable(self) -> bool {
        self.0 & 0b010 != 0
    }

    pub const fn executable(self) -> bool {
        self.0 & 0b100 != 0
    }

    fn from_elf_flags(flags: u32) -> Self {
        Self(
            ((flags & PF_R != 0) as u8)
                | (((flags & PF_W != 0) as u8) << 1)
                | (((flags & PF_X != 0) as u8) << 2),
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rv32ElfErrorKind {
    Header,
    ProgramHeader,
    Segment,
    Permissions,
    EntryPoint,
}

#[derive(Debug, Clone, PartialEq, Eq, Error)]
#[error("{message}")]
pub struct Rv32ElfError {
    kind: Rv32ElfErrorKind,
    message: String,
}

impl Rv32ElfError {
    pub fn kind(&self) -> Rv32ElfErrorKind {
        self.kind
    }

    fn new(kind: Rv32ElfErrorKind, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rv32LoadedImage {
    entry_point: u32,
    ram: Vec<u8>,
    page_permissions: Vec<Rv32PagePermissions>,
    executable_ranges: Vec<Range<u32>>,
}

impl Rv32LoadedImage {
    pub fn entry_point(&self) -> u32 {
        self.entry_point
    }

    pub fn ram(&self) -> &[u8] {
        &self.ram
    }

    pub fn page_permissions(&self, address: u32) -> Rv32PagePermissions {
        self.page_permissions
            .get(address as usize / Rv32ElfLoader::PAGE_SIZE as usize)
            .copied()
            .unwrap_or(Rv32PagePermissions::NONE)
    }

    pub fn executable_ranges(&self) -> &[Range<u32>] {
        &self.executable_ranges
    }

    pub(super) fn page_table(&self) -> &[Rv32PagePermissions] {
        &self.page_permissions
    }

    pub(super) fn into_parts(self) -> (u32, Vec<u8>, Vec<Rv32PagePermissions>, Vec<Range<u32>>) {
        (
            self.entry_point,
            self.ram,
            self.page_permissions,
            self.executable_ranges,
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct LoadSegment {
    file_range: Range<usize>,
    memory_range: Range<usize>,
    permissions: Rv32PagePermissions,
}

pub struct Rv32ElfLoader;

impl Rv32ElfLoader {
    pub const PAGE_SIZE: u32 = 4096;

    pub fn load(bytes: &[u8], ram_size: usize) -> Result<Rv32LoadedImage, Rv32ElfError> {
        validate_ident_and_header(bytes)?;
        let entry_point = read_u32(bytes, 24, Rv32ElfErrorKind::Header)?;
        let program_offset = read_u32(bytes, 28, Rv32ElfErrorKind::Header)? as usize;
        let program_entry_size = read_u16(bytes, 42, Rv32ElfErrorKind::Header)? as usize;
        let program_count = read_u16(bytes, 44, Rv32ElfErrorKind::Header)? as usize;
        if program_entry_size != ELF32_PROGRAM_HEADER_SIZE {
            return Err(Rv32ElfError::new(
                Rv32ElfErrorKind::ProgramHeader,
                format!("ELF32 program header size is {program_entry_size}, expected {ELF32_PROGRAM_HEADER_SIZE}"),
            ));
        }
        let table_size = program_count
            .checked_mul(program_entry_size)
            .ok_or_else(|| {
                Rv32ElfError::new(
                    Rv32ElfErrorKind::ProgramHeader,
                    "ELF32 program header table size overflows",
                )
            })?;
        checked_range(
            bytes.len(),
            program_offset,
            table_size,
            Rv32ElfErrorKind::ProgramHeader,
        )?;

        let mut segments = Vec::new();
        let mut executable_ranges = Vec::new();
        let page_count = ram_size.div_ceil(Self::PAGE_SIZE as usize);
        let mut page_permissions = vec![Rv32PagePermissions::NONE; page_count];
        for index in 0..program_count {
            let header = program_offset + index * program_entry_size;
            let program_type = read_u32(bytes, header, Rv32ElfErrorKind::ProgramHeader)?;
            match program_type {
                PT_NULL | PT_PHDR | PT_GNU_STACK => continue,
                PT_LOAD => {}
                unsupported => {
                    return Err(Rv32ElfError::new(
                        Rv32ElfErrorKind::ProgramHeader,
                        format!(
                            "ELF32 program header {index} has unsupported type {unsupported:#010x}"
                        ),
                    ));
                }
            }
            let file_offset =
                read_u32(bytes, header + 4, Rv32ElfErrorKind::ProgramHeader)? as usize;
            let virtual_address_u32 = read_u32(bytes, header + 8, Rv32ElfErrorKind::ProgramHeader)?;
            let physical_address = read_u32(bytes, header + 12, Rv32ElfErrorKind::ProgramHeader)?;
            let file_size_u32 = read_u32(bytes, header + 16, Rv32ElfErrorKind::ProgramHeader)?;
            let memory_size_u32 = read_u32(bytes, header + 20, Rv32ElfErrorKind::ProgramHeader)?;
            let flags = read_u32(bytes, header + 24, Rv32ElfErrorKind::ProgramHeader)?;
            let alignment = read_u32(bytes, header + 28, Rv32ElfErrorKind::ProgramHeader)?;
            if physical_address != virtual_address_u32 {
                return Err(Rv32ElfError::new(
                    Rv32ElfErrorKind::Segment,
                    format!(
                        "ELF32 load segment {index} physical address {physical_address:#010x} differs from virtual address {virtual_address_u32:#010x}"
                    ),
                ));
            }
            if flags & !(PF_R | PF_W | PF_X) != 0 || flags & PF_R == 0 {
                return Err(Rv32ElfError::new(
                    Rv32ElfErrorKind::Segment,
                    format!("ELF32 load segment {index} has unsupported flags {flags:#x}"),
                ));
            }
            if flags & (PF_W | PF_X) == PF_W | PF_X {
                return Err(Rv32ElfError::new(
                    Rv32ElfErrorKind::Permissions,
                    format!("ELF32 load segment {index} is writable and executable"),
                ));
            }
            if alignment > 1 {
                if !alignment.is_power_of_two()
                    || file_offset as u32 % alignment != virtual_address_u32 % alignment
                {
                    return Err(Rv32ElfError::new(
                        Rv32ElfErrorKind::Segment,
                        format!("ELF32 load segment {index} has invalid alignment {alignment}"),
                    ));
                }
            }
            let file_size = file_size_u32 as usize;
            let memory_size = memory_size_u32 as usize;
            if file_size > memory_size {
                return Err(Rv32ElfError::new(
                    Rv32ElfErrorKind::Segment,
                    format!("ELF32 load segment {index} has file size larger than memory size"),
                ));
            }
            virtual_address_u32
                .checked_add(memory_size_u32)
                .ok_or_else(|| {
                    Rv32ElfError::new(
                        Rv32ElfErrorKind::Segment,
                        format!("ELF32 load segment {index} virtual range overflows u32"),
                    )
                })?;
            let virtual_address = virtual_address_u32 as usize;
            let file_range = checked_range(
                bytes.len(),
                file_offset,
                file_size,
                Rv32ElfErrorKind::Segment,
            )?;
            let memory_range = checked_range(
                ram_size,
                virtual_address,
                memory_size,
                Rv32ElfErrorKind::Segment,
            )?;
            if memory_range.is_empty() {
                continue;
            }
            if segments.iter().any(|existing: &LoadSegment| {
                memory_range.start < existing.memory_range.end
                    && existing.memory_range.start < memory_range.end
            }) {
                return Err(Rv32ElfError::new(
                    Rv32ElfErrorKind::Segment,
                    format!("ELF32 load segment {index} overlaps another load segment"),
                ));
            }
            let permissions = Rv32PagePermissions::from_elf_flags(flags);
            let first_page = memory_range.start / Self::PAGE_SIZE as usize;
            let last_page = (memory_range.end - 1) / Self::PAGE_SIZE as usize;
            for page in first_page..=last_page {
                let previous = page_permissions[page];
                if previous != Rv32PagePermissions::NONE && previous != permissions {
                    return Err(Rv32ElfError::new(
                        Rv32ElfErrorKind::Permissions,
                        format!("ELF32 load segment {index} widens page {page} permissions"),
                    ));
                }
                page_permissions[page] = permissions;
            }
            if permissions.executable() {
                if !memory_range.start.is_multiple_of(4) || !memory_range.end.is_multiple_of(4) {
                    return Err(Rv32ElfError::new(
                        Rv32ElfErrorKind::Segment,
                        format!(
                            "ELF32 executable segment {index} range {:#010x}..{:#010x} is not four-byte aligned",
                            memory_range.start, memory_range.end
                        ),
                    ));
                }
                executable_ranges.push(memory_range.start as u32..memory_range.end as u32);
            }
            segments.push(LoadSegment {
                file_range,
                memory_range,
                permissions,
            });
        }
        if segments.is_empty() {
            return Err(Rv32ElfError::new(
                Rv32ElfErrorKind::Segment,
                "ELF32 executable has no load segments",
            ));
        }
        if !entry_point.is_multiple_of(4)
            || !executable_ranges
                .iter()
                .any(|range| range.contains(&entry_point))
        {
            return Err(Rv32ElfError::new(
                Rv32ElfErrorKind::EntryPoint,
                format!("ELF32 entry point {entry_point:#010x} is not executable"),
            ));
        }

        let mut ram = vec![0_u8; ram_size];
        for segment in segments {
            let destination_end = segment.memory_range.start + segment.file_range.len();
            ram[segment.memory_range.start..destination_end]
                .copy_from_slice(&bytes[segment.file_range]);
            debug_assert!(segment.permissions.readable() || segment.memory_range.is_empty());
        }
        executable_ranges.sort_unstable_by_key(|range| range.start);
        Ok(Rv32LoadedImage {
            entry_point,
            ram,
            page_permissions,
            executable_ranges,
        })
    }
}

fn validate_ident_and_header(bytes: &[u8]) -> Result<(), Rv32ElfError> {
    checked_range(bytes.len(), 0, ELF32_HEADER_SIZE, Rv32ElfErrorKind::Header)?;
    if &bytes[0..4] != b"\x7fELF"
        || bytes[4] != ELFCLASS32
        || bytes[5] != ELFDATA2LSB
        || bytes[6] != EV_CURRENT
        || read_u16(bytes, 16, Rv32ElfErrorKind::Header)? != ET_EXEC
        || read_u16(bytes, 18, Rv32ElfErrorKind::Header)? != EM_RISCV
        || read_u32(bytes, 20, Rv32ElfErrorKind::Header)? != u32::from(EV_CURRENT)
        || read_u32(bytes, 36, Rv32ElfErrorKind::Header)? != 0
        || read_u16(bytes, 40, Rv32ElfErrorKind::Header)? as usize != ELF32_HEADER_SIZE
    {
        return Err(Rv32ElfError::new(
            Rv32ElfErrorKind::Header,
            "unsupported ELF32 executable header",
        ));
    }
    Ok(())
}

fn read_u16(bytes: &[u8], offset: usize, kind: Rv32ElfErrorKind) -> Result<u16, Rv32ElfError> {
    let range = checked_range(bytes.len(), offset, 2, kind)?;
    Ok(u16::from_le_bytes(bytes[range].try_into().unwrap()))
}

fn read_u32(bytes: &[u8], offset: usize, kind: Rv32ElfErrorKind) -> Result<u32, Rv32ElfError> {
    let range = checked_range(bytes.len(), offset, 4, kind)?;
    Ok(u32::from_le_bytes(bytes[range].try_into().unwrap()))
}

fn checked_range(
    total: usize,
    start: usize,
    size: usize,
    kind: Rv32ElfErrorKind,
) -> Result<Range<usize>, Rv32ElfError> {
    let end = start
        .checked_add(size)
        .ok_or_else(|| Rv32ElfError::new(kind, format!("ELF32 range {start}+{size} overflows")))?;
    if end > total {
        return Err(Rv32ElfError::new(
            kind,
            format!("ELF32 range {start}..{end} exceeds {total} bytes"),
        ));
    }
    Ok(start..end)
}
