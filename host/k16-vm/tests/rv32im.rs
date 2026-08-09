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
use k16_vm::rv32im::encoding::{
    add, addi, auipc, beq, bge, bgeu, blt, bltu, bne, div, divu, ebreak, jal, jalr, lb, lbu, lh,
    lhu, lui, lw, mul, rem, remu, sb, sh, sw,
};
use k16_vm::rv32im::PredecodedRv32imProgram;
use k16_vm::rv32im::{Rv32imCpu, Rv32imStop};

fn write_program(bus: &mut MachineBus, words: &[u32]) {
    for (index, word) in words.iter().copied().enumerate() {
        bus.store_i32(index as u32 * 4, word as i32).unwrap();
    }
}

#[test]
fn rv32im_executes_integer_multiply_divide_and_preserves_x0() {
    let mut bus = MachineBus::new(256).unwrap();
    write_program(
        &mut bus,
        &[
            addi(1, 0, 7),
            addi(2, 0, -3),
            add(3, 1, 2),
            mul(4, 1, 2),
            div(5, 1, 2),
            divu(6, 1, 2),
            rem(7, 1, 2),
            remu(8, 1, 2),
            addi(0, 0, 99),
            ebreak(),
        ],
    );
    let mut cpu = Rv32imCpu::new(0);

    assert_eq!(
        cpu.run_until_stop(&mut bus, 16).unwrap(),
        Rv32imStop::Ebreak
    );
    assert_eq!(cpu.register(0), 0);
    assert_eq!(cpu.register(3), 4);
    assert_eq!(cpu.register(4), (-21_i32) as u32);
    assert_eq!(cpu.register(5), (-2_i32) as u32);
    assert_eq!(cpu.register(6), 0);
    assert_eq!(cpu.register(7), 1);
    assert_eq!(cpu.register(8), 7);
}

#[test]
fn rv32m_division_corner_cases_follow_the_ratified_rules() {
    let mut bus = MachineBus::new(256).unwrap();
    write_program(
        &mut bus,
        &[
            lui(1, 0x80000),
            addi(2, 0, -1),
            addi(3, 0, 0),
            div(4, 1, 2),
            rem(5, 1, 2),
            div(6, 1, 3),
            divu(7, 1, 3),
            rem(8, 1, 3),
            remu(9, 1, 3),
            ebreak(),
        ],
    );
    let mut cpu = Rv32imCpu::new(0);

    assert_eq!(
        cpu.run_until_stop(&mut bus, 16).unwrap(),
        Rv32imStop::Ebreak
    );
    assert_eq!(cpu.register(4), 0x8000_0000);
    assert_eq!(cpu.register(5), 0);
    assert_eq!(cpu.register(6), u32::MAX);
    assert_eq!(cpu.register(7), u32::MAX);
    assert_eq!(cpu.register(8), 0x8000_0000);
    assert_eq!(cpu.register(9), 0x8000_0000);
}

#[test]
fn selected_rv32i_architectural_branch_and_load_vectors_match_sign_rules() {
    let mut bus = MachineBus::new(256).unwrap();
    write_program(
        &mut bus,
        &[
            addi(1, 0, 128),
            addi(2, 0, -1),
            sb(1, 2, 0),
            sh(1, 2, 2),
            lb(3, 1, 0),
            lbu(4, 1, 0),
            lh(5, 1, 2),
            lhu(6, 1, 2),
            blt(2, 0, 8),
            addi(7, 0, 99),
            bge(0, 2, 8),
            addi(8, 0, 99),
            bltu(0, 2, 8),
            addi(9, 0, 99),
            bgeu(2, 0, 8),
            addi(10, 0, 99),
            bne(0, 2, 8),
            addi(11, 0, 99),
            beq(0, 0, 8),
            addi(12, 0, 99),
            ebreak(),
        ],
    );
    let mut cpu = Rv32imCpu::new(0);
    assert_eq!(
        cpu.run_until_stop(&mut bus, 32).unwrap(),
        Rv32imStop::Ebreak
    );
    assert_eq!(cpu.register(3), u32::MAX);
    assert_eq!(cpu.register(4), 255);
    assert_eq!(cpu.register(5), u32::MAX);
    assert_eq!(cpu.register(6), 65_535);
    for register in 7..=12 {
        assert_eq!(cpu.register(register), 0, "x{register}");
    }
}

