use rux_compiler::artifact::Rux16ArtifactTarget;
use rux_compiler::compile_rux16_artifact;
use rux_compiler::rux16_disasm;
use rux_compiler::ruxe;
use rux_vm::computer_machine::ComputerMachine;
use rux_vm::rux16::Rux16Signal;
use rux_vm::rux_computer::RuxComputerHandle;
use std::path::Path;

const TEST_COMPUTER_ABI_IMPORT: &str =
    "use rux::abi::computer::{control, debug, serial_input, status};";

fn with_computer_abi(source: &str) -> String {
    format!("{TEST_COMPUTER_ABI_IMPORT}\n{source}")
}

#[test]
fn rux16_artifact_empty_main_halts() {
    let artifact = compile_rux16_artifact("fn main() { }", Rux16ArtifactTarget::Boot)
        .expect("empty main compiles to Rux16");

    assert_eq!(artifact.target, Rux16ArtifactTarget::Boot);
    let executable = ruxe::decode_rux16_executable(&artifact.bytes).expect("RUXE decodes");
    assert_eq!(executable.abi_kind, ruxe::RuxeAbiKind::Bootloader);
    assert_eq!(executable.entry_pc, 2048);
    assert_eq!(executable.load_addr, 2048);
    assert_eq!(executable.payload, vec![0x01, 0x00]);
}

#[test]
fn rux16_kernel_artifact_is_ruxe_fixed_image() {
    let artifact = compile_rux16_artifact("fn main() { }", Rux16ArtifactTarget::Kernel)
        .expect("empty main compiles to kernel RUXE");

    assert_eq!(artifact.target, Rux16ArtifactTarget::Kernel);
    let executable = ruxe::decode_rux16_executable(&artifact.bytes).expect("RUXE decodes");
    assert_eq!(executable.abi_kind, ruxe::RuxeAbiKind::Kernel);
    assert_eq!(executable.entry_pc, 0x4000);
    assert_eq!(executable.load_addr, 0x4000);
    assert_eq!(executable.payload, vec![0x01, 0x00]);
}

#[test]
fn rux16_program_artifact_is_rejected_until_user_space_abi_exists() {
    let artifact =
        compile_rux16_artifact("fn main() { }", Rux16ArtifactTarget::Program).unwrap_err();

    assert!(
        artifact
            .message
            .contains("user-space program ABI is not defined yet"),
        "{}",
        artifact.message
    );
}

#[test]
fn rux16_bios_firmware_source_runs_from_bios_flash() {
    let artifact = compile_bundled_rux16_bios();
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 1024)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(
        machine.debug_output_bytes(),
        b"RUX16 BIOS\nNO BOOTABLE DEVICE\n"
    );
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
    assert_eq!(machine.panic_code(), ComputerMachine::STATUS_READY);
}

#[test]
fn rux16_bios_firmware_source_draws_no_bootable_device_screen() {
    let artifact = compile_bundled_rux16_bios();
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 1024)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );

    let snapshot = machine
        .display0_snapshot()
        .expect("computer profile maps display0");
    assert_eq!(rux16_display_row(&snapshot, 0), "RUX16 BIOS");
    assert_eq!(rux16_display_row(&snapshot, 2), "No bootable device");
}

#[test]
fn rux16_bios_firmware_source_rejects_incomplete_storage0_boot_record() {
    let artifact = compile_bundled_rux16_bios();
    let mut media = vec![0; 512];
    media[0..4].copy_from_slice(b"RUXB");

    assert_bios_rejects_boot_media(&artifact.bytes, media);
}

#[test]
fn rux16_bios_firmware_source_rejects_invalid_storage0_boot_header_fields() {
    let artifact = compile_bundled_rux16_bios();
    let payload = compile_stage2_program();
    let invalid_headers = [
        rux16_boot_media(0, 2048, 1, 1, &payload.bytes),
        rux16_boot_media(2048, 0, 1, 1, &payload.bytes),
        rux16_boot_media(2048, 2048, 0, 1, &payload.bytes),
        rux16_boot_media(2048, 2048, 1, 0, &payload.bytes),
    ];

    for media in invalid_headers {
        assert_bios_rejects_boot_media(&artifact.bytes, media);
    }
}

