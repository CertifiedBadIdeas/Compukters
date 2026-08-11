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
    format_product_active_row, native_checksum, populate_product_ratios, product_backend_order,
    product_percentile, PreparedProductMachine, PreparedProductNative, ProductActiveTiming,
    ProductExecutionCandidate, ProductMachineBackend, ProductMachineImage, ProductMachineWorkload,
    PRODUCT_ACTIVE_REPORT_HEADER, PRODUCT_RESIDENT_REPORT_HEADER,
};
use compukter_vm::rv32_machine::Rv32TranslationLookupUnit;

#[test]
fn all_vm_backends_use_identical_strict_elf() {
    for workload in ProductMachineWorkload::all() {
        let image = ProductMachineImage::new(*workload, 17).unwrap();
        let cached = image.prepare(ProductMachineBackend::Cached).unwrap();
        let predecoded = image.prepare(ProductMachineBackend::Predecoded).unwrap();
        let block_cached = image.prepare(ProductMachineBackend::BlockCached).unwrap();

        assert_eq!(cached.image_fingerprint(), predecoded.image_fingerprint());
        assert_eq!(cached.image_fingerprint(), block_cached.image_fingerprint());
        assert_eq!(&image.elf_bytes()[..4], b"\x7fELF");
        assert_eq!(image.elf_bytes()[4], 1, "ELFCLASS32");
        assert_eq!(image.elf_bytes()[5], 1, "ELFDATA2LSB");
    }
}

#[test]
fn product_workloads_halt_with_expected_checksum() {
    for backend in ProductMachineBackend::all() {
        for workload in ProductMachineWorkload::all() {
            let mut prepared = PreparedProductMachine::new(*backend, *workload, 17).unwrap();
            let observation = prepared.execute().unwrap();
            let expected = match workload {
                ProductMachineWorkload::TrapRoundtrip => 17,
                _ => native_checksum(workload.decoder_workload().unwrap(), 17),
            };

            assert!(observation.complete_machine);
            assert_eq!(observation.checksum, expected, "{backend:?} {workload:?}");
            assert!(observation.retired_instructions > 0);
        }
    }
}

#[test]
fn product_observation_reports_backend_owned_storage() {
    let mut cached = PreparedProductMachine::new(
        ProductMachineBackend::Cached,
        ProductMachineWorkload::PacketRing,
        8,
    )
    .unwrap();
    let cached = cached.execute().unwrap();
    assert_eq!(cached.ram_bytes, 16 * 1024);
    assert!(cached.executable_bytes > 0);
    assert!(cached.translation_bytes > 0);
    let cached_stats = cached.translation_stats.unwrap();
    assert_eq!(
        cached_stats.lookup_unit,
        Rv32TranslationLookupUnit::Instruction
    );
    assert!(cached_stats.misses > 0);

    let mut predecoded = PreparedProductMachine::new(
        ProductMachineBackend::Predecoded,
        ProductMachineWorkload::PacketRing,
        8,
    )
    .unwrap();
    let predecoded = predecoded.execute().unwrap();
    assert!(predecoded.translation_bytes >= predecoded.executable_bytes);
    assert!(predecoded.translation_stats.is_none());

    let mut block_cached = PreparedProductMachine::new(
        ProductMachineBackend::BlockCached,
        ProductMachineWorkload::PacketRing,
        8,
    )
    .unwrap();
    let block_cached = block_cached.execute().unwrap();
    let block_stats = block_cached.translation_stats.unwrap();
    assert_eq!(block_stats.lookup_unit, Rv32TranslationLookupUnit::Block);
    assert!(block_stats.blocks_built > 0);
    assert!(block_stats.decoded_slots_built >= block_stats.blocks_built);
    assert!(block_cached.translation_bytes > 0);
}

