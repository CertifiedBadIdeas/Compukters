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

use crate::{
    bridge::{self, BridgeError, CreateInStoreError, OwnedResponse, StoreBridgeError},
    handle_table::HandleError,
};

#[repr(i32)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FfiStatus {
    Ok = 0,
    InvalidArgument = 1,
    Verification = 2,
    Admission = 3,
    Start = 4,
    InvalidHandle = 5,
    StaleHandle = 6,
    BusyHandle = 7,
    HandleExhausted = 8,
    Internal = 9,
    BufferTooSmall = 10,
    Run = 11,
    Resume = 12,
    StoreNotFound = 13,
    StoreBusy = 14,
    StoreFaulted = 15,
    StoreClosed = 16,
    StoreInvalidGeneration = 17,
    StoreIo = 18,
}

const MAXIMUM_OUTCOME_BYTES: usize = 64 * 1024;
const MAXIMUM_CREATE_BYTES: usize = 9;
const MAXIMUM_INBOUND_UTF16_CODE_UNITS: usize = 4_096;
const MAXIMUM_STORE_OPEN_BYTES: usize = 10;
const MAXIMUM_STORE_HEALTH_BYTES: usize = 2;
const MAXIMUM_STORE_GENERATION_BYTES: usize = 9;
const MAXIMUM_STORE_ROOT_BYTES: usize = 32 * 1_024;
const MAXIMUM_ROM_BYTES: usize = 16 * 1_024 * 1_024;
const MAXIMUM_FILESYSTEM_LIMITS_BYTES: usize = 1 + 17 * 8;

#[unsafe(no_mangle)]
pub extern "C" fn compukter_abi_version() -> u32 {
    2
}

#[unsafe(no_mangle)]
pub extern "C" fn compukter_max_outcome_bytes() -> usize {
    MAXIMUM_OUTCOME_BYTES
}

#[unsafe(no_mangle)]
pub extern "C" fn compukter_max_create_bytes() -> usize {
    MAXIMUM_CREATE_BYTES
}

#[unsafe(no_mangle)]
/// Opens one world-scoped persistent filesystem store.
///
/// # Safety
///
/// Non-empty inputs and outputs must name readable or writable regions of the
/// declared lengths. `written_out` must always name one writable `usize`.
pub unsafe extern "C" fn compukter_store_open(
    root_utf8: *const u8,
    root_len: usize,
    limits_wire: *const u8,
    limits_len: usize,
    output: *mut u8,
    output_capacity: usize,
    written_out: *mut usize,
) -> FfiStatus {
    ffi_status(|| {
        if written_out.is_null()
            || root_len > MAXIMUM_STORE_ROOT_BYTES
            || limits_len > MAXIMUM_FILESYSTEM_LIMITS_BYTES
            || (root_len != 0 && root_utf8.is_null())
            || (limits_len != 0 && limits_wire.is_null())
            || (output_capacity != 0 && output.is_null())
        {
            return FfiStatus::InvalidArgument;
        }
        if output_capacity < MAXIMUM_STORE_OPEN_BYTES {
            // SAFETY: The validated ABI contract provides writable length output.
            unsafe { written_out.write(MAXIMUM_STORE_OPEN_BYTES) };
            return FfiStatus::BufferTooSmall;
        }
        let root_bytes = if root_len == 0 {
            Vec::new()
        } else {
            // SAFETY: The validated ABI contract provides readable root bytes.
            unsafe { core::slice::from_raw_parts(root_utf8, root_len) }.to_vec()
        };
        let root = match String::from_utf8(root_bytes) {
            Ok(root) => std::path::PathBuf::from(root),
            Err(_) => return FfiStatus::InvalidArgument,
        };
        let limits_bytes = if limits_len == 0 {
            &[][..]
        } else {
            // SAFETY: The validated ABI contract provides readable limits bytes.
            unsafe { core::slice::from_raw_parts(limits_wire, limits_len) }
        };
        let Some(limits) = crate::wire::decode_filesystem_limits(limits_bytes) else {
            return FfiStatus::InvalidArgument;
        };
        let encoded = crate::wire::encode_store_open(bridge::store_open(root, limits));
        // SAFETY: The fixed maximum was checked before the bounded encoding.
        unsafe { core::ptr::copy_nonoverlapping(encoded.as_ptr(), output, encoded.len()) };
        // SAFETY: The validated ABI contract provides writable length output.
        unsafe { written_out.write(encoded.len()) };
        FfiStatus::Ok
    })
}

