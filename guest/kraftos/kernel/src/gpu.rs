use k16_abi::computer::gpu0;

use crate::mmio;

const HEADER_BYTES: usize = 24;
const MAX_PACKET_BYTES: usize = 524_288;
const MAX_OPERATIONS: u32 = 2_048;
const INSTANCE_BYTES: usize = 24;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BuildError {
    BufferTooSmall,
    LengthOverflow,
    TooManyOperations,
    InvalidArgument,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DeviceRejection {
    pub code: u32,
    pub operation_index: u32,
    pub byte_offset: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Commit {
    pub sequence: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SubmitError {
    IncompatibleDevice {
        device_abi_version: u32,
        packet_version: u32,
    },
    Rejected(DeviceRejection),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MaskInstance {
    pub source_x: u16,
    pub source_y: u16,
    pub source_width: u16,
    pub source_height: u16,
    pub destination_x: i16,
    pub destination_y: i16,
    pub destination_width: u16,
    pub destination_height: u16,
    pub foreground_rgb565: u16,
    pub background_rgb565: u16,
    pub flags: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DrawCommand {
    MaskInstances {
        mask_resource_id: u32,
        instance_buffer_resource_id: u32,
        first_instance: u16,
        instance_count: u16,
        translation_x: i16,
        translation_y: i16,
    },
}

pub struct TransactionBuilder<'a> {
    bytes: &'a mut [u8],
    cursor: usize,
    operation_count: u32,
}

impl<'a> TransactionBuilder<'a> {
    pub fn new(bytes: &'a mut [u8], expected_base_sequence: u64) -> Result<Self, BuildError> {
        if bytes.len() < HEADER_BYTES {
            return Err(BuildError::BufferTooSmall);
        }
        bytes[..HEADER_BYTES].fill(0);
        bytes[0..4].copy_from_slice(b"KGPU");
        put_u16(bytes, 4, 1);
        put_u64(bytes, 16, expected_base_sequence);
        Ok(Self {
            bytes,
            cursor: HEADER_BYTES,
            operation_count: 0,
        })
    }

    pub fn create_mask(
        &mut self,
        resource_id: u32,
        width: u16,
        height: u16,
        rows: &[u8],
    ) -> Result<(), BuildError> {
        let expected = usize::from(width)
            .div_ceil(8)
            .checked_mul(usize::from(height))
            .ok_or(BuildError::LengthOverflow)?;
        if resource_id == 0 || width == 0 || height == 0 || rows.len() != expected {
            return Err(BuildError::InvalidArgument);
        }
        let body_len = 8usize
            .checked_add(rows.len())
            .ok_or(BuildError::LengthOverflow)?;
        let body = self.reserve_operation(0x0002, body_len)?;
        put_u32(self.bytes, body, resource_id);
        put_u16(self.bytes, body + 4, width);
        put_u16(self.bytes, body + 6, height);
        self.bytes[body + 8..body + 8 + rows.len()].copy_from_slice(rows);
        Ok(())
    }

    pub fn create_mask_instance_buffer(
        &mut self,
        resource_id: u32,
        records: &[MaskInstance],
    ) -> Result<(), BuildError> {
        let capacity = u16::try_from(records.len()).map_err(|_| BuildError::InvalidArgument)?;
        if resource_id == 0 || capacity == 0 {
            return Err(BuildError::InvalidArgument);
        }
        let record_bytes = records
            .len()
            .checked_mul(INSTANCE_BYTES)
            .ok_or(BuildError::LengthOverflow)?;
        let body_len = 8usize
            .checked_add(record_bytes)
            .ok_or(BuildError::LengthOverflow)?;
        let body = self.reserve_operation(0x0003, body_len)?;
        put_u32(self.bytes, body, resource_id);
        put_u16(self.bytes, body + 4, capacity);
        for (index, record) in records.iter().enumerate() {
            encode_instance(self.bytes, body + 8 + index * INSTANCE_BYTES, *record);
        }
        Ok(())
    }

    pub fn patch_mask_instances(
        &mut self,
        resource_id: u32,
        start_index: u16,
        records: &[MaskInstance],
    ) -> Result<(), BuildError> {
        let count = u16::try_from(records.len()).map_err(|_| BuildError::InvalidArgument)?;
        if resource_id == 0 || count == 0 {
            return Err(BuildError::InvalidArgument);
        }
        let record_bytes = records
            .len()
            .checked_mul(INSTANCE_BYTES)
            .ok_or(BuildError::LengthOverflow)?;
        let body_len = 8usize
            .checked_add(record_bytes)
            .ok_or(BuildError::LengthOverflow)?;
        let body = self.reserve_operation(0x0012, body_len)?;
        put_u32(self.bytes, body, resource_id);
        put_u16(self.bytes, body + 4, start_index);
        put_u16(self.bytes, body + 6, count);
        for (index, record) in records.iter().enumerate() {
            encode_instance(self.bytes, body + 8 + index * INSTANCE_BYTES, *record);
        }
        Ok(())
    }

    pub fn drop_resource(&mut self, resource_id: u32) -> Result<(), BuildError> {
        if resource_id == 0 {
            return Err(BuildError::InvalidArgument);
        }
        let body = self.reserve_operation(0x0020, 4)?;
        put_u32(self.bytes, body, resource_id);
        Ok(())
    }

    pub fn replace_draw_list(
        &mut self,
        background_rgb565: u16,
        commands: &[DrawCommand],
    ) -> Result<(), BuildError> {
        let command_count =
            u32::try_from(commands.len()).map_err(|_| BuildError::InvalidArgument)?;
        if command_count > 2_048 {
            return Err(BuildError::InvalidArgument);
        }
        for command in commands {
            validate_draw_command(*command)?;
        }
        let command_bytes = commands
            .len()
            .checked_mul(24)
            .ok_or(BuildError::LengthOverflow)?;
        let body_len = 8usize
            .checked_add(command_bytes)
            .ok_or(BuildError::LengthOverflow)?;
        let body = self.reserve_operation(0x0030, body_len)?;
        put_u16(self.bytes, body, background_rgb565);
        put_u32(self.bytes, body + 4, command_count);
        for (index, command) in commands.iter().enumerate() {
            encode_draw_command(self.bytes, body + 8 + index * 24, *command)?;
        }
        Ok(())
    }

    pub fn finish(self) -> Result<&'a [u8], BuildError> {
        if self.operation_count == 0 {
            return Err(BuildError::InvalidArgument);
        }
        let total_len = u32::try_from(self.cursor).map_err(|_| BuildError::LengthOverflow)?;
        put_u32(self.bytes, 8, total_len);
        put_u32(self.bytes, 12, self.operation_count);
        Ok(&self.bytes[..self.cursor])
    }

    fn reserve_operation(&mut self, opcode: u16, body_len: usize) -> Result<usize, BuildError> {
        if self.operation_count == MAX_OPERATIONS {
            return Err(BuildError::TooManyOperations);
        }
        let operation_len = 8usize
            .checked_add(body_len)
            .ok_or(BuildError::LengthOverflow)?;
        let aligned_len = operation_len
            .checked_add(3)
            .ok_or(BuildError::LengthOverflow)?
            & !3;
        let end = self
            .cursor
            .checked_add(aligned_len)
            .ok_or(BuildError::LengthOverflow)?;
        if end > self.bytes.len() || end > MAX_PACKET_BYTES {
            return Err(BuildError::BufferTooSmall);
        }
        self.bytes[self.cursor..end].fill(0);
        put_u16(self.bytes, self.cursor, opcode);
        put_u32(self.bytes, self.cursor + 4, operation_len as u32);
        let body = self.cursor + 8;
        self.cursor = end;
        self.operation_count += 1;
        Ok(body)
    }
}

pub fn committed_sequence() -> u64 {
    unsafe {
        let low = mmio::read_i32(gpu0::COMMITTED_SEQUENCE_LOW) as u32;
        let high = mmio::read_i32(gpu0::COMMITTED_SEQUENCE_HIGH) as u32;
        u64::from(low) | (u64::from(high) << 32)
    }
}

pub fn submit(packet: &[u8]) -> Result<Commit, SubmitError> {
    unsafe {
        let device_abi_version = mmio::read_i32(gpu0::DEVICE_ABI_VERSION) as u32;
        let packet_version = mmio::read_i32(gpu0::PACKET_VERSION) as u32;
        if device_abi_version != gpu0::DEVICE_ABI_VERSION_VALUE as u32
            || packet_version != gpu0::PACKET_VERSION_VALUE as u32
        {
            return Err(SubmitError::IncompatibleDevice {
                device_abi_version,
                packet_version,
            });
        }
        mmio::write_i32(
            gpu0::SUBMISSION_ADDRESS,
            packet.as_ptr() as usize as u32 as i32,
        );
        mmio::write_i32(gpu0::SUBMISSION_LENGTH, packet.len() as u32 as i32);
        mmio::write_i32(gpu0::SUBMIT, 1);
        let code = mmio::read_i32(gpu0::RESULT_CODE) as u32;
        if code != 0 {
            return Err(SubmitError::Rejected(DeviceRejection {
                code,
                operation_index: mmio::read_i32(gpu0::ERROR_OPERATION_INDEX) as u32,
                byte_offset: mmio::read_i32(gpu0::ERROR_BYTE_OFFSET) as u32,
            }));
        }
    }
    Ok(Commit {
        sequence: committed_sequence(),
    })
}

fn encode_instance(bytes: &mut [u8], offset: usize, record: MaskInstance) {
    put_u16(bytes, offset, record.source_x);
    put_u16(bytes, offset + 2, record.source_y);
    put_u16(bytes, offset + 4, record.source_width);
    put_u16(bytes, offset + 6, record.source_height);
    put_i16(bytes, offset + 8, record.destination_x);
    put_i16(bytes, offset + 10, record.destination_y);
    put_u16(bytes, offset + 12, record.destination_width);
    put_u16(bytes, offset + 14, record.destination_height);
    put_u16(bytes, offset + 16, record.foreground_rgb565);
    put_u16(bytes, offset + 18, record.background_rgb565);
    put_u16(bytes, offset + 20, record.flags);
}

fn encode_draw_command(
    bytes: &mut [u8],
    offset: usize,
    command: DrawCommand,
) -> Result<(), BuildError> {
    validate_draw_command(command)?;
    match command {
        DrawCommand::MaskInstances {
            mask_resource_id,
            instance_buffer_resource_id,
            first_instance,
            instance_count,
            translation_x,
            translation_y,
        } => {
            put_u16(bytes, offset, 0x0022);
            put_u32(bytes, offset + 4, 24);
            put_u32(bytes, offset + 8, mask_resource_id);
            put_u32(bytes, offset + 12, instance_buffer_resource_id);
            put_u16(bytes, offset + 16, first_instance);
            put_u16(bytes, offset + 18, instance_count);
            put_i16(bytes, offset + 20, translation_x);
            put_i16(bytes, offset + 22, translation_y);
        }
    }
    Ok(())
}

fn validate_draw_command(command: DrawCommand) -> Result<(), BuildError> {
    match command {
        DrawCommand::MaskInstances {
            mask_resource_id,
            instance_buffer_resource_id,
            instance_count,
            ..
        } if mask_resource_id == 0 || instance_buffer_resource_id == 0 || instance_count == 0 => {
            Err(BuildError::InvalidArgument)
        }
        DrawCommand::MaskInstances { .. } => Ok(()),
    }
}

fn put_u16(bytes: &mut [u8], offset: usize, value: u16) {
    bytes[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
}

fn put_i16(bytes: &mut [u8], offset: usize, value: i16) {
    bytes[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
}

fn put_u32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

fn put_u64(bytes: &mut [u8], offset: usize, value: u64) {
    bytes[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}

#[cfg(test)]
mod retained_builder_tests {
    use super::{BuildError, DrawCommand, MaskInstance, TransactionBuilder};

    #[test]
    fn empty_draw_list_packet_is_byte_exact() {
        let mut bytes = [0xa5; 64];
        let mut packet = TransactionBuilder::new(&mut bytes, 7).unwrap();
        packet.replace_draw_list(0x1234, &[]).unwrap();
        let encoded = packet.finish().unwrap();

        assert_eq!(encoded.len(), 40);
        assert_eq!(&encoded[0..4], b"KGPU");
        assert_eq!(u16::from_le_bytes(encoded[4..6].try_into().unwrap()), 1);
        assert_eq!(u16::from_le_bytes(encoded[6..8].try_into().unwrap()), 0);
        assert_eq!(u32::from_le_bytes(encoded[8..12].try_into().unwrap()), 40);
        assert_eq!(u32::from_le_bytes(encoded[12..16].try_into().unwrap()), 1);
        assert_eq!(u64::from_le_bytes(encoded[16..24].try_into().unwrap()), 7);
        assert_eq!(
            u16::from_le_bytes(encoded[24..26].try_into().unwrap()),
            0x0030
        );
        assert_eq!(u16::from_le_bytes(encoded[26..28].try_into().unwrap()), 0);
        assert_eq!(u32::from_le_bytes(encoded[28..32].try_into().unwrap()), 16);
        assert_eq!(
            u16::from_le_bytes(encoded[32..34].try_into().unwrap()),
            0x1234
        );
        assert_eq!(&encoded[34..40], &[0; 6]);
    }

    #[test]
    fn instance_patch_encodes_signed_geometry_and_zero_padding() {
        let instance = MaskInstance {
            source_x: 8,
            source_y: 16,
            source_width: 6,
            source_height: 7,
            destination_x: -2,
            destination_y: 19,
            destination_width: 8,
            destination_height: 8,
            foreground_rgb565: 0xffff,
            background_rgb565: 0,
            flags: 1,
        };
        let mut bytes = [0xa5; 64];
        let mut packet = TransactionBuilder::new(&mut bytes, 11).unwrap();
        packet
            .patch_mask_instances(2, 17, core::slice::from_ref(&instance))
            .unwrap();
        let encoded = packet.finish().unwrap();

        assert_eq!(encoded.len(), 64);
        assert_eq!(u32::from_le_bytes(encoded[32..36].try_into().unwrap()), 2);
        assert_eq!(u16::from_le_bytes(encoded[36..38].try_into().unwrap()), 17);
        assert_eq!(u16::from_le_bytes(encoded[38..40].try_into().unwrap()), 1);
        assert_eq!(i16::from_le_bytes(encoded[48..50].try_into().unwrap()), -2);
        assert_eq!(i16::from_le_bytes(encoded[50..52].try_into().unwrap()), 19);
        assert_eq!(&encoded[62..64], &[0; 2]);
    }

    #[test]
    fn builder_reports_exhaustion_before_writing_past_the_buffer() {
        let mut bytes = [0xa5; 39];
        let mut packet = TransactionBuilder::new(&mut bytes, 0).unwrap();

        assert_eq!(
            packet.replace_draw_list(0, &[]),
            Err(BuildError::BufferTooSmall),
        );
        assert_eq!(&bytes[24..], &[0xa5; 15]);
    }

    #[test]
    fn invalid_draw_command_is_rejected_before_mutating_the_packet() {
        let mut bytes = [0xa5; 72];
        let command = DrawCommand::MaskInstances {
            mask_resource_id: 0,
            instance_buffer_resource_id: 2,
            first_instance: 0,
            instance_count: 1,
            translation_x: 0,
            translation_y: 0,
        };
        let mut packet = TransactionBuilder::new(&mut bytes, 0).unwrap();

        assert_eq!(
            packet.replace_draw_list(0, core::slice::from_ref(&command)),
            Err(BuildError::InvalidArgument),
        );
        assert_eq!(&bytes[24..], &[0xa5; 48]);
    }
}
