use k16_vm::computer_machine::{
    BootHandoffError, ComputerMachine, COMPUTER_SNAPSHOT_V1_HEADER_SIZE,
    COMPUTER_SNAPSHOT_V1_K16_CPU_RECORD_SIZE, COMPUTER_SNAPSHOT_V1_MAGIC,
};
use k16_vm::k16::{
    K16Signal, K16_CSR_INTERRUPT_ENABLE, K16_CSR_INTERRUPT_MASK, K16_CSR_TRAP_CAUSE,
    K16_CSR_TRAP_VALUE, K16_CSR_TRAP_VECTOR, K16_INTERRUPT_SOURCE_KEYBOARD0,
    K16_INTERRUPT_SOURCE_TIMER0, K16_TRAP_CAUSE_KEYBOARD0_INTERRUPT,
};
use k16_vm::k16_computer::{K16ComputerControl, K16ComputerHandle};
use std::fs;
use std::time::{SystemTime, UNIX_EPOCH};

#[test]
fn k16_computer_handle_fails_when_memory_is_too_small() {
    let bios = k16_words(&[k16_halt()]);
    let error: String = match K16ComputerHandle::create_k16_bios_flash(&bios, 128, 128) {
        Ok(_) => panic!("computer handle should reject undersized memory"),
        Err(error) => error,
    };

    assert!(
        error.contains("smaller than profile page size"),
        "unexpected error: {error}",
    );
}

#[test]
fn k16_computer_handle_accepts_storage0_media_and_exposes_snapshot() {
    let bios = k16_words(&[k16_halt()]);
    let media = vec![7; 1024];
    let handle = K16ComputerHandle::create_k16_bios_flash_with_storage0_media(
        &bios,
        64 * 1024,
        128,
        media.clone(),
    )
    .expect("K16 BIOS flash computer creates with storage0 media");

    assert_eq!(
        handle
            .storage0_media_snapshot()
            .expect("storage0 media exists"),
        media,
    );
}

#[test]
fn k16_computer_handle_accepts_storage0_volume_path() {
    let bios = k16_words(&[k16_halt()]);
    let path = temp_volume_path("handle-storage0-path");
    write_k16_volume(&path, &[0; 1024]);

    let handle =
        K16ComputerHandle::create_k16_bios_flash_with_storage0_path(&bios, 64 * 1024, 128, &path)
            .expect("K16 BIOS flash computer creates with storage0 volume path");

    assert!(handle.storage0_media_snapshot().is_none());
    fs::remove_file(path).unwrap();
}

#[test]
fn k16_computer_profile_exposes_timer0_hardware_entry_and_mmio() {
    let mut machine = ComputerMachine::new(1024).expect("machine creates");

    assert_eq!(read_u32(machine.memory(), 0x18), 8);
    assert_hardware_entry_with_irq(
        machine.memory(),
        108,
        ComputerMachine::HARDWARE_ID_TIMER0,
        ComputerMachine::TIMER0_BASE,
        ComputerMachine::TIMER0_SIZE,
        k16_vm::k16::K16_INTERRUPT_SOURCE_TIMER0,
    );
    assert_hardware_entry_with_irq(
        machine.memory(),
        124,
        ComputerMachine::HARDWARE_ID_KEYBOARD0,
        ComputerMachine::KEYBOARD0_BASE,
        ComputerMachine::KEYBOARD0_SIZE,
        k16_vm::k16::K16_INTERRUPT_SOURCE_KEYBOARD0,
    );
    assert_hardware_entry_with_irq(
        machine.memory(),
        140,
        ComputerMachine::HARDWARE_ID_MMU0,
        ComputerMachine::MMU0_BASE,
        ComputerMachine::MMU0_SIZE,
        0,
    );
    assert_eq!(
        machine
            .memory_map()
            .region("timer0")
            .expect("timer0 is mapped")
            .base,
        ComputerMachine::TIMER0_BASE,
    );
    assert_eq!(
        machine
            .memory_map()
            .region("keyboard0")
            .expect("keyboard0 is mapped")
            .base,
        ComputerMachine::KEYBOARD0_BASE,
    );
    assert_eq!(
        machine
            .memory_map()
            .region("mmu0")
            .expect("mmu0 is mapped")
            .base,
        ComputerMachine::MMU0_BASE,
    );
    assert_eq!(
        machine
            .bus_load_i32(ComputerMachine::TIMER0_VERSION)
            .unwrap(),
        ComputerMachine::TIMER0_VERSION_VALUE,
    );
    assert_eq!(
        machine
            .bus_load_i32(ComputerMachine::TIMER0_GAME_TICKS_LOW)
            .unwrap(),
        0,
    );
    assert_eq!(
        machine
            .bus_load_i32(ComputerMachine::TIMER0_GAME_TICKS_HIGH)
            .unwrap(),
        0,
    );

    machine.advance_game_tick();

    assert_eq!(
        machine
            .bus_load_i32(ComputerMachine::TIMER0_GAME_TICKS_LOW)
            .unwrap(),
        1,
    );
}

