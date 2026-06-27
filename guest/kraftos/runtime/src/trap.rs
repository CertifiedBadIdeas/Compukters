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

#[cfg(not(any(test, feature = "host-test")))]
extern "C" {
    fn __k16_save_trap_frame(frame: *mut TrapFrame);
    fn __k16_restore_trap_frame(frame: *const TrapFrame) -> u32;
    fn __k16_iret_once() -> !;
    fn __k16_write_trap_vector(value: u32);
    fn __k16_read_trap_cause() -> u32;
    fn __k16_read_trap_pc() -> u32;
    fn __k16_read_trap_value() -> u32;
    fn __k16_read_trap_arg0() -> u32;
    fn __k16_read_trap_arg1() -> u32;
    fn __k16_read_trap_arg2() -> u32;
    fn __k16_iret_with_r0(value: u32) -> !;
    fn __k16_write_interrupt_enable(value: u32);
    fn __k16_write_interrupt_mask(value: u32);
    fn __k16_read_interrupt_pending() -> u32;
}

#[cfg(any(test, feature = "host-test"))]
use std::cell::{Cell, RefCell};

#[cfg(any(test, feature = "host-test"))]
std::thread_local! {
    static TEST_TRAP_FRAME: RefCell<TrapFrame> = const { RefCell::new(TrapFrame::zeroed()) };
    static TEST_TRAP_VECTOR: Cell<u32> = const { Cell::new(0) };
    static TEST_TRAP_CAUSE: Cell<u32> = const { Cell::new(0) };
    static TEST_TRAP_PC: Cell<u32> = const { Cell::new(0) };
    static TEST_TRAP_VALUE: Cell<u32> = const { Cell::new(0) };
    static TEST_SYSCALL_ARG0: Cell<u32> = const { Cell::new(0) };
    static TEST_SYSCALL_ARG1: Cell<u32> = const { Cell::new(0) };
    static TEST_SYSCALL_ARG2: Cell<u32> = const { Cell::new(0) };
    static TEST_INTERRUPT_ENABLE: Cell<u32> = const { Cell::new(0) };
    static TEST_INTERRUPT_MASK: Cell<u32> = const { Cell::new(0) };
    static TEST_INTERRUPT_PENDING: Cell<u32> = const { Cell::new(0) };
}

#[cfg(any(test, feature = "host-test"))]
macro_rules! test_state_store {
    ($name:ident, $value:expr) => {
        $name.with(|cell| cell.set($value))
    };
}

