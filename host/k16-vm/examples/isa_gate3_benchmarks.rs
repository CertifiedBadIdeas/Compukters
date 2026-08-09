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

use std::alloc::{GlobalAlloc, Layout, System};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;

use k16_vm::compiled_c::artifact::{
    load_compiled_c_artifact, validate_f32r32_artifact_pair, CompiledCArtifact, CompiledCCandidate,
};
use k16_vm::compiled_c::report::{
    format_decision_for_candidate, format_timing_sample, populate_vs_native, timing_report_header,
    CompiledCTiming,
};
use k16_vm::compiled_c::runner::{run_compiled_c, CompiledCObservation, PreparedCompiledC};
use k16_vm::isa_benchmarks::{native_checksum, IsaBenchmarkWorkload};

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
    let artifact_root = PathBuf::from(arguments.next().ok_or("missing artifact-root")?);
    let iterations = parse_positive("iterations", arguments.next())?;
    let sample_count = parse_positive("warm-samples", arguments.next())? as usize;
    if sample_count % 2 == 0 {
        return Err("warm-samples must be odd".to_string());
    }
    if arguments.next().is_some() {
        return Err(
            "usage: isa_gate3_benchmarks <artifact-root> <iterations> <warm-samples>".to_string(),
        );
    }
    let max_steps = u64::from(iterations)
        .checked_mul(10_000)
        .and_then(|steps| steps.checked_add(1_000_000))
        .ok_or("instruction limit calculation overflow")?;
    let artifacts = load_artifacts(&artifact_root)?;

    for artifact in &artifacts {
        run_compiled_c(artifact, iterations, max_steps)?;
    }

    let mut measurements = Vec::with_capacity(artifacts.len());
    for index in measurement_order(0, usize::MAX, artifacts.len()) {
        let artifact = &artifacts[index];
        let started = Instant::now();
        let prepared = PreparedCompiledC::new(artifact)?;
        let cold_predecode_nanos = started.elapsed().as_nanos();
        measurements.push(CandidateMeasurement {
            workload: artifact.workload,
            prepared,
            observation: run_compiled_c(artifact, iterations, max_steps)?,
            cold_predecode_nanos,
            warm_nanos: Vec::with_capacity(sample_count),
            steady_allocations: 0,
            steady_allocated_bytes: 0,
        });
    }
    let workloads = IsaBenchmarkWorkload::all()
        .iter()
        .copied()
        .take(6)
        .collect::<Vec<_>>();
    let mut native = workloads
        .iter()
        .copied()
        .map(|workload| NativeMeasurement {
            workload,
            checksum: native_checksum(workload, iterations),
            warm_nanos: Vec::with_capacity(sample_count),
            steady_allocations: 0,
            steady_allocated_bytes: 0,
        })
        .collect::<Vec<_>>();

    for sample_index in 0..sample_count {
        for index in measurement_order(1, sample_index, measurements.len()) {
            let measurement = &mut measurements[index];
            reset_allocation_counters();
            let started = Instant::now();
            let observation = measurement.prepared.execute(iterations, max_steps)?;
            let elapsed = started.elapsed().as_nanos();
            measurement.steady_allocations = measurement
                .steady_allocations
                .max(ALLOCATIONS.load(Ordering::Relaxed));
            measurement.steady_allocated_bytes = measurement
                .steady_allocated_bytes
                .max(ALLOCATED_BYTES.load(Ordering::Relaxed));
            measurement.observation = observation;
            measurement.warm_nanos.push(elapsed);
        }
        for index in measurement_order(2, sample_index, native.len()) {
            let measurement = &mut native[index];
            reset_allocation_counters();
            let started = Instant::now();
            let checksum = native_checksum(measurement.workload, iterations);
            let elapsed = started.elapsed().as_nanos();
            if checksum != measurement.checksum {
                return Err(format!(
                    "native checksum changed for {}",
                    measurement.workload.name()
                ));
            }
            measurement.steady_allocations = measurement
                .steady_allocations
                .max(ALLOCATIONS.load(Ordering::Relaxed));
            measurement.steady_allocated_bytes = measurement
                .steady_allocated_bytes
                .max(ALLOCATED_BYTES.load(Ordering::Relaxed));
            measurement.warm_nanos.push(elapsed);
        }
    }

    let mut timings = measurements
        .into_iter()
        .map(finish_candidate)
        .collect::<Vec<_>>();
    timings.sort_by_key(|timing| {
        (
            workload_index(timing.observation.workload),
            timing.observation.candidate.name(),
        )
    });
    let mut native = native
        .into_iter()
        .map(finish_native)
        .collect::<Result<Vec<_>, _>>()?;
    native.sort_by_key(|timing| workload_index(timing.workload));
    if native
        .iter()
        .any(|timing| timing.steady_allocations != 0 || timing.steady_allocated_bytes != 0)
    {
        return Err("native reference made a steady allocation".to_string());
    }
    let native_durations = native
        .iter()
        .map(|timing| (timing.workload, timing.warm_median_nanos))
        .collect::<Vec<_>>();
    populate_vs_native(&mut timings, &native_durations)?;
    let decision = format_decision_for_candidate(&timings, CompiledCCandidate::K16F32R32LR)?;

    println!("ISA Gate 3 compiled-C benchmark");
    println!("warm_samples\t{sample_count}");
    println!("{}", timing_report_header());
    for timing in &timings {
        println!("{}", format_timing_sample(timing));
    }
    for timing in &native {
        println!("{}", format_native_sample(timing, iterations));
    }
    println!();
    println!("ISA Gate 3 decision");
    print!("{decision}");
    Ok(())
}

