use rux_vm::low_image::{decode_image, ImageError};
use rux_vm::low_image_runner::{LowImageSignal, LowImageVm};
use std::fs;
use std::path::{Path, PathBuf};

#[test]
fn abi_golden_fixtures_decode_and_run() {
    let cases = [
        ("minimal_return_i32", LowImageSignal::HaltI32(42)),
        ("memory_load_store", LowImageSignal::HaltI32(0x1122_3344)),
        ("calls", LowImageSignal::HaltI32(12)),
        ("branches", LowImageSignal::HaltI32(99)),
        ("i32_u32_i64_u64_arithmetic", LowImageSignal::HaltI32(1)),
    ];

    for (fixture, expected_signal) in cases {
        let bytes = read_fixture(fixture);
        let image = decode_image(&bytes).expect("golden fixture decodes");
        let mut vm = LowImageVm::create(image, 1024).expect("golden fixture validates");

        assert_eq!(
            vm.run_until_signal().expect("golden fixture runs"),
            expected_signal,
            "fixture {fixture}",
        );
        assert_manifest_exists(fixture);
    }
}

#[test]
fn abi_negative_fixtures_are_rejected() {
    let decode_cases = [
        ("bad_magic", ImageError::InvalidMagic),
        ("bad_version", ImageError::UnsupportedVersion(2)),
        ("unknown_opcode", ImageError::UnknownInstructionTag(255)),
    ];
    for (fixture, expected_error) in decode_cases {
        let bytes = read_fixture(fixture);

        assert_eq!(
            decode_image(&bytes),
            Err(expected_error),
            "fixture {fixture}"
        );
        assert_manifest_exists(fixture);
    }

    let validation_cases = [
        (
            "register_out_of_bounds",
            "writes register 1 outside register count 1",
        ),
        (
            "entry_function_has_parameters",
            "entry function main must not declare parameters",
        ),
        (
            "bad_jump_target",
            "jump target 1 is outside instruction count 1",
        ),
        (
            "memory_sections_overflow",
            "memory sections require 5 bytes but memory size is 4",
        ),
    ];
    for (fixture, expected_error) in validation_cases {
        let bytes = read_fixture(fixture);
        let image = decode_image(&bytes).expect("negative fixture decodes before validation");
        let error = match LowImageVm::create(image, 1024) {
            Ok(_) => panic!("negative fixture {fixture} unexpectedly validated"),
            Err(error) => error,
        };

        assert!(
            error.contains(expected_error),
            "fixture {fixture}: expected error containing {expected_error:?}, got {error:?}",
        );
        assert_manifest_exists(fixture);
    }
}

#[test]
fn abi_runtime_error_fixtures_decode_validate_and_trap() {
    let cases = [
        ("runtime_divide_by_zero", "division by zero"),
        (
            "runtime_memory_out_of_bounds",
            "memory access 1022..1026 is outside 1024 bytes",
        ),
        (
            "runtime_scalar_return_without_register",
            "callee returned r0 but caller did not provide return register",
        ),
        (
            "runtime_unit_return_with_register",
            "callee returned unit but caller expected r0",
        ),
    ];

    for (fixture, expected_error) in cases {
        let bytes = read_fixture(fixture);
        let image = decode_image(&bytes).expect("runtime error fixture decodes");
        let mut vm = LowImageVm::create(image, 1024).expect("runtime error fixture validates");
        let error = vm
            .run_until_signal()
            .expect_err("runtime error fixture traps while running");

        assert!(
            error.contains(expected_error),
            "fixture {fixture}: expected error containing {expected_error:?}, got {error:?}",
        );
        assert_manifest_exists(fixture);
    }
}

fn read_fixture(name: &str) -> Vec<u8> {
    fs::read(fixture_path(name, "ruxi")).unwrap_or_else(|error| {
        panic!(
            "failed to read ABI fixture {}: {error}",
            fixture_path(name, "ruxi").display(),
        )
    })
}

fn assert_manifest_exists(name: &str) {
    let path = fixture_path(name, "json");
    assert!(
        path.exists(),
        "missing ABI fixture manifest {}",
        path.display()
    );
}

fn fixture_path(name: &str, extension: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../docs/abi/fixtures")
        .join(format!("{name}.{extension}"))
}
