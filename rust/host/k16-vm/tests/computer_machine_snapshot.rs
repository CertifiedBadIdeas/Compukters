use k16_vm::computer_machine::{
    decode_snapshot_v1, ComputerCpuSnapshotRecord, ComputerMachine, ComputerMachineProfile,
    COMPUTER_SNAPSHOT_V1_HEADER_SIZE, COMPUTER_SNAPSHOT_V1_K16_CPU_RECORD_SIZE,
    COMPUTER_SNAPSHOT_V1_MAGIC,
};
use k16_vm::k16::{
    K16Signal, K16_CSR_INTERRUPT_ENABLE, K16_CSR_INTERRUPT_MASK, K16_CSR_TRAP_VALUE,
    K16_CSR_TRAP_VECTOR, K16_INTERRUPT_SOURCE_TIMER0,
};

const CONTROL_DEVICE_RECORD_SIZE: usize = 20;
const EMPTY_DEBUG_DEVICE_RECORD_SIZE: usize = 8;
const EMPTY_DISPLAY0_DEVICE_RECORD_SIZE: usize = 2032;
const EMPTY_SERIAL_INPUT_DEVICE_RECORD_SIZE: usize = 8;
const STORAGE0_DEVICE_RECORD_SIZE: usize = 44;
const TIMER0_DEVICE_RECORD_SIZE: usize = 16;

#[test]
fn computer_machine_snapshot_v1_records_header_and_ram_payload() {
    let bios = [0x01, 0x00];
    let (mut machine, boot_cpu) =
        ComputerMachine::from_k16_bios_flash(&bios, 1024, 8).expect("machine creates");
    machine.memory_mut().store_u8(512, 0xA5).unwrap();
    machine.memory_mut().store_u8(1023, 0x5A).unwrap();

    let snapshot = machine.snapshot_v1().expect("snapshot encodes");

    assert_eq!(&snapshot[0..8], COMPUTER_SNAPSHOT_V1_MAGIC);
    assert_eq!(
        snapshot.len(),
        COMPUTER_SNAPSHOT_V1_HEADER_SIZE
            + 1024
            + COMPUTER_SNAPSHOT_V1_K16_CPU_RECORD_SIZE
            + CONTROL_DEVICE_RECORD_SIZE
            + EMPTY_DEBUG_DEVICE_RECORD_SIZE
            + EMPTY_DISPLAY0_DEVICE_RECORD_SIZE
            + EMPTY_SERIAL_INPUT_DEVICE_RECORD_SIZE
            + STORAGE0_DEVICE_RECORD_SIZE
            + TIMER0_DEVICE_RECORD_SIZE
    );

    let decoded = decode_snapshot_v1(&snapshot).expect("snapshot decodes");
    assert_eq!(decoded.header.version, 1);
    assert_eq!(
        decoded.header.header_size,
        COMPUTER_SNAPSHOT_V1_HEADER_SIZE as u16
    );
    assert_eq!(decoded.header.flags, 0);
    assert_eq!(decoded.header.ram_size, 1024);
    assert_eq!(decoded.header.cpu_count, 1);
    assert_eq!(decoded.header.boot_cpu_id, Some(boot_cpu as u32));
    assert_eq!(decoded.header.device_count, 6);
    assert_eq!(decoded.ram[512], 0xA5);
    assert_eq!(decoded.ram[1023], 0x5A);
    assert_eq!(decoded.cpus.len(), 1);
    assert_eq!(decoded.devices.len(), 6);
}

#[test]
fn computer_machine_snapshot_v1_restores_ram_bytes_without_recreating_cpu_state() {
    let mut machine = ComputerMachine::new(1024).expect("machine creates");
    machine.memory_mut().store_u8(512, 0xC3).unwrap();

    let snapshot = machine.snapshot_v1().expect("snapshot encodes");
    let restored = ComputerMachine::restore_ram_snapshot_v1(
        ComputerMachineProfile::computer_v1(1024),
        &snapshot,
    )
    .expect("snapshot restores RAM");

    assert_eq!(restored.memory().bytes()[512], 0xC3);
    assert_eq!(restored.cpu_count(), 0);
    assert_eq!(restored.boot_cpu_id(), None);
}

