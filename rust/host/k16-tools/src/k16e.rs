pub const K16E_MAGIC: &[u8; 4] = b"K16E";
pub const K16E_VERSION: u16 = 1;
pub const K16E_DYNAMIC_VERSION: u16 = 2;
pub const K16E_HEADER_SIZE: u16 = 32;
pub const K16E_SECTION_RECORD_SIZE: u32 = 20;
pub const K16E_ISA_K16: u16 = 1;
pub const K16E_SECTION_KIND_LOAD: u32 = 1;
pub const K16E_SECTION_KIND_RELOCATIONS: u32 = 2;
pub const K16E_SECTION_TABLE_OFFSET: u32 = K16E_HEADER_SIZE as u32;
pub const K16E_SECTION_COUNT_SINGLE_LOAD: u32 = 1;
pub const K16E_SECTION_COUNT_DYNAMIC_PROGRAM: u32 = 2;
pub const K16E_PAYLOAD_OFFSET_SINGLE_LOAD: u32 =
    K16E_SECTION_TABLE_OFFSET + K16E_SECTION_RECORD_SIZE;
pub const K16E_PAYLOAD_OFFSET_DYNAMIC_PROGRAM: u32 =
    K16E_SECTION_TABLE_OFFSET + K16E_SECTION_RECORD_SIZE * K16E_SECTION_COUNT_DYNAMIC_PROGRAM;
pub const K16E_RELOCATION_RECORD_SIZE: u32 = 8;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum K16eAbiKind {
    Bootloader,
    Kernel,
    Program,
}

impl K16eAbiKind {
    pub fn code(self) -> u32 {
        match self {
            Self::Bootloader => 1,
            Self::Kernel => 2,
            Self::Program => 3,
        }
    }

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
    pub memory_size: u32,
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum K16eRelocationKind {
    Abs32,
    Call32,
}

impl K16eRelocationKind {
    pub fn code(self) -> u32 {
        match self {
            Self::Abs32 => 1,
            Self::Call32 => 2,
        }
    }