#[test]
fn k16_computer_handle_guest_reads_timer0_game_ticks_after_host_advance() {
    let mut words = Vec::new();
    words.extend(k16_const32(0, ComputerMachine::TIMER0_GAME_TICKS_LOW));
    words.push(k16_load32(1, 0));
    words.extend(k16_const32(0, ComputerMachine::CONTROL_PANIC_CODE));
    words.push(k16_store32(0, 1));
    words.push(k16_halt());
    let bios = k16_words(&words);
    let mut handle = K16ComputerHandle::create_k16_bios_flash(&bios, 64 * 1024, 128)
        .expect("K16 BIOS flash computer creates");

    handle.advance_game_tick();
    handle.advance_game_tick();

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert_eq!(handle.control().panic_code, 2);
}

#[test]
fn k16_computer_handle_guest_reads_timer0_game_ticks_high_word_after_restore() {
    let mut words = Vec::new();
    words.extend(k16_const32(0, ComputerMachine::TIMER0_GAME_TICKS_LOW));
    words.push(k16_load32(1, 0));
    words.extend(k16_const32(0, ComputerMachine::CONTROL_PANIC_CODE));
    words.push(k16_store32(0, 1));
    words.extend(k16_const32(0, ComputerMachine::TIMER0_GAME_TICKS_HIGH));
    words.push(k16_load32(1, 0));
    words.extend(k16_const32(0, 0x100));
    words.push(k16_store32(0, 1));
    words.push(k16_halt());
    let bios = k16_words(&words);
    let storage_path = temp_volume_path("timer0-high-word-restore");
    write_k16_volume(&storage_path, &[0; 1024]);
    let handle = K16ComputerHandle::create_k16_bios_flash_with_storage0_path(
        &bios,
        64 * 1024,
        128,
        &storage_path,
    )
    .expect("K16 BIOS flash computer creates");
    let snapshot = snapshot_with_timer0_game_ticks(
        &handle.snapshot_v1().expect("snapshot encodes"),
        0x0000_0001_0000_002a,
    );
    let mut restored = K16ComputerHandle::restore_k16_bios_flash_snapshot_with_storage0_path(
        &bios,
        64 * 1024,
        &storage_path,
        &snapshot,
    )
    .expect("snapshot restores");

    assert_eq!(
        snapshot_timer0_game_ticks(&restored.snapshot_v1().expect("snapshot encodes")),
        0x0000_0001_0000_002a,
    );

    assert_eq!(restored.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert_eq!(
        restored.control(),
        K16ComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 42,
        },
    );
    assert_eq!(
        i32::from_le_bytes(
            restored
                .read_guest_ram_bytes(0x100, 4)
                .expect("reads RAM proof")[..]
                .try_into()
                .unwrap(),
        ),
        1,
    );
    fs::remove_file(storage_path).unwrap();
}

#[test]
fn k16_computer_handle_advance_game_tick_requests_timer0_interrupt() {
    let mut words = Vec::new();
    words.extend(k16_const32(1, ComputerMachine::K16_BIOS_FLASH_BASE + 30));
    words.push(k16_write_csr(K16_CSR_TRAP_VECTOR, 1));
    words.push(k16_const4(1, K16_INTERRUPT_SOURCE_TIMER0 as u8));
    words.push(k16_write_csr(K16_CSR_INTERRUPT_MASK, 1));
    words.push(k16_const4(1, 1));
    words.push(k16_write_csr(K16_CSR_INTERRUPT_ENABLE, 1));
    words.push(k16_halt());
    words.extend([0; 6]);
    words.push(k16_read_csr(3, K16_CSR_TRAP_VALUE));
    words.extend(k16_const32(0, ComputerMachine::CONTROL_PANIC_CODE));
    words.push(k16_store32(0, 3));
    words.push(k16_iret());
    let bios = k16_words(&words);
    let mut handle = K16ComputerHandle::create_k16_bios_flash(&bios, 64 * 1024, 6)
        .expect("K16 BIOS flash computer creates");

    assert_eq!(
        handle.run_k16_until_signal().unwrap(),
        K16Signal::StepLimitExceeded,
    );

    handle.advance_game_tick();

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert_eq!(handle.control().panic_code, 1);
}

