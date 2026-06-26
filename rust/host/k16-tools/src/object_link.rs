use crate::artifact::K16ArtifactTarget;
use crate::k16e;
use std::collections::HashMap;

const ELF_MAGIC: &[u8; 4] = b"\x7fELF";
const AR_MAGIC: &[u8; 8] = b"!<arch>\n";
const ELFCLASS32: u8 = 1;
const ELFDATA2LSB: u8 = 1;
const EV_CURRENT: u32 = 1;
const ET_REL: u16 = 1;
const EM_K16: u16 = 0x5258;

const SHT_PROGBITS: u32 = 1;
const SHT_SYMTAB: u32 = 2;
const SHT_STRTAB: u32 = 3;
const SHT_RELA: u32 = 4;
const SHT_NOBITS: u32 = 8;

const SHF_WRITE: u32 = 0x1;
const SHF_ALLOC: u32 = 0x2;
const SHF_EXECINSTR: u32 = 0x4;
const SHF_MERGE: u32 = 0x10;
const SHF_STRINGS: u32 = 0x20;

const SHN_UNDEF: u16 = 0;
const SHN_ABS: u16 = 0xfff1;
const STB_LOCAL: u8 = 0;

const R_K16_NONE: u32 = 0;
const R_K16_ABS32: u32 = 1;
const R_K16_CALL32: u32 = 2;
const R_K16_BRANCH4: u32 = 3;

const BIOS_ENTRY_TRAMPOLINE_LEN: usize = 14;
const BIOS_ENTRY_TRAMPOLINE_TARGET_REG: u16 = 14;
const BIOS_ENTRY_TRAMPOLINE_STACK_REG: u16 = 15;
const IMAGE_END_SYMBOL: &str = "__k16_image_end";

