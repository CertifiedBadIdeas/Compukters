#[cfg(any(not(test), feature = "host-test"))]
use core::cell::UnsafeCell;

const LOAD_ALIGNMENT: u32 = 2;
const STACK_ALIGNMENT: u32 = 4;
const ROOT_PARTITION: &[u8; 4] = b"ROOT";
const BIN_COMPONENT: &[u8] = b"bin";
const BIN_PREFIX: &[u8] = b"/bin/";
const KX_SUFFIX: &[u8] = b".kx";
const K16FS_MAX_NAME_BYTES: usize = 56;
pub const MAX_RUN_PATH_BYTES: usize = BIN_PREFIX.len() + K16FS_MAX_NAME_BYTES;
const USER_PROGRAM_LIMIT: u32 = 0x0002_0000;
#[cfg(any(not(test), feature = "host-test"))]
static PROCESS_TABLE: KernelProcessTable =
    KernelProcessTable::new(ProcessTable::new(ProcessContext {
        entry_pc: 0,
        stack_top: 0,
    }));

#[cfg(any(not(test), feature = "host-test"))]
struct KernelProcessTable {
    table: UnsafeCell<ProcessTable>,
}

#[cfg(any(not(test), feature = "host-test"))]
unsafe impl Sync for KernelProcessTable {}

#[cfg(any(not(test), feature = "host-test"))]
impl KernelProcessTable {
    const fn new(table: ProcessTable) -> Self {
        Self {
            table: UnsafeCell::new(table),
        }
    }

    unsafe fn get(&self) -> &mut ProcessTable {
        unsafe { &mut *self.table.get() }
    }
}

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

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TrapFrame {
    pub registers: [u32; 16],
    pub resume_pc: u32,
    pub stack_pointer: u32,
    pub interrupt_enable: u32,
}

impl TrapFrame {
    pub const fn zeroed() -> Self {
        Self {
            registers: [0; 16],
            resume_pc: 0,
            stack_pointer: 0,
            interrupt_enable: 0,
        }
    }
}

