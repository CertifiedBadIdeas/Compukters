#![no_std]

pub const FIXED_K16E_V1_HEADER_SIZE: u32 = 52;
pub const FIXED_K16E_V1_PAYLOAD_OFFSET: u32 = 52;
pub const DYNAMIC_K16E_V2_HEADER_SIZE: u32 = 72;
pub const DYNAMIC_K16E_V2_PAYLOAD_OFFSET: u32 = 72;
pub const SHARED_K16E_V4_HEADER_SIZE: u32 = 92;
pub const SHARED_K16E_V4_PAYLOAD_OFFSET: u32 = 92;
pub const DYNAMIC_K16E_V5_HEADER_SIZE: u32 = 112;
pub const DYNAMIC_K16E_V5_PAYLOAD_OFFSET: u32 = 112;
pub const K16E_RELOCATION_RECORD_SIZE: u32 = 8;
pub const K16E_SHARED_EXPORT_RECORD_SIZE: u32 = 8;
pub const K16E_IMPORT_RELOCATION_RECORD_SIZE: u32 = 16;

const K16E_HEADER_SIZE: u16 = 32;
const K16E_ISA_K16: u16 = 1;
const K16E_SECTION_TABLE_OFFSET: u32 = 32;
const K16E_SECTION_KIND_LOAD: u32 = 1;
const K16E_SECTION_KIND_RELOCATIONS: u32 = 2;
const K16E_SECTION_KIND_EXPORTS: u32 = 5;
const K16E_SECTION_KIND_NEEDED_LIBRARIES: u32 = 6;
const K16E_SECTION_KIND_IMPORT_RELOCATIONS: u32 = 7;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum K16eAbiKind {
    Bootloader = 1,
    Kernel = 2,
    Program = 3,
    SharedObject = 4,
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
pub struct K16eImportRelocation<'a> {
    pub offset: u32,
    pub kind: K16eRelocationKind,
    pub library_index: u32,
    pub symbol: &'a [u8],
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct K16eSharedExport<'a> {
    pub name: &'a [u8],
    pub offset: u32,
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
pub struct DynamicK16ImportedImageHeader<'a> {
    pub entry_offset: u32,
    pub payload_offset: u32,
    pub file_size: u32,
    pub memory_size: u32,
    pub relocation_table_offset: u32,
    pub relocation_count: u32,
    pub needed_library_count: u32,
    pub import_relocation_count: u32,
    relocation_table: &'a [u8],
    needed_libraries: &'a [u8],
    import_relocations: &'a [u8],
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct SharedK16ImageHeader<'a> {
    pub payload_offset: u32,
    pub file_size: u32,
    pub memory_size: u32,
    pub export_count: u32,
    export_section: &'a [u8],
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
        relocation_from_table(self.relocation_table, self.relocation_count, index)
    }
}

impl<'a> DynamicK16ImportedImageHeader<'a> {
    pub fn relocation(&self, index: u32) -> Option<K16eRelocation> {
        relocation_from_table(self.relocation_table, self.relocation_count, index)
    }

    pub fn needed_library(&self, index: u32) -> Option<&'a [u8]> {
        counted_string(self.needed_libraries, self.needed_library_count, index)
    }

    pub fn import_relocation(&self, index: u32) -> Option<K16eImportRelocation<'a>> {
        if index >= self.import_relocation_count {
            return None;
        }
        let record_offset = index.checked_mul(K16E_IMPORT_RELOCATION_RECORD_SIZE)?;
        let record_offset = usize::try_from(record_offset).ok()?;
        let record_bytes = self
            .import_relocation_count
            .checked_mul(K16E_IMPORT_RELOCATION_RECORD_SIZE)?;
        let record_bytes = usize::try_from(record_bytes).ok()?;
        let string_table = self.import_relocations.get(record_bytes..)?;
        let symbol_offset = read_u32_le(self.import_relocations, record_offset + 12).ok()?;
        Some(K16eImportRelocation {
            offset: read_u32_le(self.import_relocations, record_offset).ok()?,
            kind: decode_relocation_kind(
                read_u32_le(self.import_relocations, record_offset + 4).ok()?,
            )
            .ok()?,
            library_index: read_u32_le(self.import_relocations, record_offset + 8).ok()?,
            symbol: nul_terminated_string_at(string_table, symbol_offset)?,
        })
    }
}