#[test]
fn computer_machine_snapshot_v1_restores_boot_cpu_continuation_state() {
    let bios = k16_words(&[halt()]);
    let program = k16_words(&[const4(1, 5), const32(4), 512, 0, store32(4, 1), halt()]);
    let (mut machine, boot_cpu) =
        ComputerMachine::from_k16_bios_flash(&bios, 1024, 8).expect("machine creates");
    machine.write_guest_ram_bytes(0x100, &program).unwrap();
    machine
        .boot_handoff_k16_from_ram(0x100, program.len() as u32, 2)
        .expect("boot handoff succeeds");

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::StepLimitExceeded
    );
    assert_eq!(&machine.memory().bytes()[512..516], &[0, 0, 0, 0]);

    let snapshot = machine.snapshot_v1().expect("snapshot encodes");
    let mut restored =
        ComputerMachine::restore_snapshot_v1(ComputerMachineProfile::computer_v1(1024), &snapshot)
            .expect("snapshot restores");

    assert_eq!(restored.boot_cpu_id(), Some(boot_cpu));
    assert_eq!(restored.cpu_count(), 1);
    assert_eq!(
        restored.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt
    );
    assert_eq!(&restored.memory().bytes()[512..516], &[5, 0, 0, 0]);
}

#[test]
fn computer_machine_snapshot_v1_restores_k16_interrupt_state() {
    let entry_pc = 0x100;
    let mut words = Vec::new();
    words.extend([const32(1), (entry_pc + 30) as u16, 0]);
    words.push(write_csr(K16_CSR_TRAP_VECTOR, 1));
    words.push(const4(1, K16_INTERRUPT_SOURCE_TIMER0 as u16));
    words.push(write_csr(K16_CSR_INTERRUPT_MASK, 1));
    words.push(const4(1, 1));
    words.push(write_csr(K16_CSR_INTERRUPT_ENABLE, 1));
    words.extend([
        const32(0),
        ComputerMachine::CONTROL_PANIC_CODE as u16,
        0x1000,
    ]);
    words.push(store32(0, 3));
    words.push(halt());
    words.extend([0; 3]);
    words.push(read_csr(3, K16_CSR_TRAP_VALUE));
    words.push(iret());
    let bios = k16_words(&[halt()]);
    let program = k16_words(&words);
    let (mut machine, boot_cpu) =
        ComputerMachine::from_k16_bios_flash(&bios, 1024, 7).expect("machine creates");
    machine.write_guest_ram_bytes(entry_pc, &program).unwrap();
    machine
        .boot_handoff_k16_from_ram(entry_pc, program.len() as u32, 7)
        .expect("boot handoff succeeds");

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::StepLimitExceeded
    );

    machine.advance_game_tick();

    let snapshot = machine.snapshot_v1().expect("snapshot encodes");
    let mut restored =
        ComputerMachine::restore_snapshot_v1(ComputerMachineProfile::computer_v1(1024), &snapshot)
            .expect("snapshot restores");

    assert_eq!(
        restored.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::Halt
    );
    assert_eq!(restored.panic_code(), 1);
}

#[test]
fn computer_machine_snapshot_v1_preserves_k16_trap_arg0() {
    let entry_pc = 0x100;
    let mut words = Vec::new();
    words.extend([const32(1), (entry_pc + 18) as u16, 0]);
    words.push(write_csr(K16_CSR_TRAP_VECTOR, 1));
    words.push(const4(1, 3));
    words.extend([const32(2), 0x21, 0]);
    words.push(syscall(1));
    let bios = k16_words(&[halt()]);
    let program = k16_words(&words);
    let (mut machine, boot_cpu) =
        ComputerMachine::from_k16_bios_flash(&bios, 1024, 5).expect("machine creates");
    machine.write_guest_ram_bytes(entry_pc, &program).unwrap();
    machine
        .boot_handoff_k16_from_ram(entry_pc, program.len() as u32, 6)
        .expect("boot handoff succeeds");

    assert_eq!(
        machine.run_boot_k16_until_signal(boot_cpu).unwrap(),
        K16Signal::StepLimitExceeded
    );

    let snapshot = machine.snapshot_v1().expect("snapshot encodes");
    let decoded = decode_snapshot_v1(&snapshot).expect("snapshot decodes");
    let ComputerCpuSnapshotRecord::K16 { cpu, .. } = &decoded.cpus[0];

    assert_eq!(cpu.trap_arg0, 0x21);
}

