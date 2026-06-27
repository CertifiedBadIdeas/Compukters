use k16_abi::syscall as abi_syscall;

use crate::{mmio, process};

#[cfg(test)]
const TEST_PHYSICAL_CAPACITY: usize = 64;
#[cfg(test)]
static mut TEST_PHYSICAL_BASE: u32 = 0;
#[cfg(test)]
static mut TEST_PHYSICAL_BYTES: [u8; TEST_PHYSICAL_CAPACITY] = [0; TEST_PHYSICAL_CAPACITY];
#[cfg(test)]
static TEST_MMU0_YIELD_COUNT: core::sync::atomic::AtomicU64 = core::sync::atomic::AtomicU64::new(0);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct UserBytes<const MAX: usize> {
    bytes: [u8; MAX],
    len: usize,
}

impl<const MAX: usize> UserBytes<MAX> {
    pub fn as_slice(&self) -> &[u8] {
        let (slice, _) = self.bytes.split_at(self.len);
        slice
    }

    pub fn as_ptr(&self) -> *const u8 {
        self.bytes.as_ptr()
    }
}

pub fn copy_from_user<const MAX: usize>(ptr: u32, len: u32) -> Result<UserBytes<MAX>, u32> {
    let mut bytes = [0_u8; MAX];
    let len_usize = copy_from_user_into(ptr, len, &mut bytes)?.len();
    Ok(UserBytes {
        bytes,
        len: len_usize,
    })
}

pub fn copy_from_user_into<'a>(ptr: u32, len: u32, bytes: &'a mut [u8]) -> Result<&'a [u8], u32> {
    let len_usize = usize::try_from(len).map_err(|_| abi_syscall::ERROR_INVALID)?;
    if len_usize > bytes.len() {
        return Err(abi_syscall::ERROR_INVALID);
    }
    match unsafe { process::current_process_address_space() } {
        Some(address_space) => {
            run_mmu0_copy(
                address_space,
                ptr,
                bytes.as_mut_ptr() as usize as u32,
                len,
                k16_abi::computer::mmu0::COMMAND_COPY_FROM_USER,
            )?;
        }
        None => {
            if !valid_physical_user_buffer(ptr, len) {
                return Err(abi_syscall::ERROR_FAULT);
            }
            let mut offset = 0;
            while offset < len {
                bytes[offset as usize] = unsafe { physical_read_u8(ptr + offset) };
                offset += 1;
            }
        }
    }
    Ok(&bytes[..len_usize])
}

pub fn copy_to_user(ptr: u32, bytes: &[u8]) -> Result<u32, u32> {
    let len = u32::try_from(bytes.len()).map_err(|_| abi_syscall::ERROR_INVALID)?;
    match unsafe { process::current_process_address_space() } {
        Some(address_space) => {
            run_mmu0_copy(
                address_space,
                ptr,
                bytes.as_ptr() as usize as u32,
                len,
                k16_abi::computer::mmu0::COMMAND_COPY_TO_USER,
            )?;
        }
        None => {
            if !valid_physical_user_buffer(ptr, len) {
                return Err(abi_syscall::ERROR_FAULT);
            }
            let mut offset = 0;
            while offset < len {
                unsafe { physical_write_u8(ptr + offset, bytes[offset as usize]) };
                offset += 1;
            }
        }
    }
    Ok(len)
}

pub fn valid_user_buffer(ptr: u32, len: u32) -> bool {
    if unsafe { process::current_process_address_space() }.is_some() {
        return ptr.checked_add(len).is_some();
    }
    valid_physical_user_buffer(ptr, len)
}

fn valid_physical_user_buffer(ptr: u32, len: u32) -> bool {
    unsafe { process::current_process_contains_buffer(ptr, len) }
}

fn run_mmu0_copy(
    address_space: u32,
    virtual_start: u32,
    physical_start: u32,
    byte_count: u32,
    command: i32,
) -> Result<(), u32> {
    unsafe {
        mmio::write_i32(k16_abi::computer::mmu0::ADDRESS_SPACE, address_space as i32);
        mmio::write_i32(k16_abi::computer::mmu0::VIRTUAL_START, virtual_start as i32);
        mmio::write_i32(
            k16_abi::computer::mmu0::PHYSICAL_START,
            physical_start as i32,
        );
        mmio::write_i32(k16_abi::computer::mmu0::BYTE_COUNT, byte_count as i32);
        mmio::write_i32(k16_abi::computer::mmu0::COMMAND, command);
        yield_for_mmu0_command();
        if mmio::read_i32(k16_abi::computer::mmu0::STATUS) != k16_abi::computer::mmu0::STATUS_DONE {
            return Err(abi_syscall::ERROR_FAULT);
        }
        if mmio::read_i32(k16_abi::computer::mmu0::RESULT) as u32 != byte_count {
            return Err(abi_syscall::ERROR_FAULT);
        }
    }
    Ok(())
}

#[cfg(not(test))]
fn yield_for_mmu0_command() {
    k16_rt::yield_once();
}

