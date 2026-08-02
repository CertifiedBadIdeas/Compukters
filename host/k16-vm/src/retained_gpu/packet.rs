pub const MAX_PACKET_BYTES: usize = 524_288;
pub const MAX_TRANSACTION_OPERATIONS: usize = 2_048;

const HEADER_BYTES: usize = 24;
const PACKET_MAGIC: u32 = 0x5550_474b;
const PACKET_VERSION: u16 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
pub enum ResultCode {
    Ok = 0,
    UnsupportedVersion = 1,
    MalformedPacket = 2,
    StaleBase = 3,
    InvalidArgument = 4,
    InvalidResource = 5,
    ResourceInUse = 6,
    OutOfBounds = 7,
    QuotaExceeded = 8,
    InvalidDrawList = 9,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct PacketRejection {
    pub code: ResultCode,
    pub operation_index: u32,
    pub byte_offset: u32,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum PacketDecodeError {
    Rejected(PacketRejection),
    Allocation,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct DecodedPacket<'a> {
    pub expected_base_sequence: u64,
    pub operations: Vec<DecodedOperation<'a>>,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct DecodedOperation<'a> {
    pub byte_offset: u32,
    pub byte_length: u32,
    pub kind: DecodedOperationKind<'a>,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum DecodedOperationKind<'a> {
    CreateImageRgb565 {
        resource_id: u32,
        width: u16,
        height: u16,
        pixels: &'a [u8],
    },
    CreateMask1Bpp {
        resource_id: u32,
        width: u16,
        height: u16,
        rows: &'a [u8],
    },
    CreateMaskInstanceBuffer {
        resource_id: u32,
        capacity: u16,
        records: &'a [u8],
    },
    PatchImageRect {
        resource_id: u32,
        x: u16,
        y: u16,
        width: u16,
        height: u16,
        pixels: &'a [u8],
    },
    PatchMaskRect {
        resource_id: u32,
        x: u16,
        y: u16,
        width: u16,
        height: u16,
        rows: &'a [u8],
    },
    PatchMaskInstances {
        resource_id: u32,
        start_index: u16,
        count: u16,
        records: &'a [u8],
    },
    DropResource {
        resource_id: u32,
    },
    ReplaceDrawList {
        draw_list: DecodedDrawList,
    },
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct DecodedDrawList {
    pub background_rgb565: u16,
    pub encoded_byte_len: usize,
    pub commands: Vec<DecodedDrawCommand>,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct DecodedDrawCommand {
    pub byte_offset: u32,
    pub byte_length: u32,
    pub kind: DecodedDrawCommandKind,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum DecodedDrawCommandKind {
    PushClip {
        x: i16,
        y: i16,
        width: u16,
        height: u16,
    },
    PopClip,
    FillRect {
        x: i16,
        y: i16,
        width: u16,
        height: u16,
        rgb565: u16,
    },
    DrawImage {
        resource_id: u32,
        source_x: u16,
        source_y: u16,
        source_width: u16,
        source_height: u16,
        destination_x: i16,
        destination_y: i16,
        destination_width: u16,
        destination_height: u16,
    },
    DrawMask {
        resource_id: u32,
        source_x: u16,
        source_y: u16,
        source_width: u16,
        source_height: u16,
        destination_x: i16,
        destination_y: i16,
        destination_width: u16,
        destination_height: u16,
        foreground_rgb565: u16,
        background_rgb565: u16,
        opaque_background: bool,
    },
    DrawMaskInstances {
        mask_resource_id: u32,
        instance_buffer_resource_id: u32,
        first_instance: u16,
        instance_count: u16,
        translation_x: i16,
        translation_y: i16,
    },
}

pub(crate) fn decode_packet(packet: &[u8]) -> Result<DecodedPacket<'_>, PacketDecodeError> {
    if !(HEADER_BYTES..=MAX_PACKET_BYTES).contains(&packet.len()) {
        return Err(rejected(ResultCode::InvalidArgument, u32::MAX, u32::MAX));
    }
    if read_u32(packet, 0) != PACKET_MAGIC {
        return Err(rejected(ResultCode::MalformedPacket, u32::MAX, 0));
    }
    if read_u16(packet, 4) != PACKET_VERSION {
        return Err(rejected(ResultCode::UnsupportedVersion, u32::MAX, 4));
    }
    if read_u16(packet, 6) != 0 {
        return Err(rejected(ResultCode::MalformedPacket, u32::MAX, 6));
    }
    if usize::try_from(read_u32(packet, 8)).ok() != Some(packet.len()) {
        return Err(rejected(ResultCode::MalformedPacket, u32::MAX, 8));
    }
    let operation_count = read_u32(packet, 12) as usize;
    if !(1..=MAX_TRANSACTION_OPERATIONS).contains(&operation_count) {
        return Err(rejected(ResultCode::InvalidArgument, u32::MAX, 12));
    }

    let mut operations = Vec::new();
    operations
        .try_reserve_exact(operation_count)
        .map_err(|_| PacketDecodeError::Allocation)?;
    let mut position = HEADER_BYTES;
    for operation_index in 0..operation_count {
        let operation = decode_operation(packet, operation_index as u32, position)?;
        let unaligned_end = position + operation.byte_length as usize;
        let aligned_end = align4(unaligned_end).ok_or_else(|| {
            rejected(
                ResultCode::MalformedPacket,
                operation_index as u32,
                (position + 4) as u32,
            )
        })?;
        if aligned_end > packet.len() {
            return Err(rejected(
                ResultCode::MalformedPacket,
                operation_index as u32,
                unaligned_end as u32,
            ));
        }
        require_zero_padding(packet, unaligned_end, aligned_end, operation_index as u32)?;
        if matches!(operation.kind, DecodedOperationKind::ReplaceDrawList { .. })
            && operation_index + 1 != operation_count
        {
            return Err(rejected(
                ResultCode::InvalidArgument,
                operation_index as u32,
                position as u32,
            ));
        }
        operations.push(operation);
        position = aligned_end;
    }
    if position != packet.len() {
        return Err(rejected(
            ResultCode::MalformedPacket,
            u32::MAX,
            position as u32,
        ));
    }

    Ok(DecodedPacket {
        expected_base_sequence: read_u64(packet, 16),
        operations,
    })
}

fn decode_operation<'a>(
    packet: &'a [u8],
    operation_index: u32,
    offset: usize,
) -> Result<DecodedOperation<'a>, PacketDecodeError> {
    require_available(packet, offset, 8, operation_index)?;
    let opcode = read_u16(packet, offset);
    if !matches!(
        opcode,
        0x0001 | 0x0002 | 0x0003 | 0x0010 | 0x0011 | 0x0012 | 0x0020 | 0x0030
    ) {
        return Err(rejected(
            ResultCode::InvalidArgument,
            operation_index,
            offset as u32,
        ));
    }
    if read_u16(packet, offset + 2) != 0 {
        return Err(rejected(
            ResultCode::InvalidArgument,
            operation_index,
            (offset + 2) as u32,
        ));
    }
    let byte_length = read_u32(packet, offset + 4);
    let length = byte_length as usize;
    if length < 8 {
        return Err(rejected(
            ResultCode::MalformedPacket,
            operation_index,
            (offset + 4) as u32,
        ));
    }
    if offset
        .checked_add(length)
        .is_none_or(|end| end > packet.len())
    {
        return Err(malformed_length(operation_index, offset));
    }
    let body = offset + 8;
    let kind = match opcode {
        0x0001 => decode_create_image(packet, operation_index, offset, body, length)?,
        0x0002 => decode_create_mask(packet, operation_index, offset, body, length)?,
        0x0003 => decode_create_instances(packet, operation_index, offset, body, length)?,
        0x0010 => decode_patch_image(packet, operation_index, offset, body, length)?,
        0x0011 => decode_patch_mask(packet, operation_index, offset, body, length)?,
        0x0012 => decode_patch_instances(packet, operation_index, offset, body, length)?,
        0x0020 => {
            require_exact_length(operation_index, offset, length, 12)?;
            DecodedOperationKind::DropResource {
                resource_id: read_u32(packet, body),
            }
        }
        0x0030 => DecodedOperationKind::ReplaceDrawList {
            draw_list: decode_draw_list(packet, operation_index, offset, body, length)?,
        },
        _ => unreachable!("opcode checked above"),
    };
    Ok(DecodedOperation {
        byte_offset: offset as u32,
        byte_length,
        kind,
    })
}

fn decode_create_image<'a>(
    packet: &'a [u8],
    operation_index: u32,
    operation_offset: usize,
    body: usize,
    length: usize,
) -> Result<DecodedOperationKind<'a>, PacketDecodeError> {
    require_min_length(operation_index, operation_offset, length, 16)?;
    let width = read_u16(packet, body + 4);
    let height = read_u16(packet, body + 6);
    let payload_bytes = usize::from(width)
        .checked_mul(usize::from(height))
        .and_then(|pixels| pixels.checked_mul(2))
        .ok_or_else(|| malformed_length(operation_index, operation_offset))?;
    require_exact_length(
        operation_index,
        operation_offset,
        length,
        16 + payload_bytes,
    )?;
    Ok(DecodedOperationKind::CreateImageRgb565 {
        resource_id: read_u32(packet, body),
        width,
        height,
        pixels: &packet[body + 8..body + 8 + payload_bytes],
    })
}

fn decode_create_mask<'a>(
    packet: &'a [u8],
    operation_index: u32,
    operation_offset: usize,
    body: usize,
    length: usize,
) -> Result<DecodedOperationKind<'a>, PacketDecodeError> {
    require_min_length(operation_index, operation_offset, length, 16)?;
    let width = read_u16(packet, body + 4);
    let height = read_u16(packet, body + 6);
    let row_bytes = usize::from(width).div_ceil(8);
    let payload_bytes = row_bytes
        .checked_mul(usize::from(height))
        .ok_or_else(|| malformed_length(operation_index, operation_offset))?;
    require_exact_length(
        operation_index,
        operation_offset,
        length,
        16 + payload_bytes,
    )?;
    let rows = &packet[body + 8..body + 8 + payload_bytes];
    validate_mask_padding(operation_index, body + 8, width, height, rows)?;
    Ok(DecodedOperationKind::CreateMask1Bpp {
        resource_id: read_u32(packet, body),
        width,
        height,
        rows,
    })
}