#[cfg(any(test, feature = "host-test"))]
macro_rules! test_state_load {
    ($name:ident) => {
        $name.with(|cell| cell.get())
    };
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn save_trap_frame(frame: &mut TrapFrame) {
    unsafe {
        __k16_save_trap_frame(frame as *mut TrapFrame);
    }
}

#[cfg(any(test, feature = "host-test"))]
pub fn save_trap_frame(frame: &mut TrapFrame) {
    TEST_TRAP_FRAME.with(|cell| {
        *frame = *cell.borrow();
    });
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub unsafe fn restore_trap_frame(frame: &TrapFrame) -> u32 {
    unsafe { __k16_restore_trap_frame(frame as *const TrapFrame) }
}

#[cfg(any(test, feature = "host-test"))]
pub unsafe fn restore_trap_frame(frame: &TrapFrame) -> u32 {
    TEST_TRAP_FRAME.with(|cell| {
        *cell.borrow_mut() = *frame;
    });
    frame.registers[0]
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub unsafe fn install_trap_vector(address: u32) {
    unsafe {
        __k16_write_trap_vector(address);
    }
}

#[cfg(any(test, feature = "host-test"))]
pub unsafe fn install_trap_vector(address: u32) {
    test_state_store!(TEST_TRAP_VECTOR, address);
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn trap_cause() -> u32 {
    unsafe { __k16_read_trap_cause() }
}

#[cfg(any(test, feature = "host-test"))]
pub fn trap_cause() -> u32 {
    test_state_load!(TEST_TRAP_CAUSE)
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn trap_pc() -> u32 {
    unsafe { __k16_read_trap_pc() }
}

#[cfg(any(test, feature = "host-test"))]
pub fn trap_pc() -> u32 {
    test_state_load!(TEST_TRAP_PC)
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn trap_value() -> u32 {
    unsafe { __k16_read_trap_value() }
}

#[cfg(any(test, feature = "host-test"))]
pub fn trap_value() -> u32 {
    test_state_load!(TEST_TRAP_VALUE)
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn syscall_arg0() -> u32 {
    unsafe { __k16_read_trap_arg0() }
}

#[cfg(any(test, feature = "host-test"))]
pub fn syscall_arg0() -> u32 {
    test_state_load!(TEST_SYSCALL_ARG0)
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn syscall_arg1() -> u32 {
    unsafe { __k16_read_trap_arg1() }
}

#[cfg(any(test, feature = "host-test"))]
pub fn syscall_arg1() -> u32 {
    test_state_load!(TEST_SYSCALL_ARG1)
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn syscall_arg2() -> u32 {
    unsafe { __k16_read_trap_arg2() }
}

#[cfg(any(test, feature = "host-test"))]
pub fn syscall_arg2() -> u32 {
    test_state_load!(TEST_SYSCALL_ARG2)
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub unsafe fn set_interrupt_mask(mask: u32) {
    unsafe {
        __k16_write_interrupt_mask(mask);
    }
}

#[cfg(any(test, feature = "host-test"))]
pub unsafe fn set_interrupt_mask(mask: u32) {
    test_state_store!(TEST_INTERRUPT_MASK, mask);
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn interrupt_pending() -> u32 {
    unsafe { __k16_read_interrupt_pending() }
}

#[cfg(any(test, feature = "host-test"))]
pub fn interrupt_pending() -> u32 {
    test_state_load!(TEST_INTERRUPT_PENDING)
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub unsafe fn enable_interrupts() {
    unsafe {
        __k16_write_interrupt_enable(1);
    }
}

#[cfg(any(test, feature = "host-test"))]
pub unsafe fn enable_interrupts() {
    test_state_store!(TEST_INTERRUPT_ENABLE, 1);
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn disable_interrupts() {
    unsafe {
        __k16_write_interrupt_enable(0);
    }
}

#[cfg(any(test, feature = "host-test"))]
pub fn disable_interrupts() {
    test_state_store!(TEST_INTERRUPT_ENABLE, 0);
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub unsafe fn iret_once() -> ! {
    unsafe { __k16_iret_once() }
}

#[cfg(any(test, feature = "host-test"))]
pub unsafe fn iret_once() -> ! {
    panic!("k16 interrupt return is only available on the K16 target")
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub unsafe fn iret_with_r0(value: u32) -> ! {
    unsafe { __k16_iret_with_r0(value) }
}

#[cfg(any(test, feature = "host-test"))]
pub unsafe fn iret_with_r0(_value: u32) -> ! {
    panic!("k16 interrupt return is only available on the K16 target")
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn reset_test_interrupts() {
    TEST_TRAP_FRAME.with(|cell| {
        *cell.borrow_mut() = TrapFrame::zeroed();
    });
    test_state_store!(TEST_TRAP_VECTOR, 0);
    test_state_store!(TEST_TRAP_CAUSE, 0);
    test_state_store!(TEST_TRAP_PC, 0);
    test_state_store!(TEST_TRAP_VALUE, 0);
    test_state_store!(TEST_SYSCALL_ARG0, 0);
    test_state_store!(TEST_SYSCALL_ARG1, 0);
    test_state_store!(TEST_SYSCALL_ARG2, 0);
    test_state_store!(TEST_INTERRUPT_ENABLE, 0);
    test_state_store!(TEST_INTERRUPT_MASK, 0);
    test_state_store!(TEST_INTERRUPT_PENDING, 0);
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn set_test_trap_state(cause: u32, pc: u32, value: u32) {
    test_state_store!(TEST_TRAP_CAUSE, cause);
    test_state_store!(TEST_TRAP_PC, pc);
    test_state_store!(TEST_TRAP_VALUE, value);
    test_state_store!(TEST_INTERRUPT_PENDING, value);
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn set_test_syscall_args(arg0: u32, arg1: u32, arg2: u32) {
    test_state_store!(TEST_SYSCALL_ARG0, arg0);
    test_state_store!(TEST_SYSCALL_ARG1, arg1);
    test_state_store!(TEST_SYSCALL_ARG2, arg2);
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn test_trap_vector() -> u32 {
    test_state_load!(TEST_TRAP_VECTOR)
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn test_syscall_arg0() -> u32 {
    test_state_load!(TEST_SYSCALL_ARG0)
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn test_syscall_arg1() -> u32 {
    test_state_load!(TEST_SYSCALL_ARG1)
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn test_syscall_arg2() -> u32 {
    test_state_load!(TEST_SYSCALL_ARG2)
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn test_interrupt_enable() -> u32 {
    test_state_load!(TEST_INTERRUPT_ENABLE)
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn test_interrupt_mask() -> u32 {
    test_state_load!(TEST_INTERRUPT_MASK)
}
