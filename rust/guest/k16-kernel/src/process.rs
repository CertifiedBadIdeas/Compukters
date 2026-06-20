use core::cell::UnsafeCell;

const LOAD_ALIGNMENT: u32 = 2;
const STACK_ALIGNMENT: u32 = 4;
const HEAP_ALIGNMENT: u32 = 4;
const VM_PAGE_SIZE: u32 = 4096;
const TRANSLATED_USER_STACK_BYTES: u32 = VM_PAGE_SIZE * 2;
const TRANSLATED_USER_STACK_PAGES: u32 = TRANSLATED_USER_STACK_BYTES / VM_PAGE_SIZE;
const STACK_GUARD_BYTES: u32 = VM_PAGE_SIZE;
const ROOT_PARTITION: &[u8; 4] = b"ROOT";
const BIN_COMPONENT: &[u8] = b"bin";
const BIN_PREFIX: &[u8] = b"/bin/";
const KX_SUFFIX: &[u8] = b".kx";
const K16FS_MAX_NAME_BYTES: usize = 56;
pub const MAX_RUN_PATH_BYTES: usize = BIN_PREFIX.len() + K16FS_MAX_NAME_BYTES;
const CHILD_ARG_ENTRY_BYTES: u32 = 8;
const TRANSLATED_TRAP_STACK_BYTES: u32 = VM_PAGE_SIZE;
const INITIAL_USER_LOADER_SCRATCH_END: u32 = k16_storage::SCRATCH_ADDR + k16_storage::BLOCK_SIZE;
#[cfg(any(test, feature = "host-test"))]
const DEFAULT_INIT_MEMORY_END: u32 = 0x0002_5000;
// Keep relocation records outside k16_storage::SCRATCH_ADDR: storage reads use
// that block as staging, and records may straddle a storage block boundary.
const RELOCATION_RECORD_ADDR: u32 = 0x0000_0500;
const MAX_PROCESS_SLOTS: usize = 3;
const FOREGROUND_PROCESS_SLOTS: usize = MAX_PROCESS_SLOTS - 1;
const INIT_PROCESS_SLOT: usize = 0;
const INIT_PROCESS_PID: u32 = 1;
const FIRST_CHILD_PROCESS_PID: u32 = 2;
const NO_PROCESS_PID: u32 = 0;
const NO_PARENT_SLOT: u32 = u32::MAX;
const NO_CHILD_SLOT: u32 = u32::MAX;
#[cfg(any(test, feature = "host-test"))]
#[allow(dead_code)]
static PROCESS_TABLE: KernelProcessTable =
    KernelProcessTable::new(ProcessTable::new(ProcessContext {
        entry_pc: 0,
        stack_top: 0,
    }));
#[cfg(not(test))]
static RUNTIME_SLOT0_FRAME: KernelCell<k16_rt::TrapFrame> =
    KernelCell::new(k16_rt::TrapFrame::zeroed());
#[cfg(not(test))]
static RUNTIME_SLOT1_FRAME: KernelCell<k16_rt::TrapFrame> =
    KernelCell::new(k16_rt::TrapFrame::zeroed());
#[cfg(not(test))]
static RUNTIME_SLOT2_FRAME: KernelCell<k16_rt::TrapFrame> =
    KernelCell::new(k16_rt::TrapFrame::zeroed());
#[cfg(not(test))]
static mut RUNTIME_CURRENT_SLOT: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_NEXT_PID: u32 = FIRST_CHILD_PROCESS_PID;
#[cfg(not(test))]
static mut RUNTIME_SLOT0_PID: u32 = INIT_PROCESS_PID;
#[cfg(not(test))]
static mut RUNTIME_SLOT1_PID: u32 = NO_PROCESS_PID;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_PID: u32 = NO_PROCESS_PID;
#[cfg(not(test))]
static mut RUNTIME_SLOT0_PARENT_PID: u32 = NO_PROCESS_PID;
#[cfg(not(test))]
static mut RUNTIME_SLOT1_PARENT_PID: u32 = NO_PROCESS_PID;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_PARENT_PID: u32 = NO_PROCESS_PID;
#[cfg(not(test))]
static mut RUNTIME_SLOT1_PARENT: u32 = NO_PARENT_SLOT;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_PARENT: u32 = NO_PARENT_SLOT;
#[cfg(not(test))]
static mut RUNTIME_SLOT0_BLOCKED_CHILD: u32 = NO_CHILD_SLOT;
#[cfg(not(test))]
static mut RUNTIME_SLOT1_BLOCKED_CHILD: u32 = NO_CHILD_SLOT;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_BLOCKED_CHILD: u32 = NO_CHILD_SLOT;
#[cfg(not(test))]
static mut RUNTIME_SLOT0_WAIT_STATUS_PTR: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT1_WAIT_STATUS_PTR: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_WAIT_STATUS_PTR: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT0_HEAP_START: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT1_HEAP_START: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_HEAP_START: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT0_PROGRAM_BREAK: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT1_PROGRAM_BREAK: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_PROGRAM_BREAK: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT0_HEAP_LIMIT: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT1_HEAP_LIMIT: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_HEAP_LIMIT: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT0_MEMORY_START: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT1_MEMORY_START: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_MEMORY_START: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT0_MEMORY_END: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT1_MEMORY_END: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_MEMORY_END: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT0_ADDRESS_SPACE: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT1_ADDRESS_SPACE: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_ADDRESS_SPACE: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT0_KERNEL_STACK_TOP: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT1_KERNEL_STACK_TOP: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_KERNEL_STACK_TOP: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT0_BACKING_START: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT1_BACKING_START: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_BACKING_START: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT0_BACKING_FRAME_COUNT: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT1_BACKING_FRAME_COUNT: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_BACKING_FRAME_COUNT: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT0_HEAP_BACKING_START: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT1_HEAP_BACKING_START: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_HEAP_BACKING_START: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT0_HEAP_BACKING_FRAME_COUNT: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT1_HEAP_BACKING_FRAME_COUNT: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_HEAP_BACKING_FRAME_COUNT: u32 = 0;
#[cfg(not(test))]
static RUNTIME_PAGE_ALLOCATOR: KernelCell<Option<crate::page_alloc::PageFrameAllocator>> =
    KernelCell::new(None);
#[cfg(not(test))]
unsafe extern "C" {
    static __k16_image_end: u8;
}

#[cfg(any(test, feature = "host-test"))]
#[allow(dead_code)]
struct KernelProcessTable {
    table: UnsafeCell<ProcessTable>,
}

#[cfg(any(test, feature = "host-test"))]
unsafe impl Sync for KernelProcessTable {}

#[cfg(not(test))]
struct KernelCell<T> {
    value: UnsafeCell<T>,
}

#[cfg(not(test))]
unsafe impl<T> Sync for KernelCell<T> {}

#[cfg(not(test))]
impl<T> KernelCell<T> {
    const fn new(value: T) -> Self {
        Self {
            value: UnsafeCell::new(value),
        }
    }

    unsafe fn get(&self) -> &mut T {
        unsafe { &mut *self.value.get() }
    }
}

#[cfg(any(test, feature = "host-test"))]
#[allow(dead_code)]
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
pub enum HeapError {
    NoRunningChild,
    OutOfMemory,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct ProcessId(u32);

impl ProcessId {
    pub const INIT: Self = Self(INIT_PROCESS_PID);

    pub const fn from_raw(pid: u32) -> Self {
        Self(pid)
    }

    pub const fn raw(self) -> u32 {
        self.0
    }
}

#[cfg(any(test, feature = "host-test"))]
pub type ProcessState = u32;

#[cfg(any(test, feature = "host-test"))]
pub const PROCESS_STATE_EMPTY: ProcessState = 0;
#[cfg(any(test, feature = "host-test"))]
pub const PROCESS_STATE_RUNNING: ProcessState = 1;
#[cfg(any(test, feature = "host-test"))]
pub const PROCESS_STATE_BLOCKED_ON_CHILD: ProcessState = 2;
#[cfg(any(test, feature = "host-test"))]
pub const PROCESS_STATE_EXITED: ProcessState = 3;
#[cfg(any(test, feature = "host-test"))]
pub const PROCESS_STATE_READY: ProcessState = 4;

#[cfg(any(test, feature = "host-test"))]
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
struct ProcessSlotLifecycle {
    state: ProcessState,
    parent_slot: u32,
    blocked_child_slot: u32,
}

#[cfg(any(test, feature = "host-test"))]
impl ProcessSlotLifecycle {
    const fn empty() -> Self {
        Self {
            state: PROCESS_STATE_EMPTY,
            parent_slot: NO_PARENT_SLOT,
            blocked_child_slot: NO_CHILD_SLOT,
        }
    }

    const fn running_root() -> Self {
        Self {
            state: PROCESS_STATE_RUNNING,
            parent_slot: NO_PARENT_SLOT,
            blocked_child_slot: NO_CHILD_SLOT,
        }
    }

    const fn running_child(parent_slot: usize) -> Self {
        Self {
            state: PROCESS_STATE_RUNNING,
            parent_slot: parent_slot as u32,
            blocked_child_slot: NO_CHILD_SLOT,
        }
    }

    const fn ready_child(parent_slot: usize) -> Self {
        Self {
            state: PROCESS_STATE_READY,
            parent_slot: parent_slot as u32,
            blocked_child_slot: NO_CHILD_SLOT,
        }
    }

    const fn exited_child(parent_slot: usize) -> Self {
        Self {
            state: PROCESS_STATE_EXITED,
            parent_slot: parent_slot as u32,
            blocked_child_slot: NO_CHILD_SLOT,
        }
    }

