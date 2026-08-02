use super::{DrawCommand, MaskInstance, Resource, RetainedGpu};

const NETWORK_MAGIC: u32 = 0x5053_444b;
const NETWORK_VERSION: u16 = 1;
const SNAPSHOT_KIND: u16 = 1;

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
