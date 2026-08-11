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
    native_checksum, product_backend_order, product_percentile, PreparedProductMachine,
    ProductMachineBackend, ProductMachineImage, ProductMachineWorkload,
};

#[test]
fn cached_and_predecoded_use_identical_strict_elf() {
    for workload in ProductMachineWorkload::all() {
        let image = ProductMachineImage::new(*workload, 17).unwrap();
        let cached = image.prepare(ProductMachineBackend::Cached).unwrap();
        let predecoded = image.prepare(ProductMachineBackend::Predecoded).unwrap();

        assert_eq!(cached.image_fingerprint(), predecoded.image_fingerprint());
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
    assert!(cached.cache_stats.unwrap().misses > 0);

    let mut predecoded = PreparedProductMachine::new(
        ProductMachineBackend::Predecoded,
        ProductMachineWorkload::PacketRing,
        8,
    )
    .unwrap();
    let predecoded = predecoded.execute().unwrap();
    assert!(predecoded.translation_bytes >= predecoded.executable_bytes);
    assert!(predecoded.cache_stats.is_none());
}

#[test]
fn product_sampling_order_is_interleaved_and_percentiles_are_stable() {
    assert_eq!(
        product_backend_order(0, 0),
        [
            ProductMachineBackend::Cached,
            ProductMachineBackend::Predecoded
        ]
    );
    assert_eq!(
        product_backend_order(0, 1),
        [
            ProductMachineBackend::Predecoded,
            ProductMachineBackend::Cached
        ]
    );
    assert_eq!(product_percentile(&[10, 20, 30, 40, 50], 50), 30);
    assert_eq!(product_percentile(&[10, 20, 30, 40, 50], 95), 50);
}
