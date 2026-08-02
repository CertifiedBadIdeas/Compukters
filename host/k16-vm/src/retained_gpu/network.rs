use super::{
    CommittedDamage, DamageRange, DamageRect, DrawCommand, MaskInstance, Resource, ResourceDamage,
    ResourceManifest, RetainedGpu,
};

const NETWORK_MAGIC: u32 = 0x5053_444b;
const NETWORK_VERSION: u16 = 1;
const SNAPSHOT_KIND: u16 = 1;
const DELTA_KIND: u16 = 2;

pub const MAX_NETWORK_MESSAGE_BYTES: usize = 512 * 1024;

#[derive(Debug, thiserror::Error)]
pub enum NetworkEncodeError {
    #[error("retained display transport identities must be non-zero")]
    InvalidIdentity,
    #[error("retained display message length overflow")]
    LengthOverflow,
    #[error("retained display message exceeds the protocol limit")]
    MessageTooLarge,
    #[error("retained display message allocation failed")]
    Allocation,
    #[error("retained display publication state is inconsistent")]
    InconsistentState,
}

pub fn encode_snapshot(
    computer_id: u32,
    viewer_epoch: u64,
    gpu: &RetainedGpu,
) -> Result<Vec<u8>, NetworkEncodeError> {
    if computer_id == 0 || viewer_epoch == 0 {
        return Err(NetworkEncodeError::InvalidIdentity);
    }
    let resource_bytes = gpu.resources().iter().try_fold(0usize, |total, entry| {
        total
            .checked_add(16)
            .and_then(|value| value.checked_add(entry.value.payload_bytes()))
            .ok_or(NetworkEncodeError::LengthOverflow)
    })?;
    let expected_len = 40usize
        .checked_add(resource_bytes)
        .and_then(|value| value.checked_add(gpu.draw_list().encoded_byte_len()))
        .ok_or(NetworkEncodeError::LengthOverflow)?;
    if expected_len > MAX_NETWORK_MESSAGE_BYTES {
        return Err(NetworkEncodeError::MessageTooLarge);
    }

    let mut bytes = Vec::new();
    bytes
        .try_reserve_exact(expected_len)
        .map_err(|_| NetworkEncodeError::Allocation)?;
    push_header(
        &mut bytes,
        SNAPSHOT_KIND,
        expected_len,
        computer_id,
        viewer_epoch,
    )?;
    push_u64(&mut bytes, gpu.commit_sequence());
    push_u32(
        &mut bytes,
        u32::try_from(gpu.resources().len()).map_err(|_| NetworkEncodeError::LengthOverflow)?,
    );
    push_u32(
        &mut bytes,
        u32::try_from(gpu.draw_list().encoded_byte_len())
            .map_err(|_| NetworkEncodeError::LengthOverflow)?,
    );
    for entry in gpu.resources() {
        encode_resource(&mut bytes, entry.id, &entry.value)?;
    }
    encode_draw_list(&mut bytes, gpu.draw_list())?;
    debug_assert_eq!(bytes.len(), expected_len);
    Ok(bytes)
}

pub fn encode_delta(
    computer_id: u32,
    viewer_epoch: u64,
    base: &ResourceManifest,
    damage: &CommittedDamage,
    gpu: &RetainedGpu,
) -> Result<Vec<u8>, NetworkEncodeError> {
    if computer_id == 0 || viewer_epoch == 0 {
        return Err(NetworkEncodeError::InvalidIdentity);
    }
    if base.sequence() != damage.base_sequence()
        || damage.target_sequence() != gpu.commit_sequence()
        || damage.target_sequence() <= damage.base_sequence()
    {
        return Err(NetworkEncodeError::InconsistentState);
    }
    let include_draw_list =
        damage.draw_list_replaced() || draw_list_requires_rebind(base, gpu.draw_list().commands());
    let draw_list_len = if include_draw_list {
        gpu.draw_list().encoded_byte_len()
    } else {
        0
    };

    let mut bytes = Vec::new();
    bytes
        .try_reserve_exact(48)
        .map_err(|_| NetworkEncodeError::Allocation)?;
    push_header(&mut bytes, DELTA_KIND, 0, computer_id, viewer_epoch)?;
    push_u64(&mut bytes, damage.base_sequence());
    push_u64(&mut bytes, damage.target_sequence());
    push_u32(
        &mut bytes,
        u32::try_from(damage.changes().len()).map_err(|_| NetworkEncodeError::LengthOverflow)?,
    );
    push_u32(
        &mut bytes,
        u32::try_from(draw_list_len).map_err(|_| NetworkEncodeError::LengthOverflow)?,
    );
    for change in damage.changes() {
        encode_resource_change(&mut bytes, change, gpu)?;
    }
    if include_draw_list {
        encode_draw_list(&mut bytes, gpu.draw_list())?;
    }
    if bytes.len() > MAX_NETWORK_MESSAGE_BYTES {
        return Err(NetworkEncodeError::MessageTooLarge);
    }
    let total_len = u32::try_from(bytes.len()).map_err(|_| NetworkEncodeError::LengthOverflow)?;
    bytes[8..12].copy_from_slice(&total_len.to_le_bytes());
    Ok(bytes)
}