#[test]
fn product_sampling_order_is_interleaved_and_percentiles_are_stable() {
    assert_eq!(
        product_backend_order(0, 0),
        [
            ProductMachineBackend::Cached,
            ProductMachineBackend::Predecoded,
            ProductMachineBackend::BlockCached,
        ]
    );
    assert_eq!(
        product_backend_order(0, 1),
        [
            ProductMachineBackend::Predecoded,
            ProductMachineBackend::BlockCached,
            ProductMachineBackend::Cached,
        ]
    );
    assert_eq!(product_percentile(&[10, 20, 30, 40, 50], 50), 30);
    assert_eq!(product_percentile(&[10, 20, 30, 40, 50], 95), 50);
}

#[test]
fn native_product_workloads_match_machine_checksums() {
    for workload in ProductMachineWorkload::all() {
        let mut native = PreparedProductNative::new(*workload, 17).unwrap();
        let observation = native.execute_batch(3).unwrap();
        let expected = match workload {
            ProductMachineWorkload::TrapRoundtrip => 17,
            _ => native_checksum(workload.decoder_workload().unwrap(), 17),
        };
        assert_eq!(observation.checksum, expected);
        assert_eq!(observation.batch, 3);
    }
}

#[test]
fn product_timing_math_is_normalized_and_rotated() {
    assert_eq!(benchmark_normalize_nanos(10_001, 10).unwrap(), 1_000.1);
    assert_eq!(benchmark_rotating_order::<3>(0, 0), [0, 1, 2]);
    assert_eq!(benchmark_rotating_order::<3>(0, 1), [1, 2, 0]);
    assert_eq!(benchmark_rotating_order::<3>(1, 1), [2, 0, 1]);
    assert_eq!(benchmark_rotating_order::<4>(0, 1), [1, 2, 3, 0]);
    assert!((benchmark_geomean(&[4.0, 9.0]).unwrap() - 6.0).abs() < 1e-12);
    assert_eq!(ProductExecutionCandidate::NativeHost.name(), "native-host");
    assert_eq!(
        ProductExecutionCandidate::BlockCached.name(),
        "rv32-block-cached"
    );
}

#[test]
fn resident_report_header_remains_stable() {
    assert_eq!(
        PRODUCT_RESIDENT_REPORT_HEADER,
        "backend\tpopulation\tconstruction_median_ns\tconstruction_p95_ns\tresident_live_bytes\tpeak_construction_bytes\tlive_bytes_per_machine\taggregate_ram_bytes\telf_bytes\texecutable_bytes\trw_initialized_bytes\tram_bytes\tdebug_limit\tcache_sets\tblock_cache_sets\tblock_max_instructions"
    );
    assert_eq!(
        PRODUCT_ACTIVE_REPORT_HEADER,
        "workload\tcandidate\titerations\tchecksum\tbatch\tcold_ns\twarm_median_ns\twarm_p95_ns\toperations_per_second\tretired_instructions\tlookup_unit\tcache_hits\tcache_misses\tcache_evictions\tblocks_built\tdecoded_slots_built\tram_bytes\texecutable_bytes\ttranslation_bytes\tsteady_allocations\tsteady_allocated_bytes\tvs_native"
    );
}

#[test]
fn native_rows_use_dashes_and_vm_ratios_use_normalized_medians() {
    let native = ProductActiveTiming::native(
        ProductMachineWorkload::Compute32,
        1000,
        7,
        2_500.0,
        2_700.0,
        42,
    );
    let cached = ProductActiveTiming::machine(
        ProductExecutionCandidate::Cached,
        ProductMachineWorkload::Compute32,
        1000,
        100_000.0,
        110_000.0,
        1_133_597_426,
    );
    let rows = populate_product_ratios(vec![native, cached]).unwrap();
    assert_eq!(rows[0].vs_native, 1.0);
    assert_eq!(rows[1].vs_native, 40.0);
    assert!(format_product_active_row(&rows[0]).contains("\t-\t-\t-\t-\t-\t-\t"));
}