fn decode_create_instances<'a>(
    packet: &'a [u8],
    operation_index: u32,
    operation_offset: usize,
    body: usize,
    length: usize,
) -> Result<DecodedOperationKind<'a>, PacketDecodeError> {
    require_min_length(operation_index, operation_offset, length, 16)?;
    if read_u16(packet, body + 6) != 0 {
        return Err(rejected(
            ResultCode::MalformedPacket,
            operation_index,
            (body + 6) as u32,
        ));
    }
    let capacity = read_u16(packet, body + 4);
    let payload_bytes = usize::from(capacity)
        .checked_mul(24)
        .ok_or_else(|| malformed_length(operation_index, operation_offset))?;
    require_exact_length(
        operation_index,
        operation_offset,
        length,
        16 + payload_bytes,
    )?;
    let records = &packet[body + 8..body + 8 + payload_bytes];
    validate_instance_records(operation_index, body + 8, records)?;
    Ok(DecodedOperationKind::CreateMaskInstanceBuffer {
        resource_id: read_u32(packet, body),
        capacity,
        records,
    })
}

fn decode_patch_image<'a>(
    packet: &'a [u8],
    operation_index: u32,
    operation_offset: usize,
    body: usize,
    length: usize,
) -> Result<DecodedOperationKind<'a>, PacketDecodeError> {
    require_min_length(operation_index, operation_offset, length, 20)?;
    let width = read_u16(packet, body + 8);
    let height = read_u16(packet, body + 10);
    let payload_bytes = usize::from(width)
        .checked_mul(usize::from(height))
        .and_then(|pixels| pixels.checked_mul(2))
        .ok_or_else(|| malformed_length(operation_index, operation_offset))?;
    require_exact_length(
        operation_index,
        operation_offset,
        length,
        20 + payload_bytes,
    )?;
    Ok(DecodedOperationKind::PatchImageRect {
        resource_id: read_u32(packet, body),
        x: read_u16(packet, body + 4),
        y: read_u16(packet, body + 6),
        width,
        height,
        pixels: &packet[body + 12..body + 12 + payload_bytes],
    })
}