#[test]
fn k16_computer_handle_keyboard0_input_requests_keyboard0_interrupt() {
    let mut words = Vec::new();
    words.extend(k16_const32(1, ComputerMachine::K16_BIOS_FLASH_BASE + 30));
    words.push(k16_write_csr(K16_CSR_TRAP_VECTOR, 1));
    words.push(k16_const4(1, K16_INTERRUPT_SOURCE_KEYBOARD0 as u8));
    words.push(k16_write_csr(K16_CSR_INTERRUPT_MASK, 1));
    words.push(k16_const4(1, 1));
    words.push(k16_write_csr(K16_CSR_INTERRUPT_ENABLE, 1));
    words.push(k16_halt());
    words.extend([0; 6]);
    words.push(k16_read_csr(3, K16_CSR_TRAP_CAUSE));
    words.extend(k16_const32(0, ComputerMachine::CONTROL_PANIC_CODE));
    words.push(k16_store32(0, 3));
    words.push(k16_iret());
    let bios = k16_words(&words);
    let mut handle = K16ComputerHandle::create_k16_bios_flash(&bios, 64 * 1024, 64)
        .expect("K16 BIOS flash computer creates");

    handle.push_keyboard_char(b'K');

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert_eq!(
        u32::from_le_bytes(handle.control().panic_code.to_le_bytes()),
        K16_TRAP_CAUSE_KEYBOARD0_INTERRUPT,
    );
}

#[test]
fn k16_computer_handle_bios_flash_reads_storage0_version_from_path() {
    let mut words = Vec::new();
    words.extend(k16_const32(0, ComputerMachine::STORAGE0_VERSION));
    words.push(k16_load32(1, 0));
    words.extend(k16_const32(0, ComputerMachine::CONTROL_PANIC_CODE));
    words.push(k16_store32(0, 1));
    words.push(k16_halt());
    let bios = k16_words(&words);
    let path = temp_volume_path("handle-storage0-version-path");
    write_k16_volume(&path, &[0; 1024]);

    let mut handle =
        K16ComputerHandle::create_k16_bios_flash_with_storage0_path(&bios, 64 * 1024, 128, &path)
            .expect("K16 BIOS flash computer creates with storage0 volume path");

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert_eq!(handle.control().panic_code, 1);
    fs::remove_file(path).unwrap();
}

#[test]
fn k16_computer_handle_bios_flash_reads_storage0_version_through_call_helper() {
    let mut words = Vec::new();
    words.extend(k16_const32(15, 0x0001_0000));
    words.extend(k16_const32(1, ComputerMachine::STORAGE0_VERSION));
    let helper_pc = ComputerMachine::K16_BIOS_FLASH_BASE + 30;
    words.extend(k16_const32(14, helper_pc));
    words.push(k16_call(14));
    words.extend(k16_const32(1, ComputerMachine::CONTROL_PANIC_CODE));
    words.push(k16_store32(1, 0));
    words.push(k16_halt());
    words.push(k16_load32(0, 1));
    words.push(k16_ret());
    let bios = k16_words(&words);
    let path = temp_volume_path("handle-storage0-helper-path");
    write_k16_volume(&path, &[0; 1024]);

    let mut handle =
        K16ComputerHandle::create_k16_bios_flash_with_storage0_path(&bios, 64 * 1024, 128, &path)
            .expect("K16 BIOS flash computer creates with storage0 volume path");

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert_eq!(handle.control().panic_code, 1);
    fs::remove_file(path).unwrap();
}

#[test]
fn k16_computer_handle_boot_handoff_starts_k16_from_guest_ram_without_host_decode() {
    let bios = k16_words(&[k16_halt()]);
    let entry_pc = 4096;
    let program = k16_words(&[k16_const4(1, 7), k16_halt()]);
    let mut handle = K16ComputerHandle::create_k16_bios_flash(&bios, 64 * 1024, 128)
        .expect("K16 BIOS flash computer creates");
    handle.write_guest_ram_bytes(entry_pc, &program).unwrap();

    let cpu_id = handle
        .boot_handoff_k16_from_guest_ram(entry_pc, program.len() as u32, 128)
        .expect("boot handoff accepts in-RAM K16 program");

    assert_eq!(cpu_id, 0);
    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
}

