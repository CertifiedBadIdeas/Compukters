#[cfg(any(not(test), feature = "host-test"))]
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
const USER_PROGRAM_LIMIT: u32 = 0x0002_0000;
// Keep relocation records outside k16_storage::SCRATCH_ADDR: storage reads use
// that block as staging, and records may straddle a storage block boundary.
const RELOCATION_RECORD_ADDR: u32 = 0x0000_0500;
#[cfg(feature = "host-test")]
#[allow(dead_code)]
static PROCESS_TABLE: KernelProcessTable =
    KernelProcessTable::new(ProcessTable::new(ProcessContext {
        entry_pc: 0,
        stack_top: 0,
    }));
#[cfg(not(test))]
static RUNTIME_INIT_FRAME: KernelCell<k16_rt::TrapFrame> =
    KernelCell::new(k16_rt::TrapFrame::zeroed());
#[cfg(not(test))]
static mut RUNTIME_CHILD_STATE: ProcessState = PROCESS_STATE_EMPTY;
#[cfg(not(test))]
static mut RUNTIME_CHILD_LOAD_BASE: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_INIT_HEAP_START: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_INIT_PROGRAM_BREAK: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_INIT_HEAP_LIMIT: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_CHILD_HEAP_START: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_CHILD_PROGRAM_BREAK: u32 = 0;
#[cfg(not(test))]
static mut RUNTIME_CHILD_HEAP_LIMIT: u32 = 0;

#[cfg(feature = "host-test")]
#[allow(dead_code)]
struct KernelProcessTable {
    table: UnsafeCell<ProcessTable>,
}

#[cfg(feature = "host-test")]
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

#[cfg(feature = "host-test")]
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
    Child,
}

pub type ProcessState = u32;

pub const PROCESS_STATE_EMPTY: ProcessState = 0;
pub const PROCESS_STATE_RUNNING: ProcessState = 1;
pub const PROCESS_STATE_BLOCKED_ON_CHILD: ProcessState = 2;

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
    init_state: ProcessState,
    init_context: ProcessContext,
    init_frame: TrapFrame,
    child_state: ProcessState,
    child_context: ProcessContext,
    child_frame: TrapFrame,
    child_exit_status: u32,
    child_load_base: u32,
    init_heap_start: u32,
    init_program_break: u32,
    init_heap_limit: u32,
    child_heap_start: u32,
    child_program_break: u32,
    child_heap_limit: u32,
}

impl ProcessTable {
    pub const fn new(init_context: ProcessContext) -> Self {
        Self {
            init_state: PROCESS_STATE_RUNNING,
            init_context,
            init_frame: TrapFrame::zeroed(),
            child_state: PROCESS_STATE_EMPTY,
            child_context: ProcessContext {
                entry_pc: 0,
                stack_top: 0,
            },
            child_frame: TrapFrame::zeroed(),
            child_exit_status: 0,
            child_load_base: 0,
            init_heap_start: 0,
            init_program_break: 0,
            init_heap_limit: 0,
            child_heap_start: 0,
            child_program_break: 0,
            child_heap_limit: 0,
        }
    }

    pub fn initialize_init_image(
        &mut self,
        image: k16_boot_chain::LoadedImage,
    ) -> Result<(), ProcessLoadError> {
        if image.load_addr >= image.load_end || image.load_end > USER_PROGRAM_LIMIT {
            return Err(ProcessLoadError::InvalidArena);
        }
        self.init_context = ProcessContext {
            entry_pc: image.entry_pc,
            stack_top: USER_PROGRAM_LIMIT,
        };
        self.child_load_base = align_up(image.load_end, LOAD_ALIGNMENT)?;
        let heap = HeapState::from_bounds(image.load_end, USER_PROGRAM_LIMIT)
            .map_err(|_| ProcessLoadError::ProgramTooLarge)?;
        self.init_heap_start = heap.start;
        self.init_program_break = heap.start;
        self.init_heap_limit = heap.limit;
        Ok(())
    }

