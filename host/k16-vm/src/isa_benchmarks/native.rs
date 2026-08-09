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

use super::{
    native_checksum, IsaBenchmarkCandidate, IsaBenchmarkObservation, IsaBenchmarkWorkload,
    IsaTraffic,
};
use std::hint::black_box;

pub(super) fn run(
    workload: IsaBenchmarkWorkload,
    iterations: u32,
) -> Result<IsaBenchmarkObservation, String> {
    Prepared::new(workload, iterations).execute()
}

pub(super) struct Prepared {
    workload: IsaBenchmarkWorkload,
    iterations: u32,
}

impl Prepared {
    pub(super) fn new(workload: IsaBenchmarkWorkload, iterations: u32) -> Self {
        Self {
            workload,
            iterations,
        }
    }

    pub(super) fn execute(&mut self) -> Result<IsaBenchmarkObservation, String> {
        let checksum = execute_native(self.workload, self.iterations);
        let observation = IsaBenchmarkObservation {
            candidate: IsaBenchmarkCandidate::NativeRust,
            workload: self.workload,
            iterations: self.iterations,
            checksum,
            retired_instructions: 0,
            yields: 0,
            instruction_fetch: IsaTraffic::default(),
            data_ram: IsaTraffic::default(),
            mmio: IsaTraffic::default(),
            cpu_state_bytes: 0,
            translation_bytes: 0,
        };
        observation.validate_checksum()?;
        Ok(observation)
    }
}

fn execute_native(workload: IsaBenchmarkWorkload, iterations: u32) -> u32 {
    let iterations = black_box(iterations);
    let checksum = match workload {
        IsaBenchmarkWorkload::MmioControl => native_mmio_control(iterations),
        IsaBenchmarkWorkload::YieldWake => native_yield_wake(iterations),
        _ => native_checksum(workload, iterations),
    };
    black_box(checksum)
}

fn native_mmio_control(iterations: u32) -> u32 {
    let mut checksum = 0_u32;
    for index in 0..iterations {
        let register = black_box(index);
        checksum = checksum.wrapping_add(black_box(register));
    }
    checksum
}

fn native_yield_wake(iterations: u32) -> u32 {
    let mut wakes = 0_u32;
    for _ in 0..iterations {
        wakes = black_box(wakes.wrapping_add(1));
    }
    wakes
}
