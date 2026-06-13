const LOAD_ALIGNMENT: u32 = 2;
const STACK_ALIGNMENT: u32 = 4;
const ROOT_PARTITION: &[u8; 4] = b"ROOT";
const BIN_COMPONENT: &[u8] = b"bin";
const BIN_PREFIX: &[u8] = b"/bin/";
const KX_SUFFIX: &[u8] = b".kx";
const K16FS_MAX_NAME_BYTES: usize = 56;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ProcessLoadError {
    InvalidPath,
    InvalidArena,
    InvalidImage,
    AddressOverflow,
    ProgramTooLarge,
    Storage,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ProcessSwitchError {
    ChildAlreadyRunning,
    NoRunningChild,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ProcessId {
    Init,
    Child,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ProcessState {
    Empty,
    Running,
    BlockedOnChild,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct ProcessContext {
    pub entry_pc: u32,
    pub stack_top: u32,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct ChildLaunch {
    pub id: ProcessId,
    pub context: ProcessContext,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct InitResume {
    pub id: ProcessId,
    pub context: ProcessContext,
    pub child_exit_status: u32,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct ProcessTable {
    init: ProcessSlot,
    child: ProcessSlot,
}

impl ProcessTable {
    pub const fn new(init_context: ProcessContext) -> Self {
        Self {
            init: ProcessSlot {
                state: ProcessState::Running,
                context: init_context,
                exit_status: 0,
            },
            child: ProcessSlot {
                state: ProcessState::Empty,
                context: ProcessContext {
                    entry_pc: 0,
                    stack_top: 0,
                },
                exit_status: 0,
            },
        }
    }

    pub fn begin_child_run(
        &mut self,
        child_plan: DynamicUserLoadPlan,
    ) -> Result<ChildLaunch, ProcessSwitchError> {
        if self.child.state == ProcessState::Running {
            return Err(ProcessSwitchError::ChildAlreadyRunning);
        }
        let context = ProcessContext {
            entry_pc: child_plan.entry_pc,
            stack_top: child_plan.stack_top,
        };
        self.init.state = ProcessState::BlockedOnChild;
        self.child = ProcessSlot {
            state: ProcessState::Running,
            context,
            exit_status: 0,
        };
        Ok(ChildLaunch {
            id: ProcessId::Child,
            context,
        })
    }

    pub fn finish_child(&mut self, status: u32) -> Result<InitResume, ProcessSwitchError> {
        if self.child.state != ProcessState::Running {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        self.child.state = ProcessState::Empty;
        self.child.exit_status = status;
        self.init.state = ProcessState::Running;
        Ok(InitResume {
            id: ProcessId::Init,
            context: self.init.context,
            child_exit_status: status,
        })
    }

    pub const fn init_state(&self) -> ProcessState {
        self.init.state
    }

    pub const fn child_state(&self) -> ProcessState {
        self.child.state
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
struct ProcessSlot {
    state: ProcessState,
    context: ProcessContext,
    exit_status: u32,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct UserArena {
    start: u32,
    end: u32,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct UserProgramPath<'a> {
    components: [&'a [u8]; 2],
}

impl<'a> UserProgramPath<'a> {
    pub fn parse(path: &'a [u8]) -> Result<Self, ProcessLoadError> {
        if !path.starts_with(BIN_PREFIX) {
            return Err(ProcessLoadError::InvalidPath);
        }
        let name = &path[BIN_PREFIX.len()..];
        if name.is_empty()
            || name.len() > K16FS_MAX_NAME_BYTES
            || !name.ends_with(KX_SUFFIX)
            || name.contains(&b'/')
            || name == b".kx"
            || name == b".."
            || name.starts_with(b".")
        {
            return Err(ProcessLoadError::InvalidPath);
        }
        Ok(Self {
            components: [BIN_COMPONENT, name],
        })
    }

    pub const fn components(&self) -> &[&'a [u8]; 2] {
        &self.components
    }
}

impl UserArena {
    pub fn new(start: u32, end: u32) -> Result<Self, ProcessLoadError> {
        if start >= end {
            return Err(ProcessLoadError::InvalidArena);
        }
        let load_base = align_up(start, LOAD_ALIGNMENT)?;
        let stack_top = align_down(end, STACK_ALIGNMENT);
        if load_base >= stack_top {
            return Err(ProcessLoadError::InvalidArena);
        }
        Ok(Self { start, end })
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct DynamicUserImage {
    pub entry_offset: u32,
    pub file_size: u32,
    pub memory_size: u32,
}

impl DynamicUserImage {
    pub const fn from_k16e(header: k16_image::DynamicK16ImageHeader<'_>) -> Self {
        Self {
            entry_offset: header.entry_offset,
            file_size: header.file_size,
            memory_size: header.memory_size,
        }
    }

    pub const fn from_k16e_metadata(metadata: k16_image::DynamicK16ImageMetadata) -> Self {
        Self {
            entry_offset: metadata.entry_offset,
            file_size: metadata.file_size,
            memory_size: metadata.memory_size,
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct DynamicUserLoadPlan {
    pub load_base: u32,
    pub load_end: u32,
    pub entry_pc: u32,
    pub stack_top: u32,
    pub payload_dst: u32,
    pub payload_len: u32,
    pub zero_fill_addr: u32,
    pub zero_fill_len: u32,
}

pub fn plan_dynamic_user_load(
    arena: UserArena,
    image: DynamicUserImage,
) -> Result<DynamicUserLoadPlan, ProcessLoadError> {
    validate_dynamic_image(image)?;

    let load_base = align_up(arena.start, LOAD_ALIGNMENT)?;
    let stack_top = align_down(arena.end, STACK_ALIGNMENT);
    let load_end = load_base
        .checked_add(image.memory_size)
        .ok_or(ProcessLoadError::AddressOverflow)?;
    if load_end >= stack_top {
        return Err(ProcessLoadError::ProgramTooLarge);
    }
    let entry_pc = load_base
        .checked_add(image.entry_offset)
        .ok_or(ProcessLoadError::AddressOverflow)?;
    let zero_fill_addr = load_base
        .checked_add(image.file_size)
        .ok_or(ProcessLoadError::AddressOverflow)?;

    Ok(DynamicUserLoadPlan {
        load_base,
        load_end,
        entry_pc,
        stack_top,
        payload_dst: load_base,
        payload_len: image.file_size,
        zero_fill_addr,
        zero_fill_len: image.memory_size - image.file_size,
    })
}

pub unsafe fn load_dynamic_user_program_from_storage0(
    path: &[u8],
    arena: UserArena,
) -> Result<DynamicUserLoadPlan, ProcessLoadError> {
    let path = UserProgramPath::parse(path)?;
    unsafe {
        k16_storage::open_file_from_storage0(ROOT_PARTITION, path.components())
            .map_err(|_| ProcessLoadError::Storage)?;
        load_selected_dynamic_user_program(arena)
    }
}

pub unsafe fn load_selected_dynamic_user_program(
    arena: UserArena,
) -> Result<DynamicUserLoadPlan, ProcessLoadError> {
    unsafe {
        k16_storage::copy_selected_file_range_to_ram(
            0,
            k16_storage::SCRATCH_ADDR,
            k16_image::DYNAMIC_K16E_V2_HEADER_SIZE,
        )
        .map_err(|_| ProcessLoadError::Storage)?;
    }
    let header = unsafe {
        core::slice::from_raw_parts(
            k16_storage::SCRATCH_ADDR as usize as *const u8,
            k16_image::DYNAMIC_K16E_V2_HEADER_SIZE as usize,
        )
    };
    let metadata = k16_image::parse_dynamic_k16e_v2_header(header, unsafe {
        k16_storage::selected_file_size()
    })
    .map_err(|_| ProcessLoadError::InvalidImage)?;
    let plan = plan_dynamic_user_load(arena, DynamicUserImage::from_k16e_metadata(metadata))?;

    unsafe {
        k16_storage::copy_selected_file_range_to_ram(
            metadata.payload_offset,
            plan.payload_dst,
            plan.payload_len,
        )
        .map_err(|_| ProcessLoadError::Storage)?;
        zero_fill_ram(plan.zero_fill_addr, plan.zero_fill_len);
        apply_selected_file_relocations(metadata, plan)?;
    }
    Ok(plan)
}

fn validate_dynamic_image(image: DynamicUserImage) -> Result<(), ProcessLoadError> {
    if image.file_size == 0
        || image.memory_size < image.file_size
        || image.file_size % LOAD_ALIGNMENT != 0
        || image.memory_size % LOAD_ALIGNMENT != 0
        || image.entry_offset >= image.memory_size
        || image.entry_offset % LOAD_ALIGNMENT != 0
    {
        return Err(ProcessLoadError::InvalidImage);
    }
    Ok(())
}

unsafe fn apply_selected_file_relocations(
    metadata: k16_image::DynamicK16ImageMetadata,
    plan: DynamicUserLoadPlan,
) -> Result<(), ProcessLoadError> {
    let mut index = 0;
    while index < metadata.relocation_count {
        let relocation_offset = metadata
            .relocation_table_offset
            .checked_add(
                index
                    .checked_mul(k16_image::K16E_RELOCATION_RECORD_SIZE)
                    .ok_or(ProcessLoadError::AddressOverflow)?,
            )
            .ok_or(ProcessLoadError::AddressOverflow)?;
        unsafe {
            k16_storage::copy_selected_file_range_to_ram(
                relocation_offset,
                k16_storage::SCRATCH_ADDR,
                k16_image::K16E_RELOCATION_RECORD_SIZE,
            )
            .map_err(|_| ProcessLoadError::Storage)?;
        }
        let record = unsafe {
            core::slice::from_raw_parts(
                k16_storage::SCRATCH_ADDR as usize as *const u8,
                k16_image::K16E_RELOCATION_RECORD_SIZE as usize,
            )
        };
        let relocation = k16_image::parse_k16e_relocation_record(record, metadata.memory_size)
            .map_err(|_| ProcessLoadError::InvalidImage)?;
        unsafe { apply_dynamic_relocation_to_ram(plan, relocation)? };
        index += 1;
    }
    Ok(())
}

unsafe fn apply_dynamic_relocation_to_ram(
    plan: DynamicUserLoadPlan,
    relocation: k16_image::K16eRelocation,
) -> Result<(), ProcessLoadError> {
    let field_addr = plan
        .load_base
        .checked_add(relocation.offset)
        .ok_or(ProcessLoadError::AddressOverflow)?;
    let value = unsafe { read_u32(field_addr) };
    let relocated = value
        .checked_add(plan.load_base)
        .ok_or(ProcessLoadError::AddressOverflow)?;
    unsafe { write_u32(field_addr, relocated) };
    Ok(())
}

#[cfg(test)]
fn apply_dynamic_relocation_to_slice(
    memory: &mut [u8],
    memory_base: u32,
    plan: DynamicUserLoadPlan,
    relocation: k16_image::K16eRelocation,
) -> Result<(), ProcessLoadError> {
    let field_addr = plan
        .load_base
        .checked_add(relocation.offset)
        .ok_or(ProcessLoadError::AddressOverflow)?;
    let field_offset = field_addr
        .checked_sub(memory_base)
        .ok_or(ProcessLoadError::AddressOverflow)?;
    let field_offset =
        usize::try_from(field_offset).map_err(|_| ProcessLoadError::AddressOverflow)?;
    let field = memory
        .get_mut(field_offset..field_offset + 4)
        .ok_or(ProcessLoadError::AddressOverflow)?;
    let value = u32::from_le_bytes([field[0], field[1], field[2], field[3]]);
    let relocated = value
        .checked_add(plan.load_base)
        .ok_or(ProcessLoadError::AddressOverflow)?;
    field.copy_from_slice(&relocated.to_le_bytes());
    Ok(())
}

unsafe fn zero_fill_ram(dst_addr: u32, len: u32) {
    let mut offset = 0;
    while offset < len {
        unsafe { write_u8(dst_addr + offset, 0) };
        offset += 1;
    }
}

unsafe fn read_u32(address: u32) -> u32 {
    unsafe { core::ptr::read_volatile(address as usize as *const u32) }
}

unsafe fn write_u32(address: u32, value: u32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u32, value) }
}

unsafe fn write_u8(address: u32, value: u8) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u8, value) }
}

fn align_up(value: u32, alignment: u32) -> Result<u32, ProcessLoadError> {
    let mask = alignment - 1;
    value
        .checked_add(mask)
        .map(|value| value & !mask)
        .ok_or(ProcessLoadError::AddressOverflow)
}

const fn align_down(value: u32, alignment: u32) -> u32 {
    value & !(alignment - 1)
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

    fn dynamic_program_image() -> [u8; 80] {
        let mut bytes = [0u8; 80];
        bytes[0..4].copy_from_slice(b"K16E");
        write_u16_le(&mut bytes, 4, 2);
        write_u16_le(&mut bytes, 6, 32);
        write_u16_le(&mut bytes, 8, 1);
        write_u16_le(&mut bytes, 10, 0);
        write_u32_le(&mut bytes, 12, 2);
        write_u32_le(&mut bytes, 16, 32);
        write_u32_le(&mut bytes, 20, 2);
        write_u32_le(&mut bytes, 24, 3);
        write_u32_le(&mut bytes, 28, 0);
        write_u32_le(&mut bytes, 32, 1);
        write_u32_le(&mut bytes, 36, 0);
        write_u32_le(&mut bytes, 40, 72);
        write_u32_le(&mut bytes, 44, 8);
        write_u32_le(&mut bytes, 48, 12);
        write_u32_le(&mut bytes, 52, 2);
        write_u32_le(&mut bytes, 56, 0);
        write_u32_le(&mut bytes, 60, 80);
        write_u32_le(&mut bytes, 64, 0);
        write_u32_le(&mut bytes, 68, 0);
        bytes[72..80].copy_from_slice(&[0x01, 0xe1, 0, 0, 0, 0, 0, 0x90]);
        bytes
    }

    #[test]
    fn dynamic_user_image_uses_guest_k16e_metadata() {
        let image = dynamic_program_image();
        let header = k16_image::parse_dynamic_k16e_v2(&image).expect("dynamic header parses");

        assert_eq!(
            DynamicUserImage::from_k16e(header),
            DynamicUserImage {
                entry_offset: 2,
                file_size: 8,
                memory_size: 12,
            }
        );
    }

    #[test]
    fn user_program_path_accepts_absolute_bin_kx_path() {
        let path = UserProgramPath::parse(b"/bin/hello.kx").expect("path parses");

        assert_eq!(
            path.components(),
            &[b"bin".as_slice(), b"hello.kx".as_slice()]
        );
    }

    #[test]
    fn user_program_path_rejects_non_program_paths() {
        assert_eq!(
            UserProgramPath::parse(b"bin/hello.kx"),
            Err(ProcessLoadError::InvalidPath)
        );
        assert_eq!(
            UserProgramPath::parse(b"/boot/kernel.kx"),
            Err(ProcessLoadError::InvalidPath)
        );
        assert_eq!(
            UserProgramPath::parse(b"/bin/../init.kx"),
            Err(ProcessLoadError::InvalidPath)
        );
        assert_eq!(
            UserProgramPath::parse(b"/bin/tools/echo.kx"),
            Err(ProcessLoadError::InvalidPath)
        );
        assert_eq!(
            UserProgramPath::parse(b"/bin/echo"),
            Err(ProcessLoadError::InvalidPath)
        );
    }

    #[test]
    fn dynamic_user_load_plan_uses_kernel_selected_arena_base_and_stack() {
        let arena = UserArena::new(0x0000_9001, 0x0001_0003).expect("arena is valid");

        let plan = plan_dynamic_user_load(
            arena,
            DynamicUserImage {
                entry_offset: 2,
                file_size: 8,
                memory_size: 12,
            },
        )
        .expect("load plan is valid");

        assert_eq!(plan.load_base, 0x0000_9002);
        assert_eq!(plan.entry_pc, 0x0000_9004);
        assert_eq!(plan.payload_dst, 0x0000_9002);
        assert_eq!(plan.payload_len, 8);
        assert_eq!(plan.zero_fill_addr, 0x0000_900a);
        assert_eq!(plan.zero_fill_len, 4);
        assert_eq!(plan.load_end, 0x0000_900e);
        assert_eq!(plan.stack_top, 0x0001_0000);
    }

    #[test]
    fn dynamic_user_load_plan_rejects_program_that_would_reach_stack() {
        let arena = UserArena::new(0x0000_9000, 0x0000_9010).expect("arena is valid");

        assert_eq!(
            plan_dynamic_user_load(
                arena,
                DynamicUserImage {
                    entry_offset: 0,
                    file_size: 8,
                    memory_size: 16,
                },
            ),
            Err(ProcessLoadError::ProgramTooLarge)
        );
    }

    #[test]
    fn dynamic_user_load_plan_rejects_invalid_dynamic_image_metadata() {
        let arena = UserArena::new(0x0000_9000, 0x0001_0000).expect("arena is valid");

        assert_eq!(
            plan_dynamic_user_load(
                arena,
                DynamicUserImage {
                    entry_offset: 13,
                    file_size: 8,
                    memory_size: 12,
                },
            ),
            Err(ProcessLoadError::InvalidImage)
        );
    }

    #[test]
    fn dynamic_user_relocation_adds_selected_load_base_to_field() {
        let plan = DynamicUserLoadPlan {
            load_base: 0x0000_9000,
            load_end: 0x0000_900c,
            entry_pc: 0x0000_9000,
            stack_top: 0x0001_0000,
            payload_dst: 0x0000_9000,
            payload_len: 8,
            zero_fill_addr: 0x0000_9008,
            zero_fill_len: 4,
        };
        let mut memory = [0u8; 16];
        memory[4..8].copy_from_slice(&0x0000_0006u32.to_le_bytes());

        apply_dynamic_relocation_to_slice(
            &mut memory,
            0x0000_9000,
            plan,
            k16_image::K16eRelocation {
                offset: 4,
                kind: k16_image::K16eRelocationKind::Abs32,
            },
        )
        .expect("relocation applies");

        assert_eq!(
            u32::from_le_bytes([memory[4], memory[5], memory[6], memory[7]]),
            0x0000_9006
        );
    }

    #[test]
    fn process_table_blocks_init_while_child_is_running() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0x0000_8000,
            stack_top: 0x0001_0000,
        });
        let child_plan = DynamicUserLoadPlan {
            load_base: 0x0000_a000,
            load_end: 0x0000_a020,
            entry_pc: 0x0000_a004,
            stack_top: 0x0001_0000,
            payload_dst: 0x0000_a000,
            payload_len: 16,
            zero_fill_addr: 0x0000_a010,
            zero_fill_len: 16,
        };

        let child = table.begin_child_run(child_plan).expect("child starts");

        assert_eq!(child.id, ProcessId::Child);
        assert_eq!(
            child.context,
            ProcessContext {
                entry_pc: 0x0000_a004,
                stack_top: 0x0001_0000,
            }
        );
        assert_eq!(table.init_state(), ProcessState::BlockedOnChild);
        assert_eq!(table.child_state(), ProcessState::Running);
    }

    #[test]
    fn process_table_rejects_nested_child_run() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0x0000_8000,
            stack_top: 0x0001_0000,
        });
        let child_plan = DynamicUserLoadPlan {
            load_base: 0x0000_a000,
            load_end: 0x0000_a020,
            entry_pc: 0x0000_a004,
            stack_top: 0x0001_0000,
            payload_dst: 0x0000_a000,
            payload_len: 16,
            zero_fill_addr: 0x0000_a010,
            zero_fill_len: 16,
        };
        table.begin_child_run(child_plan).expect("child starts");

        assert_eq!(
            table.begin_child_run(child_plan),
            Err(ProcessSwitchError::ChildAlreadyRunning)
        );
    }

    #[test]
    fn process_table_child_exit_unblocks_init_with_status() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0x0000_8000,
            stack_top: 0x0001_0000,
        });
        let child_plan = DynamicUserLoadPlan {
            load_base: 0x0000_a000,
            load_end: 0x0000_a020,
            entry_pc: 0x0000_a004,
            stack_top: 0x0001_0000,
            payload_dst: 0x0000_a000,
            payload_len: 16,
            zero_fill_addr: 0x0000_a010,
            zero_fill_len: 16,
        };
        table.begin_child_run(child_plan).expect("child starts");

        let resumed = table.finish_child(7).expect("init resumes");

        assert_eq!(resumed.id, ProcessId::Init);
        assert_eq!(resumed.child_exit_status, 7);
        assert_eq!(table.init_state(), ProcessState::Running);
        assert_eq!(table.child_state(), ProcessState::Empty);
    }
}
