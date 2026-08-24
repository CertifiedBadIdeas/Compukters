/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

mod bridge;
mod ffi_api;
mod handle_table;
mod wire;

pub use ffi_api::{
    compukter_abi_version, compukter_advance, compukter_close, compukter_compilation_complete,
    compukter_compilation_request_copy, compukter_compilation_request_size, compukter_create,
    compukter_create_boot_in_store, compukter_create_in_store, compukter_filesystem_generation,
    compukter_max_create_bytes, compukter_max_outcome_bytes, compukter_resume_failure,
    compukter_resume_string, compukter_resume_unit, compukter_store_close,
    compukter_store_durable_generation, compukter_store_flush, compukter_store_health,
    compukter_store_open, compukter_store_recover, compukter_store_tombstone,
    compukter_terminal_changes_since, compukter_terminal_commit, compukter_terminal_full_state,
    compukter_terminal_key, compukter_terminal_text, compukter_verify_artifact, FfiStatus,
};
