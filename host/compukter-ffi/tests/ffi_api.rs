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

#[allow(dead_code)]
#[path = "../../compukter-vm/tests/support/mod.rs"]
mod support;

use compukter_ffi::{
    compukter_abi_version, compukter_advance, compukter_close, compukter_compilation_complete,
    compukter_compilation_request_copy, compukter_compilation_request_size, compukter_create,
    compukter_create_boot_in_store, compukter_create_in_store, compukter_filesystem_generation,
    compukter_max_create_bytes, compukter_max_outcome_bytes, compukter_store_close,
    compukter_store_durable_generation, compukter_store_flush, compukter_store_health,
    compukter_store_open, compukter_store_recover, compukter_store_tombstone,
    compukter_terminal_changes_since, compukter_terminal_commit, compukter_terminal_full_state,
    compukter_terminal_key, compukter_terminal_text, compukter_verify_artifact, FfiStatus,
};
use compukter_vm::ProcessResult;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

#[test]
fn c_abi_publishes_its_exact_version() {
    assert_eq!(4, compukter_abi_version());
}

#[test]
fn artifact_verification_does_not_apply_runtime_admission_limits() {
    let artifact = terminal_artifact();
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_verify_artifact(artifact.as_ptr(), artifact.len())
    });

    let invalid = [0_u8];
    assert_eq!(FfiStatus::Verification, unsafe {
        compukter_verify_artifact(invalid.as_ptr(), invalid.len())
    });
    assert_eq!(FfiStatus::InvalidArgument, unsafe {
        compukter_verify_artifact(core::ptr::null(), 1)
    });
}

#[test]
fn compilation_ffi_rejects_invalid_handles_tags_lengths_and_pointers() {
    let mut required = 0_usize;
    assert_eq!(FfiStatus::InvalidHandle, unsafe {
        compukter_compilation_request_size(0, 1, &mut required)
    });
    assert_eq!(FfiStatus::InvalidArgument, unsafe {
        compukter_compilation_request_size(0, 1, core::ptr::null_mut())
    });
    assert_eq!(FfiStatus::InvalidArgument, unsafe {
        compukter_compilation_request_copy(0, 1, core::ptr::null_mut(), 1, &mut required)
    });

    let byte = [0xff_u8];
    assert_eq!(FfiStatus::InvalidArgument, unsafe {
        compukter_compilation_complete(0, 1, 2, core::ptr::null(), 0)
    });
    assert_eq!(FfiStatus::InvalidArgument, unsafe {
        compukter_compilation_complete(0, 1, 1, byte.as_ptr(), byte.len())
    });
    assert_eq!(FfiStatus::InvalidArgument, unsafe {
        compukter_compilation_complete(0, 1, 0, core::ptr::null(), 1)
    });
}

#[test]
fn store_open_validates_inputs_before_publishing_a_versioned_handle() {
    let root = TestRoot::new();
    let root_bytes = root.path().as_os_str().as_encoded_bytes();
    let mut output = [0_u8; 10];
    let mut written = 0_usize;

    assert_eq!(FfiStatus::BufferTooSmall, unsafe {
        compukter_store_open(
            root_bytes.as_ptr(),
            root_bytes.len(),
            core::ptr::null(),
            0,
            output.as_mut_ptr(),
            1,
            &mut written,
        )
    });
    assert_eq!(10, written);
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_store_open(
            root_bytes.as_ptr(),
            root_bytes.len(),
            core::ptr::null(),
            0,
            output.as_mut_ptr(),
            output.len(),
            &mut written,
        )
    });
    assert_eq!(10, written);
    assert_eq!(&[1, 0], &output[..2]);
    let handle = u64::from_le_bytes(output[2..10].try_into().unwrap());
    assert_ne!(0, handle);
    assert_eq!(FfiStatus::Ok, compukter_store_close(handle));

    let invalid_utf8 = [0xff];
    assert_eq!(FfiStatus::InvalidArgument, unsafe {
        compukter_store_open(
            invalid_utf8.as_ptr(),
            invalid_utf8.len(),
            core::ptr::null(),
            0,
            output.as_mut_ptr(),
            output.len(),
            &mut written,
        )
    });

    let relative = b"relative";
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_store_open(
            relative.as_ptr(),
            relative.len(),
            core::ptr::null(),
            0,
            output.as_mut_ptr(),
            output.len(),
            &mut written,
        )
    });
    assert_eq!(&[1, 1], &output[..written]);
}