#[test]
fn rux16_bios_firmware_source_loads_storage0_boot_payload() {
    let artifact = compile_bundled_rux16_bios();
    let payload = compile_stage2_program();
    let payload = ruxe::decode_rux16_executable(&payload.bytes).expect("stage2 RUXE decodes");
    let media = rux16_boot_media(payload.entry_pc, payload.load_addr, 1, 1, &payload.payload);
    let mut handle = RuxComputerHandle::create_rux16_bios_flash_with_storage0_media(
        &artifact.bytes,
        64 * 1024,
        1024,
        media,
    )
    .expect("machine boots Rux16 BIOS flash with storage0 boot media");

    assert_eq!(handle.run_rux16_until_signal().unwrap(), Rux16Signal::Halt);
    assert_eq!(
        handle.debug_output_bytes(),
        b"RUX16 BIOS\nBOOT RECORD FOUND\nBOOT PAYLOAD LOADED\nS2"
    );
    assert_eq!(handle.control().status, ComputerMachine::STATUS_HALTED);
    assert_eq!(handle.control().panic_code, 82);
    assert_eq!(
        handle
            .read_guest_ram_bytes(2048, payload.payload.len() as u32)
            .unwrap(),
        payload.payload
    );
}

#[test]
fn rux16_bios_firmware_source_jumps_to_loaded_storage0_boot_payload() {
    let artifact = compile_bundled_rux16_bios();
    let payload = compile_stage2_program();
    let payload = ruxe::decode_rux16_executable(&payload.bytes).expect("stage2 RUXE decodes");
    let media = rux16_boot_media(payload.entry_pc, payload.load_addr, 1, 1, &payload.payload);
    let mut handle = RuxComputerHandle::create_rux16_bios_flash_with_storage0_media(
        &artifact.bytes,
        64 * 1024,
        1024,
        media,
    )
    .expect("machine boots Rux16 BIOS flash with executable storage0 boot media");

    assert_eq!(handle.run_rux16_until_signal().unwrap(), Rux16Signal::Halt);
    assert_eq!(
        handle.debug_output_bytes(),
        b"RUX16 BIOS\nBOOT RECORD FOUND\nBOOT PAYLOAD LOADED\nS2"
    );
    assert_eq!(handle.control().status, ComputerMachine::STATUS_HALTED);
    assert_eq!(handle.control().panic_code, 82);
}

#[test]
fn rux16_bios_firmware_source_uses_guest_storage0_read_helper() {
    let source = bundled_rux16_bios_source();

    assert!(
        source.contains("fn read_storage0_blocks("),
        "bundled BIOS should expose one guest helper for storage0 block reads"
    );
    assert_eq!(
        source
            .matches("mmio<i32>(storage0::COMMAND).store(storage0::COMMAND_READ_BLOCKS)")
            .count(),
        1,
        "storage0 read command setup should live in the helper only"
    );
}

fn compile_bundled_rux16_bios() -> rux_compiler::artifact::Rux16Artifact {
    let source = bundled_rux16_bios_source();
    compile_rux16_artifact(&source, Rux16ArtifactTarget::Bios)
        .expect("Rux16 BIOS source compiles to BIOS flash")
}

fn bundled_rux16_bios_source() -> String {
    std::fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("examples/firmware/rux16_bios.rx"),
    )
    .expect("Rux16 BIOS firmware source should exist")
}

fn compile_stage2_program() -> rux_compiler::artifact::Rux16Artifact {
    compile_rux16_artifact(
        &with_computer_abi(
            "fn main() {
            unsafe {
                mmio<u8>(debug::WRITE).store(83u8);
                mmio<u8>(debug::WRITE).store(50u8);
                mmio<i32>(control::PANIC_CODE).store(82);
            }
        }",
        ),
        Rux16ArtifactTarget::Boot,
    )
    .expect("stage2 program compiles to Rux16")
}