#[test]
fn k16_computer_handle_boot_handoff_can_install_kernel_stack_top() {
    let bios = k16_words(&[k16_halt()]);
    let entry_pc = 4096;
    let helper_pc = entry_pc + 10;
    let mut words = Vec::new();
    words.extend(k16_const32(14, helper_pc));
    words.push(k16_call(14));
    words.push(k16_halt());
    words.extend(k16_const32(0, ComputerMachine::CONTROL_PANIC_CODE));
    words.extend(k16_const32(1, 42));
    words.push(k16_store32(0, 1));
    words.push(k16_ret());
    let program = k16_words(&words);
    let mut handle = K16ComputerHandle::create_k16_bios_flash(&bios, 64 * 1024, 128)
        .expect("K16 BIOS flash computer creates");
    handle.write_guest_ram_bytes(entry_pc, &program).unwrap();

    let cpu_id = handle
        .boot_handoff_k16_from_guest_ram_with_stack(
            entry_pc,
            program.len() as u32,
            128,
            0x0001_0000,
        )
        .expect("boot handoff accepts in-RAM K16 program and stack top");

    assert_eq!(cpu_id, 0);
    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert_eq!(handle.control().panic_code, 42);
}

#[test]
fn k16_computer_handle_boot_handoff_rejects_four_byte_only_stack_top() {
    let bios = k16_words(&[k16_halt()]);
    let entry_pc = 4096;
    let program = k16_words(&[k16_halt()]);
    let mut handle = K16ComputerHandle::create_k16_bios_flash(&bios, 64 * 1024, 128)
        .expect("K16 BIOS flash computer creates");
    handle.write_guest_ram_bytes(entry_pc, &program).unwrap();

    let error = handle
        .boot_handoff_k16_from_guest_ram_with_stack(
            entry_pc,
            program.len() as u32,
            128,
            0x0001_0004,
        )
        .expect_err("boot handoff rejects stack top without 8-byte alignment");

    assert_eq!(
        error,
        BootHandoffError::StackTopMisaligned {
            stack_top: 0x0001_0004,
        }
    );
}

#[test]
fn k16_computer_handle_k16_firmware_writes_debug_and_control_mmio() {
    let bios = k16_words(&[k16_halt()]);
    let entry_pc = 4096;
    let program = k16_words(&k16_mmio_firmware_words());
    let mut handle = K16ComputerHandle::create_k16_bios_flash(&bios, 64 * 1024, 128)
        .expect("K16 BIOS flash computer creates");
    handle.write_guest_ram_bytes(entry_pc, &program).unwrap();

    handle
        .boot_handoff_k16_from_guest_ram(entry_pc, program.len() as u32, 128)
        .expect("boot handoff accepts in-RAM K16 firmware");

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert_eq!(handle.debug_output_bytes(), b"RUX");
    assert_eq!(
        handle.control(),
        K16ComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 0x16,
        },
    );
}

#[test]
fn k16_computer_handle_boots_k16_directly_from_bios_flash() {
    let bios = k16_words(&k16_mmio_firmware_words());
    let mut handle = K16ComputerHandle::create_k16_bios_flash(&bios, 64 * 1024, 128)
        .expect("K16 BIOS flash computer creates");

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert_eq!(handle.debug_output_bytes(), b"RUX");
    assert_eq!(
        handle.control(),
        K16ComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 0x16,
        },
    );
}

#[test]
fn k16_computer_handle_rejects_empty_k16_bios_flash() {
    let error = match K16ComputerHandle::create_k16_bios_flash(&[], 64 * 1024, 128) {
        Ok(_) => panic!("empty K16 BIOS flash unexpectedly created a computer"),
        Err(error) => error,
    };

    assert!(
        error.contains("K16 BIOS flash is empty"),
        "unexpected error: {error}",
    );
}

#[test]
fn k16_computer_handle_k16_bios_flash_is_read_only() {
    let mut words = Vec::new();
    words.extend(k16_const32(0, ComputerMachine::K16_BIOS_FLASH_BASE));
    words.extend(k16_const32(1, 0x1234));
    words.push(k16_store32(0, 1));
    words.push(k16_halt());
    let bios = k16_words(&words);
    let mut handle = K16ComputerHandle::create_k16_bios_flash(&bios, 64 * 1024, 128)
        .expect("K16 BIOS flash computer creates");

    let error = handle
        .run_k16_until_signal()
        .expect_err("flash write traps");

    assert!(
        error.contains("BIOS flash is read-only"),
        "unexpected error: {error}",
    );
}

#[test]
fn k16_computer_handle_k16_bios_flash_reads_storage0_block_into_ram() {
    let bios = k16_words(&k16_storage_read_bios_words());
    let mut media = vec![0; 512];
    media[0..3].copy_from_slice(b"RUX");
    let mut handle =
        K16ComputerHandle::create_k16_bios_flash_with_storage0_media(&bios, 64 * 1024, 256, media)
            .expect("K16 BIOS flash computer creates with storage0 media");

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert_eq!(handle.debug_output_bytes(), b"RUX");
    assert_eq!(
        handle.control(),
        K16ComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 2,
        },
    );
}