#[unsafe(no_mangle)]
/// Writes the observable lifecycle health for a store.
///
/// # Safety
///
/// Non-empty output must name a writable region and `written_out` must name one
/// writable `usize`.
pub unsafe extern "C" fn compukter_store_health(
    store_handle: u64,
    output: *mut u8,
    output_capacity: usize,
    written_out: *mut usize,
) -> FfiStatus {
    ffi_status(|| {
        if written_out.is_null() || (output_capacity != 0 && output.is_null()) {
            return FfiStatus::InvalidArgument;
        }
        if output_capacity < MAXIMUM_STORE_HEALTH_BYTES {
            // SAFETY: The validated ABI contract provides writable length output.
            unsafe { written_out.write(MAXIMUM_STORE_HEALTH_BYTES) };
            return FfiStatus::BufferTooSmall;
        }
        let health = match bridge::store_health(store_handle) {
            Ok(health) => health,
            Err(error) => return store_status(error),
        };
        let encoded = crate::wire::encode_store_health(health);
        // SAFETY: The fixed maximum was checked before the fixed encoding.
        unsafe { core::ptr::copy_nonoverlapping(encoded.as_ptr(), output, encoded.len()) };
        // SAFETY: The validated ABI contract provides writable length output.
        unsafe { written_out.write(encoded.len()) };
        FfiStatus::Ok
    })
}

#[unsafe(no_mangle)]
/// Writes the latest durable generation for one computer identity.
///
/// # Safety
///
/// `id` must name 16 readable bytes. Non-empty output must name a writable
/// region and `written_out` must name one writable `usize`.
pub unsafe extern "C" fn compukter_store_durable_generation(
    store_handle: u64,
    id: *const u8,
    output: *mut u8,
    output_capacity: usize,
    written_out: *mut usize,
) -> FfiStatus {
    ffi_status(|| {
        if written_out.is_null() || id.is_null() || (output_capacity != 0 && output.is_null()) {
            return FfiStatus::InvalidArgument;
        }
        if output_capacity < MAXIMUM_STORE_GENERATION_BYTES {
            // SAFETY: The validated ABI contract provides writable length output.
            unsafe { written_out.write(MAXIMUM_STORE_GENERATION_BYTES) };
            return FfiStatus::BufferTooSmall;
        }
        // SAFETY: The C ABI requires exactly 16 readable identity bytes.
        let id = unsafe { copy_computer_id(id) };
        let generation = match bridge::store_durable_generation(store_handle, id) {
            Ok(generation) => generation,
            Err(error) => return store_status(error),
        };
        let encoded = crate::wire::encode_store_generation(generation);
        // SAFETY: The fixed maximum was checked before the fixed encoding.
        unsafe { core::ptr::copy_nonoverlapping(encoded.as_ptr(), output, encoded.len()) };
        // SAFETY: The validated ABI contract provides writable length output.
        unsafe { written_out.write(encoded.len()) };
        FfiStatus::Ok
    })
}

#[unsafe(no_mangle)]
/// Flushes one admitted computer generation.
///
/// # Safety
///
/// `id` must name 16 readable bytes.
pub unsafe extern "C" fn compukter_store_flush(
    store_handle: u64,
    id: *const u8,
    generation: u64,
) -> FfiStatus {
    ffi_status(|| {
        if id.is_null() {
            return FfiStatus::InvalidArgument;
        }
        // SAFETY: The C ABI requires exactly 16 readable identity bytes.
        store_status_result(bridge::store_flush(
            store_handle,
            unsafe { copy_computer_id(id) },
            generation,
        ))
    })
}

