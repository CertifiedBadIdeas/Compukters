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
    benchmark_geomean, benchmark_normalize_nanos, benchmark_rotating_order,
    format_product_active_row, populate_product_ratios, product_backend_order, product_percentile,
    PreparedProductMachine, PreparedProductNative, ProductActiveTiming, ProductExecutionCandidate,
    ProductMachineBackend, ProductMachineImage, ProductMachineObservation, ProductMachineWorkload,
    PRODUCT_ACTIVE_REPORT_HEADER, PRODUCT_CACHE_SETS, PRODUCT_DEBUG_LIMIT, PRODUCT_RAM_BYTES,
    PRODUCT_RESIDENT_REPORT_HEADER,
};
use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::time::Instant;

struct CountingAllocator;

static ALLOCATIONS: AtomicU64 = AtomicU64::new(0);
static ALLOCATED_BYTES: AtomicU64 = AtomicU64::new(0);
static LIVE_BYTES: AtomicUsize = AtomicUsize::new(0);
static PEAK_LIVE_BYTES: AtomicUsize = AtomicUsize::new(0);

unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let pointer = unsafe { System.alloc(layout) };
        if !pointer.is_null() {
            record_allocation(layout.size());
        }
        pointer
    }

    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        let pointer = unsafe { System.alloc_zeroed(layout) };
        if !pointer.is_null() {
            record_allocation(layout.size());
        }
        pointer
    }

    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        LIVE_BYTES.fetch_sub(layout.size(), Ordering::SeqCst);
        unsafe { System.dealloc(pointer, layout) }
    }

    unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        let replacement = unsafe { System.realloc(pointer, layout, new_size) };
        if !replacement.is_null() {
            ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
            ALLOCATED_BYTES.fetch_add(new_size as u64, Ordering::Relaxed);
            if new_size >= layout.size() {
                grow_live(new_size - layout.size());
            } else {
                LIVE_BYTES.fetch_sub(layout.size() - new_size, Ordering::SeqCst);
            }
        }
        replacement
    }
}

#[global_allocator]
static GLOBAL: CountingAllocator = CountingAllocator;

fn record_allocation(bytes: usize) {
    ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
    ALLOCATED_BYTES.fetch_add(bytes as u64, Ordering::Relaxed);
    grow_live(bytes);
}

fn grow_live(bytes: usize) {
    let live = LIVE_BYTES.fetch_add(bytes, Ordering::SeqCst) + bytes;
    let mut peak = PEAK_LIVE_BYTES.load(Ordering::SeqCst);
    while live > peak {
        match PEAK_LIVE_BYTES.compare_exchange_weak(peak, live, Ordering::SeqCst, Ordering::SeqCst)
        {
            Ok(_) => break,
            Err(actual) => peak = actual,
        }
    }
}

fn main() -> Result<(), String> {
    let mut arguments = std::env::args().skip(1);
    let iterations = parse_positive("iterations", arguments.next())?;
    let warm_samples = parse_positive("warm_samples", arguments.next())?.max(21) as usize;
    let resident_samples = parse_positive("resident_samples", arguments.next())?.max(7) as usize;
    if arguments.next().is_some() {
        return Err(
            "usage: rv32_machine_benchmarks <iterations> <warm_samples> <resident_samples>"
                .to_string(),
        );
    }

    let active = measure_active(iterations, warm_samples)?;
    print_active_report(iterations, warm_samples, &active);
    let resident = measure_resident(iterations, resident_samples)?;
    print_resident_report(iterations, resident_samples, &resident);
    Ok(())
}

struct ActiveMeasurement {
    candidate: ProductExecutionCandidate,
    workload: ProductMachineWorkload,
    prepared: ActivePrepared,
    batch: u64,
    cold_nanos: u128,
    warm_nanos: Vec<u128>,
    observation: Option<ProductMachineObservation>,
    checksum: u32,
    steady_allocations: u64,
    steady_allocated_bytes: u64,
}

enum ActivePrepared {
    Native(PreparedProductNative),
    Machine {
        cold: PreparedProductMachine,
        warm: Vec<PreparedProductMachine>,
    },
}

