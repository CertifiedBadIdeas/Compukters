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

#[path = "support/rv32_elf.rs"]
#[allow(dead_code)]
mod rv32_elf_support;

use compukter_vm::rv32_machine::{Rv32ElfErrorKind, Rv32ElfLoader, Rv32PagePermissions};
use rv32_elf_support::{Elf32Builder, LoadSegment};

#[test]
fn valid_elf_loads_segments_zero_fills_bss_and_records_exact_permissions() {
    let elf = Elf32Builder::new(0x1000)
        .load(LoadSegment::rx(0x1000, [0x13, 0, 0, 0]))
        .load(LoadSegment::rw_with_mem_size(0x3000, [1, 2, 3, 4], 16))
        .finish();

    let image = Rv32ElfLoader::load(&elf, 0x5000).unwrap();
    assert_eq!(image.entry_point(), 0x1000);
    assert_eq!(&image.ram()[0x1000..0x1004], &[0x13, 0, 0, 0]);
    assert_eq!(&image.ram()[0x3000..0x3004], &[1, 2, 3, 4]);
    assert_eq!(&image.ram()[0x3004..0x3010], &[0; 12]);
    assert_eq!(
        image.page_permissions(0x1000),
        Rv32PagePermissions::READ_EXECUTE
    );
    assert_eq!(
        image.page_permissions(0x3000),
        Rv32PagePermissions::READ_WRITE
    );
    assert_eq!(image.executable_ranges(), &[0x1000..0x1004]);
}

#[test]
fn malformed_or_unsupported_elf_is_rejected_before_an_image_is_returned() {
    for case in invalid_cases() {
        let error = Rv32ElfLoader::load(&case.bytes, case.ram_size)
            .unwrap_err_or_else(|| panic!("{} unexpectedly loaded", case.name));
        assert_eq!(error.kind(), case.expected_kind, "{}: {error}", case.name);
    }
}

struct InvalidCase {
    name: &'static str,
    bytes: Vec<u8>,
    ram_size: usize,
    expected_kind: Rv32ElfErrorKind,
}