    fn require_running(self) -> Result<Self, ProcessSwitchError> {
        if self.state != PROCESS_STATE_RUNNING || self.blocked_child_slot != NO_CHILD_SLOT {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        Ok(self)
    }

    fn block_on_child(self, child_slot: usize) -> Result<Self, ProcessSwitchError> {
        self.require_running()?;
        if child_slot >= MAX_PROCESS_SLOTS {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        Ok(Self {
            state: PROCESS_STATE_BLOCKED_ON_CHILD,
            parent_slot: self.parent_slot,
            blocked_child_slot: child_slot as u32,
        })
    }

    fn resume_after_child(self, child_slot: usize) -> Result<Self, ProcessSwitchError> {
        if self.state != PROCESS_STATE_BLOCKED_ON_CHILD
            || self.blocked_child_slot as usize != child_slot
        {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        Ok(Self {
            state: PROCESS_STATE_RUNNING,
            parent_slot: self.parent_slot,
            blocked_child_slot: NO_CHILD_SLOT,
        })
    }

    fn running_parent_slot(self) -> Result<usize, ProcessSwitchError> {
        if self.state != PROCESS_STATE_RUNNING || self.blocked_child_slot != NO_CHILD_SLOT {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        let parent_slot = self.parent_slot as usize;
        if parent_slot >= MAX_PROCESS_SLOTS {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        Ok(parent_slot)
    }

    fn exited_parent_slot(self) -> Result<usize, ProcessSwitchError> {
        if self.state != PROCESS_STATE_EXITED || self.blocked_child_slot != NO_CHILD_SLOT {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        let parent_slot = self.parent_slot as usize;
        if parent_slot >= MAX_PROCESS_SLOTS {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        Ok(parent_slot)
    }

    const fn is_empty(self) -> bool {
        self.state == PROCESS_STATE_EMPTY
    }
}

#[cfg(test)]
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
struct RuntimeProcessLinkage {
    parent_slot: u32,
    blocked_child_slot: u32,
}

#[cfg(test)]
impl RuntimeProcessLinkage {
    const fn root() -> Self {
        Self {
            parent_slot: NO_PARENT_SLOT,
            blocked_child_slot: NO_CHILD_SLOT,
        }
    }

    fn block_on_child(self, child_slot: usize) -> Result<Self, ProcessSwitchError> {
        if self.blocked_child_slot != NO_CHILD_SLOT || child_slot >= MAX_PROCESS_SLOTS {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        Ok(Self {
            parent_slot: self.parent_slot,
            blocked_child_slot: child_slot as u32,
        })
    }

    fn resume_after_child(self, child_slot: usize) -> Result<Self, ProcessSwitchError> {
        if self.blocked_child_slot as usize != child_slot {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        Ok(Self {
            parent_slot: self.parent_slot,
            blocked_child_slot: NO_CHILD_SLOT,
        })
    }

    fn running_parent_slot(self) -> Result<usize, ProcessSwitchError> {
        if self.blocked_child_slot != NO_CHILD_SLOT {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        let parent_slot = self.parent_slot as usize;
        if parent_slot >= MAX_PROCESS_SLOTS {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        Ok(parent_slot)
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct ProcessContext {
    pub entry_pc: u32,
    pub stack_top: u32,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct ProcessMemory {
    pub start: u32,
    pub end: u32,
}

impl ProcessMemory {
    pub const fn empty() -> Self {
        Self { start: 0, end: 0 }
    }

    pub fn new(start: u32, end: u32) -> Result<Self, ProcessLoadError> {
        if start >= end {
            return Err(ProcessLoadError::InvalidArena);
        }
        Ok(Self { start, end })
    }

    pub fn for_loaded_image(
        image: k16_boot_chain::LoadedImage,
        memory_end: u32,
    ) -> Result<Self, ProcessLoadError> {
        if image.load_addr >= image.load_end || image.load_end > memory_end {
            return Err(ProcessLoadError::InvalidArena);
        }
        Self::new(image.load_addr, memory_end)
    }

    pub fn contains_buffer(self, ptr: u32, len: u32) -> bool {
        let Some(end) = ptr.checked_add(len) else {
            return false;
        };
        ptr >= self.start && end <= self.end
    }
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
    pub address_space: Option<u32>,
    pub kernel_stack_top: Option<u32>,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct TranslatedUserLaunch {
    pub address_space: u32,
    pub entry_pc: u32,
    pub stack_top: u32,
    pub kernel_stack_top: u32,
    pub backing_pages: Option<crate::page_alloc::FrameRange>,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct ParentResume {
    pub id: ProcessId,
    pub child_id: ProcessId,
    pub context: ProcessContext,
    pub frame: TrapFrame,
    pub child_exit_status: u32,
    pub wait_status_ptr: u32,
    pub address_space: Option<u32>,
    pub kernel_stack_top: Option<u32>,
    pub exited_address_space: Option<u32>,
    pub exited_backing_pages: Option<crate::page_alloc::FrameRange>,
    pub exited_heap_pages: Option<crate::page_alloc::FrameRange>,
}

#[cfg(any(test, feature = "host-test"))]
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct ProcessReap {
    pub pid: ProcessId,
    pub status: u32,
}

impl ParentResume {
    pub const fn empty() -> Self {
        Self {
            id: ProcessId::INIT,
            child_id: ProcessId::from_raw(NO_PROCESS_PID),
            context: ProcessContext {
                entry_pc: 0,
                stack_top: 0,
            },
            frame: TrapFrame::zeroed(),
            child_exit_status: 0,
            wait_status_ptr: 0,
            address_space: None,
            kernel_stack_top: None,
            exited_address_space: None,
            exited_backing_pages: None,
            exited_heap_pages: None,
        }
    }

    pub const fn return_value(self) -> u32 {
        if self.wait_status_ptr == 0 {
            self.child_exit_status
        } else {
            self.child_id.raw()
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
struct RuntimeHeapState {
    start: u32,
    program_break: u32,
    limit: u32,
}

impl RuntimeHeapState {
    fn from_bounds(load_end: u32, stack_top: u32) -> Result<Self, HeapError> {
        let heap = HeapState::from_bounds(load_end, stack_top)?;
        Ok(Self {
            start: heap.start,
            program_break: heap.start,
            limit: heap.limit,
        })
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
struct ProcessOwnedResources {
    address_space: Option<u32>,
    backing_pages: Option<crate::page_alloc::FrameRange>,
    heap_pages: Option<crate::page_alloc::FrameRange>,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
struct ProcessResources {
    memory: ProcessMemory,
    load_base: u32,
    heap: RuntimeHeapState,
    address_space: Option<u32>,
    kernel_stack_top: Option<u32>,
    backing_pages: Option<crate::page_alloc::FrameRange>,
    heap_backing_pages: Option<crate::page_alloc::FrameRange>,
}

impl ProcessResources {
    const fn owned_resources(self) -> ProcessOwnedResources {
        ProcessOwnedResources {
            address_space: self.address_space,
            backing_pages: self.backing_pages,
            heap_pages: self.heap_backing_pages,
        }
    }

    fn for_loaded_init_image(
        image: k16_boot_chain::LoadedImage,
        memory_end: u32,
    ) -> Result<Self, ProcessLoadError> {
        let memory = ProcessMemory::for_loaded_image(image, memory_end)?;
        Ok(Self {
            memory,
            load_base: align_up(image.load_end, LOAD_ALIGNMENT)?,
            heap: RuntimeHeapState::from_bounds(image.load_end, memory.end)
                .map_err(|_| ProcessLoadError::ProgramTooLarge)?,
            address_space: None,
            kernel_stack_top: None,
            backing_pages: None,
            heap_backing_pages: None,
        })
    }

    fn for_translated_init_plan(
        plan: DynamicUserLoadPlan,
        memory_end: u32,
        translated: TranslatedUserLaunch,
    ) -> Result<Self, ProcessLoadError> {
        let memory = ProcessMemory::new(plan.load_base, translated.stack_top)?;
        if translated.kernel_stack_top != memory_end
            || translated.entry_pc != plan.entry_pc
            || translated.stack_top != plan.stack_top
            || translated.stack_top >= translated.kernel_stack_top
        {
            return Err(ProcessLoadError::InvalidArena);
        }
        Ok(Self {
            memory,
            load_base: align_up(plan.load_end, LOAD_ALIGNMENT)?,
            heap: RuntimeHeapState::from_bounds(plan.load_end, memory.end)
                .map_err(|_| ProcessLoadError::ProgramTooLarge)?,
            address_space: Some(translated.address_space),
            kernel_stack_top: Some(translated.kernel_stack_top),
            backing_pages: translated.backing_pages,
            heap_backing_pages: None,
        })
    }

    fn for_child_plan(
        child_plan: DynamicUserLoadPlan,
        argv: ChildArgv,
        translated: Option<TranslatedUserLaunch>,
    ) -> Result<Self, ProcessSwitchError> {
        Ok(Self {
            memory: ProcessMemory::new(child_plan.load_base, child_plan.stack_top)
                .map_err(|_| ProcessSwitchError::NoRunningChild)?,
            load_base: align_up(child_plan.load_end, LOAD_ALIGNMENT)
                .map_err(|_| ProcessSwitchError::NoRunningChild)?,
            heap: RuntimeHeapState::from_bounds(
                child_plan.load_end.max(argv.end),
                child_plan.stack_top,
            )
            .map_err(|_| ProcessSwitchError::NoRunningChild)?,
            address_space: translated.map(|launch| launch.address_space),
            kernel_stack_top: translated.map(|launch| launch.kernel_stack_top),
            backing_pages: translated.and_then(|launch| launch.backing_pages),
            heap_backing_pages: None,
        })
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
struct ProcessDescriptor {
    context: ProcessContext,
    frame: TrapFrame,
    resources: ProcessResources,
}

impl ProcessDescriptor {
    fn for_loaded_init_image(
        image: k16_boot_chain::LoadedImage,
        memory_end: u32,
    ) -> Result<Self, ProcessLoadError> {
        let resources = ProcessResources::for_loaded_init_image(image, memory_end)?;
        let context = ProcessContext {
            entry_pc: image.entry_pc,
            stack_top: resources.memory.end,
        };
        Ok(Self {
            context,
            frame: child_frame_for_context(context),
            resources,
        })
    }

    fn for_translated_init_plan(
        plan: DynamicUserLoadPlan,
        memory_end: u32,
        translated: TranslatedUserLaunch,
    ) -> Result<Self, ProcessLoadError> {
        let resources = ProcessResources::for_translated_init_plan(plan, memory_end, translated)?;
        let context = ProcessContext {
            entry_pc: translated.entry_pc,
            stack_top: translated.stack_top,
        };
        Ok(Self {
            context,
            frame: child_frame_for_context(context),
            resources,
        })
    }

    fn for_child_plan(
        child_plan: DynamicUserLoadPlan,
        argv: ChildArgv,
        translated: Option<TranslatedUserLaunch>,
    ) -> Result<Self, ProcessSwitchError> {
        let context = translated
            .map(|launch| ProcessContext {
                entry_pc: launch.entry_pc,
                stack_top: launch.stack_top,
            })
            .unwrap_or(ProcessContext {
                entry_pc: child_plan.entry_pc,
                stack_top: child_plan.stack_top,
            });
        let mut frame = child_frame_for_context(context);
        frame.registers[1] = argv.argc;
        frame.registers[2] = argv.table_ptr;
        Ok(Self {
            context,
            frame,
            resources: ProcessResources::for_child_plan(child_plan, argv, translated)?,
        })
    }

    const fn launch(self, id: ProcessId) -> ChildLaunch {
        ChildLaunch {
            id,
            context: self.context,
            frame: self.frame,
            address_space: self.resources.address_space,
            kernel_stack_top: self.resources.kernel_stack_top,
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
struct RuntimeForegroundSlotState {
    pid: ProcessId,
    parent_pid: Option<ProcessId>,
    parent_slot: u32,
    blocked_child_slot: u32,
    memory: Option<ProcessMemory>,
    heap: Option<RuntimeHeapState>,
    address_space: Option<u32>,
    kernel_stack_top: Option<u32>,
    backing_pages: Option<crate::page_alloc::FrameRange>,
    heap_backing_pages: Option<crate::page_alloc::FrameRange>,
}

impl RuntimeForegroundSlotState {
    const fn from_process_resources(
        pid: ProcessId,
        parent_pid: Option<ProcessId>,
        parent_slot: u32,
        resources: ProcessResources,
    ) -> Self {
        let owned_resources = resources.owned_resources();
        Self {
            pid,
            parent_pid,
            parent_slot,
            blocked_child_slot: NO_CHILD_SLOT,
            memory: Some(resources.memory),
            heap: Some(resources.heap),
            address_space: owned_resources.address_space,
            kernel_stack_top: resources.kernel_stack_top,
            backing_pages: owned_resources.backing_pages,
            heap_backing_pages: owned_resources.heap_pages,
        }
    }

    const fn owned_resources(self) -> ProcessOwnedResources {
        ProcessOwnedResources {
            address_space: self.address_space,
            backing_pages: self.backing_pages,
            heap_pages: self.heap_backing_pages,
        }
    }

    const fn cleared_after_exit(self) -> Self {
        Self {
            pid: ProcessId::from_raw(NO_PROCESS_PID),
            parent_pid: None,
            parent_slot: NO_PARENT_SLOT,
            blocked_child_slot: NO_CHILD_SLOT,
            memory: None,
            heap: None,
            address_space: None,
            kernel_stack_top: None,
            backing_pages: None,
            heap_backing_pages: None,
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum TrapReturnOverride {
    Physical,
    Translated {
        address_space: u32,
        kernel_stack_top: u32,
    },
}

#[cfg(any(test, feature = "host-test"))]
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
struct ProcessSlot {
    pid: ProcessId,
    parent_pid: Option<ProcessId>,
    state: ProcessState,
    parent_slot: u32,
    blocked_child_slot: u32,
    wait_status_ptr: u32,
    context: ProcessContext,
    frame: TrapFrame,
    exit_status: u32,
    memory: ProcessMemory,
    load_base: u32,
    heap_start: u32,
    program_break: u32,
    heap_limit: u32,
    address_space: Option<u32>,
    kernel_stack_top: Option<u32>,
    backing_pages: Option<crate::page_alloc::FrameRange>,
    heap_backing_pages: Option<crate::page_alloc::FrameRange>,
}

#[cfg(any(test, feature = "host-test"))]
impl ProcessSlot {
    const fn empty() -> Self {
        let lifecycle = ProcessSlotLifecycle::empty();
        Self {
            pid: ProcessId::from_raw(NO_PROCESS_PID),
            parent_pid: None,
            state: lifecycle.state,
            parent_slot: lifecycle.parent_slot,
            blocked_child_slot: lifecycle.blocked_child_slot,
            wait_status_ptr: 0,
            context: ProcessContext {
                entry_pc: 0,
                stack_top: 0,
            },
            frame: TrapFrame::zeroed(),
            exit_status: 0,
            memory: ProcessMemory::empty(),
            load_base: 0,
            heap_start: 0,
            program_break: 0,
            heap_limit: 0,
            address_space: None,
            kernel_stack_top: None,
            backing_pages: None,
            heap_backing_pages: None,
        }
    }

    const fn init(context: ProcessContext) -> Self {
        let lifecycle = ProcessSlotLifecycle::running_root();
        Self {
            pid: ProcessId::INIT,
            parent_pid: None,
            state: lifecycle.state,
            parent_slot: lifecycle.parent_slot,
            blocked_child_slot: lifecycle.blocked_child_slot,
            wait_status_ptr: 0,
            context,
            frame: TrapFrame::zeroed(),
            exit_status: 0,
            memory: ProcessMemory::empty(),
            load_base: 0,
            heap_start: 0,
            program_break: 0,
            heap_limit: 0,
            address_space: None,
            kernel_stack_top: None,
            backing_pages: None,
            heap_backing_pages: None,
        }
    }

    fn clear(&mut self) {
        *self = Self::empty();
    }

    const fn lifecycle(&self) -> ProcessSlotLifecycle {
        ProcessSlotLifecycle {
            state: self.state,
            parent_slot: self.parent_slot,
            blocked_child_slot: self.blocked_child_slot,
        }
    }

    fn set_lifecycle(&mut self, lifecycle: ProcessSlotLifecycle) {
        self.parent_slot = lifecycle.parent_slot;
        self.blocked_child_slot = lifecycle.blocked_child_slot;
        unsafe { core::ptr::write_volatile(&mut self.state, lifecycle.state) };
    }

    fn initialize_from_descriptor(&mut self, descriptor: ProcessDescriptor) {
        self.context = descriptor.context;
        self.frame = descriptor.frame;
        self.memory = descriptor.resources.memory;
        self.load_base = descriptor.resources.load_base;
        self.heap_start = descriptor.resources.heap.start;
        self.program_break = descriptor.resources.heap.program_break;
        self.heap_limit = descriptor.resources.heap.limit;
        self.address_space = descriptor.resources.address_space;
        self.kernel_stack_top = descriptor.resources.kernel_stack_top;
        self.backing_pages = descriptor.resources.backing_pages;
        self.heap_backing_pages = descriptor.resources.heap_backing_pages;
    }

    fn initialize_identity(&mut self, pid: ProcessId, parent_pid: Option<ProcessId>) {
        self.pid = pid;
        self.parent_pid = parent_pid;
    }

    fn mark_exited(&mut self, status: u32) -> ProcessOwnedResources {
        let resources = self.owned_resources();
        let pid = self.pid;
        let parent_pid = self.parent_pid;
        let parent_slot = self.parent_slot as usize;
        *self = Self::empty();
        self.pid = pid;
        self.parent_pid = parent_pid;
        self.exit_status = status;
        self.set_lifecycle(ProcessSlotLifecycle::exited_child(parent_slot));
        resources
    }

    const fn owned_resources(self) -> ProcessOwnedResources {
        ProcessOwnedResources {
            address_space: self.address_space,
            backing_pages: self.backing_pages,
            heap_pages: self.heap_backing_pages,
        }
    }
}

#[cfg(any(test, feature = "host-test"))]
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct ProcessTable {
    slots: [ProcessSlot; MAX_PROCESS_SLOTS],
    current_slot: usize,
    next_pid: u32,
}

#[cfg(any(test, feature = "host-test"))]
impl ProcessTable {
    pub const fn new(init_context: ProcessContext) -> Self {
        Self {
            slots: [
                ProcessSlot::init(init_context),
                ProcessSlot::empty(),
                ProcessSlot::empty(),
            ],
            current_slot: INIT_PROCESS_SLOT,
            next_pid: FIRST_CHILD_PROCESS_PID,
        }
    }

    pub fn initialize_init_image(
        &mut self,
        image: k16_boot_chain::LoadedImage,
    ) -> Result<(), ProcessLoadError> {
        self.initialize_init_image_in_memory(image, DEFAULT_INIT_MEMORY_END)
    }

    pub fn initialize_init_image_in_memory(
        &mut self,
        image: k16_boot_chain::LoadedImage,
        memory_end: u32,
    ) -> Result<(), ProcessLoadError> {
        let descriptor = ProcessDescriptor::for_loaded_init_image(image, memory_end)?;
        let init_slot = &mut self.slots[INIT_PROCESS_SLOT];
        init_slot.initialize_from_descriptor(descriptor);
        Ok(())
    }

    pub fn initialize_translated_init_plan_in_memory(
        &mut self,
        plan: DynamicUserLoadPlan,
        memory_end: u32,
        translated: TranslatedUserLaunch,
    ) -> Result<ChildLaunch, ProcessLoadError> {
        let descriptor = ProcessDescriptor::for_translated_init_plan(plan, memory_end, translated)?;
        let init_slot = &mut self.slots[INIT_PROCESS_SLOT];
        init_slot.initialize_from_descriptor(descriptor);
        self.current_slot = INIT_PROCESS_SLOT;
        Ok(descriptor.launch(ProcessId::INIT))
    }

    pub fn child_arena_for_init_frame(
        &self,
        init_frame: TrapFrame,
    ) -> Result<UserArena, ProcessLoadError> {
        let caller = &self.slots[self.current_slot];
        if caller.load_base == 0 {
            return Err(ProcessLoadError::Storage);
        }
        let load_base = caller.program_break.max(caller.load_base);
        let arena_end = init_frame.stack_pointer.min(caller.memory.end);
        UserArena::new(load_base, arena_end).map_err(|_| ProcessLoadError::ProgramTooLarge)
    }

    pub fn begin_child_run(
        &mut self,
        child_plan: DynamicUserLoadPlan,
    ) -> Result<ChildLaunch, ProcessSwitchError> {
        self.begin_child_run_from_frame(child_plan, TrapFrame::zeroed())
    }

    pub fn begin_child_run_with_argv(
        &mut self,
        child_plan: DynamicUserLoadPlan,
        argv: ChildArgv,
    ) -> Result<ChildLaunch, ProcessSwitchError> {
        self.begin_child_run_from_frame_and_argv(child_plan, TrapFrame::zeroed(), argv)
    }

    pub fn begin_child_run_from_frame(
        &mut self,
        child_plan: DynamicUserLoadPlan,
        init_frame: TrapFrame,
    ) -> Result<ChildLaunch, ProcessSwitchError> {
        self.begin_child_run_from_frame_and_argv(child_plan, init_frame, ChildArgv::empty())
    }

    pub fn begin_child_run_from_frame_and_argv(
        &mut self,
        child_plan: DynamicUserLoadPlan,
        init_frame: TrapFrame,
        argv: ChildArgv,
    ) -> Result<ChildLaunch, ProcessSwitchError> {
        self.begin_child_run_from_frame_argv_and_translation(child_plan, init_frame, argv, None)
    }

    pub fn spawn_child(
        &mut self,
        child_plan: DynamicUserLoadPlan,
    ) -> Result<ProcessId, ProcessSwitchError> {
        self.spawn_child_with_argv(child_plan, ChildArgv::empty())
    }

    pub fn spawn_child_with_argv(
        &mut self,
        child_plan: DynamicUserLoadPlan,
        argv: ChildArgv,
    ) -> Result<ProcessId, ProcessSwitchError> {
        self.spawn_child_from_plan_argv_and_translation(child_plan, argv, None)
    }

    fn spawn_child_from_plan_argv_and_translation(
        &mut self,
        child_plan: DynamicUserLoadPlan,
        argv: ChildArgv,
        translated: Option<TranslatedUserLaunch>,
    ) -> Result<ProcessId, ProcessSwitchError> {
        self.slots[self.current_slot]
            .lifecycle()
            .require_running()?;
        let child_slot = self
            .next_empty_child_slot()
            .ok_or(ProcessSwitchError::ChildAlreadyRunning)?;
        let parent_slot = self.current_slot;
        if parent_slot == child_slot {
            return Err(ProcessSwitchError::ChildAlreadyRunning);
        }
        let descriptor = ProcessDescriptor::for_child_plan(child_plan, argv, translated)?;
        let child_pid = self.allocate_pid()?;
        let parent_pid = self.slots[parent_slot].pid;
        let child = &mut self.slots[child_slot];
        child.set_lifecycle(ProcessSlotLifecycle::ready_child(parent_slot));
        child.initialize_identity(child_pid, Some(parent_pid));
        child.initialize_from_descriptor(descriptor);
        child.exit_status = 0;
        Ok(child_pid)
    }

    pub fn wait_for_child(
        &mut self,
        pid: ProcessId,
        wait_frame: TrapFrame,
        wait_status_ptr: u32,
    ) -> Result<ChildLaunch, ProcessSwitchError> {
        let parent_lifecycle = self.slots[self.current_slot]
            .lifecycle()
            .require_running()?;
        let parent_slot = self.current_slot;
        let child_slot = self
            .ready_child_slot(pid)
            .ok_or(ProcessSwitchError::NoRunningChild)?;
        let parent_lifecycle = parent_lifecycle.block_on_child(child_slot)?;
        let parent = &mut self.slots[parent_slot];
        parent.set_lifecycle(parent_lifecycle);
        parent.context = ProcessContext {
            entry_pc: wait_frame.resume_pc,
            stack_top: wait_frame.stack_pointer,
        };
        parent.frame = wait_frame;
        parent.wait_status_ptr = wait_status_ptr;
        let child = &mut self.slots[child_slot];
        child.set_lifecycle(ProcessSlotLifecycle::running_child(parent_slot));
        self.current_slot = child_slot;
        Ok(ChildLaunch {
            id: child.pid,
            context: child.context,
            frame: child.frame,
            address_space: child.address_space,
            kernel_stack_top: child.kernel_stack_top,
        })
    }

    fn begin_child_run_from_frame_argv_and_translation(
        &mut self,
        child_plan: DynamicUserLoadPlan,
        init_frame: TrapFrame,
        argv: ChildArgv,
        translated: Option<TranslatedUserLaunch>,
    ) -> Result<ChildLaunch, ProcessSwitchError> {
        let parent_lifecycle = self.slots[self.current_slot]
            .lifecycle()
            .require_running()?;
        let child_slot = self
            .next_empty_child_slot()
            .ok_or(ProcessSwitchError::ChildAlreadyRunning)?;
        let parent_slot = self.current_slot;
        if parent_slot == child_slot {
            return Err(ProcessSwitchError::ChildAlreadyRunning);
        }
        let descriptor = ProcessDescriptor::for_child_plan(child_plan, argv, translated)?;
        let child_pid = self.allocate_pid()?;
        let parent_pid = self.slots[parent_slot].pid;
        let parent_lifecycle = parent_lifecycle.block_on_child(child_slot)?;
        let parent = &mut self.slots[parent_slot];
        parent.set_lifecycle(parent_lifecycle);
        parent.context = ProcessContext {
            entry_pc: init_frame.resume_pc,
            stack_top: init_frame.stack_pointer,
        };
        parent.frame = init_frame;
        let child = &mut self.slots[child_slot];
        child.set_lifecycle(ProcessSlotLifecycle::running_child(parent_slot));
        child.initialize_identity(child_pid, Some(parent_pid));
        child.initialize_from_descriptor(descriptor);
        child.exit_status = 0;
        self.current_slot = child_slot;
        Ok(descriptor.launch(child_pid))
    }

    pub fn begin_translated_child_run(
        &mut self,
        child_plan: DynamicUserLoadPlan,
        translated: TranslatedUserLaunch,
    ) -> Result<ChildLaunch, ProcessSwitchError> {
        self.begin_translated_child_run_with_argv(child_plan, ChildArgv::empty(), translated)
    }

    pub fn begin_translated_child_run_with_argv(
        &mut self,
        child_plan: DynamicUserLoadPlan,
        argv: ChildArgv,
        translated: TranslatedUserLaunch,
    ) -> Result<ChildLaunch, ProcessSwitchError> {
        self.begin_child_run_from_frame_argv_and_translation(
            child_plan,
            TrapFrame::zeroed(),
            argv,
            Some(translated),
        )
    }

    pub fn finish_child(&mut self, status: u32) -> Result<ParentResume, ProcessSwitchError> {
        let resume = self.finish_child_and_record_exit(status)?;
        self.reap_exited_child(ProcessId::from_raw(NO_PROCESS_PID))?;
        Ok(resume)
    }

    pub fn finish_child_and_record_exit(
        &mut self,
        status: u32,
    ) -> Result<ParentResume, ProcessSwitchError> {
        if self.current_slot == INIT_PROCESS_SLOT {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        let child_slot = self.current_slot;
        let child_lifecycle = self.slots[child_slot].lifecycle();
        let parent_slot = child_lifecycle.running_parent_slot()?;
        let parent_lifecycle = self.slots[parent_slot]
            .lifecycle()
            .resume_after_child(child_slot)?;
        let child_pid = self.slots[child_slot].pid;
        let wait_status_ptr = self.slots[parent_slot].wait_status_ptr;
        let exited_resources = self.slots[child_slot].mark_exited(status);
        self.slots[parent_slot].exit_status = status;
        self.slots[parent_slot].wait_status_ptr = 0;
        self.slots[parent_slot].set_lifecycle(parent_lifecycle);
        self.current_slot = parent_slot;
        let parent = self.slots[parent_slot];
        Ok(ParentResume {
            id: parent.pid,
            child_id: child_pid,
            context: parent.context,
            frame: parent.frame,
            child_exit_status: status,
            wait_status_ptr,
            address_space: parent.address_space,
            kernel_stack_top: parent.kernel_stack_top,
            exited_address_space: exited_resources.address_space,
            exited_backing_pages: exited_resources.backing_pages,
            exited_heap_pages: exited_resources.heap_pages,
        })
    }

    pub fn reap_exited_child(&mut self, pid: ProcessId) -> Result<ProcessReap, ProcessSwitchError> {
        let parent_pid = self.slots[self.current_slot].pid;
        let mut slot = 1;
        while slot < MAX_PROCESS_SLOTS {
            let child = self.slots[slot];
            if child.lifecycle().exited_parent_slot().is_ok()
                && child.parent_pid == Some(parent_pid)
                && (pid.raw() == NO_PROCESS_PID || child.pid == pid)
            {
                let reap = ProcessReap {
                    pid: child.pid,
                    status: child.exit_status,
                };
                self.slots[slot].clear();
                return Ok(reap);
            }
            slot += 1;
        }
        Err(ProcessSwitchError::NoRunningChild)
    }

    pub const fn init_state(&self) -> ProcessState {
        self.slots[INIT_PROCESS_SLOT].state
    }

    pub const fn init_pid(&self) -> ProcessId {
        self.slots[INIT_PROCESS_SLOT].pid
    }

    pub const fn init_parent_pid(&self) -> Option<ProcessId> {
        self.slots[INIT_PROCESS_SLOT].parent_pid
    }

    pub const fn current_pid(&self) -> ProcessId {
        self.slots[self.current_slot].pid
    }

    pub const fn current_parent_pid(&self) -> Option<ProcessId> {
        self.slots[self.current_slot].parent_pid
    }

    pub const fn child_state(&self) -> ProcessState {
        self.slots[1].state
    }

    pub fn program_break(&self) -> Result<u32, HeapError> {
        let (_, program_break, _) = self.current_heap()?;
        Ok(program_break)
    }

    pub fn heap_limit(&self) -> Result<u32, HeapError> {
        let (_, _, heap_limit) = self.current_heap()?;
        Ok(heap_limit)
    }

    pub fn current_memory(&self) -> Result<ProcessMemory, HeapError> {
        let current = self.slots[self.current_slot].memory;
        if current.start == 0 {
            return Err(HeapError::NoRunningChild);
        }
        Ok(current)
    }

    pub fn current_address_space(&self) -> Option<u32> {
        self.slots[self.current_slot].address_space
    }

    #[cfg(test)]
    pub fn set_current_address_space(&mut self, address_space: Option<u32>) {
        self.slots[self.current_slot].address_space = address_space;
    }

    #[cfg(test)]
    pub fn set_current_memory(&mut self, memory: ProcessMemory) {
        self.slots[self.current_slot].memory = memory;
    }

    pub fn current_contains_buffer(&self, ptr: u32, len: u32) -> bool {
        self.current_memory()
            .map(|memory| memory.contains_buffer(ptr, len))
            .unwrap_or(false)
    }

    pub fn set_program_break(&mut self, address: u32) -> Result<u32, HeapError> {
        let (heap_start, _, heap_limit) = self.current_heap()?;
        if address < heap_start || address > heap_limit {
            return Err(HeapError::OutOfMemory);
        }
        self.slots[self.current_slot].program_break = address;
        Ok(address)
    }

    pub fn grow_program_break(&mut self, delta: u32) -> Result<u32, HeapError> {
        let old_break = self.program_break()?;
        let new_break = old_break.checked_add(delta).ok_or(HeapError::OutOfMemory)?;
        self.set_program_break(new_break)?;
        Ok(old_break)
    }

    fn next_empty_child_slot(&self) -> Option<usize> {
        let mut slot = 1;
        while slot < MAX_PROCESS_SLOTS {
            if self.slots[slot].lifecycle().is_empty() {
                return Some(slot);
            }
            slot += 1;
        }
        None
    }

    fn ready_child_slot(&self, pid: ProcessId) -> Option<usize> {
        let parent_pid = self.slots[self.current_slot].pid;
        let mut slot = 1;
        while slot < MAX_PROCESS_SLOTS {
            let child = self.slots[slot];
            if child.state == PROCESS_STATE_READY
                && child.parent_pid == Some(parent_pid)
                && (pid.raw() == NO_PROCESS_PID || child.pid == pid)
            {
                return Some(slot);
            }
            slot += 1;
        }
        None
    }

    fn allocate_pid(&mut self) -> Result<ProcessId, ProcessSwitchError> {
        let pid = self.next_pid;
        self.next_pid = self
            .next_pid
            .checked_add(1)
            .ok_or(ProcessSwitchError::ChildAlreadyRunning)?;
        Ok(ProcessId::from_raw(pid))
    }

    fn current_heap(&self) -> Result<(u32, u32, u32), HeapError> {
        let current = self.slots[self.current_slot];
        if current.heap_start == 0 {
            return Err(HeapError::NoRunningChild);
        }
        Ok((
            current.heap_start,
            current.program_break,
            current.heap_limit,
        ))
    }
}

pub const fn child_frame_for_context(context: ProcessContext) -> TrapFrame {
    let mut frame = TrapFrame::zeroed();
    frame.resume_pc = context.entry_pc;
    frame.stack_pointer = context.stack_top;
    frame
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct ChildArgv {
    pub argc: u32,
    pub table_ptr: u32,
    pub end: u32,
}

impl ChildArgv {
    pub const fn empty() -> Self {
        Self {
            argc: 0,
            table_ptr: 0,
            end: 0,
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
struct ChildArgvLayout {
    argc: u32,
    table_ptr: u32,
    arg_data_ptr: u32,
    end: u32,
}

impl ChildArgvLayout {
    fn new(plan: DynamicUserLoadPlan, args: &[&[u8]]) -> Result<Self, ProcessLoadError> {
        if args.is_empty() {
            return Ok(Self {
                argc: 0,
                table_ptr: 0,
                arg_data_ptr: 0,
                end: 0,
            });
        }
        if args.len() > k16_abi::syscall::MAX_RUN_ARGS {
            return Err(ProcessLoadError::InvalidPath);
        }
        let table_ptr = align_up(plan.load_end, STACK_ALIGNMENT)?;
        let table_bytes = (args.len() as u32)
            .checked_mul(CHILD_ARG_ENTRY_BYTES)
            .ok_or(ProcessLoadError::AddressOverflow)?;
        let arg_data_ptr = table_ptr
            .checked_add(table_bytes)
            .ok_or(ProcessLoadError::AddressOverflow)?;
        let mut cursor = arg_data_ptr;
        for arg in args {
            if arg.len() > k16_abi::syscall::MAX_RUN_ARG_BYTES {
                return Err(ProcessLoadError::InvalidPath);
            }
            cursor = cursor
                .checked_add(arg.len() as u32)
                .ok_or(ProcessLoadError::AddressOverflow)?;
        }
        let end = align_up(cursor, HEAP_ALIGNMENT)?;
        let heap_limit = heap_limit_from_stack_top(plan.stack_top)
            .map_err(|_| ProcessLoadError::ProgramTooLarge)?;
        if end > heap_limit {
            return Err(ProcessLoadError::ProgramTooLarge);
        }
        Ok(Self {
            argc: args.len() as u32,
            table_ptr,
            arg_data_ptr,
            end,
        })
    }

    const fn child_argv(self) -> ChildArgv {
        ChildArgv {
            argc: self.argc,
            table_ptr: self.table_ptr,
            end: self.end,
        }
    }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn initialize_init_process(
    image: k16_boot_chain::LoadedImage,
    memory_end: u32,
) -> Result<(), ProcessLoadError> {
    #[cfg(not(test))]
    {
        let descriptor = ProcessDescriptor::for_loaded_init_image(image, memory_end)?;
        unsafe {
            write_runtime_word(
                core::ptr::addr_of_mut!(RUNTIME_CURRENT_SLOT),
                INIT_PROCESS_SLOT as u32,
            );
            write_runtime_word(
                core::ptr::addr_of_mut!(RUNTIME_NEXT_PID),
                FIRST_CHILD_PROCESS_PID,
            );
            write_runtime_process_identity(INIT_PROCESS_SLOT, ProcessId::INIT, None);
            write_runtime_process_identity(1, ProcessId::from_raw(NO_PROCESS_PID), None);
            write_runtime_process_identity(2, ProcessId::from_raw(NO_PROCESS_PID), None);
            write_runtime_process_linkage(INIT_PROCESS_SLOT, NO_PARENT_SLOT, NO_CHILD_SLOT);
            write_runtime_process_linkage(1, NO_PARENT_SLOT, NO_CHILD_SLOT);
            write_runtime_process_linkage(2, NO_PARENT_SLOT, NO_CHILD_SLOT);
            write_runtime_wait_status_ptr(INIT_PROCESS_SLOT, 0);
            write_runtime_wait_status_ptr(1, 0);
            write_runtime_wait_status_ptr(2, 0);
            write_runtime_process_resources(INIT_PROCESS_SLOT, descriptor.resources);
            *runtime_slot_frame(INIT_PROCESS_SLOT).get() =
                k16_rt::TrapFrame::from(descriptor.frame);
        }
        return Ok(());
    }
    #[cfg(test)]
    {
        unsafe {
            PROCESS_TABLE
                .get()
                .initialize_init_image_in_memory(image, memory_end)
        }
    }
}

#[cfg(not(test))]
pub unsafe fn begin_translated_init_from_storage0(
    path: &[u8],
    boot_info: k16_abi::computer::profile::BootInfo,
) -> Result<ChildLaunch, ProcessLoadError> {
    let kernel_image_end = unsafe { initial_user_kernel_image_end() };
    let (arena, kernel_stack_top) =
        translated_init_user_arena(boot_info.program_base, boot_info.ram_size, unsafe {
            initial_user_kernel_reserved_end()
        })?;
    let mut allocator = crate::page_alloc::PageFrameAllocator::new_for_kernel(
        translated_init_kernel_reserved_ranges(
            boot_info.program_base,
            boot_info.ram_size,
            kernel_image_end,
            kernel_stack_top,
        ),
    )
    .map_err(page_alloc_error_to_process_load_error)?;
    let mapped_child_plan =
        unsafe { load_dynamic_user_program_from_storage0_mapped(path, arena, &mut allocator)? };
    let child_plan = mapped_child_plan.virtual_plan();
    let translated = match unsafe {
        create_translated_user_launch_from_mapped(mapped_child_plan, kernel_stack_top)
    } {
        Ok(translated) => translated,
        Err(error) => {
            let _ = free_mapped_dynamic_user_load_plan(mapped_child_plan, &mut allocator);
            return Err(error);
        }
    };
    let descriptor = ProcessDescriptor::for_translated_init_plan(
        child_plan,
        translated.kernel_stack_top,
        translated,
    )?;
    unsafe {
        write_runtime_word(
            core::ptr::addr_of_mut!(RUNTIME_CURRENT_SLOT),
            INIT_PROCESS_SLOT as u32,
        );
        write_runtime_word(
            core::ptr::addr_of_mut!(RUNTIME_NEXT_PID),
            FIRST_CHILD_PROCESS_PID,
        );
        write_runtime_process_identity(INIT_PROCESS_SLOT, ProcessId::INIT, None);
        write_runtime_process_identity(1, ProcessId::from_raw(NO_PROCESS_PID), None);
        write_runtime_process_identity(2, ProcessId::from_raw(NO_PROCESS_PID), None);
        write_runtime_process_linkage(INIT_PROCESS_SLOT, NO_PARENT_SLOT, NO_CHILD_SLOT);
        write_runtime_process_linkage(1, NO_PARENT_SLOT, NO_CHILD_SLOT);
        write_runtime_process_linkage(2, NO_PARENT_SLOT, NO_CHILD_SLOT);
        write_runtime_wait_status_ptr(INIT_PROCESS_SLOT, 0);
        write_runtime_wait_status_ptr(1, 0);
        write_runtime_wait_status_ptr(2, 0);
        write_runtime_process_resources(INIT_PROCESS_SLOT, descriptor.resources);
        *runtime_slot_frame(INIT_PROCESS_SLOT).get() = k16_rt::TrapFrame::from(descriptor.frame);
        *RUNTIME_PAGE_ALLOCATOR.get() = Some(allocator);
    }
    Ok(descriptor.launch(ProcessId::INIT))
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn begin_loaded_child_from_path(path: &[u8]) -> Result<ChildLaunch, u32> {
    #[cfg(not(test))]
    {
        return unsafe { begin_loaded_child_runtime(path, &[]) };
    }
    #[cfg(test)]
    {
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
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn begin_loaded_child_from_argv_request(request: &[u8]) -> Result<ChildLaunch, u32> {
    let request = RunArgvRequest::parse(request).map_err(run_status_from_load_error)?;
    #[cfg(not(test))]
    {
        return unsafe { begin_loaded_child_runtime(request.path, request.args()) };
    }
    #[cfg(test)]
    {
        let mut init_frame = k16_rt::TrapFrame::zeroed();
        k16_rt::save_trap_frame(&mut init_frame);
        let init_frame = TrapFrame::from(init_frame);
        let table = unsafe { PROCESS_TABLE.get() };
        let arena = table
            .child_arena_for_init_frame(init_frame)
            .map_err(run_status_from_load_error)?;
        let child_plan = unsafe { load_dynamic_user_program_from_storage0(request.path, arena) }
            .map_err(run_status_from_load_error)?;
        let argv = unsafe { install_child_argv(child_plan, request.args()) }
            .map_err(run_status_from_load_error)?;
        table
            .begin_child_run_from_frame_and_argv(child_plan, init_frame, argv)
            .map_err(run_status_from_switch_error)
    }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn spawn_loaded_child_from_argv_request(request: &[u8]) -> Result<ProcessId, u32> {
    let request = RunArgvRequest::parse_with_magic(request, k16_abi::syscall::SPAWN_ARGV_MAGIC)
        .map_err(run_status_from_load_error)?;
    #[cfg(not(test))]
    {
        return unsafe { spawn_loaded_child_runtime(request.path, request.args()) };
    }
    #[cfg(test)]
    {
        let mut caller_frame = k16_rt::TrapFrame::zeroed();
        k16_rt::save_trap_frame(&mut caller_frame);
        let caller_frame = TrapFrame::from(caller_frame);
        let table = unsafe { PROCESS_TABLE.get() };
        let arena = table
            .child_arena_for_init_frame(caller_frame)
            .map_err(run_status_from_load_error)?;
        let child_plan = unsafe { load_dynamic_user_program_from_storage0(request.path, arena) }
            .map_err(run_status_from_load_error)?;
        let argv = unsafe { install_child_argv(child_plan, request.args()) }
            .map_err(run_status_from_load_error)?;
        table
            .spawn_child_with_argv(child_plan, argv)
            .map_err(run_status_from_switch_error)
    }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn wait_for_child_from_syscall(
    pid: ProcessId,
    wait_status_ptr: u32,
) -> Result<ChildLaunch, u32> {
    #[cfg(not(test))]
    {
        return unsafe { wait_for_runtime_child(pid, wait_status_ptr) }
            .map_err(run_status_from_switch_error);
    }
    #[cfg(test)]
    {
        let mut wait_frame = k16_rt::TrapFrame::zeroed();
        k16_rt::save_trap_frame(&mut wait_frame);
        unsafe { PROCESS_TABLE.get() }
            .wait_for_child(pid, TrapFrame::from(wait_frame), wait_status_ptr)
            .map_err(run_status_from_switch_error)
    }
}

#[cfg(not(test))]
unsafe fn begin_loaded_child_runtime(path: &[u8], args: &[&[u8]]) -> Result<ChildLaunch, u32> {
    match unsafe { load_runtime_child(path, args, RuntimeChildStart::Enter)? } {
        RuntimeChildLoadResult::Launch(launch) => Ok(launch),
        RuntimeChildLoadResult::Pid(_) => Err(run_status_from_switch_error(
            ProcessSwitchError::NoRunningChild,
        )),
    }
}

#[cfg(not(test))]
unsafe fn spawn_loaded_child_runtime(path: &[u8], args: &[&[u8]]) -> Result<ProcessId, u32> {
    match unsafe { load_runtime_child(path, args, RuntimeChildStart::Ready)? } {
        RuntimeChildLoadResult::Pid(pid) => Ok(pid),
        RuntimeChildLoadResult::Launch(_) => Err(run_status_from_switch_error(
            ProcessSwitchError::NoRunningChild,
        )),
    }
}

#[cfg(not(test))]
#[derive(Clone, Copy, PartialEq, Eq)]
enum RuntimeChildStart {
    Enter,
    Ready,
}

#[cfg(not(test))]
enum RuntimeChildLoadResult {
    Launch(ChildLaunch),
    Pid(ProcessId),
}

#[cfg(not(test))]
unsafe fn load_runtime_child(
    path: &[u8],
    args: &[&[u8]],
    start: RuntimeChildStart,
) -> Result<RuntimeChildLoadResult, u32> {
    let current_slot = unsafe { runtime_current_slot() };
    let current_address_space = unsafe { read_runtime_address_space(current_slot) };
    let _child_slot = runtime_child_slot_for_parent(current_slot, current_address_space)
        .map_err(run_status_from_switch_error)?;
    let caller_frame = unsafe { save_runtime_process_frame(current_slot) };
    let caller_program_break =
        unsafe { read_runtime_word(runtime_slot_program_break_ptr(current_slot)) };
    let caller_memory = unsafe { read_runtime_process_memory(current_slot) }
        .ok_or_else(|| run_status_from_load_error(ProcessLoadError::Storage))?;
    if caller_program_break == 0 {
        return Err(run_status_from_load_error(ProcessLoadError::Storage));
    }
    let translated_child = should_translate_runtime_child_path(path);
    let parent_stack_limit = caller_frame.stack_pointer.min(caller_memory.end);
    let (arena, kernel_stack_top) = if translated_child {
        let kernel_stack_top = translated_child_kernel_stack_top(parent_stack_limit)
            .map_err(|_| run_status_from_load_error(ProcessLoadError::ProgramTooLarge))?;
        let arena = translated_child_user_arena(caller_memory)
            .map_err(|_| run_status_from_load_error(ProcessLoadError::ProgramTooLarge))?;
        (arena, kernel_stack_top)
    } else {
        let arena = UserArena::new(caller_program_break, parent_stack_limit)
            .map_err(|_| run_status_from_load_error(ProcessLoadError::ProgramTooLarge))?;
        (arena, 0)
    };
    if translated_child {
        let allocator = unsafe { (*RUNTIME_PAGE_ALLOCATOR.get()).as_mut() }
            .ok_or_else(|| run_status_from_load_error(ProcessLoadError::Storage))?;
        let mapped_child_plan =
            unsafe { load_dynamic_user_program_from_storage0_mapped(path, arena, allocator) }
                .map_err(run_status_from_load_error)?;
        let child_plan = mapped_child_plan.virtual_plan();
        let argv = match unsafe { install_mapped_child_argv(mapped_child_plan, args) } {
            Ok(argv) => argv,
            Err(error) => {
                let _ = free_mapped_dynamic_user_load_plan(mapped_child_plan, allocator);
                return Err(run_status_from_load_error(error));
            }
        };
        let translated = match unsafe {
            create_translated_user_launch_from_mapped(mapped_child_plan, kernel_stack_top)
        } {
            Ok(translated) => translated,
            Err(error) => {
                let _ = free_mapped_dynamic_user_load_plan(mapped_child_plan, allocator);
                return Err(run_status_from_load_error(error));
            }
        };
        return match unsafe {
            start_runtime_child_plan_with_argv(child_plan, argv, Some(translated), start)
        } {
            Ok(result) => Ok(result),
            Err(error) => {
                let _ = unsafe { mmu0_destroy_address_space(translated.address_space) };
                let _ = free_mapped_dynamic_user_load_plan(mapped_child_plan, allocator);
                Err(run_status_from_switch_error(error))
            }
        };
    }
    let child_plan = unsafe { load_dynamic_user_program_from_storage0(path, arena) }
        .map_err(run_status_from_load_error)?;
    let argv =
        unsafe { install_child_argv(child_plan, args) }.map_err(run_status_from_load_error)?;
    unsafe { start_runtime_child_plan_with_argv(child_plan, argv, None, start) }
        .map_err(run_status_from_switch_error)
}

#[cfg(not(test))]
unsafe fn begin_loaded_child_plan_runtime_with_argv(
    child_plan: DynamicUserLoadPlan,
    argv: ChildArgv,
    translated: Option<TranslatedUserLaunch>,
) -> Result<ChildLaunch, ProcessSwitchError> {
    let parent_slot = unsafe { runtime_current_slot() };
    let child_slot = runtime_child_slot_for_parent(parent_slot, unsafe {
        read_runtime_address_space(parent_slot)
    })?;
    if unsafe { read_runtime_blocked_child_slot(parent_slot) } != NO_CHILD_SLOT {
        return Err(ProcessSwitchError::NoRunningChild);
    }
    let parent_parent_slot = unsafe { read_runtime_parent_slot(parent_slot) };
    let descriptor = ProcessDescriptor::for_child_plan(child_plan, argv, translated)?;
    let child_pid = unsafe { allocate_runtime_pid()? };
    let parent_pid = unsafe { read_runtime_pid(parent_slot) };
    let child_state = RuntimeForegroundSlotState::from_process_resources(
        child_pid,
        Some(parent_pid),
        parent_slot as u32,
        descriptor.resources,
    );
    unsafe {
        write_runtime_process_linkage(parent_slot, parent_parent_slot, child_slot as u32);
        write_runtime_foreground_slot_state(child_slot, child_state);
        write_runtime_word(
            core::ptr::addr_of_mut!(RUNTIME_CURRENT_SLOT),
            child_slot as u32,
        );
    }
    Ok(ChildLaunch {
        id: child_state.pid,
        context: descriptor.context,
        frame: descriptor.frame,
        address_space: descriptor.resources.address_space,
        kernel_stack_top: descriptor.resources.kernel_stack_top,
    })
}

#[cfg(not(test))]
unsafe fn start_runtime_child_plan_with_argv(
    child_plan: DynamicUserLoadPlan,
    argv: ChildArgv,
    translated: Option<TranslatedUserLaunch>,
    start: RuntimeChildStart,
) -> Result<RuntimeChildLoadResult, ProcessSwitchError> {
    match start {
        RuntimeChildStart::Enter => unsafe {
            begin_loaded_child_plan_runtime_with_argv(child_plan, argv, translated)
                .map(RuntimeChildLoadResult::Launch)
        },
        RuntimeChildStart::Ready => unsafe {
            spawn_loaded_child_plan_runtime_with_argv(child_plan, argv, translated)
                .map(RuntimeChildLoadResult::Pid)
        },
    }
}

#[cfg(not(test))]
unsafe fn spawn_loaded_child_plan_runtime_with_argv(
    child_plan: DynamicUserLoadPlan,
    argv: ChildArgv,
    translated: Option<TranslatedUserLaunch>,
) -> Result<ProcessId, ProcessSwitchError> {
    let parent_slot = unsafe { runtime_current_slot() };
    let child_slot = runtime_child_slot_for_parent(parent_slot, unsafe {
        read_runtime_address_space(parent_slot)
    })?;
    if unsafe { read_runtime_blocked_child_slot(parent_slot) } != NO_CHILD_SLOT {
        return Err(ProcessSwitchError::NoRunningChild);
    }
    let parent_parent_slot = unsafe { read_runtime_parent_slot(parent_slot) };
    let descriptor = ProcessDescriptor::for_child_plan(child_plan, argv, translated)?;
    let child_pid = unsafe { allocate_runtime_pid()? };
    let parent_pid = unsafe { read_runtime_pid(parent_slot) };
    let child_state = RuntimeForegroundSlotState::from_process_resources(
        child_pid,
        Some(parent_pid),
        parent_slot as u32,
        descriptor.resources,
    );
    unsafe {
        write_runtime_process_linkage(parent_slot, parent_parent_slot, NO_CHILD_SLOT);
        write_runtime_foreground_slot_state(child_slot, child_state);
        *runtime_slot_frame(child_slot).get() = k16_rt::TrapFrame::from(descriptor.frame);
    }
    Ok(child_pid)
}

#[cfg(not(test))]
unsafe fn wait_for_runtime_child(
    pid: ProcessId,
    wait_status_ptr: u32,
) -> Result<ChildLaunch, ProcessSwitchError> {
    let parent_slot = unsafe { runtime_current_slot() };
    if unsafe { read_runtime_blocked_child_slot(parent_slot) } != NO_CHILD_SLOT {
        return Err(ProcessSwitchError::NoRunningChild);
    }
    let child_slot = unsafe { ready_runtime_child_slot(parent_slot, pid) }
        .ok_or(ProcessSwitchError::NoRunningChild)?;
    let parent_parent_slot = unsafe { read_runtime_parent_slot(parent_slot) };
    let caller_frame = unsafe { save_runtime_process_frame(parent_slot) };
    let child_state = unsafe { read_runtime_foreground_slot_state(child_slot) };
    let child_frame = TrapFrame::from(*unsafe { runtime_slot_frame(child_slot).get() });
    unsafe {
        write_runtime_process_linkage(parent_slot, parent_parent_slot, child_slot as u32);
        write_runtime_wait_status_ptr(parent_slot, wait_status_ptr);
        write_runtime_word(
            core::ptr::addr_of_mut!(RUNTIME_CURRENT_SLOT),
            child_slot as u32,
        );
        *runtime_slot_frame(parent_slot).get() = k16_rt::TrapFrame::from(caller_frame);
    }
    Ok(ChildLaunch {
        id: child_state.pid,
        context: ProcessContext {
            entry_pc: child_frame.resume_pc,
            stack_top: child_frame.stack_pointer,
        },
        frame: child_frame,
        address_space: child_state.address_space,
        kernel_stack_top: child_state.kernel_stack_top,
    })
}

#[cfg(not(test))]
unsafe fn ready_runtime_child_slot(parent_slot: usize, pid: ProcessId) -> Option<usize> {
    let mut slot = 1;
    while slot < MAX_PROCESS_SLOTS {
        if slot != parent_slot
            && unsafe { read_runtime_parent_slot(slot) } as usize == parent_slot
            && unsafe { read_runtime_blocked_child_slot(slot) } == NO_CHILD_SLOT
            && (pid.raw() == NO_PROCESS_PID || unsafe { read_runtime_pid(slot) } == pid)
        {
            return Some(slot);
        }
        slot += 1;
    }
    None
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn enter_child_context(launch: ChildLaunch) -> ! {
    if let Some(address_space) = launch.address_space {
        let Some(kernel_stack_top) = launch.kernel_stack_top else {
            halt_runtime_with_panic_code(k16_abi::syscall::ERROR_INVALID as i32);
        };
        let frame = k16_rt::TrapFrame::from(launch.frame);
        let _saved_r0 = unsafe { k16_rt::restore_trap_frame(&frame) };
        let result = unsafe {
            mmu0_activate_user_address_space(
                address_space,
                launch.context.entry_pc,
                launch.context.stack_top,
                kernel_stack_top,
            )
        };
        if result.is_err() {
            halt_runtime_with_panic_code(k16_abi::syscall::ERROR_FAULT as i32);
        } else {
            halt_runtime_with_panic_code(k16_abi::syscall::ERROR_INVALID as i32);
        }
    }
    let frame = k16_rt::TrapFrame::from(launch.frame);
    let _saved_r0 = unsafe { k16_rt::restore_trap_frame(&frame) };
    unsafe { k16_rt::iret_with_r0(0) }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn finish_child_for_exit(status: u32) -> Result<ParentResume, ProcessSwitchError> {
    let mut resume = ParentResume::empty();
    unsafe { finish_child_for_exit_into(status, &mut resume)? };
    Ok(resume)
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn finish_child_for_exit_into(
    status: u32,
    out: &mut ParentResume,
) -> Result<(), ProcessSwitchError> {
    #[cfg(not(test))]
    {
        return unsafe { finish_child_runtime_into(status, out) };
    }
    #[cfg(test)]
    {
        *out = unsafe { PROCESS_TABLE.get().finish_child(status)? };
        Ok(())
    }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn current_process_slot() -> u32 {
    #[cfg(not(test))]
    {
        return unsafe { read_runtime_word(core::ptr::addr_of_mut!(RUNTIME_CURRENT_SLOT)) };
    }
    #[cfg(test)]
    {
        unsafe { PROCESS_TABLE.get().current_slot as u32 }
    }
}

#[cfg(not(test))]
unsafe fn finish_child_runtime_into(
    status: u32,
    out: &mut ParentResume,
) -> Result<(), ProcessSwitchError> {
    let current_slot = unsafe { runtime_current_slot() };
    if current_slot == INIT_PROCESS_SLOT {
        return Err(ProcessSwitchError::NoRunningChild);
    }
    let parent_slot = unsafe { read_runtime_parent_slot(current_slot) } as usize;
    if parent_slot >= MAX_PROCESS_SLOTS
        || unsafe { read_runtime_blocked_child_slot(current_slot) } != NO_CHILD_SLOT
        || unsafe { read_runtime_blocked_child_slot(parent_slot) } as usize != current_slot
    {
        return Err(ProcessSwitchError::NoRunningChild);
    }
    let frame = TrapFrame::from(unsafe { *runtime_slot_frame(parent_slot).get() });
    let child_pid = unsafe { read_runtime_pid(current_slot) };
    let wait_status_ptr = unsafe { read_runtime_wait_status_ptr(parent_slot) };
    let exited_state = unsafe { read_runtime_foreground_slot_state(current_slot) };
    let exited_resources = exited_state.owned_resources();
    unsafe {
        write_runtime_foreground_slot_state(current_slot, exited_state.cleared_after_exit());
        write_runtime_word(runtime_slot_blocked_child_ptr(parent_slot), NO_CHILD_SLOT);
        write_runtime_wait_status_ptr(parent_slot, 0);
        write_runtime_word(
            core::ptr::addr_of_mut!(RUNTIME_CURRENT_SLOT),
            parent_slot as u32,
        );
    }
    *out = ParentResume {
        id: unsafe { read_runtime_pid(parent_slot) },
        child_id: child_pid,
        context: ProcessContext {
            entry_pc: frame.resume_pc,
            stack_top: frame.stack_pointer,
        },
        frame,
        child_exit_status: status,
        wait_status_ptr,
        address_space: unsafe { read_runtime_address_space(parent_slot) },
        kernel_stack_top: unsafe { read_runtime_kernel_stack_top(parent_slot) },
        exited_address_space: exited_resources.address_space,
        exited_backing_pages: exited_resources.backing_pages,
        exited_heap_pages: exited_resources.heap_pages,
    };
    Ok(())
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn set_current_program_break(address: u32) -> Result<u32, HeapError> {
    #[cfg(not(test))]
    {
        return unsafe { set_runtime_program_break(address) };
    }
    #[cfg(test)]
    {
        unsafe { PROCESS_TABLE.get().set_program_break(address) }
    }
}

pub unsafe fn current_process_contains_buffer(ptr: u32, len: u32) -> bool {
    #[cfg(not(test))]
    {
        let current_slot = unsafe { runtime_current_slot() };
        return unsafe { read_runtime_process_memory(current_slot) }
            .map(|memory| memory.contains_buffer(ptr, len))
            .unwrap_or(false);
    }
    #[cfg(test)]
    {
        unsafe { PROCESS_TABLE.get().current_contains_buffer(ptr, len) }
    }
}

pub unsafe fn current_process_address_space() -> Option<u32> {
    #[cfg(not(test))]
    {
        let current_slot = unsafe { runtime_current_slot() };
        return unsafe { read_runtime_address_space(current_slot) };
    }
    #[cfg(test)]
    {
        unsafe { PROCESS_TABLE.get().current_address_space() }
    }
}

#[cfg(not(test))]
unsafe fn read_runtime_address_space(slot: usize) -> Option<u32> {
    let address_space = unsafe { read_runtime_word(runtime_slot_address_space_ptr(slot)) };
    if address_space == 0 {
        None
    } else {
        Some(address_space)
    }
}

#[cfg(not(test))]
unsafe fn read_runtime_kernel_stack_top(slot: usize) -> Option<u32> {
    let kernel_stack_top = unsafe { read_runtime_word(runtime_slot_kernel_stack_top_ptr(slot)) };
    if kernel_stack_top == 0 {
        None
    } else {
        Some(kernel_stack_top)
    }
}

#[cfg(test)]
pub fn set_current_process_address_space_for_tests(address_space: Option<u32>) {
    unsafe { PROCESS_TABLE.get().set_current_address_space(address_space) }
}

#[cfg(test)]
pub fn set_current_process_memory_for_tests(memory: ProcessMemory) {
    unsafe { PROCESS_TABLE.get().set_current_memory(memory) }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn grow_current_program_break(delta: u32) -> Result<u32, HeapError> {
    #[cfg(not(test))]
    {
        return unsafe { grow_runtime_program_break(delta) };
    }
    #[cfg(test)]
    {
        unsafe { PROCESS_TABLE.get().grow_program_break(delta) }
    }
}

#[cfg(not(test))]
unsafe fn save_runtime_process_frame(slot: usize) -> TrapFrame {
    let saved = unsafe { runtime_slot_frame(slot).get() };
    k16_rt::save_trap_frame(saved);
    TrapFrame::from(*saved)
}

#[cfg(not(test))]
unsafe fn runtime_current_slot() -> usize {
    unsafe { read_runtime_word(core::ptr::addr_of_mut!(RUNTIME_CURRENT_SLOT)) as usize }
}

#[cfg(not(test))]
unsafe fn allocate_runtime_pid() -> Result<ProcessId, ProcessSwitchError> {
    let pid = unsafe { read_runtime_word(core::ptr::addr_of_mut!(RUNTIME_NEXT_PID)) };
    let next = pid
        .checked_add(1)
        .ok_or(ProcessSwitchError::ChildAlreadyRunning)?;
    unsafe {
        write_runtime_word(core::ptr::addr_of_mut!(RUNTIME_NEXT_PID), next);
    }
    Ok(ProcessId::from_raw(pid))
}

#[cfg(not(test))]
fn runtime_slot_pid_ptr(slot: usize) -> *mut u32 {
    match slot {
        INIT_PROCESS_SLOT => core::ptr::addr_of_mut!(RUNTIME_SLOT0_PID),
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_PID),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT2_PID),
    }
}

#[cfg(not(test))]
fn runtime_slot_parent_pid_ptr(slot: usize) -> *mut u32 {
    match slot {
        INIT_PROCESS_SLOT => core::ptr::addr_of_mut!(RUNTIME_SLOT0_PARENT_PID),
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_PARENT_PID),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT2_PARENT_PID),
    }
}

#[cfg(not(test))]
fn runtime_slot_parent_ptr(slot: usize) -> *mut u32 {
    match slot {
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_PARENT),
        2 => core::ptr::addr_of_mut!(RUNTIME_SLOT2_PARENT),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT1_PARENT),
    }
}

#[cfg(not(test))]
fn runtime_slot_blocked_child_ptr(slot: usize) -> *mut u32 {
    match slot {
        INIT_PROCESS_SLOT => core::ptr::addr_of_mut!(RUNTIME_SLOT0_BLOCKED_CHILD),
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_BLOCKED_CHILD),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT2_BLOCKED_CHILD),
    }
}

#[cfg(not(test))]
fn runtime_slot_wait_status_ptr(slot: usize) -> *mut u32 {
    match slot {
        INIT_PROCESS_SLOT => core::ptr::addr_of_mut!(RUNTIME_SLOT0_WAIT_STATUS_PTR),
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_WAIT_STATUS_PTR),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT2_WAIT_STATUS_PTR),
    }
}

#[cfg(not(test))]
fn runtime_slot_frame(slot: usize) -> &'static KernelCell<k16_rt::TrapFrame> {
    match slot {
        INIT_PROCESS_SLOT => &RUNTIME_SLOT0_FRAME,
        1 => &RUNTIME_SLOT1_FRAME,
        _ => &RUNTIME_SLOT2_FRAME,
    }
}

#[cfg(not(test))]
fn runtime_slot_heap_start_ptr(slot: usize) -> *mut u32 {
    match slot {
        INIT_PROCESS_SLOT => core::ptr::addr_of_mut!(RUNTIME_SLOT0_HEAP_START),
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_HEAP_START),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT2_HEAP_START),
    }
}

#[cfg(not(test))]
fn runtime_slot_program_break_ptr(slot: usize) -> *mut u32 {
    match slot {
        INIT_PROCESS_SLOT => core::ptr::addr_of_mut!(RUNTIME_SLOT0_PROGRAM_BREAK),
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_PROGRAM_BREAK),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT2_PROGRAM_BREAK),
    }
}

#[cfg(not(test))]
fn runtime_slot_heap_limit_ptr(slot: usize) -> *mut u32 {
    match slot {
        INIT_PROCESS_SLOT => core::ptr::addr_of_mut!(RUNTIME_SLOT0_HEAP_LIMIT),
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_HEAP_LIMIT),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT2_HEAP_LIMIT),
    }
}

#[cfg(not(test))]
fn runtime_slot_memory_start_ptr(slot: usize) -> *mut u32 {
    match slot {
        INIT_PROCESS_SLOT => core::ptr::addr_of_mut!(RUNTIME_SLOT0_MEMORY_START),
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_MEMORY_START),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT2_MEMORY_START),
    }
}

#[cfg(not(test))]
fn runtime_slot_memory_end_ptr(slot: usize) -> *mut u32 {
    match slot {
        INIT_PROCESS_SLOT => core::ptr::addr_of_mut!(RUNTIME_SLOT0_MEMORY_END),
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_MEMORY_END),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT2_MEMORY_END),
    }
}

#[cfg(not(test))]
fn runtime_slot_address_space_ptr(slot: usize) -> *mut u32 {
    match slot {
        INIT_PROCESS_SLOT => core::ptr::addr_of_mut!(RUNTIME_SLOT0_ADDRESS_SPACE),
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_ADDRESS_SPACE),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT2_ADDRESS_SPACE),
    }
}

#[cfg(not(test))]
fn runtime_slot_kernel_stack_top_ptr(slot: usize) -> *mut u32 {
    match slot {
        INIT_PROCESS_SLOT => core::ptr::addr_of_mut!(RUNTIME_SLOT0_KERNEL_STACK_TOP),
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_KERNEL_STACK_TOP),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT2_KERNEL_STACK_TOP),
    }
}

#[cfg(not(test))]
fn runtime_slot_backing_start_ptr(slot: usize) -> *mut u32 {
    match slot {
        INIT_PROCESS_SLOT => core::ptr::addr_of_mut!(RUNTIME_SLOT0_BACKING_START),
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_BACKING_START),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT2_BACKING_START),
    }
}

#[cfg(not(test))]
fn runtime_slot_backing_frame_count_ptr(slot: usize) -> *mut u32 {
    match slot {
        INIT_PROCESS_SLOT => core::ptr::addr_of_mut!(RUNTIME_SLOT0_BACKING_FRAME_COUNT),
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_BACKING_FRAME_COUNT),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT2_BACKING_FRAME_COUNT),
    }
}

#[cfg(not(test))]
fn runtime_slot_heap_backing_start_ptr(slot: usize) -> *mut u32 {
    match slot {
        INIT_PROCESS_SLOT => core::ptr::addr_of_mut!(RUNTIME_SLOT0_HEAP_BACKING_START),
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_HEAP_BACKING_START),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT2_HEAP_BACKING_START),
    }
}

#[cfg(not(test))]
fn runtime_slot_heap_backing_frame_count_ptr(slot: usize) -> *mut u32 {
    match slot {
        INIT_PROCESS_SLOT => core::ptr::addr_of_mut!(RUNTIME_SLOT0_HEAP_BACKING_FRAME_COUNT),
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_HEAP_BACKING_FRAME_COUNT),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT2_HEAP_BACKING_FRAME_COUNT),
    }
}

#[cfg(not(test))]
unsafe fn write_runtime_process_memory(slot: usize, memory: ProcessMemory) {
    unsafe {
        write_runtime_word(runtime_slot_memory_start_ptr(slot), memory.start);
        write_runtime_word(runtime_slot_memory_end_ptr(slot), memory.end);
    }
}

#[cfg(not(test))]
unsafe fn write_runtime_process_resources(slot: usize, resources: ProcessResources) {
    unsafe {
        write_runtime_process_memory(slot, resources.memory);
        write_runtime_word(runtime_slot_heap_start_ptr(slot), resources.heap.start);
        write_runtime_word(
            runtime_slot_program_break_ptr(slot),
            resources.heap.program_break,
        );
        write_runtime_word(runtime_slot_heap_limit_ptr(slot), resources.heap.limit);
        write_runtime_word(
            runtime_slot_address_space_ptr(slot),
            resources.address_space.unwrap_or(0),
        );
        write_runtime_word(
            runtime_slot_kernel_stack_top_ptr(slot),
            resources.kernel_stack_top.unwrap_or(0),
        );
        write_runtime_backing_pages(slot, resources.backing_pages);
        write_runtime_heap_backing_pages(slot, resources.heap_backing_pages);
    }
}

#[cfg(not(test))]
unsafe fn read_runtime_process_memory(slot: usize) -> Option<ProcessMemory> {
    let start = unsafe { read_runtime_word(runtime_slot_memory_start_ptr(slot)) };
    let end = unsafe { read_runtime_word(runtime_slot_memory_end_ptr(slot)) };
    ProcessMemory::new(start, end).ok()
}

#[cfg(not(test))]
unsafe fn read_runtime_pid(slot: usize) -> ProcessId {
    ProcessId::from_raw(unsafe { read_runtime_word(runtime_slot_pid_ptr(slot)) })
}

#[cfg(not(test))]
unsafe fn read_runtime_parent_pid(slot: usize) -> Option<ProcessId> {
    let pid = unsafe { read_runtime_word(runtime_slot_parent_pid_ptr(slot)) };
    if pid == NO_PROCESS_PID {
        None
    } else {
        Some(ProcessId::from_raw(pid))
    }
}

#[cfg(not(test))]
unsafe fn write_runtime_process_identity(
    slot: usize,
    pid: ProcessId,
    parent_pid: Option<ProcessId>,
) {
    unsafe {
        write_runtime_word(runtime_slot_pid_ptr(slot), pid.raw());
        write_runtime_word(
            runtime_slot_parent_pid_ptr(slot),
            parent_pid.map(|pid| pid.raw()).unwrap_or(NO_PROCESS_PID),
        );
    }
}

#[cfg(not(test))]
unsafe fn read_runtime_parent_slot(slot: usize) -> u32 {
    if slot == INIT_PROCESS_SLOT {
        NO_PARENT_SLOT
    } else {
        unsafe { read_runtime_word(runtime_slot_parent_ptr(slot)) }
    }
}

#[cfg(not(test))]
unsafe fn read_runtime_blocked_child_slot(slot: usize) -> u32 {
    unsafe { read_runtime_word(runtime_slot_blocked_child_ptr(slot)) }
}

#[cfg(not(test))]
unsafe fn write_runtime_process_linkage(slot: usize, parent_slot: u32, blocked_child_slot: u32) {
    unsafe {
        if slot != INIT_PROCESS_SLOT {
            write_runtime_word(runtime_slot_parent_ptr(slot), parent_slot);
        }
        write_runtime_word(runtime_slot_blocked_child_ptr(slot), blocked_child_slot);
    }
}

#[cfg(not(test))]
unsafe fn read_runtime_wait_status_ptr(slot: usize) -> u32 {
    unsafe { read_runtime_word(runtime_slot_wait_status_ptr(slot)) }
}

#[cfg(not(test))]
unsafe fn write_runtime_wait_status_ptr(slot: usize, wait_status_ptr: u32) {
    unsafe { write_runtime_word(runtime_slot_wait_status_ptr(slot), wait_status_ptr) };
}

#[cfg(not(test))]
unsafe fn read_runtime_foreground_slot_state(slot: usize) -> RuntimeForegroundSlotState {
    let heap_start = unsafe { read_runtime_word(runtime_slot_heap_start_ptr(slot)) };
    let program_break = unsafe { read_runtime_word(runtime_slot_program_break_ptr(slot)) };
    let heap_limit = unsafe { read_runtime_word(runtime_slot_heap_limit_ptr(slot)) };
    let heap = if heap_start == 0 {
        None
    } else {
        Some(RuntimeHeapState {
            start: heap_start,
            program_break,
            limit: heap_limit,
        })
    };
    RuntimeForegroundSlotState {
        pid: unsafe { read_runtime_pid(slot) },
        parent_pid: unsafe { read_runtime_parent_pid(slot) },
        parent_slot: unsafe { read_runtime_parent_slot(slot) },
        blocked_child_slot: unsafe { read_runtime_blocked_child_slot(slot) },
        memory: unsafe { read_runtime_process_memory(slot) },
        heap,
        address_space: unsafe { read_runtime_address_space(slot) },
        kernel_stack_top: unsafe { read_runtime_kernel_stack_top(slot) },
        backing_pages: unsafe { read_runtime_backing_pages(slot) },
        heap_backing_pages: unsafe { read_runtime_heap_backing_pages(slot) },
    }
}

#[cfg(not(test))]
unsafe fn write_runtime_foreground_slot_state(slot: usize, state: RuntimeForegroundSlotState) {
    let memory = state.memory.unwrap_or(ProcessMemory { start: 0, end: 0 });
    let heap = state.heap.unwrap_or(RuntimeHeapState {
        start: 0,
        program_break: 0,
        limit: 0,
    });
    unsafe {
        write_runtime_process_identity(slot, state.pid, state.parent_pid);
        write_runtime_process_linkage(slot, state.parent_slot, state.blocked_child_slot);
        write_runtime_process_memory(slot, memory);
        write_runtime_word(runtime_slot_heap_start_ptr(slot), heap.start);
        write_runtime_word(runtime_slot_program_break_ptr(slot), heap.program_break);
        write_runtime_word(runtime_slot_heap_limit_ptr(slot), heap.limit);
        write_runtime_word(
            runtime_slot_address_space_ptr(slot),
            state.address_space.unwrap_or(0),
        );
        write_runtime_word(
            runtime_slot_kernel_stack_top_ptr(slot),
            state.kernel_stack_top.unwrap_or(0),
        );
        write_runtime_backing_pages(slot, state.backing_pages);
        write_runtime_heap_backing_pages(slot, state.heap_backing_pages);
    }
}

#[cfg(not(test))]
unsafe fn write_runtime_backing_pages(
    slot: usize,
    backing_pages: Option<crate::page_alloc::FrameRange>,
) {
    let start = backing_pages.map(|range| range.start).unwrap_or(0);
    let frame_count = backing_pages.map(|range| range.frame_count).unwrap_or(0);
    unsafe {
        write_runtime_word(runtime_slot_backing_start_ptr(slot), start);
        write_runtime_word(runtime_slot_backing_frame_count_ptr(slot), frame_count);
    }
}

#[cfg(not(test))]
unsafe fn read_runtime_backing_pages(slot: usize) -> Option<crate::page_alloc::FrameRange> {
    let start = unsafe { read_runtime_word(runtime_slot_backing_start_ptr(slot)) };
    let frame_count = unsafe { read_runtime_word(runtime_slot_backing_frame_count_ptr(slot)) };
    if frame_count == 0 {
        None
    } else {
        Some(crate::page_alloc::FrameRange { start, frame_count })
    }
}

#[cfg(not(test))]
unsafe fn write_runtime_heap_backing_pages(
    slot: usize,
    backing_pages: Option<crate::page_alloc::FrameRange>,
) {
    let start = backing_pages.map(|range| range.start).unwrap_or(0);
    let frame_count = backing_pages.map(|range| range.frame_count).unwrap_or(0);
    unsafe {
        write_runtime_word(runtime_slot_heap_backing_start_ptr(slot), start);
        write_runtime_word(runtime_slot_heap_backing_frame_count_ptr(slot), frame_count);
    }
}

#[cfg(not(test))]
unsafe fn read_runtime_heap_backing_pages(slot: usize) -> Option<crate::page_alloc::FrameRange> {
    let start = unsafe { read_runtime_word(runtime_slot_heap_backing_start_ptr(slot)) };
    let frame_count = unsafe { read_runtime_word(runtime_slot_heap_backing_frame_count_ptr(slot)) };
    if frame_count == 0 {
        None
    } else {
        Some(crate::page_alloc::FrameRange { start, frame_count })
    }
}

#[cfg(not(test))]
unsafe fn set_runtime_program_break(address: u32) -> Result<u32, HeapError> {
    let current_slot = unsafe { runtime_current_slot() };
    let heap_start = unsafe { read_runtime_word(runtime_slot_heap_start_ptr(current_slot)) };
    let old_break = unsafe { read_runtime_word(runtime_slot_program_break_ptr(current_slot)) };
    let heap_limit = unsafe { read_runtime_word(runtime_slot_heap_limit_ptr(current_slot)) };
    if heap_start == 0 {
        return Err(HeapError::NoRunningChild);
    }
    if address < heap_start || address > heap_limit {
        return Err(HeapError::OutOfMemory);
    }
    if address > old_break {
        if let Some(address_space) = unsafe { read_runtime_address_space(current_slot) } {
            unsafe {
                commit_runtime_heap_growth(current_slot, heap_start, address_space, address)?
            };
        }
    }
    unsafe { write_runtime_word(runtime_slot_program_break_ptr(current_slot), address) };
    Ok(address)
}

#[cfg(not(test))]
unsafe fn commit_runtime_heap_growth(
    slot: usize,
    heap_start: u32,
    address_space: u32,
    requested_break: u32,
) -> Result<(), HeapError> {
    let existing = unsafe { read_runtime_heap_backing_pages(slot) };
    let committed_end = heap_committed_end(heap_start, existing.map(|range| range.frame_count))?;
    let Some((virtual_start, page_count)) =
        heap_growth_commit_range(committed_end, requested_break)?
    else {
        return Ok(());
    };
    let allocator =
        unsafe { (*RUNTIME_PAGE_ALLOCATOR.get()).as_mut() }.ok_or(HeapError::OutOfMemory)?;
    let backing = allocator
        .allocate_contiguous(page_count)
        .map_err(|_| HeapError::OutOfMemory)?;
    let coalesced = match coalesce_heap_backing_range(existing, backing) {
        Ok(coalesced) => coalesced,
        Err(error) => {
            let _ = allocator.free_contiguous(backing);
            return Err(error);
        }
    };
    if unsafe { map_translated_heap_pages(address_space, virtual_start, backing.start, page_count) }
        .is_err()
    {
        let _ = allocator.free_contiguous(backing);
        return Err(HeapError::OutOfMemory);
    }
    unsafe { write_runtime_heap_backing_pages(slot, coalesced) };
    Ok(())
}

#[cfg(not(test))]
unsafe fn grow_runtime_program_break(delta: u32) -> Result<u32, HeapError> {
    let current_slot = unsafe { runtime_current_slot() };
    let old_break = unsafe { read_runtime_word(runtime_slot_program_break_ptr(current_slot)) };
    if old_break == 0 {
        return Err(HeapError::NoRunningChild);
    }
    let new_break = old_break.checked_add(delta).ok_or(HeapError::OutOfMemory)?;
    unsafe { set_runtime_program_break(new_break)? };
    Ok(old_break)
}

#[cfg(not(test))]
unsafe fn read_runtime_word(address: *mut u32) -> u32 {
    unsafe { core::ptr::read_volatile(address) }
}

#[cfg(not(test))]
unsafe fn write_runtime_word(address: *mut u32, value: u32) {
    unsafe { core::ptr::write_volatile(address, value) }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn resume_parent_context(resume: &ParentResume) -> ! {
    #[cfg(not(test))]
    {
        let frame = k16_rt::TrapFrame::from(resume.frame);
        let _saved_r0 = unsafe { k16_rt::restore_trap_frame(&frame) };
        let override_result = match trap_return_override_for_resume(resume) {
            Ok(TrapReturnOverride::Physical) => unsafe { mmu0_set_trap_return_physical() },
            Ok(TrapReturnOverride::Translated {
                address_space,
                kernel_stack_top,
            }) => unsafe { mmu0_set_trap_return_address_space(address_space, kernel_stack_top) },
            Err(_) => Err(ProcessLoadError::Storage),
        };
        if override_result.is_err() {
            halt_runtime_with_panic_code(k16_abi::syscall::ERROR_FAULT as i32);
        }
        unsafe { k16_rt::iret_with_r0(resume.return_value()) }
    }
    #[cfg(test)]
    {
        let frame = k16_rt::TrapFrame::from(resume.frame);
        let _saved_r0 = unsafe { k16_rt::restore_trap_frame(&frame) };
        unsafe { k16_rt::iret_with_r0(resume.return_value()) }
    }
}

fn should_translate_runtime_child_path(_path: &[u8]) -> bool {
    true
}

fn runtime_child_slot_for_parent(
    current_slot: usize,
    _current_address_space: Option<u32>,
) -> Result<usize, ProcessSwitchError> {
    #[cfg(not(test))]
    {
        return child_slot_from_occupancy(current_slot, unsafe {
            runtime_foreground_slot_occupancy()
        });
    }
    #[cfg(test)]
    {
        let mut occupied = [false; FOREGROUND_PROCESS_SLOTS];
        let mut slot = 1;
        while slot <= current_slot && slot < MAX_PROCESS_SLOTS {
            occupied[slot - 1] = true;
            slot += 1;
        }
        child_slot_from_occupancy(current_slot, occupied)
    }
}

fn child_slot_from_occupancy(
    parent_slot: usize,
    foreground_occupied: [bool; FOREGROUND_PROCESS_SLOTS],
) -> Result<usize, ProcessSwitchError> {
    if parent_slot >= MAX_PROCESS_SLOTS {
        return Err(ProcessSwitchError::ChildAlreadyRunning);
    }
    if parent_slot != INIT_PROCESS_SLOT && !foreground_occupied[parent_slot - 1] {
        return Err(ProcessSwitchError::ChildAlreadyRunning);
    }
    let mut index = 0;
    while index < FOREGROUND_PROCESS_SLOTS {
        if !foreground_occupied[index] {
            return Ok(index + 1);
        }
        index += 1;
    }
    Err(ProcessSwitchError::ChildAlreadyRunning)
}

#[cfg(not(test))]
unsafe fn runtime_foreground_slot_occupancy() -> [bool; FOREGROUND_PROCESS_SLOTS] {
    let mut occupied = [false; FOREGROUND_PROCESS_SLOTS];
    let mut index = 0;
    while index < FOREGROUND_PROCESS_SLOTS {
        let slot = index + 1;
        occupied[index] =
            unsafe { read_runtime_word(runtime_slot_parent_ptr(slot)) } != NO_PARENT_SLOT;
        index += 1;
    }
    occupied
}

fn trap_return_override_for_resume(
    resume: &ParentResume,
) -> Result<TrapReturnOverride, ProcessSwitchError> {
    match resume.address_space {
        Some(address_space) => {
            let Some(kernel_stack_top) = resume.kernel_stack_top else {
                return Err(ProcessSwitchError::NoRunningChild);
            };
            Ok(TrapReturnOverride::Translated {
                address_space,
                kernel_stack_top,
            })
        }
        None => Ok(TrapReturnOverride::Physical),
    }
}

pub const fn run_status_from_load_error(error: ProcessLoadError) -> u32 {
    match error {
        ProcessLoadError::InvalidPath => k16_abi::syscall::ERROR_INVALID,
        ProcessLoadError::InvalidArena => k16_abi::syscall::ERROR_NO_MEMORY,
        ProcessLoadError::AddressOverflow | ProcessLoadError::InvalidImage => {
            k16_abi::syscall::ERROR_EXEC_FORMAT
        }
        ProcessLoadError::ProgramTooLarge => k16_abi::syscall::ERROR_NO_MEMORY,
        ProcessLoadError::Storage => k16_abi::syscall::ERROR_NO_ENTRY,
    }
}

pub const fn run_status_from_switch_error(error: ProcessSwitchError) -> u32 {
    match error {
        ProcessSwitchError::ChildAlreadyRunning => k16_abi::syscall::ERROR_BUSY,
        ProcessSwitchError::NoRunningChild => k16_abi::syscall::ERROR_INVALID,
    }
}

pub const fn heap_status_from_error(error: HeapError) -> u32 {
    match error {
        HeapError::NoRunningChild => k16_abi::syscall::ERROR_INVALID,
        HeapError::OutOfMemory => k16_abi::syscall::ERROR_NO_MEMORY,
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
struct HeapState {
    start: u32,
    limit: u32,
}

impl HeapState {
    fn from_bounds(load_end: u32, stack_top: u32) -> Result<Self, HeapError> {
        let start = align_up(load_end, HEAP_ALIGNMENT).map_err(|_| HeapError::OutOfMemory)?;
        let limit = heap_limit_from_stack_top(stack_top)?;
        if start > limit {
            return Err(HeapError::OutOfMemory);
        }
        Ok(Self { start, limit })
    }
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

fn translated_child_kernel_stack_top(parent_stack_limit: u32) -> Result<u32, ProcessLoadError> {
    let kernel_stack_top = align_down(parent_stack_limit, STACK_ALIGNMENT);
    kernel_stack_top
        .checked_sub(TRANSLATED_TRAP_STACK_BYTES)
        .ok_or(ProcessLoadError::ProgramTooLarge)?;
    Ok(kernel_stack_top)
}

fn translated_child_user_arena(
    caller_memory: ProcessMemory,
) -> Result<UserArena, ProcessLoadError> {
    UserArena::new(caller_memory.start, caller_memory.end)
}

fn translated_init_arena_end(memory_end: u32) -> Result<(u32, u32), ProcessLoadError> {
    let kernel_stack_top = translated_child_kernel_stack_top(memory_end)?;
    let arena_end = kernel_stack_top
        .checked_sub(TRANSLATED_TRAP_STACK_BYTES)
        .ok_or(ProcessLoadError::ProgramTooLarge)?;
    Ok((align_down(arena_end, STACK_ALIGNMENT), kernel_stack_top))
}

fn translated_init_user_arena(
    program_base: u32,
    memory_end: u32,
    kernel_reserved_end: u32,
) -> Result<(UserArena, u32), ProcessLoadError> {
    let (arena_end, kernel_stack_top) = translated_init_arena_end(memory_end)?;
    let arena_start = align_up(
        program_base
            .max(INITIAL_USER_LOADER_SCRATCH_END)
            .max(kernel_reserved_end),
        VM_PAGE_SIZE,
    )?;
    Ok((UserArena::new(arena_start, arena_end)?, kernel_stack_top))
}

fn translated_init_kernel_reserved_ranges(
    program_base: u32,
    ram_size: u32,
    kernel_image_end: u32,
    kernel_stack_top: u32,
) -> crate::page_alloc::KernelReservedRanges {
    crate::page_alloc::KernelReservedRanges {
        ram_size,
        boot_reserved_end: program_base,
        loader_scratch_end: INITIAL_USER_LOADER_SCRATCH_END,
        kernel_image_end,
        terminal_cells_end: crate::memory_layout::terminal_cells_end(),
        init_kernel_stack_top: kernel_stack_top,
        init_kernel_stack_bytes: TRANSLATED_TRAP_STACK_BYTES,
    }
}

#[cfg(not(test))]
unsafe fn initial_user_kernel_reserved_end() -> u32 {
    let image_end = unsafe { initial_user_kernel_image_end() };
    image_end.max(crate::memory_layout::terminal_cells_end())
}

#[cfg(not(test))]
unsafe fn initial_user_kernel_image_end() -> u32 {
    let image_end = core::ptr::addr_of!(__k16_image_end) as u32;
    image_end
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

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct MappedDynamicUserLoadPlan {
    virtual_plan: DynamicUserLoadPlan,
    image_map_start: u32,
    image_page_count: u32,
    stack_map_start: u32,
    stack_page_count: u32,
    backing_start: u32,
    page_count: u32,
}

impl MappedDynamicUserLoadPlan {
    pub fn new(
        virtual_plan: DynamicUserLoadPlan,
        map_start: u32,
        backing_start: u32,
        page_count: u32,
    ) -> Result<Self, ProcessLoadError> {
        if page_count == 0 || map_start % VM_PAGE_SIZE != 0 || backing_start % VM_PAGE_SIZE != 0 {
            return Err(ProcessLoadError::InvalidArena);
        }
        let mapped_end = map_start
            .checked_add(
                page_count
                    .checked_mul(VM_PAGE_SIZE)
                    .ok_or(ProcessLoadError::AddressOverflow)?,
            )
            .ok_or(ProcessLoadError::AddressOverflow)?;
        if virtual_plan.load_base < map_start || virtual_plan.stack_top > mapped_end {
            return Err(ProcessLoadError::InvalidArena);
        }
        Ok(Self {
            virtual_plan,
            image_map_start: map_start,
            image_page_count: page_count,
            stack_map_start: 0,
            stack_page_count: 0,
            backing_start,
            page_count,
        })
    }

    pub fn new_committed(
        virtual_plan: DynamicUserLoadPlan,
        image_map_start: u32,
        image_page_count: u32,
        stack_map_start: u32,
        stack_page_count: u32,
        backing_start: u32,
    ) -> Result<Self, ProcessLoadError> {
        if image_page_count == 0
            || stack_page_count > TRANSLATED_USER_STACK_PAGES
            || image_map_start % VM_PAGE_SIZE != 0
            || stack_map_start % VM_PAGE_SIZE != 0
            || backing_start % VM_PAGE_SIZE != 0
        {
            return Err(ProcessLoadError::InvalidArena);
        }
        let page_count = image_page_count
            .checked_add(stack_page_count)
            .ok_or(ProcessLoadError::AddressOverflow)?;
        let image_mapped_end = image_map_start
            .checked_add(
                image_page_count
                    .checked_mul(VM_PAGE_SIZE)
                    .ok_or(ProcessLoadError::AddressOverflow)?,
            )
            .ok_or(ProcessLoadError::AddressOverflow)?;
        if virtual_plan.load_base < image_map_start || virtual_plan.load_end > image_mapped_end {
            return Err(ProcessLoadError::InvalidArena);
        }
        if stack_page_count > 0 {
            let stack_mapped_end = stack_map_start
                .checked_add(
                    stack_page_count
                        .checked_mul(VM_PAGE_SIZE)
                        .ok_or(ProcessLoadError::AddressOverflow)?,
                )
                .ok_or(ProcessLoadError::AddressOverflow)?;
            if virtual_plan.stack_top <= stack_map_start
                || virtual_plan.stack_top > stack_mapped_end
            {
                return Err(ProcessLoadError::InvalidArena);
            }
        }
        Ok(Self {
            virtual_plan,
            image_map_start,
            image_page_count,
            stack_map_start,
            stack_page_count,
            backing_start,
            page_count,
        })
    }

    pub const fn virtual_plan(&self) -> DynamicUserLoadPlan {
        self.virtual_plan
    }

    pub const fn map_start(&self) -> u32 {
        self.image_map_start
    }

    pub const fn image_page_count(&self) -> u32 {
        self.image_page_count
    }

    pub const fn stack_map_start(&self) -> u32 {
        self.stack_map_start
    }

    pub const fn stack_page_count(&self) -> u32 {
        self.stack_page_count
    }

    pub const fn backing_start(&self) -> u32 {
        self.backing_start
    }

    pub const fn page_count(&self) -> u32 {
        self.page_count
    }

    pub fn payload_dst(&self) -> u32 {
        self.translate_address(self.virtual_plan.payload_dst)
            .expect("validated payload address translates")
    }

    pub fn zero_fill_addr(&self) -> u32 {
        self.translate_address(self.virtual_plan.zero_fill_addr)
            .expect("validated zero-fill address translates")
    }

    pub fn relocation_field_addr(&self, relocation_offset: u32) -> Result<u32, ProcessLoadError> {
        let field_addr = self
            .virtual_plan
            .load_base
            .checked_add(relocation_offset)
            .ok_or(ProcessLoadError::AddressOverflow)?;
        self.translate_address(field_addr)
    }

    fn translate_address(&self, virtual_address: u32) -> Result<u32, ProcessLoadError> {
        let image_mapped_end = self
            .image_map_start
            .checked_add(
                self.image_page_count
                    .checked_mul(VM_PAGE_SIZE)
                    .ok_or(ProcessLoadError::AddressOverflow)?,
            )
            .ok_or(ProcessLoadError::AddressOverflow)?;
        if virtual_address >= self.image_map_start && virtual_address < image_mapped_end {
            let offset = virtual_address
                .checked_sub(self.image_map_start)
                .ok_or(ProcessLoadError::InvalidArena)?;
            return self
                .backing_start
                .checked_add(offset)
                .ok_or(ProcessLoadError::AddressOverflow);
        }
        if self.stack_page_count > 0 {
            let stack_mapped_end = self
                .stack_map_start
                .checked_add(
                    self.stack_page_count
                        .checked_mul(VM_PAGE_SIZE)
                        .ok_or(ProcessLoadError::AddressOverflow)?,
                )
                .ok_or(ProcessLoadError::AddressOverflow)?;
            if virtual_address >= self.stack_map_start && virtual_address < stack_mapped_end {
                let stack_backing_start = self
                    .backing_start
                    .checked_add(
                        self.image_page_count
                            .checked_mul(VM_PAGE_SIZE)
                            .ok_or(ProcessLoadError::AddressOverflow)?,
                    )
                    .ok_or(ProcessLoadError::AddressOverflow)?;
                let offset = virtual_address
                    .checked_sub(self.stack_map_start)
                    .ok_or(ProcessLoadError::InvalidArena)?;
                return stack_backing_start
                    .checked_add(offset)
                    .ok_or(ProcessLoadError::AddressOverflow);
            }
        }
        Err(ProcessLoadError::InvalidArena)
    }
}

fn allocate_mapped_dynamic_user_load_plan(
    plan: DynamicUserLoadPlan,
    allocator: &mut crate::page_alloc::PageFrameAllocator,
) -> Result<MappedDynamicUserLoadPlan, ProcessLoadError> {
    let image_map_start = page_align_down(plan.load_base);
    let image_map_end = page_align_up(plan.load_end)?;
    if image_map_start >= image_map_end || plan.stack_top == 0 {
        return Err(ProcessLoadError::InvalidArena);
    }
    let image_page_count = (image_map_end - image_map_start) / VM_PAGE_SIZE;
    let stack_map_end = page_align_up(plan.stack_top)?;
    let desired_stack_map_start = stack_map_end
        .checked_sub(TRANSLATED_USER_STACK_BYTES)
        .ok_or(ProcessLoadError::InvalidArena)?;
    let stack_map_start = if desired_stack_map_start < image_map_end {
        image_map_end
    } else {
        desired_stack_map_start
    };
    let stack_page_count = if stack_map_start >= stack_map_end {
        0
    } else {
        (stack_map_end - stack_map_start) / VM_PAGE_SIZE
    };
    let page_count = image_page_count
        .checked_add(stack_page_count)
        .ok_or(ProcessLoadError::AddressOverflow)?;
    let backing = allocator
        .allocate_contiguous(page_count)
        .map_err(page_alloc_error_to_process_load_error)?;
    MappedDynamicUserLoadPlan::new_committed(
        plan,
        image_map_start,
        image_page_count,
        stack_map_start,
        stack_page_count,
        backing.start,
    )
}

fn free_mapped_dynamic_user_load_plan(
    plan: MappedDynamicUserLoadPlan,
    allocator: &mut crate::page_alloc::PageFrameAllocator,
) -> Result<(), ProcessLoadError> {
    allocator
        .free_contiguous(crate::page_alloc::FrameRange {
            start: plan.backing_start(),
            frame_count: plan.page_count(),
        })
        .map_err(page_alloc_error_to_process_load_error)
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct RunArgvRequest<'a> {
    pub path: &'a [u8],
    argc: usize,
    args: [&'a [u8]; k16_abi::syscall::MAX_RUN_ARGS],
}

impl<'a> RunArgvRequest<'a> {
    pub fn args(&self) -> &[&'a [u8]] {
        &self.args[..self.argc]
    }

    pub fn parse(bytes: &'a [u8]) -> Result<Self, ProcessLoadError> {
        Self::parse_with_magic(bytes, k16_abi::syscall::RUN_ARGV_MAGIC)
    }

    pub fn parse_with_magic(bytes: &'a [u8], magic: u32) -> Result<Self, ProcessLoadError> {
        if bytes.len() < 12 {
            return Err(ProcessLoadError::InvalidPath);
        }
        if read_request_u32(bytes, 0)? != magic {
            return Err(ProcessLoadError::InvalidPath);
        }
        let path_len = read_request_u32(bytes, 4)? as usize;
        if path_len > k16_abi::syscall::MAX_RUN_PATH_BYTES {
            return Err(ProcessLoadError::InvalidPath);
        }
        let argc = read_request_u32(bytes, 8)? as usize;
        if argc == 0 || argc > k16_abi::syscall::MAX_RUN_ARGS {
            return Err(ProcessLoadError::InvalidPath);
        }
        let mut arg_lengths = [0_usize; k16_abi::syscall::MAX_RUN_ARGS];
        let mut index = 0;
        while index < argc {
            let len = read_request_u32(bytes, 12 + index * 4)? as usize;
            if len > k16_abi::syscall::MAX_RUN_ARG_BYTES {
                return Err(ProcessLoadError::InvalidPath);
            }
            arg_lengths[index] = len;
            index += 1;
        }
        let path_start = 12_usize
            .checked_add(argc.checked_mul(4).ok_or(ProcessLoadError::InvalidPath)?)
            .ok_or(ProcessLoadError::InvalidPath)?;
        let path_end = path_start
            .checked_add(path_len)
            .ok_or(ProcessLoadError::InvalidPath)?;
        let path = bytes
            .get(path_start..path_end)
            .ok_or(ProcessLoadError::InvalidPath)?;
        let mut args = [&[][..]; k16_abi::syscall::MAX_RUN_ARGS];
        let mut cursor = path_end;
        let mut index = 0;
        while index < argc {
            let arg_end = cursor
                .checked_add(arg_lengths[index])
                .ok_or(ProcessLoadError::InvalidPath)?;
            args[index] = bytes
                .get(cursor..arg_end)
                .ok_or(ProcessLoadError::InvalidPath)?;
            cursor = arg_end;
            index += 1;
        }
        if cursor != bytes.len() {
            return Err(ProcessLoadError::InvalidPath);
        }
        Ok(Self { path, argc, args })
    }
}

fn read_request_u32(bytes: &[u8], offset: usize) -> Result<u32, ProcessLoadError> {
    let end = offset.checked_add(4).ok_or(ProcessLoadError::InvalidPath)?;
    let word = bytes
        .get(offset..end)
        .ok_or(ProcessLoadError::InvalidPath)?;
    Ok(u32::from_le_bytes([word[0], word[1], word[2], word[3]]))
}

pub unsafe fn install_child_argv(
    plan: DynamicUserLoadPlan,
    args: &[&[u8]],
) -> Result<ChildArgv, ProcessLoadError> {
    let layout = ChildArgvLayout::new(plan, args)?;
    if args.is_empty() {
        return Ok(layout.child_argv());
    }
    let mut arg_ptr = layout.arg_data_ptr;
    let mut index = 0_u32;
    for arg in args {
        let entry_ptr = layout.table_ptr + index * CHILD_ARG_ENTRY_BYTES;
        let arg_len = arg.len() as u32;
        unsafe {
            write_u32_le(entry_ptr, arg_ptr);
            write_u32_le(entry_ptr + 4, arg_len);
            copy_bytes_to_ram(arg, arg_ptr);
        }
        arg_ptr = arg_ptr
            .checked_add(arg_len)
            .ok_or(ProcessLoadError::AddressOverflow)?;
        index += 1;
    }
    Ok(layout.child_argv())
}

pub unsafe fn install_mapped_child_argv(
    plan: MappedDynamicUserLoadPlan,
    args: &[&[u8]],
) -> Result<ChildArgv, ProcessLoadError> {
    let virtual_plan = plan.virtual_plan();
    let layout = ChildArgvLayout::new(virtual_plan, args)?;
    if args.is_empty() {
        return Ok(layout.child_argv());
    }
    let mut arg_ptr = layout.arg_data_ptr;
    let mut index = 0_u32;
    for arg in args {
        let entry_ptr = layout.table_ptr + index * CHILD_ARG_ENTRY_BYTES;
        let arg_len = arg.len() as u32;
        let physical_entry_ptr = plan.translate_address(entry_ptr)?;
        let physical_arg_ptr = plan.translate_address(arg_ptr)?;
        unsafe {
            write_u32_le(physical_entry_ptr, arg_ptr);
            write_u32_le(physical_entry_ptr + 4, arg_len);
            copy_bytes_to_ram(arg, physical_arg_ptr);
        }
        arg_ptr = arg_ptr
            .checked_add(arg_len)
            .ok_or(ProcessLoadError::AddressOverflow)?;
        index += 1;
    }
    Ok(layout.child_argv())
}

#[cfg(test)]
unsafe fn create_translated_user_launch(
    plan: DynamicUserLoadPlan,
    kernel_stack_top: u32,
) -> Result<TranslatedUserLaunch, ProcessLoadError> {
    let map_start = page_align_down(plan.load_base);
    let map_end = page_align_up(plan.stack_top)?;
    if map_start >= map_end {
        return Err(ProcessLoadError::InvalidArena);
    }
    let page_count = (map_end - map_start) / VM_PAGE_SIZE;
    let address_space = unsafe { mmu0_create_address_space()? };
    let map_result = unsafe {
        mmu0_map_pages(
            address_space,
            map_start,
            map_start,
            page_count,
            k16_abi::computer::mmu0::FLAG_USER_ACCESSIBLE
                | k16_abi::computer::mmu0::FLAG_WRITABLE
                | k16_abi::computer::mmu0::FLAG_EXECUTABLE,
        )
    };
    if let Err(error) = map_result {
        let _ = unsafe { mmu0_destroy_address_space(address_space) };
        return Err(error);
    }
    Ok(TranslatedUserLaunch {
        address_space,
        entry_pc: plan.entry_pc,
        stack_top: plan.stack_top,
        kernel_stack_top,
        backing_pages: None,
    })
}

#[cfg(test)]
unsafe fn create_translated_user_launch_with_allocator(
    plan: DynamicUserLoadPlan,
    kernel_stack_top: u32,
    allocator: &mut crate::page_alloc::PageFrameAllocator,
) -> Result<TranslatedUserLaunch, ProcessLoadError> {
    let mapped = allocate_mapped_dynamic_user_load_plan(plan, allocator)?;
    match unsafe { create_translated_user_launch_from_mapped(mapped, kernel_stack_top) } {
        Ok(launch) => Ok(launch),
        Err(error) => {
            let _ = allocator.free_contiguous(crate::page_alloc::FrameRange {
                start: mapped.backing_start(),
                frame_count: mapped.page_count(),
            });
            Err(error)
        }
    }
}

unsafe fn create_translated_user_launch_from_mapped(
    mapped: MappedDynamicUserLoadPlan,
    kernel_stack_top: u32,
) -> Result<TranslatedUserLaunch, ProcessLoadError> {
    let address_space = unsafe { mmu0_create_address_space()? };
    let map_result = unsafe {
        mmu0_map_pages(
            address_space,
            mapped.map_start(),
            mapped.backing_start(),
            mapped.image_page_count(),
            k16_abi::computer::mmu0::FLAG_USER_ACCESSIBLE
                | k16_abi::computer::mmu0::FLAG_WRITABLE
                | k16_abi::computer::mmu0::FLAG_EXECUTABLE,
        )
    };
    if let Err(error) = map_result {
        let _ = unsafe { mmu0_destroy_address_space(address_space) };
        return Err(error);
    }
    if mapped.stack_page_count() > 0 {
        let stack_backing_start = mapped
            .backing_start()
            .checked_add(
                mapped
                    .image_page_count()
                    .checked_mul(VM_PAGE_SIZE)
                    .ok_or(ProcessLoadError::AddressOverflow)?,
            )
            .ok_or(ProcessLoadError::AddressOverflow)?;
        let stack_map_result = unsafe {
            mmu0_map_pages(
                address_space,
                mapped.stack_map_start(),
                stack_backing_start,
                mapped.stack_page_count(),
                k16_abi::computer::mmu0::FLAG_USER_ACCESSIBLE
                    | k16_abi::computer::mmu0::FLAG_WRITABLE,
            )
        };
        if let Err(error) = stack_map_result {
            let _ = unsafe { mmu0_destroy_address_space(address_space) };
            return Err(error);
        }
    }
    let plan = mapped.virtual_plan();
    Ok(TranslatedUserLaunch {
        address_space,
        entry_pc: plan.entry_pc,
        stack_top: plan.stack_top,
        kernel_stack_top,
        backing_pages: Some(crate::page_alloc::FrameRange {
            start: mapped.backing_start(),
            frame_count: mapped.page_count(),
        }),
    })
}

fn page_alloc_error_to_process_load_error(
    error: crate::page_alloc::PageAllocError,
) -> ProcessLoadError {
    match error {
        crate::page_alloc::PageAllocError::InvalidRange => ProcessLoadError::InvalidArena,
        crate::page_alloc::PageAllocError::OutOfMemory => ProcessLoadError::ProgramTooLarge,
    }
}

unsafe fn mmu0_create_address_space() -> Result<u32, ProcessLoadError> {
    unsafe {
        submit_mmu0_command(k16_abi::computer::mmu0::COMMAND_CREATE_ADDRESS_SPACE)?;
        let address_space = crate::mmio::read_i32(k16_abi::computer::mmu0::RESULT) as u32;
        if address_space == 0 {
            return Err(ProcessLoadError::Storage);
        }
        Ok(address_space)
    }
}

unsafe fn mmu0_map_pages(
    address_space: u32,
    virtual_start: u32,
    physical_start: u32,
    page_count: u32,
    flags: i32,
) -> Result<(), ProcessLoadError> {
    unsafe {
        crate::mmio::write_i32(k16_abi::computer::mmu0::ADDRESS_SPACE, address_space as i32);
        crate::mmio::write_i32(k16_abi::computer::mmu0::VIRTUAL_START, virtual_start as i32);
        crate::mmio::write_i32(
            k16_abi::computer::mmu0::PHYSICAL_START,
            physical_start as i32,
        );
        crate::mmio::write_i32(k16_abi::computer::mmu0::PAGE_COUNT, page_count as i32);
        crate::mmio::write_i32(k16_abi::computer::mmu0::FLAGS, flags);
        submit_mmu0_command(k16_abi::computer::mmu0::COMMAND_MAP_PAGES)
    }
}

unsafe fn map_translated_heap_pages(
    address_space: u32,
    virtual_start: u32,
    physical_start: u32,
    page_count: u32,
) -> Result<(), ProcessLoadError> {
    unsafe {
        mmu0_map_pages(
            address_space,
            virtual_start,
            physical_start,
            page_count,
            k16_abi::computer::mmu0::FLAG_USER_ACCESSIBLE | k16_abi::computer::mmu0::FLAG_WRITABLE,
        )
    }
}

unsafe fn mmu0_destroy_address_space(address_space: u32) -> Result<(), ProcessLoadError> {
    unsafe {
        crate::mmio::write_i32(k16_abi::computer::mmu0::ADDRESS_SPACE, address_space as i32);
        submit_mmu0_command(k16_abi::computer::mmu0::COMMAND_DESTROY_ADDRESS_SPACE)
    }
}

unsafe fn mmu0_activate_user_address_space(
    address_space: u32,
    entry_pc: u32,
    stack_pointer: u32,
    kernel_stack_pointer: u32,
) -> Result<(), ProcessLoadError> {
    unsafe {
        crate::mmio::write_i32(k16_abi::computer::mmu0::ADDRESS_SPACE, address_space as i32);
        crate::mmio::write_i32(
            k16_abi::computer::mmu0::PHYSICAL_START,
            kernel_stack_pointer as i32,
        );
        crate::mmio::write_i32(k16_abi::computer::mmu0::ENTRY_PC, entry_pc as i32);
        crate::mmio::write_i32(k16_abi::computer::mmu0::STACK_POINTER, stack_pointer as i32);
        submit_mmu0_command(k16_abi::computer::mmu0::COMMAND_ACTIVATE_USER_ADDRESS_SPACE)
    }
}

#[cfg(not(test))]
unsafe fn mmu0_set_trap_return_physical() -> Result<(), ProcessLoadError> {
    unsafe { submit_mmu0_command(k16_abi::computer::mmu0::COMMAND_SET_TRAP_RETURN_PHYSICAL) }
}

#[cfg(not(test))]
unsafe fn mmu0_set_trap_return_address_space(
    address_space: u32,
    kernel_stack_pointer: u32,
) -> Result<(), ProcessLoadError> {
    unsafe {
        crate::mmio::write_i32(k16_abi::computer::mmu0::ADDRESS_SPACE, address_space as i32);
        crate::mmio::write_i32(
            k16_abi::computer::mmu0::PHYSICAL_START,
            kernel_stack_pointer as i32,
        );
        submit_mmu0_command(k16_abi::computer::mmu0::COMMAND_SET_TRAP_RETURN_ADDRESS_SPACE)
    }
}

pub unsafe fn destroy_exited_address_space(resume: &ParentResume) -> Result<(), ProcessLoadError> {
    if let Some(address_space) = resume.exited_address_space {
        unsafe { mmu0_destroy_address_space(address_space)? };
    }
    #[cfg(not(test))]
    if let Some(backing_pages) = resume.exited_backing_pages {
        let allocator =
            unsafe { (*RUNTIME_PAGE_ALLOCATOR.get()).as_mut() }.ok_or(ProcessLoadError::Storage)?;
        allocator
            .free_contiguous(backing_pages)
            .map_err(page_alloc_error_to_process_load_error)?;
    }
    #[cfg(not(test))]
    if let Some(heap_pages) = resume.exited_heap_pages {
        let allocator =
            unsafe { (*RUNTIME_PAGE_ALLOCATOR.get()).as_mut() }.ok_or(ProcessLoadError::Storage)?;
        allocator
            .free_contiguous(heap_pages)
            .map_err(page_alloc_error_to_process_load_error)?;
    }
    Ok(())
}

#[cfg(any(not(test), feature = "host-test"))]
fn halt_runtime_with_panic_code(code: i32) -> ! {
    unsafe {
        crate::mmio::write_i32(k16_abi::computer::control::PANIC_CODE, code);
        crate::mmio::write_i32(
            k16_abi::computer::control::STATUS,
            k16_abi::computer::status::HALTED,
        );
    }
    k16_rt::halt_forever()
}

unsafe fn submit_mmu0_command(command: i32) -> Result<(), ProcessLoadError> {
    unsafe {
        crate::mmio::write_i32(k16_abi::computer::mmu0::COMMAND, command);
        if crate::mmio::read_i32(k16_abi::computer::mmu0::STATUS)
            != k16_abi::computer::mmu0::STATUS_DONE
        {
            return Err(ProcessLoadError::Storage);
        }
    }
    Ok(())
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
    let heap_start = align_up(load_end, HEAP_ALIGNMENT)?;
    let heap_limit =
        heap_limit_from_stack_top(stack_top).map_err(|_| ProcessLoadError::ProgramTooLarge)?;
    if heap_start > heap_limit {
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
    }
    unsafe { load_selected_dynamic_user_program(arena) }
}

pub unsafe fn load_dynamic_user_program_from_storage0_mapped(
    path: &[u8],
    arena: UserArena,
    allocator: &mut crate::page_alloc::PageFrameAllocator,
) -> Result<MappedDynamicUserLoadPlan, ProcessLoadError> {
    let path = UserProgramPath::parse(path)?;
    unsafe {
        k16_storage::open_file_from_storage0(ROOT_PARTITION, path.components())
            .map_err(|_| ProcessLoadError::Storage)?;
    }
    unsafe { load_selected_dynamic_user_program_mapped(arena, allocator) }
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
    validate_dynamic_header_bytes(header, unsafe { k16_storage::selected_file_size() })?;
    let entry_offset = header_u32(header, 12);
    let payload_offset = header_u32(header, 40);
    let file_size = header_u32(header, 44);
    let memory_size = header_u32(header, 48);
    let relocation_table_offset = header_u32(header, 60);
    let relocation_count = header_u32(header, 68);
    let plan = plan_dynamic_user_load(
        arena,
        DynamicUserImage {
            entry_offset,
            file_size,
            memory_size,
        },
    )?;

    unsafe {
        k16_storage::copy_selected_file_range_to_ram(
            payload_offset,
            plan.payload_dst,
            plan.payload_len,
        )
        .map_err(|_| ProcessLoadError::Storage)?;
    }
    unsafe {
        zero_fill_ram(plan.zero_fill_addr, plan.zero_fill_len);
        apply_selected_file_relocations(
            relocation_table_offset,
            relocation_count,
            memory_size,
            plan,
        )?;
    }
    Ok(plan)
}

pub unsafe fn load_selected_dynamic_user_program_mapped(
    arena: UserArena,
    allocator: &mut crate::page_alloc::PageFrameAllocator,
) -> Result<MappedDynamicUserLoadPlan, ProcessLoadError> {
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
    validate_dynamic_header_bytes(header, unsafe { k16_storage::selected_file_size() })?;
    let entry_offset = header_u32(header, 12);
    let payload_offset = header_u32(header, 40);
    let file_size = header_u32(header, 44);
    let memory_size = header_u32(header, 48);
    let relocation_table_offset = header_u32(header, 60);
    let relocation_count = header_u32(header, 68);
    let plan = plan_dynamic_user_load(
        arena,
        DynamicUserImage {
            entry_offset,
            file_size,
            memory_size,
        },
    )?;
    let mapped = allocate_mapped_dynamic_user_load_plan(plan, allocator)?;

    if let Err(error) = unsafe {
        k16_storage::copy_selected_file_range_to_ram(
            payload_offset,
            mapped.payload_dst(),
            plan.payload_len,
        )
        .map_err(|_| ProcessLoadError::Storage)
    } {
        let _ = free_mapped_dynamic_user_load_plan(mapped, allocator);
        return Err(error);
    }
    unsafe {
        zero_fill_ram(mapped.zero_fill_addr(), plan.zero_fill_len);
    }
    if let Err(error) = unsafe {
        apply_selected_file_relocations_mapped(
            relocation_table_offset,
            relocation_count,
            memory_size,
            mapped,
        )
    } {
        let _ = free_mapped_dynamic_user_load_plan(mapped, allocator);
        return Err(error);
    }
    Ok(mapped)
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
    relocation_table_offset: u32,
    relocation_count: u32,
    memory_size: u32,
    plan: DynamicUserLoadPlan,
) -> Result<(), ProcessLoadError> {
    let mut index = 0;
    while index < relocation_count {
        let relocation_offset = relocation_table_offset
            .checked_add(
                index
                    .checked_mul(k16_image::K16E_RELOCATION_RECORD_SIZE)
                    .ok_or(ProcessLoadError::AddressOverflow)?,
            )
            .ok_or(ProcessLoadError::AddressOverflow)?;
        unsafe {
            k16_storage::copy_selected_file_range_to_ram(
                relocation_offset,
                RELOCATION_RECORD_ADDR,
                k16_image::K16E_RELOCATION_RECORD_SIZE,
            )
            .map_err(|_| ProcessLoadError::Storage)?;
        }
        let relocation_offset = unsafe { read_u32_le(RELOCATION_RECORD_ADDR) };
        let relocation_kind = unsafe { read_u32_le(RELOCATION_RECORD_ADDR + 4) };
        validate_dynamic_relocation_record(relocation_offset, relocation_kind, memory_size)?;
        unsafe { apply_dynamic_relocation_to_ram(plan, relocation_offset)? };
        index += 1;
    }
    Ok(())
}

unsafe fn apply_selected_file_relocations_mapped(
    relocation_table_offset: u32,
    relocation_count: u32,
    memory_size: u32,
    plan: MappedDynamicUserLoadPlan,
) -> Result<(), ProcessLoadError> {
    let mut index = 0;
    while index < relocation_count {
        let relocation_offset = relocation_table_offset
            .checked_add(
                index
                    .checked_mul(k16_image::K16E_RELOCATION_RECORD_SIZE)
                    .ok_or(ProcessLoadError::AddressOverflow)?,
            )
            .ok_or(ProcessLoadError::AddressOverflow)?;
        unsafe {
            k16_storage::copy_selected_file_range_to_ram(
                relocation_offset,
                RELOCATION_RECORD_ADDR,
                k16_image::K16E_RELOCATION_RECORD_SIZE,
            )
            .map_err(|_| ProcessLoadError::Storage)?;
        }
        let relocation_offset = unsafe { read_u32_le(RELOCATION_RECORD_ADDR) };
        let relocation_kind = unsafe { read_u32_le(RELOCATION_RECORD_ADDR + 4) };
        validate_dynamic_relocation_record(relocation_offset, relocation_kind, memory_size)?;
        unsafe { apply_dynamic_relocation_to_mapped_ram(plan, relocation_offset)? };
        index += 1;
    }
    Ok(())
}

fn validate_dynamic_header_bytes(header: &[u8], inode_size: u32) -> Result<(), ProcessLoadError> {
    if header.len() < k16_image::DYNAMIC_K16E_V2_HEADER_SIZE as usize
        || header.get(0..4) != Some(b"K16E")
        || header_u16(header, 4) != 2
        || header_u16(header, 6) != 32
        || header_u16(header, 8) != 1
        || header_u16(header, 10) != 0
        || header_u32(header, 16) != 32
        || header_u32(header, 20) != 2
        || header_u32(header, 24) != k16_image::K16eAbiKind::Program as u32
        || header_u32(header, 28) != 0
        || header_u32(header, 32) != 1
        || header_u32(header, 36) != 0
        || header_u32(header, 40) != k16_image::DYNAMIC_K16E_V2_PAYLOAD_OFFSET
        || header_u32(header, 52) != 2
        || header_u32(header, 56) != 0
    {
        return Err(ProcessLoadError::InvalidImage);
    }

    let entry_offset = header_u32(header, 12);
    let file_size = header_u32(header, 44);
    let memory_size = header_u32(header, 48);
    if file_size == 0 || memory_size < file_size || file_size % 2 != 0 || memory_size % 2 != 0 {
        return Err(ProcessLoadError::InvalidImage);
    }
    if entry_offset >= memory_size || entry_offset % 2 != 0 {
        return Err(ProcessLoadError::InvalidImage);
    }

    let relocation_table_offset = header_u32(header, 60);
    let relocation_table_size = header_u32(header, 64);
    let relocation_count = header_u32(header, 68);
    let payload_end = k16_image::DYNAMIC_K16E_V2_PAYLOAD_OFFSET
        .checked_add(file_size)
        .ok_or(ProcessLoadError::InvalidImage)?;
    if relocation_table_offset != payload_end {
        return Err(ProcessLoadError::InvalidImage);
    }
    let expected_relocation_table_size = relocation_count
        .checked_mul(k16_image::K16E_RELOCATION_RECORD_SIZE)
        .ok_or(ProcessLoadError::InvalidImage)?;
    if relocation_table_size != expected_relocation_table_size {
        return Err(ProcessLoadError::InvalidImage);
    }
    let file_end = relocation_table_offset
        .checked_add(relocation_table_size)
        .ok_or(ProcessLoadError::InvalidImage)?;
    if file_end > inode_size {
        return Err(ProcessLoadError::InvalidImage);
    }
    Ok(())
}

fn validate_dynamic_relocation_record(
    offset: u32,
    kind: u32,
    memory_size: u32,
) -> Result<(), ProcessLoadError> {
    if kind != 1 && kind != 2 {
        return Err(ProcessLoadError::InvalidImage);
    }
    if offset % 2 != 0 {
        return Err(ProcessLoadError::InvalidImage);
    }
    let end = offset
        .checked_add(4)
        .ok_or(ProcessLoadError::InvalidImage)?;
    if end > memory_size {
        return Err(ProcessLoadError::InvalidImage);
    }
    Ok(())
}

fn header_u16(bytes: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes([bytes[offset], bytes[offset + 1]])
}

fn header_u32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}

unsafe fn apply_dynamic_relocation_to_ram(
    plan: DynamicUserLoadPlan,
    relocation_offset: u32,
) -> Result<(), ProcessLoadError> {
    let field_addr = plan
        .load_base
        .checked_add(relocation_offset)
        .ok_or(ProcessLoadError::AddressOverflow)?;
    let value = unsafe { read_u32_le(field_addr) };
    let relocated = value
        .checked_add(plan.load_base)
        .ok_or(ProcessLoadError::AddressOverflow)?;
    unsafe { write_u32_le(field_addr, relocated) };
    Ok(())
}

unsafe fn apply_dynamic_relocation_to_mapped_ram(
    plan: MappedDynamicUserLoadPlan,
    relocation_offset: u32,
) -> Result<(), ProcessLoadError> {
    let field_addr = plan.relocation_field_addr(relocation_offset)?;
    let value = unsafe { read_u32_le(field_addr) };
    let relocated = value
        .checked_add(plan.virtual_plan().load_base)
        .ok_or(ProcessLoadError::AddressOverflow)?;
    unsafe { write_u32_le(field_addr, relocated) };
    Ok(())
}

#[cfg(test)]
fn apply_dynamic_relocation_to_slice(
    memory: &mut [u8],
    memory_base: u32,
    plan: DynamicUserLoadPlan,
    relocation_offset: u32,
) -> Result<(), ProcessLoadError> {
    let field_addr = plan
        .load_base
        .checked_add(relocation_offset)
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

#[cfg(test)]
fn apply_dynamic_relocation_to_mapped_slice(
    memory: &mut [u8],
    memory_base: u32,
    plan: MappedDynamicUserLoadPlan,
    relocation_offset: u32,
) -> Result<(), ProcessLoadError> {
    let field_addr = plan.relocation_field_addr(relocation_offset)?;
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
        .checked_add(plan.virtual_plan().load_base)
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

unsafe fn copy_bytes_to_ram(bytes: &[u8], dst_addr: u32) {
    let mut offset = 0;
    while offset < bytes.len() {
        unsafe { write_u8(dst_addr + offset as u32, bytes[offset]) };
        offset += 1;
    }
}

unsafe fn read_u32_le(address: u32) -> u32 {
    let b0 = unsafe { read_u8(address) };
    let b1 = unsafe { read_u8(address + 1) };
    let b2 = unsafe { read_u8(address + 2) };
    let b3 = unsafe { read_u8(address + 3) };
    u32::from_le_bytes([b0, b1, b2, b3])
}

unsafe fn write_u32_le(address: u32, value: u32) {
    let bytes = value.to_le_bytes();
    unsafe {
        write_u8(address, bytes[0]);
        write_u8(address + 1, bytes[1]);
        write_u8(address + 2, bytes[2]);
        write_u8(address + 3, bytes[3]);
    }
}

unsafe fn read_u8(address: u32) -> u8 {
    unsafe { core::ptr::read_volatile(address as usize as *const u8) }
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

const fn page_align_down(value: u32) -> u32 {
    value & !(VM_PAGE_SIZE - 1)
}

fn page_align_up(value: u32) -> Result<u32, ProcessLoadError> {
    align_up(value, VM_PAGE_SIZE)
}

fn heap_page_align_up(value: u32) -> Result<u32, HeapError> {
    align_up(value, VM_PAGE_SIZE).map_err(|_| HeapError::OutOfMemory)
}

fn heap_committed_end(heap_start: u32, heap_frame_count: Option<u32>) -> Result<u32, HeapError> {
    let base = heap_page_align_up(heap_start)?;
    let bytes = heap_frame_count
        .unwrap_or(0)
        .checked_mul(VM_PAGE_SIZE)
        .ok_or(HeapError::OutOfMemory)?;
    base.checked_add(bytes).ok_or(HeapError::OutOfMemory)
}

fn heap_growth_commit_range(
    committed_end: u32,
    requested_break: u32,
) -> Result<Option<(u32, u32)>, HeapError> {
    if committed_end % VM_PAGE_SIZE != 0 {
        return Err(HeapError::OutOfMemory);
    }
    if requested_break <= committed_end {
        return Ok(None);
    }
    let map_end = heap_page_align_up(requested_break)?;
    let page_count = (map_end - committed_end) / VM_PAGE_SIZE;
    Ok(Some((committed_end, page_count)))
}

fn coalesce_heap_backing_range(
    existing: Option<crate::page_alloc::FrameRange>,
    next: crate::page_alloc::FrameRange,
) -> Result<Option<crate::page_alloc::FrameRange>, HeapError> {
    let Some(existing) = existing else {
        return Ok(Some(next));
    };
    let existing_end = existing
        .start
        .checked_add(
            existing
                .frame_count
                .checked_mul(VM_PAGE_SIZE)
                .ok_or(HeapError::OutOfMemory)?,
        )
        .ok_or(HeapError::OutOfMemory)?;
    if existing_end != next.start {
        return Err(HeapError::OutOfMemory);
    }
    Ok(Some(crate::page_alloc::FrameRange {
        start: existing.start,
        frame_count: existing
            .frame_count
            .checked_add(next.frame_count)
            .ok_or(HeapError::OutOfMemory)?,
    }))
}

fn heap_limit_from_stack_top(stack_top: u32) -> Result<u32, HeapError> {
    let guarded = stack_top
        .checked_sub(TRANSLATED_USER_STACK_BYTES)
        .ok_or(HeapError::OutOfMemory)?
        .checked_sub(STACK_GUARD_BYTES)
        .ok_or(HeapError::OutOfMemory)?;
    Ok(align_down(guarded, STACK_ALIGNMENT))
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
    fn mapped_dynamic_user_load_plan_translates_kernel_writes_to_backing_pages() {
        let plan = DynamicUserLoadPlan {
            load_base: 0x0001_5020,
            load_end: 0x0001_6020,
            entry_pc: 0x0001_5024,
            stack_top: 0x0001_c000,
            payload_dst: 0x0001_5020,
            payload_len: 16,
            zero_fill_addr: 0x0001_5030,
            zero_fill_len: 16,
        };

        let mapped = MappedDynamicUserLoadPlan::new(plan, 0x0001_5000, 0x0000_9000, 7)
            .expect("mapped load plan is valid");

        assert_eq!(mapped.virtual_plan(), plan);
        assert_eq!(mapped.backing_start(), 0x0000_9000);
        assert_eq!(mapped.payload_dst(), 0x0000_9020);
        assert_eq!(mapped.zero_fill_addr(), 0x0000_9030);
        assert_eq!(mapped.relocation_field_addr(4), Ok(0x0000_9024));
        assert_eq!(
            MappedDynamicUserLoadPlan::new(plan, 0x0001_5000, 0x0000_9000, 6),
            Err(ProcessLoadError::InvalidArena)
        );
    }

    #[test]
    fn mapped_dynamic_relocation_reads_physical_field_and_writes_virtual_value() {
        let plan = DynamicUserLoadPlan {
            load_base: 0x0001_5020,
            load_end: 0x0001_6020,
            entry_pc: 0x0001_5024,
            stack_top: 0x0001_c000,
            payload_dst: 0x0001_5020,
            payload_len: 16,
            zero_fill_addr: 0x0001_5030,
            zero_fill_len: 16,
        };
        let mapped = MappedDynamicUserLoadPlan::new(plan, 0x0001_5000, 0x0000_9000, 7)
            .expect("mapped load plan is valid");
        let mut memory = [0u8; 0x80];
        write_u32_le(&mut memory, 0x24, 0x20);

        apply_dynamic_relocation_to_mapped_slice(&mut memory, 0x0000_9000, mapped, 4)
            .expect("relocation applies");

        assert_eq!(
            u32::from_le_bytes([memory[0x24], memory[0x25], memory[0x26], memory[0x27]]),
            0x0001_5040
        );
    }

    #[test]
    fn mapped_dynamic_user_load_plan_allocates_backing_pages_from_allocator() {
        let plan = DynamicUserLoadPlan {
            load_base: 0x0001_5020,
            load_end: 0x0001_6020,
            entry_pc: 0x0001_5024,
            stack_top: 0x0001_c000,
            payload_dst: 0x0001_5020,
            payload_len: 16,
            zero_fill_addr: 0x0001_5030,
            zero_fill_len: 16,
        };
        let mut allocator =
            crate::page_alloc::PageFrameAllocator::new(0x0003_0000).expect("allocator initializes");
        allocator
            .reserve_range(0, 0x0000_9000)
            .expect("kernel frames reserve");

        let mapped = allocate_mapped_dynamic_user_load_plan(plan, &mut allocator)
            .expect("mapped load plan allocates");

        assert_eq!(mapped.virtual_plan(), plan);
        assert_eq!(mapped.map_start(), 0x0001_5000);
        assert_eq!(mapped.backing_start(), 0x0000_9000);
        assert_eq!(mapped.image_page_count(), 2);
        assert_eq!(mapped.stack_map_start(), 0x0001_a000);
        assert_eq!(mapped.stack_page_count(), 2);
        assert_eq!(mapped.page_count(), 4);
        assert_eq!(
            allocator
                .allocate_contiguous(1)
                .expect("next frame allocates"),
            crate::page_alloc::FrameRange {
                start: 0x0000_d000,
                frame_count: 1,
            }
        );
    }

    #[test]
    fn mapped_dynamic_user_load_plan_commits_two_stack_pages() {
        let plan = DynamicUserLoadPlan {
            load_base: 0x0001_5020,
            load_end: 0x0001_6020,
            entry_pc: 0x0001_5024,
            stack_top: 0x0001_c000,
            payload_dst: 0x0001_5020,
            payload_len: 16,
            zero_fill_addr: 0x0001_5030,
            zero_fill_len: 16,
        };
        let mut allocator =
            crate::page_alloc::PageFrameAllocator::new(0x0003_0000).expect("allocator initializes");
        allocator
            .reserve_range(0, 0x0000_9000)
            .expect("kernel frames reserve");

        let mapped = allocate_mapped_dynamic_user_load_plan(plan, &mut allocator)
            .expect("mapped load plan allocates");

        assert_eq!(mapped.translate_address(0x0001_a000), Ok(0x0000_b000));
        assert_eq!(mapped.translate_address(0x0001_bffc), Ok(0x0000_cffc));
        assert_eq!(
            mapped.translate_address(0x0001_9ffc),
            Err(ProcessLoadError::InvalidArena)
        );
    }

    #[test]
    fn mapped_dynamic_user_load_plan_leaves_uncommitted_arena_pages_free() {
        let mut allocator =
            crate::page_alloc::PageFrameAllocator::new(0x0003_0000).expect("allocator initializes");
        allocator
            .reserve_range(0, 0x0001_6000)
            .expect("kernel image reserves");
        allocator
            .reserve_range(0x0002_f000, 0x0003_0000)
            .expect("kernel trap stack reserves");
        let init_plan = DynamicUserLoadPlan {
            load_base: 0x0001_6000,
            load_end: 0x0001_6612,
            entry_pc: 0x0001_606e,
            stack_top: 0x0002_f000,
            payload_dst: 0x0001_6000,
            payload_len: 0x612,
            zero_fill_addr: 0x0001_6612,
            zero_fill_len: 0,
        };

        let mapped = allocate_mapped_dynamic_user_load_plan(init_plan, &mut allocator)
            .expect("init backing allocates");

        assert_eq!(mapped.map_start(), 0x0001_6000);
        assert_eq!(mapped.image_page_count(), 1);
        assert_eq!(mapped.stack_map_start(), 0x0002_d000);
        assert_eq!(mapped.stack_page_count(), 2);
        assert_eq!(mapped.page_count(), 3);
        assert!(
            allocator.free_frames() >= 20,
            "uncommitted arena pages should remain available for shell child"
        );
    }

    #[test]
    fn mapped_dynamic_user_load_plan_releases_backing_pages_to_allocator() {
        let plan = DynamicUserLoadPlan {
            load_base: 0x0001_5020,
            load_end: 0x0001_6020,
            entry_pc: 0x0001_5024,
            stack_top: 0x0001_c000,
            payload_dst: 0x0001_5020,
            payload_len: 16,
            zero_fill_addr: 0x0001_5030,
            zero_fill_len: 16,
        };
        let mut allocator =
            crate::page_alloc::PageFrameAllocator::new(0x0003_0000).expect("allocator initializes");
        allocator
            .reserve_range(0, 0x0000_9000)
            .expect("kernel frames reserve");
        let mapped = allocate_mapped_dynamic_user_load_plan(plan, &mut allocator)
            .expect("mapped load plan allocates");

        free_mapped_dynamic_user_load_plan(mapped, &mut allocator).expect("mapped load plan frees");

        assert_eq!(
            allocator
                .allocate_contiguous(4)
                .expect("released frames allocate"),
            crate::page_alloc::FrameRange {
                start: 0x0000_9000,
                frame_count: 4,
            }
        );
    }

    #[test]
    fn heap_growth_commit_range_starts_after_current_mapped_page() {
        assert_eq!(heap_committed_end(0x0001_6612, None), Ok(0x0001_7000));
        assert_eq!(heap_committed_end(0x0001_6612, Some(2)), Ok(0x0001_9000));
        assert_eq!(heap_growth_commit_range(0x0001_7000, 0x0001_6fff), Ok(None));
        assert_eq!(
            heap_growth_commit_range(0x0001_7000, 0x0001_7001),
            Ok(Some((0x0001_7000, 1)))
        );
        assert_eq!(
            heap_growth_commit_range(0x0001_7000, 0x0001_9004),
            Ok(Some((0x0001_7000, 3)))
        );
    }

    #[test]
    fn translated_heap_pages_are_mapped_writable_without_execute() {
        crate::mmio::reset_test_state();
        crate::mmio::set_test_mmu0_result(0, k16_abi::computer::mmu0::STATUS_DONE, 0);

        unsafe { map_translated_heap_pages(7, 0x0001_7000, 0x0000_c000, 2) }
            .expect("heap pages map");

        let writes = crate::mmio::take_test_writes();
        let writes = writes.as_slice();
        assert_eq!(writes[0], (k16_abi::computer::mmu0::ADDRESS_SPACE, 7));
        assert_eq!(
            writes[1],
            (k16_abi::computer::mmu0::VIRTUAL_START, 0x0001_7000)
        );
        assert_eq!(
            writes[2],
            (k16_abi::computer::mmu0::PHYSICAL_START, 0x0000_c000)
        );
        assert_eq!(writes[3], (k16_abi::computer::mmu0::PAGE_COUNT, 2));
        assert_eq!(
            writes[4],
            (
                k16_abi::computer::mmu0::FLAGS,
                (k16_abi::computer::mmu0::FLAG_USER_ACCESSIBLE
                    | k16_abi::computer::mmu0::FLAG_WRITABLE) as u32
            )
        );
        assert_eq!(
            writes[5],
            (
                k16_abi::computer::mmu0::COMMAND,
                k16_abi::computer::mmu0::COMMAND_MAP_PAGES as u32
            )
        );
    }

    #[test]
    fn heap_backing_range_coalesces_adjacent_growth() {
        let initial = crate::page_alloc::FrameRange {
            start: 0x0000_c000,
            frame_count: 2,
        };
        let next = crate::page_alloc::FrameRange {
            start: 0x0000_e000,
            frame_count: 1,
        };

        assert_eq!(
            coalesce_heap_backing_range(Some(initial), next),
            Ok(Some(crate::page_alloc::FrameRange {
                start: 0x0000_c000,
                frame_count: 3,
            }))
        );
        assert_eq!(
            coalesce_heap_backing_range(None, initial),
            Ok(Some(initial))
        );
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

        apply_dynamic_relocation_to_slice(&mut memory, 0x0000_9000, plan, 4)
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

        assert_eq!(child.id, ProcessId::from_raw(2));
        assert_eq!(
            child.context,
            ProcessContext {
                entry_pc: 0x0000_a004,
                stack_top: 0x0001_0000,
            }
        );
        assert_eq!(table.init_state(), PROCESS_STATE_BLOCKED_ON_CHILD);
        assert_eq!(table.child_state(), PROCESS_STATE_RUNNING);
    }

    #[test]
    fn process_table_spawn_keeps_parent_running_and_child_ready() {
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

        let child = table.spawn_child(child_plan).expect("child spawns");

        assert_eq!(child, ProcessId::from_raw(2));
        assert_eq!(table.current_pid(), ProcessId::INIT);
        assert_eq!(table.init_state(), PROCESS_STATE_RUNNING);
        assert_eq!(table.child_state(), PROCESS_STATE_READY);
        assert_eq!(
            table.slots[1].lifecycle(),
            ProcessSlotLifecycle::ready_child(INIT_PROCESS_SLOT)
        );
    }

    #[test]
    fn process_table_wait_for_ready_child_blocks_parent_and_enters_child() {
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
        let wait_frame = TrapFrame {
            resume_pc: 0x0000_8100,
            stack_pointer: 0x0000_ff00,
            ..TrapFrame::zeroed()
        };
        let child = table.spawn_child(child_plan).expect("child spawns");

        let launch = table
            .wait_for_child(child, wait_frame, 0x0000_e000)
            .expect("ready child starts");

        assert_eq!(launch.id, child);
        assert_eq!(
            launch.context,
            ProcessContext {
                entry_pc: 0x0000_a004,
                stack_top: 0x0001_0000,
            }
        );
        assert_eq!(table.current_pid(), child);
        assert_eq!(table.init_state(), PROCESS_STATE_BLOCKED_ON_CHILD);
        assert_eq!(table.child_state(), PROCESS_STATE_RUNNING);
        assert_eq!(table.slots[INIT_PROCESS_SLOT].frame, wait_frame);
    }

    #[test]
    fn process_table_wait_for_any_ready_child_uses_zero_pid() {
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
        let child = table.spawn_child(child_plan).expect("child spawns");

        let launch = table
            .wait_for_child(
                ProcessId::from_raw(NO_PROCESS_PID),
                TrapFrame::zeroed(),
                0x0000_e000,
            )
            .expect("any ready child starts");

        assert_eq!(launch.id, child);
        assert_eq!(table.current_pid(), child);
    }

    #[test]
    fn process_table_wait_for_missing_child_leaves_ready_child_untouched() {
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
        table.spawn_child(child_plan).expect("child spawns");

        assert_eq!(
            table.wait_for_child(ProcessId::from_raw(999), TrapFrame::zeroed(), 0x0000_e000),
            Err(ProcessSwitchError::NoRunningChild)
        );
        assert_eq!(table.current_pid(), ProcessId::INIT);
        assert_eq!(table.child_state(), PROCESS_STATE_READY);
    }

    #[test]
    fn process_table_waited_child_exit_returns_pid_and_preserves_status_pointer() {
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
        let child = table.spawn_child(child_plan).expect("child spawns");
        table
            .wait_for_child(child, TrapFrame::zeroed(), 0x0000_e000)
            .expect("child starts");

        let resumed = table.finish_child(17).expect("parent resumes");

        assert_eq!(resumed.child_id, child);
        assert_eq!(resumed.child_exit_status, 17);
        assert_eq!(resumed.wait_status_ptr, 0x0000_e000);
        assert_eq!(resumed.return_value(), child.raw());
    }

    #[test]
    fn process_table_initializes_init_with_pid_one() {
        let table = ProcessTable::new(ProcessContext {
            entry_pc: 0x0000_8000,
            stack_top: 0x0001_0000,
        });

        assert_eq!(table.init_pid(), ProcessId::from_raw(1));
        assert_eq!(table.init_parent_pid(), None);
        assert_eq!(table.current_pid(), ProcessId::from_raw(1));
    }

    #[test]
    fn process_table_allocates_child_pid_and_records_parent_pid() {
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

        assert_eq!(child.id, ProcessId::from_raw(2));
        assert_eq!(table.current_pid(), ProcessId::from_raw(2));
        assert_eq!(table.current_parent_pid(), Some(ProcessId::from_raw(1)));
    }

    #[test]
    fn process_table_allocates_stable_pid_after_child_slot_reuse() {
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

        let first = table
            .begin_child_run(child_plan)
            .expect("first child starts");
        table.finish_child(0).expect("init resumes");
        let second = table
            .begin_child_run(child_plan)
            .expect("second child starts");

        assert_eq!(first.id, ProcessId::from_raw(2));
        assert_eq!(second.id, ProcessId::from_raw(3));
        assert_ne!(first.id, second.id);
    }

    #[test]
    fn process_table_records_exited_child_until_reap() {
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

        let resumed = table
            .finish_child_and_record_exit(7)
            .expect("parent resumes");

        assert_eq!(resumed.id, ProcessId::INIT);
        assert_eq!(resumed.child_exit_status, 7);
        assert_eq!(table.current_pid(), ProcessId::INIT);
        assert_eq!(table.child_state(), PROCESS_STATE_EXITED);
        assert_eq!(table.slots[1].pid, child.id);
        assert_eq!(table.slots[1].parent_pid, Some(ProcessId::INIT));
        assert_eq!(table.slots[1].exit_status, 7);
    }

    #[test]
    fn process_table_reaps_exited_child_by_pid() {
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
        table
            .finish_child_and_record_exit(9)
            .expect("parent resumes");

        assert_eq!(
            table.reap_exited_child(child.id),
            Ok(ProcessReap {
                pid: child.id,
                status: 9,
            })
        );
        assert_eq!(table.child_state(), PROCESS_STATE_EMPTY);
    }

    #[test]
    fn process_table_reaps_any_exited_child_with_zero_pid() {
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
        table
            .finish_child_and_record_exit(11)
            .expect("parent resumes");

        assert_eq!(
            table.reap_exited_child(ProcessId::from_raw(0)),
            Ok(ProcessReap {
                pid: child.id,
                status: 11,
            })
        );
        assert_eq!(table.child_state(), PROCESS_STATE_EMPTY);
    }

    #[test]
    fn process_table_rejects_reap_for_non_child_pid_without_clearing_exit() {
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
        table
            .finish_child_and_record_exit(13)
            .expect("parent resumes");

        assert_eq!(
            table.reap_exited_child(ProcessId::from_raw(999)),
            Err(ProcessSwitchError::NoRunningChild)
        );
        assert_eq!(table.child_state(), PROCESS_STATE_EXITED);
        assert_eq!(
            table.reap_exited_child(child.id),
            Ok(ProcessReap {
                pid: child.id,
                status: 13,
            })
        );
    }

    #[test]
    fn process_lifecycle_blocks_parent_and_starts_child_with_parent_link() {
        let parent = ProcessSlotLifecycle::running_root();

        assert_eq!(
            parent.block_on_child(1),
            Ok(ProcessSlotLifecycle {
                state: PROCESS_STATE_BLOCKED_ON_CHILD,
                parent_slot: NO_PARENT_SLOT,
                blocked_child_slot: 1,
            })
        );
        assert_eq!(
            ProcessSlotLifecycle::running_child(1),
            ProcessSlotLifecycle {
                state: PROCESS_STATE_RUNNING,
                parent_slot: 1,
                blocked_child_slot: NO_CHILD_SLOT,
            }
        );
    }

    #[test]
    fn process_lifecycle_rejects_invalid_running_child_parent_link() {
        assert_eq!(
            ProcessSlotLifecycle {
                state: PROCESS_STATE_EMPTY,
                parent_slot: 0,
                blocked_child_slot: NO_CHILD_SLOT,
            }
            .running_parent_slot(),
            Err(ProcessSwitchError::NoRunningChild)
        );
        assert_eq!(
            ProcessSlotLifecycle {
                state: PROCESS_STATE_RUNNING,
                parent_slot: NO_PARENT_SLOT,
                blocked_child_slot: NO_CHILD_SLOT,
            }
            .running_parent_slot(),
            Err(ProcessSwitchError::NoRunningChild)
        );
        assert_eq!(
            ProcessSlotLifecycle {
                state: PROCESS_STATE_RUNNING,
                parent_slot: MAX_PROCESS_SLOTS as u32,
                blocked_child_slot: NO_CHILD_SLOT,
            }
            .running_parent_slot(),
            Err(ProcessSwitchError::NoRunningChild)
        );
        assert_eq!(
            ProcessSlotLifecycle {
                state: PROCESS_STATE_RUNNING,
                parent_slot: INIT_PROCESS_SLOT as u32,
                blocked_child_slot: 1,
            }
            .running_parent_slot(),
            Err(ProcessSwitchError::NoRunningChild)
        );
        assert_eq!(
            ProcessSlotLifecycle {
                state: PROCESS_STATE_RUNNING,
                parent_slot: NO_PARENT_SLOT,
                blocked_child_slot: 1,
            }
            .block_on_child(2),
            Err(ProcessSwitchError::NoRunningChild)
        );
    }

    #[test]
    fn process_lifecycle_resume_validates_blocked_child_link() {
        let blocked = ProcessSlotLifecycle::running_root()
            .block_on_child(1)
            .expect("parent blocks on child");

        assert_eq!(
            blocked.resume_after_child(1),
            Ok(ProcessSlotLifecycle::running_root())
        );
        assert_eq!(
            blocked.resume_after_child(2),
            Err(ProcessSwitchError::NoRunningChild)
        );
        assert_eq!(
            ProcessSlotLifecycle::running_root().resume_after_child(1),
            Err(ProcessSwitchError::NoRunningChild)
        );
    }

    #[test]
    fn process_ids_are_numeric_pids_not_slots() {
        assert_eq!(ProcessId::INIT.raw(), 1);
        assert_eq!(ProcessId::from_raw(2).raw(), 2);
    }

    #[test]
    fn process_table_initializes_child_heap_from_load_end_and_stack_guard() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0x0000_8000,
            stack_top: 0x0001_0000,
        });
        let child_plan = DynamicUserLoadPlan {
            load_base: 0x0000_a000,
            load_end: 0x0000_a022,
            entry_pc: 0x0000_a004,
            stack_top: 0x0001_0000,
            payload_dst: 0x0000_a000,
            payload_len: 16,
            zero_fill_addr: 0x0000_a010,
            zero_fill_len: 16,
        };

        table.begin_child_run(child_plan).expect("child starts");

        assert_eq!(table.program_break(), Ok(0x0000_a024));
        assert_eq!(table.heap_limit(), Ok(0x0000_d000));
    }

    #[test]
    fn process_table_places_child_argv_before_heap_start() {
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
        let argv = ChildArgv {
            argc: 1,
            table_ptr: 0x0000_a024,
            end: 0x0000_a038,
        };

        let child = table
            .begin_child_run_with_argv(child_plan, argv)
            .expect("child starts with argv");

        assert_eq!(child.frame.registers[1], 1);
        assert_eq!(child.frame.registers[2], 0x0000_a024);
        assert_eq!(table.program_break(), Ok(0x0000_a038));
    }

    #[test]
    fn process_descriptor_builds_translated_child_context_frame_and_resources() {
        let child_plan = DynamicUserLoadPlan {
            load_base: 0x0000_a000,
            load_end: 0x0000_a022,
            entry_pc: 0x0000_a004,
            stack_top: 0x0001_0000,
            payload_dst: 0x0000_a000,
            payload_len: 16,
            zero_fill_addr: 0x0000_a010,
            zero_fill_len: 16,
        };
        let argv = ChildArgv {
            argc: 2,
            table_ptr: 0x0000_a024,
            end: 0x0000_a038,
        };
        let translated = TranslatedUserLaunch {
            address_space: 7,
            entry_pc: 0x0000_a004,
            stack_top: 0x0001_0000,
            kernel_stack_top: 0x0001_1000,
            backing_pages: Some(crate::page_alloc::FrameRange {
                start: 0x0002_0000,
                frame_count: 3,
            }),
        };

        let descriptor = ProcessDescriptor::for_child_plan(child_plan, argv, Some(translated))
            .expect("descriptor builds");

        assert_eq!(
            descriptor.context,
            ProcessContext {
                entry_pc: 0x0000_a004,
                stack_top: 0x0001_0000,
            }
        );
        assert_eq!(descriptor.frame.resume_pc, 0x0000_a004);
        assert_eq!(descriptor.frame.stack_pointer, 0x0001_0000);
        assert_eq!(descriptor.frame.registers[1], 2);
        assert_eq!(descriptor.frame.registers[2], 0x0000_a024);
        assert_eq!(
            descriptor.resources,
            ProcessResources {
                memory: ProcessMemory {
                    start: 0x0000_a000,
                    end: 0x0001_0000,
                },
                load_base: 0x0000_a022,
                heap: RuntimeHeapState {
                    start: 0x0000_a038,
                    program_break: 0x0000_a038,
                    limit: 0x0000_d000,
                },
                address_space: Some(7),
                kernel_stack_top: Some(0x0001_1000),
                backing_pages: Some(crate::page_alloc::FrameRange {
                    start: 0x0002_0000,
                    frame_count: 3,
                }),
                heap_backing_pages: None,
            }
        );
    }

    #[test]
    fn runtime_foreground_slot_state_is_created_from_process_resources() {
        let resources = ProcessResources {
            memory: ProcessMemory {
                start: 0x0000_a000,
                end: 0x0001_0000,
            },
            load_base: 0x0000_a020,
            heap: RuntimeHeapState {
                start: 0x0000_a020,
                program_break: 0x0000_a040,
                limit: 0x0000_d000,
            },
            address_space: Some(11),
            kernel_stack_top: Some(0x0001_1000),
            backing_pages: Some(crate::page_alloc::FrameRange {
                start: 0x0002_0000,
                frame_count: 3,
            }),
            heap_backing_pages: Some(crate::page_alloc::FrameRange {
                start: 0x0002_3000,
                frame_count: 1,
            }),
        };

        assert_eq!(
            RuntimeForegroundSlotState::from_process_resources(
                ProcessId::from_raw(2),
                Some(ProcessId::INIT),
                1,
                resources,
            ),
            RuntimeForegroundSlotState {
                pid: ProcessId::from_raw(2),
                parent_pid: Some(ProcessId::INIT),
                parent_slot: 1,
                blocked_child_slot: NO_CHILD_SLOT,
                memory: Some(resources.memory),
                heap: Some(resources.heap),
                address_space: Some(11),
                kernel_stack_top: Some(0x0001_1000),
                backing_pages: Some(crate::page_alloc::FrameRange {
                    start: 0x0002_0000,
                    frame_count: 3,
                }),
                heap_backing_pages: Some(crate::page_alloc::FrameRange {
                    start: 0x0002_3000,
                    frame_count: 1,
                }),
            }
        );
    }

    #[test]
    fn runtime_process_linkage_blocks_and_resumes_specific_child() {
        let root = RuntimeProcessLinkage::root();
        let blocked = root.block_on_child(1).expect("root blocks on child");

        assert_eq!(
            blocked,
            RuntimeProcessLinkage {
                parent_slot: NO_PARENT_SLOT,
                blocked_child_slot: 1,
            }
        );
        assert_eq!(blocked.resume_after_child(1), Ok(root));
        assert_eq!(
            blocked.resume_after_child(2),
            Err(ProcessSwitchError::NoRunningChild)
        );
    }

    #[test]
    fn runtime_process_linkage_validates_running_child_parent_slot() {
        assert_eq!(
            RuntimeProcessLinkage {
                parent_slot: INIT_PROCESS_SLOT as u32,
                blocked_child_slot: NO_CHILD_SLOT,
            }
            .running_parent_slot()
            .expect("child reports parent"),
            INIT_PROCESS_SLOT
        );
        assert_eq!(
            RuntimeProcessLinkage::root().running_parent_slot(),
            Err(ProcessSwitchError::NoRunningChild)
        );
        assert_eq!(
            RuntimeProcessLinkage {
                parent_slot: INIT_PROCESS_SLOT as u32,
                blocked_child_slot: 2,
            }
            .running_parent_slot(),
            Err(ProcessSwitchError::NoRunningChild)
        );
    }

    #[test]
    fn process_resources_reports_owned_resources_for_cleanup() {
        let resources = ProcessResources {
            memory: ProcessMemory {
                start: 0x0000_a000,
                end: 0x0001_0000,
            },
            load_base: 0x0000_a020,
            heap: RuntimeHeapState {
                start: 0x0000_a020,
                program_break: 0x0000_a040,
                limit: 0x0000_d000,
            },
            address_space: Some(11),
            kernel_stack_top: Some(0x0001_1000),
            backing_pages: Some(crate::page_alloc::FrameRange {
                start: 0x0002_0000,
                frame_count: 3,
            }),
            heap_backing_pages: Some(crate::page_alloc::FrameRange {
                start: 0x0002_3000,
                frame_count: 1,
            }),
        };

        assert_eq!(
            resources.owned_resources(),
            ProcessOwnedResources {
                address_space: Some(11),
                backing_pages: Some(crate::page_alloc::FrameRange {
                    start: 0x0002_0000,
                    frame_count: 3,
                }),
                heap_pages: Some(crate::page_alloc::FrameRange {
                    start: 0x0002_3000,
                    frame_count: 1,
                }),
            }
        );
    }

    #[test]
    fn mapped_child_argv_layout_keeps_child_addresses_virtual() {
        let child_plan = DynamicUserLoadPlan {
            load_base: 0x0001_5020,
            load_end: 0x0001_6020,
            entry_pc: 0x0001_5024,
            stack_top: 0x0001_c000,
            payload_dst: 0x0001_5020,
            payload_len: 16,
            zero_fill_addr: 0x0001_5030,
            zero_fill_len: 16,
        };
        let mapped = MappedDynamicUserLoadPlan::new(child_plan, 0x0001_5000, 0x0000_9000, 7)
            .expect("mapped load plan initializes");
        let args = &[b"alpha".as_slice(), b"beta".as_slice()];

        let layout = ChildArgvLayout::new(child_plan, args).expect("argv layout initializes");

        assert_eq!(
            layout.child_argv(),
            ChildArgv {
                argc: 2,
                table_ptr: 0x0001_6020,
                end: 0x0001_603c,
            }
        );
        assert_eq!(mapped.translate_address(layout.table_ptr), Ok(0x0000_a020));
        assert_eq!(
            mapped.translate_address(layout.arg_data_ptr),
            Ok(0x0000_a030)
        );
    }

    #[test]
    fn run_argv_request_parses_multiple_arguments() {
        let bytes = [
            b'R', b'A', b'R', b'G', 11, 0, 0, 0, 2, 0, 0, 0, 9, 0, 0, 0, 2, 0, 0, 0, b'/', b'b',
            b'i', b'n', b'/', b'c', b'a', b't', b'.', b'k', b'x', b'/', b'e', b't', b'c', b'/',
            b'm', b'o', b't', b'd', b'-', b'n',
        ];

        let request = RunArgvRequest::parse(&bytes).unwrap();

        assert_eq!(request.path, b"/bin/cat.kx");
        assert_eq!(request.args(), &[b"/etc/motd".as_slice(), b"-n".as_slice()]);
    }

    #[test]
    fn spawn_argv_request_uses_spawn_magic_without_accepting_run_magic() {
        let bytes = [
            b'S', b'P', b'A', b'W', 12, 0, 0, 0, 1, 0, 0, 0, 3, 0, 0, 0, b'/', b'b', b'i', b'n',
            b'/', b'e', b'c', b'h', b'o', b'.', b'k', b'x', b'h', b'i', b'!',
        ];

        let request =
            RunArgvRequest::parse_with_magic(&bytes, k16_abi::syscall::SPAWN_ARGV_MAGIC).unwrap();

        assert_eq!(request.path, b"/bin/echo.kx");
        assert_eq!(request.args(), &[b"hi!".as_slice()]);
        assert_eq!(
            RunArgvRequest::parse(&bytes),
            Err(ProcessLoadError::InvalidPath)
        );
    }

    #[test]
    fn run_argv_request_rejects_trailing_bytes_after_declared_arguments() {
        let bytes = [
            b'R', b'A', b'R', b'G', 11, 0, 0, 0, 1, 0, 0, 0, 9, 0, 0, 0, b'/', b'b', b'i', b'n',
            b'/', b'c', b'a', b't', b'.', b'k', b'x', b'/', b'e', b't', b'c', b'/', b'm', b'o',
            b't', b'd', b'!',
        ];

        assert_eq!(
            RunArgvRequest::parse(&bytes),
            Err(ProcessLoadError::InvalidPath)
        );
    }

    #[test]
    fn process_table_initializes_init_heap_from_loaded_image() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0,
            stack_top: 0,
        });

        table
            .initialize_init_image(k16_boot_chain::LoadedImage {
                load_addr: 0x0000_8000,
                load_end: 0x0000_9022,
                entry_pc: 0x0000_8004,
            })
            .expect("init image initializes");

        assert_eq!(table.program_break(), Ok(0x0000_9024));
        assert_eq!(table.heap_limit(), Ok(0x0002_2000));
        assert_eq!(table.grow_program_break(0x20), Ok(0x0000_9024));
        assert_eq!(table.program_break(), Ok(0x0000_9044));
    }

    #[test]
    fn process_table_initializes_init_heap_from_explicit_memory_range() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0,
            stack_top: 0,
        });

        table
            .initialize_init_image_in_memory(
                k16_boot_chain::LoadedImage {
                    load_addr: 0x0001_3000,
                    load_end: 0x0001_4022,
                    entry_pc: 0x0001_3004,
                },
                0x0001_9000,
            )
            .expect("init image initializes inside explicit memory range");

        assert_eq!(
            table.current_memory(),
            Ok(ProcessMemory {
                start: 0x0001_3000,
                end: 0x0001_9000,
            })
        );
        assert_eq!(table.program_break(), Ok(0x0001_4024));
        assert_eq!(table.heap_limit(), Ok(0x0001_6000));
        assert_eq!(
            table.slots[INIT_PROCESS_SLOT].context.stack_top,
            0x0001_9000
        );
    }

    #[test]
    fn process_descriptor_builds_loaded_init_context_frame_and_resources() {
        let image = k16_boot_chain::LoadedImage {
            load_addr: 0x0001_3000,
            load_end: 0x0001_4022,
            entry_pc: 0x0001_3004,
        };

        let descriptor = ProcessDescriptor::for_loaded_init_image(image, 0x0001_9000)
            .expect("descriptor builds");

        assert_eq!(
            descriptor.context,
            ProcessContext {
                entry_pc: 0x0001_3004,
                stack_top: 0x0001_9000,
            }
        );
        assert_eq!(descriptor.frame.resume_pc, 0x0001_3004);
        assert_eq!(descriptor.frame.stack_pointer, 0x0001_9000);
        assert_eq!(
            descriptor.resources,
            ProcessResources {
                memory: ProcessMemory {
                    start: 0x0001_3000,
                    end: 0x0001_9000,
                },
                load_base: 0x0001_4022,
                heap: RuntimeHeapState {
                    start: 0x0001_4024,
                    program_break: 0x0001_4024,
                    limit: 0x0001_6000,
                },
                address_space: None,
                kernel_stack_top: None,
                backing_pages: None,
                heap_backing_pages: None,
            }
        );
    }

    #[test]
    fn process_descriptor_builds_translated_init_context_frame_and_resources() {
        let plan = DynamicUserLoadPlan {
            load_base: 0x0001_5000,
            load_end: 0x0001_6022,
            entry_pc: 0x0001_5004,
            stack_top: 0x0002_f000,
            payload_dst: 0x0001_5000,
            payload_len: 16,
            zero_fill_addr: 0x0001_5010,
            zero_fill_len: 16,
        };
        let translated = TranslatedUserLaunch {
            address_space: 9,
            entry_pc: 0x0001_5004,
            stack_top: 0x0002_f000,
            kernel_stack_top: 0x0003_0000,
            backing_pages: Some(crate::page_alloc::FrameRange {
                start: 0x0000_9000,
                frame_count: 3,
            }),
        };

        let descriptor = ProcessDescriptor::for_translated_init_plan(
            plan,
            translated.kernel_stack_top,
            translated,
        )
        .expect("descriptor builds");

        assert_eq!(
            descriptor.context,
            ProcessContext {
                entry_pc: 0x0001_5004,
                stack_top: 0x0002_f000,
            }
        );
        assert_eq!(descriptor.frame.resume_pc, 0x0001_5004);
        assert_eq!(descriptor.frame.stack_pointer, 0x0002_f000);
        assert_eq!(
            descriptor.resources,
            ProcessResources {
                memory: ProcessMemory {
                    start: 0x0001_5000,
                    end: 0x0002_f000,
                },
                load_base: 0x0001_6022,
                heap: RuntimeHeapState {
                    start: 0x0001_6024,
                    program_break: 0x0001_6024,
                    limit: 0x0002_c000,
                },
                address_space: Some(9),
                kernel_stack_top: Some(0x0003_0000),
                backing_pages: Some(crate::page_alloc::FrameRange {
                    start: 0x0000_9000,
                    frame_count: 3,
                }),
                heap_backing_pages: None,
            }
        );
    }

    #[test]
    fn process_table_buffer_validation_uses_current_process_memory() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0,
            stack_top: 0,
        });
        table
            .initialize_init_image_in_memory(
                k16_boot_chain::LoadedImage {
                    load_addr: 0x0001_3000,
                    load_end: 0x0001_4020,
                    entry_pc: 0x0001_3004,
                },
                0x0001_9000,
            )
            .expect("init image initializes");

        assert!(table.current_contains_buffer(0x0001_3000, 4));
        assert!(table.current_contains_buffer(0x0001_8ffc, 4));
        assert!(!table.current_contains_buffer(0x0001_2ffc, 4));
        assert!(!table.current_contains_buffer(0x0001_8ffe, 4));
        assert!(!table.current_contains_buffer(0xffff_fffc, 8));
    }

    #[test]
    fn process_table_child_arena_starts_after_current_init_break() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0,
            stack_top: 0,
        });
        table
            .initialize_init_image(k16_boot_chain::LoadedImage {
                load_addr: 0x0000_8000,
                load_end: 0x0000_9000,
                entry_pc: 0x0000_8004,
            })
            .expect("init image initializes");
        table
            .grow_program_break(0x120)
            .expect("init heap grows before child launch");

        let arena = table
            .child_arena_for_init_frame(TrapFrame {
                stack_pointer: 0x0001_f000,
                ..TrapFrame::zeroed()
            })
            .expect("child arena is available");

        assert_eq!(arena.start, 0x0000_9120);
        assert_eq!(arena.end, 0x0001_f000);
    }

    #[test]
    fn process_table_child_arena_is_clamped_to_current_memory_end() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0,
            stack_top: 0,
        });
        table
            .initialize_init_image_in_memory(
                k16_boot_chain::LoadedImage {
                    load_addr: 0x0001_3000,
                    load_end: 0x0001_4000,
                    entry_pc: 0x0001_3004,
                },
                0x0001_9000,
            )
            .expect("init image initializes");

        let arena = table
            .child_arena_for_init_frame(TrapFrame {
                stack_pointer: 0x0002_0000,
                ..TrapFrame::zeroed()
            })
            .expect("arena is clamped to init memory");

        assert_eq!(arena.start, 0x0001_4000);
        assert_eq!(arena.end, 0x0001_9000);
    }

    #[test]
    fn process_table_switches_current_memory_to_child_range() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0,
            stack_top: 0,
        });
        table
            .initialize_init_image_in_memory(
                k16_boot_chain::LoadedImage {
                    load_addr: 0x0001_3000,
                    load_end: 0x0001_4000,
                    entry_pc: 0x0001_3004,
                },
                0x0002_0000,
            )
            .expect("init image initializes");
        let child_plan = DynamicUserLoadPlan {
            load_base: 0x0001_5000,
            load_end: 0x0001_6020,
            entry_pc: 0x0001_5004,
            stack_top: 0x0001_c000,
            payload_dst: 0x0001_5000,
            payload_len: 16,
            zero_fill_addr: 0x0001_5010,
            zero_fill_len: 16,
        };

        table
            .begin_child_run(child_plan)
            .expect("child starts with its own range");

        assert_eq!(
            table.current_memory(),
            Ok(ProcessMemory {
                start: 0x0001_5000,
                end: 0x0001_c000,
            })
        );
        assert!(table.current_contains_buffer(0x0001_5000, 4));
        assert!(!table.current_contains_buffer(0x0001_4000, 4));
        assert!(!table.current_contains_buffer(0x0001_c000, 4));
    }

    #[test]
    fn process_table_records_translated_child_address_space_and_virtual_context() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0,
            stack_top: 0,
        });
        table
            .initialize_init_image_in_memory(
                k16_boot_chain::LoadedImage {
                    load_addr: 0x0001_3000,
                    load_end: 0x0001_4000,
                    entry_pc: 0x0001_3004,
                },
                0x0002_0000,
            )
            .expect("init image initializes");
        let child_plan = DynamicUserLoadPlan {
            load_base: 0x0001_5000,
            load_end: 0x0001_6020,
            entry_pc: 0x0001_5004,
            stack_top: 0x0001_c000,
            payload_dst: 0x0001_5000,
            payload_len: 16,
            zero_fill_addr: 0x0001_5010,
            zero_fill_len: 16,
        };
        let translated = TranslatedUserLaunch {
            address_space: 7,
            entry_pc: 0x0001_0004,
            stack_top: 0x0001_7000,
            kernel_stack_top: 0x0001_8000,
            backing_pages: None,
        };

