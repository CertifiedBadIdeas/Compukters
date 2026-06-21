pub const K16E_MAGIC: &[u8; 4] = b"K16E";
pub const K16E_VERSION: u16 = 1;
pub const K16E_DYNAMIC_VERSION: u16 = 2;
pub const K16E_DYNAMIC_RUNTIME_VERSION: u16 = 3;
pub const K16E_SHARED_OBJECT_VERSION: u16 = 4;
pub const K16E_DYNAMIC_IMPORTS_VERSION: u16 = 5;
pub const K16E_HEADER_SIZE: u16 = 32;
pub const K16E_SECTION_RECORD_SIZE: u32 = 20;
pub const K16E_ISA_K16: u16 = 1;
pub const K16E_SECTION_KIND_LOAD: u32 = 1;
pub const K16E_SECTION_KIND_RELOCATIONS: u32 = 2;
pub const K16E_SECTION_KIND_CPU_HELPER_REQUIREMENT: u32 = 3;
pub const K16E_SECTION_KIND_CPU_HELPER_RELOCATIONS: u32 = 4;
pub const K16E_SECTION_KIND_EXPORTS: u32 = 5;
pub const K16E_SECTION_KIND_NEEDED_LIBRARIES: u32 = 6;
pub const K16E_SECTION_KIND_IMPORT_RELOCATIONS: u32 = 7;
pub const K16E_SECTION_TABLE_OFFSET: u32 = K16E_HEADER_SIZE as u32;
pub const K16E_SECTION_COUNT_SINGLE_LOAD: u32 = 1;
pub const K16E_SECTION_COUNT_DYNAMIC_PROGRAM: u32 = 2;
pub const K16E_SECTION_COUNT_DYNAMIC_PROGRAM_WITH_CPU_HELPERS: u32 = 4;
pub const K16E_SECTION_COUNT_SHARED_OBJECT: u32 = 3;
pub const K16E_SECTION_COUNT_DYNAMIC_PROGRAM_WITH_IMPORTS: u32 = 4;
pub const K16E_PAYLOAD_OFFSET_SINGLE_LOAD: u32 =
    K16E_SECTION_TABLE_OFFSET + K16E_SECTION_RECORD_SIZE;
pub const K16E_PAYLOAD_OFFSET_DYNAMIC_PROGRAM: u32 =
    K16E_SECTION_TABLE_OFFSET + K16E_SECTION_RECORD_SIZE * K16E_SECTION_COUNT_DYNAMIC_PROGRAM;
pub const K16E_PAYLOAD_OFFSET_DYNAMIC_PROGRAM_WITH_CPU_HELPERS: u32 = K16E_SECTION_TABLE_OFFSET
    + K16E_SECTION_RECORD_SIZE * K16E_SECTION_COUNT_DYNAMIC_PROGRAM_WITH_CPU_HELPERS;
pub const K16E_PAYLOAD_OFFSET_SHARED_OBJECT: u32 =
    K16E_SECTION_TABLE_OFFSET + K16E_SECTION_RECORD_SIZE * K16E_SECTION_COUNT_SHARED_OBJECT;
pub const K16E_PAYLOAD_OFFSET_DYNAMIC_PROGRAM_WITH_IMPORTS: u32 = K16E_SECTION_TABLE_OFFSET
    + K16E_SECTION_RECORD_SIZE * K16E_SECTION_COUNT_DYNAMIC_PROGRAM_WITH_IMPORTS;
pub const K16E_RELOCATION_RECORD_SIZE: u32 = 8;
pub const K16E_CPU_HELPER_REQUIREMENT_SIZE: u32 = 8;
pub const K16E_CPU_HELPER_RELOCATION_RECORD_SIZE: u32 = 12;
pub const K16E_SHARED_EXPORT_RECORD_SIZE: u32 = 8;
pub const K16E_IMPORT_RELOCATION_RECORD_SIZE: u32 = 16;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum K16eAbiKind {
    Bootloader,
    Kernel,
    Program,
    SharedObject,
}

impl K16eAbiKind {
    pub fn code(self) -> u32 {
        match self {
            Self::Bootloader => 1,
            Self::Kernel => 2,
            Self::Program => 3,
            Self::SharedObject => 4,
        }
    }