fn encode_resource_change(
    bytes: &mut Vec<u8>,
    change: &ResourceDamage,
    gpu: &RetainedGpu,
) -> Result<(), NetworkEncodeError> {
    match change {
        ResourceDamage::Created {
            resource_id,
            incarnation,
        } => {
            let entry = current_entry(gpu, *resource_id, *incarnation)?;
            encode_resource(bytes, *resource_id, &entry.value)
        }
        ResourceDamage::Dropped { resource_id, .. } => {
            push_envelope(bytes, 0x0020, 0, 12);
            push_u32(bytes, *resource_id);
            Ok(())
        }
        ResourceDamage::ImagePatches {
            resource_id,
            incarnation,
            rectangles,
        } => {
            let entry = current_entry(gpu, *resource_id, *incarnation)?;
            let Resource::ImageRgb565(image) = &entry.value else {
                return Err(NetworkEncodeError::InconsistentState);
            };
            let length_offset = begin_patch_group(bytes, 0x0010, *resource_id, rectangles.len())?;
            for rectangle in rectangles {
                push_rect(bytes, *rectangle);
                for row in 0..rectangle.height {
                    let start = usize::from(rectangle.y + row) * usize::from(image.width())
                        + usize::from(rectangle.x);
                    let end = start + usize::from(rectangle.width);
                    for pixel in &image.pixels()[start..end] {
                        push_u16(bytes, *pixel);
                    }
                }
                pad4(bytes);
            }
            finish_record_length(bytes, length_offset)
        }
        ResourceDamage::MaskPatches {
            resource_id,
            incarnation,
            rectangles,
        } => {
            let entry = current_entry(gpu, *resource_id, *incarnation)?;
            let Resource::Mask1Bpp(mask) = &entry.value else {
                return Err(NetworkEncodeError::InconsistentState);
            };
            let length_offset = begin_patch_group(bytes, 0x0011, *resource_id, rectangles.len())?;
            for rectangle in rectangles {
                push_rect(bytes, *rectangle);
                encode_mask_rectangle(bytes, mask, *rectangle);
                pad4(bytes);
            }
            finish_record_length(bytes, length_offset)
        }
        ResourceDamage::InstancePatches {
            resource_id,
            incarnation,
            ranges,
        } => {
            let entry = current_entry(gpu, *resource_id, *incarnation)?;
            let Resource::MaskInstanceBuffer(buffer) = &entry.value else {
                return Err(NetworkEncodeError::InconsistentState);
            };
            let length_offset = begin_patch_group(bytes, 0x0012, *resource_id, ranges.len())?;
            for range in ranges {
                encode_instance_range(bytes, buffer, *range)?;
            }
            finish_record_length(bytes, length_offset)
        }
    }
}

fn current_entry(
    gpu: &RetainedGpu,
    resource_id: u32,
    incarnation: u64,
) -> Result<&super::ResourceEntry, NetworkEncodeError> {
    gpu.resources()
        .binary_search_by_key(&resource_id, |entry| entry.id)
        .ok()
        .map(|index| &gpu.resources()[index])
        .filter(|entry| entry.incarnation == incarnation)
        .ok_or(NetworkEncodeError::InconsistentState)
}

fn begin_patch_group(
    bytes: &mut Vec<u8>,
    opcode: u16,
    resource_id: u32,
    patch_count: usize,
) -> Result<usize, NetworkEncodeError> {
    let start = bytes.len();
    push_envelope(bytes, opcode, 0, 0);
    push_u32(bytes, resource_id);
    push_u32(
        bytes,
        u32::try_from(patch_count).map_err(|_| NetworkEncodeError::LengthOverflow)?,
    );
    Ok(start + 4)
}

