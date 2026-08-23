/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

use compukter_ffi::{
    compukter_abi_version, compukter_advance, compukter_close, compukter_create,
    compukter_max_create_bytes, compukter_max_outcome_bytes, compukter_terminal_changes_since,
    compukter_terminal_commit, compukter_terminal_compatibility_line,
    compukter_terminal_full_state, compukter_terminal_key, compukter_terminal_text, FfiStatus,
};

#[test]
fn c_abi_publishes_its_exact_version() {
    assert_eq!(1, compukter_abi_version());
}

#[test]
fn c_abi_creates_and_closes_an_opaque_machine_handle() {
    let artifact = terminal_artifact();
    let handle = create_machine(&artifact);
    assert_ne!(0, handle);
    assert_eq!(FfiStatus::Ok, compukter_close(handle));
    assert_eq!(FfiStatus::StaleHandle, compukter_close(handle));
}

#[test]
fn create_preserves_the_typed_wire_result_and_rejects_short_output_first() {
    let invalid_artifact = [0_u8];
    let mut short = [0_u8; 1];
    let mut written = 0_usize;

    assert_eq!(FfiStatus::BufferTooSmall, unsafe {
        compukter_create(
            invalid_artifact.as_ptr(),
            invalid_artifact.len(),
            short.as_mut_ptr(),
            short.len(),
            &mut written,
        )
    },);
    assert_eq!(compukter_max_create_bytes(), written);

    let mut output = vec![0_u8; compukter_max_create_bytes()];
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_create(
            invalid_artifact.as_ptr(),
            invalid_artifact.len(),
            output.as_mut_ptr(),
            output.len(),
            &mut written,
        )
    },);
    assert_eq!(&[1], &output[..written]);
}

#[test]
fn advance_rejects_a_short_buffer_before_advancing_the_machine() {
    let artifact = terminal_artifact();
    let handle = create_machine(&artifact);
    let control = create_machine(&artifact);
    let mut written = 0_usize;
    let mut short = [0_u8; 1];

    assert_eq!(FfiStatus::BufferTooSmall, unsafe {
        compukter_advance(
            handle,
            64,
            64,
            short.as_mut_ptr(),
            short.len(),
            &mut written,
        )
    },);
    assert_eq!(compukter_max_outcome_bytes(), written);

    let mut output = vec![0_u8; compukter_max_outcome_bytes()];
    let mut control_output = vec![0_u8; compukter_max_outcome_bytes()];
    let mut control_written = 0_usize;
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_advance(
            handle,
            64,
            64,
            output.as_mut_ptr(),
            output.len(),
            &mut written,
        )
    },);
    assert!(written >= 1 && written <= output.len());
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_advance(
            control,
            64,
            64,
            control_output.as_mut_ptr(),
            control_output.len(),
            &mut control_written,
        )
    },);
    assert_eq!(control_written, written);
    assert_eq!(&control_output[..control_written], &output[..written]);
    assert_eq!(FfiStatus::Ok, compukter_close(handle));
    assert_eq!(FfiStatus::Ok, compukter_close(control));
}

#[test]
fn computer_terminal_state_and_inputs_cross_the_bounded_c_abi() {
    let artifact = terminal_artifact();
    let handle = create_machine(&artifact);
    for _ in 0..1_024 {
        let outcome = next_outcome(handle);
        if outcome == [8] {
            break;
        }
    }
    assert_eq!(vec![8], next_outcome(handle));
    assert_eq!(FfiStatus::Ok, compukter_terminal_commit(handle));

    let state = terminal_full_state(handle);
    assert_eq!(2, state[0]);
    assert_eq!(1, u64::from_le_bytes(state[1..9].try_into().unwrap()));
    assert_eq!(51, u16::from_le_bytes(state[9..11].try_into().unwrap()));
    assert_eq!(19, u16::from_le_bytes(state[11..13].try_into().unwrap()));
    assert_eq!(969, u32::from_le_bytes(state[13..17].try_into().unwrap()));
    assert_eq!(
        '>' as u32,
        u32::from_le_bytes(state[17..21].try_into().unwrap())
    );
    let delta = terminal_changes_since(handle, 0);
    assert_eq!(1, delta[0]);
    assert_eq!(0, u64::from_le_bytes(delta[1..9].try_into().unwrap()));
    assert_eq!(1, u64::from_le_bytes(delta[9..17].try_into().unwrap()));

    assert_eq!(FfiStatus::Ok, compukter_terminal_key(handle, 13, 0, 1),);
    let text = ['λ' as u32, 0x1f600];
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_terminal_text(handle, text.as_ptr(), text.len())
    });
    let line: Vec<u16> = "answer".encode_utf16().collect();
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_terminal_compatibility_line(handle, line.as_ptr(), line.len())
    });
    assert_eq!(FfiStatus::Ok, compukter_close(handle));
}

fn create_machine(artifact: &[u8]) -> u64 {
    let mut output = vec![0_u8; compukter_max_create_bytes()];
    let mut written = 0_usize;
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_create(
            artifact.as_ptr(),
            artifact.len(),
            output.as_mut_ptr(),
            output.len(),
            &mut written,
        )
    },);
    assert_eq!(9, written);
    assert_eq!(0, output[0]);
    u64::from_le_bytes(output[1..9].try_into().unwrap())
}

fn next_outcome(handle: u64) -> Vec<u8> {
    let mut output = vec![0_u8; compukter_max_outcome_bytes()];
    let mut written = 0_usize;
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_advance(
            handle,
            64,
            64,
            output.as_mut_ptr(),
            output.len(),
            &mut written,
        )
    });
    output.truncate(written);
    output
}

fn terminal_full_state(handle: u64) -> Vec<u8> {
    let mut output = vec![0_u8; compukter_max_outcome_bytes()];
    let mut written = 0_usize;
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_terminal_full_state(handle, output.as_mut_ptr(), output.len(), &mut written)
    });
    output.truncate(written);
    output
}

fn terminal_changes_since(handle: u64, revision: u64) -> Vec<u8> {
    let mut output = vec![0_u8; compukter_max_outcome_bytes()];
    let mut written = 0_usize;
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_terminal_changes_since(
            handle,
            revision,
            output.as_mut_ptr(),
            output.len(),
            &mut written,
        )
    });
    output.truncate(written);
    output
}

fn terminal_artifact() -> Vec<u8> {
    include_str!("../../compukter-vm/tests/fixtures/terminal-session.hex")
        .trim()
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            let digit = |value| match value {
                b'0'..=b'9' => value - b'0',
                b'a'..=b'f' => value - b'a' + 10,
                _ => panic!("invalid fixture hex"),
            };
            (digit(pair[0]) << 4) | digit(pair[1])
        })
        .collect()
}