#[test]
fn computer_machine_snapshot_v1_restores_control_and_debug_device_state() {
    let mut machine = ComputerMachine::new(1024).expect("machine creates");
    machine
        .bus_store_i32(ComputerMachine::DEBUG_WRITE, b'O'.into())
        .unwrap();
    machine
        .bus_store_i32(ComputerMachine::DEBUG_WRITE, b'K'.into())
        .unwrap();
    machine
        .bus_store_i32(
            ComputerMachine::CONTROL_STATUS,
            ComputerMachine::STATUS_PANIC,
        )
        .unwrap();
    machine
        .bus_store_i32(ComputerMachine::CONTROL_PANIC_CODE, 123)
        .unwrap();
    machine
        .bus_store_i32(ComputerMachine::CONTROL_EXIT_CODE, 7)
        .unwrap();

    let snapshot = machine.snapshot_v1().expect("snapshot encodes");
    let restored =
        ComputerMachine::restore_snapshot_v1(ComputerMachineProfile::computer_v1(1024), &snapshot)
            .expect("snapshot restores");

    assert_eq!(restored.control_status(), ComputerMachine::STATUS_PANIC);
    assert_eq!(restored.panic_code(), 123);
    assert_eq!(restored.exit_code(), 7);
    assert_eq!(restored.debug_output_bytes(), b"OK");
}

#[test]
fn computer_machine_snapshot_v1_restores_display0_device_state() {
    let mut machine = ComputerMachine::new(1024).expect("machine creates");
    machine
        .bus_store_i32(ComputerMachine::DISPLAY0_CURSOR_X, 3)
        .unwrap();
    machine
        .bus_store_i32(ComputerMachine::DISPLAY0_CURSOR_Y, 2)
        .unwrap();
    machine
        .bus_store_i32(ComputerMachine::DISPLAY0_DATA, i32::from(b'R'))
        .unwrap();
    machine
        .bus_store_i32(
            ComputerMachine::DISPLAY0_COMMAND,
            ComputerMachine::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR,
        )
        .unwrap();
    machine
        .bus_store_i32(ComputerMachine::DISPLAY0_CURSOR_X, 12)
        .unwrap();

    let before = machine.display0_snapshot().expect("display0 is present");
    let snapshot = machine.snapshot_v1().expect("snapshot encodes");
    let restored =
        ComputerMachine::restore_snapshot_v1(ComputerMachineProfile::computer_v1(1024), &snapshot)
            .expect("snapshot restores");

    assert_eq!(
        restored.display0_snapshot().expect("display0 is present"),
        before
    );
}

#[test]
fn computer_machine_snapshot_v1_restores_serial_input_device_state() {
    let mut machine = ComputerMachine::new(1024).expect("machine creates");
    machine.push_serial_input(b"ABC");
    assert_eq!(
        machine
            .bus_load_i32(ComputerMachine::SERIAL_INPUT_READ)
            .unwrap(),
        i32::from(b'A')
    );

    let snapshot = machine.snapshot_v1().expect("snapshot encodes");
    let restored =
        ComputerMachine::restore_snapshot_v1(ComputerMachineProfile::computer_v1(1024), &snapshot)
            .expect("snapshot restores");

    assert_eq!(restored.serial_input_len(), 2);
    assert_eq!(
        restored
            .bus_load_i32(ComputerMachine::SERIAL_INPUT_READY)
            .unwrap(),
        1
    );
    assert_eq!(
        restored
            .bus_load_i32(ComputerMachine::SERIAL_INPUT_READ)
            .unwrap(),
        i32::from(b'B')
    );
    assert_eq!(
        restored
            .bus_load_i32(ComputerMachine::SERIAL_INPUT_READ)
            .unwrap(),
        i32::from(b'C')
    );
    assert_eq!(restored.serial_input_len(), 0);
}

