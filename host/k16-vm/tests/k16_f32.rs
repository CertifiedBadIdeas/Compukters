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

use k16_vm::k16_f32::encoding::{
    add, addi, and, branchz, call, eq, halt, load16, load32, load8, lt_s, ltu, materialize, mul,
    ne, or, ret, sar, shl, shr, store16, store32, store8, sub, xor, yield_now,
};
use k16_vm::k16_f32::{K16F32Cpu, K16F32Stop};
use k16_vm::low_bus::MachineBus;

fn write_program(bus: &mut MachineBus, words: &[u32]) {
    for (index, word) in words.iter().copied().enumerate() {
        bus.store_i32(index as u32 * 4, word as i32).unwrap();
    }
}

#[test]
fn reported_cpu_state_bytes_include_rust_layout_padding() {
    assert_eq!(
        K16F32Cpu::cpu_state_bytes(),
        std::mem::size_of::<K16F32Cpu>()
    );
}

#[test]
fn every_instruction_is_one_aligned_u32() {
    let words = [addi(1, 0, 7), branchz(1, -1), halt()];
    let bytes = words
        .into_iter()
        .flat_map(u32::to_le_bytes)
        .collect::<Vec<_>>();

    assert_eq!(bytes.len(), words.len() * 4);
    assert_eq!(bytes.len() % 4, 0);
}

#[test]
fn upper_plus_addi_materializes_every_boundary_constant() {
    for value in [0, 1, 0x7fff, 0x8000, 0xffff, 0x1234_5678, u32::MAX] {
        let mut bus = MachineBus::new(64).unwrap();
        let [upper, lower] = materialize(3, value);
        write_program(&mut bus, &[upper, lower, halt()]);
        let mut cpu = K16F32Cpu::new(0);

        assert_eq!(cpu.run_until_stop(&mut bus, 4).unwrap(), K16F32Stop::Halt,);
        assert_eq!(cpu.register(3), value);
    }
}

#[test]
fn zero_branch_skips_exactly_one_instruction() {
    let mut bus = MachineBus::new(64).unwrap();
    write_program(
        &mut bus,
        &[
            addi(1, 0, 0),
            branchz(1, 1),
            addi(2, 0, 99),
            addi(2, 0, 7),
            halt(),
        ],
    );
    let mut cpu = K16F32Cpu::new(0);

    assert_eq!(cpu.run_until_stop(&mut bus, 8).unwrap(), K16F32Stop::Halt,);
    assert_eq!(cpu.register(2), 7);
}

#[test]
fn unknown_opcode_and_misaligned_pc_fail_without_retiring() {
    let mut illegal_bus = MachineBus::new(16).unwrap();
    write_program(&mut illegal_bus, &[0xff00_0000]);
    let mut illegal_cpu = K16F32Cpu::new(0);
    assert!(illegal_cpu.run_until_stop(&mut illegal_bus, 1).is_err());
    assert_eq!(illegal_cpu.retired_instructions(), 0);

    let mut aligned_bus = MachineBus::new(16).unwrap();
    write_program(&mut aligned_bus, &[halt()]);
    let mut misaligned_cpu = K16F32Cpu::new(2);
    assert!(misaligned_cpu.run_until_stop(&mut aligned_bus, 1).is_err());
    assert_eq!(misaligned_cpu.retired_instructions(), 0);
}

#[test]
fn arithmetic_and_comparison_opcode_family_executes_canonical_results() {
    let mut bus = MachineBus::new(256).unwrap();
    let mut words = Vec::new();
    words.extend(materialize(1, 0xffff_fffe));
    words.extend(materialize(2, 3));
    words.extend([
        add(3, 1, 2),
        sub(4, 2, 1),
        mul(5, 1, 2),
        and(6, 1, 2),
        or(7, 1, 2),
        xor(8, 1, 2),
        shl(9, 2, 2),
        shr(10, 1, 2),
        sar(11, 1, 2),
        eq(12, 1, 2),
        ne(13, 1, 2),
        ltu(14, 1, 2),
        lt_s(0, 1, 2),
        halt(),
    ]);
    write_program(&mut bus, &words);
    let mut cpu = K16F32Cpu::new(0);

    assert_eq!(cpu.run_until_stop(&mut bus, 32).unwrap(), K16F32Stop::Halt,);
    assert_eq!(cpu.register(3), 1);
    assert_eq!(cpu.register(4), 5);
    assert_eq!(cpu.register(5), 0xffff_fffa);
    assert_eq!(cpu.register(6), 2);
    assert_eq!(cpu.register(7), u32::MAX);
    assert_eq!(cpu.register(8), 0xffff_fffd);
    assert_eq!(cpu.register(9), 24);
    assert_eq!(cpu.register(10), 0x1fff_ffff);
    assert_eq!(cpu.register(11), u32::MAX);
    assert_eq!(cpu.register(12), 0);
    assert_eq!(cpu.register(13), 1);
    assert_eq!(cpu.register(14), 0);
    assert_eq!(cpu.register(0), 1);
}

#[test]
fn byte_halfword_and_word_memory_opcodes_round_trip() {
    let mut bus = MachineBus::new(256).unwrap();
    let mut words = Vec::new();
    words.extend(materialize(1, 128));
    words.extend(materialize(2, 0x89ab_cdef));
    words.extend([
        store8(1, 2, 0),
        store16(1, 2, 2),
        store32(1, 2, 4),
        load8(3, 1, 0),
        load16(4, 1, 2),
        load32(5, 1, 4),
        halt(),
    ]);
    write_program(&mut bus, &words);
    let mut cpu = K16F32Cpu::new(0);

    assert_eq!(cpu.run_until_stop(&mut bus, 24).unwrap(), K16F32Stop::Halt,);
    assert_eq!(cpu.register(3), 0xef);
    assert_eq!(cpu.register(4), 0xcdef);
    assert_eq!(cpu.register(5), 0x89ab_cdef);
}

#[test]
fn relative_call_and_ret_use_r15_stack() {
    let mut bus = MachineBus::new(256).unwrap();
    let mut words = Vec::new();
    words.extend(materialize(15, 256));
    words.extend([call(1), halt(), addi(1, 1, 7), ret()]);
    write_program(&mut bus, &words);
    let mut cpu = K16F32Cpu::new(0);

    assert_eq!(cpu.run_until_stop(&mut bus, 12).unwrap(), K16F32Stop::Halt,);
    assert_eq!(cpu.register(1), 7);
    assert_eq!(cpu.register(15), 256);
}

#[test]
fn yield_is_resumable_and_reserved_bits_are_rejected() {
    let mut bus = MachineBus::new(32).unwrap();
    write_program(&mut bus, &[yield_now(), halt()]);
    let mut cpu = K16F32Cpu::new(0);
    assert_eq!(cpu.run_until_stop(&mut bus, 1).unwrap(), K16F32Stop::Yield,);
    assert_eq!(cpu.run_until_stop(&mut bus, 1).unwrap(), K16F32Stop::Halt,);

    let mut reserved_bus = MachineBus::new(16).unwrap();
    write_program(&mut reserved_bus, &[add(1, 2, 3) | 1]);
    let mut reserved_cpu = K16F32Cpu::new(0);
    assert!(reserved_cpu.run_until_stop(&mut reserved_bus, 1).is_err());
    assert_eq!(reserved_cpu.retired_instructions(), 0);
}
