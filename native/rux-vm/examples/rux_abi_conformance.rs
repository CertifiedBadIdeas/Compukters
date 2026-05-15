use rux_vm::low_image::{decode_image, ImageError};
use rux_vm::low_image_runner::{LowImageSignal, LowImageVm};
use std::fs;
use std::path::{Path, PathBuf};

fn main() -> Result<(), String> {
    let mut passed = 0_usize;

    for (fixture, expected_signal) in golden_cases() {
        let bytes = read_fixture(fixture)?;
        let image = decode_image(&bytes).map_err(|error| format!("{fixture}: {error}"))?;
        let mut vm =
            LowImageVm::create(image, 1024).map_err(|error| format!("{fixture}: {error}"))?;
        let signal = vm
            .run_until_signal()
            .map_err(|error| format!("{fixture}: {error}"))?;
        if signal != expected_signal {
            return Err(format!(
                "{fixture}: expected {expected_signal:?}, got {signal:?}",
            ));
        }
        assert_manifest_exists(fixture)?;
        println!("ok golden {fixture}");
        passed += 1;
    }

    for (fixture, expected_error) in decode_error_cases() {
        let bytes = read_fixture(fixture)?;
        let error = decode_image(&bytes).expect_err("decode error fixture unexpectedly decoded");
        if error != expected_error {
            return Err(format!(
                "{fixture}: expected {expected_error:?}, got {error:?}",
            ));
        }
        assert_manifest_exists(fixture)?;
        println!("ok decode-error {fixture}");
        passed += 1;
    }

    for (fixture, expected_error) in validation_error_cases() {
        let bytes = read_fixture(fixture)?;
        let image = decode_image(&bytes).map_err(|error| format!("{fixture}: {error}"))?;
        let error = match LowImageVm::create(image, 1024) {
            Ok(_) => return Err(format!("{fixture}: validation unexpectedly passed")),
            Err(error) => error,
        };
        if !error.contains(expected_error) {
            return Err(format!(
                "{fixture}: expected error containing {expected_error:?}, got {error:?}",
            ));
        }
        assert_manifest_exists(fixture)?;
        println!("ok validation-error {fixture}");
        passed += 1;
    }

    for (fixture, expected_error) in runtime_error_cases() {
        let bytes = read_fixture(fixture)?;
        let image = decode_image(&bytes).map_err(|error| format!("{fixture}: {error}"))?;
        let mut vm =
            LowImageVm::create(image, 1024).map_err(|error| format!("{fixture}: {error}"))?;
        let error = vm
            .run_until_signal()
            .expect_err("runtime error fixture unexpectedly completed");
        if !error.contains(expected_error) {
            return Err(format!(
                "{fixture}: expected error containing {expected_error:?}, got {error:?}",
            ));
        }
        assert_manifest_exists(fixture)?;
        println!("ok runtime-error {fixture}");
        passed += 1;
    }

    println!("rux abi conformance: {passed} fixtures passed");
    Ok(())
}

fn golden_cases() -> [(&'static str, LowImageSignal); 5] {
    [
        ("minimal_return_i32", LowImageSignal::HaltI32(42)),
        ("memory_load_store", LowImageSignal::HaltI32(0x1122_3344)),
        ("calls", LowImageSignal::HaltI32(12)),
        ("branches", LowImageSignal::HaltI32(99)),
        ("i32_u32_i64_u64_arithmetic", LowImageSignal::HaltI32(1)),
    ]
}

fn decode_error_cases() -> [(&'static str, ImageError); 3] {
    [
        ("bad_magic", ImageError::InvalidMagic),
        ("bad_version", ImageError::UnsupportedVersion(2)),
        ("unknown_opcode", ImageError::UnknownInstructionTag(255)),
    ]
}

fn validation_error_cases() -> [(&'static str, &'static str); 4] {
    [
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
    ]
}

fn runtime_error_cases() -> [(&'static str, &'static str); 4] {
    [
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
    ]
}

fn read_fixture(name: &str) -> Result<Vec<u8>, String> {
    fs::read(fixture_path(name, "ruxi"))
        .map_err(|error| format!("failed to read fixture {name}.ruxi: {error}"))
}

fn assert_manifest_exists(name: &str) -> Result<(), String> {
    let path = fixture_path(name, "json");
    if path.exists() {
        Ok(())
    } else {
        Err(format!("missing fixture manifest {}", path.display()))
    }
}

fn fixture_path(name: &str, extension: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../docs/abi/fixtures")
        .join(format!("{name}.{extension}"))
}