fn assert_bios_rejects_boot_media(bios_flash: &[u8], media: Vec<u8>) {
    let mut handle = RuxComputerHandle::create_rux16_bios_flash_with_storage0_media(
        bios_flash,
        64 * 1024,
        1024,
        media,
    )
    .expect("machine boots Rux16 BIOS flash with storage0 boot media");

    assert_eq!(handle.run_rux16_until_signal().unwrap(), Rux16Signal::Halt);
    assert_eq!(
        handle.debug_output_bytes(),
        b"RUX16 BIOS\nBOOT RECORD FOUND\nNO BOOTABLE DEVICE\n"
    );
    assert_eq!(handle.control().status, ComputerMachine::STATUS_HALTED);
    assert_eq!(handle.control().panic_code, ComputerMachine::STATUS_READY);
}

fn rux16_display_row(
    snapshot: &rux_vm::computer_machine::ComputerTextDisplaySnapshot,
    row: u32,
) -> String {
    let start = (row * snapshot.columns) as usize;
    let end = start + snapshot.columns as usize;
    let row = &snapshot.cells[start..end];
    let visible_end = row
        .iter()
        .rposition(|byte| *byte != b' ' && *byte != 0)
        .map_or(0, |index| index + 1);
    String::from_utf8_lossy(&row[..visible_end]).to_string()
}

fn rux16_boot_media(
    entry_pc: u32,
    load_addr: u32,
    block_count: u32,
    start_lba: u32,
    payload: &[u8],
) -> Vec<u8> {
    let mut media = vec![0; 1024];
    media[0..4].copy_from_slice(b"RUXB");
    media[4..8].copy_from_slice(&entry_pc.to_le_bytes());
    media[8..12].copy_from_slice(&load_addr.to_le_bytes());
    media[12..16].copy_from_slice(&block_count.to_le_bytes());
    media[16..20].copy_from_slice(&start_lba.to_le_bytes());
    media[512..512 + payload.len()].copy_from_slice(payload);
    media
}

