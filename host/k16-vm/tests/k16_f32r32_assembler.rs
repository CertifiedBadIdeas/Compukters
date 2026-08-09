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

use k16_vm::compiled_c::k16_f32r32_assembler::assemble_k16_f32r32;
use k16_vm::k16_f32r32::encoding::{
    add, addi, branch_ltu, branchnz, call, halt, jump, load32, materialize, ret, store32,
};
use std::process::Command;

fn words(image: &[u8]) -> Vec<u32> {
    image
        .chunks_exact(4)
        .map(|bytes| u32::from_le_bytes(bytes.try_into().unwrap()))
        .collect()
}

#[test]
fn assembles_high_registers_and_ri14_memory_operands() {
    let assembled = assemble_k16_f32r32(
        "kernel:\n  addi r16, r0, -8192\n  addi r31, r0, 8191\n  load32 r29, [r30 + 8191]\n  store32 [r30 + -8192], r29\n  add r31, r16, r29\n  ret\n",
        0x1000,
        "kernel",
    )
    .unwrap();

    assert_eq!(assembled.entry_offset, 0);
    assert_eq!(assembled.instruction_count, 6);
    assert_eq!(assembled.stop_offset, 24);
    assert_eq!(
        words(&assembled.image),
        vec![
            addi(16, 0, -8192),
            addi(31, 0, 8191),
            load32(29, 30, 8191),
            store32(30, 29, -8192),
            add(31, 16, 29),
            ret(),
            halt(),
        ]
    );
}

#[test]
fn expands_every_boundary_const32_to_two_words() {
    for value in [0, 0x7fff_ffff, 0x8000_0000, u32::MAX] {
        let assembled = assemble_k16_f32r32(
            &format!("kernel:\n  const32 r31, {value}\n  ret\n"),
            0,
            "kernel",
        )
        .unwrap();
        let [upper, lower] = materialize(31, value);
        assert_eq!(words(&assembled.image), vec![upper, lower, ret(), halt()]);
        assert_eq!(assembled.instruction_count, 3);
    }
}

#[test]
fn resolves_j24_br19_brr14_and_direct_call_targets() {
    let assembled = assemble_k16_f32r32(
        "kernel:\n  br target\nloop:\n  addi r16, r16, -1\n  brnz r16, loop\ntarget:\n  brltu r16, r31, helper\n  call32 helper\n  ret\nhelper:\n  ret\n",
        0,
        "kernel",
    )
    .unwrap();

    assert_eq!(
        words(&assembled.image),
        vec![
            jump(2),
            addi(16, 16, -1),
            branchnz(16, -2),
            branch_ltu(16, 31, 2),
            call(1),
            ret(),
            ret(),
            halt(),
        ]
    );
}

#[test]
fn accepts_compiler_directives_and_is_deterministic() {
    let source = ".file \"fixture.c\"\n.section .text.k16,\"ax\",@progbits\n.globl kernel\n.p2align 1\n.type kernel,@function\nkernel:\n  addi r31, r0, 7\n  ret\n.size kernel, .-kernel\n.section \".note.GNU-stack\",\"\",@progbits\n";
    let first = assemble_k16_f32r32(source, 0x2000, "kernel").unwrap();
    let second = assemble_k16_f32r32(source, 0x2000, "kernel").unwrap();

    assert_eq!(first, second);
    assert_eq!(first.image.len(), first.stop_offset as usize + 4);
}

#[test]
fn rejects_out_of_range_or_unknown_input() {
    let cases = [
        ("kernel:\naddi r32, r0, 1\n", "register"),
        ("kernel:\naddi r1, r0, 8192\n", "14-bit"),
        ("kernel:\nload32 r1, [r30 - 8193]\n", "14-bit"),
        ("kernel:\nbr missing\n", "undefined label"),
        ("kernel:\n.word 0\n", "directive"),
        ("kernel:\nmagic r1\n", "opcode"),
    ];
    for (source, expected) in cases {
        let error = assemble_k16_f32r32(source, 0, "kernel").unwrap_err();
        assert!(
            error.contains(expected),
            "{error:?} did not contain {expected:?}"
        );
    }
}

#[test]
fn cli_writes_candidate_manifest_and_image() {
    let directory =
        std::env::temp_dir().join(format!("k16-f32r32-assembler-{}", std::process::id()));
    std::fs::create_dir_all(&directory).unwrap();
    let input = directory.join("fixture.s");
    let output = directory.join("fixture.bin");
    let manifest = directory.join("fixture.manifest");
    std::fs::write(&input, "kernel:\n  addi r16, r0, 7\n  ret\n").unwrap();

    let status = Command::new(env!("CARGO_BIN_EXE_k16_f32r32_assemble"))
        .args([
            "--base",
            "4096",
            "--entry",
            "kernel",
            "--input",
            input.to_str().unwrap(),
            "--output",
            output.to_str().unwrap(),
            "--manifest",
            manifest.to_str().unwrap(),
        ])
        .status()
        .unwrap();

    assert!(status.success());
    assert_eq!(std::fs::read(&output).unwrap().len(), 12);
    let manifest = std::fs::read_to_string(&manifest).unwrap();
    assert!(manifest.contains("candidate=k16-f32r32-lr\n"));
    assert!(manifest.contains("image_base=4096\n"));
    assert!(manifest.contains("entry_offset=0\n"));
    assert!(manifest.contains("stop_offset=8\n"));
    assert!(manifest.contains("instruction_count=2\n"));

    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn cli_help_exits_successfully() {
    let output = Command::new(env!("CARGO_BIN_EXE_k16_f32r32_assemble"))
        .arg("--help")
        .output()
        .unwrap();

    assert!(output.status.success());
    assert!(String::from_utf8(output.stdout)
        .unwrap()
        .contains("--manifest"));
}