#[test]
fn k16_computer_handle_k16_bios_loads_stage2_from_storage_and_jumps_to_ram() {
    let entry_pc = 2048;
    let bios = k16_words(&k16_stage2_boot_bios_words());
    let stage2 = k16_words(&k16_stage2_program_words());
    let media = k16_boot_media(entry_pc, entry_pc, 1, 1, &stage2);
    let mut handle =
        K16ComputerHandle::create_k16_bios_flash_with_storage0_media(&bios, 64 * 1024, 512, media)
            .expect("K16 BIOS flash computer creates with boot media");

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert_eq!(handle.debug_output_bytes(), b"S2");
    assert_eq!(
        handle.control(),
        K16ComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 0x52,
        },
    );
}

#[test]
fn k16_computer_handle_k16_bios_loads_stage2_from_storage_volume_path() {
    let entry_pc = 2048;
    let bios = k16_words(&k16_stage2_boot_bios_words());
    let stage2 = k16_words(&k16_stage2_program_words());
    let media = k16_boot_media(entry_pc, entry_pc, 1, 1, &stage2);
    let path = temp_volume_path("k16-stage2-volume-path");
    write_k16_volume(&path, &media);
    let mut handle =
        K16ComputerHandle::create_k16_bios_flash_with_storage0_path(&bios, 64 * 1024, 512, &path)
            .expect("K16 BIOS flash computer creates with boot volume path");

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert_eq!(handle.debug_output_bytes(), b"S2");
    assert_eq!(
        handle.control(),
        K16ComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 0x52,
        },
    );
    fs::remove_file(path).unwrap();
}

#[test]
fn k16_computer_handle_loads_k16_bios_flash_from_path_with_storage0_path() {
    let entry_pc = 2048;
    let bios = k16_words(&k16_stage2_boot_bios_words());
    let stage2 = k16_words(&k16_stage2_program_words());
    let media = k16_boot_media(entry_pc, entry_pc, 1, 1, &stage2);
    let bios_path = temp_volume_path("k16-bios-flash-path");
    let storage_path = temp_volume_path("k16-stage2-storage-path");
    fs::write(&bios_path, &bios).unwrap();
    write_k16_volume(&storage_path, &media);
    let mut handle = K16ComputerHandle::create_k16_bios_flash_path_with_storage0_path(
        &bios_path,
        64 * 1024,
        512,
        &storage_path,
    )
    .expect("K16 BIOS flash computer creates from BIOS and storage paths");

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert_eq!(handle.debug_output_bytes(), b"S2");
    assert_eq!(
        handle.control(),
        K16ComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 0x52,
        },
    );
    fs::remove_file(bios_path).unwrap();
    fs::remove_file(storage_path).unwrap();
}

#[test]
fn k16_computer_handle_restores_snapshot_with_bios_flash_and_storage0_path() {
    let bios = k16_words(&k16_mmio_firmware_words());
    let storage_path = temp_volume_path("k16-restore-storage-path");
    write_k16_volume(&storage_path, &[0; 1024]);
    let mut handle = K16ComputerHandle::create_k16_bios_flash_with_storage0_path(
        &bios,
        64 * 1024,
        128,
        &storage_path,
    )
    .expect("K16 BIOS flash computer creates with storage0 path");

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);

    let snapshot = handle.snapshot_v1().expect("snapshot encodes");
    let restored = K16ComputerHandle::restore_k16_bios_flash_snapshot_with_storage0_path(
        &bios,
        64 * 1024,
        &storage_path,
        &snapshot,
    )
    .expect("snapshot restores");

    assert_eq!(restored.debug_output_bytes(), b"RUX");
    assert_eq!(
        restored.control(),
        K16ComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 0x16,
        },
    );
    fs::remove_file(storage_path).unwrap();
}

#[test]
fn k16_computer_handle_k16_bios_rejects_corrupt_boot_header_magic() {
    let entry_pc = 2048;
    let bios = k16_words(&k16_stage2_boot_bios_words());
    let stage2 = k16_words(&k16_stage2_program_words());
    let mut media = k16_boot_media(entry_pc, entry_pc, 1, 1, &stage2);
    media[0..4].copy_from_slice(b"NOPE");
    let mut handle =
        K16ComputerHandle::create_k16_bios_flash_with_storage0_media(&bios, 64 * 1024, 512, media)
            .expect("K16 BIOS flash computer creates with corrupt boot media");

    assert_eq!(handle.run_k16_until_signal().unwrap(), K16Signal::Halt);
    assert_eq!(handle.debug_output_bytes(), b"");
    assert_eq!(
        handle.control(),
        K16ComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 0xB,
        },
    );
}