#[test]
fn rux16_artifact_imports_abi_constants_from_rux_source() {
    let artifact = compile_rux16_artifact(
        "use rux::abi::computer::{CONTROL_PANIC_CODE, STATUS_READY};

         fn main() {
            unsafe {
                mmio<i32>(CONTROL_PANIC_CODE).store(STATUS_READY);
            }
         }",
        Rux16ArtifactTarget::Bios,
    )
    .expect("explicit ABI constant imports compile to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 128)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.panic_code(), ComputerMachine::STATUS_READY);
}

#[test]
fn rux16_artifact_imports_abi_namespace_constants_from_rux_source() {
    let artifact = compile_rux16_artifact(
        "use rux::abi::computer::{control, status};

         fn main() {
            unsafe {
                mmio<i32>(control::PANIC_CODE).store(status::READY);
            }
         }",
        Rux16ArtifactTarget::Bios,
    )
    .expect("qualified ABI namespace constants compile to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 128)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.panic_code(), ComputerMachine::STATUS_READY);
}

#[test]
fn rux16_artifact_rejects_bare_abi_constants_without_import() {
    let error = compile_rux16_artifact(
        "fn main() {
            unsafe {
                mmio<i32>(control::PANIC_CODE).store(2);
            }
         }",
        Rux16ArtifactTarget::Bios,
    )
    .unwrap_err();

    assert!(
        error
            .message
            .contains("unknown Rux16 MMIO address `control::PANIC_CODE`"),
        "{}",
        error.message
    );
}

#[test]
fn rux16_artifact_mmio_i32_store_runs_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi("fn main() { unsafe { mmio<i32>(debug::WRITE).store(65); } }"),
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
        &with_computer_abi("fn main() { unsafe { mmio<u8>(debug::WRITE).store(65u8); } }"),
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
        &with_computer_abi("fn main() { unsafe { mmio<u8>(debug::WRITE).store(256); } }"),
        Rux16ArtifactTarget::Boot,
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
        &with_computer_abi(
            "fn main() {
            unsafe {
                mmio<i32>(control::STATUS).store(status::READY);
                let mut status: i32 = mmio<i32>(control::STATUS).load();
                mmio<i32>(control::PANIC_CODE).store(status);
            }
         }",
        ),
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
        &with_computer_abi(
            "fn main() {
            unsafe {
                let mut byte: u8 = mmio<u8>(serial_input::READ).load();
                mmio<u8>(debug::WRITE).store(byte);
            }
         }",
        ),
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
        &with_computer_abi(
            "fn main() {
            unsafe {
                let mut status: i32 = status::RESET;
                status = status::READY;
                mmio<i32>(control::PANIC_CODE).store(status);
            }
         }",
        ),
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
        &with_computer_abi(
            "fn main() {
            unsafe {
                let mut status: i32 = mmio<i32>(control::STATUS).load();
                while status == status::RESET {
                    mmio<i32>(control::STATUS).store(status::READY);
                    status = mmio<i32>(control::STATUS).load();
                    mmio<u8>(debug::WRITE).store(76u8);
                }
                mmio<u8>(debug::WRITE).store(68u8);
            }
         }",
        ),
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
        &with_computer_abi(
            "fn main() {
            unsafe {
                let mut byte: u8 = 65u8;
                byte = 66u8;
                mmio<u8>(debug::WRITE).store(byte);
            }
         }",
        ),
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
fn rux16_artifact_uses_u32_local_as_mmio_address_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "fn write_ready() {
            unsafe {
                let mut panic_code: u32 = control::STATUS;
                panic_code = control::PANIC_CODE;
                let mut status_addr: u32 = control::STATUS;
                mmio<i32>(status_addr).store(status::READY);
                let mut status: i32 = mmio<i32>(status_addr).load();
                mmio<i32>(panic_code).store(status);
            }
         }

         fn main() {
            write_ready();
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .expect("u32 MMIO address local compiles to Rux16");
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
fn rux16_artifact_lowers_u32_loop_and_address_arithmetic_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "fn sum_table() {
            unsafe {
                let mut base: u32 = 0x1000u32;
                let mut count: u32 = 3u32;
                let mut i: u32 = 0u32;
                let mut sum: i32 = 0;
                while i < count {
                    let mut entry: u32 = base + i * 4u32;
                    sum = sum + ptr<i32>(entry).load();
                    i += 1u32;
                }
                let mut control_base: u32 = control::STATUS;
                mmio<i32>(control_base + 4u32).store(sum);
            }
         }

         fn main() {
            sum_table();
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .expect("u32 loop and address arithmetic compiles to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 128)
            .expect("machine boots Rux16 BIOS flash");
    machine
        .write_guest_ram_bytes(0x1000, &10i32.to_le_bytes())
        .unwrap();
    machine
        .write_guest_ram_bytes(0x1004, &20i32.to_le_bytes())
        .unwrap();
    machine
        .write_guest_ram_bytes(0x1008, &30i32.to_le_bytes())
        .unwrap();

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.panic_code(), 60);
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_lowers_if_eq_condition_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "fn main() {
            unsafe {
                mmio<i32>(control::STATUS).store(status::READY);
                if mmio<i32>(control::STATUS).load() == status::READY {
                    mmio<u8>(debug::WRITE).store(89u8);
                }
            }
         }",
        ),
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
        &with_computer_abi(
            "fn main() {
            unsafe {
                if mmio<i32>(control::STATUS).load() == status::READY {
                    mmio<u8>(debug::WRITE).store(84u8);
                } else {
                    mmio<u8>(debug::WRITE).store(70u8);
                }
            }
         }",
        ),
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
        &with_computer_abi(
            "fn main() {
            unsafe {
                while mmio<i32>(control::STATUS).load() == status::RESET {
                    mmio<i32>(control::STATUS).store(status::READY);
                    mmio<u8>(debug::WRITE).store(76u8);
                }
                mmio<u8>(debug::WRITE).store(68u8);
            }
         }",
        ),
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
fn rux16_artifact_lowers_logical_conditions_with_short_circuit_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "fn mark_x() -> i32 {
            unsafe {
                mmio<u8>(debug::WRITE).store(88u8);
            }
            return 1;
         }

         fn mark_y() -> i32 {
            unsafe {
                mmio<u8>(debug::WRITE).store(89u8);
            }
            return 1;
         }

         fn main() {
            unsafe {
                if status::RESET == status::READY && mark_x() == 1 {
                    mmio<u8>(debug::WRITE).store(70u8);
                } else {
                    mmio<u8>(debug::WRITE).store(65u8);
                }

                if status::READY == status::READY || mark_y() == 1 {
                    mmio<u8>(debug::WRITE).store(66u8);
                }

                if !(status::RESET == status::READY) {
                    mmio<u8>(debug::WRITE).store(67u8);
                }
            }
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .expect("logical conditions compile to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 256)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"ABC");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_rejects_numeric_logical_operands_without_truthiness() {
    let error = compile_rux16_artifact(
        "fn main() {
            if 1 && true {
            }
         }",
        Rux16ArtifactTarget::Boot,
    )
    .unwrap_err();

    assert!(
        error
            .message
            .contains("logical operator operands must be boolean conditions"),
        "{}",
        error.message
    );
}

