pub const RUXE_MAGIC: &[u8; 4] = b"RUXE";
pub const RUXE_VERSION: u16 = 1;
pub const RUXE_HEADER_SIZE: u16 = 32;
pub const RUXE_SECTION_RECORD_SIZE: u32 = 20;
pub const RUXE_ISA_RUX16: u16 = 1;
pub const RUXE_SECTION_KIND_LOAD: u32 = 1;
pub const RUXE_SECTION_TABLE_OFFSET: u32 = RUXE_HEADER_SIZE as u32;
pub const RUXE_SECTION_COUNT_SINGLE_LOAD: u32 = 1;
pub const RUXE_PAYLOAD_OFFSET_SINGLE_LOAD: u32 =
    RUXE_SECTION_TABLE_OFFSET + RUXE_SECTION_RECORD_SIZE;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RuxeAbiKind {
    Bootloader,
    Kernel,
}

impl RuxeAbiKind {
    pub fn code(self) -> u32 {
        match self {
            Self::Bootloader => 1,
            Self::Kernel => 2,
        }
    }

    fn decode(code: u32) -> Result<Self, String> {
        match code {
            1 => Ok(Self::Bootloader),
            2 => Ok(Self::Kernel),
            _ => Err(format!("unsupported RUXE ABI kind {code}")),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuxeExecutable {
    pub abi_kind: RuxeAbiKind,
    pub entry_pc: u32,
    pub load_addr: u32,
    pub payload: Vec<u8>,
}

pub fn encode_rux16_executable(
    payload: &[u8],
    abi_kind: RuxeAbiKind,
    entry_pc: u32,
    load_addr: u32,
) -> Result<Vec<u8>, String> {
    if payload.is_empty() {
        return Err("RUXE payload is empty".to_string());
    }
    if payload.len() % 2 != 0 {
        return Err("RUXE Rux16 payload length must be even".to_string());
    }
    let payload_size =
        u32::try_from(payload.len()).map_err(|_| "RUXE payload is too large".to_string())?;
    validate_entry_inside_payload(entry_pc, load_addr, payload_size)?;
    load_addr
        .checked_add(payload_size)
        .ok_or_else(|| "RUXE load range overflows address space".to_string())?;

    let capacity = usize::try_from(RUXE_PAYLOAD_OFFSET_SINGLE_LOAD)
        .map_err(|_| "RUXE payload offset does not fit usize".to_string())?
        .checked_add(payload.len())
        .ok_or_else(|| "RUXE file size overflows usize".to_string())?;
    let mut bytes = Vec::with_capacity(capacity);
    bytes.extend_from_slice(RUXE_MAGIC);
    write_u16(&mut bytes, RUXE_VERSION);
    write_u16(&mut bytes, RUXE_HEADER_SIZE);
    write_u16(&mut bytes, RUXE_ISA_RUX16);
    write_u16(&mut bytes, 0);
    write_u32(&mut bytes, entry_pc);
    write_u32(&mut bytes, RUXE_SECTION_TABLE_OFFSET);
    write_u32(&mut bytes, RUXE_SECTION_COUNT_SINGLE_LOAD);
    write_u32(&mut bytes, abi_kind.code());
    write_u32(&mut bytes, 0);

    write_u32(&mut bytes, RUXE_SECTION_KIND_LOAD);
    write_u32(&mut bytes, load_addr);
    write_u32(&mut bytes, RUXE_PAYLOAD_OFFSET_SINGLE_LOAD);
    write_u32(&mut bytes, payload_size);
    write_u32(&mut bytes, payload_size);

    bytes.extend_from_slice(payload);
    Ok(bytes)
}

pub fn decode_rux16_executable(bytes: &[u8]) -> Result<RuxeExecutable, String> {
    let magic = bytes
        .get(0..4)
        .ok_or_else(|| "invalid RUXE magic".to_string())?;
    if magic != RUXE_MAGIC {
        return Err("invalid RUXE magic".to_string());
    }
    if bytes.len() < RUXE_PAYLOAD_OFFSET_SINGLE_LOAD as usize {
        return Err("RUXE file is smaller than the fixed header".to_string());
    }
    let version = read_u16(bytes, 4)?;
    if version != RUXE_VERSION {
        return Err(format!("unsupported RUXE version {version}"));
    }
    let header_size = read_u16(bytes, 6)?;
    if header_size != RUXE_HEADER_SIZE {
        return Err(format!("unsupported RUXE header size {header_size}"));
    }
    let isa = read_u16(bytes, 8)?;
    if isa != RUXE_ISA_RUX16 {
        return Err(format!("unsupported RUXE ISA {isa}"));
    }
    let flags = read_u16(bytes, 10)?;
    if flags != 0 {
        return Err(format!("unsupported RUXE flags {flags:#06x}"));
    }
    let entry_pc = read_u32(bytes, 12)?;
    let section_table_offset = read_u32(bytes, 16)?;
    if section_table_offset != RUXE_SECTION_TABLE_OFFSET {
        return Err(format!(
            "unsupported RUXE section table offset {section_table_offset}"
        ));
    }
    let section_count = read_u32(bytes, 20)?;
    if section_count != RUXE_SECTION_COUNT_SINGLE_LOAD {
        return Err(format!("unsupported RUXE section count {section_count}"));
    }
    let abi_kind = RuxeAbiKind::decode(read_u32(bytes, 24)?)?;
    if read_u32(bytes, 28)? != 0 {
        return Err("RUXE reserved header fields must be zero".to_string());
    }

    let section_kind = read_u32(bytes, 32)?;
    if section_kind != RUXE_SECTION_KIND_LOAD {
        return Err(format!("unsupported RUXE section kind {section_kind}"));
    }
    let load_addr = read_u32(bytes, 36)?;
    let file_offset = read_u32(bytes, 40)?;
    if file_offset != RUXE_PAYLOAD_OFFSET_SINGLE_LOAD {
        return Err(format!("unsupported RUXE payload offset {file_offset}"));
    }
    let file_size = read_u32(bytes, 44)?;
    let memory_size = read_u32(bytes, 48)?;
    if file_size == 0 {
        return Err("RUXE payload is empty".to_string());
    }
    if file_size != memory_size {
        return Err("RUXE zero-fill sections are not supported yet".to_string());
    }
    if file_size % 2 != 0 {
        return Err("RUXE Rux16 payload length must be even".to_string());
    }
    validate_entry_inside_payload(entry_pc, load_addr, memory_size)?;
    let end = file_offset
        .checked_add(file_size)
        .ok_or_else(|| "RUXE payload range overflows".to_string())?;
    let end = usize::try_from(end).map_err(|_| "RUXE payload range is too large".to_string())?;
    let start = usize::try_from(file_offset)
        .map_err(|_| "RUXE payload offset does not fit usize".to_string())?;
    let payload = bytes
        .get(start..end)
        .ok_or_else(|| "RUXE payload range is out of bounds".to_string())?
        .to_vec();

    Ok(RuxeExecutable {
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
        .ok_or_else(|| "RUXE load range overflows address space".to_string())?;
    if entry_pc < load_addr || entry_pc >= end {
        return Err(format!(
            "RUXE entry PC {entry_pc:#010x} is outside load range {load_addr:#010x}..{end:#010x}",
        ));
    }
    if entry_pc % 2 != 0 {
        return Err("RUXE Rux16 entry PC must be 2-byte aligned".to_string());
    }
    Ok(())
}

fn read_u16(bytes: &[u8], offset: usize) -> Result<u16, String> {
    let value = bytes
        .get(offset..offset + 2)
        .ok_or_else(|| "RUXE u16 field is truncated".to_string())?;
    Ok(u16::from_le_bytes(value.try_into().unwrap()))
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, String> {
    let value = bytes
        .get(offset..offset + 4)
        .ok_or_else(|| "RUXE u32 field is truncated".to_string())?;
    Ok(u32::from_le_bytes(value.try_into().unwrap()))
}

fn write_u16(bytes: &mut Vec<u8>, value: u16) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_u32(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}