fn write_k16_volume(path: &std::path::Path, payload: &[u8]) {
    let mut bytes = Vec::new();
    bytes.extend_from_slice(b"K16VOL");
    bytes.extend_from_slice(&1u16.to_le_bytes());
    bytes.extend_from_slice(&(payload.len() as u64).to_le_bytes());
    bytes.extend_from_slice(payload);
    fs::write(path, bytes).unwrap();
}

fn temp_volume_path(name: &str) -> std::path::PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    std::env::temp_dir().join(format!(
        "k16-computer-{name}-{}-{nanos}.kv",
        std::process::id()
    ))
}

fn snapshot_with_timer0_game_ticks(snapshot: &[u8], game_ticks: u64) -> Vec<u8> {
    const TIMER0_DEVICE_KIND: u32 = 6;
    const DEVICE_HEADER_SIZE: usize = 8;
    const TIMER0_PAYLOAD_SIZE: usize = 8;

    let mut edited = snapshot.to_vec();
    assert_eq!(&edited[0..8], COMPUTER_SNAPSHOT_V1_MAGIC);
    let header_size = u16::from_le_bytes([edited[0x0a], edited[0x0b]]) as usize;
    assert_eq!(header_size, COMPUTER_SNAPSHOT_V1_HEADER_SIZE);
    let ram_size = u64::from_le_bytes(edited[0x10..0x18].try_into().unwrap()) as usize;
    let cpu_count = u32::from_le_bytes(edited[0x18..0x1c].try_into().unwrap()) as usize;
    let device_count = u32::from_le_bytes(edited[0x20..0x24].try_into().unwrap()) as usize;
    let mut offset = header_size + ram_size + cpu_count * COMPUTER_SNAPSHOT_V1_K16_CPU_RECORD_SIZE;
    for _ in 0..device_count {
        let device_kind = u32::from_le_bytes(edited[offset..offset + 4].try_into().unwrap());
        let payload_size =
            u32::from_le_bytes(edited[offset + 4..offset + 8].try_into().unwrap()) as usize;
        let payload_offset = offset + DEVICE_HEADER_SIZE;
        if device_kind == TIMER0_DEVICE_KIND {
            assert_eq!(payload_size, TIMER0_PAYLOAD_SIZE);
            edited[payload_offset..payload_offset + TIMER0_PAYLOAD_SIZE]
                .copy_from_slice(&game_ticks.to_le_bytes());
            return edited;
        }
        offset = payload_offset + payload_size;
    }
    panic!("K16SNAP did not contain a timer0 device record");
}

fn snapshot_timer0_game_ticks(snapshot: &[u8]) -> u64 {
    const TIMER0_DEVICE_KIND: u32 = 6;
    const DEVICE_HEADER_SIZE: usize = 8;
    const TIMER0_PAYLOAD_SIZE: usize = 8;

    assert_eq!(&snapshot[0..8], COMPUTER_SNAPSHOT_V1_MAGIC);
    let header_size = u16::from_le_bytes([snapshot[0x0a], snapshot[0x0b]]) as usize;
    assert_eq!(header_size, COMPUTER_SNAPSHOT_V1_HEADER_SIZE);
    let ram_size = u64::from_le_bytes(snapshot[0x10..0x18].try_into().unwrap()) as usize;
    let cpu_count = u32::from_le_bytes(snapshot[0x18..0x1c].try_into().unwrap()) as usize;
    let device_count = u32::from_le_bytes(snapshot[0x20..0x24].try_into().unwrap()) as usize;
    let mut offset = header_size + ram_size + cpu_count * COMPUTER_SNAPSHOT_V1_K16_CPU_RECORD_SIZE;
    for _ in 0..device_count {
        let device_kind = u32::from_le_bytes(snapshot[offset..offset + 4].try_into().unwrap());
        let payload_size =
            u32::from_le_bytes(snapshot[offset + 4..offset + 8].try_into().unwrap()) as usize;
        let payload_offset = offset + DEVICE_HEADER_SIZE;
        if device_kind == TIMER0_DEVICE_KIND {
            assert_eq!(payload_size, TIMER0_PAYLOAD_SIZE);
            return u64::from_le_bytes(
                snapshot[payload_offset..payload_offset + TIMER0_PAYLOAD_SIZE]
                    .try_into()
                    .unwrap(),
            );
        }
        offset = payload_offset + payload_size;
    }
    panic!("K16SNAP did not contain a timer0 device record");
}