#[derive(Debug, Clone)]
pub struct K16LinkInput<'a> {
    pub name: &'a str,
    pub bytes: &'a [u8],
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct K16LinkOptions {
    pub shared_cpu_helpers: bool,
    pub imports: Vec<K16LinkImport>,
    pub dylibs: Vec<K16LinkDylib>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct K16LinkImport {
    pub library: String,
    pub symbol: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct K16LinkDylib {
    pub library: String,
    pub bytes: Vec<u8>,
}

pub fn link_k16_objects_to_k16e(
    inputs: &[K16LinkInput<'_>],
    target: K16ArtifactTarget,
) -> Result<Vec<u8>, String> {
    Ok(link_k16_objects(inputs, target)?.bytes)
}

pub fn link_k16_objects(
    inputs: &[K16LinkInput<'_>],
    target: K16ArtifactTarget,
) -> Result<K16LinkedOutput, String> {
    link_k16_objects_with_options(inputs, target, K16LinkOptions::default())
}

pub fn link_k16_objects_with_options(
    inputs: &[K16LinkInput<'_>],
    target: K16ArtifactTarget,
    options: K16LinkOptions,
) -> Result<K16LinkedOutput, String> {
    if inputs.is_empty() {
        return Err("k16 link requires at least one input object".to_string());
    }
    if options.shared_cpu_helpers && target != K16ArtifactTarget::ProgramDynamic {
        return Err("--shared-cpu-helpers requires --target program-dynamic".to_string());
    }
    if (!options.imports.is_empty() || !options.dylibs.is_empty())
        && target != K16ArtifactTarget::ProgramDynamic
    {
        return Err("--import and --dylib require --target program-dynamic".to_string());
    }
    if options.shared_cpu_helpers && (!options.imports.is_empty() || !options.dylibs.is_empty()) {
        return Err("--shared-cpu-helpers cannot be combined with dynamic imports".to_string());
    }
    let imports = normalize_imports(&options.imports, &options.dylibs)?;

    let objects = parse_link_inputs(inputs, &imports.imported_symbols)?;
    let load_addr = target.base_address();
    let bios_prefix_len = if target == K16ArtifactTarget::Bios {
        BIOS_ENTRY_TRAMPOLINE_LEN
    } else {
        0
    };
    let mut linked = link_objects(
        &objects,
        load_addr,
        bios_prefix_len,
        target == K16ArtifactTarget::ProgramDynamic || target == K16ArtifactTarget::SharedObject,
        options.shared_cpu_helpers,
        target == K16ArtifactTarget::SharedObject,
        &imports.imported_symbols,
    )?;
    validate_payload_range(target, linked.memory_size)?;
    let map = K16LinkMap {
        target,
        load_addr,
        payload_bytes: linked.payload.len(),
        memory_bytes: linked.memory_size,
        retained_sections: linked.retained_sections.clone(),
    };
    let bytes = if target == K16ArtifactTarget::SharedObject {
        k16e::encode_k16_shared_object(
            &linked.payload,
            linked.memory_size,
            &linked.relocations,
            &linked.shared_exports,
        )?
    } else if target == K16ArtifactTarget::ProgramDynamic {
        if options.shared_cpu_helpers {
            k16e::encode_dynamic_k16_program_with_cpu_helpers(
                &linked.payload,
                linked.memory_size,
                linked.entry_pc,
                &linked.relocations,
                k16e::K16eCpuHelperRuntimeRequirement {
                    abi_version: 1,
                    helper_table_version: 1,
                },
                &linked.cpu_helper_relocations,
            )?
        } else if !imports.needed_libraries.is_empty() {
            k16e::encode_dynamic_k16_program_with_imports(
                &linked.payload,
                linked.memory_size,
                linked.entry_pc,
                &linked.relocations,
                &imports.needed_libraries,
                &linked.import_relocations,
            )?
        } else {
            k16e::encode_dynamic_k16_program(
                &linked.payload,
                linked.memory_size,
                linked.entry_pc,
                &linked.relocations,
            )?
        }
    } else {
        match target.fixed_image_abi_kind() {
            Some(abi_kind) => k16e::encode_k16_executable_with_memory_size(
                &linked.payload,
                linked.memory_size,
                abi_kind,
                linked.entry_pc,
                load_addr,
            )?,
            None => {
                linked.materialize_memory_payload()?;
                write_bios_entry_trampoline(&mut linked.payload, linked.entry_pc)?;
                linked.payload
            }
        }
    };
    Ok(K16LinkedOutput { bytes, map })
}

fn validate_payload_range(target: K16ArtifactTarget, memory_size: u32) -> Result<(), String> {
    let Some(limit) = target.payload_end_limit() else {
        return Ok(());
    };
    let end = target
        .base_address()
        .checked_add(memory_size)
        .ok_or_else(|| "linked K16 payload range overflows".to_string())?;
    if end > limit {
        return Err(format!(
            "linked {target:?} payload range {:#010x}..{:#010x} exceeds target limit {:#010x}",
            target.base_address(),
            end,
            limit,
        ));
    }
    Ok(())
}

struct LinkedImage {
    payload: Vec<u8>,
    memory_size: u32,
    entry_pc: u32,
    relocations: Vec<k16e::K16eRelocation>,
    cpu_helper_relocations: Vec<k16e::K16eCpuHelperRelocation>,
    import_relocations: Vec<k16e::K16eImportRelocation>,
    shared_exports: Vec<k16e::K16eSharedExport>,
    retained_sections: Vec<K16LinkMapSection>,
}

struct NormalizedImports {
    needed_libraries: Vec<String>,
    imported_symbols: HashMap<String, u32>,
}

fn normalize_imports(
    imports: &[K16LinkImport],
    dylibs: &[K16LinkDylib],
) -> Result<NormalizedImports, String> {
    let mut needed_libraries = Vec::new();
    let mut library_indexes = HashMap::new();
    let mut imported_symbols = HashMap::new();

    for import in imports {
        if import.library.is_empty() {
            return Err("K16 import library name must not be empty".to_string());
        }
        if import.symbol.is_empty() {
            return Err("K16 import symbol name must not be empty".to_string());
        }
        let library_index = if let Some(index) = library_indexes.get(&import.library) {
            *index
        } else {
            let index = u32::try_from(needed_libraries.len())
                .map_err(|_| "too many K16 needed libraries".to_string())?;
            needed_libraries.push(import.library.clone());
            library_indexes.insert(import.library.clone(), index);
            index
        };
        if let Some(previous) = imported_symbols.insert(import.symbol.clone(), library_index) {
            return Err(format!(
                "duplicate K16 import symbol `{}` in library indexes {} and {}",
                import.symbol, previous, library_index
            ));
        }
    }

    for dylib in dylibs {
        if dylib.library.is_empty() {
            return Err("K16 dylib library name must not be empty".to_string());
        }
        let library_index = if let Some(index) = library_indexes.get(&dylib.library) {
            *index
        } else {
            let index = u32::try_from(needed_libraries.len())
                .map_err(|_| "too many K16 needed libraries".to_string())?;
            needed_libraries.push(dylib.library.clone());
            library_indexes.insert(dylib.library.clone(), index);
            index
        };
        let shared = k16e::decode_k16_shared_object(&dylib.bytes)
            .map_err(|error| format!("failed to decode K16 dylib `{}`: {error}", dylib.library))?;
        for export in shared.exports {
            if let Some(previous) = imported_symbols.insert(export.name.clone(), library_index) {
                return Err(format!(
                    "duplicate K16 import symbol `{}` in library indexes {} and {}",
                    export.name, previous, library_index
                ));
            }
        }
    }

    Ok(NormalizedImports {
        needed_libraries,
        imported_symbols,
    })
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct K16LinkedOutput {
    pub bytes: Vec<u8>,
    pub map: K16LinkMap,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct K16LinkMap {
    pub target: K16ArtifactTarget,
    pub load_addr: u32,
    pub payload_bytes: usize,
    pub memory_bytes: u32,
    pub retained_sections: Vec<K16LinkMapSection>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct K16LinkMapSection {
    pub output_offset: u32,
    pub class: &'static str,
    pub file_bytes: u32,
    pub memory_bytes: u32,
    pub object: String,
    pub name: String,
}

impl K16LinkMap {
    pub fn to_text(&self) -> String {
        let mut output = format!(
            "K16 link map target={} load_addr={:#010x} payload_bytes={} memory_bytes={} retained_sections={}\n",
            self.target.tag(),
            self.load_addr,
            self.payload_bytes,
            self.memory_bytes,
            self.retained_sections.len()
        );
        for section in &self.retained_sections {
            output.push_str(&format!(
                "section offset={:#010x} class={} file_bytes={} memory_bytes={} object={} name={}\n",
                section.output_offset,
                section.class,
                section.file_bytes,
                section.memory_bytes,
                section.object,
                section.name
            ));
        }
        output
    }
}

impl LinkedImage {
    fn materialize_memory_payload(&mut self) -> Result<(), String> {
        let memory_size = usize::try_from(self.memory_size)
            .map_err(|_| "linked K16 payload is too large".to_string())?;
        if self.payload.len() > memory_size {
            return Err("linked K16 payload exceeds memory size".to_string());
        }
        self.payload.resize(memory_size, 0);
        Ok(())
    }
}

fn link_objects(
    objects: &[ParsedObject],
    load_addr: u32,
    prefix_len: usize,
    emit_dynamic_relocations: bool,
    shared_cpu_helpers: bool,
    shared_object: bool,
    imported_symbols: &HashMap<String, u32>,
) -> Result<LinkedImage, String> {
    let retained_sections = if shared_object {
        all_alloc_sections(objects)
    } else {
        reachable_alloc_sections(objects, shared_cpu_helpers, imported_symbols)?
    };
    let mut payload = vec![0; prefix_len];
    let mut memory_size =
        u32::try_from(prefix_len).map_err(|_| "linked K16 payload is too large".to_string())?;
    let mut section_offsets: Vec<Vec<Option<u32>>> = Vec::new();
    let mut dynamic_relocations = Vec::new();
    let mut cpu_helper_relocations = Vec::new();
    let mut import_relocations = Vec::new();
    let mut retained_section_map = Vec::new();

    for (object_index, object) in objects.iter().enumerate() {
        let mut object_offsets = vec![None; object.sections.len()];
        for section in &object.sections {
            if !retained_sections
                .get(object_index)
                .and_then(|sections| sections.get(section.index))
                .copied()
                .unwrap_or(false)
            {
                continue;
            }
            validate_alloc_section(section)?;
            memory_size = align_memory_size(memory_size, section.alignment)?;
            let output_offset = memory_size;
            object_offsets[section.index] = Some(output_offset);
            retained_section_map.push(K16LinkMapSection {
                output_offset,
                class: section_class(section)?,
                file_bytes: section_file_size(section)?,
                memory_bytes: section.size,
                object: object.name.clone(),
                name: section.name.clone(),
            });
            match section.kind {
                SHT_PROGBITS => {
                    ensure_payload_len(&mut payload, memory_size)?;
                    payload.extend_from_slice(&section.bytes);
                    memory_size = memory_size
                        .checked_add(section.size)
                        .ok_or_else(|| "linked K16 payload is too large".to_string())?;
                }
                SHT_NOBITS => {
                    memory_size = memory_size
                        .checked_add(section.size)
                        .ok_or_else(|| "linked K16 payload is too large".to_string())?;
                }
                _ => return Err(format!("unsupported alloc section type {}", section.kind)),
            }
        }
        section_offsets.push(object_offsets);
    }

    if memory_size
        == u32::try_from(prefix_len).map_err(|_| "linked K16 payload is too large".to_string())?
    {
        return Err("K16 link produced an empty payload".to_string());
    }
    if payload.len() % 2 != 0 {
        payload.push(0);
    }
    if memory_size % 2 != 0 {
        memory_size = memory_size
            .checked_add(1)
            .ok_or_else(|| "linked K16 payload is too large".to_string())?;
    }

    let mut defined_symbols = HashMap::new();
    for (object_index, object) in objects.iter().enumerate() {
        for symbol in &object.symbols {
            if symbol.binding == STB_LOCAL
                || symbol.name.is_empty()
                || symbol.section_index == SHN_UNDEF
                || symbol.section_index == SHN_ABS
            {
                continue;
            }
            let section_index = usize::from(symbol.section_index);
            if !retained_sections
                .get(object_index)
                .and_then(|sections| sections.get(section_index))
                .copied()
                .unwrap_or(false)
            {
                continue;
            }
            let Some(Some(section_offset)) = section_offsets
                .get(object_index)
                .and_then(|offsets| offsets.get(section_index))
            else {
                return Err(format!(
                    "{}: symbol `{}` points at non-allocated section {}",
                    object.name, symbol.name, symbol.section_index
                ));
            };
            let address = load_addr
                .checked_add(*section_offset)
                .and_then(|value| value.checked_add(symbol.value))
                .ok_or_else(|| {
                    format!(
                        "{}: symbol `{}` address overflows",
                        object.name, symbol.name
                    )
                })?;
            if let Some(previous) = defined_symbols.insert(symbol.name.clone(), address) {
                return Err(format!(
                    "duplicate K16 symbol `{}` at {previous:#010x} and {address:#010x}",
                    symbol.name
                ));
            }
        }
    }
    let image_end = load_addr
        .checked_add(memory_size)
        .ok_or_else(|| "linked K16 payload range overflows".to_string())?;
    if let Some(previous) = defined_symbols.insert(IMAGE_END_SYMBOL.to_string(), image_end) {
        return Err(format!(
            "duplicate K16 symbol `{IMAGE_END_SYMBOL}` at {previous:#010x} and {image_end:#010x}"
        ));
    }

    for (object_index, object) in objects.iter().enumerate() {
        for relocation in &object.relocations {
            let target_section = usize::try_from(relocation.target_section)
                .map_err(|_| format!("{}: relocation target section is too large", object.name))?;
            let Some(target_retained) = retained_sections
                .get(object_index)
                .and_then(|sections| sections.get(target_section))
            else {
                return Err(format!(
                    "{}: relocation targets unsupported section {}",
                    object.name, relocation.target_section
                ));
            };
            if !target_retained {
                continue;
            }
            let Some(Some(section_offset)) = section_offsets
                .get(object_index)
                .and_then(|offsets| offsets.get(target_section))
            else {
                return Err(format!(
                    "{}: relocation targets unsupported section {}",
                    object.name, relocation.target_section
                ));
            };
            let output_offset = section_offset
                .checked_add(relocation.offset)
                .ok_or_else(|| format!("{}: relocation offset overflows", object.name))?;
            let symbol = object.symbols.get(relocation.symbol_index).ok_or_else(|| {
                format!(
                    "{}: relocation references missing symbol {}",
                    object.name, relocation.symbol_index
                )
            })?;
            if shared_cpu_helpers && symbol.section_index == SHN_UNDEF {
                if let Some(helper) = cpu_helper_symbol(&symbol.name) {
                    let kind = cpu_helper_relocation_kind(relocation.kind)?;
                    cpu_helper_relocations.push(k16e::K16eCpuHelperRelocation {
                        offset: output_offset,
                        kind,
                        helper,
                    });
                    continue;
                }
            }
            if symbol.section_index == SHN_UNDEF {
                if let Some(library_index) = imported_symbols.get(&symbol.name).copied() {
                    let kind = import_relocation_kind(relocation.kind)?;
                    import_relocations.push(k16e::K16eImportRelocation {
                        offset: output_offset,
                        kind,
                        library_index,
                        symbol: symbol.name.clone(),
                    });
                    continue;
                }
            }
            let symbol_address = resolve_symbol(
                object,
                object_index,
                symbol,
                &section_offsets,
                &defined_symbols,
                load_addr,
            )?;
            let value = i64::from(symbol_address) + i64::from(relocation.addend);
            apply_relocation(
                &mut payload,
                output_offset,
                relocation.kind,
                value,
                load_addr,
                &object.name,
            )?;
            if emit_dynamic_relocations {
                if let Some(kind) = dynamic_relocation_kind(relocation.kind)? {
                    dynamic_relocations.push(k16e::K16eRelocation {
                        offset: output_offset,
                        kind,
                    });
                }
            }
        }
    }

    let shared_exports = if shared_object {
        shared_exports(objects, &retained_sections, &section_offsets)?
    } else {
        Vec::new()
    };
    let entry_pc = if shared_object {
        0
    } else {
        *defined_symbols
            .get("_start")
            .ok_or_else(|| "K16 link requires defined entry symbol `_start`".to_string())?
    };
    Ok(LinkedImage {
        payload,
        memory_size,
        entry_pc,
        relocations: dynamic_relocations,
        cpu_helper_relocations,
        import_relocations,
        shared_exports,
        retained_sections: retained_section_map,
    })
}

fn reachable_alloc_sections(
    objects: &[ParsedObject],
    shared_cpu_helpers: bool,
    imported_symbols: &HashMap<String, u32>,
) -> Result<Vec<Vec<bool>>, String> {
    let mut retained = objects
        .iter()
        .map(|object| vec![false; object.sections.len()])
        .collect::<Vec<_>>();
    let entry = unique_global_definition(objects, "_start")?
        .ok_or_else(|| "K16 link requires defined entry symbol `_start`".to_string())?;
    mark_alloc_section(objects, &mut retained, entry.0, entry.1, "_start")?;

    let mut stack = vec![entry];
    while let Some((object_index, section_index)) = stack.pop() {
        let object = &objects[object_index];
        for relocation in object
            .relocations
            .iter()
            .filter(|relocation| relocation.target_section == section_index as u32)
        {
            if relocation.kind == R_K16_NONE {
                continue;
            }
            let symbol = object.symbols.get(relocation.symbol_index).ok_or_else(|| {
                format!(
                    "{}: relocation references missing symbol {}",
                    object.name, relocation.symbol_index
                )
            })?;
            let Some((target_object, target_section)) = relocated_symbol_section(
                objects,
                object_index,
                symbol,
                shared_cpu_helpers,
                imported_symbols,
            )?
            else {
                continue;
            };
            if mark_alloc_section(
                objects,
                &mut retained,
                target_object,
                target_section,
                &symbol.name,
            )? {
                stack.push((target_object, target_section));
            }
        }
    }

    Ok(retained)
}

fn all_alloc_sections(objects: &[ParsedObject]) -> Vec<Vec<bool>> {
    objects
        .iter()
        .map(|object| {
            object
                .sections
                .iter()
                .map(|section| section.flags & SHF_ALLOC != 0)
                .collect()
        })
        .collect()
}

fn unique_global_definition(
    objects: &[ParsedObject],
    name: &str,
) -> Result<Option<(usize, usize)>, String> {
    let mut definition: Option<(usize, usize)> = None;
    for (object_index, object) in objects.iter().enumerate() {
        for symbol in &object.symbols {
            if !symbol.is_global_definition() || symbol.name != name {
                continue;
            }
            let section_index = usize::from(symbol.section_index);
            let section = object.sections.get(section_index).ok_or_else(|| {
                format!(
                    "{}: symbol `{}` points at missing section {}",
                    object.name, symbol.name, symbol.section_index
                )
            })?;
            if section.flags & SHF_ALLOC == 0 {
                return Err(format!(
                    "{}: symbol `{}` points at non-allocated section {}",
                    object.name, symbol.name, symbol.section_index
                ));
            }
            if let Some((previous_object, previous_section)) = definition {
                let previous = &objects[previous_object].sections[previous_section];
                return Err(format!(
                    "duplicate K16 symbol `{}` in `{}` and `{}`",
                    name, previous.name, section.name
                ));
            }
            definition = Some((object_index, section_index));
        }
    }
    Ok(definition)
}

fn relocated_symbol_section(
    objects: &[ParsedObject],
    object_index: usize,
    symbol: &Symbol,
    shared_cpu_helpers: bool,
    imported_symbols: &HashMap<String, u32>,
) -> Result<Option<(usize, usize)>, String> {
    if symbol.section_index == SHN_UNDEF {
        if is_synthetic_symbol(&symbol.name) {
            return Ok(None);
        }
        if shared_cpu_helpers && cpu_helper_symbol(&symbol.name).is_some() {
            return Ok(None);
        }
        if imported_symbols.contains_key(&symbol.name) {
            return Ok(None);
        }
        return unique_global_definition(objects, &symbol.name)?
            .map(Some)
            .ok_or_else(|| {
                format!(
                    "{}: unresolved K16 symbol `{}`",
                    objects[object_index].name, symbol.name
                )
            });
    }
    if symbol.section_index == SHN_ABS {
        return Ok(None);
    }
    Ok(Some((object_index, usize::from(symbol.section_index))))
}

fn is_synthetic_symbol(name: &str) -> bool {
    name == IMAGE_END_SYMBOL
}

fn mark_alloc_section(
    objects: &[ParsedObject],
    retained: &mut [Vec<bool>],
    object_index: usize,
    section_index: usize,
    symbol_name: &str,
) -> Result<bool, String> {
    let object = objects
        .get(object_index)
        .ok_or_else(|| "internal K16 link object index is out of bounds".to_string())?;
    let section = object.sections.get(section_index).ok_or_else(|| {
        format!(
            "{}: symbol `{}` points at missing section {}",
            object.name, symbol_name, section_index
        )
    })?;
    if section.flags & SHF_ALLOC == 0 {
        return Err(format!(
            "{}: symbol `{}` points at non-allocated section {}",
            object.name, symbol_name, section_index
        ));
    }
    let retained_section = retained
        .get_mut(object_index)
        .and_then(|sections| sections.get_mut(section_index))
        .ok_or_else(|| "internal K16 link retained section index is out of bounds".to_string())?;
    if *retained_section {
        return Ok(false);
    }
    *retained_section = true;
    Ok(true)
}

fn write_bios_entry_trampoline(payload: &mut [u8], entry_pc: u32) -> Result<(), String> {
    let Some(trampoline) = payload.get_mut(..BIOS_ENTRY_TRAMPOLINE_LEN) else {
        return Err("K16 BIOS payload is too small for entry trampoline".to_string());
    };
    let const32_stack = 0xe001 | (BIOS_ENTRY_TRAMPOLINE_STACK_REG << 8);
    let const32_target = 0xe001 | (BIOS_ENTRY_TRAMPOLINE_TARGET_REG << 8);
    let jump = 0x7000 | (BIOS_ENTRY_TRAMPOLINE_TARGET_REG << 8);
    let stack_top = K16ArtifactTarget::PROGRAM_STACK_TOP;
    trampoline[0..2].copy_from_slice(&const32_stack.to_le_bytes());
    trampoline[2..4].copy_from_slice(&(stack_top as u16).to_le_bytes());
    trampoline[4..6].copy_from_slice(&((stack_top >> 16) as u16).to_le_bytes());
    trampoline[6..8].copy_from_slice(&const32_target.to_le_bytes());
    trampoline[8..10].copy_from_slice(&(entry_pc as u16).to_le_bytes());
    trampoline[10..12].copy_from_slice(&((entry_pc >> 16) as u16).to_le_bytes());
    trampoline[12..14].copy_from_slice(&jump.to_le_bytes());
    Ok(())
}

fn validate_alloc_section(section: &Section) -> Result<(), String> {
    match section.name.as_str() {
        name if name == ".text.k16" || name.starts_with(".text.k16.") => {
            if section.kind != SHT_PROGBITS
                || section.flags != (SHF_ALLOC | SHF_EXECINSTR)
                || section.alignment != 2
            {
                return Err("unsupported .text.k16 section attributes".to_string());
            }
            if section.size % 2 != 0 {
                return Err(".text.k16 size must be even".to_string());
            }
        }
        name if name == ".rodata" || name.starts_with(".rodata.") => validate_rodata(section)?,
        name if name == ".data" || name.starts_with(".data.") => {
            if section.kind != SHT_PROGBITS || section.flags != (SHF_ALLOC | SHF_WRITE) {
                return Err("unsupported .data section attributes".to_string());
            }
        }
        name if name == ".bss" || name.starts_with(".bss.") => {
            if section.kind != SHT_NOBITS || section.flags != (SHF_ALLOC | SHF_WRITE) {
                return Err("unsupported .bss section attributes".to_string());
            }
        }
        other => return Err(format!("unsupported alloc section `{other}`")),
    }
    Ok(())
}

fn validate_rodata(section: &Section) -> Result<(), String> {
    let supported_flags = SHF_ALLOC | SHF_MERGE | SHF_STRINGS;
    if section.kind != SHT_PROGBITS
        || section.flags & SHF_ALLOC == 0
        || section.flags & !supported_flags != 0
    {
        return Err("unsupported .rodata section attributes".to_string());
    }
    Ok(())
}

fn section_class(section: &Section) -> Result<&'static str, String> {
    match section.name.as_str() {
        name if name == ".text.k16" || name.starts_with(".text.k16.") => Ok("text"),
        name if name == ".rodata" || name.starts_with(".rodata.") => Ok("rodata"),
        name if name == ".data" || name.starts_with(".data.") => Ok("data"),
        name if name == ".bss" || name.starts_with(".bss.") => Ok("bss"),
        other => Err(format!("unsupported alloc section `{other}`")),
    }
}

fn section_file_size(section: &Section) -> Result<u32, String> {
    match section.kind {
        SHT_PROGBITS => Ok(section.size),
        SHT_NOBITS => Ok(0),
        other => Err(format!("unsupported alloc section type {other}")),
    }
}

fn resolve_symbol(
    object: &ParsedObject,
    object_index: usize,
    symbol: &Symbol,
    section_offsets: &[Vec<Option<u32>>],
    defined_symbols: &HashMap<String, u32>,
    load_addr: u32,
) -> Result<u32, String> {
    if symbol.section_index == SHN_UNDEF {
        return defined_symbols
            .get(&symbol.name)
            .copied()
            .ok_or_else(|| format!("{}: unresolved K16 symbol `{}`", object.name, symbol.name));
    }
    let section_index = usize::from(symbol.section_index);
    let Some(Some(section_offset)) = section_offsets
        .get(object_index)
        .and_then(|offsets| offsets.get(section_index))
    else {
        return Err(format!(
            "{}: relocation symbol `{}` points at non-allocated section {}",
            object.name, symbol.name, symbol.section_index
        ));
    };
    load_addr
        .checked_add(*section_offset)
        .and_then(|value| value.checked_add(symbol.value))
        .ok_or_else(|| {
            format!(
                "{}: symbol `{}` address overflows",
                object.name, symbol.name
            )
        })
}

fn apply_relocation(
    payload: &mut [u8],
    output_offset: u32,
    kind: u32,
    value: i64,
    load_addr: u32,
    object_name: &str,
) -> Result<(), String> {
    match kind {
        R_K16_NONE => Ok(()),
        R_K16_ABS32 | R_K16_CALL32 => {
            let value = u32::try_from(value).map_err(|_| {
                format!("{object_name}: K16 relocation value {value} does not fit u32")
            })?;
            let offset = usize::try_from(output_offset)
                .map_err(|_| format!("{object_name}: relocation offset is too large"))?;
            let field = payload
                .get_mut(offset..offset + 4)
                .ok_or_else(|| format!("{object_name}: relocation field is out of bounds"))?;
            field.copy_from_slice(&value.to_le_bytes());
            Ok(())
        }
        R_K16_BRANCH4 => {
            let pc = i64::from(load_addr) + i64::from(output_offset);
            let delta = value - (pc + 2);
            if delta % 2 != 0 {
                return Err(format!(
                    "{object_name}: R_K16_BRANCH4 target {value:#010x} is not word-aligned"
                ));
            }
            let offset_words = delta / 2;
            if !(-8..=7).contains(&offset_words) {
                return Err(format!(
                    "{object_name}: R_K16_BRANCH4 offset {offset_words} is outside -8..=7"
                ));
            }
            let offset = usize::try_from(output_offset)
                .map_err(|_| format!("{object_name}: relocation offset is too large"))?;
            let field = payload.get_mut(offset..offset + 2).ok_or_else(|| {
                format!("{object_name}: branch relocation field is out of bounds")
            })?;
            let mut word = u16::from_le_bytes(field.try_into().unwrap());
            word = (word & !0x000f) | u16::from((offset_words as i16 & 0x000f) as u8);
            field.copy_from_slice(&word.to_le_bytes());
            Ok(())
        }
        other => Err(format!("unsupported K16 relocation {other}")),
    }
}

fn dynamic_relocation_kind(kind: u32) -> Result<Option<k16e::K16eRelocationKind>, String> {
    match kind {
        R_K16_NONE => Ok(None),
        R_K16_ABS32 => Ok(Some(k16e::K16eRelocationKind::Abs32)),
        R_K16_CALL32 => Ok(Some(k16e::K16eRelocationKind::Call32)),
        R_K16_BRANCH4 => Ok(None),
        other => Err(format!("unsupported K16 relocation {other}")),
    }
}

fn cpu_helper_relocation_kind(kind: u32) -> Result<k16e::K16eCpuHelperRelocationKind, String> {
    match kind {
        R_K16_ABS32 => Ok(k16e::K16eCpuHelperRelocationKind::Abs32),
        R_K16_CALL32 => Ok(k16e::K16eCpuHelperRelocationKind::Call32),
        other => Err(format!(
            "unsupported K16 CPU helper relocation {other}; expected R_K16_ABS32 or R_K16_CALL32"
        )),
    }
}

fn import_relocation_kind(kind: u32) -> Result<k16e::K16eRelocationKind, String> {
    match dynamic_relocation_kind(kind)? {
        Some(kind) => Ok(kind),
        None => Err("K16 import relocation requires R_K16_ABS32 or R_K16_CALL32".to_string()),
    }
}

fn shared_exports(
    objects: &[ParsedObject],
    retained_sections: &[Vec<bool>],
    section_offsets: &[Vec<Option<u32>>],
) -> Result<Vec<k16e::K16eSharedExport>, String> {
    let mut exports = Vec::new();
    for (object_index, object) in objects.iter().enumerate() {
        for symbol in &object.symbols {
            if symbol.binding == STB_LOCAL
                || symbol.name.is_empty()
                || symbol.section_index == SHN_UNDEF
                || symbol.section_index == SHN_ABS
                || cpu_helper_symbol(&symbol.name).is_some()
            {
                continue;
            }
            let section_index = usize::from(symbol.section_index);
            if !retained_sections
                .get(object_index)
                .and_then(|sections| sections.get(section_index))
                .copied()
                .unwrap_or(false)
            {
                continue;
            }
            let Some(Some(section_offset)) = section_offsets
                .get(object_index)
                .and_then(|offsets| offsets.get(section_index))
            else {
                return Err(format!(
                    "{}: shared export `{}` points at non-allocated section {}",
                    object.name, symbol.name, symbol.section_index
                ));
            };
            let offset = section_offset.checked_add(symbol.value).ok_or_else(|| {
                format!(
                    "{}: shared export `{}` offset overflows",
                    object.name, symbol.name
                )
            })?;
            exports.push(k16e::K16eSharedExport {
                name: symbol.name.clone(),
                offset,
            });
        }
    }
    if exports.is_empty() {
        return Err("K16 shared object requires at least one exported symbol".to_string());
    }
    Ok(exports)
}

fn cpu_helper_symbol(name: &str) -> Option<k16e::K16eCpuHelper> {
    match name {
        "__k16_halt_once" => Some(k16e::K16eCpuHelper::HaltOnce),
        "__k16_wait_once" => Some(k16e::K16eCpuHelper::WaitOnce),
        "__k16_yield_once" => Some(k16e::K16eCpuHelper::YieldOnce),
        "__k16_iret_once" => Some(k16e::K16eCpuHelper::IretOnce),
        "__k16_save_trap_frame" => Some(k16e::K16eCpuHelper::SaveTrapFrame),
        "__k16_restore_trap_frame" => Some(k16e::K16eCpuHelper::RestoreTrapFrame),
        "__k16_write_trap_vector" => Some(k16e::K16eCpuHelper::WriteTrapVector),
        "__k16_read_trap_cause" => Some(k16e::K16eCpuHelper::ReadTrapCause),
        "__k16_read_trap_pc" => Some(k16e::K16eCpuHelper::ReadTrapPc),
        "__k16_read_trap_value" => Some(k16e::K16eCpuHelper::ReadTrapValue),
        "__k16_read_trap_arg0" => Some(k16e::K16eCpuHelper::ReadTrapArg0),
        "__k16_read_trap_arg1" => Some(k16e::K16eCpuHelper::ReadTrapArg1),
        "__k16_read_trap_arg2" => Some(k16e::K16eCpuHelper::ReadTrapArg2),
        "__k16_syscall_once" => Some(k16e::K16eCpuHelper::SyscallOnce),
        "__k16_syscall0" => Some(k16e::K16eCpuHelper::Syscall0),
        "__k16_syscall1" => Some(k16e::K16eCpuHelper::Syscall1),
        "__k16_syscall3" => Some(k16e::K16eCpuHelper::Syscall3),
        "__k16_iret_with_r0" => Some(k16e::K16eCpuHelper::IretWithR0),
        "__k16_write_interrupt_enable" => Some(k16e::K16eCpuHelper::WriteInterruptEnable),
        "__k16_write_interrupt_mask" => Some(k16e::K16eCpuHelper::WriteInterruptMask),
        "__k16_read_interrupt_pending" => Some(k16e::K16eCpuHelper::ReadInterruptPending),
        "__k16_write_syscall" => Some(k16e::K16eCpuHelper::WriteSyscall),
        "__k16_read_syscall" => Some(k16e::K16eCpuHelper::ReadSyscall),
        "__k16_open_syscall" => Some(k16e::K16eCpuHelper::OpenSyscall),
        "__k16_close_syscall" => Some(k16e::K16eCpuHelper::CloseSyscall),
        "__k16_brk_syscall" => Some(k16e::K16eCpuHelper::BrkSyscall),
        "__k16_sbrk_syscall" => Some(k16e::K16eCpuHelper::SbrkSyscall),
        _ => None,
    }
}

fn align_memory_size(memory_size: u32, alignment: u32) -> Result<u32, String> {
    if alignment == 0 {
        return Err("K16 alloc section alignment must be non-zero".to_string());
    }
    Ok(memory_size.div_ceil(alignment) * alignment)
}

fn ensure_payload_len(payload: &mut Vec<u8>, len: u32) -> Result<(), String> {
    let len = usize::try_from(len).map_err(|_| "linked K16 payload is too large".to_string())?;
    if payload.len() > len {
        return Err("linked K16 payload exceeds memory size".to_string());
    }
    payload.resize(len, 0);
    Ok(())
}

#[derive(Debug, Clone)]
struct ParsedObject {
    name: String,
    sections: Vec<Section>,
    symbols: Vec<Symbol>,
    relocations: Vec<Relocation>,
}

fn parse_link_inputs(
    inputs: &[K16LinkInput<'_>],
    imported_symbols: &HashMap<String, u32>,
) -> Result<Vec<ParsedObject>, String> {
    let mut objects = Vec::new();
    let mut archives = Vec::new();
    for input in inputs {
        if input.bytes.starts_with(ELF_MAGIC) {
            objects.push(ParsedObject::parse(input.name, input.bytes)?);
        } else if input.bytes.starts_with(AR_MAGIC) {
            archives.push(parse_archive(input.name, input.bytes)?);
        } else {
            return Err(format!("{}: invalid ELF or archive magic", input.name));
        }
    }
    select_archive_members(&mut objects, archives, imported_symbols);
    Ok(objects)
}

fn select_archive_members(
    objects: &mut Vec<ParsedObject>,
    archives: Vec<Vec<ParsedObject>>,
    imported_symbols: &HashMap<String, u32>,
) {
    let mut selected = archives
        .into_iter()
        .map(|archive| archive.into_iter().map(Some).collect::<Vec<_>>())
        .collect::<Vec<_>>();

    loop {
        let unresolved = unresolved_global_symbols(objects, imported_symbols);
        if unresolved.is_empty() {
            break;
        }

        let mut selected_member = None;
        'archive_search: for (archive_index, archive) in selected.iter_mut().enumerate() {
            for (member_index, member) in archive.iter_mut().enumerate() {
                let Some(object) = member else {
                    continue;
                };
                if object.defines_any(&unresolved) {
                    selected_member = Some((archive_index, member_index));
                    break 'archive_search;
                }
            }
        }

        let Some((archive_index, member_index)) = selected_member else {
            break;
        };
        let object = selected[archive_index][member_index]
            .take()
            .expect("selected archive member is present");
        objects.push(object);
    }
}

fn unresolved_global_symbols(
    objects: &[ParsedObject],
    imported_symbols: &HashMap<String, u32>,
) -> Vec<String> {
    let mut defined = Vec::new();
    let mut unresolved = Vec::new();

    for object in objects {
        for symbol in &object.symbols {
            if symbol.is_global_definition() {
                defined.push(symbol.name.clone());
            }
        }
    }

    for object in objects {
        for symbol in &object.symbols {
            if symbol.is_global_undefined()
                && !imported_symbols.contains_key(&symbol.name)
                && !defined.iter().any(|defined| defined == &symbol.name)
                && !unresolved
                    .iter()
                    .any(|unresolved| unresolved == &symbol.name)
            {
                unresolved.push(symbol.name.clone());
            }
        }
    }

    unresolved
}

fn parse_archive(name: &str, bytes: &[u8]) -> Result<Vec<ParsedObject>, String> {
    let mut objects = Vec::new();
    let mut offset = AR_MAGIC.len();
    while offset < bytes.len() {
        let header = bytes
            .get(offset..offset + 60)
            .ok_or_else(|| format!("{name}: archive member header is truncated"))?;
        if header.get(58..60) != Some(b"`\n") {
            return Err(format!("{name}: invalid archive member header"));
        }
        let raw_name = String::from_utf8_lossy(&header[0..16]).trim().to_string();
        let size_text = String::from_utf8_lossy(&header[48..58]).trim().to_string();
        let size = size_text
            .parse::<usize>()
            .map_err(|_| format!("{name}: invalid archive member size `{size_text}`"))?;
        offset += 60;
        let member = bytes
            .get(offset..offset + size)
            .ok_or_else(|| format!("{name}: archive member `{raw_name}` is truncated"))?;
        let member_name = archive_member_name(&raw_name);
        if member.starts_with(ELF_MAGIC) {
            objects.push(ParsedObject::parse(
                &format!("{name}({member_name})"),
                member,
            )?);
        }
        offset += size + (size % 2);
    }
    if objects.is_empty() {
        return Err(format!(
            "{name}: archive contains no K16 ELF object members"
        ));
    }
    Ok(objects)
}

fn archive_member_name(raw_name: &str) -> String {
    raw_name
        .strip_suffix('/')
        .unwrap_or(raw_name)
        .trim()
        .to_string()
}

impl ParsedObject {
    fn parse(name: &str, bytes: &[u8]) -> Result<Self, String> {
        validate_elf_header(name, bytes)?;
        let section_header_offset = read_u32(bytes, 32)?;
        let section_header_size = read_u16(bytes, 46)?;
        let section_count = read_u16(bytes, 48)?;
        let section_name_index = read_u16(bytes, 50)?;
        if section_header_size != 40 {
            return Err(format!(
                "{name}: unsupported ELF section header size {section_header_size}"
            ));
        }
        let headers = read_section_headers(
            bytes,
            section_header_offset,
            section_count,
            section_name_index,
            name,
        )?;
        let mut sections = Vec::with_capacity(headers.len());
        for (index, header) in headers.iter().enumerate() {
            let section_name =
                section_name(bytes, &headers, section_name_index, header.name, name)?;
            let section_bytes = if header.kind == SHT_NOBITS {
                Vec::new()
            } else {
                section_data(bytes, header, name, &section_name)?.to_vec()
            };
            sections.push(Section {
                index,
                name: section_name,
                kind: header.kind,
                flags: header.flags,
                size: header.size,
                alignment: header.alignment,
                bytes: section_bytes,
            });
        }
        let symbols = parse_symbols(bytes, &headers, name)?;
        let relocations = parse_relocations(bytes, &headers, name)?;
        Ok(Self {
            name: name.to_string(),
            sections,
            symbols,
            relocations,
        })
    }
}

impl ParsedObject {
    fn defines_any(&self, unresolved: &[String]) -> bool {
        self.symbols
            .iter()
            .any(|symbol| symbol.is_global_definition() && unresolved.contains(&symbol.name))
    }
}

fn validate_elf_header(name: &str, bytes: &[u8]) -> Result<(), String> {
    if bytes.get(0..4) != Some(ELF_MAGIC) {
        return Err(format!("{name}: invalid ELF magic"));
    }
    if bytes.get(4).copied() != Some(ELFCLASS32) {
        return Err(format!("{name}: unsupported ELF class"));
    }
    if bytes.get(5).copied() != Some(ELFDATA2LSB) {
        return Err(format!("{name}: unsupported ELF endianness"));
    }
    if bytes.get(6).copied() != Some(1) {
        return Err(format!("{name}: unsupported ELF ident version"));
    }
    let elf_type = read_u16(bytes, 16)?;
    if elf_type != ET_REL {
        return Err(format!(
            "{name}: unsupported ELF type {elf_type}; expected ET_REL"
        ));
    }
    let machine = read_u16(bytes, 18)?;
    if machine != EM_K16 {
        return Err(format!("{name}: unsupported ELF machine {machine:#06x}"));
    }
    let version = read_u32(bytes, 20)?;
    if version != EV_CURRENT {
        return Err(format!("{name}: unsupported ELF version {version}"));
    }
    Ok(())
}

#[derive(Debug, Clone)]
struct SectionHeader {
    name: u32,
    kind: u32,
    flags: u32,
    offset: u32,
    size: u32,
    link: u32,
    info: u32,
    alignment: u32,
    entry_size: u32,
}

fn read_section_headers(
    bytes: &[u8],
    section_header_offset: u32,
    section_count: u16,
    section_name_index: u16,
    object_name: &str,
) -> Result<Vec<SectionHeader>, String> {
    if usize::from(section_name_index) >= usize::from(section_count) {
        return Err(format!(
            "{object_name}: ELF section name table index is out of bounds"
        ));
    }
    let mut headers = Vec::with_capacity(usize::from(section_count));
    let base = usize::try_from(section_header_offset)
        .map_err(|_| format!("{object_name}: section header offset is too large"))?;
    for index in 0..usize::from(section_count) {
        let offset = base
            .checked_add(index * 40)
            .ok_or_else(|| format!("{object_name}: section header offset overflows"))?;
        bytes
            .get(offset..offset + 40)
            .ok_or_else(|| format!("{object_name}: section header {index} is truncated"))?;
        headers.push(SectionHeader {
            name: read_u32_at(bytes, offset)?,
            kind: read_u32_at(bytes, offset + 4)?,
            flags: read_u32_at(bytes, offset + 8)?,
            offset: read_u32_at(bytes, offset + 16)?,
            size: read_u32_at(bytes, offset + 20)?,
            link: read_u32_at(bytes, offset + 24)?,
            info: read_u32_at(bytes, offset + 28)?,
            alignment: read_u32_at(bytes, offset + 32)?,
            entry_size: read_u32_at(bytes, offset + 36)?,
        });
    }
    Ok(headers)
}

#[derive(Debug, Clone)]
struct Section {
    index: usize,
    name: String,
    kind: u32,
    flags: u32,
    size: u32,
    alignment: u32,
    bytes: Vec<u8>,
}

#[derive(Debug, Clone)]
struct Symbol {
    name: String,
    value: u32,
    binding: u8,
    section_index: u16,
}

impl Symbol {
    fn is_global_definition(&self) -> bool {
        self.binding != STB_LOCAL
            && !self.name.is_empty()
            && self.section_index != SHN_UNDEF
            && self.section_index != SHN_ABS
    }

    fn is_global_undefined(&self) -> bool {
        self.binding != STB_LOCAL && !self.name.is_empty() && self.section_index == SHN_UNDEF
    }
}

#[derive(Debug, Clone)]
struct Relocation {
    target_section: u32,
    offset: u32,
    symbol_index: usize,
    kind: u32,
    addend: i32,
}

fn parse_symbols(
    bytes: &[u8],
    headers: &[SectionHeader],
    object_name: &str,
) -> Result<Vec<Symbol>, String> {
    let Some((index, symtab)) = headers
        .iter()
        .enumerate()
        .find(|(_, header)| header.kind == SHT_SYMTAB)
    else {
        return Err(format!("{object_name}: missing ELF symbol table"));
    };
    if symtab.entry_size != 16 {
        return Err(format!(
            "{object_name}: unsupported symtab entry size {}",
            symtab.entry_size
        ));
    }
    let strtab = headers
        .get(symtab.link as usize)
        .ok_or_else(|| format!("{object_name}: symtab string table link is out of bounds"))?;
    if strtab.kind != SHT_STRTAB {
        return Err(format!(
            "{object_name}: symtab link does not point to a string table"
        ));
    }
    let strtab_bytes = section_data(bytes, strtab, object_name, ".strtab")?;
    let count = symtab.size / symtab.entry_size;
    let symtab_offset = usize::try_from(symtab.offset)
        .map_err(|_| format!("{object_name}: symtab offset is too large"))?;
    let mut symbols = Vec::with_capacity(count as usize);
    for symbol_index in 0..count as usize {
        let offset = symtab_offset
            .checked_add(symbol_index * 16)
            .ok_or_else(|| format!("{object_name}: symtab entry offset overflows"))?;
        bytes
            .get(offset..offset + 16)
            .ok_or_else(|| format!("{object_name}: symtab entry {symbol_index} is truncated"))?;
        let name_offset = read_u32_at(bytes, offset)?;
        let info = bytes
            .get(offset + 12)
            .copied()
            .ok_or_else(|| format!("{object_name}: symtab entry {symbol_index} is truncated"))?;
        symbols.push(Symbol {
            name: read_string(strtab_bytes, name_offset, object_name)?,
            value: read_u32_at(bytes, offset + 4)?,
            binding: info >> 4,
            section_index: read_u16_at(bytes, offset + 14)?,
        });
    }
    if index == 0 {
        return Err(format!("{object_name}: symtab cannot be section 0"));
    }
    Ok(symbols)
}

fn parse_relocations(
    bytes: &[u8],
    headers: &[SectionHeader],
    object_name: &str,
) -> Result<Vec<Relocation>, String> {
    let mut relocations = Vec::new();
    for header in headers.iter().filter(|header| header.kind == SHT_RELA) {
        if header.entry_size != 12 {
            return Err(format!(
                "{object_name}: unsupported RELA entry size {}",
                header.entry_size
            ));
        }
        let count = header.size / header.entry_size;
        let offset = usize::try_from(header.offset)
            .map_err(|_| format!("{object_name}: RELA offset is too large"))?;
        for relocation_index in 0..count as usize {
            let entry_offset = offset
                .checked_add(relocation_index * 12)
                .ok_or_else(|| format!("{object_name}: RELA entry offset overflows"))?;
            bytes.get(entry_offset..entry_offset + 12).ok_or_else(|| {
                format!("{object_name}: RELA entry {relocation_index} is truncated")
            })?;
            let info = read_u32_at(bytes, entry_offset + 4)?;
            relocations.push(Relocation {
                target_section: header.info,
                offset: read_u32_at(bytes, entry_offset)?,
                symbol_index: (info >> 8) as usize,
                kind: info & 0xff,
                addend: read_i32_at(bytes, entry_offset + 8)?,
            });
        }
    }
    Ok(relocations)
}

fn section_name(
    bytes: &[u8],
    headers: &[SectionHeader],
    section_name_index: u16,
    name_offset: u32,
    object_name: &str,
) -> Result<String, String> {
    let shstrtab = headers
        .get(usize::from(section_name_index))
        .ok_or_else(|| format!("{object_name}: section name table index is out of bounds"))?;
    if shstrtab.kind != SHT_STRTAB {
        return Err(format!(
            "{object_name}: section name table is not SHT_STRTAB"
        ));
    }
    read_string(
        section_data(bytes, shstrtab, object_name, ".shstrtab")?,
        name_offset,
        object_name,
    )
}

fn section_data<'a>(
    bytes: &'a [u8],
    header: &SectionHeader,
    object_name: &str,
    label: &str,
) -> Result<&'a [u8], String> {
    let start = usize::try_from(header.offset)
        .map_err(|_| format!("{object_name}: {label} offset is too large"))?;
    let size = usize::try_from(header.size)
        .map_err(|_| format!("{object_name}: {label} size is too large"))?;
    let end = start
        .checked_add(size)
        .ok_or_else(|| format!("{object_name}: {label} range overflows"))?;
    bytes
        .get(start..end)
        .ok_or_else(|| format!("{object_name}: {label} is out of bounds"))
}

fn read_string(table: &[u8], offset: u32, object_name: &str) -> Result<String, String> {
    let offset = usize::try_from(offset)
        .map_err(|_| format!("{object_name}: string table offset is too large"))?;
    let rest = table
        .get(offset..)
        .ok_or_else(|| format!("{object_name}: string table offset is out of bounds"))?;
    let end = rest
        .iter()
        .position(|byte| *byte == 0)
        .ok_or_else(|| format!("{object_name}: string table entry is not null-terminated"))?;
    String::from_utf8(rest[..end].to_vec())
        .map_err(|_| format!("{object_name}: string table entry is not valid UTF-8"))
}

fn read_u16(bytes: &[u8], offset: usize) -> Result<u16, String> {
    read_u16_at(bytes, offset)
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, String> {
    read_u32_at(bytes, offset)
}

fn read_u16_at(bytes: &[u8], offset: usize) -> Result<u16, String> {
    let value = bytes
        .get(offset..offset + 2)
        .ok_or_else(|| "ELF u16 field is truncated".to_string())?;
    Ok(u16::from_le_bytes(value.try_into().unwrap()))
}

fn read_u32_at(bytes: &[u8], offset: usize) -> Result<u32, String> {
    let value = bytes
        .get(offset..offset + 4)
        .ok_or_else(|| "ELF u32 field is truncated".to_string())?;
    Ok(u32::from_le_bytes(value.try_into().unwrap()))
}

fn read_i32_at(bytes: &[u8], offset: usize) -> Result<i32, String> {
    let value = bytes
        .get(offset..offset + 4)
        .ok_or_else(|| "ELF i32 field is truncated".to_string())?;
    Ok(i32::from_le_bytes(value.try_into().unwrap()))
}