    fn decode(code: u32) -> Result<Self, String> {
        match code {
            1 => Ok(Self::Abs32),
            2 => Ok(Self::Call32),
            _ => Err(format!("unsupported K16E relocation kind {code}")),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct K16eRelocation {
    pub offset: u32,
    pub kind: K16eRelocationKind,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DynamicK16Program {
    pub entry_offset: u32,
    pub memory_size: u32,
    pub payload: Vec<u8>,
    pub relocations: Vec<K16eRelocation>,
}

pub fn encode_k16_executable(
    payload: &[u8],
    abi_kind: K16eAbiKind,
    entry_pc: u32,
    load_addr: u32,
) -> Result<Vec<u8>, String> {
    let memory_size =
        u32::try_from(payload.len()).map_err(|_| "K16E payload is too large".to_string())?;
    encode_k16_executable_with_memory_size(payload, memory_size, abi_kind, entry_pc, load_addr)
}

pub fn encode_k16_executable_with_memory_size(
    payload: &[u8],
    memory_size: u32,
    abi_kind: K16eAbiKind,
    entry_pc: u32,
    load_addr: u32,
) -> Result<Vec<u8>, String> {
    if payload.is_empty() {
        return Err("K16E payload is empty".to_string());
    }
    if payload.len() % 2 != 0 {
        return Err("K16E K16 payload length must be even".to_string());
    }
    let payload_size =
        u32::try_from(payload.len()).map_err(|_| "K16E payload is too large".to_string())?;
    if memory_size < payload_size {
        return Err("K16E memory size is smaller than payload size".to_string());
    }
    if memory_size % 2 != 0 {
        return Err("K16E K16 memory size must be even".to_string());
    }
    validate_entry_inside_payload(entry_pc, load_addr, memory_size)?;
    load_addr
        .checked_add(memory_size)
        .ok_or_else(|| "K16E load range overflows address space".to_string())?;

    let capacity = usize::try_from(K16E_PAYLOAD_OFFSET_SINGLE_LOAD)
        .map_err(|_| "K16E payload offset does not fit usize".to_string())?
        .checked_add(payload.len())
        .ok_or_else(|| "K16E file size overflows usize".to_string())?;
    let mut bytes = Vec::with_capacity(capacity);
    bytes.extend_from_slice(K16E_MAGIC);
    write_u16(&mut bytes, K16E_VERSION);
    write_u16(&mut bytes, K16E_HEADER_SIZE);
    write_u16(&mut bytes, K16E_ISA_K16);
    write_u16(&mut bytes, 0);
    write_u32(&mut bytes, entry_pc);
    write_u32(&mut bytes, K16E_SECTION_TABLE_OFFSET);
    write_u32(&mut bytes, K16E_SECTION_COUNT_SINGLE_LOAD);
    write_u32(&mut bytes, abi_kind.code());
    write_u32(&mut bytes, 0);

    write_u32(&mut bytes, K16E_SECTION_KIND_LOAD);
    write_u32(&mut bytes, load_addr);
    write_u32(&mut bytes, K16E_PAYLOAD_OFFSET_SINGLE_LOAD);
    write_u32(&mut bytes, payload_size);
    write_u32(&mut bytes, memory_size);

    bytes.extend_from_slice(payload);
    Ok(bytes)
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
    if memory_size < file_size {
        return Err("K16E memory size is smaller than payload size".to_string());
    }
    if file_size % 2 != 0 {
        return Err("K16E K16 payload length must be even".to_string());
    }
    if memory_size % 2 != 0 {
        return Err("K16E K16 memory size must be even".to_string());
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
        memory_size,
        payload,
    })
}

pub fn encode_dynamic_k16_program(
    payload: &[u8],
    memory_size: u32,
    entry_offset: u32,
    relocations: &[K16eRelocation],
) -> Result<Vec<u8>, String> {
    if payload.is_empty() {
        return Err("K16E payload is empty".to_string());
    }
    if payload.len() % 2 != 0 {
        return Err("K16E K16 payload length must be even".to_string());
    }
    let payload_size =
        u32::try_from(payload.len()).map_err(|_| "K16E payload is too large".to_string())?;
    if memory_size < payload_size {
        return Err("K16E memory size is smaller than payload size".to_string());
    }
    if memory_size % 2 != 0 {
        return Err("K16E K16 memory size must be even".to_string());
    }
    validate_entry_offset_inside_payload(entry_offset, memory_size)?;
    validate_dynamic_relocations(memory_size, relocations)?;

    let relocation_table_size = u32::try_from(relocations.len())
        .map_err(|_| "K16E relocation table is too large".to_string())?
        .checked_mul(K16E_RELOCATION_RECORD_SIZE)
        .ok_or_else(|| "K16E relocation table size overflows".to_string())?;
    let relocation_table_offset = K16E_PAYLOAD_OFFSET_DYNAMIC_PROGRAM
        .checked_add(payload_size)
        .ok_or_else(|| "K16E relocation table offset overflows".to_string())?;
    let capacity = usize::try_from(relocation_table_offset)
        .map_err(|_| "K16E relocation table offset does not fit usize".to_string())?
        .checked_add(
            usize::try_from(relocation_table_size)
                .map_err(|_| "K16E relocation table size does not fit usize".to_string())?,
        )
        .ok_or_else(|| "K16E file size overflows usize".to_string())?;

    let mut bytes = Vec::with_capacity(capacity);
    bytes.extend_from_slice(K16E_MAGIC);
    write_u16(&mut bytes, K16E_DYNAMIC_VERSION);
    write_u16(&mut bytes, K16E_HEADER_SIZE);
    write_u16(&mut bytes, K16E_ISA_K16);
    write_u16(&mut bytes, 0);
    write_u32(&mut bytes, entry_offset);
    write_u32(&mut bytes, K16E_SECTION_TABLE_OFFSET);
    write_u32(&mut bytes, K16E_SECTION_COUNT_DYNAMIC_PROGRAM);
    write_u32(&mut bytes, K16eAbiKind::Program.code());
    write_u32(&mut bytes, 0);

    write_u32(&mut bytes, K16E_SECTION_KIND_LOAD);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, K16E_PAYLOAD_OFFSET_DYNAMIC_PROGRAM);
    write_u32(&mut bytes, payload_size);
    write_u32(&mut bytes, memory_size);

    write_u32(&mut bytes, K16E_SECTION_KIND_RELOCATIONS);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, relocation_table_offset);
    write_u32(&mut bytes, relocation_table_size);
    write_u32(&mut bytes, relocations.len() as u32);

    bytes.extend_from_slice(payload);
    for relocation in relocations {
        write_u32(&mut bytes, relocation.offset);
        write_u32(&mut bytes, relocation.kind.code());
    }
    Ok(bytes)
}

pub fn decode_dynamic_k16_program(bytes: &[u8]) -> Result<DynamicK16Program, String> {
    let magic = bytes
        .get(0..4)
        .ok_or_else(|| "invalid K16E magic".to_string())?;
    if magic != K16E_MAGIC {
        return Err("invalid K16E magic".to_string());
    }
    if bytes.len() < K16E_PAYLOAD_OFFSET_DYNAMIC_PROGRAM as usize {
        return Err("K16E file is smaller than the dynamic header".to_string());
    }
    let version = read_u16(bytes, 4)?;
    if version != K16E_DYNAMIC_VERSION {
        return Err(format!("unsupported dynamic K16E version {version}"));
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
    let entry_offset = read_u32(bytes, 12)?;
    let section_table_offset = read_u32(bytes, 16)?;
    if section_table_offset != K16E_SECTION_TABLE_OFFSET {
        return Err(format!(
            "unsupported K16E section table offset {section_table_offset}"
        ));
    }
    let section_count = read_u32(bytes, 20)?;
    if section_count != K16E_SECTION_COUNT_DYNAMIC_PROGRAM {
        return Err(format!(
            "unsupported dynamic K16E section count {section_count}"
        ));
    }
    let abi_kind = K16eAbiKind::decode(read_u32(bytes, 24)?)?;
    if abi_kind != K16eAbiKind::Program {
        return Err(format!(
            "dynamic K16E ABI kind {:?} is not a program",
            abi_kind
        ));
    }
    if read_u32(bytes, 28)? != 0 {
        return Err("K16E reserved header fields must be zero".to_string());
    }

    let load_kind = read_u32(bytes, 32)?;
    if load_kind != K16E_SECTION_KIND_LOAD {
        return Err(format!("unsupported K16E section kind {load_kind}"));
    }
    let load_addr = read_u32(bytes, 36)?;
    if load_addr != 0 {
        return Err(format!(
            "dynamic K16E load address must be zero, got {load_addr:#010x}"
        ));
    }
    let payload_offset = read_u32(bytes, 40)?;
    if payload_offset != K16E_PAYLOAD_OFFSET_DYNAMIC_PROGRAM {
        return Err(format!(
            "unsupported dynamic K16E payload offset {payload_offset}"
        ));
    }
    let payload_size = read_u32(bytes, 44)?;
    let memory_size = read_u32(bytes, 48)?;
    if payload_size == 0 {
        return Err("K16E payload is empty".to_string());
    }
    if memory_size < payload_size {
        return Err("K16E memory size is smaller than payload size".to_string());
    }
    if payload_size % 2 != 0 {
        return Err("K16E K16 payload length must be even".to_string());
    }
    if memory_size % 2 != 0 {
        return Err("K16E K16 memory size must be even".to_string());
    }
    validate_entry_offset_inside_payload(entry_offset, memory_size)?;

    let relocation_kind = read_u32(bytes, 52)?;
    if relocation_kind != K16E_SECTION_KIND_RELOCATIONS {
        return Err(format!("unsupported K16E section kind {relocation_kind}"));
    }
    if read_u32(bytes, 56)? != 0 {
        return Err("dynamic K16E relocation section address must be zero".to_string());
    }
    let relocation_table_offset = read_u32(bytes, 60)?;
    let relocation_table_size = read_u32(bytes, 64)?;
    let relocation_count = read_u32(bytes, 68)?;
    let expected_relocation_table_size = relocation_count
        .checked_mul(K16E_RELOCATION_RECORD_SIZE)
        .ok_or_else(|| "K16E relocation table size overflows".to_string())?;
    if relocation_table_size != expected_relocation_table_size {
        return Err("K16E relocation table size does not match relocation count".to_string());
    }
    if relocation_table_offset
        != payload_offset
            .checked_add(payload_size)
            .ok_or_else(|| "K16E relocation table offset overflows".to_string())?
    {
        return Err(format!(
            "unsupported dynamic K16E relocation table offset {relocation_table_offset}"
        ));
    }

    let payload = bytes_slice(bytes, payload_offset, payload_size, "payload")?.to_vec();
    let relocation_table = bytes_slice(
        bytes,
        relocation_table_offset,
        relocation_table_size,
        "relocation table",
    )?;
    let mut relocations = Vec::with_capacity(
        usize::try_from(relocation_count)
            .map_err(|_| "K16E relocation count does not fit usize".to_string())?,
    );
    for index in 0..relocation_count {
        let offset = usize::try_from(index * K16E_RELOCATION_RECORD_SIZE)
            .map_err(|_| "K16E relocation offset does not fit usize".to_string())?;
        relocations.push(K16eRelocation {
            offset: read_u32(relocation_table, offset)?,
            kind: K16eRelocationKind::decode(read_u32(relocation_table, offset + 4)?)?,
        });
    }
    validate_dynamic_relocations(memory_size, &relocations)?;

    Ok(DynamicK16Program {
        entry_offset,
        memory_size,
        payload,
        relocations,
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

fn validate_entry_offset_inside_payload(entry_offset: u32, memory_size: u32) -> Result<(), String> {
    if entry_offset >= memory_size {
        return Err(format!(
            "K16E entry offset {entry_offset:#010x} is outside dynamic load range 0x00000000..{memory_size:#010x}",
        ));
    }
    if entry_offset % 2 != 0 {
        return Err("K16E K16 entry offset must be 2-byte aligned".to_string());
    }
    Ok(())
}

fn validate_dynamic_relocations(
    memory_size: u32,
    relocations: &[K16eRelocation],
) -> Result<(), String> {
    for relocation in relocations {
        if relocation.offset % 2 != 0 {
            return Err(format!(
                "K16E relocation offset {:#010x} must be 2-byte aligned",
                relocation.offset
            ));
        }
        let relocation_width = match relocation.kind {
            K16eRelocationKind::Abs32 | K16eRelocationKind::Call32 => 4,
        };
        let relocation_end = relocation
            .offset
            .checked_add(relocation_width)
            .ok_or_else(|| "K16E relocation range overflows".to_string())?;
        if relocation_end > memory_size {
            return Err(format!(
                "K16E relocation range {:#010x}..{:#010x} exceeds dynamic memory size {memory_size:#010x}",
                relocation.offset, relocation_end,
            ));
        }
    }
    Ok(())
}

fn bytes_slice<'a>(
    bytes: &'a [u8],
    offset: u32,
    size: u32,
    name: &str,
) -> Result<&'a [u8], String> {
    let end = offset
        .checked_add(size)
        .ok_or_else(|| format!("K16E {name} range overflows"))?;
    let start = usize::try_from(offset).map_err(|_| format!("K16E {name} offset is too large"))?;
    let end = usize::try_from(end).map_err(|_| format!("K16E {name} range is too large"))?;
    bytes
        .get(start..end)
        .ok_or_else(|| format!("K16E {name} range is out of bounds"))
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

fn write_u16(bytes: &mut Vec<u8>, value: u16) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_u32(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}