#[test]
fn rux16_artifact_const_mmio_sequence_runs_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "const O: i32 = 79;
         const K: i32 = 75;

         fn main() {
            unsafe {
                mmio<i32>(control::STATUS).store(status::BOOTING);
                mmio<i32>(debug::WRITE).store(O);
                mmio<i32>(debug::WRITE).store(K);
                mmio<i32>(control::PANIC_CODE).store(status::READY);
                mmio<i32>(control::STATUS).store(status::READY);
            }
         }",
        ),
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
fn rux16_artifact_calls_unit_helper_function_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "const O: i32 = 79;
         const K: i32 = 75;

         fn write_ok() {
            unsafe {
                mmio<i32>(debug::WRITE).store(O);
                mmio<i32>(debug::WRITE).store(K);
            }
         }

         fn main() {
            write_ok();
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .expect("unit helper call compiles to Rux16 call/ret");
    let disassembly =
        rux16_disasm::disassemble_artifact(&artifact.bytes, Rux16ArtifactTarget::Bios)
            .expect("BIOS artifact disassembles");
    assert!(
        disassembly.contains("call r"),
        "unit helper call must lower to a real Rux16 call:\n{disassembly}"
    );
    assert!(
        disassembly.contains("ret"),
        "unit helper body must return with Rux16 ret:\n{disassembly}"
    );
    assert!(
        disassembly.contains("r12"),
        "real helper bodies must reserve r12 as the helper frame pointer:\n{disassembly}"
    );
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 1024)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"OK");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_preserves_live_locals_around_unit_helper_call_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "fn write_bang() {
            unsafe {
                mmio<u8>(debug::WRITE).store(33u8);
            }
         }

         fn main() {
            let mut byte: u8 = 65u8;
            write_bang();
            unsafe {
                mmio<u8>(debug::WRITE).store(byte);
            }
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .expect("live locals survive Rux16 call/ret helper lowering");
    let disassembly =
        rux16_disasm::disassemble_artifact(&artifact.bytes, Rux16ArtifactTarget::Bios)
            .expect("BIOS artifact disassembles");
    assert!(
        disassembly.contains("call r"),
        "unit helper with live caller locals must lower to a real Rux16 call:\n{disassembly}"
    );
    assert!(
        disassembly.contains("ret"),
        "unit helper body must return with Rux16 ret:\n{disassembly}"
    );
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 1024)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"!A");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_preserves_live_locals_around_argument_helper_call_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "fn write_three(first: u8, second: u8, third: u8) {
            unsafe {
                mmio<u8>(debug::WRITE).store(first);
                mmio<u8>(debug::WRITE).store(second);
                mmio<u8>(debug::WRITE).store(third);
            }
         }

         fn main() {
            let mut after: u8 = 33u8;
            write_three(after, 66u8, 67u8);
            unsafe {
                mmio<u8>(debug::WRITE).store(after);
            }
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .expect("live locals survive Rux16 call ABI argument lowering");
    let disassembly =
        rux16_disasm::disassemble_artifact(&artifact.bytes, Rux16ArtifactTarget::Bios)
            .expect("BIOS artifact disassembles");
    assert!(
        disassembly.contains("call r"),
        "argument helper with live caller locals must lower to a real Rux16 call:\n{disassembly}"
    );
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 1024)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"!BC!", "{disassembly}");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_preserves_helper_parameters_from_scratch_clobbering() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "fn write_after_scratch(first: u8, second: u8, third: u8) {
            unsafe {
                mmio<i32>(control::STATUS).store(0);
                mmio<u8>(debug::WRITE).store(first);
                mmio<u8>(debug::WRITE).store(second);
                mmio<i32>(control::PANIC_CODE).store(0);
                mmio<u8>(debug::WRITE).store(third);
            }
         }

         fn main() {
            write_after_scratch(65u8, 66u8, 67u8);
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .expect("helper parameters survive scratch register use in helper bodies");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 1024)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"ABC");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_lowers_early_returning_helper_control_flow_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "fn choose_byte(flag: i32) -> u8 {
            if flag == 1 {
                return 79u8;
            }
            return 33u8;
         }

         fn main() {
            unsafe {
                mmio<u8>(debug::WRITE).store(choose_byte(1));
                mmio<u8>(debug::WRITE).store(choose_byte(0));
            }
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .expect("early returning helper control flow compiles to Rux16");
    let disassembly =
        rux16_disasm::disassemble_artifact(&artifact.bytes, Rux16ArtifactTarget::Bios)
            .expect("BIOS artifact disassembles");
    assert!(
        disassembly.contains("ret"),
        "early returning helper must lower returns to Rux16 ret:\n{disassembly}"
    );
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 1024)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"O!", "{disassembly}");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_lowers_u8_equality_helper_condition_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "fn choose_byte(flag: u8) -> u8 {
            if flag == 1u8 {
                return 79u8;
            }
            if flag != 0u8 {
                return 63u8;
            }
            return 33u8;
         }

         fn main() {
            unsafe {
                mmio<u8>(debug::WRITE).store(choose_byte(1u8));
                mmio<u8>(debug::WRITE).store(choose_byte(2u8));
                mmio<u8>(debug::WRITE).store(choose_byte(0u8));
            }
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .expect("u8 equality helper condition compiles to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 1024)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"O?!");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_lowers_all_returning_if_else_helper_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "fn choose_byte(flag: u8) -> u8 {
            if flag == 1u8 {
                return 79u8;
            } else {
                return 33u8;
            }
         }

         fn main() {
            unsafe {
                mmio<u8>(debug::WRITE).store(choose_byte(1u8));
                mmio<u8>(debug::WRITE).store(choose_byte(0u8));
            }
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .expect("all-returning if/else helper compiles to Rux16");
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 1024)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"O!");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_rejects_returning_helper_with_missing_return_path() {
    let error = compile_rux16_artifact(
        &with_computer_abi(
            "fn choose_byte(flag: u8) -> u8 {
            if flag == 1u8 {
                return 79u8;
            }
         }

         fn main() {
            unsafe {
                mmio<u8>(debug::WRITE).store(choose_byte(0u8));
            }
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .unwrap_err();

    assert!(
        error
            .message
            .contains("returning helper function `choose_byte` does not return on all paths"),
        "{}",
        error.message
    );
}

#[test]
fn rux16_artifact_calls_helper_parameters_and_returns_from_bios_flash() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "fn panic_addr() -> u32 {
            return control::PANIC_CODE;
         }

         fn ready_code() -> i32 {
            return status::READY;
         }

         fn byte_o() -> u8 {
            return 79u8;
         }

         fn write_panic(addr: u32, code: i32) {
            unsafe {
                mmio<i32>(addr).store(code);
            }
         }

         fn write_byte(byte: u8) {
            unsafe {
                mmio<u8>(debug::WRITE).store(byte);
            }
         }

         fn main() {
            write_panic(panic_addr(), ready_code());
            write_byte(byte_o());
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .expect("helper parameters and returns compile to call/ret Rux16");
    let disassembly =
        rux16_disasm::disassemble_artifact(&artifact.bytes, Rux16ArtifactTarget::Bios)
            .expect("BIOS artifact disassembles");
    assert!(
        disassembly.contains("call r"),
        "helper parameters and returns must lower through the Rux16 call ABI:\n{disassembly}"
    );
    assert!(
        disassembly.contains("ret"),
        "called helper bodies must return through Rux16 ret:\n{disassembly}"
    );
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 128)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.panic_code(), ComputerMachine::STATUS_READY);
    assert_eq!(machine.debug_output_bytes(), b"O", "{disassembly}");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_backend_reserves_r15_for_stack_pointer() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "fn main() {
            unsafe {
                if mmio<i32>(control::STATUS).load() == status::RESET {
                    mmio<i32>(control::STATUS).store(status::READY);
                } else {
                    mmio<i32>(control::PANIC_CODE).store(status::BOOTING);
                }
            }
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .expect("conditional firmware compiles to Rux16");
    let disassembly =
        rux16_disasm::disassemble_artifact(&artifact.bytes, Rux16ArtifactTarget::Bios)
            .expect("BIOS artifact disassembles");

    assert!(
        !disassembly.contains("r15"),
        "r15 is the stack pointer and must not be used as a compiler scratch register:\n{disassembly}"
    );
}