    pub fn child_arena_for_init_frame(
        &self,
        init_frame: TrapFrame,
    ) -> Result<UserArena, ProcessLoadError> {
        if self.child_load_base == 0 {
            return Err(ProcessLoadError::Storage);
        }
        let load_base = self.init_program_break.max(self.child_load_base);
        UserArena::new(load_base, init_frame.stack_pointer)
            .map_err(|_| ProcessLoadError::ProgramTooLarge)
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
        if self.child_state_runtime() == PROCESS_STATE_RUNNING {
            return Err(ProcessSwitchError::ChildAlreadyRunning);
        }
        let context = ProcessContext {
            entry_pc: child_plan.entry_pc,
            stack_top: child_plan.stack_top,
        };
        let child_frame = child_frame_for_context(context);
        let heap = HeapState::from_child_plan(child_plan)
            .map_err(|_| ProcessSwitchError::NoRunningChild)?;
        unsafe { core::ptr::write_volatile(&mut self.init_state, PROCESS_STATE_BLOCKED_ON_CHILD) };
        self.init_context = ProcessContext {
            entry_pc: init_frame.resume_pc,
            stack_top: init_frame.stack_pointer,
        };
        self.init_frame = init_frame;
        unsafe { core::ptr::write_volatile(&mut self.child_state, PROCESS_STATE_RUNNING) };
        self.child_context = context;
        self.child_frame = child_frame;
        self.child_exit_status = 0;
        self.child_heap_start = heap.start;
        self.child_program_break = heap.start;
        self.child_heap_limit = heap.limit;
        Ok(ChildLaunch {
            id: ProcessId::Child,
            context,
            frame: child_frame,
        })
    }

    pub fn finish_child(&mut self, status: u32) -> Result<InitResume, ProcessSwitchError> {
        if self.child_state_runtime() != PROCESS_STATE_RUNNING {
            return Err(ProcessSwitchError::NoRunningChild);
        }
        unsafe { core::ptr::write_volatile(&mut self.child_state, PROCESS_STATE_EMPTY) };
        self.child_exit_status = status;
        self.child_heap_start = 0;
        self.child_program_break = 0;
        self.child_heap_limit = 0;
        unsafe { core::ptr::write_volatile(&mut self.init_state, PROCESS_STATE_RUNNING) };
        Ok(InitResume {
            id: ProcessId::Init,
            context: self.init_context,
            frame: self.init_frame,
            child_exit_status: status,
        })
    }

    pub const fn init_state(&self) -> ProcessState {
        self.init_state
    }

    pub const fn child_state(&self) -> ProcessState {
        self.child_state
    }

    pub fn program_break(&self) -> Result<u32, HeapError> {
        let (_, program_break, _) = self.current_heap()?;
        Ok(program_break)
    }

    pub fn heap_limit(&self) -> Result<u32, HeapError> {
        let (_, _, heap_limit) = self.current_heap()?;
        Ok(heap_limit)
    }

    pub fn set_program_break(&mut self, address: u32) -> Result<u32, HeapError> {
        let (heap_start, _, heap_limit) = self.current_heap()?;
        if address < heap_start || address > heap_limit {
            return Err(HeapError::OutOfMemory);
        }
        if self.child_state_runtime() == PROCESS_STATE_RUNNING {
            self.child_program_break = address;
        } else {
            self.init_program_break = address;
        }
        Ok(address)
    }

    pub fn grow_program_break(&mut self, delta: u32) -> Result<u32, HeapError> {
        let old_break = self.program_break()?;
        let new_break = old_break.checked_add(delta).ok_or(HeapError::OutOfMemory)?;
        self.set_program_break(new_break)?;
        Ok(old_break)
    }

    fn child_state_runtime(&self) -> ProcessState {
        unsafe { core::ptr::read_volatile(&self.child_state) }
    }

