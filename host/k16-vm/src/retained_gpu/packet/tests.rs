use super::{
    decode_packet, DecodedDrawCommandKind, DecodedOperationKind, PacketDecodeError,
    PacketRejection, ResultCode, MAX_PACKET_BYTES, MAX_TRANSACTION_OPERATIONS,
};
use crate::retained_gpu::{MAX_DRAW_COMMANDS, MAX_DRAW_LIST_BYTES};

const MAGIC: u32 = 0x5550_474b;

fn push_u16(bytes: &mut Vec<u8>, value: u16) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn push_i16(bytes: &mut Vec<u8>, value: i16) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn push_u32(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn push_u64(bytes: &mut Vec<u8>, value: u64) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn record(opcode: u16, flags: u16, body: &[u8]) -> Vec<u8> {
    let mut bytes = Vec::new();
    push_u16(&mut bytes, opcode);
    push_u16(&mut bytes, flags);
    push_u32(&mut bytes, (8 + body.len()) as u32);
    bytes.extend_from_slice(body);
    bytes
}

fn packet(base: u64, operations: &[Vec<u8>]) -> Vec<u8> {
    let mut bytes = Vec::new();
    push_u32(&mut bytes, MAGIC);
    push_u16(&mut bytes, 1);
    push_u16(&mut bytes, 0);
    push_u32(&mut bytes, 0);
    push_u32(&mut bytes, operations.len() as u32);
    push_u64(&mut bytes, base);
    for operation in operations {
        bytes.extend_from_slice(operation);
        bytes.resize(bytes.len().next_multiple_of(4), 0);
    }
    let total = bytes.len() as u32;
    bytes[8..12].copy_from_slice(&total.to_le_bytes());
    bytes
}

fn image_body(resource_id: u32, width: u16, height: u16, pixels: &[u16]) -> Vec<u8> {
    let mut body = Vec::new();
    push_u32(&mut body, resource_id);
    push_u16(&mut body, width);
    push_u16(&mut body, height);
    for pixel in pixels {
        push_u16(&mut body, *pixel);
    }
    body
}

fn mask_body(resource_id: u32, width: u16, height: u16, rows: &[u8]) -> Vec<u8> {
    let mut body = Vec::new();
    push_u32(&mut body, resource_id);
    push_u16(&mut body, width);
    push_u16(&mut body, height);
    body.extend_from_slice(rows);
    body
}

fn instance_record() -> Vec<u8> {
    let mut bytes = Vec::new();
    for value in [0, 0, 8, 8] {
        push_u16(&mut bytes, value);
    }
    push_i16(&mut bytes, 0);
    push_i16(&mut bytes, 0);
    for value in [8, 8, 0xffff, 0, 1, 0] {
        push_u16(&mut bytes, value);
    }
    assert_eq!(bytes.len(), 24);
    bytes
}

fn create_instances_body(resource_id: u32, capacity: u16) -> Vec<u8> {
    let mut body = Vec::new();
    push_u32(&mut body, resource_id);
    push_u16(&mut body, capacity);
    push_u16(&mut body, 0);
    for _ in 0..capacity {
        body.extend_from_slice(&instance_record());
    }
    body
}

fn patch_rect_body(
    resource_id: u32,
    x: u16,
    y: u16,
    width: u16,
    height: u16,
    payload: &[u8],
) -> Vec<u8> {
    let mut body = Vec::new();
    push_u32(&mut body, resource_id);
    for value in [x, y, width, height] {
        push_u16(&mut body, value);
    }
    body.extend_from_slice(payload);
    body
}

fn patch_instances_body(resource_id: u32, start: u16, records: &[Vec<u8>]) -> Vec<u8> {
    let mut body = Vec::new();
    push_u32(&mut body, resource_id);
    push_u16(&mut body, start);
    push_u16(&mut body, records.len() as u16);
    for record in records {
        body.extend_from_slice(record);
    }
    body
}

fn draw_list_body(background: u16, commands: &[Vec<u8>]) -> Vec<u8> {
    let mut body = Vec::new();
    push_u16(&mut body, background);
    push_u16(&mut body, 0);
    push_u32(&mut body, commands.len() as u32);
    for command in commands {
        body.extend_from_slice(command);
        body.resize(body.len().next_multiple_of(4), 0);
    }
    body
}

fn into_rejection(error: PacketDecodeError) -> PacketRejection {
    match error {
        PacketDecodeError::Rejected(rejection) => rejection,
        PacketDecodeError::Allocation => panic!("unexpected allocation failure"),
    }
}

#[test]
fn header_is_exactly_24_bytes_and_preserves_the_expected_base() {
    let bytes = packet(
        0x1122_3344_5566_7788,
        &[record(0x0020, 0, &1u32.to_le_bytes())],
    );

    let decoded = decode_packet(&bytes).expect("valid packet");

    assert_eq!(decoded.expected_base_sequence, 0x1122_3344_5566_7788);
    assert_eq!(decoded.operations.len(), 1);
    assert_eq!(decoded.operations[0].byte_offset, 24);
    assert_eq!(decoded.operations[0].byte_length, 12);
    assert!(matches!(
        decoded.operations[0].kind,
        DecodedOperationKind::DropResource { resource_id: 1 }
    ));
}

#[test]
fn every_top_level_operation_uses_the_fixed_wire_length() {
    let image_patch_pixels = [0x34, 0x12];
    let operations = vec![
        record(0x0001, 0, &image_body(1, 1, 1, &[0x1234])),
        record(0x0002, 0, &mask_body(2, 1, 1, &[0x80])),
        record(0x0003, 0, &create_instances_body(3, 1)),
        record(
            0x0010,
            0,
            &patch_rect_body(1, 0, 0, 1, 1, &image_patch_pixels),
        ),
        record(0x0011, 0, &patch_rect_body(2, 0, 0, 1, 1, &[0x80])),
        record(0x0012, 0, &patch_instances_body(3, 0, &[instance_record()])),
        record(0x0020, 0, &1u32.to_le_bytes()),
        record(0x0030, 0, &draw_list_body(0, &[])),
    ];

    let bytes = packet(0, &operations);
    let decoded = decode_packet(&bytes).expect("valid packet");
    let lengths: Vec<u32> = decoded
        .operations
        .iter()
        .map(|operation| operation.byte_length)
        .collect();

    assert_eq!(lengths, [18, 17, 40, 22, 21, 40, 12, 16]);
    assert!(matches!(
        decoded.operations[0].kind,
        DecodedOperationKind::CreateImageRgb565 { pixels, .. } if pixels == [0x34, 0x12]
    ));
    assert!(matches!(
        decoded.operations[1].kind,
        DecodedOperationKind::CreateMask1Bpp { rows, .. } if rows == [0x80]
    ));
    assert!(matches!(
        decoded.operations[2].kind,
        DecodedOperationKind::CreateMaskInstanceBuffer { records, .. } if records.len() == 24
    ));
}

#[test]
fn every_nested_draw_command_uses_the_fixed_wire_length_and_flags() {
    let mut push_clip = Vec::new();
    for value in [-1i16, -2] {
        push_i16(&mut push_clip, value);
    }
    for value in [10, 11] {
        push_u16(&mut push_clip, value);
    }

    let mut fill = Vec::new();
    push_i16(&mut fill, 0);
    push_i16(&mut fill, 1);
    for value in [2, 3, 0xf800, 0] {
        push_u16(&mut fill, value);
    }

    let mut draw_image = Vec::new();
    push_u32(&mut draw_image, 1);
    for value in [0, 0, 8, 8] {
        push_u16(&mut draw_image, value);
    }
    push_i16(&mut draw_image, 0);
    push_i16(&mut draw_image, 0);
    for value in [8, 8] {
        push_u16(&mut draw_image, value);
    }

    let mut draw_mask = draw_image.clone();
    push_u16(&mut draw_mask, 0xffff);
    push_u16(&mut draw_mask, 0x001f);

    let mut draw_instances = Vec::new();
    push_u32(&mut draw_instances, 2);
    push_u32(&mut draw_instances, 3);
    push_u16(&mut draw_instances, 0);
    push_u16(&mut draw_instances, 1);
    push_i16(&mut draw_instances, -4);
    push_i16(&mut draw_instances, 5);

    let commands = vec![
        record(0x0001, 0, &push_clip),
        record(0x0002, 0, &[]),
        record(0x0010, 0, &fill),
        record(0x0020, 0, &draw_image),
        record(0x0021, 1, &draw_mask),
        record(0x0022, 0, &draw_instances),
    ];
    let bytes = packet(0, &[record(0x0030, 0, &draw_list_body(0, &commands))]);

    let decoded = decode_packet(&bytes).expect("valid draw list");
    let DecodedOperationKind::ReplaceDrawList { draw_list } = &decoded.operations[0].kind else {
        panic!("expected draw list");
    };
    let lengths: Vec<u32> = draw_list
        .commands
        .iter()
        .map(|command| command.byte_length)
        .collect();
    assert_eq!(lengths, [16, 8, 20, 28, 32, 24]);
    assert!(matches!(
        draw_list.commands[4].kind,
        DecodedDrawCommandKind::DrawMask {
            opaque_background: true,
            ..
        }
    ));
}

#[test]
fn header_failures_report_the_first_offending_field() {
    let valid = packet(0, &[record(0x0020, 0, &1u32.to_le_bytes())]);
    let cases = [
        (0usize, 0u32, ResultCode::MalformedPacket),
        (4, 2, ResultCode::UnsupportedVersion),
        (6, 1, ResultCode::MalformedPacket),
        (8, 0, ResultCode::MalformedPacket),
        (12, 0, ResultCode::InvalidArgument),
    ];

    for (offset, value, expected_code) in cases {
        let mut bytes = valid.clone();
        match offset {
            0 | 8 | 12 => bytes[offset..offset + 4].copy_from_slice(&u32::to_le_bytes(value)),
            4 | 6 => bytes[offset..offset + 2].copy_from_slice(&(value as u16).to_le_bytes()),
            _ => unreachable!(),
        }
        let rejection = into_rejection(decode_packet(&bytes).expect_err("invalid header"));
        assert_eq!(rejection.code, expected_code, "offset {offset}");
        assert_eq!(rejection.operation_index, u32::MAX, "offset {offset}");
        assert_eq!(rejection.byte_offset, offset as u32, "offset {offset}");
    }
}

#[test]
fn packet_and_operation_quotas_are_checked_before_record_parsing() {
    let short = vec![0; 23];
    let rejection = into_rejection(decode_packet(&short).expect_err("short packet"));
    assert_eq!(rejection.code, ResultCode::InvalidArgument);
    assert_eq!(rejection.byte_offset, u32::MAX);

    let oversized = vec![0; MAX_PACKET_BYTES + 1];
    let rejection = into_rejection(decode_packet(&oversized).expect_err("oversized packet"));
    assert_eq!(rejection.code, ResultCode::InvalidArgument);

    let mut too_many = packet(0, &[record(0x0020, 0, &1u32.to_le_bytes())]);
    too_many[12..16].copy_from_slice(&((MAX_TRANSACTION_OPERATIONS + 1) as u32).to_le_bytes());
    let rejection = into_rejection(decode_packet(&too_many).expect_err("too many operations"));
    assert_eq!(rejection.code, ResultCode::InvalidArgument);
    assert_eq!(rejection.byte_offset, 12);

    let mut draw_body = draw_list_body(0, &[]);
    draw_body[4..8].copy_from_slice(&((MAX_DRAW_COMMANDS + 1) as u32).to_le_bytes());
    let rejection = into_rejection(
        decode_packet(&packet(0, &[record(0x0030, 0, &draw_body)]))
            .expect_err("too many draw commands"),
    );
    assert_eq!(rejection.code, ResultCode::QuotaExceeded);
    assert_eq!(rejection.operation_index, 0);
    assert_eq!(rejection.byte_offset, 36);

    let mut oversized_draw_list = vec![0; MAX_DRAW_LIST_BYTES + 1];
    oversized_draw_list[4..8].copy_from_slice(&0u32.to_le_bytes());
    let rejection = into_rejection(
        decode_packet(&packet(0, &[record(0x0030, 0, &oversized_draw_list)]))
            .expect_err("oversized draw list"),
    );
    assert_eq!(rejection.code, ResultCode::QuotaExceeded);
    assert_eq!(rejection.operation_index, 0);
    assert_eq!(rejection.byte_offset, 28);
}

#[test]
fn malformed_envelopes_flags_padding_and_trailing_data_are_not_skipped() {
    let valid_body = 1u32.to_le_bytes();
    let cases = [
        (
            record(0xffff, 0, &valid_body),
            ResultCode::InvalidArgument,
            24,
        ),
        (
            record(0x0020, 1, &valid_body),
            ResultCode::InvalidArgument,
            26,
        ),
    ];
    for (operation, code, offset) in cases {
        let rejection =
            into_rejection(decode_packet(&packet(0, &[operation])).expect_err("bad record"));
        assert_eq!(rejection.code, code);
        assert_eq!(rejection.operation_index, 0);
        assert_eq!(rejection.byte_offset, offset);
    }

    let mut wrong_length = record(0x0020, 0, &valid_body);
    wrong_length[4..8].copy_from_slice(&11u32.to_le_bytes());
    let rejection = into_rejection(
        decode_packet(&packet(0, &[wrong_length])).expect_err("wrong operation length"),
    );
    assert_eq!(rejection.code, ResultCode::MalformedPacket);
    assert_eq!(rejection.byte_offset, 28);

    let mut truncated = packet(0, &[record(0x0020, 0, &valid_body)]);
    truncated[28..32].copy_from_slice(&16u32.to_le_bytes());
    let rejection = into_rejection(decode_packet(&truncated).expect_err("truncated operation"));
    assert_eq!(rejection.code, ResultCode::MalformedPacket);
    assert_eq!(rejection.byte_offset, 28);

    let mut padded = packet(0, &[record(0x0002, 0, &mask_body(1, 1, 1, &[0x80]))]);
    *padded.last_mut().expect("padding") = 1;
    let padding_offset = padded.len() as u32 - 1;
    let rejection = into_rejection(decode_packet(&padded).expect_err("non-zero padding"));
    assert_eq!(rejection.byte_offset, padding_offset);

    let mut trailing = packet(0, &[record(0x0020, 0, &valid_body)]);
    trailing.extend_from_slice(&[0, 0, 0, 0]);
    let total = trailing.len() as u32;
    trailing[8..12].copy_from_slice(&total.to_le_bytes());
    let rejection = into_rejection(decode_packet(&trailing).expect_err("trailing data"));
    assert_eq!(rejection.byte_offset, (trailing.len() - 4) as u32);
}

#[test]
fn reserved_fields_and_mask_unused_bits_report_exact_offsets() {
    let mut create_instances = create_instances_body(1, 1);
    create_instances[6..8].copy_from_slice(&1u16.to_le_bytes());
    let rejection = into_rejection(
        decode_packet(&packet(0, &[record(0x0003, 0, &create_instances)]))
            .expect_err("create reserved"),
    );
    assert_eq!(rejection.byte_offset, 38);

    let mut bad_instance = instance_record();
    bad_instance[22..24].copy_from_slice(&1u16.to_le_bytes());
    let rejection = into_rejection(
        decode_packet(&packet(
            0,
            &[record(
                0x0012,
                0,
                &patch_instances_body(1, 0, &[bad_instance]),
            )],
        ))
        .expect_err("instance reserved"),
    );
    assert_eq!(rejection.byte_offset, 62);

    let mut multiply_invalid_instance = instance_record();
    multiply_invalid_instance[18..20].copy_from_slice(&1u16.to_le_bytes());
    multiply_invalid_instance[20..22].copy_from_slice(&0u16.to_le_bytes());
    multiply_invalid_instance[22..24].copy_from_slice(&1u16.to_le_bytes());
    let rejection = into_rejection(
        decode_packet(&packet(
            0,
            &[record(
                0x0012,
                0,
                &patch_instances_body(1, 0, &[multiply_invalid_instance]),
            )],
        ))
        .expect_err("cutout background precedes reserved field"),
    );
    assert_eq!(rejection.code, ResultCode::InvalidArgument);
    assert_eq!(rejection.byte_offset, 58);

    let rejection = into_rejection(
        decode_packet(&packet(
            0,
            &[record(0x0002, 0, &mask_body(1, 10, 1, &[0xff, 0xc1]))],
        ))
        .expect_err("unused mask bits"),
    );
    assert_eq!(rejection.byte_offset, 41);
}
