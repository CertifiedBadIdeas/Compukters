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

use compukter_vm::benchmarks::{
    c_comparison_next_batch, c_comparison_qemu_target_nanos, c_comparison_timeout_nanos,
    parse_c_comparison_result,
};
use compukter_vm::rv32_machine::{
    Rv32ExecutionBackendConfig, Rv32Machine, Rv32MachineConfig, Rv32MachineOutcome,
};

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

#[test]
fn comparison_build_keeps_one_rv32_kernel_object_for_both_platforms() {
    let script = fs::read_to_string(
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../scripts/compile-rv32-c-comparison.sh"),
    )
    .unwrap();

    assert_eq!(script.matches("-c \"$SOURCE_ROOT/kernel.c\"").count(), 1);
    assert!(script.matches("\"$BUILD_DIR/kernel-rv32.o\"").count() >= 3);
    assert!(script.contains("-O3 -march=native -flto"));
    assert!(script.contains("-march=rv32im_zicsr"));
    assert!(script.contains("-mabi=ilp32"));
    assert!(script.contains("kernel-object-sha256"));
    assert!(script.contains("product.elf"));
    assert!(script.contains("qemu.elf"));
}

#[test]
#[ignore = "requires the focused Clang/LLD C comparison artifacts"]
fn product_c_artifact_matches_the_fixed_native_and_qemu_oracle() {
    let path = std::env::var_os("RV32_C_PRODUCT_ELF")
        .expect("RV32_C_PRODUCT_ELF must name the product comparison ELF");
    let elf = fs::read(path).unwrap();

    for execution in [
        Rv32ExecutionBackendConfig::Cached { sets: 64 },
        Rv32ExecutionBackendConfig::Predecoded,
    ] {
        let mut machine = Rv32Machine::from_elf(
            &elf,
            Rv32MachineConfig {
                ram_size: 16 * 1024,
                debug_limit: 0,
                execution,
            },
        )
        .unwrap();
        let outcome = machine.run(20_000_000).unwrap();
        assert!(matches!(
            outcome,
            Rv32MachineOutcome::Halted {
                exit_code: -301_646_504,
                ..
            }
        ));
    }
}

#[test]
fn comparison_parser_and_calibration_reject_ambiguous_measurements() {
    assert_eq!(
        parse_c_comparison_result(b"CK_RESULT\tee053d58\n").unwrap(),
        0xee05_3d58
    );
    assert_eq!(
        parse_c_comparison_result(b"CK_RESULT\tee053d58\r\n").unwrap(),
        0xee05_3d58
    );
    assert!(parse_c_comparison_result(b"CK_RESULT\tee053d58\nextra\n").is_err());
    assert!(parse_c_comparison_result(b"CK_RESULT\tee053d5\n").is_err());
    assert!(parse_c_comparison_result(b"").is_err());

    assert_eq!(c_comparison_next_batch(1, 249, 250).unwrap(), Some(2));
    assert_eq!(c_comparison_next_batch(8, 250, 250).unwrap(), None);
    assert!(c_comparison_next_batch(u64::MAX / 2 + 1, 1, 250).is_err());
    assert_eq!(
        c_comparison_qemu_target_nanos(8_000_000).unwrap(),
        400_000_000
    );
    assert_eq!(
        c_comparison_qemu_target_nanos(1_000_000).unwrap(),
        250_000_000
    );
    assert_eq!(c_comparison_timeout_nanos(400_000_000), 6_600_000_000);
}

#[test]
fn comparison_runner_keeps_qemu_system_tcg_explicit_and_report_stable() {
    let source = fs::read_to_string(
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("examples/rv32_c_comparison.rs"),
    )
    .unwrap();

    for token in [
        "qemu-system-riscv32",
        "\"-M\".into()",
        "\"virt\".into()",
        "\"-bios\".into()",
        "\"none\".into()",
        "\"-accel\".into()",
        "\"tcg\".into()",
        "\"-nographic\".into()",
        "\"-monitor\".into()",
    ] {
        assert!(
            source.contains(token),
            "runner lacks required QEMU token {token}"
        );
    }
    assert!(source.contains("candidate\\tmode\\titerations\\tseed\\tbatch\\tchecksum"));
    assert!(!source.contains("Command::new(\"sh\")"));
    assert!(!source.contains("Command::new(\"bash\")"));
}
