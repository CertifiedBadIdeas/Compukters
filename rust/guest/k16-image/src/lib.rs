#![no_std]

pub const FIXED_K16E_V1_HEADER_SIZE: u32 = 52;
pub const FIXED_K16E_V1_PAYLOAD_OFFSET: u32 = 52;
pub const DYNAMIC_K16E_V2_HEADER_SIZE: u32 = 72;
pub const DYNAMIC_K16E_V2_PAYLOAD_OFFSET: u32 = 72;
pub const K16E_RELOCATION_RECORD_SIZE: u32 = 8;

const K16E_HEADER_SIZE: u16 = 32;
const K16E_ISA_K16: u16 = 1;
const K16E_SECTION_TABLE_OFFSET: u32 = 32;
const K16E_SECTION_KIND_LOAD: u32 = 1;
const K16E_SECTION_KIND_RELOCATIONS: u32 = 2;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum K16eAbiKind {
    Bootloader = 1,
    Kernel = 2,
    Program = 3,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum K16ImageError {
    InvalidExecutable,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct FixedK16ImageHeader {
    pub entry_pc: u32,
    pub load_addr: u32,
    pub file_size: u32,
    pub memory_size: u32,
    pub zero_fill_addr: u32,
    pub zero_fill_len: u32,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum K16eRelocationKind {
    Abs32,
    Call32,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct K16eRelocation {
    pub offset: u32,
    pub kind: K16eRelocationKind,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct DynamicK16ImageHeader<'a> {
    pub entry_offset: u32,
    pub payload_offset: u32,
    pub file_size: u32,
    pub memory_size: u32,
    pub relocation_table_offset: u32,
    pub relocation_count: u32,
    relocation_table: &'a [u8],
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct DynamicK16ImageMetadata {
    pub entry_offset: u32,
    pub payload_offset: u32,
    pub file_size: u32,
    pub memory_size: u32,
    pub relocation_table_offset: u32,
    pub relocation_count: u32,
}

impl DynamicK16ImageHeader<'_> {
    pub fn relocation(&self, index: u32) -> Option<K16eRelocation> {
        if index >= self.relocation_count {
            return None;
        }
        let offset = index.checked_mul(K16E_RELOCATION_RECORD_SIZE)?;
        let offset = usize::try_from(offset).ok()?;
        Some(K16eRelocation {
            offset: read_u32_le(self.relocation_table, offset).ok()?,
            kind: decode_relocation_kind(read_u32_le(self.relocation_table, offset + 4).ok()?)
                .ok()?,
        })
    }
}

pub fn parse_fixed_k16e_v1(
    header: &[u8],
    expected_abi_kind: K16eAbiKind,
    inode_size: u32,
) -> Result<FixedK16ImageHeader, K16ImageError> {
    if header.len() < FIXED_K16E_V1_HEADER_SIZE as usize
        || header_bytes(header, 0, b"K16E").is_err()
        || read_u16_le(header, 4)? != 1
        || read_u16_le(header, 6)? != 32
        || read_u16_le(header, 8)? != 1
        || read_u16_le(header, 10)? != 0
        || read_u32_le(header, 16)? != 32
        || read_u32_le(header, 20)? != 1
        || read_u32_le(header, 24)? != expected_abi_kind as u32
        || read_u32_le(header, 28)? != 0
        || read_u32_le(header, 32)? != 1
        || read_u32_le(header, 40)? != FIXED_K16E_V1_PAYLOAD_OFFSET
    {
        return Err(K16ImageError::InvalidExecutable);
    }

    let entry_pc = read_u32_le(header, 12)?;
    let load_addr = read_u32_le(header, 36)?;
    let file_size = read_u32_le(header, 44)?;
    let memory_size = read_u32_le(header, 48)?;
    let image = fixed_k16e_load_plan(entry_pc, load_addr, file_size, memory_size)?;
    let file_end = match FIXED_K16E_V1_PAYLOAD_OFFSET.checked_add(file_size) {
        Some(value) => value,
        None => return Err(K16ImageError::InvalidExecutable),
    };
    if file_end > inode_size {
        return Err(K16ImageError::InvalidExecutable);
    }
    Ok(image)
}

pub fn parse_dynamic_k16e_v2(image: &[u8]) -> Result<DynamicK16ImageHeader<'_>, K16ImageError> {
    let metadata = parse_dynamic_k16e_v2_header(image, image.len() as u32)?;
    let file_end = metadata
        .relocation_table_offset
        .checked_add(
            metadata
                .relocation_count
                .checked_mul(K16E_RELOCATION_RECORD_SIZE)
                .ok_or(K16ImageError::InvalidExecutable)?,
        )
        .ok_or(K16ImageError::InvalidExecutable)?;
    let relocation_table_start = match usize::try_from(metadata.relocation_table_offset) {
        Ok(value) => value,
        Err(_) => return Err(K16ImageError::InvalidExecutable),
    };
    let relocation_table_end = match usize::try_from(file_end) {
        Ok(value) => value,
        Err(_) => return Err(K16ImageError::InvalidExecutable),
    };
    let relocation_table = match image.get(relocation_table_start..relocation_table_end) {
        Some(value) => value,
        None => return Err(K16ImageError::InvalidExecutable),
    };
    validate_dynamic_relocations(
        metadata.memory_size,
        relocation_table,
        metadata.relocation_count,
    )?;

    Ok(DynamicK16ImageHeader {
        entry_offset: metadata.entry_offset,
        payload_offset: metadata.payload_offset,
        file_size: metadata.file_size,
        memory_size: metadata.memory_size,
        relocation_table_offset: metadata.relocation_table_offset,
        relocation_count: metadata.relocation_count,
        relocation_table,
    })
}

pub fn parse_dynamic_k16e_v2_header(
    header: &[u8],
    inode_size: u32,
) -> Result<DynamicK16ImageMetadata, K16ImageError> {
    if header.len() < DYNAMIC_K16E_V2_HEADER_SIZE as usize
        || header_bytes(header, 0, b"K16E").is_err()
        || read_u16_le(header, 4)? != 2
        || read_u16_le(header, 6)? != K16E_HEADER_SIZE
        || read_u16_le(header, 8)? != K16E_ISA_K16
        || read_u16_le(header, 10)? != 0
        || read_u32_le(header, 16)? != K16E_SECTION_TABLE_OFFSET
        || read_u32_le(header, 20)? != 2
        || read_u32_le(header, 24)? != K16eAbiKind::Program as u32
        || read_u32_le(header, 28)? != 0
        || read_u32_le(header, 32)? != K16E_SECTION_KIND_LOAD
        || read_u32_le(header, 36)? != 0
        || read_u32_le(header, 40)? != DYNAMIC_K16E_V2_PAYLOAD_OFFSET
        || read_u32_le(header, 52)? != K16E_SECTION_KIND_RELOCATIONS
        || read_u32_le(header, 56)? != 0
    {
        return Err(K16ImageError::InvalidExecutable);
    }

    let entry_offset = read_u32_le(header, 12)?;
    let file_size = read_u32_le(header, 44)?;
    let memory_size = read_u32_le(header, 48)?;
    if file_size == 0 || memory_size < file_size || file_size % 2 != 0 || memory_size % 2 != 0 {
        return Err(K16ImageError::InvalidExecutable);
    }
    if entry_offset >= memory_size || entry_offset % 2 != 0 {
        return Err(K16ImageError::InvalidExecutable);
    }

    let relocation_table_offset = read_u32_le(header, 60)?;
    let relocation_table_size = read_u32_le(header, 64)?;
    let relocation_count = read_u32_le(header, 68)?;
    let payload_end = match DYNAMIC_K16E_V2_PAYLOAD_OFFSET.checked_add(file_size) {
        Some(value) => value,
        None => return Err(K16ImageError::InvalidExecutable),
    };
    if relocation_table_offset != payload_end {
        return Err(K16ImageError::InvalidExecutable);
    }
    let expected_relocation_table_size =
        match relocation_count.checked_mul(K16E_RELOCATION_RECORD_SIZE) {
            Some(value) => value,
            None => return Err(K16ImageError::InvalidExecutable),
        };
    if relocation_table_size != expected_relocation_table_size {
        return Err(K16ImageError::InvalidExecutable);
    }
    let file_end = match relocation_table_offset.checked_add(relocation_table_size) {
        Some(value) => value,
        None => return Err(K16ImageError::InvalidExecutable),
    };
    if file_end > inode_size {
        return Err(K16ImageError::InvalidExecutable);
    }

    Ok(DynamicK16ImageMetadata {
        entry_offset,
        payload_offset: DYNAMIC_K16E_V2_PAYLOAD_OFFSET,
        file_size,
        memory_size,
        relocation_table_offset,
        relocation_count,
    })
}

pub fn parse_k16e_relocation_record(
    record: &[u8],
    memory_size: u32,
) -> Result<K16eRelocation, K16ImageError> {
    if record.len() < K16E_RELOCATION_RECORD_SIZE as usize {
        return Err(K16ImageError::InvalidExecutable);
    }
    let relocation = K16eRelocation {
        offset: read_u32_le(record, 0)?,
        kind: decode_relocation_kind(read_u32_le(record, 4)?)?,
    };
    validate_dynamic_relocation(memory_size, relocation)?;
    Ok(relocation)
}

fn fixed_k16e_load_plan(
    entry_pc: u32,
    load_addr: u32,
    file_size: u32,
    memory_size: u32,
) -> Result<FixedK16ImageHeader, K16ImageError> {
    if file_size == 0 || memory_size < file_size || file_size % 2 != 0 || memory_size % 2 != 0 {
        return Err(K16ImageError::InvalidExecutable);
    }
    let load_end = match load_addr.checked_add(memory_size) {
        Some(value) => value,
        None => return Err(K16ImageError::InvalidExecutable),
    };
    if entry_pc < load_addr || entry_pc >= load_end || entry_pc % 2 != 0 {
        return Err(K16ImageError::InvalidExecutable);
    }
    let zero_fill_addr = match load_addr.checked_add(file_size) {
        Some(value) => value,
        None => return Err(K16ImageError::InvalidExecutable),
    };
    Ok(FixedK16ImageHeader {
        entry_pc,
        load_addr,
        file_size,
        memory_size,
        zero_fill_addr,
        zero_fill_len: memory_size - file_size,
    })
}

fn validate_dynamic_relocations(
    memory_size: u32,
    relocation_table: &[u8],
    relocation_count: u32,
) -> Result<(), K16ImageError> {
    let mut index = 0;
    while index < relocation_count {
        let table_offset = match index.checked_mul(K16E_RELOCATION_RECORD_SIZE) {
            Some(value) => value,
            None => return Err(K16ImageError::InvalidExecutable),
        };
        let table_offset = match usize::try_from(table_offset) {
            Ok(value) => value,
            Err(_) => return Err(K16ImageError::InvalidExecutable),
        };
        let record_end = table_offset + K16E_RELOCATION_RECORD_SIZE as usize;
        let record = match relocation_table.get(table_offset..record_end) {
            Some(value) => value,
            None => return Err(K16ImageError::InvalidExecutable),
        };
        parse_k16e_relocation_record(record, memory_size)?;
        index += 1;
    }
    Ok(())
}

fn validate_dynamic_relocation(
    memory_size: u32,
    relocation: K16eRelocation,
) -> Result<(), K16ImageError> {
    validate_dynamic_relocation_parts(memory_size, relocation.offset)
}

fn validate_dynamic_relocation_parts(memory_size: u32, offset: u32) -> Result<(), K16ImageError> {
    if offset % 2 != 0 {
        return Err(K16ImageError::InvalidExecutable);
    }
    let width = 4;
    let end = match offset.checked_add(width) {
        Some(value) => value,
        None => return Err(K16ImageError::InvalidExecutable),
    };
    if end > memory_size {
        return Err(K16ImageError::InvalidExecutable);
    }
    Ok(())
}

fn decode_relocation_kind(value: u32) -> Result<K16eRelocationKind, K16ImageError> {
    match value {
        1 => Ok(K16eRelocationKind::Abs32),
        2 => Ok(K16eRelocationKind::Call32),
        _ => Err(K16ImageError::InvalidExecutable),
    }
}

fn header_bytes(header: &[u8], offset: usize, expected: &[u8]) -> Result<(), K16ImageError> {
    let end = match offset.checked_add(expected.len()) {
        Some(value) => value,
        None => return Err(K16ImageError::InvalidExecutable),
    };
    if header.get(offset..end) == Some(expected) {
        Ok(())
    } else {
        Err(K16ImageError::InvalidExecutable)
    }
}

fn read_u16_le(header: &[u8], offset: usize) -> Result<u16, K16ImageError> {
    let end = match offset.checked_add(2) {
        Some(value) => value,
        None => return Err(K16ImageError::InvalidExecutable),
    };
    let bytes = match header.get(offset..end) {
        Some(value) => value,
        None => return Err(K16ImageError::InvalidExecutable),
    };
    Ok(u16::from_le_bytes([bytes[0], bytes[1]]))
}

fn read_u32_le(header: &[u8], offset: usize) -> Result<u32, K16ImageError> {
    let end = match offset.checked_add(4) {
        Some(value) => value,
        None => return Err(K16ImageError::InvalidExecutable),
    };
    let bytes = match header.get(offset..end) {
        Some(value) => value,
        None => return Err(K16ImageError::InvalidExecutable),
    };
    Ok(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write_u16_le(bytes: &mut [u8], offset: usize, value: u16) {
        bytes[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
    }

    fn write_u32_le(bytes: &mut [u8], offset: usize, value: u32) {
        bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
    }

    fn fixed_header() -> [u8; 52] {
        let mut bytes = [0u8; 52];
        bytes[0..4].copy_from_slice(b"K16E");
        write_u16_le(&mut bytes, 4, 1);
        write_u16_le(&mut bytes, 6, 32);
        write_u16_le(&mut bytes, 8, 1);
        write_u16_le(&mut bytes, 10, 0);
        write_u32_le(&mut bytes, 12, 0x8000);
        write_u32_le(&mut bytes, 16, 32);
        write_u32_le(&mut bytes, 20, 1);
        write_u32_le(&mut bytes, 24, K16eAbiKind::Program as u32);
        write_u32_le(&mut bytes, 28, 0);
        write_u32_le(&mut bytes, 32, 1);
        write_u32_le(&mut bytes, 36, 0x8000);
        write_u32_le(&mut bytes, 40, FIXED_K16E_V1_PAYLOAD_OFFSET);
        write_u32_le(&mut bytes, 44, 4);
        write_u32_le(&mut bytes, 48, 8);
        bytes
    }

    fn dynamic_program_image() -> [u8; 88] {
        let mut bytes = [0u8; 88];
        bytes[0..4].copy_from_slice(b"K16E");
        write_u16_le(&mut bytes, 4, 2);
        write_u16_le(&mut bytes, 6, 32);
        write_u16_le(&mut bytes, 8, 1);
        write_u16_le(&mut bytes, 10, 0);
        write_u32_le(&mut bytes, 12, 2);
        write_u32_le(&mut bytes, 16, 32);
        write_u32_le(&mut bytes, 20, 2);
        write_u32_le(&mut bytes, 24, K16eAbiKind::Program as u32);
        write_u32_le(&mut bytes, 28, 0);
        write_u32_le(&mut bytes, 32, 1);
        write_u32_le(&mut bytes, 36, 0);
        write_u32_le(&mut bytes, 40, 72);
        write_u32_le(&mut bytes, 44, 8);
        write_u32_le(&mut bytes, 48, 12);
        write_u32_le(&mut bytes, 52, 2);
        write_u32_le(&mut bytes, 56, 0);
        write_u32_le(&mut bytes, 60, 80);
        write_u32_le(&mut bytes, 64, 8);
        write_u32_le(&mut bytes, 68, 1);
        bytes[72..80].copy_from_slice(&[0x01, 0xe1, 0, 0, 0, 0, 0, 0x90]);
        write_u32_le(&mut bytes, 80, 2);
        write_u32_le(&mut bytes, 84, 1);
        bytes
    }

    #[test]
    fn fixed_k16e_v1_header_parses_boot_chain_fields() {
        let header =
            parse_fixed_k16e_v1(&fixed_header(), K16eAbiKind::Program, 56).expect("header parses");

        assert_eq!(header.entry_pc, 0x8000);
        assert_eq!(header.load_addr, 0x8000);
        assert_eq!(header.file_size, 4);
        assert_eq!(header.memory_size, 8);
        assert_eq!(header.zero_fill_addr, 0x8004);
        assert_eq!(header.zero_fill_len, 4);
    }

    #[test]
    fn fixed_k16e_v1_header_rejects_wrong_abi_kind() {
        assert_eq!(
            parse_fixed_k16e_v1(&fixed_header(), K16eAbiKind::Kernel, 56),
            Err(K16ImageError::InvalidExecutable)
        );
    }

    #[test]
    fn fixed_k16e_v1_header_rejects_memory_smaller_than_file() {
        let mut bytes = fixed_header();
        write_u32_le(&mut bytes, 48, 2);

        assert_eq!(
            parse_fixed_k16e_v1(&bytes, K16eAbiKind::Program, 56),
            Err(K16ImageError::InvalidExecutable)
        );
    }

    #[test]
    fn dynamic_k16e_v2_header_parses_program_metadata() {
        let image = dynamic_program_image();

        let header = parse_dynamic_k16e_v2(&image).expect("dynamic header parses");

        assert_eq!(header.entry_offset, 2);
        assert_eq!(header.payload_offset, 72);
        assert_eq!(header.file_size, 8);
        assert_eq!(header.memory_size, 12);
        assert_eq!(header.relocation_table_offset, 80);
        assert_eq!(header.relocation_count, 1);
        assert_eq!(
            header.relocation(0),
            Some(K16eRelocation {
                offset: 2,
                kind: K16eRelocationKind::Abs32,
            })
        );
        assert_eq!(header.relocation(1), None);
    }

    #[test]
    fn dynamic_k16e_v2_header_only_parser_uses_inode_size_for_ranges() {
        let image = dynamic_program_image();

        let header =
            parse_dynamic_k16e_v2_header(&image[..72], image.len() as u32).expect("header parses");

        assert_eq!(header.entry_offset, 2);
        assert_eq!(header.payload_offset, 72);
        assert_eq!(header.file_size, 8);
        assert_eq!(header.memory_size, 12);
        assert_eq!(header.relocation_table_offset, 80);
        assert_eq!(header.relocation_count, 1);
    }

    #[test]
    fn dynamic_k16e_v2_header_only_parser_rejects_truncated_inode_ranges() {
        let image = dynamic_program_image();

        assert_eq!(
            parse_dynamic_k16e_v2_header(&image[..72], 87),
            Err(K16ImageError::InvalidExecutable)
        );
    }

    #[test]
    fn dynamic_k16e_v2_rejects_fixed_v1_image() {
        assert_eq!(
            parse_dynamic_k16e_v2(&fixed_header()),
            Err(K16ImageError::InvalidExecutable)
        );
    }

    #[test]
    fn dynamic_k16e_v2_rejects_nonzero_load_address() {
        let mut image = dynamic_program_image();
        write_u32_le(&mut image, 36, 0x8000);

        assert_eq!(
            parse_dynamic_k16e_v2(&image),
            Err(K16ImageError::InvalidExecutable)
        );
    }

    #[test]
    fn dynamic_k16e_v2_rejects_relocation_outside_memory() {
        let mut image = dynamic_program_image();
        write_u32_le(&mut image, 80, 10);

        assert_eq!(
            parse_dynamic_k16e_v2(&image),
            Err(K16ImageError::InvalidExecutable)
        );
    }

    #[test]
    fn k16e_relocation_record_parses_single_storage_record() {
        let mut record = [0u8; 8];
        write_u32_le(&mut record, 0, 6);
        write_u32_le(&mut record, 4, 2);

        assert_eq!(
            parse_k16e_relocation_record(&record, 12),
            Ok(K16eRelocation {
                offset: 6,
                kind: K16eRelocationKind::Call32,
            })
        );
    }
}