fn measure_active(iterations: u32, warm_samples: usize) -> Result<Vec<ActiveMeasurement>, String> {
    let mut completed = Vec::with_capacity(
        ProductMachineWorkload::all().len() * ProductExecutionCandidate::all().len(),
    );
    for (workload_index, workload) in ProductMachineWorkload::all().iter().copied().enumerate() {
        let image = ProductMachineImage::new(workload, iterations)?;
        let mut measurements = ProductExecutionCandidate::all()
            .iter()
            .copied()
            .map(|candidate| {
                let prepared = match candidate {
                    ProductExecutionCandidate::NativeHost => {
                        ActivePrepared::Native(PreparedProductNative::new(workload, iterations)?)
                    }
                    ProductExecutionCandidate::Cached | ProductExecutionCandidate::Predecoded => {
                        let backend = candidate_backend(candidate);
                        ActivePrepared::Machine {
                            cold: image.prepare(backend)?,
                            warm: (0..warm_samples)
                                .map(|_| image.prepare(backend))
                                .collect::<Result<Vec<_>, String>>()?,
                        }
                    }
                };
                Ok(ActiveMeasurement {
                    candidate,
                    workload,
                    prepared,
                    batch: 1,
                    cold_nanos: 0,
                    warm_nanos: Vec::with_capacity(warm_samples),
                    observation: None,
                    checksum: 0,
                    steady_allocations: 0,
                    steady_allocated_bytes: 0,
                })
            })
            .collect::<Result<Vec<_>, String>>()?;

        let native = active_for_candidate(&mut measurements, ProductExecutionCandidate::NativeHost);
        native.batch = calibrate_native(native)?;
        for candidate_index in benchmark_rotating_order::<3>(workload_index, 0) {
            let candidate = ProductExecutionCandidate::all()[candidate_index];
            let measurement = active_for_candidate(&mut measurements, candidate);
            let (checksum, observation, nanos, allocations, allocated_bytes) =
                measure_active_execution(measurement, None)?;
            measurement.cold_nanos = nanos;
            measurement.checksum = checksum;
            measurement.observation = observation;
            measurement.steady_allocations = allocations;
            measurement.steady_allocated_bytes = allocated_bytes;
        }
        for sample_index in 0..warm_samples {
            for candidate_index in benchmark_rotating_order::<3>(workload_index, sample_index + 1) {
                let candidate = ProductExecutionCandidate::all()[candidate_index];
                let measurement = active_for_candidate(&mut measurements, candidate);
                let (checksum, observation, nanos, allocations, allocated_bytes) =
                    measure_active_execution(measurement, Some(sample_index))?;
                measurement.warm_nanos.push(nanos);
                measurement.checksum = checksum;
                measurement.observation = observation;
                measurement.steady_allocations = measurement.steady_allocations.max(allocations);
                measurement.steady_allocated_bytes =
                    measurement.steady_allocated_bytes.max(allocated_bytes);
            }
        }
        for measurement in &mut measurements {
            measurement.warm_nanos.sort_unstable();
            if measurement.steady_allocations != 0 || measurement.steady_allocated_bytes != 0 {
                return Err(format!(
                    "{} {} allocated during run: {} allocations, {} bytes",
                    measurement.workload.name(),
                    measurement.candidate.name(),
                    measurement.steady_allocations,
                    measurement.steady_allocated_bytes,
                ));
            }
        }
        completed.extend(measurements);
    }
    Ok(completed)
}

fn active_for_candidate(
    measurements: &mut [ActiveMeasurement],
    candidate: ProductExecutionCandidate,
) -> &mut ActiveMeasurement {
    measurements
        .iter_mut()
        .find(|measurement| measurement.candidate == candidate)
        .unwrap()
}

fn candidate_backend(candidate: ProductExecutionCandidate) -> ProductMachineBackend {
    match candidate {
        ProductExecutionCandidate::Cached => ProductMachineBackend::Cached,
        ProductExecutionCandidate::Predecoded => ProductMachineBackend::Predecoded,
        ProductExecutionCandidate::NativeHost => unreachable!(),
    }
}