#[test]
fn store_lifecycle_is_typed_bounded_and_uses_exact_computer_id_bytes() {
    let root = TestRoot::new();
    let handle = open_store(root.path());
    let id = [
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e,
        0x0f,
    ];
    let mut output = [0_u8; 9];
    let mut written = 0_usize;

    assert_eq!(FfiStatus::BufferTooSmall, unsafe {
        compukter_store_health(handle, output.as_mut_ptr(), 1, &mut written)
    });
    assert_eq!(2, written);
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_store_health(handle, output.as_mut_ptr(), output.len(), &mut written)
    });
    assert_eq!(&[1, 0], &output[..written]);

    assert_eq!(FfiStatus::BufferTooSmall, unsafe {
        compukter_store_durable_generation(
            handle,
            id.as_ptr(),
            output.as_mut_ptr(),
            1,
            &mut written,
        )
    });
    assert_eq!(9, written);
    assert_eq!(FfiStatus::StoreNotFound, unsafe {
        compukter_store_durable_generation(
            handle,
            id.as_ptr(),
            output.as_mut_ptr(),
            output.len(),
            &mut written,
        )
    });
    assert_eq!(FfiStatus::StoreInvalidGeneration, unsafe {
        compukter_store_flush(handle, id.as_ptr(), 1)
    });

    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_store_tombstone(handle, id.as_ptr())
    });
    assert!(root
        .path()
        .join("computers/000102030405060708090a0b0c0d0e0f/tombstone")
        .is_file());
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_store_recover(handle, id.as_ptr())
    });
    assert!(!root
        .path()
        .join("computers/000102030405060708090a0b0c0d0e0f/tombstone")
        .exists());

    assert_eq!(FfiStatus::InvalidHandle, unsafe {
        compukter_store_health(0, output.as_mut_ptr(), output.len(), &mut written)
    });
    assert_eq!(FfiStatus::InvalidHandle, unsafe {
        compukter_store_health(u64::MAX, output.as_mut_ptr(), output.len(), &mut written)
    });
    assert_eq!(FfiStatus::Ok, compukter_store_close(handle));
    assert_eq!(FfiStatus::StaleHandle, compukter_store_close(handle));
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
fn c_abi_creates_a_machine_inside_an_open_world_store() {
    let root = TestRoot::new();
    let store = open_store(root.path());
    let artifact = terminal_artifact();
    let rom = empty_rom();
    let id = [7_u8; 16];
    let mut output = vec![0_u8; compukter_max_create_bytes()];
    let mut written = 0_usize;

    assert_eq!(FfiStatus::BufferTooSmall, unsafe {
        compukter_create_in_store(
            store,
            id.as_ptr(),
            rom.as_ptr(),
            rom.len(),
            artifact.as_ptr(),
            artifact.len(),
            output.as_mut_ptr(),
            1,
            &mut written,
        )
    });
    assert_eq!(compukter_max_create_bytes(), written);
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_create_in_store(
            store,
            id.as_ptr(),
            rom.as_ptr(),
            rom.len(),
            artifact.as_ptr(),
            artifact.len(),
            output.as_mut_ptr(),
            output.len(),
            &mut written,
        )
    });
    assert_eq!(9, written);
    assert_eq!(0, output[0]);
    let machine = u64::from_le_bytes(output[1..9].try_into().unwrap());
    assert_ne!(0, machine);
    let mut generation = [0_u8; 9];
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_filesystem_generation(
            machine,
            generation.as_mut_ptr(),
            generation.len(),
            &mut written,
        )
    });
    assert_eq!(9, written);
    assert_eq!(1, generation[0]);
    assert_eq!(0, u64::from_le_bytes(generation[1..9].try_into().unwrap()));
    assert_eq!(FfiStatus::Ok, compukter_close(machine));
    assert_eq!(FfiStatus::Ok, compukter_store_close(store));
}

