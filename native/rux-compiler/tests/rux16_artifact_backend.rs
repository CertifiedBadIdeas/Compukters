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
fn rux16_artifact_loads_i32_mmio_into_local_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        "fn main() {
            unsafe {
                mmio<i32>(CONTROL_STATUS).store(STATUS_READY);
                let mut status: i32 = mmio<i32>(CONTROL_STATUS).load();
                mmio<i32>(CONTROL_PANIC_CODE).store(status);
            }
         }",
        Rux16ArtifactTarget::Bios,
    )
    .expect("i32 MMIO load into local compiles to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 128)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.panic_code(), ComputerMachine::STATUS_READY);
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_loads_u8_mmio_into_local_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        "fn main() {
            unsafe {
                let mut byte: u8 = mmio<u8>(SERIAL_INPUT_READ).load();
                mmio<u8>(DEBUG_WRITE).store(byte);
            }
         }",
        Rux16ArtifactTarget::Bios,
    )
    .expect("u8 MMIO load into local compiles to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 128)
            .expect("machine boots Rux16 BIOS flash");
    machine.push_serial_input(b"Z");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"Z");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_assigns_i32_local_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        "fn main() {
            unsafe {
                let mut status: i32 = STATUS_RESET;
                status = STATUS_READY;
                mmio<i32>(CONTROL_PANIC_CODE).store(status);
            }
         }",
        Rux16ArtifactTarget::Bios,
    )
    .expect("i32 local assignment compiles to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 128)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.panic_code(), ComputerMachine::STATUS_READY);
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_updates_i32_local_inside_while_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        "fn main() {
            unsafe {
                let mut status: i32 = mmio<i32>(CONTROL_STATUS).load();
                while status == STATUS_RESET {
                    mmio<i32>(CONTROL_STATUS).store(STATUS_READY);
                    status = mmio<i32>(CONTROL_STATUS).load();
                    mmio<u8>(DEBUG_WRITE).store(76u8);
                }
                mmio<u8>(DEBUG_WRITE).store(68u8);
            }
         }",
        Rux16ArtifactTarget::Bios,
    )
    .expect("while-local polling assignment compiles to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 128)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"LD");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_assigns_u8_local_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        "fn main() {
            unsafe {
                let mut byte: u8 = 65u8;
                byte = 66u8;
                mmio<u8>(DEBUG_WRITE).store(byte);
            }
         }",
        Rux16ArtifactTarget::Bios,
    )
    .expect("u8 local assignment compiles to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 128)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"B");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_lowers_if_eq_condition_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        "fn main() {
            unsafe {
                mmio<i32>(CONTROL_STATUS).store(STATUS_READY);
                if mmio<i32>(CONTROL_STATUS).load() == STATUS_READY {
                    mmio<u8>(DEBUG_WRITE).store(89u8);
                }
            }
         }",
        Rux16ArtifactTarget::Bios,
    )
    .expect("if equality condition compiles to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 128)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"Y");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_lowers_if_else_false_condition_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        "fn main() {
            unsafe {
                if mmio<i32>(CONTROL_STATUS).load() == STATUS_READY {
                    mmio<u8>(DEBUG_WRITE).store(84u8);
                } else {
                    mmio<u8>(DEBUG_WRITE).store(70u8);
                }
            }
         }",
        Rux16ArtifactTarget::Bios,
    )
    .expect("if/else equality condition compiles to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 128)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"F");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_lowers_while_eq_condition_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        "fn main() {
            unsafe {
                while mmio<i32>(CONTROL_STATUS).load() == STATUS_RESET {
                    mmio<i32>(CONTROL_STATUS).store(STATUS_READY);
                    mmio<u8>(DEBUG_WRITE).store(76u8);
                }
                mmio<u8>(DEBUG_WRITE).store(68u8);
            }
         }",
        Rux16ArtifactTarget::Bios,
    )
    .expect("while equality condition compiles to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 128)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"LD");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
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