fn decode_patch_mask<'a>(
    packet: &'a [u8],
    operation_index: u32,
    operation_offset: usize,
    body: usize,
    length: usize,
) -> Result<DecodedOperationKind<'a>, PacketDecodeError> {
    require_min_length(operation_index, operation_offset, length, 20)?;
    let width = read_u16(packet, body + 8);
    let height = read_u16(packet, body + 10);
    let row_bytes = usize::from(width).div_ceil(8);
    let payload_bytes = row_bytes
        .checked_mul(usize::from(height))
        .ok_or_else(|| malformed_length(operation_index, operation_offset))?;
    require_exact_length(
        operation_index,
        operation_offset,
        length,
        20 + payload_bytes,
    )?;
    let rows = &packet[body + 12..body + 12 + payload_bytes];
    validate_mask_padding(operation_index, body + 12, width, height, rows)?;
    Ok(DecodedOperationKind::PatchMaskRect {
        resource_id: read_u32(packet, body),
        x: read_u16(packet, body + 4),
        y: read_u16(packet, body + 6),
        width,
        height,
        rows,
    })
}

fn decode_patch_instances<'a>(
    packet: &'a [u8],
    operation_index: u32,
    operation_offset: usize,
    body: usize,
    length: usize,
) -> Result<DecodedOperationKind<'a>, PacketDecodeError> {
    require_min_length(operation_index, operation_offset, length, 16)?;
    let count = read_u16(packet, body + 6);
    let payload_bytes = usize::from(count)
        .checked_mul(24)
        .ok_or_else(|| malformed_length(operation_index, operation_offset))?;
    require_exact_length(
        operation_index,
        operation_offset,
        length,
        16 + payload_bytes,
    )?;
    let records = &packet[body + 8..body + 8 + payload_bytes];
    validate_instance_records(operation_index, body + 8, records)?;
    Ok(DecodedOperationKind::PatchMaskInstances {
        resource_id: read_u32(packet, body),
        start_index: read_u16(packet, body + 4),
        count,
        records,
    })
}

