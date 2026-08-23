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
    bridge::{self, BridgeError, OwnedResponse},
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
}

const MAXIMUM_OUTCOME_BYTES: usize = 64 * 1024;
const MAXIMUM_CREATE_BYTES: usize = 9;
const MAXIMUM_INBOUND_UTF16_CODE_UNITS: usize = 4_096;

#[unsafe(no_mangle)]
pub extern "C" fn compukter_abi_version() -> u32 {
    1
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
pub extern "C" fn compukter_close(handle: u64) -> FfiStatus {
    ffi_status(|| match bridge::close(handle) {
        Ok(()) => FfiStatus::Ok,
        Err(BridgeError::Handle(error)) => handle_status(error),
        Err(_) => FfiStatus::Internal,
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
    }
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
