#[cfg(test)]
const TEST_WRITES_CAPACITY: usize = 16;
#[cfg(test)]
const TEST_MMU0_STATUS_SCRIPT_CAPACITY: usize = 8;
#[cfg(test)]
static mut TEST_WRITES: TestWrites = TestWrites::empty();
#[cfg(test)]
static mut TEST_MMU0_RESULT: i32 = 0;
#[cfg(test)]
static mut TEST_MMU0_STATUS: i32 = k16_abi::computer::mmu0::STATUS_DONE;
#[cfg(test)]
static mut TEST_MMU0_ERROR: i32 = 0;
#[cfg(test)]
static mut TEST_MMU0_STATUS_SCRIPT: [i32; TEST_MMU0_STATUS_SCRIPT_CAPACITY] =
    [0; TEST_MMU0_STATUS_SCRIPT_CAPACITY];
#[cfg(test)]
static mut TEST_MMU0_STATUS_SCRIPT_LEN: usize = 0;
#[cfg(test)]
static mut TEST_MMU0_STATUS_SCRIPT_CURSOR: usize = 0;

#[cfg(test)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TestWrites {
    entries: [(u32, u32); TEST_WRITES_CAPACITY],
    len: usize,
}

#[cfg(test)]
impl TestWrites {
    const fn empty() -> Self {
        Self {
            entries: [(0, 0); TEST_WRITES_CAPACITY],
            len: 0,
        }
    }

    pub fn as_slice(&self) -> &[(u32, u32)] {
        &self.entries[..self.len]
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }
}

#[cfg(test)]
pub fn reset_test_state() {
    unsafe {
        TEST_WRITES = TestWrites::empty();
        TEST_MMU0_RESULT = 0;
        TEST_MMU0_STATUS = k16_abi::computer::mmu0::STATUS_DONE;
        TEST_MMU0_ERROR = 0;
        TEST_MMU0_STATUS_SCRIPT = [0; TEST_MMU0_STATUS_SCRIPT_CAPACITY];
        TEST_MMU0_STATUS_SCRIPT_LEN = 0;
        TEST_MMU0_STATUS_SCRIPT_CURSOR = 0;
    }
}

#[cfg(test)]
pub fn set_test_mmu0_result(result: u32, status: i32, error: i32) {
    unsafe {
        TEST_MMU0_RESULT = result as i32;
        TEST_MMU0_STATUS = status;
        TEST_MMU0_ERROR = error;
        TEST_WRITES = TestWrites::empty();
        TEST_MMU0_STATUS_SCRIPT = [0; TEST_MMU0_STATUS_SCRIPT_CAPACITY];
        TEST_MMU0_STATUS_SCRIPT_LEN = 0;
        TEST_MMU0_STATUS_SCRIPT_CURSOR = 0;
    }
}

#[cfg(test)]
pub fn set_test_mmu0_status_script(result: u32, statuses: &[i32]) {
    unsafe {
        TEST_MMU0_RESULT = result as i32;
        TEST_MMU0_ERROR = 0;
        TEST_MMU0_STATUS_SCRIPT = [0; TEST_MMU0_STATUS_SCRIPT_CAPACITY];
        TEST_MMU0_STATUS_SCRIPT_LEN = statuses.len().min(TEST_MMU0_STATUS_SCRIPT_CAPACITY);
        TEST_MMU0_STATUS_SCRIPT_CURSOR = 0;
        let mut index = 0;
        while index < TEST_MMU0_STATUS_SCRIPT_LEN {
            TEST_MMU0_STATUS_SCRIPT[index] = statuses[index];
            index += 1;
        }
        TEST_WRITES = TestWrites::empty();
    }
}

#[cfg(test)]
pub fn take_test_writes() -> TestWrites {
    unsafe {
        let writes = TEST_WRITES;
        TEST_WRITES = TestWrites::empty();
        writes
    }
}

#[cfg(not(test))]
pub unsafe fn read_i32(address: u32) -> i32 {
    unsafe { core::ptr::read_volatile(address as usize as *const i32) }
}

#[cfg(test)]
pub unsafe fn read_i32(address: u32) -> i32 {
    match address {
        k16_abi::computer::mmu0::STATUS => unsafe {
            if TEST_MMU0_STATUS_SCRIPT_CURSOR < TEST_MMU0_STATUS_SCRIPT_LEN {
                let status = TEST_MMU0_STATUS_SCRIPT[TEST_MMU0_STATUS_SCRIPT_CURSOR];
                TEST_MMU0_STATUS_SCRIPT_CURSOR += 1;
                status
            } else {
                TEST_MMU0_STATUS
            }
        },
        k16_abi::computer::mmu0::ERROR => unsafe { TEST_MMU0_ERROR },
        k16_abi::computer::mmu0::RESULT => unsafe { TEST_MMU0_RESULT },
        _ => 0,
    }
}

#[cfg(not(test))]
pub unsafe fn write_i32(address: u32, value: i32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut i32, value) }
}

#[cfg(test)]
pub unsafe fn write_i32(address: u32, value: i32) {
    unsafe {
        let len = TEST_WRITES.len;
        if len < TEST_WRITES_CAPACITY {
            TEST_WRITES.entries[len] = (address, u32::from_le_bytes(value.to_le_bytes()));
            TEST_WRITES.len = len + 1;
        }
    }
}

#[cfg(not(test))]
pub unsafe fn write_u8(address: u32, value: u8) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u8, value) }
}

#[cfg(test)]
pub unsafe fn write_u8(_address: u32, _value: u8) {}