fn decode_draw_list(
    packet: &[u8],
    operation_index: u32,
    operation_offset: usize,
    body: usize,
    length: usize,
) -> Result<DecodedDrawList, PacketDecodeError> {
    require_min_length(operation_index, operation_offset, length, 16)?;
    if read_u16(packet, body + 2) != 0 {
        return Err(rejected(
            ResultCode::MalformedPacket,
            operation_index,
            (body + 2) as u32,
        ));
    }
    if length - 8 > MAX_DRAW_LIST_BYTES {
        return Err(rejected(
            ResultCode::QuotaExceeded,
            operation_index,
            (operation_offset + 4) as u32,
        ));
    }
    let command_count = read_u32(packet, body + 4) as usize;
    if command_count > MAX_DRAW_COMMANDS {
        return Err(rejected(
            ResultCode::QuotaExceeded,
            operation_index,
            (body + 4) as u32,
        ));
    }
    let mut commands = Vec::new();
    commands
        .try_reserve_exact(command_count)
        .map_err(|_| PacketDecodeError::Allocation)?;
    let end = operation_offset + length;
    let mut position = body + 8;
    for _ in 0..command_count {
        let command = decode_draw_command(packet, operation_index, position, end)?;
        let unaligned_end = position + command.byte_length as usize;
        let aligned_end = align4(unaligned_end)
            .ok_or_else(|| malformed_length(operation_index, operation_offset))?;
        if aligned_end > end {
            return Err(rejected(
                ResultCode::MalformedPacket,
                operation_index,
                unaligned_end as u32,
            ));
        }
        require_zero_padding(packet, unaligned_end, aligned_end, operation_index)?;
        commands.push(command);
        position = aligned_end;
    }
    if position != end {
        return Err(rejected(
            ResultCode::MalformedPacket,
            operation_index,
            position as u32,
        ));
    }
    Ok(DecodedDrawList {
        background_rgb565: read_u16(packet, body),
        encoded_byte_len: length - 8,
        commands,
    })
}