fn read_u32(memory: &k16_vm::low_machine::MachineMemory, address: u32) -> u32 {
    u32::from_le_bytes(memory.load_i32(address).unwrap().to_le_bytes())
}

fn assert_hardware_entry_with_irq(
    memory: &k16_vm::low_machine::MachineMemory,
    address: u32,
    id: u32,
    base: u32,
    size: u32,
    irq_source: u32,
) {
    assert_eq!(read_u32(memory, address), id);
    assert_eq!(read_u32(memory, address + 4), base);
    assert_eq!(read_u32(memory, address + 8), size);
    assert_eq!(read_u32(memory, address + 12), irq_source);
}

fn k16_words(words: &[u16]) -> Vec<u8> {
    words
        .iter()
        .flat_map(|word| word.to_le_bytes())
        .collect::<Vec<_>>()
}

fn k16_const4(dst: u8, value: u8) -> u16 {
    0x1000 | (u16::from(dst) << 8) | u16::from(value & 0x0f)
}

fn k16_const32(dst: u8, value: u32) -> [u16; 3] {
    [
        0xe001 | (u16::from(dst) << 8),
        value as u16,
        (value >> 16) as u16,
    ]
}

fn k16_store32(addr: u8, src: u8) -> u16 {
    0x5002 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn k16_load8(dst: u8, addr: u8) -> u16 {
    0x4000 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn k16_load32(dst: u8, addr: u8) -> u16 {
    0x4002 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn k16_eq(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x2008 | (u16::from(dst) << 8),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

fn k16_branch_if_nonzero(src: u8, offset_words: u8) -> u16 {
    0x6000 | (u16::from(src) << 8) | 0x0010 | u16::from(offset_words & 0x0f)
}

fn k16_jump(target: u8) -> u16 {
    0x7000 | (u16::from(target) << 8)
}

fn k16_mmio_firmware_words() -> Vec<u16> {
    let mut words = Vec::new();
    words.extend(k16_const32(0, ComputerMachine::DEBUG_WRITE));
    words.extend(k16_const32(1, u32::from(b'R')));
    words.push(k16_store32(0, 1));
    words.extend(k16_const32(1, u32::from(b'U')));
    words.push(k16_store32(0, 1));
    words.extend(k16_const32(1, u32::from(b'X')));
    words.push(k16_store32(0, 1));
    words.extend(k16_const32(0, ComputerMachine::CONTROL_PANIC_CODE));
    words.extend(k16_const32(1, 0x16));
    words.push(k16_store32(0, 1));
    words.push(k16_halt());
    words
}

fn k16_stage2_boot_bios_words() -> Vec<u16> {
    let mut words = Vec::new();
    words.extend(k16_const32(0, 512));
    words.push(k16_const4(1, 0));
    words.extend(k16_const32(2, ComputerMachine::STORAGE0_LBA_LOW));
    words.push(k16_store32(2, 1));
    words.extend(k16_const32(2, ComputerMachine::STORAGE0_LBA_HIGH));
    words.push(k16_store32(2, 1));
    words.extend(k16_const32(2, ComputerMachine::STORAGE0_BLOCK_COUNT));
    words.push(k16_const4(3, 1));
    words.push(k16_store32(2, 3));
    words.extend(k16_const32(2, ComputerMachine::STORAGE0_BUFFER_ADDR));
    words.push(k16_store32(2, 0));
    words.extend(k16_const32(2, ComputerMachine::STORAGE0_COMMAND));
    words.push(k16_store32(2, 3));

    words.push(k16_load32(5, 0));
    words.extend(k16_const32(6, u32::from_le_bytes(*b"K16B")));
    words.extend(k16_eq(6, 5, 6));
    words.push(k16_branch_if_nonzero(6, 6));
    words.extend(k16_const32(2, ComputerMachine::CONTROL_PANIC_CODE));
    words.push(k16_const4(3, 0xB));
    words.push(k16_store32(2, 3));
    words.push(k16_halt());

    words.push(k16_const4(4, 4));
    words.extend(k16_add(0, 0, 4));
    words.push(k16_load32(7, 0));
    words.extend(k16_add(0, 0, 4));
    words.push(k16_load32(8, 0));
    words.extend(k16_add(0, 0, 4));
    words.push(k16_load32(9, 0));
    words.extend(k16_add(0, 0, 4));
    words.push(k16_load32(10, 0));

    words.extend(k16_const32(2, ComputerMachine::STORAGE0_LBA_LOW));
    words.push(k16_store32(2, 10));
    words.push(k16_const4(1, 0));
    words.extend(k16_const32(2, ComputerMachine::STORAGE0_LBA_HIGH));
    words.push(k16_store32(2, 1));
    words.extend(k16_const32(2, ComputerMachine::STORAGE0_BLOCK_COUNT));
    words.push(k16_store32(2, 9));
    words.extend(k16_const32(2, ComputerMachine::STORAGE0_BUFFER_ADDR));
    words.push(k16_store32(2, 8));
    words.extend(k16_const32(2, ComputerMachine::STORAGE0_COMMAND));
    words.push(k16_const4(3, 1));
    words.push(k16_store32(2, 3));
    words.push(k16_jump(7));
    words
}

fn k16_stage2_program_words() -> Vec<u16> {
    let mut words = Vec::new();
    words.extend(k16_const32(0, ComputerMachine::DEBUG_WRITE));
    words.extend(k16_const32(1, u32::from(b'S')));
    words.push(k16_store32(0, 1));
    words.extend(k16_const32(1, u32::from(b'2')));
    words.push(k16_store32(0, 1));
    words.extend(k16_const32(0, ComputerMachine::CONTROL_PANIC_CODE));
    words.extend(k16_const32(1, 0x52));
    words.push(k16_store32(0, 1));
    words.push(k16_halt());
    words
}

fn k16_boot_media(
    entry_pc: u32,
    load_addr: u32,
    block_count: u32,
    start_lba: u32,
    stage2: &[u8],
) -> Vec<u8> {
    let mut media = vec![0; 1024];
    media[0..4].copy_from_slice(b"K16B");
    media[4..8].copy_from_slice(&entry_pc.to_le_bytes());
    media[8..12].copy_from_slice(&load_addr.to_le_bytes());
    media[12..16].copy_from_slice(&block_count.to_le_bytes());
    media[16..20].copy_from_slice(&start_lba.to_le_bytes());
    media[512..512 + stage2.len()].copy_from_slice(stage2);
    media
}

fn k16_storage_read_bios_words() -> Vec<u16> {
    let mut words = Vec::new();
    words.extend(k16_const32(0, ComputerMachine::STORAGE0_LBA_LOW));
    words.push(k16_const4(1, 0));
    words.push(k16_store32(0, 1));
    words.extend(k16_const32(0, ComputerMachine::STORAGE0_LBA_HIGH));
    words.push(k16_store32(0, 1));
    words.extend(k16_const32(0, ComputerMachine::STORAGE0_BLOCK_COUNT));
    words.push(k16_const4(1, 1));
    words.push(k16_store32(0, 1));
    words.extend(k16_const32(0, ComputerMachine::STORAGE0_BUFFER_ADDR));
    words.extend(k16_const32(1, 512));
    words.push(k16_store32(0, 1));
    words.extend(k16_const32(0, ComputerMachine::STORAGE0_COMMAND));
    words.push(k16_const4(1, 1));
    words.push(k16_store32(0, 1));

    words.extend(k16_const32(0, 512));
    words.extend(k16_const32(3, ComputerMachine::DEBUG_WRITE));
    words.push(k16_const4(4, 1));
    words.push(k16_load8(2, 0));
    words.push(k16_store32(3, 2));
    words.extend(k16_add(0, 0, 4));
    words.push(k16_load8(2, 0));
    words.push(k16_store32(3, 2));
    words.extend(k16_add(0, 0, 4));
    words.push(k16_load8(2, 0));
    words.push(k16_store32(3, 2));

    words.extend(k16_const32(0, ComputerMachine::STORAGE0_STATUS));
    words.push(k16_load32(2, 0));
    words.extend(k16_const32(0, ComputerMachine::CONTROL_PANIC_CODE));
    words.push(k16_store32(0, 2));
    words.push(k16_halt());
    words
}

fn k16_add(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x2000 | (u16::from(dst) << 8),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

fn k16_halt() -> u16 {
    0x0001
}

fn k16_call(register: u8) -> u16 {
    0x8000 | (u16::from(register) << 8)
}

fn k16_ret() -> u16 {
    0x9000
}

fn k16_read_csr(dst: u8, csr: u32) -> u16 {
    0x0002 | (u16::from(dst) << 8) | ((csr as u16) << 4)
}

fn k16_write_csr(csr: u32, src: u8) -> u16 {
    0x0003 | ((csr as u16) << 8) | (u16::from(src) << 4)
}

fn k16_iret() -> u16 {
    0x0004
}