fn finish_record_length(bytes: &mut [u8], length_offset: usize) -> Result<(), NetworkEncodeError> {
    let start = length_offset - 4;
    let length =
        u32::try_from(bytes.len() - start).map_err(|_| NetworkEncodeError::LengthOverflow)?;
    bytes[length_offset..length_offset + 4].copy_from_slice(&length.to_le_bytes());
    Ok(())
}

fn push_rect(bytes: &mut Vec<u8>, rectangle: DamageRect) {
    push_u16(bytes, rectangle.x);
    push_u16(bytes, rectangle.y);
    push_u16(bytes, rectangle.width);
    push_u16(bytes, rectangle.height);
}

fn encode_mask_rectangle(bytes: &mut Vec<u8>, mask: &super::Mask1Bpp, rectangle: DamageRect) {
    let output_row_bytes = usize::from(rectangle.width).div_ceil(8);
    for row in 0..rectangle.height {
        let output_start = bytes.len();
        bytes.resize(output_start + output_row_bytes, 0);
        for column in 0..rectangle.width {
            let source_x = usize::from(rectangle.x + column);
            let source_y = usize::from(rectangle.y + row);
            let source_byte = mask.rows()[source_y * mask.row_bytes() + source_x / 8];
            if source_byte & (0x80 >> (source_x % 8)) != 0 {
                bytes[output_start + usize::from(column) / 8] |= 0x80 >> (usize::from(column) % 8);
            }
        }
    }
}

fn encode_instance_range(
    bytes: &mut Vec<u8>,
    buffer: &super::MaskInstanceBuffer,
    range: DamageRange,
) -> Result<(), NetworkEncodeError> {
    let start = usize::from(range.start_index);
    let end = start + usize::from(range.count);
    let instances = buffer
        .instances()
        .get(start..end)
        .ok_or(NetworkEncodeError::InconsistentState)?;
    push_u16(bytes, range.start_index);
    push_u16(bytes, range.count);
    for instance in instances {
        encode_instance(bytes, *instance);
    }
    Ok(())
}

fn draw_list_requires_rebind(base: &ResourceManifest, commands: &[DrawCommand]) -> bool {
    commands.iter().any(|command| {
        command_resource_refs(command)
            .iter()
            .flatten()
            .any(|reference| {
                base.entry(reference.id)
                    .is_none_or(|entry| entry.incarnation != reference.incarnation)
            })
    })
}

fn command_resource_refs(command: &DrawCommand) -> [Option<super::ResourceRef>; 2] {
    match command {
        DrawCommand::DrawImage { image, .. } => [Some(*image), None],
        DrawCommand::DrawMask { mask, .. } => [Some(*mask), None],
        DrawCommand::DrawMaskInstances {
            mask, instances, ..
        } => [Some(*mask), Some(*instances)],
        _ => [None, None],
    }
}

fn pad4(bytes: &mut Vec<u8>) {
    bytes.resize(bytes.len().next_multiple_of(4), 0);
}

fn push_header(
    bytes: &mut Vec<u8>,
    kind: u16,
    total_len: usize,
    computer_id: u32,
    viewer_epoch: u64,
) -> Result<(), NetworkEncodeError> {
    push_u32(bytes, NETWORK_MAGIC);
    push_u16(bytes, NETWORK_VERSION);
    push_u16(bytes, kind);
    push_u32(
        bytes,
        u32::try_from(total_len).map_err(|_| NetworkEncodeError::LengthOverflow)?,
    );
    push_u32(bytes, computer_id);
    push_u64(bytes, viewer_epoch);
    Ok(())
}

fn encode_resource(
    bytes: &mut Vec<u8>,
    resource_id: u32,
    resource: &Resource,
) -> Result<(), NetworkEncodeError> {
    let kind = match resource {
        Resource::ImageRgb565(_) => 1,
        Resource::Mask1Bpp(_) => 2,
        Resource::MaskInstanceBuffer(_) => 3,
    };
    let byte_length = 16usize
        .checked_add(resource.payload_bytes())
        .ok_or(NetworkEncodeError::LengthOverflow)?;
    push_u16(bytes, kind);
    push_u16(bytes, 0);
    push_u32(
        bytes,
        u32::try_from(byte_length).map_err(|_| NetworkEncodeError::LengthOverflow)?,
    );
    push_u32(bytes, resource_id);
    match resource {
        Resource::ImageRgb565(image) => {
            push_u16(bytes, image.width());
            push_u16(bytes, image.height());
            for pixel in image.pixels() {
                push_u16(bytes, *pixel);
            }
        }
        Resource::Mask1Bpp(mask) => {
            push_u16(bytes, mask.width());
            push_u16(bytes, mask.height());
            bytes.extend_from_slice(mask.rows());
        }
        Resource::MaskInstanceBuffer(buffer) => {
            push_u16(bytes, buffer.capacity());
            push_u16(bytes, 0);
            for instance in buffer.instances() {
                encode_instance(bytes, *instance);
            }
        }
    }
    Ok(())
}

