use rux_compiler::artifact::Rux16ArtifactTarget;
use rux_compiler::compile_rux16_artifact;
use rux_vm::computer_machine::ComputerMachine;
use rux_vm::rux16::Rux16Signal;

#[test]
fn rux16_artifact_empty_main_halts() {
    let artifact = compile_rux16_artifact("fn main() { }", Rux16ArtifactTarget::Program)
        .expect("empty main compiles to Rux16");

    assert_eq!(artifact.target, Rux16ArtifactTarget::Program);
    assert_eq!(artifact.bytes, vec![0x01, 0x00]);
}

#[test]
fn rux16_artifact_mmio_i32_store_runs_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        "fn main() { unsafe { mmio<i32>(DEBUG_WRITE).store(65); } }",
        Rux16ArtifactTarget::Bios,
    )
    .expect("debug MMIO store compiles to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 128)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"A");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
    assert_eq!(machine.exit_code(), 0);
}

#[test]
fn rux16_artifact_mmio_u8_store_runs_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        "fn main() { unsafe { mmio<u8>(DEBUG_WRITE).store(65u8); } }",
        Rux16ArtifactTarget::Bios,
    )
    .expect("debug MMIO u8 store compiles to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 128)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"A");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_rejects_out_of_range_u8_store_value() {
    let error = compile_rux16_artifact(
        "fn main() { unsafe { mmio<u8>(DEBUG_WRITE).store(256); } }",
        Rux16ArtifactTarget::Program,
    )
    .unwrap_err();

    assert!(
        error.message.contains("does not fit `u8`"),
        "{}",
        error.message
    );
}

#[test]
fn rux16_artifact_const_mmio_sequence_runs_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        "const O: i32 = 79;
         const K: i32 = 75;

         fn main() {
            unsafe {
                mmio<i32>(CONTROL_STATUS).store(STATUS_BOOTING);
                mmio<i32>(DEBUG_WRITE).store(O);
                mmio<i32>(DEBUG_WRITE).store(K);
                mmio<i32>(CONTROL_PANIC_CODE).store(STATUS_READY);
                mmio<i32>(CONTROL_STATUS).store(STATUS_READY);
            }
         }",
        Rux16ArtifactTarget::Bios,
    )
    .expect("const-backed MMIO sequence compiles to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 128)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"OK");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
    assert_eq!(machine.exit_code(), 0);
    assert_eq!(machine.panic_code(), ComputerMachine::STATUS_READY);
}

#[test]
fn rux16_artifact_inlines_unit_helper_function_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        "const O: i32 = 79;
         const K: i32 = 75;

         fn write_ok() {
            unsafe {
                mmio<i32>(DEBUG_WRITE).store(O);
                mmio<i32>(DEBUG_WRITE).store(K);
            }
         }

         fn main() {
            write_ok();
         }",
        Rux16ArtifactTarget::Bios,
    )
    .expect("unit helper call compiles to inlined Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 128)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"OK");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_rejects_recursive_helper_inline() {
    let error = compile_rux16_artifact(
        "fn again() {
            again();
         }

         fn main() {
            again();
         }",
        Rux16ArtifactTarget::Program,
    )
    .unwrap_err();

    assert!(
        error
            .message
            .contains("recursive Rux16 helper call `again`"),
        "{}",
        error.message
    );
}