#[test]
fn computer_machine_snapshot_v1_restores_storage0_controller_state() {
    let profile =
        ComputerMachineProfile::computer_v1_with_storage0_media(2048, vec![0x5A; 1024], false);
    let mut machine = ComputerMachine::from_profile(profile.clone()).expect("machine creates");
    machine
        .bus_store_i32(ComputerMachine::STORAGE0_LBA_LOW, 1)
        .unwrap();
    machine
        .bus_store_i32(ComputerMachine::STORAGE0_BLOCK_COUNT, 1)
        .unwrap();
    machine
        .bus_store_i32(ComputerMachine::STORAGE0_BUFFER_ADDR, 512)
        .unwrap();
    machine
        .bus_store_i32(
            ComputerMachine::STORAGE0_COMMAND,
            k16_vm::computer_abi::STORAGE_COMMAND_READ_BLOCKS,
        )
        .unwrap();
    machine
        .bus_store_i32(ComputerMachine::STORAGE0_LBA_LOW, 3)
        .unwrap();

    let snapshot = machine.snapshot_v1().expect("snapshot encodes");
    let restored = ComputerMachine::restore_snapshot_v1(profile, &snapshot).expect("restores");

    assert_eq!(
        restored
            .bus_load_i32(ComputerMachine::STORAGE0_STATUS)
            .unwrap(),
        k16_vm::computer_abi::STORAGE_STATUS_DONE
    );
    assert_eq!(
        restored
            .bus_load_i32(ComputerMachine::STORAGE0_ERROR)
            .unwrap(),
        k16_vm::computer_abi::STORAGE_ERROR_NONE
    );
    assert_eq!(
        restored
            .bus_load_i32(ComputerMachine::STORAGE0_LBA_LOW)
            .unwrap(),
        3
    );
    assert_eq!(
        restored
            .bus_load_i32(ComputerMachine::STORAGE0_LBA_HIGH)
            .unwrap(),
        0
    );
    assert_eq!(
        restored
            .bus_load_i32(ComputerMachine::STORAGE0_BLOCK_COUNT)
            .unwrap(),
        1
    );
    assert_eq!(
        restored
            .bus_load_i32(ComputerMachine::STORAGE0_BUFFER_ADDR)
            .unwrap(),
        512
    );
    assert_eq!(
        restored
            .bus_load_i32(ComputerMachine::STORAGE0_BYTES_DONE)
            .unwrap(),
        512
    );
    assert_eq!(
        restored
            .bus_load_i32(ComputerMachine::STORAGE0_SEQUENCE_LOW)
            .unwrap(),
        1
    );
    assert_eq!(
        restored
            .bus_load_i32(ComputerMachine::STORAGE0_SEQUENCE_HIGH)
            .unwrap(),
        0
    );
    assert_eq!(
        restored
            .bus_load_i32(ComputerMachine::STORAGE0_MEDIA_STATUS)
            .unwrap(),
        k16_vm::computer_abi::STORAGE_MEDIA_PRESENT
    );
}

#[test]
fn computer_machine_snapshot_v1_restores_timer0_game_ticks() {
    let mut machine = ComputerMachine::new(1024).expect("machine creates");
    machine.advance_game_tick();
    machine.advance_game_tick();

    let snapshot = machine.snapshot_v1().expect("snapshot encodes");
    let mut restored =
        ComputerMachine::restore_snapshot_v1(ComputerMachineProfile::computer_v1(1024), &snapshot)
            .expect("snapshot restores");

    assert_eq!(
        restored
            .bus_load_i32(ComputerMachine::TIMER0_GAME_TICKS_LOW)
            .unwrap(),
        2
    );

    restored.advance_game_tick();

    assert_eq!(
        restored
            .bus_load_i32(ComputerMachine::TIMER0_GAME_TICKS_LOW)
            .unwrap(),
        3
    );
}

