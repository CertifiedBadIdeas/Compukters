#[cfg(not(any(test, feature = "host-test")))]
extern "C" {
    fn __k16_iret_once() -> !;
    fn __k16_write_trap_vector(value: u32);
    fn __k16_read_trap_cause() -> u32;
    fn __k16_read_trap_pc() -> u32;
    fn __k16_read_trap_value() -> u32;
    fn __k16_read_trap_arg0() -> u32;
    fn __k16_read_trap_arg1() -> u32;
    fn __k16_read_trap_arg2() -> u32;
    fn __k16_syscall_once(number: u32);
    fn __k16_syscall0(number: u32) -> u32;
    fn __k16_syscall1(number: u32, arg0: u32) -> u32;
    fn __k16_syscall3(number: u32, arg0: u32, arg1: u32, arg2: u32) -> u32;
    fn __k16_iret_with_r0(value: u32) -> !;
    fn __k16_write_interrupt_enable(value: u32);
    fn __k16_write_interrupt_mask(value: u32);
    fn __k16_read_interrupt_pending() -> u32;
}

#[cfg(any(test, feature = "host-test"))]
use core::sync::atomic::{AtomicU32, Ordering};

#[cfg(any(test, feature = "host-test"))]
static TEST_TRAP_VECTOR: AtomicU32 = AtomicU32::new(0);
#[cfg(any(test, feature = "host-test"))]
static TEST_TRAP_CAUSE: AtomicU32 = AtomicU32::new(0);
#[cfg(any(test, feature = "host-test"))]
static TEST_TRAP_PC: AtomicU32 = AtomicU32::new(0);
#[cfg(any(test, feature = "host-test"))]
static TEST_TRAP_VALUE: AtomicU32 = AtomicU32::new(0);
#[cfg(any(test, feature = "host-test"))]
static TEST_SYSCALL_NUMBER: AtomicU32 = AtomicU32::new(0);
#[cfg(any(test, feature = "host-test"))]
static TEST_SYSCALL_ARG0: AtomicU32 = AtomicU32::new(0);
#[cfg(any(test, feature = "host-test"))]
static TEST_SYSCALL_ARG1: AtomicU32 = AtomicU32::new(0);
#[cfg(any(test, feature = "host-test"))]
static TEST_SYSCALL_ARG2: AtomicU32 = AtomicU32::new(0);
#[cfg(any(test, feature = "host-test"))]
static TEST_SYSCALL_RETURN: AtomicU32 = AtomicU32::new(0);
#[cfg(any(test, feature = "host-test"))]
static TEST_INTERRUPT_ENABLE: AtomicU32 = AtomicU32::new(0);
#[cfg(any(test, feature = "host-test"))]
static TEST_INTERRUPT_MASK: AtomicU32 = AtomicU32::new(0);
#[cfg(any(test, feature = "host-test"))]
static TEST_INTERRUPT_PENDING: AtomicU32 = AtomicU32::new(0);

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub unsafe fn install_trap_vector(address: u32) {
    unsafe {
        __k16_write_trap_vector(address);
    }
}

