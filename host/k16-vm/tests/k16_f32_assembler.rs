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

use k16_vm::compiled_c::k16_f32_assembler::assemble_k16_f32;
use k16_vm::k16_f32::encoding::{
    addi, branch_ltu, branchnz, call, halt, jump, load32, materialize, ret, store32,
};

fn words(image: &[u8]) -> Vec<u32> {
    image
        .chunks_exact(4)
        .map(|bytes| u32::from_le_bytes(bytes.try_into().unwrap()))
        .collect()
}

#[test]
fn assembles_forward_and_backward_relative_targets() {
    let assembled = assemble_k16_f32(
        "kernel:\n  br forward\nloop:\n  addi r1, r1, -1\n  brnz r1, loop\nforward:\n  ret\n",
        0x1000,
        "kernel",
    )
    .unwrap();

    assert_eq!(assembled.entry_offset, 0);
    assert_eq!(assembled.stop_offset, 16);
    assert_eq!(assembled.instruction_count, 4);
    assert_eq!(
        words(&assembled.image),
        vec![jump(2), addi(1, 1, -1), branchnz(1, -2), ret(), halt()]
    );
}

#[test]
fn assembles_direct_call_return_and_const32_expansion() {
    let assembled = assemble_k16_f32(
        "helper:\n  const32 r3, 305419896\n  ret\nkernel:\n  call32 helper\n  ret\n",
        0x2000,
        "kernel",
    )
    .unwrap();
    let [upper, lower] = materialize(3, 0x1234_5678);

    assert_eq!(assembled.entry_offset, 12);
    assert_eq!(assembled.stop_offset, 20);
    assert_eq!(assembled.instruction_count, 5);
    assert_eq!(
        words(&assembled.image),
        vec![upper, lower, ret(), call(-4), ret(), halt()]
    );
}

#[test]
fn accepts_compiler_directives_and_memory_operand_forms() {
    let assembled = assemble_k16_f32(
        ".file \"fixture.c\"\n.section .text.k16,\"ax\",@progbits\n.globl kernel\n.p2align 1\n.type kernel,@function\nkernel:\nload32 r1, [r15]\nstore32 [r15 + -4], r1\n.size kernel, .-kernel\n.section \".note.GNU-stack\",\"\",@progbits\n",
        0,
        "kernel",
    )
    .unwrap();

    assert_eq!(
        words(&assembled.image),
        vec![load32(1, 15, 0), store32(15, 1, -4), halt()]
    );
}

#[test]
fn assembles_unsigned_two_register_branch() {
    let assembled = assemble_k16_f32(
        "kernel:\n  brltu r1, r2, target\n  ret\ntarget:\n  ret\n",
        0,
        "kernel",
    )
    .unwrap();

    assert_eq!(
        words(&assembled.image),
        vec![branch_ltu(1, 2, 1), ret(), ret(), halt()]
    );
}

#[test]
fn rejects_invalid_source_instead_of_guessing() {
    let cases = [
        ("kernel:\nkernel:\nret\n", "duplicate label"),
        ("kernel:\nbr missing\n", "undefined label"),
        ("kernel:\naddi r16, r0, 1\n", "register"),
        ("kernel:\naddi r1, r0, 32768\n", "immediate"),
        ("kernel:\n.word 0\n", "directive"),
        ("kernel:\nmagic r1\n", "opcode"),
    ];
    for (source, expected) in cases {
        let error = assemble_k16_f32(source, 0, "kernel").unwrap_err();
        assert!(
            error.contains(expected),
            "{error:?} did not contain {expected:?}"
        );
    }
    assert!(assemble_k16_f32("kernel:\nret\n", 2, "kernel")
        .unwrap_err()
        .contains("four-byte"));
}

#[test]
fn rejects_relative_target_outside_branch_encoding() {
    let mut source = String::from("kernel:\nbrltu r1, r2, distant\n");
    source.push_str(&"ret\n".repeat(32_768));
    source.push_str("distant:\nret\n");

    assert!(assemble_k16_f32(&source, 0, "kernel")
        .unwrap_err()
        .contains("relative target"));
}
