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

use k16_vm::low_bus::MachineBus;
use k16_vm::rv64im::{PredecodedRv64imProgram, Rv64imCpu, Rv64imStop};

fn r(funct7: u32, rs2: usize, rs1: usize, funct3: u32, rd: usize, opcode: u32) -> u32 {
    (funct7 << 25)
        | ((rs2 as u32) << 20)
        | ((rs1 as u32) << 15)
        | (funct3 << 12)
        | ((rd as u32) << 7)
        | opcode
}

fn i(immediate: i32, rs1: usize, funct3: u32, rd: usize, opcode: u32) -> u32 {
    (((immediate as u32) & 0xfff) << 20)
        | ((rs1 as u32) << 15)
        | (funct3 << 12)
        | ((rd as u32) << 7)
        | opcode
}

fn s(immediate: i32, rs2: usize, rs1: usize, funct3: u32) -> u32 {
    let immediate = immediate as u32 & 0xfff;
    ((immediate >> 5) << 25)
        | ((rs2 as u32) << 20)
        | ((rs1 as u32) << 15)
        | (funct3 << 12)
        | ((immediate & 0x1f) << 7)
        | 0x23
}

fn write_program(bus: &mut MachineBus, words: &[u32]) {
    for (index, word) in words.iter().copied().enumerate() {
        bus.store_i32(index as u32 * 4, word as i32).unwrap();
    }
}

#[test]
fn rv64im_executes_64_bit_memory_and_sign_extends_word_results() {
    let words = [
        i(128, 0, 0, 1, 0x13),  // addi x1, x0, 128
        i(-1, 0, 0, 2, 0x13),   // addi x2, x0, -1
        s(0, 2, 1, 3),          // sd x2, 0(x1)
        i(0, 1, 3, 3, 0x03),    // ld x3, 0(x1)
        i(0, 1, 6, 4, 0x03),    // lwu x4, 0(x1)
        i(1, 0, 0, 5, 0x1b),    // addiw x5, x0, 1
        i(31, 5, 1, 5, 0x1b),   // slliw x5, x5, 31
        i(1, 5, 0, 6, 0x1b),    // addiw x6, x5, 1
        r(1, 6, 5, 0, 7, 0x3b), // mulw x7, x5, x6
        0x0010_0073,            // ebreak
    ];
    let mut bus = MachineBus::new(256).unwrap();
    write_program(&mut bus, &words);
    let mut cpu = Rv64imCpu::new(0);

    assert_eq!(
        cpu.run_until_stop(&mut bus, 32).unwrap(),
        Rv64imStop::Ebreak
    );
    assert_eq!(cpu.register(0), 0);
    assert_eq!(cpu.register(3), u64::MAX);
    assert_eq!(cpu.register(4), u32::MAX as u64);
    assert_eq!(cpu.register(5), 0xffff_ffff_8000_0000);
    assert_eq!(cpu.register(6), 0xffff_ffff_8000_0001);
    assert_eq!(cpu.register(7), 0xffff_ffff_8000_0000);
}

#[test]
fn rv64m_and_rv64m_word_division_follow_ratified_corner_cases() {
    let words = [
        r(1, 2, 1, 4, 3, 0x33), // div x3, x1, x2
        r(1, 2, 1, 6, 4, 0x33), // rem x4, x1, x2
        r(1, 0, 1, 4, 5, 0x33), // div x5, x1, x0
        r(1, 0, 1, 6, 6, 0x33), // rem x6, x1, x0
        r(1, 2, 1, 4, 7, 0x3b), // divw x7, x1, x2
        r(1, 0, 1, 5, 8, 0x3b), // divuw x8, x1, x0
        0x0010_0073,
    ];
    let mut bus = MachineBus::new(128).unwrap();
    write_program(&mut bus, &words);
    let mut cpu = Rv64imCpu::new(0);
    cpu.set_register(1, i64::MIN as u64).unwrap();
    cpu.set_register(2, u64::MAX).unwrap();

    assert_eq!(
        cpu.run_until_stop(&mut bus, 16).unwrap(),
        Rv64imStop::Ebreak
    );
    assert_eq!(cpu.register(3), i64::MIN as u64);
    assert_eq!(cpu.register(4), 0);
    assert_eq!(cpu.register(5), u64::MAX);
    assert_eq!(cpu.register(6), i64::MIN as u64);
    assert_eq!(cpu.register(7), 0);
    assert_eq!(cpu.register(8), u64::MAX);
}

#[test]
fn rv64im_predecode_matches_direct_execution() {
    let words = [
        i(21, 0, 0, 1, 0x13),
        r(1, 1, 1, 0, 2, 0x33),
        i(1, 2, 0, 3, 0x1b),
        0x0010_0073,
    ];
    let image = words
        .into_iter()
        .flat_map(u32::to_le_bytes)
        .collect::<Vec<_>>();
    let program = PredecodedRv64imProgram::new(0, &image).unwrap();
    let mut direct_bus = MachineBus::new(128).unwrap();
    let mut predecoded_bus = MachineBus::new(128).unwrap();
    write_program(&mut direct_bus, &words);
    write_program(&mut predecoded_bus, &words);
    let mut direct = Rv64imCpu::new(0);
    let mut predecoded = Rv64imCpu::new(0);

    assert_eq!(
        direct.run_until_stop(&mut direct_bus, 16).unwrap(),
        Rv64imStop::Ebreak
    );
    assert_eq!(
        program
            .run_until_stop(&mut predecoded, &mut predecoded_bus, 16)
            .unwrap(),
        Rv64imStop::Ebreak
    );
    assert_eq!(predecoded, direct);
    assert!(program.retained_bytes() >= image.len());
}

#[test]
fn rv64im_rejects_addresses_outside_the_u32_benchmark_bus() {
    let mut bus = MachineBus::new(64).unwrap();
    write_program(&mut bus, &[i(0, 1, 3, 2, 0x03)]); // ld x2, 0(x1)
    let mut cpu = Rv64imCpu::new(0);
    cpu.set_register(1, 1_u64 << 32).unwrap();

    let error = cpu.step(&mut bus).unwrap_err();
    assert!(
        error.contains("outside the 32-bit benchmark bus"),
        "{error}"
    );
    assert_eq!(cpu.pc(), 0);
    assert_eq!(cpu.retired_instructions(), 0);
}
