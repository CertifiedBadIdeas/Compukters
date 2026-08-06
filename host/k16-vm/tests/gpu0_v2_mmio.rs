use k16_vm::computer_machine::ComputerMachine;
use k16_vm::retained_gpu::ResultCode;

const PACKET_ADDRESS: u32 = 0x1000;

#[test]
fn computer_gpu0_retained_commits_guest_packet_and_late_attach_sees_it() {
    let mut machine = ComputerMachine::new(64 * 1024).expect("machine creates");
    let packet = packet(0, &[replace_draw_list(0x1234)]);

    submit(&mut machine, PACKET_ADDRESS, &packet);

    for (register, value) in [
        (ComputerMachine::GPU0_DEVICE_ABI_VERSION, 2),
        (ComputerMachine::GPU0_WIDTH, 320),
        (ComputerMachine::GPU0_HEIGHT, 200),
        (ComputerMachine::GPU0_PACKET_VERSION, 1),
        (ComputerMachine::GPU0_MAX_PACKET_BYTES, 524_288),
        (ComputerMachine::GPU0_MAX_TRANSACTION_OPERATIONS, 2_048),
        (ComputerMachine::GPU0_MAX_RESOURCES, 128),
        (ComputerMachine::GPU0_MAX_RESOURCE_BYTES, 131_072),
        (ComputerMachine::GPU0_MAX_TOTAL_RESOURCE_BYTES, 262_144),
        (ComputerMachine::GPU0_MAX_DRAW_LIST_BYTES, 65_536),
        (ComputerMachine::GPU0_MAX_DRAW_COMMANDS, 2_048),
        (ComputerMachine::GPU0_MAX_CLIP_DEPTH, 32),
    ] {
        assert_eq!(machine.bus_load_i32(register).unwrap(), value);
    }
    assert_eq!(result_code(&machine), ResultCode::Ok);
    assert_eq!(committed_sequence(&machine), 1);
    assert_eq!(machine.gpu0_authoritative_payload_bytes(), 8);

    let epoch = machine
        .attach_retained_display_viewer(101, 42)
        .expect("late viewer attaches");
    assert_ne!(epoch, 0);
    let snapshot = machine
        .drain_retained_display_payload(101)
        .expect("gpu0 exists")
        .expect("snapshot is queued");
    assert_eq!(&snapshot[0..4], b"KDSP");
    assert_eq!(read_u64(&snapshot, 24), 1);
    assert_eq!(read_u32(&snapshot, 36), 8);
    assert_eq!(read_u16(&snapshot, 40), 0x1234);
    let stats = machine.stats_snapshot();
    let gpu = stats
        .devices
        .iter()
        .find(|device| device.name == "gpu0")
        .expect("gpu0 stats are present")
        .gpu;
    assert_eq!(gpu.submission_attempts, 1);
    assert_eq!(gpu.committed_submissions, 1);
    assert_eq!(gpu.rejected_submissions, 0);
    assert_eq!(gpu.submitted_bytes, packet.len() as u64);
    assert_eq!(gpu.resource_count, 0);
    assert_eq!(gpu.authoritative_payload_bytes, 8);
    assert_eq!(gpu.viewer_count, 1);
    assert_eq!(gpu.snapshot_payloads, 1);
    assert_eq!(gpu.delta_payloads, 0);
    assert_eq!(gpu.network_payload_bytes, snapshot.len() as u64);
}

#[test]
fn computer_gpu0_retained_rejects_invalid_copy_preconditions_without_mutation() {
    let packet = packet(0, &[replace_draw_list(0x1234)]);

    for (address, length, expected) in [
        (
            PACKET_ADDRESS + 1,
            packet.len() as u32,
            ResultCode::InvalidArgument,
        ),
        (PACKET_ADDRESS, 23, ResultCode::InvalidArgument),
        (PACKET_ADDRESS, 524_289, ResultCode::InvalidArgument),
        (64 * 1024 - 20, packet.len() as u32, ResultCode::OutOfBounds),
    ] {
        let mut machine = ComputerMachine::new(64 * 1024).expect("machine creates");
        machine
            .write_guest_ram_bytes(PACKET_ADDRESS, &packet)
            .expect("valid packet fixture fits RAM");
        ring(&mut machine, address, length);

        assert_eq!(result_code(&machine), expected);
        assert_eq!(error_operation_index(&machine), u32::MAX);
        assert_eq!(error_byte_offset(&machine), u32::MAX);
        assert_eq!(committed_sequence(&machine), 0);
        assert_eq!(machine.gpu0_authoritative_payload_bytes(), 8);
    }
}

#[test]
fn computer_gpu0_retained_reports_decoder_locations_and_stale_base() {
    let mut machine = ComputerMachine::new(64 * 1024).expect("machine creates");
    let malformed = vec![0; 24];
    submit(&mut machine, PACKET_ADDRESS, &malformed);

    assert_eq!(result_code(&machine), ResultCode::MalformedPacket);
    assert_eq!(error_operation_index(&machine), u32::MAX);
    assert_eq!(error_byte_offset(&machine), 0);
    assert_eq!(committed_sequence(&machine), 0);

    let first = packet(0, &[replace_draw_list(0x1234)]);
    submit(&mut machine, PACKET_ADDRESS, &first);
    assert_eq!(result_code(&machine), ResultCode::Ok);
    assert_eq!(committed_sequence(&machine), 1);

    submit(&mut machine, PACKET_ADDRESS, &first);
    assert_eq!(result_code(&machine), ResultCode::StaleBase);
    assert_eq!(error_operation_index(&machine), u32::MAX);
    assert_eq!(error_byte_offset(&machine), 16);
    assert_eq!(committed_sequence(&machine), 1);
    assert_eq!(machine.gpu0_authoritative_payload_bytes(), 8);
}