fn invalid_cases() -> Vec<InvalidCase> {
    let valid = || {
        Elf32Builder::new(0x1000)
            .load(LoadSegment::rx(0x1000, [0x13, 0, 0, 0]))
            .finish()
    };
    let changed_u8 = |offset, value| {
        let mut bytes = valid();
        bytes[offset] = value;
        bytes
    };
    let changed_u16 = |offset, value| {
        let mut bytes = valid();
        put_u16(&mut bytes, offset, value);
        bytes
    };
    let changed_u32 = |offset, value| {
        let mut bytes = valid();
        put_u32(&mut bytes, offset, value);
        bytes
    };
    let program = 52;
    let mut truncated_program_table = valid();
    truncated_program_table.truncate(program + 8);
    let mut outside_ram = valid();
    put_u32(&mut outside_ram, program + 8, 0x5000);
    put_u32(&mut outside_ram, program + 12, 0x5000);
    let mut overflowing_virtual_range = valid();
    put_u32(&mut overflowing_virtual_range, program + 8, 0xffff_fff0);
    put_u32(&mut overflowing_virtual_range, program + 12, 0xffff_fff0);
    put_u32(&mut overflowing_virtual_range, program + 20, 0x20);

    vec![
        invalid("magic", changed_u8(0, 0), Rv32ElfErrorKind::Header),
        invalid("class", changed_u8(4, 2), Rv32ElfErrorKind::Header),
        invalid("endianness", changed_u8(5, 2), Rv32ElfErrorKind::Header),
        invalid("ident version", changed_u8(6, 0), Rv32ElfErrorKind::Header),
        invalid("file type", changed_u16(16, 1), Rv32ElfErrorKind::Header),
        invalid("machine", changed_u16(18, 62), Rv32ElfErrorKind::Header),
        invalid("ELF version", changed_u32(20, 0), Rv32ElfErrorKind::Header),
        invalid("ELF flags", changed_u32(36, 1), Rv32ElfErrorKind::Header),
        invalid("header size", changed_u16(40, 51), Rv32ElfErrorKind::Header),
        invalid(
            "program header size",
            changed_u16(42, 31),
            Rv32ElfErrorKind::ProgramHeader,
        ),
        invalid(
            "program table overflow",
            changed_u32(28, u32::MAX - 4),
            Rv32ElfErrorKind::ProgramHeader,
        ),
        invalid(
            "truncated program table",
            truncated_program_table,
            Rv32ElfErrorKind::ProgramHeader,
        ),
        invalid(
            "unsupported program type",
            changed_u32(program, 3),
            Rv32ElfErrorKind::ProgramHeader,
        ),
        invalid(
            "unknown segment flags",
            changed_u32(program + 24, 0b1100),
            Rv32ElfErrorKind::Segment,
        ),
        invalid(
            "non-readable segment",
            changed_u32(program + 24, 0b010),
            Rv32ElfErrorKind::Segment,
        ),
        invalid(
            "physical address mismatch",
            changed_u32(program + 12, 0x2000),
            Rv32ElfErrorKind::Segment,
        ),
        invalid(
            "file size exceeds memory size",
            changed_u32(program + 20, 3),
            Rv32ElfErrorKind::Segment,
        ),
        invalid(
            "file range outside artifact",
            changed_u32(program + 4, u32::MAX - 1),
            Rv32ElfErrorKind::Segment,
        ),
        invalid(
            "memory range outside RAM",
            outside_ram,
            Rv32ElfErrorKind::Segment,
        ),
        invalid(
            "virtual range overflow",
            overflowing_virtual_range,
            Rv32ElfErrorKind::Segment,
        ),
        invalid(
            "non-power-of-two alignment",
            changed_u32(program + 28, 3),
            Rv32ElfErrorKind::Segment,
        ),
        invalid(
            "alignment incongruence",
            changed_u32(program + 4, 0x1001),
            Rv32ElfErrorKind::Segment,
        ),
        invalid(
            "writable executable segment",
            changed_u32(program + 24, 0b111),
            Rv32ElfErrorKind::Permissions,
        ),
        invalid(
            "misaligned executable segment",
            Elf32Builder::new(0x1004)
                .load(LoadSegment::rx(0x1002, [0x13, 0, 0, 0]))
                .finish(),
            Rv32ElfErrorKind::Segment,
        ),
        invalid(
            "overlapping load segments",
            Elf32Builder::new(0x1000)
                .load(LoadSegment::rx(0x1000, [0x13; 8]))
                .load(LoadSegment::rx(0x1004, [0x13; 4]))
                .finish(),
            Rv32ElfErrorKind::Segment,
        ),
        invalid(
            "page permission widening",
            Elf32Builder::new(0x1000)
                .load(LoadSegment::rx(0x1000, [0x13; 4]))
                .load(LoadSegment::rw_with_mem_size(0x1100, [1; 4], 4))
                .finish(),
            Rv32ElfErrorKind::Permissions,
        ),
        invalid(
            "misaligned entry",
            changed_u32(24, 0x1002),
            Rv32ElfErrorKind::EntryPoint,
        ),
        invalid(
            "entry outside executable range",
            changed_u32(24, 0x2000),
            Rv32ElfErrorKind::EntryPoint,
        ),
        invalid(
            "missing load segments",
            changed_u32(program, 0),
            Rv32ElfErrorKind::Segment,
        ),
    ]
}

fn invalid(name: &'static str, bytes: Vec<u8>, expected_kind: Rv32ElfErrorKind) -> InvalidCase {
    InvalidCase {
        name,
        bytes,
        ram_size: 0x5000,
        expected_kind,
    }
}

fn put_u16(bytes: &mut [u8], offset: usize, value: u16) {
    bytes[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
}

fn put_u32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

trait UnwrapErrOrElse<T, E> {
    fn unwrap_err_or_else(self, f: impl FnOnce() -> E) -> E;
}

impl<T, E> UnwrapErrOrElse<T, E> for Result<T, E> {
    fn unwrap_err_or_else(self, f: impl FnOnce() -> E) -> E {
        match self {
            Ok(_) => f(),
            Err(error) => error,
        }
    }
}
