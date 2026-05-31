pub const K16E_MAGIC: &[u8; 4] = b"K16E";
pub const K16E_VERSION: u16 = 1;
pub const K16E_HEADER_SIZE: u16 = 32;
pub const K16E_SECTION_RECORD_SIZE: u32 = 20;
pub const K16E_ISA_K16: u16 = 1;
pub const K16E_SECTION_KIND_LOAD: u32 = 1;
pub const K16E_SECTION_TABLE_OFFSET: u32 = K16E_HEADER_SIZE as u32;
pub const K16E_SECTION_COUNT_SINGLE_LOAD: u32 = 1;
pub const K16E_PAYLOAD_OFFSET_SINGLE_LOAD: u32 =
    K16E_SECTION_TABLE_OFFSET + K16E_SECTION_RECORD_SIZE;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum K16eAbiKind {
    Bootloader,
    Kernel,
    Program,
}

impl K16eAbiKind {
    fn decode(code: u32) -> Result<Self, String> {
        match code {
            1 => Ok(Self::Bootloader),
            2 => Ok(Self::Kernel),
            3 => Ok(Self::Program),
            _ => Err(format!("unsupported K16E ABI kind {code}")),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct K16eExecutable {
    pub abi_kind: K16eAbiKind,
    pub entry_pc: u32,
    pub load_addr: u32,
    pub payload: Vec<u8>,
}

pub fn decode_program_k16_executable(bytes: &[u8]) -> Result<K16eExecutable, String> {
    let executable = decode_k16_executable(bytes)?;
    if executable.abi_kind != K16eAbiKind::Program {
        return Err("expected K16E program ABI kind".to_string());
    }
    Ok(executable)
}

pub fn decode_k16_executable(bytes: &[u8]) -> Result<K16eExecutable, String> {
    let magic = bytes
        .get(0..4)
        .ok_or_else(|| "invalid K16E magic".to_string())?;
    if magic != K16E_MAGIC {
        return Err("invalid K16E magic".to_string());
    }
    if bytes.len() < K16E_PAYLOAD_OFFSET_SINGLE_LOAD as usize {
        return Err("K16E file is smaller than the fixed header".to_string());
    }
    let version = read_u16(bytes, 4)?;
    if version != K16E_VERSION {
        return Err(format!("unsupported K16E version {version}"));
    }
    let header_size = read_u16(bytes, 6)?;
    if header_size != K16E_HEADER_SIZE {
        return Err(format!("unsupported K16E header size {header_size}"));
    }
    let isa = read_u16(bytes, 8)?;
    if isa != K16E_ISA_K16 {
        return Err(format!("unsupported K16E ISA {isa}"));
    }
    let flags = read_u16(bytes, 10)?;
    if flags != 0 {
        return Err(format!("unsupported K16E flags {flags:#06x}"));
    }
    let entry_pc = read_u32(bytes, 12)?;
    let section_table_offset = read_u32(bytes, 16)?;
    if section_table_offset != K16E_SECTION_TABLE_OFFSET {
        return Err(format!(
            "unsupported K16E section table offset {section_table_offset}"
        ));
    }
    let section_count = read_u32(bytes, 20)?;
    if section_count != K16E_SECTION_COUNT_SINGLE_LOAD {
        return Err(format!("unsupported K16E section count {section_count}"));
    }
    let abi_kind = K16eAbiKind::decode(read_u32(bytes, 24)?)?;
    if read_u32(bytes, 28)? != 0 {
        return Err("K16E reserved header fields must be zero".to_string());
    }

    let section_kind = read_u32(bytes, 32)?;
    if section_kind != K16E_SECTION_KIND_LOAD {
        return Err(format!("unsupported K16E section kind {section_kind}"));
    }
    let load_addr = read_u32(bytes, 36)?;
    let file_offset = read_u32(bytes, 40)?;
    if file_offset != K16E_PAYLOAD_OFFSET_SINGLE_LOAD {
        return Err(format!("unsupported K16E payload offset {file_offset}"));
    }
    let file_size = read_u32(bytes, 44)?;
    let memory_size = read_u32(bytes, 48)?;
    if file_size == 0 {
        return Err("K16E payload is empty".to_string());
    }
    if file_size != memory_size {
        return Err("K16E zero-fill sections are not supported yet".to_string());
    }
    if file_size % 2 != 0 {
        return Err("K16E K16 payload length must be even".to_string());
    }
    validate_entry_inside_payload(entry_pc, load_addr, memory_size)?;
    let end = file_offset
        .checked_add(file_size)
        .ok_or_else(|| "K16E payload range overflows".to_string())?;
    let end = usize::try_from(end).map_err(|_| "K16E payload range is too large".to_string())?;
    let start = usize::try_from(file_offset)
        .map_err(|_| "K16E payload offset does not fit usize".to_string())?;
    let payload = bytes
        .get(start..end)
        .ok_or_else(|| "K16E payload range is out of bounds".to_string())?
        .to_vec();

    Ok(K16eExecutable {
        abi_kind,
        entry_pc,
        load_addr,
        payload,
    })
}

fn validate_entry_inside_payload(
    entry_pc: u32,
    load_addr: u32,
    payload_size: u32,
) -> Result<(), String> {
    let end = load_addr
        .checked_add(payload_size)
        .ok_or_else(|| "K16E load range overflows address space".to_string())?;
    if entry_pc < load_addr || entry_pc >= end {
        return Err(format!(
            "K16E entry PC {entry_pc:#010x} is outside load range {load_addr:#010x}..{end:#010x}",
        ));
    }
    if entry_pc % 2 != 0 {
        return Err("K16E K16 entry PC must be 2-byte aligned".to_string());
    }
    Ok(())
}

fn read_u16(bytes: &[u8], offset: usize) -> Result<u16, String> {
    let value = bytes
        .get(offset..offset + 2)
        .ok_or_else(|| "K16E u16 field is truncated".to_string())?;
    Ok(u16::from_le_bytes(value.try_into().unwrap()))
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, String> {
    let value = bytes
        .get(offset..offset + 4)
        .ok_or_else(|| "K16E u32 field is truncated".to_string())?;
    Ok(u32::from_le_bytes(value.try_into().unwrap()))
}
