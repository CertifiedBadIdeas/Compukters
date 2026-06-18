use core::cell::UnsafeCell;

const LOAD_ALIGNMENT: u32 = 2;
const STACK_ALIGNMENT: u32 = 4;
const HEAP_ALIGNMENT: u32 = 4;
const STACK_GUARD_BYTES: u32 = 0x100;
const ROOT_PARTITION: &[u8; 4] = b"ROOT";
const BIN_COMPONENT: &[u8] = b"bin";
const BIN_PREFIX: &[u8] = b"/bin/";
const KX_SUFFIX: &[u8] = b".kx";
const K16FS_MAX_NAME_BYTES: usize = 56;
pub const MAX_RUN_PATH_BYTES: usize = BIN_PREFIX.len() + K16FS_MAX_NAME_BYTES;
const CHILD_ARG_ENTRY_BYTES: u32 = 8;
const VM_PAGE_SIZE: u32 = 4096;
const TRANSLATED_TRAP_STACK_BYTES: u32 = VM_PAGE_SIZE;
const INITIAL_USER_LOADER_SCRATCH_END: u32 = k16_storage::SCRATCH_ADDR + k16_storage::BLOCK_SIZE;
#[cfg(any(test, feature = "host-test"))]
const DEFAULT_INIT_MEMORY_END: u32 = 0x0002_5000;
// Keep relocation records outside k16_storage::SCRATCH_ADDR: storage reads use
// that block as staging, and records may straddle a storage block boundary.
const RELOCATION_RECORD_ADDR: u32 = 0x0000_0500;
const MAX_PROCESS_SLOTS: usize = 3;
const INIT_PROCESS_SLOT: usize = 0;
const NO_PARENT_SLOT: u32 = u32::MAX;
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
static mut RUNTIME_SLOT1_PARENT: u32 = NO_PARENT_SLOT;
#[cfg(not(test))]
static mut RUNTIME_SLOT2_PARENT: u32 = NO_PARENT_SLOT;
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

#[repr(u32)]
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ProcessId {
    Init,
    Foreground1,
    Foreground2,
}