#[unsafe(no_mangle)]
/// Persists a tombstone for one computer identity.
///
/// # Safety
///
/// `id` must name 16 readable bytes.
pub unsafe extern "C" fn compukter_store_tombstone(store_handle: u64, id: *const u8) -> FfiStatus {
    ffi_status(|| {
        if id.is_null() {
            return FfiStatus::InvalidArgument;
        }
        // SAFETY: The C ABI requires exactly 16 readable identity bytes.
        store_status_result(bridge::store_tombstone(store_handle, unsafe {
            copy_computer_id(id)
        }))
    })
}

#[unsafe(no_mangle)]
/// Removes a retained tombstone for one computer identity.
///
/// # Safety
///
/// `id` must name 16 readable bytes.
pub unsafe extern "C" fn compukter_store_recover(store_handle: u64, id: *const u8) -> FfiStatus {
    ffi_status(|| {
        if id.is_null() {
            return FfiStatus::InvalidArgument;
        }
        // SAFETY: The C ABI requires exactly 16 readable identity bytes.
        store_status_result(bridge::store_recover(store_handle, unsafe {
            copy_computer_id(id)
        }))
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn compukter_store_close(store_handle: u64) -> FfiStatus {
    ffi_status(|| store_status_result(bridge::store_close(store_handle)))
}

#[unsafe(no_mangle)]
/// Creates a VM session and writes its bounded wire result.
///
/// # Safety
///
/// When `artifact_len` is non-zero, `artifact` must point to a readable region
/// of at least that many bytes. When `output_capacity` is non-zero, `output`
/// must point to a writable region of at least that many bytes. `written_out`
/// must always point to a writable `usize`.
pub unsafe extern "C" fn compukter_create(
    artifact: *const u8,
    artifact_len: usize,
    output: *mut u8,
    output_capacity: usize,
    written_out: *mut usize,
) -> FfiStatus {
    ffi_status(|| {
        if written_out.is_null()
            || artifact_len > compukter_vm::ArtifactLimits::default().artifact_bytes
            || (artifact_len != 0 && artifact.is_null())
            || (output_capacity != 0 && output.is_null())
        {
            return FfiStatus::InvalidArgument;
        }
        if output_capacity < MAXIMUM_CREATE_BYTES {
            // SAFETY: Null was rejected above and the C ABI requires writable output.
            unsafe { written_out.write(MAXIMUM_CREATE_BYTES) };
            return FfiStatus::BufferTooSmall;
        }
        let bytes = if artifact_len == 0 {
            Vec::new()
        } else {
            // SAFETY: The C ABI requires a readable region of `artifact_len` bytes.
            unsafe { core::slice::from_raw_parts(artifact, artifact_len) }.to_vec()
        };
        let encoded = crate::wire::encode_create(bridge::create(bytes));
        if encoded.len() > output_capacity {
            return FfiStatus::Internal;
        }
        // SAFETY: The C ABI requires `output_capacity` writable bytes and the
        // encoded result is no larger than that validated capacity.
        unsafe { core::ptr::copy_nonoverlapping(encoded.as_ptr(), output, encoded.len()) };
        // SAFETY: Null was rejected above and the C ABI requires writable output.
        unsafe { written_out.write(encoded.len()) };
        FfiStatus::Ok
    })
}

#[unsafe(no_mangle)]
/// Creates a VM session backed by one computer in an open world store.
///
/// # Safety
///
/// `id` must name 16 readable bytes. Non-empty ROM, artifact, and output
/// inputs must name readable or writable regions of their declared lengths.
/// `written_out` must always name one writable `usize`.
pub unsafe extern "C" fn compukter_create_in_store(
    store_handle: u64,
    id: *const u8,
    rom: *const u8,
    rom_len: usize,
    artifact: *const u8,
    artifact_len: usize,
    output: *mut u8,
    output_capacity: usize,
    written_out: *mut usize,
) -> FfiStatus {
    ffi_status(|| {
        if written_out.is_null()
            || id.is_null()
            || rom_len > MAXIMUM_ROM_BYTES
            || artifact_len > compukter_vm::ArtifactLimits::default().artifact_bytes
            || (rom_len != 0 && rom.is_null())
            || (artifact_len != 0 && artifact.is_null())
            || (output_capacity != 0 && output.is_null())
        {
            return FfiStatus::InvalidArgument;
        }
        if output_capacity < MAXIMUM_CREATE_BYTES {
            // SAFETY: The validated ABI contract provides writable length output.
            unsafe { written_out.write(MAXIMUM_CREATE_BYTES) };
            return FfiStatus::BufferTooSmall;
        }
        // SAFETY: The C ABI requires exactly 16 readable identity bytes.
        let id = unsafe { copy_computer_id(id) };
        let rom = if rom_len == 0 {
            Vec::new()
        } else {
            // SAFETY: The validated ABI contract provides readable ROM bytes.
            unsafe { core::slice::from_raw_parts(rom, rom_len) }.to_vec()
        };
        let artifact = if artifact_len == 0 {
            Vec::new()
        } else {
            // SAFETY: The validated ABI contract provides readable artifact bytes.
            unsafe { core::slice::from_raw_parts(artifact, artifact_len) }.to_vec()
        };
        let encoded = match bridge::create_in_store(store_handle, id, rom, artifact) {
            Ok(handle) => crate::wire::encode_create(Ok(handle)),
            Err(CreateInStoreError::Create(error)) => crate::wire::encode_create(Err(error)),
            Err(CreateInStoreError::Rom) => return FfiStatus::Admission,
            Err(CreateInStoreError::Store(error)) => return store_status(error),
        };
        // SAFETY: The fixed maximum was checked before the bounded encoding.
        unsafe { core::ptr::copy_nonoverlapping(encoded.as_ptr(), output, encoded.len()) };
        // SAFETY: The validated ABI contract provides writable length output.
        unsafe { written_out.write(encoded.len()) };
        FfiStatus::Ok
    })
}

#[unsafe(no_mangle)]
/// Creates a persistent VM session from executable `/rom/boot`.
///
/// # Safety
///
/// `id` must name 16 readable bytes. Non-empty ROM and output inputs must name
/// readable or writable regions of their declared lengths. `written_out` must
/// always name one writable `usize`.
pub unsafe extern "C" fn compukter_create_boot_in_store(
    store_handle: u64,
    id: *const u8,
    rom: *const u8,
    rom_len: usize,
    output: *mut u8,
    output_capacity: usize,
    written_out: *mut usize,
) -> FfiStatus {
    ffi_status(|| {
        if written_out.is_null()
            || id.is_null()
            || rom_len > MAXIMUM_ROM_BYTES
            || (rom_len != 0 && rom.is_null())
            || (output_capacity != 0 && output.is_null())
        {
            return FfiStatus::InvalidArgument;
        }
        if output_capacity < MAXIMUM_CREATE_BYTES {
            // SAFETY: The validated ABI contract provides writable length output.
            unsafe { written_out.write(MAXIMUM_CREATE_BYTES) };
            return FfiStatus::BufferTooSmall;
        }
        // SAFETY: The C ABI requires exactly 16 readable identity bytes.
        let id = unsafe { copy_computer_id(id) };
        let rom = if rom_len == 0 {
            Vec::new()
        } else {
            // SAFETY: The validated ABI contract provides readable ROM bytes.
            unsafe { core::slice::from_raw_parts(rom, rom_len) }.to_vec()
        };
        let encoded = match bridge::create_boot_in_store(store_handle, id, rom) {
            Ok(handle) => crate::wire::encode_create(Ok(handle)),
            Err(CreateInStoreError::Create(error)) => crate::wire::encode_create(Err(error)),
            Err(CreateInStoreError::Rom) => return FfiStatus::Admission,
            Err(CreateInStoreError::Store(error)) => return store_status(error),
        };
        // SAFETY: The fixed maximum was checked before the bounded encoding.
        unsafe { core::ptr::copy_nonoverlapping(encoded.as_ptr(), output, encoded.len()) };
        // SAFETY: The validated ABI contract provides writable length output.
        unsafe { written_out.write(encoded.len()) };
        FfiStatus::Ok
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn compukter_close(handle: u64) -> FfiStatus {
    ffi_status(|| match bridge::close(handle) {
        Ok(()) => FfiStatus::Ok,
        Err(BridgeError::Handle(error)) => handle_status(error),
        Err(_) => FfiStatus::Internal,
    })
}

#[unsafe(no_mangle)]
/// Writes the current visible filesystem generation for a VM session.
///
/// # Safety
///
/// Non-empty output must name a writable region and `written_out` must name
/// one writable `usize`.
pub unsafe extern "C" fn compukter_filesystem_generation(
    handle: u64,
    output: *mut u8,
    output_capacity: usize,
    written_out: *mut usize,
) -> FfiStatus {
    ffi_status(|| {
        if written_out.is_null() || (output_capacity != 0 && output.is_null()) {
            return FfiStatus::InvalidArgument;
        }
        if output_capacity < MAXIMUM_STORE_GENERATION_BYTES {
            // SAFETY: The validated ABI contract provides writable length output.
            unsafe { written_out.write(MAXIMUM_STORE_GENERATION_BYTES) };
            return FfiStatus::BufferTooSmall;
        }
        let generation = match bridge::filesystem_generation(handle) {
            Ok(generation) => generation,
            Err(error) => return bridge_status(Err(error)),
        };
        let encoded = crate::wire::encode_store_generation(generation);
        // SAFETY: The fixed maximum was checked before the fixed encoding.
        unsafe { core::ptr::copy_nonoverlapping(encoded.as_ptr(), output, encoded.len()) };
        // SAFETY: The validated ABI contract provides writable length output.
        unsafe { written_out.write(encoded.len()) };
        FfiStatus::Ok
    })
}

#[unsafe(no_mangle)]
/// Advances a VM session and writes its bounded outcome wire result.
///
/// # Safety
///
/// When `output_capacity` is non-zero, `output` must point to a writable region
/// of at least that many bytes. `written_out` must always point to a writable
/// `usize`.
pub unsafe extern "C" fn compukter_advance(
    handle: u64,
    guest_budget: u32,
    maintenance_budget: u32,
    output: *mut u8,
    output_capacity: usize,
    written_out: *mut usize,
) -> FfiStatus {
    ffi_status(|| {
        if written_out.is_null() || (output_capacity != 0 && output.is_null()) {
            return FfiStatus::InvalidArgument;
        }
        if output_capacity < MAXIMUM_OUTCOME_BYTES {
            // SAFETY: Null was rejected above and the C ABI requires writable output.
            unsafe { written_out.write(MAXIMUM_OUTCOME_BYTES) };
            return FfiStatus::BufferTooSmall;
        }
        let encoded = match bridge::advance(handle, guest_budget, maintenance_budget) {
            Ok(outcome) => crate::wire::encode_outcome(outcome),
            Err(BridgeError::Handle(error)) => return handle_status(error),
            Err(BridgeError::Run(_)) => return FfiStatus::Run,
            Err(BridgeError::Resume(_) | BridgeError::InvalidRequestId) => {
                return FfiStatus::Resume
            }
            Err(BridgeError::InvalidOperation) => return FfiStatus::InvalidArgument,
        };
        if encoded.len() > output_capacity {
            return FfiStatus::Internal;
        }
        // SAFETY: The C ABI requires `output_capacity` writable bytes and the
        // encoded result is no larger than that validated capacity.
        unsafe { core::ptr::copy_nonoverlapping(encoded.as_ptr(), output, encoded.len()) };
        // SAFETY: Null was rejected above and the C ABI requires writable output.
        unsafe { written_out.write(encoded.len()) };
        FfiStatus::Ok
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn compukter_resume_unit(handle: u64, request_id: u64) -> FfiStatus {
    ffi_status(|| {
        bridge_status(bridge::resume(
            handle,
            request_id,
            &OwnedResponse::SuccessUnit,
        ))
    })
}

#[unsafe(no_mangle)]
/// Resumes a host request with exact UTF-16 code units.
///
/// # Safety
///
/// When `value_len` is non-zero, `value` must point to a readable region of at
/// least that many `u16` values.
pub unsafe extern "C" fn compukter_resume_string(
    handle: u64,
    request_id: u64,
    value: *const u16,
    value_len: usize,
) -> FfiStatus {
    ffi_status(|| {
        if value_len > MAXIMUM_INBOUND_UTF16_CODE_UNITS || (value_len != 0 && value.is_null()) {
            return FfiStatus::InvalidArgument;
        }
        let units = if value_len == 0 {
            Vec::new()
        } else {
            // SAFETY: The C ABI requires a readable region of `value_len` u16 values.
            unsafe { core::slice::from_raw_parts(value, value_len) }.to_vec()
        };
        bridge_status(bridge::resume(
            handle,
            request_id,
            &OwnedResponse::SuccessString(units),
        ))
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn compukter_resume_failure(
    handle: u64,
    request_id: u64,
    kind: u32,
    code: u32,
) -> FfiStatus {
    ffi_status(|| {
        let kind = match kind {
            0 => compukter_vm::HostFailureKind::EndOfFile,
            1 => compukter_vm::HostFailureKind::Unavailable,
            2 => compukter_vm::HostFailureKind::InputOutput,
            3 => compukter_vm::HostFailureKind::Cancelled,
            4 => compukter_vm::HostFailureKind::Other,
            _ => return FfiStatus::InvalidArgument,
        };
        bridge_status(bridge::resume(
            handle,
            request_id,
            &OwnedResponse::Failure(compukter_vm::HostFailure::new(kind, code)),
        ))
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn compukter_terminal_commit(handle: u64) -> FfiStatus {
    ffi_status(|| bridge_status(bridge::terminal_commit(handle)))
}

#[unsafe(no_mangle)]
/// Writes the complete retained terminal state in logical row order.
///
/// # Safety
///
/// `output` must name `output_capacity` writable bytes when capacity is non-zero,
/// and `written_out` must always point to a writable `usize`.
pub unsafe extern "C" fn compukter_terminal_full_state(
    handle: u64,
    output: *mut u8,
    output_capacity: usize,
    written_out: *mut usize,
) -> FfiStatus {
    ffi_status(|| {
        if written_out.is_null() || (output_capacity != 0 && output.is_null()) {
            return FfiStatus::InvalidArgument;
        }
        if output_capacity < MAXIMUM_OUTCOME_BYTES {
            // SAFETY: The validated ABI contract provides writable output.
            unsafe { written_out.write(MAXIMUM_OUTCOME_BYTES) };
            return FfiStatus::BufferTooSmall;
        }
        // SAFETY: The validated ABI contract provides enough writable output.
        let output = unsafe { core::slice::from_raw_parts_mut(output, output_capacity) };
        let written = match bridge::with_terminal(handle, |terminal| {
            crate::wire::encode_terminal_full_into(output, terminal)
        }) {
            Ok(Ok(written)) => written,
            Ok(Err(_)) => return FfiStatus::Internal,
            Err(BridgeError::Handle(error)) => return handle_status(error),
            Err(_) => return FfiStatus::Internal,
        };
        // SAFETY: The validated ABI contract provides writable length output.
        unsafe { written_out.write(written) };
        FfiStatus::Ok
    })
}

#[unsafe(no_mangle)]
/// Writes unchanged, delta, or full terminal state since `revision`.
///
/// # Safety
///
/// `output` must name `output_capacity` writable bytes when capacity is non-zero,
/// and `written_out` must always point to a writable `usize`.
pub unsafe extern "C" fn compukter_terminal_changes_since(
    handle: u64,
    revision: u64,
    output: *mut u8,
    output_capacity: usize,
    written_out: *mut usize,
) -> FfiStatus {
    ffi_status(|| {
        if written_out.is_null() || (output_capacity != 0 && output.is_null()) {
            return FfiStatus::InvalidArgument;
        }
        if output_capacity < MAXIMUM_OUTCOME_BYTES {
            // SAFETY: The validated ABI contract provides writable output.
            unsafe { written_out.write(MAXIMUM_OUTCOME_BYTES) };
            return FfiStatus::BufferTooSmall;
        }
        let update = match bridge::terminal_changes_since(handle, revision) {
            Ok(update) => update,
            Err(BridgeError::Handle(error)) => return handle_status(error),
            Err(_) => return FfiStatus::Internal,
        };
        // SAFETY: The validated ABI contract provides enough writable output.
        let output = unsafe { core::slice::from_raw_parts_mut(output, output_capacity) };
        let written = match crate::wire::encode_terminal_update_into(output, &update) {
            Ok(written) => written,
            Err(_) => return FfiStatus::Internal,
        };
        // SAFETY: The validated ABI contract provides writable length output.
        unsafe { written_out.write(written) };
        FfiStatus::Ok
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn compukter_terminal_key(
    handle: u64,
    key: u16,
    action: u32,
    modifiers: u32,
) -> FfiStatus {
    ffi_status(|| bridge_status(bridge::terminal_key(handle, key, action, modifiers)))
}

#[unsafe(no_mangle)]
/// Appends one atomic Unicode text event.
///
/// # Safety
///
/// When `text_len` is non-zero, `text` must point to at least that many
/// readable `u32` Unicode scalar values.
pub unsafe extern "C" fn compukter_terminal_text(
    handle: u64,
    text: *const u32,
    text_len: usize,
) -> FfiStatus {
    ffi_status(|| {
        if text_len > MAXIMUM_INBOUND_UTF16_CODE_UNITS || (text_len != 0 && text.is_null()) {
            return FfiStatus::InvalidArgument;
        }
        let scalars = if text_len == 0 {
            &[][..]
        } else {
            // SAFETY: The validated ABI contract provides readable scalar input.
            unsafe { core::slice::from_raw_parts(text, text_len) }
        };
        let value = match scalars
            .iter()
            .copied()
            .map(char::from_u32)
            .collect::<Option<String>>()
        {
            Some(value) => value,
            None => return FfiStatus::InvalidArgument,
        };
        bridge_status(bridge::terminal_text(handle, &value))
    })
}

fn ffi_status(operation: impl FnOnce() -> FfiStatus) -> FfiStatus {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(operation)).unwrap_or(FfiStatus::Internal)
}

fn bridge_status(outcome: Result<(), BridgeError>) -> FfiStatus {
    match outcome {
        Ok(()) => FfiStatus::Ok,
        Err(BridgeError::Handle(error)) => handle_status(error),
        Err(BridgeError::Run(_)) => FfiStatus::Run,
        Err(BridgeError::Resume(_)) => FfiStatus::Resume,
        Err(BridgeError::InvalidRequestId) => FfiStatus::InvalidArgument,
        Err(BridgeError::InvalidOperation) => FfiStatus::InvalidArgument,
    }
}

fn store_status_result(outcome: Result<(), StoreBridgeError>) -> FfiStatus {
    match outcome {
        Ok(()) => FfiStatus::Ok,
        Err(error) => store_status(error),
    }
}

fn store_status(error: StoreBridgeError) -> FfiStatus {
    match error {
        StoreBridgeError::Handle(error) => handle_status(error),
        StoreBridgeError::Store(error) => match error {
            compukter_vm::StoreError::NotFound => FfiStatus::StoreNotFound,
            compukter_vm::StoreError::Busy => FfiStatus::StoreBusy,
            compukter_vm::StoreError::StorageFaulted => FfiStatus::StoreFaulted,
            compukter_vm::StoreError::Closed => FfiStatus::StoreClosed,
            compukter_vm::StoreError::InvalidGeneration => FfiStatus::StoreInvalidGeneration,
            compukter_vm::StoreError::Io => FfiStatus::StoreIo,
        },
    }
}

unsafe fn copy_computer_id(id: *const u8) -> compukter_vm::ComputerId {
    let mut bytes = [0_u8; 16];
    // SAFETY: The caller validated the ABI's fixed 16-byte readable region.
    unsafe { core::ptr::copy_nonoverlapping(id, bytes.as_mut_ptr(), bytes.len()) };
    compukter_vm::ComputerId::from_bytes(bytes)
}

fn handle_status(error: HandleError) -> FfiStatus {
    match error {
        HandleError::Invalid => FfiStatus::InvalidHandle,
        HandleError::Stale => FfiStatus::StaleHandle,
        HandleError::Busy => FfiStatus::BusyHandle,
        HandleError::Exhausted => FfiStatus::HandleExhausted,
        HandleError::Poisoned => FfiStatus::Internal,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn panic_is_contained_as_an_internal_status() {
        assert_eq!(FfiStatus::Internal, ffi_status(|| panic!("contained")));
    }
}
