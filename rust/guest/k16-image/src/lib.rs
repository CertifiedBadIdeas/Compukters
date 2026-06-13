#![no_std]

pub const FIXED_K16E_V1_HEADER_SIZE: u32 = 52;
pub const FIXED_K16E_V1_PAYLOAD_OFFSET: u32 = 52;

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
}