fn decode_draw_command(
    packet: &[u8],
    operation_index: u32,
    offset: usize,
    draw_list_end: usize,
) -> Result<DecodedDrawCommand, PacketDecodeError> {
    if offset.checked_add(8).is_none_or(|end| end > draw_list_end) {
        return Err(rejected(
            ResultCode::MalformedPacket,
            operation_index,
            offset as u32,
        ));
    }
    let opcode = read_u16(packet, offset);
    if !matches!(opcode, 0x0001 | 0x0002 | 0x0010 | 0x0020 | 0x0021 | 0x0022) {
        return Err(rejected(
            ResultCode::InvalidArgument,
            operation_index,
            offset as u32,
        ));
    }
    let flags = read_u16(packet, offset + 2);
    let allowed_flags = if opcode == 0x0021 { 1 } else { 0 };
    if flags & !allowed_flags != 0 || (allowed_flags == 0 && flags != 0) {
        return Err(rejected(
            ResultCode::InvalidArgument,
            operation_index,
            (offset + 2) as u32,
        ));
    }
    let byte_length = read_u32(packet, offset + 4);
    let length = byte_length as usize;
    let expected_length = match opcode {
        0x0001 => 16,
        0x0002 => 8,
        0x0010 => 20,
        0x0020 => 28,
        0x0021 => 32,
        0x0022 => 24,
        _ => unreachable!(),
    };
    if length != expected_length
        || offset
            .checked_add(length)
            .is_none_or(|end| end > draw_list_end)
    {
        return Err(rejected(
            ResultCode::MalformedPacket,
            operation_index,
            (offset + 4) as u32,
        ));
    }
    let body = offset + 8;
    let kind = match opcode {
        0x0001 => DecodedDrawCommandKind::PushClip {
            x: read_i16(packet, body),
            y: read_i16(packet, body + 2),
            width: read_u16(packet, body + 4),
            height: read_u16(packet, body + 6),
        },
        0x0002 => DecodedDrawCommandKind::PopClip,
        0x0010 => {
            if read_u16(packet, body + 10) != 0 {
                return Err(rejected(
                    ResultCode::MalformedPacket,
                    operation_index,
                    (body + 10) as u32,
                ));
            }
            DecodedDrawCommandKind::FillRect {
                x: read_i16(packet, body),
                y: read_i16(packet, body + 2),
                width: read_u16(packet, body + 4),
                height: read_u16(packet, body + 6),
                rgb565: read_u16(packet, body + 8),
            }
        }
        0x0020 => DecodedDrawCommandKind::DrawImage {
            resource_id: read_u32(packet, body),
            source_x: read_u16(packet, body + 4),
            source_y: read_u16(packet, body + 6),
            source_width: read_u16(packet, body + 8),
            source_height: read_u16(packet, body + 10),
            destination_x: read_i16(packet, body + 12),
            destination_y: read_i16(packet, body + 14),
            destination_width: read_u16(packet, body + 16),
            destination_height: read_u16(packet, body + 18),
        },
        0x0021 => DecodedDrawCommandKind::DrawMask {
            resource_id: read_u32(packet, body),
            source_x: read_u16(packet, body + 4),
            source_y: read_u16(packet, body + 6),
            source_width: read_u16(packet, body + 8),
            source_height: read_u16(packet, body + 10),
            destination_x: read_i16(packet, body + 12),
            destination_y: read_i16(packet, body + 14),
            destination_width: read_u16(packet, body + 16),
            destination_height: read_u16(packet, body + 18),
            foreground_rgb565: read_u16(packet, body + 20),
            background_rgb565: read_u16(packet, body + 22),
            opaque_background: flags & 1 != 0,
        },
        0x0022 => DecodedDrawCommandKind::DrawMaskInstances {
            mask_resource_id: read_u32(packet, body),
            instance_buffer_resource_id: read_u32(packet, body + 4),
            first_instance: read_u16(packet, body + 8),
            instance_count: read_u16(packet, body + 10),
            translation_x: read_i16(packet, body + 12),
            translation_y: read_i16(packet, body + 14),
        },
        _ => unreachable!(),
    };
    Ok(DecodedDrawCommand {
        byte_offset: offset as u32,
        byte_length,
        kind,
    })
}