    fn decode(code: u32) -> Result<Self, String> {
        match code {
            1 => Ok(Self::Bootloader),
            2 => Ok(Self::Kernel),
            3 => Ok(Self::Program),
            4 => Ok(Self::SharedObject),
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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct K16eCpuHelperRuntimeRequirement {
    pub abi_version: u32,
    pub helper_table_version: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum K16eCpuHelperRelocationKind {
    Abs32,
    Call32,
}

impl K16eCpuHelperRelocationKind {
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
            _ => Err(format!(
                "unsupported K16E CPU helper relocation kind {code}"
            )),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum K16eCpuHelper {
    HaltOnce,
    WaitOnce,
    YieldOnce,
    IretOnce,
    SaveTrapFrame,
    RestoreTrapFrame,
    WriteTrapVector,
    ReadTrapCause,
    ReadTrapPc,
    ReadTrapValue,
    ReadTrapArg0,
    ReadTrapArg1,
    ReadTrapArg2,
    SyscallOnce,
    Syscall0,
    Syscall1,
    Syscall3,
    IretWithR0,
    WriteInterruptEnable,
    WriteInterruptMask,
    ReadInterruptPending,
    WriteSyscall,
    ReadSyscall,
    OpenSyscall,
    CloseSyscall,
    BrkSyscall,
    SbrkSyscall,
}

impl K16eCpuHelper {
    pub fn code(self) -> u32 {
        match self {
            Self::HaltOnce => 1,
            Self::WaitOnce => 2,
            Self::YieldOnce => 3,
            Self::IretOnce => 4,
            Self::SaveTrapFrame => 5,
            Self::RestoreTrapFrame => 6,
            Self::WriteTrapVector => 7,
            Self::ReadTrapCause => 8,
            Self::ReadTrapPc => 9,
            Self::ReadTrapValue => 10,
            Self::ReadTrapArg0 => 11,
            Self::ReadTrapArg1 => 12,
            Self::ReadTrapArg2 => 13,
            Self::SyscallOnce => 14,
            Self::Syscall0 => 15,
            Self::Syscall1 => 16,
            Self::Syscall3 => 17,
            Self::IretWithR0 => 18,
            Self::WriteInterruptEnable => 19,
            Self::WriteInterruptMask => 20,
            Self::ReadInterruptPending => 21,
            Self::WriteSyscall => 22,
            Self::ReadSyscall => 23,
            Self::OpenSyscall => 24,
            Self::CloseSyscall => 25,
            Self::BrkSyscall => 26,
            Self::SbrkSyscall => 27,
        }
    }

    fn decode(code: u32) -> Result<Self, String> {
        match code {
            1 => Ok(Self::HaltOnce),
            2 => Ok(Self::WaitOnce),
            3 => Ok(Self::YieldOnce),
            4 => Ok(Self::IretOnce),
            5 => Ok(Self::SaveTrapFrame),
            6 => Ok(Self::RestoreTrapFrame),
            7 => Ok(Self::WriteTrapVector),
            8 => Ok(Self::ReadTrapCause),
            9 => Ok(Self::ReadTrapPc),
            10 => Ok(Self::ReadTrapValue),
            11 => Ok(Self::ReadTrapArg0),
            12 => Ok(Self::ReadTrapArg1),
            13 => Ok(Self::ReadTrapArg2),
            14 => Ok(Self::SyscallOnce),
            15 => Ok(Self::Syscall0),
            16 => Ok(Self::Syscall1),
            17 => Ok(Self::Syscall3),
            18 => Ok(Self::IretWithR0),
            19 => Ok(Self::WriteInterruptEnable),
            20 => Ok(Self::WriteInterruptMask),
            21 => Ok(Self::ReadInterruptPending),
            22 => Ok(Self::WriteSyscall),
            23 => Ok(Self::ReadSyscall),
            24 => Ok(Self::OpenSyscall),
            25 => Ok(Self::CloseSyscall),
            26 => Ok(Self::BrkSyscall),
            27 => Ok(Self::SbrkSyscall),
            _ => Err(format!("unsupported K16E CPU helper id {code}")),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct K16eCpuHelperRelocation {
    pub offset: u32,
    pub kind: K16eCpuHelperRelocationKind,
    pub helper: K16eCpuHelper,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DynamicK16Program {
    pub entry_offset: u32,
    pub memory_size: u32,
    pub payload: Vec<u8>,
    pub relocations: Vec<K16eRelocation>,
    pub cpu_helper_runtime: Option<K16eCpuHelperRuntimeRequirement>,
    pub cpu_helper_relocations: Vec<K16eCpuHelperRelocation>,
    pub needed_libraries: Vec<String>,
    pub import_relocations: Vec<K16eImportRelocation>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct K16eSharedExport {
    pub name: String,
    pub offset: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct K16eSharedObject {
    pub memory_size: u32,
    pub payload: Vec<u8>,
    pub exports: Vec<K16eSharedExport>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct K16eImportRelocation {
    pub offset: u32,
    pub kind: K16eRelocationKind,
    pub library_index: u32,
    pub symbol: String,
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
    if abi_kind == K16eAbiKind::SharedObject {
        return Err("shared object ABI kind requires K16E shared object version".to_string());
    }
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

pub fn encode_dynamic_k16_program_with_cpu_helpers(
    payload: &[u8],
    memory_size: u32,
    entry_offset: u32,
    relocations: &[K16eRelocation],
    cpu_helper_runtime: K16eCpuHelperRuntimeRequirement,
    cpu_helper_relocations: &[K16eCpuHelperRelocation],
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
    validate_cpu_helper_relocations(memory_size, cpu_helper_relocations)?;

    let relocation_table_size = u32::try_from(relocations.len())
        .map_err(|_| "K16E relocation table is too large".to_string())?
        .checked_mul(K16E_RELOCATION_RECORD_SIZE)
        .ok_or_else(|| "K16E relocation table size overflows".to_string())?;
    let cpu_helper_relocation_table_size = u32::try_from(cpu_helper_relocations.len())
        .map_err(|_| "K16E CPU helper relocation table is too large".to_string())?
        .checked_mul(K16E_CPU_HELPER_RELOCATION_RECORD_SIZE)
        .ok_or_else(|| "K16E CPU helper relocation table size overflows".to_string())?;
    let relocation_table_offset = K16E_PAYLOAD_OFFSET_DYNAMIC_PROGRAM_WITH_CPU_HELPERS
        .checked_add(payload_size)
        .ok_or_else(|| "K16E relocation table offset overflows".to_string())?;
    let cpu_helper_requirement_offset = relocation_table_offset
        .checked_add(relocation_table_size)
        .ok_or_else(|| "K16E CPU helper requirement offset overflows".to_string())?;
    let cpu_helper_relocation_table_offset = cpu_helper_requirement_offset
        .checked_add(K16E_CPU_HELPER_REQUIREMENT_SIZE)
        .ok_or_else(|| "K16E CPU helper relocation table offset overflows".to_string())?;
    let file_size = cpu_helper_relocation_table_offset
        .checked_add(cpu_helper_relocation_table_size)
        .ok_or_else(|| "K16E file size overflows".to_string())?;
    let capacity =
        usize::try_from(file_size).map_err(|_| "K16E file size does not fit usize".to_string())?;

    let mut bytes = Vec::with_capacity(capacity);
    bytes.extend_from_slice(K16E_MAGIC);
    write_u16(&mut bytes, K16E_DYNAMIC_RUNTIME_VERSION);
    write_u16(&mut bytes, K16E_HEADER_SIZE);
    write_u16(&mut bytes, K16E_ISA_K16);
    write_u16(&mut bytes, 0);
    write_u32(&mut bytes, entry_offset);
    write_u32(&mut bytes, K16E_SECTION_TABLE_OFFSET);
    write_u32(
        &mut bytes,
        K16E_SECTION_COUNT_DYNAMIC_PROGRAM_WITH_CPU_HELPERS,
    );
    write_u32(&mut bytes, K16eAbiKind::Program.code());
    write_u32(&mut bytes, 0);

    write_u32(&mut bytes, K16E_SECTION_KIND_LOAD);
    write_u32(&mut bytes, 0);
    write_u32(
        &mut bytes,
        K16E_PAYLOAD_OFFSET_DYNAMIC_PROGRAM_WITH_CPU_HELPERS,
    );
    write_u32(&mut bytes, payload_size);
    write_u32(&mut bytes, memory_size);

    write_u32(&mut bytes, K16E_SECTION_KIND_RELOCATIONS);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, relocation_table_offset);
    write_u32(&mut bytes, relocation_table_size);
    write_u32(&mut bytes, relocations.len() as u32);

    write_u32(&mut bytes, K16E_SECTION_KIND_CPU_HELPER_REQUIREMENT);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, cpu_helper_requirement_offset);
    write_u32(&mut bytes, K16E_CPU_HELPER_REQUIREMENT_SIZE);
    write_u32(&mut bytes, 1);

    write_u32(&mut bytes, K16E_SECTION_KIND_CPU_HELPER_RELOCATIONS);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, cpu_helper_relocation_table_offset);
    write_u32(&mut bytes, cpu_helper_relocation_table_size);
    write_u32(&mut bytes, cpu_helper_relocations.len() as u32);

    bytes.extend_from_slice(payload);
    for relocation in relocations {
        write_u32(&mut bytes, relocation.offset);
        write_u32(&mut bytes, relocation.kind.code());
    }
    write_u32(&mut bytes, cpu_helper_runtime.abi_version);
    write_u32(&mut bytes, cpu_helper_runtime.helper_table_version);
    for relocation in cpu_helper_relocations {
        write_u32(&mut bytes, relocation.offset);
        write_u32(&mut bytes, relocation.kind.code());
        write_u32(&mut bytes, relocation.helper.code());
    }
    Ok(bytes)
}

pub fn encode_dynamic_k16_program_with_imports(
    payload: &[u8],
    memory_size: u32,
    entry_offset: u32,
    relocations: &[K16eRelocation],
    needed_libraries: &[String],
    import_relocations: &[K16eImportRelocation],
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
    validate_needed_libraries(needed_libraries)?;
    validate_import_relocations(memory_size, needed_libraries.len(), import_relocations)?;

    let relocation_table_size = u32::try_from(relocations.len())
        .map_err(|_| "K16E relocation table is too large".to_string())?
        .checked_mul(K16E_RELOCATION_RECORD_SIZE)
        .ok_or_else(|| "K16E relocation table size overflows".to_string())?;
    let needed_section = encode_string_table(needed_libraries, "needed library")?;
    let needed_section_size = u32::try_from(needed_section.len())
        .map_err(|_| "K16E needed library section is too large".to_string())?;
    let import_section = encode_import_relocation_section(import_relocations)?;
    let import_section_size = u32::try_from(import_section.len())
        .map_err(|_| "K16E import relocation section is too large".to_string())?;

    let relocation_table_offset = K16E_PAYLOAD_OFFSET_DYNAMIC_PROGRAM_WITH_IMPORTS
        .checked_add(payload_size)
        .ok_or_else(|| "K16E relocation table offset overflows".to_string())?;
    let needed_section_offset = relocation_table_offset
        .checked_add(relocation_table_size)
        .ok_or_else(|| "K16E needed library section offset overflows".to_string())?;
    let import_section_offset = needed_section_offset
        .checked_add(needed_section_size)
        .ok_or_else(|| "K16E import relocation section offset overflows".to_string())?;
    let file_size = import_section_offset
        .checked_add(import_section_size)
        .ok_or_else(|| "K16E file size overflows".to_string())?;
    let capacity =
        usize::try_from(file_size).map_err(|_| "K16E file size does not fit usize".to_string())?;

    let mut bytes = Vec::with_capacity(capacity);
    bytes.extend_from_slice(K16E_MAGIC);
    write_u16(&mut bytes, K16E_DYNAMIC_IMPORTS_VERSION);
    write_u16(&mut bytes, K16E_HEADER_SIZE);
    write_u16(&mut bytes, K16E_ISA_K16);
    write_u16(&mut bytes, 0);
    write_u32(&mut bytes, entry_offset);
    write_u32(&mut bytes, K16E_SECTION_TABLE_OFFSET);
    write_u32(&mut bytes, K16E_SECTION_COUNT_DYNAMIC_PROGRAM_WITH_IMPORTS);
    write_u32(&mut bytes, K16eAbiKind::Program.code());
    write_u32(&mut bytes, 0);

    write_u32(&mut bytes, K16E_SECTION_KIND_LOAD);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, K16E_PAYLOAD_OFFSET_DYNAMIC_PROGRAM_WITH_IMPORTS);
    write_u32(&mut bytes, payload_size);
    write_u32(&mut bytes, memory_size);

    write_u32(&mut bytes, K16E_SECTION_KIND_RELOCATIONS);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, relocation_table_offset);
    write_u32(&mut bytes, relocation_table_size);
    write_u32(&mut bytes, relocations.len() as u32);

    write_u32(&mut bytes, K16E_SECTION_KIND_NEEDED_LIBRARIES);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, needed_section_offset);
    write_u32(&mut bytes, needed_section_size);
    write_u32(&mut bytes, needed_libraries.len() as u32);

    write_u32(&mut bytes, K16E_SECTION_KIND_IMPORT_RELOCATIONS);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, import_section_offset);
    write_u32(&mut bytes, import_section_size);
    write_u32(&mut bytes, import_relocations.len() as u32);

    bytes.extend_from_slice(payload);
    for relocation in relocations {
        write_u32(&mut bytes, relocation.offset);
        write_u32(&mut bytes, relocation.kind.code());
    }
    bytes.extend_from_slice(&needed_section);
    bytes.extend_from_slice(&import_section);
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
    if version != K16E_DYNAMIC_VERSION
        && version != K16E_DYNAMIC_RUNTIME_VERSION
        && version != K16E_DYNAMIC_IMPORTS_VERSION
    {
        return Err(format!("unsupported dynamic K16E version {version}"));
    }
    let expected_section_count = match version {
        K16E_DYNAMIC_RUNTIME_VERSION => K16E_SECTION_COUNT_DYNAMIC_PROGRAM_WITH_CPU_HELPERS,
        K16E_DYNAMIC_IMPORTS_VERSION => K16E_SECTION_COUNT_DYNAMIC_PROGRAM_WITH_IMPORTS,
        _ => K16E_SECTION_COUNT_DYNAMIC_PROGRAM,
    };
    let expected_payload_offset = match version {
        K16E_DYNAMIC_RUNTIME_VERSION => K16E_PAYLOAD_OFFSET_DYNAMIC_PROGRAM_WITH_CPU_HELPERS,
        K16E_DYNAMIC_IMPORTS_VERSION => K16E_PAYLOAD_OFFSET_DYNAMIC_PROGRAM_WITH_IMPORTS,
        _ => K16E_PAYLOAD_OFFSET_DYNAMIC_PROGRAM,
    };
    if bytes.len() < expected_payload_offset as usize {
        return Err("K16E file is smaller than the dynamic header".to_string());
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
    if section_count != expected_section_count {
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
    if payload_offset != expected_payload_offset {
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

    let (cpu_helper_runtime, cpu_helper_relocations, needed_libraries, import_relocations) =
        if version == K16E_DYNAMIC_RUNTIME_VERSION {
            let requirement_kind = read_u32(bytes, 72)?;
            if requirement_kind != K16E_SECTION_KIND_CPU_HELPER_REQUIREMENT {
                return Err(format!("unsupported K16E section kind {requirement_kind}"));
            }
            if read_u32(bytes, 76)? != 0 {
                return Err(
                    "dynamic K16E CPU helper requirement section address must be zero".to_string(),
                );
            }
            let requirement_offset = read_u32(bytes, 80)?;
            let requirement_size = read_u32(bytes, 84)?;
            let requirement_count = read_u32(bytes, 88)?;
            if requirement_size != K16E_CPU_HELPER_REQUIREMENT_SIZE || requirement_count != 1 {
                return Err(
                    "K16E CPU helper requirement section must contain one record".to_string(),
                );
            }
            if requirement_offset
                != relocation_table_offset
                    .checked_add(relocation_table_size)
                    .ok_or_else(|| "K16E CPU helper requirement offset overflows".to_string())?
            {
                return Err(format!(
                    "unsupported dynamic K16E CPU helper requirement offset {requirement_offset}"
                ));
            }

            let helper_relocation_kind = read_u32(bytes, 92)?;
            if helper_relocation_kind != K16E_SECTION_KIND_CPU_HELPER_RELOCATIONS {
                return Err(format!(
                    "unsupported K16E section kind {helper_relocation_kind}"
                ));
            }
            if read_u32(bytes, 96)? != 0 {
                return Err(
                    "dynamic K16E CPU helper relocation section address must be zero".to_string(),
                );
            }
            let helper_relocation_table_offset = read_u32(bytes, 100)?;
            let helper_relocation_table_size = read_u32(bytes, 104)?;
            let helper_relocation_count = read_u32(bytes, 108)?;
            let expected_helper_relocation_table_size = helper_relocation_count
                .checked_mul(K16E_CPU_HELPER_RELOCATION_RECORD_SIZE)
                .ok_or_else(|| "K16E CPU helper relocation table size overflows".to_string())?;
            if helper_relocation_table_size != expected_helper_relocation_table_size {
                return Err(
                    "K16E CPU helper relocation table size does not match relocation count"
                        .to_string(),
                );
            }
            if helper_relocation_table_offset
                != requirement_offset
                    .checked_add(requirement_size)
                    .ok_or_else(|| {
                        "K16E CPU helper relocation table offset overflows".to_string()
                    })?
            {
                return Err(format!(
                "unsupported dynamic K16E CPU helper relocation table offset {helper_relocation_table_offset}"
            ));
            }

            let requirement = bytes_slice(
                bytes,
                requirement_offset,
                requirement_size,
                "CPU helper requirement",
            )?;
            let requirement = K16eCpuHelperRuntimeRequirement {
                abi_version: read_u32(requirement, 0)?,
                helper_table_version: read_u32(requirement, 4)?,
            };
            let helper_relocation_table = bytes_slice(
                bytes,
                helper_relocation_table_offset,
                helper_relocation_table_size,
                "CPU helper relocation table",
            )?;
            let mut helper_relocations =
                Vec::with_capacity(usize::try_from(helper_relocation_count).map_err(|_| {
                    "K16E CPU helper relocation count does not fit usize".to_string()
                })?);
            for index in 0..helper_relocation_count {
                let offset = usize::try_from(index * K16E_CPU_HELPER_RELOCATION_RECORD_SIZE)
                    .map_err(|_| {
                        "K16E CPU helper relocation offset does not fit usize".to_string()
                    })?;
                helper_relocations.push(K16eCpuHelperRelocation {
                    offset: read_u32(helper_relocation_table, offset)?,
                    kind: K16eCpuHelperRelocationKind::decode(read_u32(
                        helper_relocation_table,
                        offset + 4,
                    )?)?,
                    helper: K16eCpuHelper::decode(read_u32(helper_relocation_table, offset + 8)?)?,
                });
            }
            validate_cpu_helper_relocations(memory_size, &helper_relocations)?;
            (
                Some(requirement),
                helper_relocations,
                Vec::new(),
                Vec::new(),
            )
        } else if version == K16E_DYNAMIC_IMPORTS_VERSION {
            let needed_kind = read_u32(bytes, 72)?;
            if needed_kind != K16E_SECTION_KIND_NEEDED_LIBRARIES {
                return Err(format!("unsupported K16E section kind {needed_kind}"));
            }
            if read_u32(bytes, 76)? != 0 {
                return Err("dynamic K16E needed library section address must be zero".to_string());
            }
            let needed_section_offset = read_u32(bytes, 80)?;
            let needed_section_size = read_u32(bytes, 84)?;
            let needed_count = read_u32(bytes, 88)?;
            if needed_section_offset
                != relocation_table_offset
                    .checked_add(relocation_table_size)
                    .ok_or_else(|| "K16E needed library section offset overflows".to_string())?
            {
                return Err(format!(
                "unsupported dynamic K16E needed library section offset {needed_section_offset}"
            ));
            }

            let import_kind = read_u32(bytes, 92)?;
            if import_kind != K16E_SECTION_KIND_IMPORT_RELOCATIONS {
                return Err(format!("unsupported K16E section kind {import_kind}"));
            }
            if read_u32(bytes, 96)? != 0 {
                return Err(
                    "dynamic K16E import relocation section address must be zero".to_string(),
                );
            }
            let import_section_offset = read_u32(bytes, 100)?;
            let import_section_size = read_u32(bytes, 104)?;
            let import_count = read_u32(bytes, 108)?;
            if import_section_offset
                != needed_section_offset
                    .checked_add(needed_section_size)
                    .ok_or_else(|| "K16E import relocation section offset overflows".to_string())?
            {
                return Err(format!(
                "unsupported dynamic K16E import relocation section offset {import_section_offset}"
            ));
            }

            let needed_section = bytes_slice(
                bytes,
                needed_section_offset,
                needed_section_size,
                "needed library section",
            )?;
            let needed_libraries =
                decode_counted_strings(needed_section, needed_count, "needed library")?;
            validate_needed_libraries(&needed_libraries)?;

            let import_section = bytes_slice(
                bytes,
                import_section_offset,
                import_section_size,
                "import relocation section",
            )?;
            let import_relocations =
                decode_import_relocation_section(import_section, import_count)?;
            validate_import_relocations(memory_size, needed_libraries.len(), &import_relocations)?;
            (None, Vec::new(), needed_libraries, import_relocations)
        } else {
            (None, Vec::new(), Vec::new(), Vec::new())
        };

    Ok(DynamicK16Program {
        entry_offset,
        memory_size,
        payload,
        relocations,
        cpu_helper_runtime,
        cpu_helper_relocations,
        needed_libraries,
        import_relocations,
    })
}

pub fn encode_k16_shared_object(
    payload: &[u8],
    memory_size: u32,
    exports: &[K16eSharedExport],
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
    validate_shared_exports(memory_size, exports)?;

    let export_section = encode_shared_export_section(exports)?;
    let export_section_size = u32::try_from(export_section.len())
        .map_err(|_| "K16E export section is too large".to_string())?;
    let relocation_table_offset = K16E_PAYLOAD_OFFSET_SHARED_OBJECT
        .checked_add(payload_size)
        .ok_or_else(|| "K16E relocation table offset overflows".to_string())?;
    let export_section_offset = relocation_table_offset;
    let file_size = export_section_offset
        .checked_add(export_section_size)
        .ok_or_else(|| "K16E file size overflows".to_string())?;
    let capacity =
        usize::try_from(file_size).map_err(|_| "K16E file size does not fit usize".to_string())?;

    let mut bytes = Vec::with_capacity(capacity);
    bytes.extend_from_slice(K16E_MAGIC);
    write_u16(&mut bytes, K16E_SHARED_OBJECT_VERSION);
    write_u16(&mut bytes, K16E_HEADER_SIZE);
    write_u16(&mut bytes, K16E_ISA_K16);
    write_u16(&mut bytes, 0);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, K16E_SECTION_TABLE_OFFSET);
    write_u32(&mut bytes, K16E_SECTION_COUNT_SHARED_OBJECT);
    write_u32(&mut bytes, K16eAbiKind::SharedObject.code());
    write_u32(&mut bytes, 0);

    write_u32(&mut bytes, K16E_SECTION_KIND_LOAD);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, K16E_PAYLOAD_OFFSET_SHARED_OBJECT);
    write_u32(&mut bytes, payload_size);
    write_u32(&mut bytes, memory_size);

    write_u32(&mut bytes, K16E_SECTION_KIND_RELOCATIONS);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, relocation_table_offset);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, 0);

    write_u32(&mut bytes, K16E_SECTION_KIND_EXPORTS);
    write_u32(&mut bytes, 0);
    write_u32(&mut bytes, export_section_offset);
    write_u32(&mut bytes, export_section_size);
    write_u32(&mut bytes, exports.len() as u32);

    bytes.extend_from_slice(payload);
    bytes.extend_from_slice(&export_section);
    Ok(bytes)
}

pub fn decode_k16_shared_object(bytes: &[u8]) -> Result<K16eSharedObject, String> {
    let magic = bytes
        .get(0..4)
        .ok_or_else(|| "invalid K16E magic".to_string())?;
    if magic != K16E_MAGIC {
        return Err("invalid K16E magic".to_string());
    }
    if bytes.len() < K16E_PAYLOAD_OFFSET_SHARED_OBJECT as usize {
        return Err("K16E file is smaller than the shared object header".to_string());
    }
    let version = read_u16(bytes, 4)?;
    if version != K16E_SHARED_OBJECT_VERSION {
        return Err(format!("unsupported shared object K16E version {version}"));
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
    if entry_offset != 0 {
        return Err("shared object K16E entry offset must be zero".to_string());
    }
    let section_table_offset = read_u32(bytes, 16)?;
    if section_table_offset != K16E_SECTION_TABLE_OFFSET {
        return Err(format!(
            "unsupported K16E section table offset {section_table_offset}"
        ));
    }
    let section_count = read_u32(bytes, 20)?;
    if section_count != K16E_SECTION_COUNT_SHARED_OBJECT {
        return Err(format!(
            "unsupported shared object K16E section count {section_count}"
        ));
    }
    let abi_kind = K16eAbiKind::decode(read_u32(bytes, 24)?)?;
    if abi_kind != K16eAbiKind::SharedObject {
        return Err(format!(
            "shared object K16E ABI kind {:?} is not a shared object",
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
            "shared object K16E load address must be zero, got {load_addr:#010x}"
        ));
    }
    let payload_offset = read_u32(bytes, 40)?;
    if payload_offset != K16E_PAYLOAD_OFFSET_SHARED_OBJECT {
        return Err(format!(
            "unsupported shared object K16E payload offset {payload_offset}"
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

    let relocation_kind = read_u32(bytes, 52)?;
    if relocation_kind != K16E_SECTION_KIND_RELOCATIONS {
        return Err(format!("unsupported K16E section kind {relocation_kind}"));
    }
    if read_u32(bytes, 56)? != 0 {
        return Err("shared object K16E relocation section address must be zero".to_string());
    }
    let relocation_table_offset = read_u32(bytes, 60)?;
    let relocation_table_size = read_u32(bytes, 64)?;
    let relocation_count = read_u32(bytes, 68)?;
    if relocation_table_size != 0 || relocation_count != 0 {
        return Err("shared object K16E v0 does not support relocations yet".to_string());
    }
    if relocation_table_offset
        != payload_offset
            .checked_add(payload_size)
            .ok_or_else(|| "K16E relocation table offset overflows".to_string())?
    {
        return Err(format!(
            "unsupported shared object K16E relocation table offset {relocation_table_offset}"
        ));
    }

    let export_kind = read_u32(bytes, 72)?;
    if export_kind != K16E_SECTION_KIND_EXPORTS {
        return Err(format!("unsupported K16E section kind {export_kind}"));
    }
    if read_u32(bytes, 76)? != 0 {
        return Err("shared object K16E export section address must be zero".to_string());
    }
    let export_section_offset = read_u32(bytes, 80)?;
    let export_section_size = read_u32(bytes, 84)?;
    let export_count = read_u32(bytes, 88)?;
    if export_section_offset != relocation_table_offset {
        return Err(format!(
            "unsupported shared object K16E export section offset {export_section_offset}"
        ));
    }

    let payload = bytes_slice(bytes, payload_offset, payload_size, "payload")?.to_vec();
    let export_section = bytes_slice(
        bytes,
        export_section_offset,
        export_section_size,
        "export section",
    )?;
    let exports = decode_shared_export_section(export_section, export_count)?;
    validate_shared_exports(memory_size, &exports)?;

    Ok(K16eSharedObject {
        memory_size,
        payload,
        exports,
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

fn validate_cpu_helper_relocations(
    memory_size: u32,
    relocations: &[K16eCpuHelperRelocation],
) -> Result<(), String> {
    for relocation in relocations {
        if relocation.offset % 2 != 0 {
            return Err(format!(
                "K16E CPU helper relocation offset {:#010x} must be 2-byte aligned",
                relocation.offset
            ));
        }
        let relocation_width = match relocation.kind {
            K16eCpuHelperRelocationKind::Abs32 | K16eCpuHelperRelocationKind::Call32 => 4,
        };
        let relocation_end = relocation
            .offset
            .checked_add(relocation_width)
            .ok_or_else(|| "K16E CPU helper relocation range overflows".to_string())?;
        if relocation_end > memory_size {
            return Err(format!(
                "K16E CPU helper relocation range {:#010x}..{:#010x} exceeds dynamic memory size {memory_size:#010x}",
                relocation.offset, relocation_end,
            ));
        }
    }
    Ok(())
}

fn validate_shared_exports(memory_size: u32, exports: &[K16eSharedExport]) -> Result<(), String> {
    if exports.is_empty() {
        return Err("K16E shared object must export at least one symbol".to_string());
    }
    for export in exports {
        if export.name.is_empty() {
            return Err("K16E shared export name is empty".to_string());
        }
        if export.name.as_bytes().contains(&0) {
            return Err(format!(
                "K16E shared export `{}` contains a NUL byte",
                export.name
            ));
        }
        if export.offset % 2 != 0 {
            return Err(format!(
                "K16E shared export `{}` offset {:#010x} must be 2-byte aligned",
                export.name, export.offset
            ));
        }
        if export.offset >= memory_size {
            return Err(format!(
                "K16E shared export `{}` offset {:#010x} is outside shared object memory size {memory_size:#010x}",
                export.name, export.offset,
            ));
        }
    }
    Ok(())
}

fn validate_needed_libraries(needed_libraries: &[String]) -> Result<(), String> {
    if needed_libraries.is_empty() {
        return Err("K16E dynamic imports require at least one needed library".to_string());
    }
    for library in needed_libraries {
        validate_non_empty_nul_free_name(library, "needed library")?;
    }
    Ok(())
}

fn validate_import_relocations(
    memory_size: u32,
    library_count: usize,
    relocations: &[K16eImportRelocation],
) -> Result<(), String> {
    if relocations.is_empty() {
        return Err("K16E dynamic imports require at least one import relocation".to_string());
    }
    for relocation in relocations {
        if usize::try_from(relocation.library_index)
            .map_err(|_| "K16E import relocation library index is too large".to_string())?
            >= library_count
        {
            return Err(format!(
                "K16E import relocation library index {} is out of bounds",
                relocation.library_index
            ));
        }
        validate_non_empty_nul_free_name(&relocation.symbol, "import symbol")?;
        if relocation.offset % 2 != 0 {
            return Err(format!(
                "K16E import relocation offset {:#010x} must be 2-byte aligned",
                relocation.offset
            ));
        }
        let relocation_width = match relocation.kind {
            K16eRelocationKind::Abs32 | K16eRelocationKind::Call32 => 4,
        };
        let relocation_end = relocation
            .offset
            .checked_add(relocation_width)
            .ok_or_else(|| "K16E import relocation range overflows".to_string())?;
        if relocation_end > memory_size {
            return Err(format!(
                "K16E import relocation range {:#010x}..{:#010x} exceeds dynamic memory size {memory_size:#010x}",
                relocation.offset, relocation_end,
            ));
        }
    }
    Ok(())
}

fn validate_non_empty_nul_free_name(name: &str, kind: &str) -> Result<(), String> {
    if name.is_empty() {
        return Err(format!("K16E {kind} name is empty"));
    }
    if name.as_bytes().contains(&0) {
        return Err(format!("K16E {kind} `{name}` contains a NUL byte"));
    }
    Ok(())
}

fn encode_shared_export_section(exports: &[K16eSharedExport]) -> Result<Vec<u8>, String> {
    let record_bytes = u32::try_from(exports.len())
        .map_err(|_| "K16E shared export count does not fit u32".to_string())?
        .checked_mul(K16E_SHARED_EXPORT_RECORD_SIZE)
        .ok_or_else(|| "K16E shared export record table size overflows".to_string())?;
    let mut names = Vec::new();
    let mut records = Vec::new();
    for export in exports {
        let name_offset = u32::try_from(names.len())
            .map_err(|_| "K16E shared export name table is too large".to_string())?;
        records.push((name_offset, export.offset));
        names.extend_from_slice(export.name.as_bytes());
        names.push(0);
    }

    let capacity = usize::try_from(record_bytes)
        .map_err(|_| "K16E shared export record table is too large".to_string())?
        .checked_add(names.len())
        .and_then(|value| value.checked_add(1))
        .ok_or_else(|| "K16E shared export section size overflows".to_string())?;
    let mut section = Vec::with_capacity(capacity);
    for (name_offset, export_offset) in records {
        write_u32(&mut section, name_offset);
        write_u32(&mut section, export_offset);
    }
    section.extend_from_slice(&names);
    if section.len() % 2 != 0 {
        section.push(0);
    }
    Ok(section)
}

fn encode_string_table(names: &[String], kind: &str) -> Result<Vec<u8>, String> {
    let mut section = Vec::new();
    for name in names {
        validate_non_empty_nul_free_name(name, kind)?;
        section.extend_from_slice(name.as_bytes());
        section.push(0);
    }
    if section.len() % 2 != 0 {
        section.push(0);
    }
    Ok(section)
}

fn decode_counted_strings(section: &[u8], count: u32, kind: &str) -> Result<Vec<String>, String> {
    let mut names = Vec::with_capacity(
        usize::try_from(count).map_err(|_| format!("K16E {kind} count does not fit usize"))?,
    );
    let mut cursor = 0usize;
    for _ in 0..count {
        let tail = section
            .get(cursor..)
            .ok_or_else(|| format!("K16E {kind} string table is truncated"))?;
        let end = tail
            .iter()
            .position(|byte| *byte == 0)
            .ok_or_else(|| format!("K16E {kind} name is not NUL-terminated"))?;
        let name = std::str::from_utf8(&tail[..end])
            .map_err(|_| format!("K16E {kind} name is not UTF-8"))?
            .to_string();
        validate_non_empty_nul_free_name(&name, kind)?;
        names.push(name);
        cursor = cursor
            .checked_add(end)
            .and_then(|value| value.checked_add(1))
            .ok_or_else(|| format!("K16E {kind} string table cursor overflows"))?;
    }
    Ok(names)
}

fn encode_import_relocation_section(
    relocations: &[K16eImportRelocation],
) -> Result<Vec<u8>, String> {
    let record_bytes = u32::try_from(relocations.len())
        .map_err(|_| "K16E import relocation count does not fit u32".to_string())?
        .checked_mul(K16E_IMPORT_RELOCATION_RECORD_SIZE)
        .ok_or_else(|| "K16E import relocation record table size overflows".to_string())?;
    let mut names = Vec::new();
    let mut records = Vec::new();
    for relocation in relocations {
        let symbol_offset = u32::try_from(names.len())
            .map_err(|_| "K16E import relocation symbol table is too large".to_string())?;
        records.push((
            relocation.offset,
            relocation.kind,
            relocation.library_index,
            symbol_offset,
        ));
        names.extend_from_slice(relocation.symbol.as_bytes());
        names.push(0);
    }

    let capacity = usize::try_from(record_bytes)
        .map_err(|_| "K16E import relocation record table is too large".to_string())?
        .checked_add(names.len())
        .and_then(|value| value.checked_add(1))
        .ok_or_else(|| "K16E import relocation section size overflows".to_string())?;
    let mut section = Vec::with_capacity(capacity);
    for (offset, kind, library_index, symbol_offset) in records {
        write_u32(&mut section, offset);
        write_u32(&mut section, kind.code());
        write_u32(&mut section, library_index);
        write_u32(&mut section, symbol_offset);
    }
    section.extend_from_slice(&names);
    if section.len() % 2 != 0 {
        section.push(0);
    }
    Ok(section)
}

fn decode_import_relocation_section(
    section: &[u8],
    relocation_count: u32,
) -> Result<Vec<K16eImportRelocation>, String> {
    let record_bytes = relocation_count
        .checked_mul(K16E_IMPORT_RELOCATION_RECORD_SIZE)
        .ok_or_else(|| "K16E import relocation record table size overflows".to_string())?;
    let record_bytes = usize::try_from(record_bytes)
        .map_err(|_| "K16E import relocation record table is too large".to_string())?;
    if section.len() < record_bytes {
        return Err("K16E import relocation section is smaller than its record table".to_string());
    }
    let string_table = &section[record_bytes..];
    let mut relocations = Vec::with_capacity(
        usize::try_from(relocation_count)
            .map_err(|_| "K16E import relocation count does not fit usize".to_string())?,
    );
    for index in 0..relocation_count {
        let record_offset = usize::try_from(index * K16E_IMPORT_RELOCATION_RECORD_SIZE)
            .map_err(|_| "K16E import relocation record offset does not fit usize".to_string())?;
        let symbol_offset = read_u32(section, record_offset + 12)?;
        let symbol_offset = usize::try_from(symbol_offset)
            .map_err(|_| "K16E import relocation symbol offset is too large".to_string())?;
        let symbol_tail = string_table
            .get(symbol_offset..)
            .ok_or_else(|| "K16E import relocation symbol offset is out of bounds".to_string())?;
        let symbol_end = symbol_tail
            .iter()
            .position(|byte| *byte == 0)
            .ok_or_else(|| "K16E import relocation symbol is not NUL-terminated".to_string())?;
        let symbol = std::str::from_utf8(&symbol_tail[..symbol_end])
            .map_err(|_| "K16E import relocation symbol is not UTF-8".to_string())?
            .to_string();
        relocations.push(K16eImportRelocation {
            offset: read_u32(section, record_offset)?,
            kind: K16eRelocationKind::decode(read_u32(section, record_offset + 4)?)?,
            library_index: read_u32(section, record_offset + 8)?,
            symbol,
        });
    }
    Ok(relocations)
}

fn decode_shared_export_section(
    section: &[u8],
    export_count: u32,
) -> Result<Vec<K16eSharedExport>, String> {
    let record_bytes = export_count
        .checked_mul(K16E_SHARED_EXPORT_RECORD_SIZE)
        .ok_or_else(|| "K16E shared export record table size overflows".to_string())?;
    let record_bytes = usize::try_from(record_bytes)
        .map_err(|_| "K16E shared export record table is too large".to_string())?;
    if section.len() < record_bytes {
        return Err("K16E shared export section is smaller than its record table".to_string());
    }
    let string_table = &section[record_bytes..];
    let mut exports = Vec::with_capacity(
        usize::try_from(export_count)
            .map_err(|_| "K16E shared export count does not fit usize".to_string())?,
    );
    for index in 0..export_count {
        let record_offset = usize::try_from(index * K16E_SHARED_EXPORT_RECORD_SIZE)
            .map_err(|_| "K16E shared export record offset does not fit usize".to_string())?;
        let name_offset = read_u32(section, record_offset)?;
        let export_offset = read_u32(section, record_offset + 4)?;
        let name_offset = usize::try_from(name_offset)
            .map_err(|_| "K16E shared export name offset is too large".to_string())?;
        let name_tail = string_table
            .get(name_offset..)
            .ok_or_else(|| "K16E shared export name offset is out of bounds".to_string())?;
        let name_end = name_tail
            .iter()
            .position(|byte| *byte == 0)
            .ok_or_else(|| "K16E shared export name is not NUL-terminated".to_string())?;
        let name = std::str::from_utf8(&name_tail[..name_end])
            .map_err(|_| "K16E shared export name is not UTF-8".to_string())?
            .to_string();
        exports.push(K16eSharedExport {
            name,
            offset: export_offset,
        });
    }
    Ok(exports)
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