impl Default for TrapFrame {
    fn default() -> Self {
        Self::zeroed()
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct ChildLaunch {
    pub id: ProcessId,
    pub context: ProcessContext,
    pub frame: TrapFrame,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct InitResume {
    pub id: ProcessId,
    pub context: ProcessContext,
    pub frame: TrapFrame,
    pub child_exit_status: u32,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct ProcessTable {
    init: ProcessSlot,
    child: ProcessSlot,
    child_load_base: Option<u32>,
}

impl ProcessTable {
    pub const fn new(init_context: ProcessContext) -> Self {
        Self {
            init: ProcessSlot {
                state: ProcessState::Running,
                context: init_context,
                frame: TrapFrame::zeroed(),
                exit_status: 0,
            },
            child: ProcessSlot {
                state: ProcessState::Empty,
                context: ProcessContext {
                    entry_pc: 0,
                    stack_top: 0,
                },
                frame: TrapFrame::zeroed(),
                exit_status: 0,
            },
            child_load_base: None,
        }
    }

    pub fn initialize_init_image(
        &mut self,
        image: k16_boot_chain::LoadedImage,
    ) -> Result<(), ProcessLoadError> {
        if image.load_addr >= image.load_end || image.load_end > USER_PROGRAM_LIMIT {
            return Err(ProcessLoadError::InvalidArena);
        }
        self.init.context = ProcessContext {
            entry_pc: image.entry_pc,
            stack_top: USER_PROGRAM_LIMIT,
        };
        self.child_load_base = Some(align_up(image.load_end, LOAD_ALIGNMENT)?);
        Ok(())
    }

    pub fn child_arena_for_init_frame(
        &self,
        init_frame: TrapFrame,
    ) -> Result<UserArena, ProcessLoadError> {
        let load_base = self.child_load_base.ok_or(ProcessLoadError::InvalidArena)?;
        UserArena::new(load_base, init_frame.stack_pointer)
    }

    pub fn begin_child_run(
        &mut self,
        child_plan: DynamicUserLoadPlan,
    ) -> Result<ChildLaunch, ProcessSwitchError> {
        self.begin_child_run_from_frame(child_plan, TrapFrame::zeroed())
    }

    pub fn begin_child_run_from_frame(
        &mut self,
        child_plan: DynamicUserLoadPlan,
        init_frame: TrapFrame,
    ) -> Result<ChildLaunch, ProcessSwitchError> {
        if self.child.state == ProcessState::Running {
            return Err(ProcessSwitchError::ChildAlreadyRunning);
        }
        let context = ProcessContext {
            entry_pc: child_plan.entry_pc,
            stack_top: child_plan.stack_top,
        };
        let child_frame = child_frame_for_context(context);
        self.init.state = ProcessState::BlockedOnChild;
        self.init.context = ProcessContext {
            entry_pc: init_frame.resume_pc,
            stack_top: init_frame.stack_pointer,
        };
        self.init.frame = init_frame;
        self.child = ProcessSlot {
            state: ProcessState::Running,
            context,
            frame: child_frame,
            exit_status: 0,
        };
        Ok(ChildLaunch {
            id: ProcessId::Child,
            context,
            frame: child_frame,
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
            frame: self.init.frame,
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
    frame: TrapFrame,
    exit_status: u32,
}

pub const fn child_frame_for_context(context: ProcessContext) -> TrapFrame {
    let mut frame = TrapFrame::zeroed();
    frame.resume_pc = context.entry_pc;
    frame.stack_pointer = context.stack_top;
    frame
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn begin_loaded_child(
    child_plan: DynamicUserLoadPlan,
) -> Result<ChildLaunch, ProcessSwitchError> {
    let mut init_frame = k16_rt::TrapFrame::zeroed();
    k16_rt::save_trap_frame(&mut init_frame);
    unsafe {
        PROCESS_TABLE
            .get()
            .begin_child_run_from_frame(child_plan, TrapFrame::from(init_frame))
    }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn initialize_init_process(
    image: k16_boot_chain::LoadedImage,
) -> Result<(), ProcessLoadError> {
    unsafe { PROCESS_TABLE.get().initialize_init_image(image) }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn begin_loaded_child_from_path(path: &[u8]) -> Result<ChildLaunch, u32> {
    let mut init_frame = k16_rt::TrapFrame::zeroed();
    k16_rt::save_trap_frame(&mut init_frame);
    let init_frame = TrapFrame::from(init_frame);
    let table = unsafe { PROCESS_TABLE.get() };
    let arena = table
        .child_arena_for_init_frame(init_frame)
        .map_err(run_status_from_load_error)?;
    let child_plan = unsafe { load_dynamic_user_program_from_storage0(path, arena) }
        .map_err(run_status_from_load_error)?;
    table
        .begin_child_run_from_frame(child_plan, init_frame)
        .map_err(run_status_from_switch_error)
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn enter_child_context(launch: ChildLaunch) -> ! {
    let frame = k16_rt::TrapFrame::from(launch.frame);
    let _saved_r0 = unsafe { k16_rt::restore_trap_frame(&frame) };
    unsafe { k16_rt::iret_with_r0(0) }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn finish_child_for_exit(status: u32) -> Result<InitResume, ProcessSwitchError> {
    unsafe { PROCESS_TABLE.get().finish_child(status) }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn resume_init_context(resume: InitResume) -> ! {
    let frame = k16_rt::TrapFrame::from(resume.frame);
    let _saved_r0 = unsafe { k16_rt::restore_trap_frame(&frame) };
    unsafe { k16_rt::iret_with_r0(resume.child_exit_status) }
}

pub const fn run_status_from_load_error(error: ProcessLoadError) -> u32 {
    match error {
        ProcessLoadError::InvalidPath => k16_abi::syscall::ERROR_INVALID,
        ProcessLoadError::InvalidArena
        | ProcessLoadError::AddressOverflow
        | ProcessLoadError::ProgramTooLarge => k16_abi::syscall::ERROR_NO_MEMORY,
        ProcessLoadError::InvalidImage => k16_abi::syscall::ERROR_EXEC_FORMAT,
        ProcessLoadError::Storage => k16_abi::syscall::ERROR_NO_ENTRY,
    }
}

pub const fn run_status_from_switch_error(error: ProcessSwitchError) -> u32 {
    match error {
        ProcessSwitchError::ChildAlreadyRunning => k16_abi::syscall::ERROR_BUSY,
        ProcessSwitchError::NoRunningChild => k16_abi::syscall::ERROR_INVALID,
    }
}

#[cfg(any(not(test), feature = "host-test"))]
impl From<k16_rt::TrapFrame> for TrapFrame {
    fn from(frame: k16_rt::TrapFrame) -> Self {
        Self {
            registers: frame.registers,
            resume_pc: frame.resume_pc,
            stack_pointer: frame.stack_pointer,
            interrupt_enable: frame.interrupt_enable,
        }
    }
}

#[cfg(any(not(test), feature = "host-test"))]
impl From<TrapFrame> for k16_rt::TrapFrame {
    fn from(frame: TrapFrame) -> Self {
        Self {
            registers: frame.registers,
            resume_pc: frame.resume_pc,
            stack_pointer: frame.stack_pointer,
            interrupt_enable: frame.interrupt_enable,
        }
    }
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

    #[test]
    fn process_table_preserves_init_frame_and_builds_child_frame() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0,
            stack_top: 0,
        });
        let mut init_frame = TrapFrame::default();
        init_frame.registers[1] = 0x0000_0011;
        init_frame.resume_pc = 0x0000_8100;
        init_frame.stack_pointer = 0x0000_f000;
        init_frame.interrupt_enable = 1;
        let child_plan = DynamicUserLoadPlan {
            load_base: 0x0000_a000,
            load_end: 0x0000_a020,
            entry_pc: 0x0000_a004,
            stack_top: 0x0000_e000,
            payload_dst: 0x0000_a000,
            payload_len: 16,
            zero_fill_addr: 0x0000_a010,
            zero_fill_len: 16,
        };

        let child = table
            .begin_child_run_from_frame(child_plan, init_frame)
            .expect("child starts");

        assert_eq!(child.frame.resume_pc, child.context.entry_pc);
        assert_eq!(child.frame.stack_pointer, child.context.stack_top);
        assert_eq!(child.frame.registers, [0; 16]);
        assert_eq!(child.frame.interrupt_enable, 0);

        let resumed = table.finish_child(17).expect("init resumes");

        assert_eq!(resumed.child_exit_status, 17);
        assert_eq!(resumed.frame, init_frame);
        assert_eq!(resumed.context.entry_pc, init_frame.resume_pc);
        assert_eq!(resumed.context.stack_top, init_frame.stack_pointer);
    }

    #[test]
    fn init_loaded_image_defines_child_arena_after_init_image() {
        let image = k16_boot_chain::LoadedImage {
            entry_pc: 0x0001_0000,
            load_addr: 0x0001_0000,
            load_end: 0x0001_0a20,
        };
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0,
            stack_top: 0,
        });
        table
            .initialize_init_image(image)
            .expect("init image records");
        let mut init_frame = TrapFrame::zeroed();
        init_frame.stack_pointer = 0x0001_f000;

        let arena = table
            .child_arena_for_init_frame(init_frame)
            .expect("arena is valid");
        let plan = plan_dynamic_user_load(
            arena,
            DynamicUserImage {
                entry_offset: 0,
                file_size: 8,
                memory_size: 16,
            },
        )
        .expect("child fits after loaded init image");

        assert_eq!(plan.load_base, 0x0001_0a20);
        assert_eq!(plan.stack_top, 0x0001_f000);
    }

    #[test]
    fn run_status_maps_load_and_switch_errors_to_negative_syscall_statuses() {
        assert_eq!(
            run_status_from_load_error(ProcessLoadError::InvalidPath),
            k16_abi::syscall::ERROR_INVALID
        );
        assert_eq!(
            run_status_from_load_error(ProcessLoadError::InvalidImage),
            k16_abi::syscall::ERROR_EXEC_FORMAT
        );
        assert_eq!(
            run_status_from_switch_error(ProcessSwitchError::ChildAlreadyRunning),
            k16_abi::syscall::ERROR_BUSY
        );
    }
}
