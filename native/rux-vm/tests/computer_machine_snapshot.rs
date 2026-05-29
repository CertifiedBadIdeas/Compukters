use rux_vm::computer_machine::{
    decode_snapshot_v1, ComputerMachine, ComputerMachineProfile, COMPUTER_SNAPSHOT_V1_HEADER_SIZE,
    COMPUTER_SNAPSHOT_V1_MAGIC, COMPUTER_SNAPSHOT_V1_RUX16_CPU_RECORD_SIZE,
};
use rux_vm::rux16::Rux16Signal;

#[test]
fn computer_machine_snapshot_v1_records_header_and_ram_payload() {
    let bios = [0x01, 0x00];
    let (mut machine, boot_cpu) =
        ComputerMachine::from_rux16_bios_flash(&bios, 1024, 8).expect("machine creates");
    machine.memory_mut().store_u8(512, 0xA5).unwrap();
    machine.memory_mut().store_u8(1023, 0x5A).unwrap();

    let snapshot = machine.snapshot_v1().expect("snapshot encodes");

    assert_eq!(&snapshot[0..8], COMPUTER_SNAPSHOT_V1_MAGIC);
    assert_eq!(
        snapshot.len(),
        COMPUTER_SNAPSHOT_V1_HEADER_SIZE + 1024 + COMPUTER_SNAPSHOT_V1_RUX16_CPU_RECORD_SIZE
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
    assert_eq!(decoded.ram[512], 0xA5);
    assert_eq!(decoded.ram[1023], 0x5A);
    assert_eq!(decoded.cpus.len(), 1);
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
    let bios = rux16_words(&[halt()]);
    let program = rux16_words(&[const4(1, 5), const32(4), 512, 0, store32(4, 1), halt()]);
    let (mut machine, boot_cpu) =
        ComputerMachine::from_rux16_bios_flash(&bios, 1024, 8).expect("machine creates");
    machine.write_guest_ram_bytes(0x100, &program).unwrap();
    machine
        .boot_handoff_rux16_from_ram(0x100, program.len() as u32, 2)
        .expect("boot handoff succeeds");

    assert_eq!(
        machine.run_boot_rux16_until_signal(boot_cpu).unwrap(),
        Rux16Signal::StepLimitExceeded
    );
    assert_eq!(&machine.memory().bytes()[512..516], &[0, 0, 0, 0]);

    let snapshot = machine.snapshot_v1().expect("snapshot encodes");
    let mut restored =
        ComputerMachine::restore_snapshot_v1(ComputerMachineProfile::computer_v1(1024), &snapshot)
            .expect("snapshot restores");

    assert_eq!(restored.boot_cpu_id(), Some(boot_cpu));
    assert_eq!(restored.cpu_count(), 1);
    assert_eq!(
        restored.run_boot_rux16_until_signal(boot_cpu).unwrap(),
        Rux16Signal::Halt
    );
    assert_eq!(&restored.memory().bytes()[512..516], &[5, 0, 0, 0]);
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
        "ComputerMachine snapshot declares 1024 payload bytes but file has 1023 payload bytes"
    );
}

#[test]
fn computer_machine_snapshot_v1_rejects_invalid_cpu_record_fields() {
    let bios = rux16_words(&[halt()]);
    let (machine, _) =
        ComputerMachine::from_rux16_bios_flash(&bios, 1024, 8).expect("machine creates");
    let snapshot = machine.snapshot_v1().expect("snapshot encodes");
    let cpu_record = COMPUTER_SNAPSHOT_V1_HEADER_SIZE + 1024;

    let mut bad_state = snapshot.clone();
    bad_state[cpu_record + 4..cpu_record + 8].copy_from_slice(&99_u32.to_le_bytes());
    assert_eq!(
        decode_snapshot_v1(&bad_state).unwrap_err(),
        "unsupported ComputerMachine snapshot Rux16 CPU state 99"
    );

    let mut bad_reserved = snapshot.clone();
    bad_reserved[cpu_record + 36..cpu_record + 40].copy_from_slice(&1_u32.to_le_bytes());
    assert_eq!(
        decode_snapshot_v1(&bad_reserved).unwrap_err(),
        "unsupported ComputerMachine snapshot CPU 0 reserved field 0x00000001"
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
        "ComputerMachine snapshot Rux16 CPU max_steps must be non-zero"
    );
}

fn rux16_words(words: &[u16]) -> Vec<u8> {
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