fn calibrate_native(measurement: &mut ActiveMeasurement) -> Result<u64, String> {
    let ActivePrepared::Native(prepared) = &mut measurement.prepared else {
        return Err("native calibration requires a native candidate".to_string());
    };
    let mut batch = 1_u64;
    loop {
        let started = Instant::now();
        prepared.execute_batch(batch)?;
        if started.elapsed().as_nanos() >= 1_000_000 {
            return Ok(batch);
        }
        batch = batch
            .checked_mul(2)
            .ok_or_else(|| "native calibration batch overflow".to_string())?;
    }
}

fn measure_active_execution(
    measurement: &mut ActiveMeasurement,
    warm_index: Option<usize>,
) -> Result<(u32, Option<ProductMachineObservation>, u128, u64, u64), String> {
    ALLOCATIONS.store(0, Ordering::Relaxed);
    ALLOCATED_BYTES.store(0, Ordering::Relaxed);
    let started = Instant::now();
    let (checksum, observation) = match &mut measurement.prepared {
        ActivePrepared::Native(prepared) => {
            let observation = prepared.execute_batch(measurement.batch)?;
            (observation.checksum, None)
        }
        ActivePrepared::Machine { cold, warm } => {
            let prepared = warm_index.map_or(cold, |index| &mut warm[index]);
            let observation = prepared.execute()?;
            (observation.checksum, Some(observation))
        }
    };
    let nanos = started.elapsed().as_nanos();
    Ok((
        checksum,
        observation,
        nanos,
        ALLOCATIONS.load(Ordering::Relaxed),
        ALLOCATED_BYTES.load(Ordering::Relaxed),
    ))
}

fn print_active_report(iterations: u32, warm_samples: usize, rows: &[ActiveMeasurement]) {
    println!("RV32 product machine execution report");
    println!("iterations\t{iterations}");
    println!("warm_samples\t{warm_samples}");
    println!("ram_bytes\t{PRODUCT_RAM_BYTES}");
    println!("debug_limit\t{PRODUCT_DEBUG_LIMIT}");
    println!("cached_sets\t{PRODUCT_CACHE_SETS}");
    println!("cached_entries\t{}", PRODUCT_CACHE_SETS * 2);
    println!("{PRODUCT_ACTIVE_REPORT_HEADER}");
    let timings = rows
        .iter()
        .map(|row| {
            Ok(ProductActiveTiming {
                candidate: row.candidate,
                workload: row.workload,
                iterations,
                checksum: row.checksum,
                batch: row.batch,
                cold_nanos: benchmark_normalize_nanos(row.cold_nanos, row.batch)?,
                warm_median_nanos: benchmark_normalize_nanos(
                    product_percentile(&row.warm_nanos, 50),
                    row.batch,
                )?,
                warm_p95_nanos: benchmark_normalize_nanos(
                    product_percentile(&row.warm_nanos, 95),
                    row.batch,
                )?,
                machine: row.observation.clone(),
                steady_allocations: row.steady_allocations,
                steady_allocated_bytes: row.steady_allocated_bytes,
                vs_native: 0.0,
            })
        })
        .collect::<Result<Vec<_>, String>>()
        .and_then(populate_product_ratios)
        .unwrap();
    for timing in &timings {
        println!("{}", format_product_active_row(timing));
    }
    println!();
    println!("RV32 product machine native summary");
    println!("candidate\thost_overhead_geomean");
    for candidate in ProductExecutionCandidate::all() {
        let ratios = timings
            .iter()
            .filter(|timing| timing.candidate == *candidate)
            .map(|timing| timing.vs_native)
            .collect::<Vec<_>>();
        println!(
            "{}\t{:.6}",
            candidate.name(),
            benchmark_geomean(&ratios).unwrap()
        );
    }
    println!();
}

struct ResidentSample {
    nanos: u128,
    live_bytes: usize,
    peak_bytes: usize,
}

struct ResidentMeasurement {
    backend: ProductMachineBackend,
    population: usize,
    samples: Vec<ResidentSample>,
}