#[test]
fn computer_gpu0_retained_rolls_back_an_earlier_valid_operation() {
    let mut machine = ComputerMachine::new(64 * 1024).expect("machine creates");
    let first = packet(0, &[replace_draw_list(0x1234)]);
    submit(&mut machine, PACKET_ADDRESS, &first);

    let rejected = packet(1, &[create_image(7, 0xabcd), drop_resource(999)]);
    submit(&mut machine, PACKET_ADDRESS, &rejected);

    assert_eq!(result_code(&machine), ResultCode::InvalidResource);
    assert_eq!(error_operation_index(&machine), 1);
    assert_eq!(error_byte_offset(&machine), 52);
    assert_eq!(committed_sequence(&machine), 1);
    assert_eq!(machine.gpu0_authoritative_payload_bytes(), 8);
}

fn submit(machine: &mut ComputerMachine, address: u32, packet: &[u8]) {
    machine
        .write_guest_ram_bytes(address, packet)
        .expect("packet fits guest RAM");
    ring(machine, address, packet.len() as u32);
}

fn ring(machine: &mut ComputerMachine, address: u32, length: u32) {
    machine
        .bus_store_i32(
            ComputerMachine::GPU0_SUBMISSION_ADDRESS,
            i32::from_le_bytes(address.to_le_bytes()),
        )
        .unwrap();
    machine
        .bus_store_i32(
            ComputerMachine::GPU0_SUBMISSION_LENGTH,
            i32::from_le_bytes(length.to_le_bytes()),
        )
        .unwrap();
    machine
        .bus_store_i32(ComputerMachine::GPU0_SUBMIT, 1)
        .unwrap();
}

fn result_code(machine: &ComputerMachine) -> ResultCode {
    match machine
        .bus_load_i32(ComputerMachine::GPU0_RESULT_CODE)
        .unwrap() as u32
    {
        0 => ResultCode::Ok,
        1 => ResultCode::UnsupportedVersion,
        2 => ResultCode::MalformedPacket,
        3 => ResultCode::StaleBase,
        4 => ResultCode::InvalidArgument,
        5 => ResultCode::InvalidResource,
        6 => ResultCode::ResourceInUse,
        7 => ResultCode::OutOfBounds,
        8 => ResultCode::QuotaExceeded,
        9 => ResultCode::InvalidDrawList,
        value => panic!("unknown gpu0 result code {value}"),
    }
}

fn error_operation_index(machine: &ComputerMachine) -> u32 {
    machine
        .bus_load_i32(ComputerMachine::GPU0_ERROR_OPERATION_INDEX)
        .unwrap() as u32
}

fn error_byte_offset(machine: &ComputerMachine) -> u32 {
    machine
        .bus_load_i32(ComputerMachine::GPU0_ERROR_BYTE_OFFSET)
        .unwrap() as u32
}

fn committed_sequence(machine: &ComputerMachine) -> u64 {
    let low = machine
        .bus_load_i32(ComputerMachine::GPU0_COMMITTED_SEQUENCE_LOW)
        .unwrap() as u32;
    let high = machine
        .bus_load_i32(ComputerMachine::GPU0_COMMITTED_SEQUENCE_HIGH)
        .unwrap() as u32;
    u64::from(low) | (u64::from(high) << 32)
}

fn packet(base: u64, operations: &[Vec<u8>]) -> Vec<u8> {
    let mut bytes = Vec::new();
    bytes.extend_from_slice(b"KGPU");
    bytes.extend_from_slice(&1u16.to_le_bytes());
    bytes.extend_from_slice(&0u16.to_le_bytes());
    bytes.extend_from_slice(&0u32.to_le_bytes());
    bytes.extend_from_slice(&(operations.len() as u32).to_le_bytes());
    bytes.extend_from_slice(&base.to_le_bytes());
    for operation in operations {
        bytes.extend_from_slice(operation);
        bytes.resize(bytes.len().next_multiple_of(4), 0);
    }
    let length = bytes.len() as u32;
    bytes[8..12].copy_from_slice(&length.to_le_bytes());
    bytes
}

fn operation(opcode: u16, body: &[u8]) -> Vec<u8> {
    let mut bytes = Vec::new();
    bytes.extend_from_slice(&opcode.to_le_bytes());
    bytes.extend_from_slice(&0u16.to_le_bytes());
    bytes.extend_from_slice(&((8 + body.len()) as u32).to_le_bytes());
    bytes.extend_from_slice(body);
    bytes
}

fn replace_draw_list(background: u16) -> Vec<u8> {
    let mut body = Vec::new();
    body.extend_from_slice(&background.to_le_bytes());
    body.extend_from_slice(&0u16.to_le_bytes());
    body.extend_from_slice(&0u32.to_le_bytes());
    operation(0x0030, &body)
}

fn create_image(resource_id: u32, pixel: u16) -> Vec<u8> {
    let mut body = Vec::new();
    body.extend_from_slice(&resource_id.to_le_bytes());
    body.extend_from_slice(&1u16.to_le_bytes());
    body.extend_from_slice(&1u16.to_le_bytes());
    body.extend_from_slice(&pixel.to_le_bytes());
    operation(0x0001, &body)
}

fn drop_resource(resource_id: u32) -> Vec<u8> {
    operation(0x0020, &resource_id.to_le_bytes())
}

fn read_u16(bytes: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes(bytes[offset..offset + 2].try_into().unwrap())
}

fn read_u32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
}

fn read_u64(bytes: &[u8], offset: usize) -> u64 {
    u64::from_le_bytes(bytes[offset..offset + 8].try_into().unwrap())
}