#[test]
fn rux16_artifact_backend_reserves_r12_for_frame_pointer() {
    let error = compile_rux16_artifact(
        &with_computer_abi(
            "fn main() {
            let mut a: u8 = 1u8;
            let mut b: u8 = 2u8;
            let mut c: u8 = 3u8;
            let mut d: u8 = 4u8;
            let mut e: u8 = 5u8;
            let mut f: u8 = 6u8;
            let mut g: u8 = 7u8;
            let mut h: u8 = 8u8;
            let mut i: u8 = 9u8;
            let mut j: u8 = 10u8;
            unsafe {
                mmio<u8>(debug::WRITE).store(a);
                mmio<u8>(debug::WRITE).store(j);
            }
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .unwrap_err();

    assert!(
        error.message.contains("Rux16 backend ran out of registers"),
        "{}",
        error.message
    );
}

#[test]
fn rux16_artifact_uses_stack_backed_helper_local_from_frame_pointer() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "fn write_stacked() {
            let mut a: u8 = 1u8;
            let mut b: u8 = 2u8;
            let mut c: u8 = 3u8;
            let mut d: u8 = 4u8;
            let mut e: u8 = 5u8;
            let mut f: u8 = 6u8;
            let mut g: u8 = 7u8;
            let mut h: u8 = 8u8;
            let mut i: u8 = 9u8;
            let mut j: u8 = 74u8;
            unsafe {
                mmio<u8>(debug::WRITE).store(j);
            }
         }

         fn main() {
            write_stacked();
            write_stacked();
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .expect("helper locals can spill to a frame-pointer stack slot");
    let disassembly =
        rux16_disasm::disassemble_artifact(&artifact.bytes, Rux16ArtifactTarget::Bios)
            .expect("BIOS artifact disassembles");
    assert!(
        disassembly.contains(", r12,"),
        "stack-backed helper local addresses must be derived from r12 frame pointer:\n{disassembly}"
    );
    assert!(
        disassembly.contains("store32 [r"),
        "stack-backed helper local initializer must be stored to its frame slot:\n{disassembly}"
    );
    assert!(
        disassembly.contains("load32 r"),
        "stack-backed helper local use must load from its frame slot:\n{disassembly}"
    );
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 1024)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"JJ", "{disassembly}");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_uses_stack_array_helper_byte_buffer_from_frame_pointer() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "fn write_buffered() {
            let mut buffer: [u8; 4] = b\"\\0\\0\\0\\0\";
            let mut index: u32 = 2u32;
            buffer[0u32] = 79u8;
            buffer[index] = 75u8;
            unsafe {
                mmio<u8>(debug::WRITE).store(buffer[0u32]);
                mmio<u8>(debug::WRITE).store(buffer[index]);
            }
         }

         fn main() {
            write_buffered();
            write_buffered();
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .expect("helper byte arrays can live in frame-pointer stack storage");
    let disassembly =
        rux16_disasm::disassemble_artifact(&artifact.bytes, Rux16ArtifactTarget::Bios)
            .expect("BIOS artifact disassembles");
    assert!(
        disassembly.contains(", r12,"),
        "stack-backed helper array addresses must be derived from r12 frame pointer:\n{disassembly}"
    );
    assert!(
        disassembly.contains("store8 [r"),
        "stack-backed helper array writes must use byte stores:\n{disassembly}"
    );
    assert!(
        disassembly.contains("load8 r"),
        "stack-backed helper array reads must use byte loads:\n{disassembly}"
    );
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 1024)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"OKOK", "{disassembly}");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_lowers_address_of_stack_array_element_to_guest_address() {
    let artifact = compile_rux16_artifact(
        &with_computer_abi(
            "fn write_through_address() {
            let mut buffer: [u8; 4] = b\"\\0\\0\\0\\0\";
            let mut addr: u32 = &mut buffer[0u32];
            unsafe {
                ptr<u8>(addr).store(65u8);
                mmio<u8>(debug::WRITE).store(buffer[0u32]);
            }
         }

         fn main() {
            write_through_address();
            write_through_address();
         }",
        ),
        Rux16ArtifactTarget::Bios,
    )
    .expect("helper can take a guest address of a stack-backed byte array element");
    let disassembly =
        rux16_disasm::disassemble_artifact(&artifact.bytes, Rux16ArtifactTarget::Bios)
            .expect("BIOS artifact disassembles");
    assert!(
        disassembly.contains(", r12,"),
        "address-of stack array element must derive from r12 frame pointer:\n{disassembly}"
    );
    assert!(
        disassembly.contains("store8 [r"),
        "pointer store must write through the computed guest address:\n{disassembly}"
    );
    let (mut machine, cpu_id) =
        ComputerMachine::from_rux16_bios_flash(&artifact.bytes, 64 * 1024, 1024)
            .expect("machine boots Rux16 BIOS flash");

    assert_eq!(
        machine.run_boot_rux16_until_signal(cpu_id).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(machine.debug_output_bytes(), b"AA", "{disassembly}");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
}

