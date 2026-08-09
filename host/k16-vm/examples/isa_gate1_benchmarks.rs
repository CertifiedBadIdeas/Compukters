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

use k16_vm::isa_benchmarks::{
    format_recommendations, format_timing_sample, timing_report_header, IsaBenchmarkCandidate,
    IsaBenchmarkTiming, IsaBenchmarkWorkload, PreparedIsaBenchmark,
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
        return Err("usage: isa_gate1_benchmarks <iterations> <samples>".to_string());
    }
    let sample_count = requested_samples.max(7);

    let mut timings =
        Vec::with_capacity(IsaBenchmarkCandidate::all().len() * IsaBenchmarkWorkload::all().len());
    for workload in IsaBenchmarkWorkload::all() {
        for candidate in IsaBenchmarkCandidate::all() {
            timings.push(measure(*candidate, *workload, iterations, sample_count)?);
        }
    }

    println!("Gate 1 timing and resource report");
    println!("warm_samples\t{sample_count}");
    println!("{}", timing_report_header());
    for timing in &timings {
        println!("{}", format_timing_sample(timing));
    }
    println!();
    println!("Gate 1 recommendation");
    print!("{}", format_recommendations(&timings)?);
    Ok(())
}

fn measure(
    candidate: IsaBenchmarkCandidate,
    workload: IsaBenchmarkWorkload,
    iterations: u32,
    sample_count: usize,
) -> Result<IsaBenchmarkTiming, String> {
    let cold_started = Instant::now();
    let mut prepared = PreparedIsaBenchmark::new(candidate, workload, iterations)?;
    let cold_observation = prepared.execute()?;
    let cold_nanos = cold_started.elapsed().as_nanos();
    cold_observation.validate_checksum()?;

    let mut warm_nanos = Vec::with_capacity(sample_count);
    let mut steady_allocations = 0;
    let mut steady_allocated_bytes = 0;
    let mut observation = cold_observation;
    for _ in 0..sample_count {
        reset_allocation_counters();
        let started = Instant::now();
        observation = prepared.execute()?;
        let elapsed = started.elapsed().as_nanos();
        observation.validate_checksum()?;
        warm_nanos.push(elapsed);
        steady_allocations = steady_allocations.max(ALLOCATIONS.load(Ordering::Relaxed));
        steady_allocated_bytes =
            steady_allocated_bytes.max(ALLOCATED_BYTES.load(Ordering::Relaxed));
    }
    warm_nanos.sort_unstable();
    let median = warm_nanos[warm_nanos.len() / 2];
    let p95_index = (warm_nanos.len() * 95).div_ceil(100).saturating_sub(1);

    Ok(IsaBenchmarkTiming {
        observation,
        cold_nanos,
        warm_median_nanos: median,
        warm_p95_nanos: warm_nanos[p95_index],
        steady_allocations,
        steady_allocated_bytes,
    })
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