        let child = table
            .begin_translated_child_run(child_plan, translated)
            .expect("translated child starts");

        assert_eq!(child.context.entry_pc, translated.entry_pc);
        assert_eq!(child.context.stack_top, translated.stack_top);
        assert_eq!(child.kernel_stack_top, Some(translated.kernel_stack_top));
        assert_eq!(table.current_address_space(), Some(7));
        assert_eq!(
            table.current_memory(),
            Ok(ProcessMemory {
                start: 0x0001_5000,
                end: 0x0001_c000,
            })
        );
    }

    #[test]
    fn process_table_preserves_argv_for_translated_child() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0,
            stack_top: 0,
        });
        let child_plan = DynamicUserLoadPlan {
            load_base: 0x0001_5000,
            load_end: 0x0001_6020,
            entry_pc: 0x0001_5004,
            stack_top: 0x0001_c000,
            payload_dst: 0x0001_5000,
            payload_len: 16,
            zero_fill_addr: 0x0001_5010,
            zero_fill_len: 16,
        };
        let argv = ChildArgv {
            argc: 2,
            table_ptr: 0x0001_6040,
            end: 0x0001_6080,
        };
        let translated = TranslatedUserLaunch {
            address_space: 9,
            entry_pc: 0x0001_0004,
            stack_top: 0x0001_7000,
            kernel_stack_top: 0x0001_8000,
            backing_pages: None,
        };

        let child = table
            .begin_translated_child_run_with_argv(child_plan, argv, translated)
            .expect("translated argv child starts");

        assert_eq!(child.frame.registers[1], 2);
        assert_eq!(child.frame.registers[2], 0x0001_6040);
        assert_eq!(child.address_space, Some(9));
        assert_eq!(child.kernel_stack_top, Some(0x0001_8000));
    }

    #[test]
    fn translated_child_kernel_stack_reserves_physical_page_below_parent_stack() {
        assert_eq!(
            translated_child_kernel_stack_top(0x0002_0000),
            Ok(0x0002_0000)
        );
        assert_eq!(
            translated_child_kernel_stack_top(0x0002_0003),
            Ok(0x0002_0000)
        );
    }

    #[test]
    fn translated_child_user_arena_reuses_parent_virtual_bounds() {
        assert_eq!(
            translated_child_user_arena(ProcessMemory {
                start: 0x0000_4000,
                end: 0x0002_f000,
            }),
            Ok(UserArena {
                start: 0x0000_4000,
                end: 0x0002_f000,
            })
        );
    }

    #[test]
    fn translated_init_arena_reserves_top_page_for_kernel_trap_stack() {
        assert_eq!(
            translated_init_arena_end(0x0003_0000),
            Ok((0x0002_f000, 0x0003_0000))
        );
        assert_eq!(
            translated_init_user_arena(0x0000_1000, 0x0003_0000, 0),
            Ok((
                UserArena {
                    start: 0x0000_1000,
                    end: 0x0002_f000,
                },
                0x0003_0000,
            ))
        );
        assert_eq!(
            translated_init_user_arena(0x0000_0100, 0x0003_0000, 0),
            Ok((
                UserArena {
                    start: 0x0000_1000,
                    end: 0x0002_f000,
                },
                0x0003_0000,
            ))
        );
        assert_eq!(
            translated_init_user_arena(0x0000_0100, 0x0003_0000, 0x0000_352d),
            Ok((
                UserArena {
                    start: 0x0000_4000,
                    end: 0x0002_f000,
                },
                0x0003_0000,
            ))
        );
    }

    #[test]
    fn translated_init_kernel_reserved_ranges_match_initial_arena_bounds() {
        let (arena, kernel_stack_top) =
            translated_init_user_arena(0x0000_0100, 0x0003_0000, 0x0000_8450)
                .expect("arena initializes");
        let mut allocator = crate::page_alloc::PageFrameAllocator::new_for_kernel(
            translated_init_kernel_reserved_ranges(
                0x0000_0100,
                0x0003_0000,
                0x0000_8450,
                kernel_stack_top,
            ),
        )
        .expect("allocator initializes");

        assert_eq!(
            allocator
                .allocate_contiguous(1)
                .expect("first frame allocates"),
            crate::page_alloc::FrameRange {
                start: arena.start,
                frame_count: 1,
            }
        );
    }

    #[test]
    fn runtime_launch_policy_translates_shell_and_nested_utility_children() {
        assert!(should_translate_runtime_child_path(b"/bin/shell.kx"));
        assert!(should_translate_runtime_child_path(b"/bin/cat.kx"));
        assert!(runtime_child_slot_for_parent(0, None).is_ok());
        assert!(runtime_child_slot_for_parent(1, Some(7)).is_ok());
        assert_eq!(
            runtime_child_slot_for_parent(2, Some(9)),
            Err(ProcessSwitchError::ChildAlreadyRunning)
        );
    }

    #[test]
    fn runtime_slot_policy_reuses_first_empty_foreground_slot() {
        assert_eq!(child_slot_from_occupancy(0, [false, true]), Ok(1));
        assert_eq!(child_slot_from_occupancy(1, [true, false]), Ok(2));
    }

    #[test]
    fn runtime_slot_policy_reports_busy_when_no_foreground_slot_is_free() {
        assert_eq!(
            child_slot_from_occupancy(1, [true, true]),
            Err(ProcessSwitchError::ChildAlreadyRunning)
        );
        assert_eq!(
            child_slot_from_occupancy(MAX_PROCESS_SLOTS, [false, false]),
            Err(ProcessSwitchError::ChildAlreadyRunning)
        );
    }

    #[test]
    fn translated_parent_resume_requires_trap_return_address_space_override() {
        let resume = ParentResume {
            id: ProcessId::from_raw(2),
            child_id: ProcessId::from_raw(NO_PROCESS_PID),
            context: ProcessContext {
                entry_pc: 0x0001_5004,
                stack_top: 0x0001_f000,
            },
            frame: TrapFrame {
                resume_pc: 0x0001_5004,
                stack_pointer: 0x0001_f000,
                interrupt_enable: 1,
                ..TrapFrame::zeroed()
            },
            child_exit_status: 0,
            wait_status_ptr: 0,
            address_space: Some(7),
            kernel_stack_top: Some(0x0001_d000),
            exited_address_space: None,
            exited_backing_pages: None,
            exited_heap_pages: None,
        };

        assert_eq!(
            trap_return_override_for_resume(&resume),
            Ok(TrapReturnOverride::Translated {
                address_space: 7,
                kernel_stack_top: 0x0001_d000,
            })
        );

        assert_eq!(
            trap_return_override_for_resume(&ParentResume {
                kernel_stack_top: None,
                ..resume
            }),
            Err(ProcessSwitchError::NoRunningChild)
        );
    }

    #[test]
    fn translated_user_launch_maps_identity_child_pages_without_owned_backing() {
        crate::mmio::reset_test_state();
        crate::mmio::set_test_mmu0_result(7, k16_abi::computer::mmu0::STATUS_DONE, 0);
        let child_plan = DynamicUserLoadPlan {
            load_base: 0x0001_5020,
            load_end: 0x0001_6020,
            entry_pc: 0x0001_5024,
            stack_top: 0x0001_c000,
            payload_dst: 0x0001_5020,
            payload_len: 16,
            zero_fill_addr: 0x0001_5030,
            zero_fill_len: 16,
        };

        let translated = unsafe { create_translated_user_launch(child_plan, 0x0001_d000) }
            .expect("translated launch maps");

        assert_eq!(
            translated,
            TranslatedUserLaunch {
                address_space: 7,
                entry_pc: 0x0001_5024,
                stack_top: 0x0001_c000,
                kernel_stack_top: 0x0001_d000,
                backing_pages: None,
            }
        );
        let writes = crate::mmio::take_test_writes();
        let writes = writes.as_slice();
        assert_eq!(writes.len(), 7);
        assert_eq!(
            writes[0],
            (
                k16_abi::computer::mmu0::COMMAND,
                k16_abi::computer::mmu0::COMMAND_CREATE_ADDRESS_SPACE as u32,
            )
        );
        assert_eq!(writes[1], (k16_abi::computer::mmu0::ADDRESS_SPACE, 7));
        assert_eq!(
            writes[2],
            (k16_abi::computer::mmu0::VIRTUAL_START, 0x0001_5000)
        );
        assert_eq!(
            writes[3],
            (k16_abi::computer::mmu0::PHYSICAL_START, 0x0001_5000)
        );
        assert_eq!(writes[4], (k16_abi::computer::mmu0::PAGE_COUNT, 7));
        assert_eq!(
            writes[5],
            (
                k16_abi::computer::mmu0::FLAGS,
                (k16_abi::computer::mmu0::FLAG_USER_ACCESSIBLE
                    | k16_abi::computer::mmu0::FLAG_WRITABLE
                    | k16_abi::computer::mmu0::FLAG_EXECUTABLE) as u32,
            )
        );
        assert_eq!(
            writes[6],
            (
                k16_abi::computer::mmu0::COMMAND,
                k16_abi::computer::mmu0::COMMAND_MAP_PAGES as u32,
            )
        );
    }

    #[test]
    fn translated_user_launch_allocates_child_backing_pages() {
        crate::mmio::reset_test_state();
        crate::mmio::set_test_mmu0_result(7, k16_abi::computer::mmu0::STATUS_DONE, 0);
        let mut allocator =
            crate::page_alloc::PageFrameAllocator::new(0x0003_0000).expect("allocator initializes");
        allocator
            .reserve_range(0, 0x0000_9000)
            .expect("kernel frames reserve");
        let child_plan = DynamicUserLoadPlan {
            load_base: 0x0001_5020,
            load_end: 0x0001_6020,
            entry_pc: 0x0001_5024,
            stack_top: 0x0001_c000,
            payload_dst: 0x0001_5020,
            payload_len: 16,
            zero_fill_addr: 0x0001_5030,
            zero_fill_len: 16,
        };

        let translated = unsafe {
            create_translated_user_launch_with_allocator(child_plan, 0x0001_d000, &mut allocator)
        }
        .expect("translated launch maps");

        assert_eq!(
            translated,
            TranslatedUserLaunch {
                address_space: 7,
                entry_pc: 0x0001_5024,
                stack_top: 0x0001_c000,
                kernel_stack_top: 0x0001_d000,
                backing_pages: Some(crate::page_alloc::FrameRange {
                    start: 0x0000_9000,
                    frame_count: 4,
                }),
            }
        );
        let writes = crate::mmio::take_test_writes();
        let writes = writes.as_slice();
        assert_eq!(
            writes[2],
            (k16_abi::computer::mmu0::VIRTUAL_START, 0x0001_5000)
        );
        assert_eq!(
            writes[3],
            (k16_abi::computer::mmu0::PHYSICAL_START, 0x0000_9000)
        );
        assert_eq!(writes[4], (k16_abi::computer::mmu0::PAGE_COUNT, 2));
        assert_eq!(
            allocator
                .allocate_contiguous(1)
                .expect("next frame allocates"),
            crate::page_alloc::FrameRange {
                start: 0x0000_d000,
                frame_count: 1,
            }
        );
    }

    #[test]
    fn translated_user_launch_maps_preloaded_backing_pages() {
        crate::mmio::reset_test_state();
        crate::mmio::set_test_mmu0_result(7, k16_abi::computer::mmu0::STATUS_DONE, 0);
        let child_plan = DynamicUserLoadPlan {
            load_base: 0x0001_5020,
            load_end: 0x0001_6020,
            entry_pc: 0x0001_5024,
            stack_top: 0x0001_c000,
            payload_dst: 0x0001_5020,
            payload_len: 16,
            zero_fill_addr: 0x0001_5030,
            zero_fill_len: 16,
        };
        let mapped = MappedDynamicUserLoadPlan::new(child_plan, 0x0001_5000, 0x0000_9000, 7)
            .expect("mapped load plan initializes");

        let translated = unsafe { create_translated_user_launch_from_mapped(mapped, 0x0001_d000) }
            .expect("translated launch maps");

        assert_eq!(
            translated,
            TranslatedUserLaunch {
                address_space: 7,
                entry_pc: 0x0001_5024,
                stack_top: 0x0001_c000,
                kernel_stack_top: 0x0001_d000,
                backing_pages: Some(crate::page_alloc::FrameRange {
                    start: 0x0000_9000,
                    frame_count: 7,
                }),
            }
        );
        let writes = crate::mmio::take_test_writes();
        let writes = writes.as_slice();
        assert_eq!(
            writes[2],
            (k16_abi::computer::mmu0::VIRTUAL_START, 0x0001_5000)
        );
        assert_eq!(
            writes[3],
            (k16_abi::computer::mmu0::PHYSICAL_START, 0x0000_9000)
        );
        assert_eq!(writes[4], (k16_abi::computer::mmu0::PAGE_COUNT, 7));
    }

    #[test]
    fn translated_user_launch_destroys_address_space_when_mapping_fails() {
        crate::mmio::reset_test_state();
        crate::mmio::set_test_mmu0_status_script(
            7,
            &[
                k16_abi::computer::mmu0::STATUS_DONE,
                k16_abi::computer::mmu0::STATUS_ERROR,
                k16_abi::computer::mmu0::STATUS_DONE,
            ],
        );
        let child_plan = DynamicUserLoadPlan {
            load_base: 0x0001_5020,
            load_end: 0x0001_6020,
            entry_pc: 0x0001_5024,
            stack_top: 0x0001_c000,
            payload_dst: 0x0001_5020,
            payload_len: 16,
            zero_fill_addr: 0x0001_5030,
            zero_fill_len: 16,
        };

        let error = unsafe { create_translated_user_launch(child_plan, 0x0001_d000) }
            .expect_err("map failure rejects translated launch");

        assert_eq!(error, ProcessLoadError::Storage);
        let writes = crate::mmio::take_test_writes();
        let writes = writes.as_slice();
        assert_eq!(
            writes[writes.len() - 2],
            (k16_abi::computer::mmu0::ADDRESS_SPACE, 7)
        );
        assert_eq!(
            writes[writes.len() - 1],
            (
                k16_abi::computer::mmu0::COMMAND,
                k16_abi::computer::mmu0::COMMAND_DESTROY_ADDRESS_SPACE as u32,
            )
        );
    }

    #[test]
    fn destroy_exited_address_space_submits_destroy_for_translated_child() {
        crate::mmio::reset_test_state();
        crate::mmio::set_test_mmu0_result(0, k16_abi::computer::mmu0::STATUS_DONE, 0);
        let resume = ParentResume {
            id: ProcessId::INIT,
            child_id: ProcessId::from_raw(NO_PROCESS_PID),
            context: ProcessContext {
                entry_pc: 0,
                stack_top: 0,
            },
            frame: TrapFrame::zeroed(),
            child_exit_status: 0,
            wait_status_ptr: 0,
            address_space: None,
            kernel_stack_top: None,
            exited_address_space: Some(11),
            exited_backing_pages: None,
            exited_heap_pages: None,
        };

        unsafe { destroy_exited_address_space(&resume) }.expect("destroy succeeds");

        let writes = crate::mmio::take_test_writes();
        assert_eq!(
            writes.as_slice(),
            &[
                (k16_abi::computer::mmu0::ADDRESS_SPACE, 11),
                (
                    k16_abi::computer::mmu0::COMMAND,
                    k16_abi::computer::mmu0::COMMAND_DESTROY_ADDRESS_SPACE as u32,
                ),
            ]
        );
    }

    #[test]
    fn translated_user_launch_activation_submits_entry_and_stack() {
        crate::mmio::reset_test_state();
        crate::mmio::set_test_mmu0_result(0, k16_abi::computer::mmu0::STATUS_DONE, 0);
        let translated = TranslatedUserLaunch {
            address_space: 7,
            entry_pc: 0x0001_5024,
            stack_top: 0x0001_c000,
            kernel_stack_top: 0x0001_d000,
            backing_pages: None,
        };

        unsafe {
            mmu0_activate_user_address_space(
                translated.address_space,
                translated.entry_pc,
                translated.stack_top,
                translated.kernel_stack_top,
            )
        }
        .expect("activation command submits");

        let writes = crate::mmio::take_test_writes();
        let writes = writes.as_slice();
        assert_eq!(writes.len(), 5);
        assert_eq!(writes[0], (k16_abi::computer::mmu0::ADDRESS_SPACE, 7));
        assert_eq!(
            writes[1],
            (
                k16_abi::computer::mmu0::PHYSICAL_START,
                translated.kernel_stack_top
            )
        );
        assert_eq!(
            writes[2],
            (k16_abi::computer::mmu0::ENTRY_PC, translated.entry_pc)
        );
        assert_eq!(
            writes[3],
            (k16_abi::computer::mmu0::STACK_POINTER, translated.stack_top,)
        );
        assert_eq!(
            writes[4],
            (
                k16_abi::computer::mmu0::COMMAND,
                k16_abi::computer::mmu0::COMMAND_ACTIVATE_USER_ADDRESS_SPACE as u32,
            )
        );
    }

    #[test]
    fn process_table_brk_accepts_only_current_child_heap_range() {
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

        assert_eq!(table.set_program_break(0x0000_a040), Ok(0x0000_a040));
        assert_eq!(table.program_break(), Ok(0x0000_a040));
        assert_eq!(
            table.set_program_break(0x0000_a01c),
            Err(HeapError::OutOfMemory)
        );
        assert_eq!(
            table.set_program_break(0x0000_ff04),
            Err(HeapError::OutOfMemory)
        );
    }

    #[test]
    fn process_table_sbrk_returns_old_break_and_rejects_overflow() {
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

        assert_eq!(table.grow_program_break(0x20), Ok(0x0000_a020));
        assert_eq!(table.program_break(), Ok(0x0000_a040));
        assert_eq!(
            table.grow_program_break(0x6000),
            Err(HeapError::OutOfMemory)
        );
        assert_eq!(
            table.set_program_break(0xffff_fffc),
            Err(HeapError::OutOfMemory)
        );
    }

    #[test]
    fn process_table_heap_is_available_only_while_child_runs() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0x0000_8000,
            stack_top: 0x0001_0000,
        });

        assert_eq!(table.program_break(), Err(HeapError::NoRunningChild));

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
        table.finish_child(0).expect("init resumes");

        assert_eq!(table.program_break(), Err(HeapError::NoRunningChild));
    }

    #[test]
    fn process_table_allows_nested_foreground_child_run() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0x0000_8000,
            stack_top: 0x0001_0000,
        });
        let shell_plan = DynamicUserLoadPlan {
            load_base: 0x0000_a000,
            load_end: 0x0000_a020,
            entry_pc: 0x0000_a004,
            stack_top: 0x0001_0000,
            payload_dst: 0x0000_a000,
            payload_len: 16,
            zero_fill_addr: 0x0000_a010,
            zero_fill_len: 16,
        };
        let utility_plan = DynamicUserLoadPlan {
            load_base: 0x0000_b000,
            load_end: 0x0000_b020,
            entry_pc: 0x0000_b004,
            stack_top: 0x0000_f000,
            payload_dst: 0x0000_b000,
            payload_len: 16,
            zero_fill_addr: 0x0000_b010,
            zero_fill_len: 16,
        };
        let init_frame = TrapFrame {
            resume_pc: 0x0000_8100,
            stack_pointer: 0x0000_ff00,
            ..TrapFrame::zeroed()
        };
        let shell_frame = TrapFrame {
            resume_pc: 0x0000_a100,
            stack_pointer: 0x0000_ef00,
            ..TrapFrame::zeroed()
        };

        let shell = table
            .begin_child_run_from_frame(shell_plan, init_frame)
            .expect("shell starts");
        let utility = table
            .begin_child_run_from_frame(utility_plan, shell_frame)
            .expect("utility starts from shell");

        assert_ne!(utility.id, shell.id);

        let resumed_shell = table.finish_child(5).expect("shell resumes");
        assert_eq!(resumed_shell.id, shell.id);
        assert_eq!(resumed_shell.child_exit_status, 5);
        assert_eq!(resumed_shell.frame, shell_frame);

        let resumed_init = table.finish_child(7).expect("init resumes");
        assert_eq!(resumed_init.id, ProcessId::INIT);
        assert_eq!(resumed_init.child_exit_status, 7);
        assert_eq!(resumed_init.frame, init_frame);
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

        assert_eq!(resumed.id, ProcessId::INIT);
        assert_eq!(resumed.child_exit_status, 7);
        assert_eq!(table.init_state(), PROCESS_STATE_RUNNING);
        assert_eq!(table.child_state(), PROCESS_STATE_EMPTY);
    }

    #[test]
    fn process_table_child_start_records_blocked_child_link() {
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
            table.slots[INIT_PROCESS_SLOT].lifecycle(),
            ProcessSlotLifecycle {
                state: PROCESS_STATE_BLOCKED_ON_CHILD,
                parent_slot: NO_PARENT_SLOT,
                blocked_child_slot: 1,
            }
        );
    }

    #[test]
    fn process_table_child_finish_rejects_mismatched_blocked_child_link() {
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
        table.slots[INIT_PROCESS_SLOT].set_lifecycle(
            ProcessSlotLifecycle::running_root()
                .block_on_child(2)
                .expect("parent blocks on another child"),
        );

        assert_eq!(
            table.finish_child(0),
            Err(ProcessSwitchError::NoRunningChild)
        );
    }

    #[test]
    fn process_table_child_exit_reports_parent_address_space() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0,
            stack_top: 0,
        });
        table.set_current_address_space(Some(5));
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

        let resumed = table.finish_child(0).expect("parent resumes");

        assert_eq!(resumed.address_space, Some(5));
    }

    #[test]
    fn process_table_child_exit_reports_exited_address_space_for_cleanup() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0,
            stack_top: 0,
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
        let translated = TranslatedUserLaunch {
            address_space: 9,
            entry_pc: 0x0000_a004,
            stack_top: 0x0001_0000,
            kernel_stack_top: 0x0000_f000,
            backing_pages: None,
        };
        table
            .begin_translated_child_run(child_plan, translated)
            .expect("translated child starts");

        let resumed = table.finish_child(0).expect("parent resumes");

        assert_eq!(resumed.exited_address_space, Some(9));
        assert_eq!(resumed.address_space, None);
    }

    #[test]
    fn runtime_foreground_slot_state_reports_owned_resources() {
        assert_eq!(
            RuntimeHeapState::from_bounds(0x0001_2000, 0x0001_f000),
            Ok(RuntimeHeapState {
                start: 0x0001_2000,
                program_break: 0x0001_2000,
                limit: 0x0001_c000,
            })
        );
        let state = RuntimeForegroundSlotState {
            pid: ProcessId::from_raw(2),
            parent_pid: Some(ProcessId::INIT),
            parent_slot: 1,
            blocked_child_slot: NO_CHILD_SLOT,
            memory: ProcessMemory::new(0x0001_0000, 0x0001_f000).ok(),
            heap: Some(RuntimeHeapState {
                start: 0x0001_2000,
                program_break: 0x0001_3000,
                limit: 0x0001_e000,
            }),
            address_space: Some(7),
            kernel_stack_top: Some(0x0001_f000),
            backing_pages: Some(crate::page_alloc::FrameRange {
                start: 0x0000_9000,
                frame_count: 2,
            }),
            heap_backing_pages: Some(crate::page_alloc::FrameRange {
                start: 0x0000_b000,
                frame_count: 1,
            }),
        };

        assert_eq!(
            state.owned_resources(),
            ProcessOwnedResources {
                address_space: Some(7),
                backing_pages: Some(crate::page_alloc::FrameRange {
                    start: 0x0000_9000,
                    frame_count: 2,
                }),
                heap_pages: Some(crate::page_alloc::FrameRange {
                    start: 0x0000_b000,
                    frame_count: 1,
                }),
            }
        );
    }

    #[test]
    fn runtime_foreground_slot_state_clears_owned_state_after_exit() {
        let state = RuntimeForegroundSlotState {
            pid: ProcessId::from_raw(3),
            parent_pid: Some(ProcessId::from_raw(2)),
            parent_slot: 1,
            blocked_child_slot: 2,
            memory: ProcessMemory::new(0x0001_0000, 0x0001_f000).ok(),
            heap: Some(RuntimeHeapState {
                start: 0x0001_2000,
                program_break: 0x0001_3000,
                limit: 0x0001_e000,
            }),
            address_space: Some(7),
            kernel_stack_top: Some(0x0001_f000),
            backing_pages: Some(crate::page_alloc::FrameRange {
                start: 0x0000_9000,
                frame_count: 2,
            }),
            heap_backing_pages: Some(crate::page_alloc::FrameRange {
                start: 0x0000_b000,
                frame_count: 1,
            }),
        };

        assert_eq!(
            state.cleared_after_exit(),
            RuntimeForegroundSlotState {
                pid: ProcessId::from_raw(NO_PROCESS_PID),
                parent_pid: None,
                parent_slot: NO_PARENT_SLOT,
                blocked_child_slot: NO_CHILD_SLOT,
                memory: None,
                heap: None,
                address_space: None,
                kernel_stack_top: None,
                backing_pages: None,
                heap_backing_pages: None,
            }
        );
    }

    #[test]
    fn process_table_child_exit_reports_owned_resources_for_cleanup() {
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0,
            stack_top: 0,
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
        let backing_pages = crate::page_alloc::FrameRange {
            start: 0x0002_0000,
            frame_count: 6,
        };
        let translated = TranslatedUserLaunch {
            address_space: 9,
            entry_pc: 0x0000_a004,
            stack_top: 0x0001_0000,
            kernel_stack_top: 0x0000_f000,
            backing_pages: Some(backing_pages),
        };
        table
            .begin_translated_child_run(child_plan, translated)
            .expect("translated child starts");
        table.slots[1].heap_backing_pages = Some(crate::page_alloc::FrameRange {
            start: 0x0002_8000,
            frame_count: 1,
        });

        let resumed = table.finish_child(0).expect("parent resumes");

        assert_eq!(resumed.exited_backing_pages, Some(backing_pages));
        assert_eq!(
            resumed.exited_heap_pages,
            Some(crate::page_alloc::FrameRange {
                start: 0x0002_8000,
                frame_count: 1,
            })
        );
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
    fn process_table_records_translated_init_address_space_and_reserved_kernel_stack() {
        let plan = DynamicUserLoadPlan {
            load_base: 0x0001_0000,
            load_end: 0x0001_0a20,
            entry_pc: 0x0001_0004,
            stack_top: 0x0002_f000,
            payload_dst: 0x0001_0000,
            payload_len: 0x0a20,
            zero_fill_addr: 0x0001_0a20,
            zero_fill_len: 0,
        };
        let translated = TranslatedUserLaunch {
            address_space: 7,
            entry_pc: 0x0001_0004,
            stack_top: 0x0002_f000,
            kernel_stack_top: 0x0003_0000,
            backing_pages: None,
        };
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0,
            stack_top: 0,
        });

        let launch = table
            .initialize_translated_init_plan_in_memory(plan, 0x0003_0000, translated)
            .expect("translated init image records");

        assert_eq!(launch.id, ProcessId::INIT);
        assert_eq!(launch.address_space, Some(7));
        assert_eq!(launch.kernel_stack_top, Some(0x0003_0000));
        assert_eq!(launch.context.entry_pc, 0x0001_0004);
        assert_eq!(launch.context.stack_top, 0x0002_f000);
        assert_eq!(table.current_address_space(), Some(7));
        assert_eq!(
            table.current_memory(),
            Ok(ProcessMemory {
                start: 0x0001_0000,
                end: 0x0002_f000,
            })
        );
    }

    #[test]
    fn child_arena_rejects_program_that_would_overlap_live_init_stack() {
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
        init_frame.stack_pointer = 0x0001_0b00;

        let arena = table
            .child_arena_for_init_frame(init_frame)
            .expect("arena records live init stack boundary");
        assert_eq!(
            plan_dynamic_user_load(
                arena,
                DynamicUserImage {
                    entry_offset: 0,
                    file_size: 0x400,
                    memory_size: 0x400,
                },
            ),
            Err(ProcessLoadError::ProgramTooLarge)
        );
    }

    #[test]
    fn child_arena_uses_live_init_stack_as_child_stack_top() {
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
        init_frame.stack_pointer = 0x0001_4000;

        let arena = table
            .child_arena_for_init_frame(init_frame)
            .expect("arena uses live init stack boundary");
        let plan = plan_dynamic_user_load(
            arena,
            DynamicUserImage {
                entry_offset: 0,
                file_size: 0x400,
                memory_size: 0x400,
            },
        )
        .expect("small child fits between loaded init and init stack top");

        assert_eq!(plan.load_base, 0x0001_0a20);
        assert_eq!(plan.stack_top, 0x0001_4000);
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
            run_status_from_load_error(ProcessLoadError::AddressOverflow),
            k16_abi::syscall::ERROR_EXEC_FORMAT
        );
        assert_eq!(
            run_status_from_load_error(ProcessLoadError::InvalidArena),
            k16_abi::syscall::ERROR_NO_MEMORY
        );
        assert_eq!(
            run_status_from_load_error(ProcessLoadError::ProgramTooLarge),
            k16_abi::syscall::ERROR_NO_MEMORY
        );
        assert_eq!(
            run_status_from_load_error(ProcessLoadError::Storage),
            k16_abi::syscall::ERROR_NO_ENTRY
        );
        assert_eq!(
            run_status_from_switch_error(ProcessSwitchError::ChildAlreadyRunning),
            k16_abi::syscall::ERROR_BUSY
        );
        assert_eq!(
            run_status_from_switch_error(ProcessSwitchError::NoRunningChild),
            k16_abi::syscall::ERROR_INVALID
        );
        assert_eq!(
            heap_status_from_error(HeapError::NoRunningChild),
            k16_abi::syscall::ERROR_INVALID
        );
        assert_eq!(
            heap_status_from_error(HeapError::OutOfMemory),
            k16_abi::syscall::ERROR_NO_MEMORY
        );
    }
}
