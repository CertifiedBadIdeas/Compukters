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