struct CandidateMeasurement {
    workload: IsaBenchmarkWorkload,
    prepared: PreparedCompiledC,
    observation: CompiledCObservation,
    cold_predecode_nanos: u128,
    warm_nanos: Vec<u128>,
    steady_allocations: u64,
    steady_allocated_bytes: u64,
}

struct NativeMeasurement {
    workload: IsaBenchmarkWorkload,
    checksum: u32,
    warm_nanos: Vec<u128>,
    steady_allocations: u64,
    steady_allocated_bytes: u64,
}

struct NativeTiming {
    workload: IsaBenchmarkWorkload,
    checksum: u32,
    warm_median_nanos: u128,
    warm_p95_nanos: u128,
    steady_allocations: u64,
    steady_allocated_bytes: u64,
}

fn load_artifacts(root: &Path) -> Result<Vec<CompiledCArtifact>, String> {
    let mut artifacts = Vec::with_capacity(12);
    for workload in IsaBenchmarkWorkload::all().iter().copied().take(6) {
        let directory = root.join(workload.name());
        let k16 = load_compiled_c_artifact(&directory.join("k16-f32r32-lr.manifest"))?;
        let rv = load_compiled_c_artifact(&directory.join("rv32im.manifest"))?;
        validate_f32r32_artifact_pair(&k16, &rv)?;
        artifacts.push(k16);
        artifacts.push(rv);
    }
    Ok(artifacts)
}

fn finish_candidate(mut measurement: CandidateMeasurement) -> CompiledCTiming {
    debug_assert_eq!(measurement.workload, measurement.observation.workload);
    measurement.warm_nanos.sort_unstable();
    CompiledCTiming {
        observation: measurement.observation,
        cold_predecode_nanos: measurement.cold_predecode_nanos,
        warm_median_nanos: median(&measurement.warm_nanos),
        warm_p95_nanos: p95(&measurement.warm_nanos),
        steady_allocations: measurement.steady_allocations,
        steady_allocated_bytes: measurement.steady_allocated_bytes,
        vs_native: 0.0,
    }
}

fn finish_native(mut measurement: NativeMeasurement) -> Result<NativeTiming, String> {
    measurement.warm_nanos.sort_unstable();
    let warm_median_nanos = median(&measurement.warm_nanos);
    if warm_median_nanos == 0 {
        return Err(format!(
            "native workload {} has zero warm duration",
            measurement.workload.name()
        ));
    }
    Ok(NativeTiming {
        workload: measurement.workload,
        checksum: measurement.checksum,
        warm_median_nanos,
        warm_p95_nanos: p95(&measurement.warm_nanos),
        steady_allocations: measurement.steady_allocations,
        steady_allocated_bytes: measurement.steady_allocated_bytes,
    })
}

fn format_native_sample(timing: &NativeTiming, iterations: u32) -> String {
    format!(
        "{}\tnative-rust\t{}\t{}\t0\t{}\t{}\t{:.3}\t0\t0\t0\t0\t0\t0\t0\t{}\t{}\t1.000000",
        timing.workload.name(),
        iterations,
        timing.checksum,
        timing.warm_median_nanos,
        timing.warm_p95_nanos,
        timing.warm_median_nanos as f64 / f64::from(iterations),
        timing.steady_allocations,
        timing.steady_allocated_bytes,
    )
}

fn median(samples: &[u128]) -> u128 {
    samples[samples.len() / 2]
}

fn p95(samples: &[u128]) -> u128 {
    let index = (samples.len() * 95).div_ceil(100).saturating_sub(1);
    samples[index]
}

fn measurement_order(phase: usize, round: usize, count: usize) -> Vec<usize> {
    let mut order = (0..count).collect::<Vec<_>>();
    let mut state = (phase as u64 + 1).wrapping_mul(0x9e37_79b9_7f4a_7c15)
        ^ (round as u64).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    for index in (1..count).rev() {
        state ^= state >> 12;
        state ^= state << 25;
        state ^= state >> 27;
        let selected = (state.wrapping_mul(0x2545_f491_4f6c_dd1d) as usize) % (index + 1);
        order.swap(index, selected);
    }
    order
}

fn workload_index(workload: IsaBenchmarkWorkload) -> usize {
    IsaBenchmarkWorkload::all()
        .iter()
        .position(|candidate| *candidate == workload)
        .unwrap()
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
    use super::measurement_order;

    #[test]
    fn measurement_order_is_reproducible_and_changes_between_rounds() {
        let first = measurement_order(1, 0, 12);
        let second = measurement_order(1, 1, 12);
        let mut sorted = first.clone();
        sorted.sort_unstable();
        assert_eq!(sorted, (0..12).collect::<Vec<_>>());
        assert_ne!(first, second);
        assert_eq!(first, measurement_order(1, 0, 12));
    }
}