    fn current_heap(&self) -> Result<(u32, u32, u32), HeapError> {
        if self.child_state_runtime() == PROCESS_STATE_RUNNING {
            return Ok((
                self.child_heap_start,
                self.child_program_break,
                self.child_heap_limit,
            ));
        }
        if self.init_heap_start == 0 {
            return Err(HeapError::NoRunningChild);
        }
        Ok((
            self.init_heap_start,
            self.init_program_break,
            self.init_heap_limit,
        ))
    }
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
    #[cfg(not(test))]
    {
        unsafe { save_runtime_init_frame() };
        return unsafe { begin_loaded_child_plan_runtime(child_plan) };
    }
    #[cfg(test)]
    {
        let mut init_frame = k16_rt::TrapFrame::zeroed();
        k16_rt::save_trap_frame(&mut init_frame);
        unsafe {
            PROCESS_TABLE
                .get()
                .begin_child_run_from_frame(child_plan, TrapFrame::from(init_frame))
        }
    }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn initialize_init_process(
    image: k16_boot_chain::LoadedImage,
) -> Result<(), ProcessLoadError> {
    #[cfg(not(test))]
    {
        if image.load_addr >= image.load_end || image.load_end > USER_PROGRAM_LIMIT {
            return Err(ProcessLoadError::InvalidArena);
        }
        let heap = HeapState::from_bounds(image.load_end, USER_PROGRAM_LIMIT)
            .map_err(|_| ProcessLoadError::ProgramTooLarge)?;
        unsafe {
            write_runtime_word(
                core::ptr::addr_of_mut!(RUNTIME_CHILD_LOAD_BASE),
                align_up(image.load_end, LOAD_ALIGNMENT)?,
            );
            write_runtime_word(core::ptr::addr_of_mut!(RUNTIME_INIT_HEAP_START), heap.start);
            write_runtime_word(
                core::ptr::addr_of_mut!(RUNTIME_INIT_PROGRAM_BREAK),
                heap.start,
            );
            write_runtime_word(core::ptr::addr_of_mut!(RUNTIME_INIT_HEAP_LIMIT), heap.limit);
        }
        return Ok(());
    }
    #[cfg(test)]
    {
        unsafe { PROCESS_TABLE.get().initialize_init_image(image) }
    }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn begin_loaded_child_from_path(path: &[u8]) -> Result<ChildLaunch, u32> {
    #[cfg(not(test))]
    {
        let mut init_frame = k16_rt::TrapFrame::zeroed();
        k16_rt::save_trap_frame(&mut init_frame);
        unsafe { save_runtime_init_frame() };
        let initial_child_load_base =
            unsafe { read_runtime_word(core::ptr::addr_of_mut!(RUNTIME_CHILD_LOAD_BASE)) };
        let init_program_break =
            unsafe { read_runtime_word(core::ptr::addr_of_mut!(RUNTIME_INIT_PROGRAM_BREAK)) };
        let load_base = initial_child_load_base.max(init_program_break);
        if load_base == 0 {
            return Err(run_status_from_load_error(ProcessLoadError::Storage));
        }
        let arena = UserArena::new(load_base, init_frame.stack_pointer)
            .map_err(|_| run_status_from_load_error(ProcessLoadError::ProgramTooLarge))?;
        let child_plan = unsafe { load_dynamic_user_program_from_storage0(path, arena) }
            .map_err(run_status_from_load_error)?;
        return unsafe { begin_loaded_child_plan_runtime(child_plan) }
            .map_err(run_status_from_switch_error);
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

#[cfg(not(test))]
unsafe fn begin_loaded_child_plan_runtime(
    child_plan: DynamicUserLoadPlan,
) -> Result<ChildLaunch, ProcessSwitchError> {
    if unsafe { runtime_child_state() } == PROCESS_STATE_RUNNING {
        return Err(ProcessSwitchError::ChildAlreadyRunning);
    }
    let context = ProcessContext {
        entry_pc: child_plan.entry_pc,
        stack_top: child_plan.stack_top,
    };
    let child_frame = child_frame_for_context(context);
    unsafe { initialize_runtime_heap(child_plan).map_err(|_| ProcessSwitchError::NoRunningChild)? };
    unsafe { set_runtime_child_state_running() };
    Ok(ChildLaunch {
        id: ProcessId::Child,
        context,
        frame: child_frame,
    })
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn enter_child_context(launch: ChildLaunch) -> ! {
    let frame = k16_rt::TrapFrame::from(launch.frame);
    let _saved_r0 = unsafe { k16_rt::restore_trap_frame(&frame) };
    unsafe { k16_rt::iret_with_r0(0) }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn finish_child_for_exit(status: u32) -> Result<InitResume, ProcessSwitchError> {
    #[cfg(not(test))]
    {
        return unsafe { finish_child_runtime(status) };
    }
    #[cfg(test)]
    {
        unsafe { PROCESS_TABLE.get().finish_child(status) }
    }
}

#[cfg(not(test))]
unsafe fn finish_child_runtime(status: u32) -> Result<InitResume, ProcessSwitchError> {
    let child_state = unsafe { runtime_child_state() };
    if child_state != PROCESS_STATE_RUNNING {
        return Err(ProcessSwitchError::NoRunningChild);
    }
    unsafe { set_runtime_child_state_empty() };
    unsafe { clear_runtime_heap() };
    Ok(InitResume {
        id: ProcessId::Init,
        context: ProcessContext {
            entry_pc: 0,
            stack_top: 0,
        },
        frame: TrapFrame::zeroed(),
        child_exit_status: status,
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
unsafe fn save_runtime_init_frame() {
    let saved = unsafe { RUNTIME_INIT_FRAME.get() };
    k16_rt::save_trap_frame(saved);
}

#[cfg(not(test))]
unsafe fn runtime_child_state() -> ProcessState {
    unsafe { read_u32_le(runtime_child_state_addr()) }
}

#[cfg(not(test))]
unsafe fn set_runtime_child_state_running() {
    unsafe { write_runtime_word(core::ptr::addr_of_mut!(RUNTIME_CHILD_STATE), 1) }
}

#[cfg(not(test))]
unsafe fn set_runtime_child_state_empty() {
    unsafe { write_runtime_word(core::ptr::addr_of_mut!(RUNTIME_CHILD_STATE), 0) }
}

#[cfg(not(test))]
fn runtime_child_state_addr() -> u32 {
    core::ptr::addr_of_mut!(RUNTIME_CHILD_STATE) as usize as u32
}

#[cfg(not(test))]
unsafe fn initialize_runtime_heap(child_plan: DynamicUserLoadPlan) -> Result<(), HeapError> {
    let heap = HeapState::from_child_plan(child_plan)?;
    unsafe {
        write_runtime_word(
            core::ptr::addr_of_mut!(RUNTIME_CHILD_HEAP_START),
            heap.start,
        );
        write_runtime_word(
            core::ptr::addr_of_mut!(RUNTIME_CHILD_PROGRAM_BREAK),
            heap.start,
        );
        write_runtime_word(
            core::ptr::addr_of_mut!(RUNTIME_CHILD_HEAP_LIMIT),
            heap.limit,
        );
    }
    Ok(())
}

#[cfg(not(test))]
unsafe fn clear_runtime_heap() {
    unsafe {
        write_runtime_word(core::ptr::addr_of_mut!(RUNTIME_CHILD_HEAP_START), 0);
        write_runtime_word(core::ptr::addr_of_mut!(RUNTIME_CHILD_PROGRAM_BREAK), 0);
        write_runtime_word(core::ptr::addr_of_mut!(RUNTIME_CHILD_HEAP_LIMIT), 0);
    }
}

#[cfg(not(test))]
unsafe fn set_runtime_program_break(address: u32) -> Result<u32, HeapError> {
    let child_running = unsafe { runtime_child_state() } == PROCESS_STATE_RUNNING;
    let heap_start = unsafe {
        read_runtime_word(if child_running {
            core::ptr::addr_of_mut!(RUNTIME_CHILD_HEAP_START)
        } else {
            core::ptr::addr_of_mut!(RUNTIME_INIT_HEAP_START)
        })
    };
    let heap_limit = unsafe {
        read_runtime_word(if child_running {
            core::ptr::addr_of_mut!(RUNTIME_CHILD_HEAP_LIMIT)
        } else {
            core::ptr::addr_of_mut!(RUNTIME_INIT_HEAP_LIMIT)
        })
    };
    if heap_start == 0 {
        return Err(HeapError::NoRunningChild);
    }
    if address < heap_start || address > heap_limit {
        return Err(HeapError::OutOfMemory);
    }
    unsafe {
        write_runtime_word(
            if child_running {
                core::ptr::addr_of_mut!(RUNTIME_CHILD_PROGRAM_BREAK)
            } else {
                core::ptr::addr_of_mut!(RUNTIME_INIT_PROGRAM_BREAK)
            },
            address,
        )
    };
    Ok(address)
}

#[cfg(not(test))]
unsafe fn grow_runtime_program_break(delta: u32) -> Result<u32, HeapError> {
    let child_running = unsafe { runtime_child_state() } == PROCESS_STATE_RUNNING;
    let old_break = unsafe {
        read_runtime_word(if child_running {
            core::ptr::addr_of_mut!(RUNTIME_CHILD_PROGRAM_BREAK)
        } else {
            core::ptr::addr_of_mut!(RUNTIME_INIT_PROGRAM_BREAK)
        })
    };
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

#[cfg(not(test))]
unsafe fn restore_runtime_init_frame() -> u32 {
    let saved = unsafe { RUNTIME_INIT_FRAME.get() };
    unsafe { k16_rt::restore_trap_frame(&*saved) }
}

#[cfg(any(not(test), feature = "host-test"))]
pub unsafe fn resume_init_context(resume: InitResume) -> ! {
    #[cfg(not(test))]
    {
        let _saved_r0 = unsafe { restore_runtime_init_frame() };
        unsafe { k16_rt::iret_with_r0(resume.child_exit_status) }
    }
    #[cfg(test)]
    {
        let frame = k16_rt::TrapFrame::from(resume.frame);
        let _saved_r0 = unsafe { k16_rt::restore_trap_frame(&frame) };
        unsafe { k16_rt::iret_with_r0(resume.child_exit_status) }
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

    fn from_child_plan(child_plan: DynamicUserLoadPlan) -> Result<Self, HeapError> {
        Self::from_bounds(child_plan.load_end, child_plan.stack_top)
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

unsafe fn zero_fill_ram(dst_addr: u32, len: u32) {
    let mut offset = 0;
    while offset < len {
        unsafe { write_u8(dst_addr + offset, 0) };
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

        assert_eq!(child.id, ProcessId::Child);
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
        assert_eq!(table.heap_limit(), Ok(0x0001_ff00));
        assert_eq!(table.grow_program_break(0x20), Ok(0x0000_9024));
        assert_eq!(table.program_break(), Ok(0x0000_9044));
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
        assert_eq!(table.init_state(), PROCESS_STATE_RUNNING);
        assert_eq!(table.child_state(), PROCESS_STATE_EMPTY);
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
