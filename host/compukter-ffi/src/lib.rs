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

mod bridge;
mod ffi_api;
mod handle_table;
mod wire;

pub use ffi_api::{
    compukter_abi_version, compukter_advance, compukter_close, compukter_create,
    compukter_max_create_bytes, compukter_max_outcome_bytes, compukter_resume_failure,
    compukter_resume_string, compukter_resume_unit, FfiStatus,
};