fn validate_mask_padding(
    operation_index: u32,
    payload_offset: usize,
    width: u16,
    height: u16,
    rows: &[u8],
) -> Result<(), PacketDecodeError> {
    let used_bits = width % 8;
    if used_bits == 0 {
        return Ok(());
    }
    let allowed_mask = u8::MAX << (8 - used_bits);
    let row_bytes = usize::from(width).div_ceil(8);
    for row in 0..usize::from(height) {
        let index = (row + 1) * row_bytes - 1;
        if rows[index] & !allowed_mask != 0 {
            return Err(rejected(
                ResultCode::InvalidArgument,
                operation_index,
                (payload_offset + index) as u32,
            ));
        }
    }
    Ok(())
}

fn validate_instance_records(
    operation_index: u32,
    payload_offset: usize,
    records: &[u8],
) -> Result<(), PacketDecodeError> {
    for (record_index, record) in records.chunks_exact(24).enumerate() {
        let offset = payload_offset + record_index * 24;
        for field_offset in [4usize, 6, 12, 14] {
            if read_u16(record, field_offset) == 0 {
                return Err(rejected(
                    ResultCode::InvalidArgument,
                    operation_index,
                    (offset + field_offset) as u32,
                ));
            }
        }
        let background = read_u16(record, 18);
        let flags = read_u16(record, 20);
        let reserved = read_u16(record, 22);
        if flags & !1 != 0 {
            return Err(rejected(
                ResultCode::InvalidArgument,
                operation_index,
                (offset + 20) as u32,
            ));
        }
        if flags & 1 == 0 && background != 0 {
            return Err(rejected(
                ResultCode::InvalidArgument,
                operation_index,
                (offset + 18) as u32,
            ));
        }
        if reserved != 0 {
            return Err(rejected(
                ResultCode::MalformedPacket,
                operation_index,
                (offset + 22) as u32,
            ));
        }
    }
    Ok(())
}

fn require_available(
    packet: &[u8],
    offset: usize,
    length: usize,
    operation_index: u32,
) -> Result<(), PacketDecodeError> {
    if offset
        .checked_add(length)
        .is_some_and(|end| end <= packet.len())
    {
        Ok(())
    } else {
        Err(rejected(
            ResultCode::MalformedPacket,
            operation_index,
            offset as u32,
        ))
    }
}

fn require_min_length(
    operation_index: u32,
    operation_offset: usize,
    actual: usize,
    minimum: usize,
) -> Result<(), PacketDecodeError> {
    if actual < minimum {
        Err(malformed_length(operation_index, operation_offset))
    } else {
        Ok(())
    }
}

fn require_exact_length(
    operation_index: u32,
    operation_offset: usize,
    actual: usize,
    expected: usize,
) -> Result<(), PacketDecodeError> {
    if actual != expected {
        Err(malformed_length(operation_index, operation_offset))
    } else {
        Ok(())
    }
}

fn malformed_length(operation_index: u32, operation_offset: usize) -> PacketDecodeError {
    rejected(
        ResultCode::MalformedPacket,
        operation_index,
        (operation_offset + 4) as u32,
    )
}

fn require_zero_padding(
    packet: &[u8],
    start: usize,
    end: usize,
    operation_index: u32,
) -> Result<(), PacketDecodeError> {
    if let Some(relative) = packet[start..end].iter().position(|byte| *byte != 0) {
        Err(rejected(
            ResultCode::MalformedPacket,
            operation_index,
            (start + relative) as u32,
        ))
    } else {
        Ok(())
    }
}

fn align4(value: usize) -> Option<usize> {
    value.checked_add(3).map(|value| value & !3)
}

fn rejected(code: ResultCode, operation_index: u32, byte_offset: u32) -> PacketDecodeError {
    PacketDecodeError::Rejected(PacketRejection {
        code,
        operation_index,
        byte_offset,
    })
}

fn read_u16(bytes: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes([bytes[offset], bytes[offset + 1]])
}

fn read_i16(bytes: &[u8], offset: usize) -> i16 {
    i16::from_le_bytes([bytes[offset], bytes[offset + 1]])
}

fn read_u32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}

fn read_u64(bytes: &[u8], offset: usize) -> u64 {
    u64::from_le_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
        bytes[offset + 4],
        bytes[offset + 5],
        bytes[offset + 6],
        bytes[offset + 7],
    ])
}

#[cfg(test)]
mod tests;
use super::{MAX_DRAW_COMMANDS, MAX_DRAW_LIST_BYTES};