impl ProcessId {
    const fn from_slot(slot: usize) -> Self {
        match slot {
            INIT_PROCESS_SLOT => Self::Init,
            1 => Self::Foreground1,
            _ => Self::Foreground2,
        }
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
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct ParentResume {
    pub id: ProcessId,
    pub context: ProcessContext,
    pub frame: TrapFrame,
    pub child_exit_status: u32,
    pub address_space: Option<u32>,
    pub kernel_stack_top: Option<u32>,
    pub exited_address_space: Option<u32>,
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
    state: ProcessState,
    parent_slot: u32,
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
}

#[cfg(any(test, feature = "host-test"))]
impl ProcessSlot {
    const fn empty() -> Self {
        Self {
            state: PROCESS_STATE_EMPTY,
            parent_slot: NO_PARENT_SLOT,
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
        }
    }

    const fn init(context: ProcessContext) -> Self {
        Self {
            state: PROCESS_STATE_RUNNING,
            parent_slot: NO_PARENT_SLOT,
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
        }
    }

    fn clear(&mut self) {
        *self = Self::empty();
    }
}

#[cfg(any(test, feature = "host-test"))]
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct ProcessTable {
    slots: [ProcessSlot; MAX_PROCESS_SLOTS],
    current_slot: usize,
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
        let memory = ProcessMemory::for_loaded_image(image, memory_end)?;
        let init_slot = &mut self.slots[INIT_PROCESS_SLOT];
        init_slot.context = ProcessContext {
            entry_pc: image.entry_pc,
            stack_top: memory.end,
        };
        init_slot.memory = memory;
        init_slot.load_base = align_up(image.load_end, LOAD_ALIGNMENT)?;
        let heap = HeapState::from_bounds(image.load_end, memory.end)
            .map_err(|_| ProcessLoadError::ProgramTooLarge)?;
        init_slot.heap_start = heap.start;
        init_slot.program_break = heap.start;
        init_slot.heap_limit = heap.limit;
        Ok(())
    }

    pub fn initialize_translated_init_plan_in_memory(
        &mut self,
        plan: DynamicUserLoadPlan,
        memory_end: u32,
        translated: TranslatedUserLaunch,
    ) -> Result<ChildLaunch, ProcessLoadError> {
        let memory = ProcessMemory::new(plan.load_base, translated.stack_top)?;
        if translated.kernel_stack_top != memory_end
            || translated.entry_pc != plan.entry_pc
            || translated.stack_top != plan.stack_top
            || translated.stack_top >= translated.kernel_stack_top
        {
            return Err(ProcessLoadError::InvalidArena);
        }
        let init_slot = &mut self.slots[INIT_PROCESS_SLOT];
        let context = ProcessContext {
            entry_pc: translated.entry_pc,
            stack_top: translated.stack_top,
        };
        let frame = child_frame_for_context(context);
        init_slot.context = context;
        init_slot.frame = frame;
        init_slot.memory = memory;
        init_slot.load_base = align_up(plan.load_end, LOAD_ALIGNMENT)?;
        let heap = HeapState::from_bounds(plan.load_end, memory.end)
            .map_err(|_| ProcessLoadError::ProgramTooLarge)?;
        init_slot.heap_start = heap.start;
        init_slot.program_break = heap.start;
        init_slot.heap_limit = heap.limit;
        init_slot.address_space = Some(translated.address_space);
        init_slot.kernel_stack_top = Some(translated.kernel_stack_top);
        self.current_slot = INIT_PROCESS_SLOT;
        Ok(ChildLaunch {
            id: ProcessId::Init,
            context,
            frame,
            address_space: Some(translated.address_space),
            kernel_stack_top: Some(translated.kernel_stack_top),
        })
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
        if self.slots[self.current_slot].state != PROCESS_STATE_RUNNING {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        let child_slot = self
            .next_empty_child_slot()
            .ok_or(ProcessSwitchError::ChildAlreadyRunning)?;
        let parent_slot = self.current_slot;
        if parent_slot == child_slot {
            return Err(ProcessSwitchError::ChildAlreadyRunning);
        }
        let context = ProcessContext {
            entry_pc: child_plan.entry_pc,
            stack_top: child_plan.stack_top,
        };
        let mut child_frame = child_frame_for_context(context);
        child_frame.registers[1] = argv.argc;
        child_frame.registers[2] = argv.table_ptr;
        let heap = HeapState::from_bounds(child_plan.load_end.max(argv.end), child_plan.stack_top)
            .map_err(|_| ProcessSwitchError::NoRunningChild)?;
        let parent = &mut self.slots[parent_slot];
        unsafe { core::ptr::write_volatile(&mut parent.state, PROCESS_STATE_BLOCKED_ON_CHILD) };
        parent.context = ProcessContext {
            entry_pc: init_frame.resume_pc,
            stack_top: init_frame.stack_pointer,
        };
        parent.frame = init_frame;
        let child = &mut self.slots[child_slot];
        child.parent_slot = parent_slot as u32;
        child.context = context;
        child.frame = child_frame;
        child.exit_status = 0;
        child.memory = ProcessMemory::new(child_plan.load_base, child_plan.stack_top)
            .map_err(|_| ProcessSwitchError::NoRunningChild)?;
        child.load_base = align_up(child_plan.load_end, LOAD_ALIGNMENT)
            .map_err(|_| ProcessSwitchError::NoRunningChild)?;
        child.heap_start = heap.start;
        child.program_break = heap.start;
        child.heap_limit = heap.limit;
        unsafe { core::ptr::write_volatile(&mut child.state, PROCESS_STATE_RUNNING) };
        self.current_slot = child_slot;
        Ok(ChildLaunch {
            id: ProcessId::from_slot(child_slot),
            context,
            frame: child_frame,
            address_space: None,
            kernel_stack_top: None,
        })
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
        let mut launch =
            self.begin_child_run_from_frame_and_argv(child_plan, TrapFrame::zeroed(), argv)?;
        let context = ProcessContext {
            entry_pc: translated.entry_pc,
            stack_top: translated.stack_top,
        };
        let mut frame = child_frame_for_context(context);
        frame.registers[1] = argv.argc;
        frame.registers[2] = argv.table_ptr;
        let child = &mut self.slots[self.current_slot];
        child.address_space = Some(translated.address_space);
        child.kernel_stack_top = Some(translated.kernel_stack_top);
        child.context = context;
        child.frame = frame;
        launch.context = context;
        launch.frame = frame;
        launch.address_space = Some(translated.address_space);
        launch.kernel_stack_top = Some(translated.kernel_stack_top);
        Ok(launch)
    }

    pub fn finish_child(&mut self, status: u32) -> Result<ParentResume, ProcessSwitchError> {
        if self.current_slot == INIT_PROCESS_SLOT
            || self.slots[self.current_slot].state != PROCESS_STATE_RUNNING
        {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        let child_slot = self.current_slot;
        let parent_slot = self.slots[child_slot].parent_slot as usize;
        if parent_slot >= MAX_PROCESS_SLOTS {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        let exited_address_space = self.slots[child_slot].address_space;
        unsafe {
            core::ptr::write_volatile(&mut self.slots[child_slot].state, PROCESS_STATE_EMPTY)
        };
        self.slots[child_slot].exit_status = status;
        self.slots[child_slot].clear();
        self.slots[parent_slot].exit_status = status;
        unsafe {
            core::ptr::write_volatile(&mut self.slots[parent_slot].state, PROCESS_STATE_RUNNING)
        };
        self.current_slot = parent_slot;
        let parent = self.slots[parent_slot];
        Ok(ParentResume {
            id: ProcessId::from_slot(parent_slot),
            context: parent.context,
            frame: parent.frame,
            child_exit_status: status,
            address_space: parent.address_space,
            kernel_stack_top: parent.kernel_stack_top,
            exited_address_space,
        })
    }

    pub const fn init_state(&self) -> ProcessState {
        self.slots[INIT_PROCESS_SLOT].state
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
            if self.slots[slot].state == PROCESS_STATE_EMPTY {
                return Some(slot);
            }
            slot += 1;
        }
        None
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

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn initialize_init_process(
    image: k16_boot_chain::LoadedImage,
    memory_end: u32,
) -> Result<(), ProcessLoadError> {
    #[cfg(not(test))]
    {
        let memory = ProcessMemory::for_loaded_image(image, memory_end)?;
        let heap = HeapState::from_bounds(image.load_end, memory.end)
            .map_err(|_| ProcessLoadError::ProgramTooLarge)?;
        unsafe {
            write_runtime_word(
                core::ptr::addr_of_mut!(RUNTIME_CURRENT_SLOT),
                INIT_PROCESS_SLOT as u32,
            );
            write_runtime_word(runtime_slot_parent_ptr(1), NO_PARENT_SLOT);
            write_runtime_word(runtime_slot_parent_ptr(2), NO_PARENT_SLOT);
            write_runtime_process_memory(INIT_PROCESS_SLOT, memory);
            write_runtime_word(runtime_slot_heap_start_ptr(INIT_PROCESS_SLOT), heap.start);
            write_runtime_word(
                runtime_slot_program_break_ptr(INIT_PROCESS_SLOT),
                heap.start,
            );
            write_runtime_word(runtime_slot_heap_limit_ptr(INIT_PROCESS_SLOT), heap.limit);
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
    let child_plan = unsafe { load_dynamic_user_program_from_storage0(path, arena)? };
    let mut allocator = crate::page_alloc::PageFrameAllocator::new_for_kernel(
        translated_init_kernel_reserved_ranges(
            boot_info.program_base,
            boot_info.ram_size,
            kernel_image_end,
            kernel_stack_top,
        ),
    )
    .map_err(page_alloc_error_to_process_load_error)?;
    let translated = unsafe {
        create_translated_user_launch_with_allocator(child_plan, kernel_stack_top, &mut allocator)?
    };
    let memory = ProcessMemory::new(child_plan.load_base, translated.stack_top)?;
    let heap = HeapState::from_bounds(child_plan.load_end, memory.end)
        .map_err(|_| ProcessLoadError::ProgramTooLarge)?;
    let context = ProcessContext {
        entry_pc: translated.entry_pc,
        stack_top: translated.stack_top,
    };
    let frame = child_frame_for_context(context);
    unsafe {
        write_runtime_word(
            core::ptr::addr_of_mut!(RUNTIME_CURRENT_SLOT),
            INIT_PROCESS_SLOT as u32,
        );
        write_runtime_word(runtime_slot_parent_ptr(1), NO_PARENT_SLOT);
        write_runtime_word(runtime_slot_parent_ptr(2), NO_PARENT_SLOT);
        write_runtime_process_memory(INIT_PROCESS_SLOT, memory);
        write_runtime_word(runtime_slot_heap_start_ptr(INIT_PROCESS_SLOT), heap.start);
        write_runtime_word(
            runtime_slot_program_break_ptr(INIT_PROCESS_SLOT),
            heap.start,
        );
        write_runtime_word(runtime_slot_heap_limit_ptr(INIT_PROCESS_SLOT), heap.limit);
        write_runtime_word(
            runtime_slot_address_space_ptr(INIT_PROCESS_SLOT),
            translated.address_space,
        );
        write_runtime_word(
            runtime_slot_kernel_stack_top_ptr(INIT_PROCESS_SLOT),
            translated.kernel_stack_top,
        );
        *runtime_slot_frame(INIT_PROCESS_SLOT).get() = k16_rt::TrapFrame::from(frame);
        *RUNTIME_PAGE_ALLOCATOR.get() = Some(allocator);
    }
    Ok(ChildLaunch {
        id: ProcessId::Init,
        context,
        frame,
        address_space: Some(translated.address_space),
        kernel_stack_top: Some(translated.kernel_stack_top),
    })
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

#[cfg(not(test))]
unsafe fn begin_loaded_child_runtime(path: &[u8], args: &[&[u8]]) -> Result<ChildLaunch, u32> {
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
    let (arena_end, kernel_stack_top) = if translated_child {
        translated_child_arena_end(parent_stack_limit)
            .map_err(|_| run_status_from_load_error(ProcessLoadError::ProgramTooLarge))?
    } else {
        (parent_stack_limit, 0)
    };
    let arena = UserArena::new(caller_program_break, arena_end)
        .map_err(|_| run_status_from_load_error(ProcessLoadError::ProgramTooLarge))?;
    let child_plan = unsafe { load_dynamic_user_program_from_storage0(path, arena) }
        .map_err(run_status_from_load_error)?;
    let argv =
        unsafe { install_child_argv(child_plan, args) }.map_err(run_status_from_load_error)?;
    let translated = if translated_child {
        Some(
            unsafe { create_translated_user_launch(child_plan, kernel_stack_top) }
                .map_err(run_status_from_load_error)?,
        )
    } else {
        None
    };
    unsafe { begin_loaded_child_plan_runtime_with_argv(child_plan, argv, translated) }
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
    let context = translated
        .map(|launch| ProcessContext {
            entry_pc: launch.entry_pc,
            stack_top: launch.stack_top,
        })
        .unwrap_or(ProcessContext {
            entry_pc: child_plan.entry_pc,
            stack_top: child_plan.stack_top,
        });
    let mut child_frame = child_frame_for_context(context);
    child_frame.registers[1] = argv.argc;
    child_frame.registers[2] = argv.table_ptr;
    unsafe {
        write_runtime_process_memory(
            child_slot,
            ProcessMemory::new(child_plan.load_base, child_plan.stack_top)
                .map_err(|_| ProcessSwitchError::NoRunningChild)?,
        );
        write_runtime_word(
            runtime_slot_address_space_ptr(child_slot),
            translated.map(|launch| launch.address_space).unwrap_or(0),
        );
        write_runtime_word(
            runtime_slot_kernel_stack_top_ptr(child_slot),
            translated
                .map(|launch| launch.kernel_stack_top)
                .unwrap_or(0),
        );
        initialize_runtime_heap_from_bounds(
            child_slot,
            child_plan.load_end.max(argv.end),
            child_plan.stack_top,
        )
        .map_err(|_| ProcessSwitchError::NoRunningChild)?
    };
    unsafe {
        write_runtime_word(runtime_slot_parent_ptr(child_slot), parent_slot as u32);
        write_runtime_word(
            core::ptr::addr_of_mut!(RUNTIME_CURRENT_SLOT),
            child_slot as u32,
        );
    }
    Ok(ChildLaunch {
        id: ProcessId::from_slot(child_slot),
        context,
        frame: child_frame,
        address_space: translated.map(|launch| launch.address_space),
        kernel_stack_top: translated.map(|launch| launch.kernel_stack_top),
    })
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
    #[cfg(not(test))]
    {
        return unsafe { finish_child_runtime(status) };
    }
    #[cfg(test)]
    {
        unsafe { PROCESS_TABLE.get().finish_child(status) }
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
unsafe fn finish_child_runtime(status: u32) -> Result<ParentResume, ProcessSwitchError> {
    let current_slot = unsafe { runtime_current_slot() };
    if current_slot == INIT_PROCESS_SLOT {
        return Err(ProcessSwitchError::NoRunningChild);
    }
    let parent_slot = unsafe { read_runtime_word(runtime_slot_parent_ptr(current_slot)) } as usize;
    if parent_slot >= MAX_PROCESS_SLOTS {
        return Err(ProcessSwitchError::NoRunningChild);
    }
    let frame = TrapFrame::from(unsafe { *runtime_slot_frame(parent_slot).get() });
    let exited_address_space = unsafe { read_runtime_address_space(current_slot) };
    unsafe {
        write_runtime_word(runtime_slot_parent_ptr(current_slot), NO_PARENT_SLOT);
        clear_runtime_process_memory(current_slot);
        clear_runtime_address_space(current_slot);
        clear_runtime_kernel_stack_top(current_slot);
        clear_runtime_heap(current_slot);
        write_runtime_word(
            core::ptr::addr_of_mut!(RUNTIME_CURRENT_SLOT),
            parent_slot as u32,
        );
    }
    Ok(ParentResume {
        id: ProcessId::from_slot(parent_slot),
        context: ProcessContext {
            entry_pc: frame.resume_pc,
            stack_top: frame.stack_pointer,
        },
        frame,
        child_exit_status: status,
        address_space: unsafe { read_runtime_address_space(parent_slot) },
        kernel_stack_top: unsafe { read_runtime_kernel_stack_top(parent_slot) },
        exited_address_space,
    })
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
unsafe fn clear_runtime_address_space(slot: usize) {
    unsafe { write_runtime_word(runtime_slot_address_space_ptr(slot), 0) }
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

#[cfg(not(test))]
unsafe fn clear_runtime_kernel_stack_top(slot: usize) {
    unsafe { write_runtime_word(runtime_slot_kernel_stack_top_ptr(slot), 0) }
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
fn runtime_slot_parent_ptr(slot: usize) -> *mut u32 {
    match slot {
        1 => core::ptr::addr_of_mut!(RUNTIME_SLOT1_PARENT),
        2 => core::ptr::addr_of_mut!(RUNTIME_SLOT2_PARENT),
        _ => core::ptr::addr_of_mut!(RUNTIME_SLOT1_PARENT),
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
unsafe fn write_runtime_process_memory(slot: usize, memory: ProcessMemory) {
    unsafe {
        write_runtime_word(runtime_slot_memory_start_ptr(slot), memory.start);
        write_runtime_word(runtime_slot_memory_end_ptr(slot), memory.end);
    }
}

#[cfg(not(test))]
unsafe fn read_runtime_process_memory(slot: usize) -> Option<ProcessMemory> {
    let start = unsafe { read_runtime_word(runtime_slot_memory_start_ptr(slot)) };
    let end = unsafe { read_runtime_word(runtime_slot_memory_end_ptr(slot)) };
    ProcessMemory::new(start, end).ok()
}

#[cfg(not(test))]
unsafe fn clear_runtime_process_memory(slot: usize) {
    unsafe {
        write_runtime_word(runtime_slot_memory_start_ptr(slot), 0);
        write_runtime_word(runtime_slot_memory_end_ptr(slot), 0);
    }
}

#[cfg(not(test))]
unsafe fn initialize_runtime_heap_from_bounds(
    slot: usize,
    load_end: u32,
    stack_top: u32,
) -> Result<(), HeapError> {
    let heap = HeapState::from_bounds(load_end, stack_top)?;
    unsafe {
        write_runtime_word(runtime_slot_heap_start_ptr(slot), heap.start);
        write_runtime_word(runtime_slot_program_break_ptr(slot), heap.start);
        write_runtime_word(runtime_slot_heap_limit_ptr(slot), heap.limit);
    }
    Ok(())
}

#[cfg(not(test))]
unsafe fn clear_runtime_heap(slot: usize) {
    unsafe {
        write_runtime_word(runtime_slot_heap_start_ptr(slot), 0);
        write_runtime_word(runtime_slot_program_break_ptr(slot), 0);
        write_runtime_word(runtime_slot_heap_limit_ptr(slot), 0);
    }
}

#[cfg(not(test))]
unsafe fn set_runtime_program_break(address: u32) -> Result<u32, HeapError> {
    let current_slot = unsafe { runtime_current_slot() };
    let heap_start = unsafe { read_runtime_word(runtime_slot_heap_start_ptr(current_slot)) };
    let heap_limit = unsafe { read_runtime_word(runtime_slot_heap_limit_ptr(current_slot)) };
    if heap_start == 0 {
        return Err(HeapError::NoRunningChild);
    }
    if address < heap_start || address > heap_limit {
        return Err(HeapError::OutOfMemory);
    }
    unsafe { write_runtime_word(runtime_slot_program_break_ptr(current_slot), address) };
    Ok(address)
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
pub unsafe fn resume_parent_context(resume: ParentResume) -> ! {
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
        unsafe { k16_rt::iret_with_r0(resume.child_exit_status) }
    }
    #[cfg(test)]
    {
        let frame = k16_rt::TrapFrame::from(resume.frame);
        let _saved_r0 = unsafe { k16_rt::restore_trap_frame(&frame) };
        unsafe { k16_rt::iret_with_r0(resume.child_exit_status) }
    }
}

fn should_translate_runtime_child_path(_path: &[u8]) -> bool {
    true
}

fn runtime_child_slot_for_parent(
    current_slot: usize,
    _current_address_space: Option<u32>,
) -> Result<usize, ProcessSwitchError> {
    let child_slot = current_slot + 1;
    if child_slot >= MAX_PROCESS_SLOTS {
        return Err(ProcessSwitchError::ChildAlreadyRunning);
    }
    Ok(child_slot)
}

fn trap_return_override_for_resume(
    resume: ParentResume,
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

fn translated_child_arena_end(parent_stack_limit: u32) -> Result<(u32, u32), ProcessLoadError> {
    let kernel_stack_top = align_down(parent_stack_limit, STACK_ALIGNMENT);
    let child_arena_end = kernel_stack_top
        .checked_sub(TRANSLATED_TRAP_STACK_BYTES)
        .ok_or(ProcessLoadError::ProgramTooLarge)?;
    Ok((
        align_down(child_arena_end, STACK_ALIGNMENT),
        kernel_stack_top,
    ))
}

fn translated_init_arena_end(memory_end: u32) -> Result<(u32, u32), ProcessLoadError> {
    translated_child_arena_end(memory_end)
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
    map_start: u32,
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
            map_start,
            backing_start,
            page_count,
        })
    }

    pub const fn virtual_plan(&self) -> DynamicUserLoadPlan {
        self.virtual_plan
    }

    pub const fn backing_start(&self) -> u32 {
        self.backing_start
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
        let offset = virtual_address
            .checked_sub(self.map_start)
            .ok_or(ProcessLoadError::InvalidArena)?;
        self.backing_start
            .checked_add(offset)
            .ok_or(ProcessLoadError::AddressOverflow)
    }
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
        if bytes.len() < 12 {
            return Err(ProcessLoadError::InvalidPath);
        }
        if read_request_u32(bytes, 0)? != k16_abi::syscall::RUN_ARGV_MAGIC {
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
    if args.is_empty() {
        return Ok(ChildArgv::empty());
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
    let heap_limit =
        heap_limit_from_stack_top(plan.stack_top).map_err(|_| ProcessLoadError::ProgramTooLarge)?;
    if end > heap_limit {
        return Err(ProcessLoadError::ProgramTooLarge);
    }
    let mut arg_ptr = arg_data_ptr;
    let mut index = 0_u32;
    for arg in args {
        let entry_ptr = table_ptr + index * CHILD_ARG_ENTRY_BYTES;
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
    Ok(ChildArgv {
        argc: args.len() as u32,
        table_ptr,
        end,
    })
}

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
    })
}

unsafe fn create_translated_user_launch_with_allocator(
    plan: DynamicUserLoadPlan,
    kernel_stack_top: u32,
    allocator: &mut crate::page_alloc::PageFrameAllocator,
) -> Result<TranslatedUserLaunch, ProcessLoadError> {
    let map_start = page_align_down(plan.load_base);
    let map_end = page_align_up(plan.stack_top)?;
    if map_start >= map_end {
        return Err(ProcessLoadError::InvalidArena);
    }
    let page_count = (map_end - map_start) / VM_PAGE_SIZE;
    let backing = allocator
        .allocate_contiguous(page_count)
        .map_err(page_alloc_error_to_process_load_error)?;
    let address_space = match unsafe { mmu0_create_address_space() } {
        Ok(address_space) => address_space,
        Err(error) => {
            let _ = allocator.free_contiguous(backing);
            return Err(error);
        }
    };
    let map_result = unsafe {
        mmu0_map_pages(
            address_space,
            map_start,
            backing.start,
            page_count,
            k16_abi::computer::mmu0::FLAG_USER_ACCESSIBLE
                | k16_abi::computer::mmu0::FLAG_WRITABLE
                | k16_abi::computer::mmu0::FLAG_EXECUTABLE,
        )
    };
    if let Err(error) = map_result {
        let _ = unsafe { mmu0_destroy_address_space(address_space) };
        let _ = allocator.free_contiguous(backing);
        return Err(error);
    }
    Ok(TranslatedUserLaunch {
        address_space,
        entry_pc: plan.entry_pc,
        stack_top: plan.stack_top,
        kernel_stack_top,
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

pub unsafe fn destroy_exited_address_space(resume: ParentResume) -> Result<(), ProcessLoadError> {
    let Some(address_space) = resume.exited_address_space else {
        return Ok(());
    };
    unsafe { mmu0_destroy_address_space(address_space) }
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

fn heap_limit_from_stack_top(stack_top: u32) -> Result<u32, HeapError> {
    let guarded = stack_top
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

        assert_eq!(child.id, ProcessId::Foreground1);
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
    fn process_ids_name_foreground_slots_by_depth() {
        assert_eq!(ProcessId::from_slot(0), ProcessId::Init);
        assert_eq!(ProcessId::from_slot(1), ProcessId::Foreground1);
        assert_eq!(ProcessId::from_slot(2), ProcessId::Foreground2);
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
        assert_eq!(table.heap_limit(), Ok(0x0000_ff00));
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
        assert_eq!(table.heap_limit(), Ok(0x0002_4f00));
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
        assert_eq!(table.heap_limit(), Ok(0x0001_8f00));
        assert_eq!(
            table.slots[INIT_PROCESS_SLOT].context.stack_top,
            0x0001_9000
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
    fn translated_child_arena_reserves_kernel_trap_stack_below_parent_stack() {
        assert_eq!(
            translated_child_arena_end(0x0002_0000),
            Ok((0x0001_f000, 0x0002_0000))
        );
        assert_eq!(
            translated_child_arena_end(0x0002_0003),
            Ok((0x0001_f000, 0x0002_0000))
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
    fn translated_parent_resume_requires_trap_return_address_space_override() {
        let resume = ParentResume {
            id: ProcessId::Foreground1,
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
            address_space: Some(7),
            kernel_stack_top: Some(0x0001_d000),
            exited_address_space: None,
        };

        assert_eq!(
            trap_return_override_for_resume(resume),
            Ok(TrapReturnOverride::Translated {
                address_space: 7,
                kernel_stack_top: 0x0001_d000,
            })
        );

        assert_eq!(
            trap_return_override_for_resume(ParentResume {
                kernel_stack_top: None,
                ..resume
            }),
            Err(ProcessSwitchError::NoRunningChild)
        );
    }

    #[test]
    fn translated_user_launch_maps_child_backing_pages() {
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
        assert_eq!(
            allocator
                .allocate_contiguous(1)
                .expect("next frame allocates"),
            crate::page_alloc::FrameRange {
                start: 0x0001_0000,
                frame_count: 1,
            }
        );
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
            id: ProcessId::Init,
            context: ProcessContext {
                entry_pc: 0,
                stack_top: 0,
            },
            frame: TrapFrame::zeroed(),
            child_exit_status: 0,
            address_space: None,
            kernel_stack_top: None,
            exited_address_space: Some(11),
        };

        unsafe { destroy_exited_address_space(resume) }.expect("destroy succeeds");

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
        assert_eq!(resumed_init.id, ProcessId::Init);
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

        assert_eq!(resumed.id, ProcessId::Init);
        assert_eq!(resumed.child_exit_status, 7);
        assert_eq!(table.init_state(), PROCESS_STATE_RUNNING);
        assert_eq!(table.child_state(), PROCESS_STATE_EMPTY);
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
        };
        table
            .begin_translated_child_run(child_plan, translated)
            .expect("translated child starts");

        let resumed = table.finish_child(0).expect("parent resumes");

        assert_eq!(resumed.exited_address_space, Some(9));
        assert_eq!(resumed.address_space, None);
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
        };
        let mut table = ProcessTable::new(ProcessContext {
            entry_pc: 0,
            stack_top: 0,
        });

        let launch = table
            .initialize_translated_init_plan_in_memory(plan, 0x0003_0000, translated)
            .expect("translated init image records");

        assert_eq!(launch.id, ProcessId::Init);
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
        init_frame.stack_pointer = 0x0001_2000;

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
        assert_eq!(plan.stack_top, 0x0001_2000);
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
