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

use k16_vm::k16_f32r32::encoding::{
    add, addi, branch_ltu, call, halt, load32, materialize, ret, store32,
};
use k16_vm::k16_f32r32::{K16F32R32Cpu, K16F32R32Stop, PredecodedK16F32R32Program};
use k16_vm::low_bus::MachineBus;

fn write_program(bus: &mut MachineBus, words: &[u32]) {
    for (index, word) in words.iter().copied().enumerate() {
        bus.store_i32(index as u32 * 4, word as i32).unwrap();
    }
}

fn bytes(words: &[u32]) -> Vec<u8> {
    words.iter().copied().flat_map(u32::to_le_bytes).collect()
}

#[test]
fn exposes_thirty_two_ordinary_registers() {
    let mut cpu = K16F32R32Cpu::new(0x1000);

    for register in [0, 15, 16, 29, 30, 31] {
        cpu.set_register(register, 0xfeed_0000 | register as u32)
            .unwrap();
        assert_eq!(cpu.register(register), 0xfeed_0000 | register as u32);
    }
    assert!(cpu.set_register(32, 0).is_err());
    assert_eq!(
        K16F32R32Cpu::cpu_state_bytes(),
        std::mem::size_of::<K16F32R32Cpu>()
    );
}

#[test]
fn lui_and_ri14_addi_materialize_boundary_values() {
    for value in [0, 1, 0x1fff, 0x2000, 0x7fff_ffff, 0x8000_0000, u32::MAX] {
        let mut bus = MachineBus::new(64).unwrap();
        let [upper, lower] = materialize(31, value);
        write_program(&mut bus, &[upper, lower, halt()]);
        let mut cpu = K16F32R32Cpu::new(0);

        assert_eq!(
            cpu.run_until_stop(&mut bus, 4).unwrap(),
            K16F32R32Stop::Halt
        );
        assert_eq!(cpu.register(31), value);
    }
}

#[test]
fn ri14_accepts_both_signed_endpoints() {
    let mut bus = MachineBus::new(64).unwrap();
    write_program(&mut bus, &[addi(16, 0, -8192), addi(17, 0, 8191), halt()]);
    let mut cpu = K16F32R32Cpu::new(0);

    assert_eq!(
        cpu.run_until_stop(&mut bus, 4).unwrap(),
        K16F32R32Stop::Halt
    );
    assert_eq!(cpu.register(16), (-8192_i32) as u32);
    assert_eq!(cpu.register(17), 8191);
}

#[test]
fn direct_call_uses_r31_without_guest_memory_traffic() {
    let mut bus = MachineBus::new(128).unwrap();
    write_program(&mut bus, &[call(1), halt(), addi(16, 0, 7), ret()]);
    let memory_before = bus.memory().bytes().to_vec();
    let mut cpu = K16F32R32Cpu::new(0);

    assert_eq!(
        cpu.run_until_stop(&mut bus, 8).unwrap(),
        K16F32R32Stop::Halt
    );
    assert_eq!(cpu.register(16), 7);
    assert_eq!(cpu.register(31), 4);
    assert_eq!(bus.memory().bytes(), memory_before.as_slice());
}

#[test]
fn non_leaf_function_can_preserve_link_register_once() {
    let mut bus = MachineBus::new(256).unwrap();
    write_program(
        &mut bus,
        &[
            addi(30, 0, 192),
            call(1),
            halt(),
            addi(30, 30, -4),
            store32(30, 31, 0),
            call(3),
            load32(31, 30, 0),
            addi(30, 30, 4),
            ret(),
            addi(16, 16, 11),
            ret(),
        ],
    );
    let mut cpu = K16F32R32Cpu::new(0);

    assert_eq!(
        cpu.run_until_stop(&mut bus, 16).unwrap(),
        K16F32R32Stop::Halt
    );
    assert_eq!(cpu.register(16), 11);
    assert_eq!(cpu.register(30), 192);
    assert_eq!(cpu.register(31), 8);
}

#[test]
fn two_register_branch_and_high_register_alu_execute() {
    let mut bus = MachineBus::new(128).unwrap();
    write_program(
        &mut bus,
        &[
            addi(16, 0, 1),
            addi(30, 0, 2),
            branch_ltu(16, 30, 1),
            addi(31, 0, 99),
            add(31, 16, 30),
            halt(),
        ],
    );
    let mut cpu = K16F32R32Cpu::new(0);

    assert_eq!(
        cpu.run_until_stop(&mut bus, 8).unwrap(),
        K16F32R32Stop::Halt
    );
    assert_eq!(cpu.register(31), 3);
}

#[test]
fn reserved_rrr_bits_fail_without_retiring() {
    let mut bus = MachineBus::new(16).unwrap();
    write_program(&mut bus, &[add(1, 2, 3) | 1]);
    let mut cpu = K16F32R32Cpu::new(0);

    assert!(cpu.run_until_stop(&mut bus, 1).is_err());
    assert_eq!(cpu.retired_instructions(), 0);
}

#[test]
fn eager_predecode_matches_direct_execution() {
    let words = [
        addi(30, 0, 96),
        addi(16, 0, 5),
        store32(30, 16, 0),
        load32(17, 30, 0),
        add(31, 16, 17),
        halt(),
    ];
    let program = PredecodedK16F32R32Program::new(0, &bytes(&words)).unwrap();
    let mut direct_bus = MachineBus::new(128).unwrap();
    let mut predecoded_bus = MachineBus::new(128).unwrap();
    write_program(&mut direct_bus, &words);
    write_program(&mut predecoded_bus, &words);
    let mut direct = K16F32R32Cpu::new(0);
    let mut predecoded = K16F32R32Cpu::new(0);

    assert_eq!(
        direct.run_until_stop(&mut direct_bus, 8).unwrap(),
        K16F32R32Stop::Halt
    );
    assert_eq!(
        program
            .run_until_stop(&mut predecoded, &mut predecoded_bus, 8)
            .unwrap(),
        K16F32R32Stop::Halt
    );
    for register in 0..32 {
        assert_eq!(direct.register(register), predecoded.register(register));
    }
    assert_eq!(direct.pc(), predecoded.pc());
    assert_eq!(
        direct.retired_instructions(),
        predecoded.retired_instructions()
    );
    assert_eq!(direct_bus.memory(), predecoded_bus.memory());
    assert!(program.retained_bytes() >= words.len());
}