#[cfg(test)]
fn yield_for_mmu0_command() {
    TEST_MMU0_YIELD_COUNT.fetch_add(1, core::sync::atomic::Ordering::Relaxed);
}

#[cfg(not(test))]
unsafe fn physical_read_u8(address: u32) -> u8 {
    unsafe { core::ptr::read_volatile(address as usize as *const u8) }
}

#[cfg(test)]
unsafe fn physical_read_u8(address: u32) -> u8 {
    let offset = unsafe { address - TEST_PHYSICAL_BASE } as usize;
    unsafe { TEST_PHYSICAL_BYTES[offset] }
}

#[cfg(not(test))]
unsafe fn physical_write_u8(address: u32, byte: u8) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u8, byte) };
}

#[cfg(test)]
unsafe fn physical_write_u8(address: u32, byte: u8) {
    let offset = unsafe { address - TEST_PHYSICAL_BASE } as usize;
    unsafe { TEST_PHYSICAL_BYTES[offset] = byte };
}

#[cfg(test)]
fn set_test_physical_bytes(base: u32, bytes: &[u8]) {
    unsafe {
        TEST_PHYSICAL_BASE = base;
        let mut offset = 0;
        while offset < bytes.len() {
            TEST_PHYSICAL_BYTES[offset] = bytes[offset];
            offset += 1;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use core::hint::spin_loop;
    use core::sync::atomic::{AtomicBool, Ordering};

    static TEST_LOCK: AtomicBool = AtomicBool::new(false);

    struct TestGuard;

    impl Drop for TestGuard {
        fn drop(&mut self) {
            process::set_current_process_address_space_for_tests(None);
            mmio::reset_test_state();
            TEST_LOCK.store(false, Ordering::Release);
        }
    }

    fn test_guard() -> TestGuard {
        while TEST_LOCK
            .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
            .is_err()
        {
            spin_loop();
        }
        process::set_current_process_address_space_for_tests(None);
        mmio::reset_test_state();
        TestGuard
    }

    #[test]
    fn copy_from_user_uses_physical_buffer_for_physical_process() {
        let _guard = test_guard();
        let ptr = 0x1200;
        set_test_physical_bytes(ptr, b"/bin/ls");
        process::set_current_process_address_space_for_tests(None);
        process::set_current_process_memory_for_tests(
            process::ProcessMemory::new(ptr, ptr + 7).unwrap(),
        );

        let copied = copy_from_user::<16>(ptr, 7).unwrap();

        assert_eq!(copied.as_slice(), b"/bin/ls");
        assert!(mmio::take_test_writes().is_empty());
    }

    #[test]
    fn copy_from_user_uses_mmu0_for_translated_process() {
        let _guard = test_guard();
        process::set_current_process_address_space_for_tests(Some(7));
        mmio::set_test_mmu0_result(3, k16_abi::computer::mmu0::STATUS_DONE, 0);

        let copied = copy_from_user::<8>(0x4000, 3).unwrap();

        assert_eq!(copied.as_slice(), &[0, 0, 0]);
        let writes = mmio::take_test_writes();
        let writes = writes.as_slice();
        assert_eq!(writes.len(), 5);
        assert_eq!(writes[0], (k16_abi::computer::mmu0::ADDRESS_SPACE, 7));
        assert_eq!(writes[1], (k16_abi::computer::mmu0::VIRTUAL_START, 0x4000));
        assert_eq!(writes[2].0, k16_abi::computer::mmu0::PHYSICAL_START);
        assert_ne!(writes[2].1, 0);
        assert_eq!(writes[3], (k16_abi::computer::mmu0::BYTE_COUNT, 3));
        assert_eq!(
            writes[4],
            (
                k16_abi::computer::mmu0::COMMAND,
                k16_abi::computer::mmu0::COMMAND_COPY_FROM_USER as u32,
            )
        );
    }

    #[test]
    fn copy_to_user_maps_mmu0_error_to_fault_for_translated_process() {
        let _guard = test_guard();
        process::set_current_process_address_space_for_tests(Some(9));
        mmio::set_test_mmu0_result(
            0,
            k16_abi::computer::mmu0::STATUS_ERROR,
            k16_abi::computer::mmu0::ERROR_TRANSLATION_FAULT,
        );

        let error = copy_to_user(0x8000, b"abc").unwrap_err();

        assert_eq!(error, abi_syscall::ERROR_FAULT);
    }

    #[test]
    fn copy_from_user_yields_for_host_mmu0_command_before_reading_status() {
        let _guard = test_guard();
        process::set_current_process_address_space_for_tests(Some(9));
        TEST_MMU0_YIELD_COUNT.store(0, Ordering::Relaxed);
        mmio::set_test_mmu0_result(
            0,
            k16_abi::computer::mmu0::STATUS_ERROR,
            k16_abi::computer::mmu0::ERROR_TRANSLATION_FAULT,
        );

        let error = copy_from_user::<8>(0xffff_f000, 1).unwrap_err();

        assert_eq!(error, abi_syscall::ERROR_FAULT);
        assert_eq!(TEST_MMU0_YIELD_COUNT.load(Ordering::Relaxed), 1);
    }
}