#[cfg(any(test, feature = "host-test"))]
pub unsafe fn install_trap_vector(address: u32) {
    TEST_TRAP_VECTOR.store(address, Ordering::Relaxed);
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn trap_cause() -> u32 {
    unsafe { __k16_read_trap_cause() }
}

#[cfg(any(test, feature = "host-test"))]
pub fn trap_cause() -> u32 {
    TEST_TRAP_CAUSE.load(Ordering::Relaxed)
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn trap_pc() -> u32 {
    unsafe { __k16_read_trap_pc() }
}

#[cfg(any(test, feature = "host-test"))]
pub fn trap_pc() -> u32 {
    TEST_TRAP_PC.load(Ordering::Relaxed)
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn trap_value() -> u32 {
    unsafe { __k16_read_trap_value() }
}

#[cfg(any(test, feature = "host-test"))]
pub fn trap_value() -> u32 {
    TEST_TRAP_VALUE.load(Ordering::Relaxed)
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn syscall_arg0() -> u32 {
    unsafe { __k16_read_trap_arg0() }
}

#[cfg(any(test, feature = "host-test"))]
pub fn syscall_arg0() -> u32 {
    TEST_SYSCALL_ARG0.load(Ordering::Relaxed)
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn syscall_arg1() -> u32 {
    unsafe { __k16_read_trap_arg1() }
}

#[cfg(any(test, feature = "host-test"))]
pub fn syscall_arg1() -> u32 {
    TEST_SYSCALL_ARG1.load(Ordering::Relaxed)
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn syscall_arg2() -> u32 {
    unsafe { __k16_read_trap_arg2() }
}

#[cfg(any(test, feature = "host-test"))]
pub fn syscall_arg2() -> u32 {
    TEST_SYSCALL_ARG2.load(Ordering::Relaxed)
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn syscall_once(number: u32) {
    unsafe {
        __k16_syscall_once(number);
    }
}

#[cfg(any(test, feature = "host-test"))]
pub fn syscall_once(number: u32) {
    TEST_SYSCALL_NUMBER.store(number, Ordering::Relaxed);
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn syscall0(number: u32) -> u32 {
    unsafe { __k16_syscall0(number) }
}

#[cfg(any(test, feature = "host-test"))]
pub fn syscall0(number: u32) -> u32 {
    TEST_SYSCALL_NUMBER.store(number, Ordering::Relaxed);
    TEST_SYSCALL_RETURN.load(Ordering::Relaxed)
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn syscall1(number: u32, arg0: u32) -> u32 {
    unsafe { __k16_syscall1(number, arg0) }
}

#[cfg(any(test, feature = "host-test"))]
pub fn syscall1(number: u32, arg0: u32) -> u32 {
    TEST_SYSCALL_NUMBER.store(number, Ordering::Relaxed);
    TEST_SYSCALL_ARG0.store(arg0, Ordering::Relaxed);
    TEST_SYSCALL_RETURN.load(Ordering::Relaxed)
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn syscall3(number: u32, arg0: u32, arg1: u32, arg2: u32) -> u32 {
    unsafe { __k16_syscall3(number, arg0, arg1, arg2) }
}

#[cfg(any(test, feature = "host-test"))]
pub fn syscall3(number: u32, arg0: u32, arg1: u32, arg2: u32) -> u32 {
    TEST_SYSCALL_NUMBER.store(number, Ordering::Relaxed);
    TEST_SYSCALL_ARG0.store(arg0, Ordering::Relaxed);
    TEST_SYSCALL_ARG1.store(arg1, Ordering::Relaxed);
    TEST_SYSCALL_ARG2.store(arg2, Ordering::Relaxed);
    TEST_SYSCALL_RETURN.load(Ordering::Relaxed)
}

#[inline(always)]
pub fn debug_marker() -> u32 {
    syscall0(k16_abi::syscall::DEBUG_MARKER)
}

#[inline(always)]
pub fn debug_write_byte(byte: u8) -> u32 {
    syscall1(k16_abi::syscall::DEBUG_WRITE_BYTE, u32::from(byte))
}

#[inline(always)]
pub fn yield_syscall() -> u32 {
    syscall0(k16_abi::syscall::YIELD)
}

#[inline(always)]
pub fn sleep_ticks_syscall(ticks: u32) -> u32 {
    syscall1(k16_abi::syscall::SLEEP_TICKS, ticks)
}

#[inline(always)]
pub fn exit_syscall(status: u32) -> ! {
    let _ = syscall1(k16_abi::syscall::EXIT, status);
    crate::halt_forever()
}

#[inline(always)]
pub fn write_syscall(fd: u32, ptr: *const u8, len: usize) -> u32 {
    syscall3(k16_abi::syscall::WRITE, fd, ptr as usize as u32, len as u32)
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
    TEST_INTERRUPT_MASK.store(mask, Ordering::Relaxed);
}

#[cfg(not(any(test, feature = "host-test")))]
#[inline(always)]
pub fn interrupt_pending() -> u32 {
    unsafe { __k16_read_interrupt_pending() }
}

#[cfg(any(test, feature = "host-test"))]
pub fn interrupt_pending() -> u32 {
    TEST_INTERRUPT_PENDING.load(Ordering::Relaxed)
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
    TEST_INTERRUPT_ENABLE.store(1, Ordering::Relaxed);
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
    TEST_INTERRUPT_ENABLE.store(0, Ordering::Relaxed);
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
    TEST_TRAP_VECTOR.store(0, Ordering::Relaxed);
    TEST_TRAP_CAUSE.store(0, Ordering::Relaxed);
    TEST_TRAP_PC.store(0, Ordering::Relaxed);
    TEST_TRAP_VALUE.store(0, Ordering::Relaxed);
    TEST_SYSCALL_NUMBER.store(0, Ordering::Relaxed);
    TEST_SYSCALL_ARG0.store(0, Ordering::Relaxed);
    TEST_SYSCALL_ARG1.store(0, Ordering::Relaxed);
    TEST_SYSCALL_ARG2.store(0, Ordering::Relaxed);
    TEST_SYSCALL_RETURN.store(0, Ordering::Relaxed);
    TEST_INTERRUPT_ENABLE.store(0, Ordering::Relaxed);
    TEST_INTERRUPT_MASK.store(0, Ordering::Relaxed);
    TEST_INTERRUPT_PENDING.store(0, Ordering::Relaxed);
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn set_test_trap_state(cause: u32, pc: u32, value: u32) {
    TEST_TRAP_CAUSE.store(cause, Ordering::Relaxed);
    TEST_TRAP_PC.store(pc, Ordering::Relaxed);
    TEST_TRAP_VALUE.store(value, Ordering::Relaxed);
    TEST_INTERRUPT_PENDING.store(value, Ordering::Relaxed);
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn set_test_syscall_return(value: u32) {
    TEST_SYSCALL_RETURN.store(value, Ordering::Relaxed);
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn test_trap_vector() -> u32 {
    TEST_TRAP_VECTOR.load(Ordering::Relaxed)
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn test_syscall_number() -> u32 {
    TEST_SYSCALL_NUMBER.load(Ordering::Relaxed)
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn test_syscall_arg0() -> u32 {
    TEST_SYSCALL_ARG0.load(Ordering::Relaxed)
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn test_syscall_arg1() -> u32 {
    TEST_SYSCALL_ARG1.load(Ordering::Relaxed)
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn test_syscall_arg2() -> u32 {
    TEST_SYSCALL_ARG2.load(Ordering::Relaxed)
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn test_interrupt_enable() -> u32 {
    TEST_INTERRUPT_ENABLE.load(Ordering::Relaxed)
}

#[cfg(any(test, feature = "host-test"))]
pub(crate) fn test_interrupt_mask() -> u32 {
    TEST_INTERRUPT_MASK.load(Ordering::Relaxed)
}