#[test]
fn rv32i_control_flow_and_word_memory_use_standard_pc_rules() {
    let mut bus = MachineBus::new(256).unwrap();
    write_program(
        &mut bus,
        &[
            addi(1, 0, 128),
            addi(2, 0, 42),
            sw(1, 2, 0),
            lw(3, 1, 0),
            beq(2, 3, 8),
            addi(4, 0, 99),
            jal(5, 8),
            addi(4, 0, 88),
            auipc(6, 0),
            jalr(7, 5, 12),
            ebreak(),
        ],
    );
    let mut cpu = Rv32imCpu::new(0);

    assert_eq!(
        cpu.run_until_stop(&mut bus, 32).unwrap(),
        Rv32imStop::Ebreak
    );
    assert_eq!(cpu.register(3), 42);
    assert_eq!(cpu.register(4), 0);
    assert_eq!(cpu.register(5), 28);
    assert_eq!(cpu.register(6), 32);
    assert_eq!(cpu.register(7), 40);
}

#[test]
fn rv32im_rejects_illegal_and_misaligned_access_without_retiring_it() {
    let mut illegal_bus = MachineBus::new(32).unwrap();
    write_program(&mut illegal_bus, &[0xffff_ffff]);
    let mut illegal_cpu = Rv32imCpu::new(0);
    assert!(illegal_cpu.run_until_stop(&mut illegal_bus, 1).is_err());
    assert_eq!(illegal_cpu.retired_instructions(), 0);

    let mut fetch_bus = MachineBus::new(32).unwrap();
    let mut fetch_cpu = Rv32imCpu::new(2);
    assert!(fetch_cpu.run_until_stop(&mut fetch_bus, 1).is_err());
    assert_eq!(fetch_cpu.retired_instructions(), 0);

    let mut data_bus = MachineBus::new(32).unwrap();
    write_program(&mut data_bus, &[addi(1, 0, 3), lw(2, 1, 0)]);
    let mut data_cpu = Rv32imCpu::new(0);
    assert!(data_cpu.run_until_stop(&mut data_bus, 2).is_err());
    assert_eq!(data_cpu.retired_instructions(), 1);
    assert_eq!(data_cpu.pc(), 4);
    assert_eq!(data_cpu.register(2), 0);

    let mut jump_bus = MachineBus::new(32).unwrap();
    write_program(&mut jump_bus, &[jal(5, 2)]);
    let mut jump_cpu = Rv32imCpu::new(0);
    assert!(jump_cpu.step(&mut jump_bus).is_err());
    assert_eq!(jump_cpu.pc(), 0);
    assert_eq!(jump_cpu.register(5), 0);
    assert_eq!(jump_cpu.retired_instructions(), 0);
}

#[test]
fn reported_cpu_state_bytes_include_rust_layout_padding() {
    assert_eq!(
        Rv32imCpu::cpu_state_bytes(),
        std::mem::size_of::<Rv32imCpu>()
    );
}

#[test]
fn predecoded_program_matches_direct_execution_and_reports_retained_bytes() {
    let words = [
        addi(1, 0, 128),
        addi(2, 0, 21),
        mul(3, 2, 2),
        sw(1, 3, 0),
        lw(4, 1, 0),
        ebreak(),
    ];
    let bytes = words
        .into_iter()
        .flat_map(u32::to_le_bytes)
        .collect::<Vec<_>>();
    let program = PredecodedRv32imProgram::new(0, &bytes).unwrap();
    assert!(program.retained_bytes() >= words.len() * std::mem::size_of::<u32>());

    let mut direct_bus = MachineBus::new(256).unwrap();
    let mut predecoded_bus = MachineBus::new(256).unwrap();
    write_program(&mut direct_bus, &words);
    write_program(&mut predecoded_bus, &words);
    let mut direct = Rv32imCpu::new(0);
    let mut predecoded = Rv32imCpu::new(0);

    assert_eq!(
        direct.run_until_stop(&mut direct_bus, 16).unwrap(),
        Rv32imStop::Ebreak
    );
    assert_eq!(
        program
            .run_until_stop(&mut predecoded, &mut predecoded_bus, 16)
            .unwrap(),
        Rv32imStop::Ebreak,
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
}

#[test]
fn predecode_rejects_partial_and_illegal_images() {
    assert!(PredecodedRv32imProgram::new(0, &[0, 1, 2]).is_err());
    assert!(PredecodedRv32imProgram::new(0, &0xffff_ffff_u32.to_le_bytes()).is_err());
}