fn measure_resident(
    iterations: u32,
    sample_count: usize,
) -> Result<Vec<ResidentMeasurement>, String> {
    let image = ProductMachineImage::new(ProductMachineWorkload::PacketRing, iterations)?;
    let mut completed = Vec::with_capacity(8);
    for (population_index, population) in [1_usize, 32, 256, 1024].into_iter().enumerate() {
        let mut measurements = ProductMachineBackend::all()
            .iter()
            .copied()
            .map(|backend| ResidentMeasurement {
                backend,
                population,
                samples: Vec::with_capacity(sample_count),
            })
            .collect::<Vec<_>>();
        for sample_index in 0..sample_count {
            for backend in product_backend_order(population_index, sample_index) {
                let live_before = LIVE_BYTES.load(Ordering::SeqCst);
                PEAK_LIVE_BYTES.store(live_before, Ordering::SeqCst);
                let started = Instant::now();
                let mut machines = Vec::with_capacity(population);
                for _ in 0..population {
                    machines.push(image.prepare(backend)?);
                }
                let nanos = started.elapsed().as_nanos();
                let live_bytes = LIVE_BYTES
                    .load(Ordering::SeqCst)
                    .checked_sub(live_before)
                    .ok_or_else(|| "resident live heap underflow".to_string())?;
                let peak_bytes = PEAK_LIVE_BYTES
                    .load(Ordering::SeqCst)
                    .checked_sub(live_before)
                    .ok_or_else(|| "resident peak heap underflow".to_string())?;
                drop(machines);
                let live_after = LIVE_BYTES.load(Ordering::SeqCst);
                if live_after != live_before {
                    return Err(format!(
                        "resident {} population {} returned to {live_after} live bytes instead of baseline {live_before}",
                        backend.name(), population,
                    ));
                }
                measurements
                    .iter_mut()
                    .find(|measurement| measurement.backend == backend)
                    .unwrap()
                    .samples
                    .push(ResidentSample {
                        nanos,
                        live_bytes,
                        peak_bytes,
                    });
            }
        }
        completed.extend(measurements);
    }
    Ok(completed)
}

fn print_resident_report(iterations: u32, sample_count: usize, rows: &[ResidentMeasurement]) {
    let image = ProductMachineImage::new(ProductMachineWorkload::PacketRing, iterations).unwrap();
    println!("RV32 resident population report");
    println!("resident_samples\t{sample_count}");
    println!("workload\t{}", ProductMachineWorkload::PacketRing.name());
    println!("{PRODUCT_RESIDENT_REPORT_HEADER}");
    for row in rows {
        let mut nanos = row
            .samples
            .iter()
            .map(|sample| sample.nanos)
            .collect::<Vec<_>>();
        let mut live = row
            .samples
            .iter()
            .map(|sample| sample.live_bytes as u128)
            .collect::<Vec<_>>();
        let mut peak = row
            .samples
            .iter()
            .map(|sample| sample.peak_bytes as u128)
            .collect::<Vec<_>>();
        nanos.sort_unstable();
        live.sort_unstable();
        peak.sort_unstable();
        let resident_live_bytes = product_percentile(&live, 50);
        let cache_sets = match row.backend {
            ProductMachineBackend::Cached => PRODUCT_CACHE_SETS.to_string(),
            ProductMachineBackend::Predecoded => "-".to_string(),
        };
        println!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{:.3}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            row.backend.name(),
            row.population,
            product_percentile(&nanos, 50),
            product_percentile(&nanos, 95),
            resident_live_bytes,
            product_percentile(&peak, 50),
            resident_live_bytes as f64 / row.population as f64,
            row.population * PRODUCT_RAM_BYTES,
            image.elf_bytes().len(),
            image.executable_bytes(),
            image.rw_initialized_bytes(),
            PRODUCT_RAM_BYTES,
            PRODUCT_DEBUG_LIMIT,
            cache_sets,
        );
    }
}

fn parse_positive(name: &str, value: Option<String>) -> Result<u32, String> {
    let value = value.ok_or_else(|| format!("missing {name}"))?;
    let parsed = value
        .parse::<u32>()
        .map_err(|error| format!("invalid {name} {value:?}: {error}"))?;
    if parsed == 0 {
        return Err(format!("{name} must be positive"));
    }
    Ok(parsed)
}
