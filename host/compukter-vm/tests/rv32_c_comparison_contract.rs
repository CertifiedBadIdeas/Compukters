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

use std::fs;
use std::path::PathBuf;

fn workload_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../tools/benchmarks/rv32-c-comparison")
}

#[test]
fn portable_c_kernel_has_one_platform_neutral_entrypoint() {
    let header = fs::read_to_string(workload_root().join("kernel.h")).unwrap();
    let source = fs::read_to_string(workload_root().join("kernel.c")).unwrap();

    assert!(header.contains("uint32_t benchmark_kernel(uint32_t iterations, uint32_t seed);"));
    assert!(source.contains("CK_COMPUTE_ROUNDS"));
    assert!(source.contains("CK_ARRAY_WORDS"));
    assert!(source.contains("CK_COPY_BYTES"));
    assert!(header.contains("#define CK_ORACLE_ITERATIONS 1000u"));
    assert!(header.contains("#define CK_ORACLE_SEED 0x12345678u"));
    assert!(header.contains("#define CK_ORACLE_CHECKSUM 3993320792u"));

    for forbidden in [
        "malloc",
        "free(",
        "printf",
        "puts(",
        "clock(",
        "fopen",
        "volatile",
        "0x10000000",
    ] {
        assert!(
            !source.contains(forbidden),
            "portable kernel contains forbidden platform/libc token {forbidden}"
        );
    }
}