#[test]
fn computer_machine_snapshot_v1_rejects_profile_ram_size_mismatch() {
    let machine = ComputerMachine::new(1024).expect("machine creates");
    let snapshot = machine.snapshot_v1().expect("snapshot encodes");

    let error = match ComputerMachine::restore_ram_snapshot_v1(
        ComputerMachineProfile::computer_v1(2048),
        &snapshot,
    ) {
        Ok(_) => panic!("snapshot restore should reject mismatched profile RAM size"),
        Err(error) => error,
    };

    assert_eq!(
        error,
        "ComputerMachine snapshot RAM size 1024 does not match profile memory size 2048"
    );
}

#[test]
fn computer_machine_snapshot_v1_rejects_bad_magic_version_and_length() {
    let machine = ComputerMachine::new(1024).expect("machine creates");
    let snapshot = machine.snapshot_v1().expect("snapshot encodes");

    let mut bad_magic = snapshot.clone();
    bad_magic[0] = b'X';
    assert_eq!(
        decode_snapshot_v1(&bad_magic).unwrap_err(),
        "invalid ComputerMachine snapshot magic"
    );

    let mut bad_version = snapshot.clone();
    bad_version[8..10].copy_from_slice(&2_u16.to_le_bytes());
    assert_eq!(
        decode_snapshot_v1(&bad_version).unwrap_err(),
        "unsupported ComputerMachine snapshot version 2"
    );

    let truncated = &snapshot[..snapshot.len() - 1];
    assert_eq!(
        decode_snapshot_v1(truncated).unwrap_err(),
        "ComputerMachine snapshot device 5 payload is truncated"
    );
}

#[test]
fn computer_machine_snapshot_v1_rejects_invalid_cpu_record_fields() {
    let bios = k16_words(&[halt()]);
    let (machine, _) =
        ComputerMachine::from_k16_bios_flash(&bios, 1024, 8).expect("machine creates");
    let snapshot = machine.snapshot_v1().expect("snapshot encodes");
    let cpu_record = COMPUTER_SNAPSHOT_V1_HEADER_SIZE + 1024;

    let mut bad_state = snapshot.clone();
    bad_state[cpu_record + 4..cpu_record + 8].copy_from_slice(&99_u32.to_le_bytes());
    assert_eq!(
        decode_snapshot_v1(&bad_state).unwrap_err(),
        "unsupported ComputerMachine snapshot K16 CPU state 99"
    );

    let mut bad_interrupt_enable = snapshot.clone();
    bad_interrupt_enable[cpu_record + 36..cpu_record + 40].copy_from_slice(&2_u32.to_le_bytes());
    assert_eq!(
        decode_snapshot_v1(&bad_interrupt_enable).unwrap_err(),
        "unsupported ComputerMachine snapshot K16 CPU interrupt_enable value 2"
    );

    let mut bad_max_steps = snapshot;
    bad_max_steps[cpu_record + 8..cpu_record + 16].copy_from_slice(&0_u64.to_le_bytes());
    let error = match ComputerMachine::restore_snapshot_v1(
        ComputerMachineProfile::computer_v1(1024),
        &bad_max_steps,
    ) {
        Ok(_) => panic!("snapshot restore should reject zero max_steps"),
        Err(error) => error,
    };
    assert_eq!(
        error,
        "ComputerMachine snapshot K16 CPU max_steps must be non-zero"
    );
}

