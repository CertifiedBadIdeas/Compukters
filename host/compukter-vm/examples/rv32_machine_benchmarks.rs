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
    product_backend_order, product_percentile, PreparedProductMachine, ProductMachineBackend,
    ProductMachineImage, ProductMachineObservation, ProductMachineWorkload, PRODUCT_CACHE_SETS,
    PRODUCT_DEBUG_LIMIT, PRODUCT_RAM_BYTES,
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
    backend: ProductMachineBackend,
    workload: ProductMachineWorkload,
    cold: PreparedProductMachine,
    warm: Vec<PreparedProductMachine>,
    cold_nanos: u128,
    warm_nanos: Vec<u128>,
    observation: Option<ProductMachineObservation>,
    steady_allocations: u64,
    steady_allocated_bytes: u64,
}

fn measure_active(iterations: u32, warm_samples: usize) -> Result<Vec<ActiveMeasurement>, String> {
    let mut completed = Vec::with_capacity(ProductMachineWorkload::all().len() * 2);
    for (workload_index, workload) in ProductMachineWorkload::all().iter().copied().enumerate() {
        let image = ProductMachineImage::new(workload, iterations)?;
        let mut measurements = ProductMachineBackend::all()
            .iter()
            .copied()
            .map(|backend| {
                Ok(ActiveMeasurement {
                    backend,
                    workload,
                    cold: image.prepare(backend)?,
                    warm: (0..warm_samples)
                        .map(|_| image.prepare(backend))
                        .collect::<Result<Vec<_>, String>>()?,
                    cold_nanos: 0,
                    warm_nanos: Vec::with_capacity(warm_samples),
                    observation: None,
                    steady_allocations: 0,
                    steady_allocated_bytes: 0,
                })
            })
            .collect::<Result<Vec<_>, String>>()?;

        for backend in product_backend_order(workload_index, 0) {
            let measurement = active_for_backend(&mut measurements, backend);
            let (observation, nanos, allocations, allocated_bytes) =
                measure_execution(&mut measurement.cold)?;
            measurement.cold_nanos = nanos;
            measurement.observation = Some(observation);
            measurement.steady_allocations = allocations;
            measurement.steady_allocated_bytes = allocated_bytes;
        }
        for sample_index in 0..warm_samples {
            for backend in product_backend_order(workload_index, sample_index + 1) {
                let measurement = active_for_backend(&mut measurements, backend);
                let (observation, nanos, allocations, allocated_bytes) =
                    measure_execution(&mut measurement.warm[sample_index])?;
                measurement.warm_nanos.push(nanos);
                measurement.observation = Some(observation);
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
                    measurement.backend.name(),
                    measurement.steady_allocations,
                    measurement.steady_allocated_bytes,
                ));
            }
        }
        completed.extend(measurements);
    }
    Ok(completed)
}

fn active_for_backend(
    measurements: &mut [ActiveMeasurement],
    backend: ProductMachineBackend,
) -> &mut ActiveMeasurement {
    measurements
        .iter_mut()
        .find(|measurement| measurement.backend == backend)
        .unwrap()
}

fn measure_execution(
    prepared: &mut PreparedProductMachine,
) -> Result<(ProductMachineObservation, u128, u64, u64), String> {
    ALLOCATIONS.store(0, Ordering::Relaxed);
    ALLOCATED_BYTES.store(0, Ordering::Relaxed);
    let started = Instant::now();
    let observation = prepared.execute()?;
    let nanos = started.elapsed().as_nanos();
    Ok((
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
    println!("workload\tbackend\titerations\tchecksum\tcold_ns\twarm_median_ns\twarm_p95_ns\tns_per_iteration\tretired_instructions\tcache_hits\tcache_misses\tram_bytes\texecutable_bytes\ttranslation_bytes\tsteady_allocations\tsteady_allocated_bytes");
    for row in rows {
        let observation = row.observation.as_ref().unwrap();
        let median = product_percentile(&row.warm_nanos, 50);
        let p95 = product_percentile(&row.warm_nanos, 95);
        let (cache_hits, cache_misses) = observation.cache_stats.map_or_else(
            || ("-".to_string(), "-".to_string()),
            |stats| (stats.hits.to_string(), stats.misses.to_string()),
        );
        println!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{:.3}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            row.workload.name(),
            row.backend.name(),
            observation.iterations,
            observation.checksum,
            row.cold_nanos,
            median,
            p95,
            median as f64 / f64::from(iterations),
            observation.retired_instructions,
            cache_hits,
            cache_misses,
            observation.ram_bytes,
            observation.executable_bytes,
            observation.translation_bytes,
            row.steady_allocations,
            row.steady_allocated_bytes,
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
    println!("backend\tpopulation\tconstruction_median_ns\tconstruction_p95_ns\tresident_live_bytes\tpeak_construction_bytes\tlive_bytes_per_machine\taggregate_ram_bytes\telf_bytes\texecutable_bytes\trw_initialized_bytes\tram_bytes\tdebug_limit\tcache_sets");
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
