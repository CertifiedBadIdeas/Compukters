/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

const MINIMUM_SAMPLE_NANOS: u128 = 250_000_000;
const QEMU_STARTUP_MULTIPLIER: u128 = 50;
const TIMEOUT_MULTIPLIER: u128 = 4;
const TIMEOUT_MARGIN_NANOS: u128 = 5_000_000_000;

pub fn parse_c_comparison_result(output: &[u8]) -> Result<u32, String> {
    let text = std::str::from_utf8(output)
        .map_err(|error| format!("comparison output is not UTF-8: {error}"))?;
    let line = text
        .strip_suffix("\r\n")
        .or_else(|| text.strip_suffix('\n'))
        .ok_or_else(|| "comparison output is not one terminated record".to_string())?;
    if line.contains(['\n', '\r']) {
        return Err("comparison output contains more than one record".to_string());
    }
    let checksum = line
        .strip_prefix("CK_RESULT\t")
        .ok_or_else(|| "comparison output has an unexpected record prefix".to_string())?;
    if checksum.len() != 8 || !checksum.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(
            "comparison checksum must contain exactly eight hexadecimal digits".to_string(),
        );
    }
    u32::from_str_radix(checksum, 16)
        .map_err(|error| format!("comparison checksum is invalid: {error}"))
}

pub fn c_comparison_next_batch(
    batch: u64,
    elapsed_nanos: u128,
    target_nanos: u128,
) -> Result<Option<u64>, String> {
    if batch == 0 || target_nanos == 0 {
        return Err("comparison batch and target must be positive".to_string());
    }
    if elapsed_nanos >= target_nanos {
        return Ok(None);
    }
    batch
        .checked_mul(2)
        .map(Some)
        .ok_or_else(|| "comparison batch overflowed during calibration".to_string())
}

pub fn c_comparison_qemu_target_nanos(startup_median_nanos: u128) -> Result<u128, String> {
    let startup_target = startup_median_nanos
        .checked_mul(QEMU_STARTUP_MULTIPLIER)
        .ok_or_else(|| "QEMU startup calibration target overflowed".to_string())?;
    Ok(MINIMUM_SAMPLE_NANOS.max(startup_target))
}

pub fn c_comparison_timeout_nanos(calibrated_sample_nanos: u128) -> u128 {
    calibrated_sample_nanos
        .saturating_mul(TIMEOUT_MULTIPLIER)
        .saturating_add(TIMEOUT_MARGIN_NANOS)
}