fn encode_instance(bytes: &mut Vec<u8>, instance: MaskInstance) {
    let record = instance.record();
    push_u16(bytes, record.source_x);
    push_u16(bytes, record.source_y);
    push_u16(bytes, record.source_width);
    push_u16(bytes, record.source_height);
    push_i16(bytes, record.destination_x);
    push_i16(bytes, record.destination_y);
    push_u16(bytes, record.destination_width);
    push_u16(bytes, record.destination_height);
    push_u16(bytes, record.foreground_rgb565);
    push_u16(bytes, record.background_rgb565);
    push_u16(bytes, record.flags);
    push_u16(bytes, 0);
}

fn encode_draw_list(
    bytes: &mut Vec<u8>,
    draw_list: &super::DrawList,
) -> Result<(), NetworkEncodeError> {
    push_u16(bytes, draw_list.background_rgb565());
    push_u16(bytes, 0);
    push_u32(
        bytes,
        u32::try_from(draw_list.commands().len())
            .map_err(|_| NetworkEncodeError::LengthOverflow)?,
    );
    for command in draw_list.commands() {
        encode_draw_command(bytes, command);
    }
    Ok(())
}

fn encode_draw_command(bytes: &mut Vec<u8>, command: &DrawCommand) {
    match command {
        DrawCommand::PushClip {
            x,
            y,
            width,
            height,
        } => {
            push_envelope(bytes, 0x0001, 0, 16);
            push_i16(bytes, *x);
            push_i16(bytes, *y);
            push_u16(bytes, *width);
            push_u16(bytes, *height);
        }
        DrawCommand::PopClip => push_envelope(bytes, 0x0002, 0, 8),
        DrawCommand::FillRect {
            x,
            y,
            width,
            height,
            rgb565,
        } => {
            push_envelope(bytes, 0x0010, 0, 20);
            push_i16(bytes, *x);
            push_i16(bytes, *y);
            push_u16(bytes, *width);
            push_u16(bytes, *height);
            push_u16(bytes, *rgb565);
            push_u16(bytes, 0);
        }
        DrawCommand::DrawImage {
            image,
            source,
            destination,
        } => {
            push_envelope(bytes, 0x0020, 0, 28);
            push_u32(bytes, image.id);
            push_source(bytes, *source);
            push_destination(bytes, *destination);
        }
        DrawCommand::DrawMask {
            mask,
            source,
            destination,
            foreground_rgb565,
            background_rgb565,
            opaque_background,
        } => {
            push_envelope(bytes, 0x0021, u16::from(*opaque_background), 32);
            push_u32(bytes, mask.id);
            push_source(bytes, *source);
            push_destination(bytes, *destination);
            push_u16(bytes, *foreground_rgb565);
            push_u16(bytes, *background_rgb565);
        }
        DrawCommand::DrawMaskInstances {
            mask,
            instances,
            first_instance,
            instance_count,
            translation_x,
            translation_y,
        } => {
            push_envelope(bytes, 0x0022, 0, 24);
            push_u32(bytes, mask.id);
            push_u32(bytes, instances.id);
            push_u16(bytes, *first_instance);
            push_u16(bytes, *instance_count);
            push_i16(bytes, *translation_x);
            push_i16(bytes, *translation_y);
        }
    }
}

fn push_source(bytes: &mut Vec<u8>, source: super::SourceRect) {
    push_u16(bytes, source.x);
    push_u16(bytes, source.y);
    push_u16(bytes, source.width);
    push_u16(bytes, source.height);
}

fn push_destination(bytes: &mut Vec<u8>, destination: super::DestinationRect) {
    push_i16(bytes, destination.x);
    push_i16(bytes, destination.y);
    push_u16(bytes, destination.width);
    push_u16(bytes, destination.height);
}

fn push_envelope(bytes: &mut Vec<u8>, opcode: u16, flags: u16, byte_length: u32) {
    push_u16(bytes, opcode);
    push_u16(bytes, flags);
    push_u32(bytes, byte_length);
}

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
