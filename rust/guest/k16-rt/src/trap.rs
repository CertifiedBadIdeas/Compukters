#[cfg(not(test))]
extern "C" {
    fn __k16_iret_once() -> !;
    fn __k16_write_trap_vector(value: u32);
    fn __k16_read_trap_cause() -> u32;
    fn __k16_read_trap_pc() -> u32;
    fn __k16_read_trap_value() -> u32;
    fn __k16_syscall_once(number: u32);
    fn __k16_write_interrupt_enable(value: u32);
    fn __k16_write_interrupt_mask(value: u32);
    fn __k16_read_interrupt_pending() -> u32;
}

#[cfg(test)]
use core::sync::atomic::{AtomicU32, Ordering};

#[cfg(test)]
static TEST_TRAP_VECTOR: AtomicU32 = AtomicU32::new(0);
#[cfg(test)]
static TEST_TRAP_CAUSE: AtomicU32 = AtomicU32::new(0);
#[cfg(test)]
static TEST_TRAP_PC: AtomicU32 = AtomicU32::new(0);
#[cfg(test)]
static TEST_TRAP_VALUE: AtomicU32 = AtomicU32::new(0);
#[cfg(test)]
static TEST_SYSCALL_NUMBER: AtomicU32 = AtomicU32::new(0);
#[cfg(test)]
static TEST_INTERRUPT_ENABLE: AtomicU32 = AtomicU32::new(0);
#[cfg(test)]
static TEST_INTERRUPT_MASK: AtomicU32 = AtomicU32::new(0);
#[cfg(test)]
static TEST_INTERRUPT_PENDING: AtomicU32 = AtomicU32::new(0);

#[cfg(not(test))]
#[inline(always)]
pub unsafe fn install_trap_vector(address: u32) {
    unsafe {
        __k16_write_trap_vector(address);
    }
}

#[cfg(test)]
pub unsafe fn install_trap_vector(address: u32) {
    TEST_TRAP_VECTOR.store(address, Ordering::Relaxed);
}

#[cfg(not(test))]
#[inline(always)]
pub fn trap_cause() -> u32 {
    unsafe { __k16_read_trap_cause() }
}

#[cfg(test)]
pub fn trap_cause() -> u32 {
    TEST_TRAP_CAUSE.load(Ordering::Relaxed)
}

#[cfg(not(test))]
#[inline(always)]
pub fn trap_pc() -> u32 {
    unsafe { __k16_read_trap_pc() }
}

#[cfg(test)]
pub fn trap_pc() -> u32 {
    TEST_TRAP_PC.load(Ordering::Relaxed)
}

#[cfg(not(test))]
#[inline(always)]
pub fn trap_value() -> u32 {
    unsafe { __k16_read_trap_value() }
}

#[cfg(test)]
pub fn trap_value() -> u32 {
    TEST_TRAP_VALUE.load(Ordering::Relaxed)
}

#[cfg(not(test))]
#[inline(always)]
pub fn syscall_once(number: u32) {
    unsafe {
        __k16_syscall_once(number);
    }
}

#[cfg(test)]
pub fn syscall_once(number: u32) {
    TEST_SYSCALL_NUMBER.store(number, Ordering::Relaxed);
}

#[cfg(not(test))]
#[inline(always)]
pub unsafe fn set_interrupt_mask(mask: u32) {
    unsafe {
        __k16_write_interrupt_mask(mask);
    }
}

#[cfg(test)]
pub unsafe fn set_interrupt_mask(mask: u32) {
    TEST_INTERRUPT_MASK.store(mask, Ordering::Relaxed);
}

#[cfg(not(test))]
#[inline(always)]
pub fn interrupt_pending() -> u32 {
    unsafe { __k16_read_interrupt_pending() }
}

#[cfg(test)]
pub fn interrupt_pending() -> u32 {
    TEST_INTERRUPT_PENDING.load(Ordering::Relaxed)
}

#[cfg(not(test))]
#[inline(always)]
pub unsafe fn enable_interrupts() {
    unsafe {
        __k16_write_interrupt_enable(1);
    }
}

#[cfg(test)]
pub unsafe fn enable_interrupts() {
    TEST_INTERRUPT_ENABLE.store(1, Ordering::Relaxed);
}

#[cfg(not(test))]
#[inline(always)]
pub fn disable_interrupts() {
    unsafe {
        __k16_write_interrupt_enable(0);
    }
}

#[cfg(test)]
pub fn disable_interrupts() {
    TEST_INTERRUPT_ENABLE.store(0, Ordering::Relaxed);
}

#[cfg(not(test))]
#[inline(always)]
pub unsafe fn iret_once() -> ! {
    unsafe { __k16_iret_once() }
}

#[cfg(test)]
pub unsafe fn iret_once() -> ! {
    panic!("k16 interrupt return is only available on the K16 target")
}

#[cfg(test)]
pub(crate) fn reset_test_interrupts() {
    TEST_TRAP_VECTOR.store(0, Ordering::Relaxed);
    TEST_TRAP_CAUSE.store(0, Ordering::Relaxed);
    TEST_TRAP_PC.store(0, Ordering::Relaxed);
    TEST_TRAP_VALUE.store(0, Ordering::Relaxed);
    TEST_SYSCALL_NUMBER.store(0, Ordering::Relaxed);
    TEST_INTERRUPT_ENABLE.store(0, Ordering::Relaxed);
    TEST_INTERRUPT_MASK.store(0, Ordering::Relaxed);
    TEST_INTERRUPT_PENDING.store(0, Ordering::Relaxed);
}

#[cfg(test)]
pub(crate) fn set_test_trap_state(cause: u32, pc: u32, value: u32) {
    TEST_TRAP_CAUSE.store(cause, Ordering::Relaxed);
    TEST_TRAP_PC.store(pc, Ordering::Relaxed);
    TEST_TRAP_VALUE.store(value, Ordering::Relaxed);
    TEST_INTERRUPT_PENDING.store(value, Ordering::Relaxed);
}

#[cfg(test)]
pub(crate) fn test_trap_vector() -> u32 {
    TEST_TRAP_VECTOR.load(Ordering::Relaxed)
}

#[cfg(test)]
pub(crate) fn test_syscall_number() -> u32 {
    TEST_SYSCALL_NUMBER.load(Ordering::Relaxed)
}

#[cfg(test)]
pub(crate) fn test_interrupt_enable() -> u32 {
    TEST_INTERRUPT_ENABLE.load(Ordering::Relaxed)
}

#[cfg(test)]
pub(crate) fn test_interrupt_mask() -> u32 {
    TEST_INTERRUPT_MASK.load(Ordering::Relaxed)
}