impl<'a> SharedK16ImageHeader<'a> {
    pub fn export(&self, index: u32) -> Option<K16eSharedExport<'a>> {
        if index >= self.export_count {
            return None;
        }
        let record_offset = index.checked_mul(K16E_SHARED_EXPORT_RECORD_SIZE)?;
        let record_offset = usize::try_from(record_offset).ok()?;
        let record_bytes = self
            .export_count
            .checked_mul(K16E_SHARED_EXPORT_RECORD_SIZE)?;
        let record_bytes = usize::try_from(record_bytes).ok()?;
        let string_table = self.export_section.get(record_bytes..)?;
        let name_offset = read_u32_le(self.export_section, record_offset).ok()?;
        Some(K16eSharedExport {
            name: nul_terminated_string_at(string_table, name_offset)?,
            offset: read_u32_le(self.export_section, record_offset + 4).ok()?,
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

pub fn parse_dynamic_k16e_v5(
    image: &[u8],
) -> Result<DynamicK16ImportedImageHeader<'_>, K16ImageError> {
    if image.len() < DYNAMIC_K16E_V5_HEADER_SIZE as usize
        || header_bytes(image, 0, b"K16E").is_err()
        || read_u16_le(image, 4)? != 5
        || read_u16_le(image, 6)? != K16E_HEADER_SIZE
        || read_u16_le(image, 8)? != K16E_ISA_K16
        || read_u16_le(image, 10)? != 0
        || read_u32_le(image, 16)? != K16E_SECTION_TABLE_OFFSET
        || read_u32_le(image, 20)? != 4
        || read_u32_le(image, 24)? != K16eAbiKind::Program as u32
        || read_u32_le(image, 28)? != 0
        || read_u32_le(image, 32)? != K16E_SECTION_KIND_LOAD
        || read_u32_le(image, 36)? != 0
        || read_u32_le(image, 40)? != DYNAMIC_K16E_V5_PAYLOAD_OFFSET
        || read_u32_le(image, 52)? != K16E_SECTION_KIND_RELOCATIONS
        || read_u32_le(image, 56)? != 0
        || read_u32_le(image, 72)? != K16E_SECTION_KIND_NEEDED_LIBRARIES
        || read_u32_le(image, 76)? != 0
        || read_u32_le(image, 92)? != K16E_SECTION_KIND_IMPORT_RELOCATIONS
        || read_u32_le(image, 96)? != 0
    {
        return Err(K16ImageError::InvalidExecutable);
    }

    let entry_offset = read_u32_le(image, 12)?;
    let file_size = read_u32_le(image, 44)?;
    let memory_size = read_u32_le(image, 48)?;
    validate_dynamic_image_parts(entry_offset, file_size, memory_size)?;

    let relocation_table_offset = read_u32_le(image, 60)?;
    let relocation_table_size = read_u32_le(image, 64)?;
    let relocation_count = read_u32_le(image, 68)?;
    let needed_section_offset = read_u32_le(image, 80)?;
    let needed_section_size = read_u32_le(image, 84)?;
    let needed_library_count = read_u32_le(image, 88)?;
    let import_section_offset = read_u32_le(image, 100)?;
    let import_section_size = read_u32_le(image, 104)?;
    let import_relocation_count = read_u32_le(image, 108)?;

    let payload_end = checked_add(DYNAMIC_K16E_V5_PAYLOAD_OFFSET, file_size)?;
    if relocation_table_offset != payload_end {
        return Err(K16ImageError::InvalidExecutable);
    }
    let expected_relocation_table_size =
        checked_mul(relocation_count, K16E_RELOCATION_RECORD_SIZE)?;
    if relocation_table_size != expected_relocation_table_size {
        return Err(K16ImageError::InvalidExecutable);
    }
    let relocation_table_end = checked_add(relocation_table_offset, relocation_table_size)?;
    if needed_section_offset != relocation_table_end {
        return Err(K16ImageError::InvalidExecutable);
    }
    let needed_section_end = checked_add(needed_section_offset, needed_section_size)?;
    if import_section_offset != needed_section_end {
        return Err(K16ImageError::InvalidExecutable);
    }
    let expected_import_section_record_size =
        checked_mul(import_relocation_count, K16E_IMPORT_RELOCATION_RECORD_SIZE)?;
    if import_section_size < expected_import_section_record_size {
        return Err(K16ImageError::InvalidExecutable);
    }
    let import_section_end = checked_add(import_section_offset, import_section_size)?;
    if import_section_end > image.len() as u32 {
        return Err(K16ImageError::InvalidExecutable);
    }

    let relocation_table = image_slice(image, relocation_table_offset, relocation_table_size)?;
    let needed_libraries = image_slice(image, needed_section_offset, needed_section_size)?;
    let import_relocations = image_slice(image, import_section_offset, import_section_size)?;
    validate_dynamic_relocations(memory_size, relocation_table, relocation_count)?;
    validate_counted_strings(needed_libraries, needed_library_count)?;
    validate_import_relocations(
        memory_size,
        needed_library_count,
        import_relocations,
        import_relocation_count,
    )?;

    Ok(DynamicK16ImportedImageHeader {
        entry_offset,
        payload_offset: DYNAMIC_K16E_V5_PAYLOAD_OFFSET,
        file_size,
        memory_size,
        relocation_table_offset,
        relocation_count,
        needed_library_count,
        import_relocation_count,
        relocation_table,
        needed_libraries,
        import_relocations,
    })
}

pub fn parse_shared_k16e_v4(image: &[u8]) -> Result<SharedK16ImageHeader<'_>, K16ImageError> {
    if image.len() < SHARED_K16E_V4_HEADER_SIZE as usize
        || header_bytes(image, 0, b"K16E").is_err()
        || read_u16_le(image, 4)? != 4
        || read_u16_le(image, 6)? != K16E_HEADER_SIZE
        || read_u16_le(image, 8)? != K16E_ISA_K16
        || read_u16_le(image, 10)? != 0
        || read_u32_le(image, 12)? != 0
        || read_u32_le(image, 16)? != K16E_SECTION_TABLE_OFFSET
        || read_u32_le(image, 20)? != 3
        || read_u32_le(image, 24)? != K16eAbiKind::SharedObject as u32
        || read_u32_le(image, 28)? != 0
        || read_u32_le(image, 32)? != K16E_SECTION_KIND_LOAD
        || read_u32_le(image, 36)? != 0
        || read_u32_le(image, 40)? != SHARED_K16E_V4_PAYLOAD_OFFSET
        || read_u32_le(image, 52)? != K16E_SECTION_KIND_RELOCATIONS
        || read_u32_le(image, 56)? != 0
        || read_u32_le(image, 72)? != K16E_SECTION_KIND_EXPORTS
        || read_u32_le(image, 76)? != 0
    {
        return Err(K16ImageError::InvalidExecutable);
    }

    let file_size = read_u32_le(image, 44)?;
    let memory_size = read_u32_le(image, 48)?;
    if file_size == 0 || memory_size < file_size || file_size % 2 != 0 || memory_size % 2 != 0 {
        return Err(K16ImageError::InvalidExecutable);
    }

    let relocation_table_offset = read_u32_le(image, 60)?;
    let relocation_table_size = read_u32_le(image, 64)?;
    let relocation_count = read_u32_le(image, 68)?;
    let payload_end = checked_add(SHARED_K16E_V4_PAYLOAD_OFFSET, file_size)?;
    if relocation_table_offset != payload_end || relocation_table_size != 0 || relocation_count != 0
    {
        return Err(K16ImageError::InvalidExecutable);
    }

    let export_section_offset = read_u32_le(image, 80)?;
    let export_section_size = read_u32_le(image, 84)?;
    let export_count = read_u32_le(image, 88)?;
    if export_count == 0 || export_section_offset != relocation_table_offset {
        return Err(K16ImageError::InvalidExecutable);
    }
    let export_section_end = checked_add(export_section_offset, export_section_size)?;
    if export_section_end > image.len() as u32 {
        return Err(K16ImageError::InvalidExecutable);
    }
    let expected_export_record_size = checked_mul(export_count, K16E_SHARED_EXPORT_RECORD_SIZE)?;
    if export_section_size < expected_export_record_size {
        return Err(K16ImageError::InvalidExecutable);
    }

    let export_section = image_slice(image, export_section_offset, export_section_size)?;
    validate_shared_exports(memory_size, export_section, export_count)?;
    Ok(SharedK16ImageHeader {
        payload_offset: SHARED_K16E_V4_PAYLOAD_OFFSET,
        file_size,
        memory_size,
        export_count,
        export_section,
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

fn validate_counted_strings(section: &[u8], count: u32) -> Result<(), K16ImageError> {
    let mut index = 0;
    while index < count {
        let name = counted_string(section, count, index).ok_or(K16ImageError::InvalidExecutable)?;
        validate_non_empty_name(name)?;
        index += 1;
    }
    Ok(())
}

fn validate_import_relocations(
    memory_size: u32,
    library_count: u32,
    section: &[u8],
    import_relocation_count: u32,
) -> Result<(), K16ImageError> {
    if library_count == 0 || import_relocation_count == 0 {
        return Err(K16ImageError::InvalidExecutable);
    }
    let record_bytes = checked_mul(import_relocation_count, K16E_IMPORT_RELOCATION_RECORD_SIZE)?;
    if section.len() < record_bytes as usize {
        return Err(K16ImageError::InvalidExecutable);
    }
    let mut index = 0;
    while index < import_relocation_count {
        let relocation = import_relocation_from_table(section, import_relocation_count, index)
            .ok_or(K16ImageError::InvalidExecutable)?;
        if relocation.library_index >= library_count {
            return Err(K16ImageError::InvalidExecutable);
        }
        validate_non_empty_name(relocation.symbol)?;
        validate_dynamic_relocation(
            memory_size,
            K16eRelocation {
                offset: relocation.offset,
                kind: relocation.kind,
            },
        )?;
        index += 1;
    }
    Ok(())
}

fn validate_shared_exports(
    memory_size: u32,
    section: &[u8],
    export_count: u32,
) -> Result<(), K16ImageError> {
    let record_bytes = checked_mul(export_count, K16E_SHARED_EXPORT_RECORD_SIZE)?;
    if section.len() < record_bytes as usize {
        return Err(K16ImageError::InvalidExecutable);
    }
    let mut index = 0;
    while index < export_count {
        let export = shared_export_from_table(section, export_count, index)
            .ok_or(K16ImageError::InvalidExecutable)?;
        validate_non_empty_name(export.name)?;
        if export.offset % 2 != 0 || export.offset >= memory_size {
            return Err(K16ImageError::InvalidExecutable);
        }
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

fn validate_non_empty_name(name: &[u8]) -> Result<(), K16ImageError> {
    if name.is_empty() || name.contains(&0) {
        Err(K16ImageError::InvalidExecutable)
    } else {
        Ok(())
    }
}

fn validate_dynamic_image_parts(
    entry_offset: u32,
    file_size: u32,
    memory_size: u32,
) -> Result<(), K16ImageError> {
    if file_size == 0 || memory_size < file_size || file_size % 2 != 0 || memory_size % 2 != 0 {
        return Err(K16ImageError::InvalidExecutable);
    }
    if entry_offset >= memory_size || entry_offset % 2 != 0 {
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

fn relocation_from_table(
    relocation_table: &[u8],
    relocation_count: u32,
    index: u32,
) -> Option<K16eRelocation> {
    if index >= relocation_count {
        return None;
    }
    let offset = index.checked_mul(K16E_RELOCATION_RECORD_SIZE)?;
    let offset = usize::try_from(offset).ok()?;
    Some(K16eRelocation {
        offset: read_u32_le(relocation_table, offset).ok()?,
        kind: decode_relocation_kind(read_u32_le(relocation_table, offset + 4).ok()?).ok()?,
    })
}

fn import_relocation_from_table<'a>(
    section: &'a [u8],
    import_relocation_count: u32,
    index: u32,
) -> Option<K16eImportRelocation<'a>> {
    if index >= import_relocation_count {
        return None;
    }
    let record_offset = index.checked_mul(K16E_IMPORT_RELOCATION_RECORD_SIZE)?;
    let record_offset = usize::try_from(record_offset).ok()?;
    let record_bytes = import_relocation_count.checked_mul(K16E_IMPORT_RELOCATION_RECORD_SIZE)?;
    let record_bytes = usize::try_from(record_bytes).ok()?;
    let string_table = section.get(record_bytes..)?;
    let symbol_offset = read_u32_le(section, record_offset + 12).ok()?;
    Some(K16eImportRelocation {
        offset: read_u32_le(section, record_offset).ok()?,
        kind: decode_relocation_kind(read_u32_le(section, record_offset + 4).ok()?).ok()?,
        library_index: read_u32_le(section, record_offset + 8).ok()?,
        symbol: nul_terminated_string_at(string_table, symbol_offset)?,
    })
}

fn shared_export_from_table<'a>(
    section: &'a [u8],
    export_count: u32,
    index: u32,
) -> Option<K16eSharedExport<'a>> {
    if index >= export_count {
        return None;
    }
    let record_offset = index.checked_mul(K16E_SHARED_EXPORT_RECORD_SIZE)?;
    let record_offset = usize::try_from(record_offset).ok()?;
    let record_bytes = export_count.checked_mul(K16E_SHARED_EXPORT_RECORD_SIZE)?;
    let record_bytes = usize::try_from(record_bytes).ok()?;
    let string_table = section.get(record_bytes..)?;
    let name_offset = read_u32_le(section, record_offset).ok()?;
    Some(K16eSharedExport {
        name: nul_terminated_string_at(string_table, name_offset)?,
        offset: read_u32_le(section, record_offset + 4).ok()?,
    })
}

fn counted_string(section: &[u8], count: u32, index: u32) -> Option<&[u8]> {
    if index >= count {
        return None;
    }
    let mut cursor = 0usize;
    let mut current = 0;
    while current <= index {
        let tail = section.get(cursor..)?;
        let end = tail.iter().position(|byte| *byte == 0)?;
        if current == index {
            return Some(&tail[..end]);
        }
        cursor = cursor.checked_add(end)?.checked_add(1)?;
        current += 1;
    }
    None
}

fn nul_terminated_string_at(section: &[u8], offset: u32) -> Option<&[u8]> {
    let offset = usize::try_from(offset).ok()?;
    let tail = section.get(offset..)?;
    let end = tail.iter().position(|byte| *byte == 0)?;
    Some(&tail[..end])
}

fn image_slice(image: &[u8], offset: u32, size: u32) -> Result<&[u8], K16ImageError> {
    let start = usize::try_from(offset).map_err(|_| K16ImageError::InvalidExecutable)?;
    let size = usize::try_from(size).map_err(|_| K16ImageError::InvalidExecutable)?;
    let end = start
        .checked_add(size)
        .ok_or(K16ImageError::InvalidExecutable)?;
    image
        .get(start..end)
        .ok_or(K16ImageError::InvalidExecutable)
}

fn checked_add(left: u32, right: u32) -> Result<u32, K16ImageError> {
    left.checked_add(right)
        .ok_or(K16ImageError::InvalidExecutable)
}

fn checked_mul(left: u32, right: u32) -> Result<u32, K16ImageError> {
    left.checked_mul(right)
        .ok_or(K16ImageError::InvalidExecutable)
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

    fn dynamic_import_program_image() -> [u8; 154] {
        let mut bytes = [0u8; 154];
        bytes[0..4].copy_from_slice(b"K16E");
        write_u16_le(&mut bytes, 4, 5);
        write_u16_le(&mut bytes, 6, 32);
        write_u16_le(&mut bytes, 8, 1);
        write_u16_le(&mut bytes, 10, 0);
        write_u32_le(&mut bytes, 12, 2);
        write_u32_le(&mut bytes, 16, 32);
        write_u32_le(&mut bytes, 20, 4);
        write_u32_le(&mut bytes, 24, K16eAbiKind::Program as u32);
        write_u32_le(&mut bytes, 28, 0);
        write_u32_le(&mut bytes, 32, 1);
        write_u32_le(&mut bytes, 36, 0);
        write_u32_le(&mut bytes, 40, 112);
        write_u32_le(&mut bytes, 44, 8);
        write_u32_le(&mut bytes, 48, 12);
        write_u32_le(&mut bytes, 52, 2);
        write_u32_le(&mut bytes, 56, 0);
        write_u32_le(&mut bytes, 60, 120);
        write_u32_le(&mut bytes, 64, 0);
        write_u32_le(&mut bytes, 68, 0);
        write_u32_le(&mut bytes, 72, 6);
        write_u32_le(&mut bytes, 76, 0);
        write_u32_le(&mut bytes, 80, 120);
        write_u32_le(&mut bytes, 84, 14);
        write_u32_le(&mut bytes, 88, 1);
        write_u32_le(&mut bytes, 92, 7);
        write_u32_le(&mut bytes, 96, 0);
        write_u32_le(&mut bytes, 100, 134);
        write_u32_le(&mut bytes, 104, 20);
        write_u32_le(&mut bytes, 108, 1);
        bytes[112..120].copy_from_slice(&[0x01, 0xe1, 0, 0, 0, 0, 0, 0x90]);
        bytes[120..133].copy_from_slice(b"libfoo.k16so\0");
        write_u32_le(&mut bytes, 134, 4);
        write_u32_le(&mut bytes, 138, 2);
        write_u32_le(&mut bytes, 142, 0);
        write_u32_le(&mut bytes, 146, 0);
        bytes[150..154].copy_from_slice(b"foo\0");
        bytes
    }

    fn shared_object_image() -> [u8; 112] {
        let mut bytes = [0u8; 112];
        bytes[0..4].copy_from_slice(b"K16E");
        write_u16_le(&mut bytes, 4, 4);
        write_u16_le(&mut bytes, 6, 32);
        write_u16_le(&mut bytes, 8, 1);
        write_u16_le(&mut bytes, 10, 0);
        write_u32_le(&mut bytes, 12, 0);
        write_u32_le(&mut bytes, 16, 32);
        write_u32_le(&mut bytes, 20, 3);
        write_u32_le(&mut bytes, 24, 4);
        write_u32_le(&mut bytes, 28, 0);
        write_u32_le(&mut bytes, 32, 1);
        write_u32_le(&mut bytes, 36, 0);
        write_u32_le(&mut bytes, 40, 92);
        write_u32_le(&mut bytes, 44, 8);
        write_u32_le(&mut bytes, 48, 12);
        write_u32_le(&mut bytes, 52, 2);
        write_u32_le(&mut bytes, 56, 0);
        write_u32_le(&mut bytes, 60, 100);
        write_u32_le(&mut bytes, 64, 0);
        write_u32_le(&mut bytes, 68, 0);
        write_u32_le(&mut bytes, 72, 5);
        write_u32_le(&mut bytes, 76, 0);
        write_u32_le(&mut bytes, 80, 100);
        write_u32_le(&mut bytes, 84, 12);
        write_u32_le(&mut bytes, 88, 1);
        bytes[92..100].copy_from_slice(&[0x01, 0xe1, 0, 0, 0, 0, 0, 0x90]);
        write_u32_le(&mut bytes, 100, 0);
        write_u32_le(&mut bytes, 104, 2);
        bytes[108..112].copy_from_slice(b"foo\0");
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
    fn dynamic_k16e_v5_parser_exposes_needed_libraries_and_import_relocations() {
        let image = dynamic_import_program_image();

        let header = parse_dynamic_k16e_v5(&image).expect("dynamic import header parses");

        assert_eq!(header.entry_offset, 2);
        assert_eq!(header.payload_offset, 112);
        assert_eq!(header.file_size, 8);
        assert_eq!(header.memory_size, 12);
        assert_eq!(header.relocation_table_offset, 120);
        assert_eq!(header.relocation_count, 0);
        assert_eq!(header.needed_library(0), Some(b"libfoo.k16so".as_slice()));
        assert_eq!(header.needed_library(1), None);
        assert_eq!(
            header.import_relocation(0),
            Some(K16eImportRelocation {
                offset: 4,
                kind: K16eRelocationKind::Call32,
                library_index: 0,
                symbol: b"foo".as_slice(),
            })
        );
        assert_eq!(header.import_relocation(1), None);
    }

    #[test]
    fn shared_k16e_v4_parser_exposes_export_records() {
        let image = shared_object_image();

        let shared = parse_shared_k16e_v4(&image).expect("shared object header parses");

        assert_eq!(shared.payload_offset, 92);
        assert_eq!(shared.file_size, 8);
        assert_eq!(shared.memory_size, 12);
        assert_eq!(
            shared.export(0),
            Some(K16eSharedExport {
                name: b"foo".as_slice(),
                offset: 2,
            })
        );
        assert_eq!(shared.export(1), None);
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