#[test]
fn c_abi_boots_a_machine_from_the_executable_rom_entry() {
    let root = TestRoot::new();
    let store = open_store(root.path());
    let rom = rom_with_boot(&terminal_artifact(), true);
    let id = [9_u8; 16];
    let mut output = vec![0_u8; compukter_max_create_bytes()];
    let mut written = 0_usize;

    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_create_boot_in_store(
            store,
            id.as_ptr(),
            rom.as_ptr(),
            rom.len(),
            output.as_mut_ptr(),
            output.len(),
            &mut written,
        )
    });
    assert_eq!(9, written);
    assert_eq!(0, output[0]);
    let machine = u64::from_le_bytes(output[1..9].try_into().unwrap());
    assert_eq!(FfiStatus::Ok, compukter_close(machine));

    assert_eq!(
        vec![5, ProcessResult::NotFound.code() as u8],
        boot_create_wire(store, [10; 16], &empty_rom()),
    );
    assert_eq!(
        vec![5, ProcessResult::NotExecutable.code() as u8],
        boot_create_wire(store, [11; 16], &rom_with_boot(&terminal_artifact(), false)),
    );
    assert_eq!(
        vec![5, ProcessResult::InvalidArtifact.code() as u8],
        boot_create_wire(store, [12; 16], &rom_with_boot(b"invalid", true)),
    );
    assert_eq!(FfiStatus::Ok, compukter_store_close(store));
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
    assert_eq!(FfiStatus::Ok, compukter_terminal_commit(handle));

    let state = terminal_full_state(handle);
    assert_eq!(2, state[0]);
    assert_eq!(0, u64::from_le_bytes(state[1..9].try_into().unwrap()));
    assert_eq!(51, u16::from_le_bytes(state[9..11].try_into().unwrap()));
    assert_eq!(19, u16::from_le_bytes(state[11..13].try_into().unwrap()));
    assert_eq!(969, u32::from_le_bytes(state[13..17].try_into().unwrap()));
    let delta = terminal_changes_since(handle, 0);
    assert_eq!(0, delta[0]);
    assert_eq!(0, u64::from_le_bytes(delta[1..9].try_into().unwrap()));

    assert_eq!(FfiStatus::Ok, compukter_terminal_key(handle, 13, 0, 1),);
    let text = ['λ' as u32, 0x1f600];
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_terminal_text(handle, text.as_ptr(), text.len())
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
    support::executable_minimal_vector()
}

fn empty_rom() -> Vec<u8> {
    let mut bytes = b"CPKTROM\0\x01\0\0\0\0\0\0\0".to_vec();
    bytes.extend_from_slice(&[
        0xcc, 0x66, 0xe6, 0x9e, 0x46, 0x7a, 0xbd, 0x44, 0xda, 0x92, 0x6f, 0xbb, 0xd2, 0x70, 0x3f,
        0x99, 0xac, 0x00, 0x66, 0x27, 0x01, 0x21, 0x13, 0x00, 0xa3, 0xef, 0x3e, 0x03, 0x67, 0x3d,
        0xf5, 0x95,
    ]);
    bytes
}

fn rom_with_boot(artifact: &[u8], executable: bool) -> Vec<u8> {
    use sha2::{Digest, Sha256};

    let path = b"/rom/boot";
    let mut bytes = Vec::new();
    bytes.extend_from_slice(b"CPKTROM\0");
    bytes.extend_from_slice(&1_u16.to_le_bytes());
    bytes.extend_from_slice(&0_u16.to_le_bytes());
    bytes.extend_from_slice(&1_u32.to_le_bytes());
    bytes.extend_from_slice(&(path.len() as u32).to_le_bytes());
    bytes.extend_from_slice(path);
    bytes.push(2);
    bytes.push(u8::from(executable));
    bytes.extend_from_slice(&0_u16.to_le_bytes());
    bytes.extend_from_slice(&(artifact.len() as u64).to_le_bytes());
    bytes.extend_from_slice(artifact);
    let digest = Sha256::digest(&bytes);
    bytes.extend_from_slice(&digest);
    bytes
}

fn boot_create_wire(store: u64, id: [u8; 16], rom: &[u8]) -> Vec<u8> {
    let mut output = vec![0_u8; compukter_max_create_bytes()];
    let mut written = 0_usize;
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_create_boot_in_store(
            store,
            id.as_ptr(),
            rom.as_ptr(),
            rom.len(),
            output.as_mut_ptr(),
            output.len(),
            &mut written,
        )
    });
    output.truncate(written);
    output
}

fn open_store(root: &Path) -> u64 {
    let root_bytes = root.as_os_str().as_encoded_bytes();
    let mut output = [0_u8; 10];
    let mut written = 0_usize;
    assert_eq!(FfiStatus::Ok, unsafe {
        compukter_store_open(
            root_bytes.as_ptr(),
            root_bytes.len(),
            core::ptr::null(),
            0,
            output.as_mut_ptr(),
            output.len(),
            &mut written,
        )
    });
    assert_eq!(&[1, 0], &output[..2]);
    u64::from_le_bytes(output[2..10].try_into().unwrap())
}

struct TestRoot(PathBuf);

impl TestRoot {
    fn new() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(1);
        let path = std::env::temp_dir()
            .join("compukters-ffi-tests")
            .join(format!(
                "{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
        std::fs::create_dir_all(&path).unwrap();
        Self(path.canonicalize().unwrap())
    }

    fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for TestRoot {
    fn drop(&mut self) {
        let expected = std::env::temp_dir().join("compukters-ffi-tests");
        assert!(self.0.starts_with(expected));
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
