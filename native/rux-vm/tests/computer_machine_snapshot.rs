use rux_vm::computer_machine::{
    decode_snapshot_v1, ComputerMachine, ComputerMachineProfile, COMPUTER_SNAPSHOT_V1_HEADER_SIZE,
    COMPUTER_SNAPSHOT_V1_MAGIC,
};

#[test]
fn computer_machine_snapshot_v1_records_header_and_ram_payload() {
    let bios = [0x01, 0x00];
    let (mut machine, boot_cpu) =
        ComputerMachine::from_rux16_bios_flash(&bios, 1024, 8).expect("machine creates");
    machine.memory_mut().store_u8(512, 0xA5).unwrap();
    machine.memory_mut().store_u8(1023, 0x5A).unwrap();

    let snapshot = machine.snapshot_v1().expect("snapshot encodes");

    assert_eq!(&snapshot[0..8], COMPUTER_SNAPSHOT_V1_MAGIC);
    assert_eq!(snapshot.len(), COMPUTER_SNAPSHOT_V1_HEADER_SIZE + 1024);

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
        "ComputerMachine snapshot declares 1024 RAM bytes but file has 1023 RAM bytes"
    );
}