#[test]
fn computer_machine_snapshot_v1_rejects_invalid_device_record_fields() {
    let machine = ComputerMachine::new(1024).expect("machine creates");
    let snapshot = machine.snapshot_v1().expect("snapshot encodes");
    let first_device_record = COMPUTER_SNAPSHOT_V1_HEADER_SIZE + 1024;
    let display0_device_record =
        first_device_record + CONTROL_DEVICE_RECORD_SIZE + EMPTY_DEBUG_DEVICE_RECORD_SIZE;
    let storage0_device_record = display0_device_record
        + EMPTY_DISPLAY0_DEVICE_RECORD_SIZE
        + EMPTY_SERIAL_INPUT_DEVICE_RECORD_SIZE;

    let mut bad_reserved = snapshot.clone();
    bad_reserved[36..40].copy_from_slice(&1_u32.to_le_bytes());
    assert_eq!(
        decode_snapshot_v1(&bad_reserved).unwrap_err(),
        "unsupported ComputerMachine snapshot reserved header field 0x00000001"
    );

    let mut bad_device_kind = snapshot.clone();
    bad_device_kind[first_device_record..first_device_record + 4]
        .copy_from_slice(&99_u32.to_le_bytes());
    assert_eq!(
        decode_snapshot_v1(&bad_device_kind).unwrap_err(),
        "unsupported ComputerMachine snapshot device 0 kind 99"
    );

    let mut bad_control_payload_size = snapshot.clone();
    bad_control_payload_size[first_device_record + 4..first_device_record + 8]
        .copy_from_slice(&11_u32.to_le_bytes());
    assert_eq!(
        decode_snapshot_v1(&bad_control_payload_size).unwrap_err(),
        "ComputerMachine snapshot control device payload has 11 bytes but expected 12"
    );

    let mut bad_display0_cells = snapshot.clone();
    bad_display0_cells[display0_device_record + 4..display0_device_record + 8]
        .copy_from_slice(&(EMPTY_DISPLAY0_DEVICE_RECORD_SIZE as u32 - 9).to_le_bytes());
    assert_eq!(
        decode_snapshot_v1(&bad_display0_cells).unwrap_err(),
        "ComputerMachine snapshot display0 device payload has 1999 cells but expected 2000"
    );

    let mut bad_display0_cursor = snapshot.clone();
    bad_display0_cursor[display0_device_record + 16..display0_device_record + 20]
        .copy_from_slice(&80_u32.to_le_bytes());
    let error = match ComputerMachine::restore_snapshot_v1(
        ComputerMachineProfile::computer_v1(1024),
        &bad_display0_cursor,
    ) {
        Ok(_) => panic!("snapshot restore should reject out-of-bounds display0 cursor"),
        Err(error) => error,
    };
    assert_eq!(error, "display0 snapshot cursor 80,0 is outside 80x25");

    let mut bad_storage0_payload_size = snapshot.clone();
    bad_storage0_payload_size[storage0_device_record + 4..storage0_device_record + 8]
        .copy_from_slice(&35_u32.to_le_bytes());
    assert_eq!(
        decode_snapshot_v1(&bad_storage0_payload_size).unwrap_err(),
        "ComputerMachine snapshot storage0 device payload has 35 bytes but expected 36"
    );

    let mut trailing_bytes = snapshot;
    trailing_bytes.push(0);
    assert_eq!(
        decode_snapshot_v1(&trailing_bytes).unwrap_err(),
        "ComputerMachine snapshot has 1 trailing bytes after device records"
    );
}

fn k16_words(words: &[u16]) -> Vec<u8> {
    words.iter().flat_map(|word| word.to_le_bytes()).collect()
}

fn halt() -> u16 {
    0x0001
}

fn const4(dst: u16, value: u16) -> u16 {
    0x1000 | (dst << 8) | (value & 0x000f)
}

fn const32(dst: u16) -> u16 {
    0xe001 | (dst << 8)
}

fn store32(addr: u16, src: u16) -> u16 {
    0x5002 | (addr << 8) | (src << 4)
}

fn read_csr(dst: u16, csr: u32) -> u16 {
    0x0002 | (dst << 8) | ((csr as u16) << 4)
}

fn write_csr(csr: u32, src: u16) -> u16 {
    0x0003 | ((csr as u16) << 8) | (src << 4)
}

fn syscall(register: u16) -> u16 {
    0x0005 | (register << 8)
}

fn iret() -> u16 {
    0x0004
}