#[test]
fn rux16_artifact_reads_storage0_block_into_stack_buffer() {
    let zero_initializer = "\\0".repeat(512);
    let source = format!(
        "use rux::abi::computer::storage0;

         fn read_storage0_block() {{
            let mut block: [u8; 512] = b\"{zero_initializer}\";
            let mut addr: u32 = &mut block[0u32];
            unsafe {{
                mmio<i32>(storage0::LBA_LOW).store(0);
                mmio<i32>(storage0::LBA_HIGH).store(0);
                mmio<i32>(storage0::BLOCK_COUNT).store(1);
                mmio<u32>(storage0::BUFFER_ADDR).store(addr);
                mmio<i32>(storage0::COMMAND).store(storage0::COMMAND_READ_BLOCKS);
                if mmio<i32>(storage0::STATUS).load() == storage0::STATUS_DONE {{
                    mmio<u8>(debug::WRITE).store(block[0u32]);
                    mmio<u8>(debug::WRITE).store(block[1u32]);
                }}
            }}
         }}

         fn main() {{
            read_storage0_block();
         }}"
    );
    let artifact = compile_rux16_artifact(&with_computer_abi(&source), Rux16ArtifactTarget::Bios)
        .expect("helper can read one storage0 block into a stack-backed byte buffer");
    let mut media = vec![0; 512];
    media[0] = b'O';
    media[1] = b'K';
    let mut handle = RuxComputerHandle::create_rux16_bios_flash_with_storage0_media(
        &artifact.bytes,
        64 * 1024,
        100_000,
        media,
    )
    .expect("machine boots Rux16 BIOS flash with storage0 media");

    let signal = handle.run_rux16_until_signal().unwrap();
    assert_eq!(
        signal,
        Rux16Signal::Halt,
        "debug output before signal: {:?}",
        String::from_utf8_lossy(handle.debug_output_bytes())
    );
    assert_eq!(handle.debug_output_bytes(), b"OK");
    assert_eq!(handle.control().status, ComputerMachine::STATUS_HALTED);
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
        Rux16ArtifactTarget::Boot,
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
