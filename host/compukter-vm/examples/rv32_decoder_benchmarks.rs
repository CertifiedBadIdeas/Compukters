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

use compukter_vm::benchmarks::{
    format_summary, format_timing_sample, populate_vs_native, timing_report_header,
    BenchmarkCandidate, BenchmarkTiming, BenchmarkWorkload, PreparedBenchmark,
};
use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;

struct CountingAllocator;

static ALLOCATIONS: AtomicU64 = AtomicU64::new(0);
static ALLOCATED_BYTES: AtomicU64 = AtomicU64::new(0);

unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
        ALLOCATED_BYTES.fetch_add(layout.size() as u64, Ordering::Relaxed);
        unsafe { System.alloc(layout) }
    }

    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
        ALLOCATED_BYTES.fetch_add(layout.size() as u64, Ordering::Relaxed);
        unsafe { System.alloc_zeroed(layout) }
    }

    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        unsafe { System.dealloc(pointer, layout) }
    }

    unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
        ALLOCATED_BYTES.fetch_add(new_size as u64, Ordering::Relaxed);
        unsafe { System.realloc(pointer, layout, new_size) }
    }
}

#[global_allocator]
static GLOBAL: CountingAllocator = CountingAllocator;

fn main() -> Result<(), String> {
    let mut arguments = std::env::args().skip(1);
    let iterations = parse_positive("iterations", arguments.next())?;
    let requested_samples = parse_positive("samples", arguments.next())? as usize;
    if arguments.next().is_some() {
        return Err("usage: rv32_decoder_benchmarks <iterations> <samples>".to_string());
    }
    let sample_count = requested_samples.max(7);

    let mut timings =
        Vec::with_capacity(BenchmarkCandidate::all().len() * BenchmarkWorkload::all().len());
    for (workload_index, workload) in BenchmarkWorkload::all().iter().enumerate() {
        timings.extend(measure_workload(
            *workload,
            workload_index,
            iterations,
            sample_count,
        )?);
    }

    println!("RV32 decoder timing and resource report");
    println!("warm_samples\t{sample_count}");
    println!("{}", timing_report_header());
    for timing in &timings {
        println!("{}", format_timing_sample(timing));
    }
    println!();
    println!("RV32 decoder summary");
    print!("{}", format_summary(&timings)?);
    Ok(())
}

struct CandidateMeasurement {
    candidate: BenchmarkCandidate,
    prepared: PreparedBenchmark,
    observation: compukter_vm::benchmarks::BenchmarkObservation,
    cold_nanos: u128,
    warm_nanos: Vec<u128>,
    steady_allocations: u64,
    steady_allocated_bytes: u64,
}

fn measure_workload(
    workload: BenchmarkWorkload,
    workload_index: usize,
    iterations: u32,
    sample_count: usize,
) -> Result<Vec<BenchmarkTiming>, String> {
    let candidates = BenchmarkCandidate::all();

    // Populate host instruction/page caches without retaining guest state.
    for candidate in candidates {
        let mut warmup = PreparedBenchmark::new(*candidate, workload, iterations.min(100))?;
        warmup.execute()?.validate_checksum()?;
    }

    let mut measurements = Vec::with_capacity(candidates.len());
    for index in candidate_order(workload_index, usize::MAX, candidates.len()) {
        let candidate = candidates[index];
        let cold_started = Instant::now();
        let mut prepared = PreparedBenchmark::new(candidate, workload, iterations)?;
        let observation = prepared.execute()?;
        let cold_nanos = cold_started.elapsed().as_nanos();
        observation.validate_checksum()?;
        measurements.push(CandidateMeasurement {
            candidate,
            prepared,
            observation,
            cold_nanos,
            warm_nanos: Vec::with_capacity(sample_count),
            steady_allocations: 0,
            steady_allocated_bytes: 0,
        });
    }

    for sample_index in 0..sample_count {
        for index in candidate_order(workload_index, sample_index, measurements.len()) {
            let measurement = &mut measurements[index];
            reset_allocation_counters();
            let started = Instant::now();
            measurement.observation = measurement.prepared.execute()?;
            let elapsed = started.elapsed().as_nanos();
            measurement.observation.validate_checksum()?;
            measurement.warm_nanos.push(elapsed);
            measurement.steady_allocations = measurement
                .steady_allocations
                .max(ALLOCATIONS.load(Ordering::Relaxed));
            measurement.steady_allocated_bytes = measurement
                .steady_allocated_bytes
                .max(ALLOCATED_BYTES.load(Ordering::Relaxed));
        }
    }

    measurements.sort_by_key(|measurement| {
        candidates
            .iter()
            .position(|candidate| *candidate == measurement.candidate)
            .unwrap()
    });
    let mut timings = measurements
        .into_iter()
        .map(finish_measurement)
        .collect::<Vec<_>>();
    populate_vs_native(&mut timings)?;
    Ok(timings)
}

fn finish_measurement(mut measurement: CandidateMeasurement) -> BenchmarkTiming {
    measurement.warm_nanos.sort_unstable();
    let median = measurement.warm_nanos[measurement.warm_nanos.len() / 2];
    let p95_index = (measurement.warm_nanos.len() * 95)
        .div_ceil(100)
        .saturating_sub(1);
    BenchmarkTiming {
        observation: measurement.observation,
        cold_nanos: measurement.cold_nanos,
        warm_median_nanos: median,
        warm_p95_nanos: measurement.warm_nanos[p95_index],
        steady_allocations: measurement.steady_allocations,
        steady_allocated_bytes: measurement.steady_allocated_bytes,
        vs_native: 0.0,
    }
}

fn candidate_order(workload_index: usize, sample_index: usize, count: usize) -> Vec<usize> {
    let mut order = (0..count).collect::<Vec<_>>();
    let mut state = (workload_index as u64 + 1).wrapping_mul(0x9e37_79b9_7f4a_7c15)
        ^ (sample_index as u64).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    for index in (1..count).rev() {
        state ^= state >> 12;
        state ^= state << 25;
        state ^= state >> 27;
        let selected = (state.wrapping_mul(0x2545_f491_4f6c_dd1d) as usize) % (index + 1);
        order.swap(index, selected);
    }
    order
}

fn reset_allocation_counters() {
    ALLOCATIONS.store(0, Ordering::Relaxed);
    ALLOCATED_BYTES.store(0, Ordering::Relaxed);
}

fn parse_positive(name: &str, value: Option<String>) -> Result<u32, String> {
    let raw = value.ok_or_else(|| format!("missing {name}"))?;
    let parsed = raw
        .parse::<u32>()
        .map_err(|_| format!("{name} must be a positive integer"))?;
    if parsed == 0 {
        return Err(format!("{name} must be a positive integer"));
    }
    Ok(parsed)
}

#[cfg(test)]
mod tests {
    use super::candidate_order;

    #[test]
    fn candidate_order_is_a_reproducible_permutation_that_changes_between_rounds() {
        let first = candidate_order(0, 0, 6);
        let second = candidate_order(0, 1, 6);
        let mut sorted = first.clone();
        sorted.sort_unstable();
        assert_eq!(sorted, vec![0, 1, 2, 3, 4, 5]);
        assert_ne!(first, second);
        assert_eq!(first, candidate_order(0, 0, 6));
    }
}
