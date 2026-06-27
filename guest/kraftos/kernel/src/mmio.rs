#[cfg(test)]
const TEST_WRITES_CAPACITY: usize = 16;
#[cfg(test)]
const TEST_MMU0_STATUS_SCRIPT_CAPACITY: usize = 8;
#[cfg(test)]
use std::cell::RefCell;

#[cfg(test)]
std::thread_local! {
    static TEST_STATE: RefCell<TestState> = const { RefCell::new(TestState::empty()) };
}

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
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct TestState {
    writes: TestWrites,
    mmu0_result: i32,
    mmu0_status: i32,
    mmu0_error: i32,
    mmu0_status_script: [i32; TEST_MMU0_STATUS_SCRIPT_CAPACITY],
    mmu0_status_script_len: usize,
    mmu0_status_script_cursor: usize,
}

#[cfg(test)]
impl TestState {
    const fn empty() -> Self {
        Self {
            writes: TestWrites::empty(),
            mmu0_result: 0,
            mmu0_status: k16_abi::computer::mmu0::STATUS_DONE,
            mmu0_error: 0,
            mmu0_status_script: [0; TEST_MMU0_STATUS_SCRIPT_CAPACITY],
            mmu0_status_script_len: 0,
            mmu0_status_script_cursor: 0,
        }
    }

    fn set_mmu0_result(&mut self, result: u32, status: i32, error: i32) {
        self.mmu0_result = result as i32;
        self.mmu0_status = status;
        self.mmu0_error = error;
        self.writes = TestWrites::empty();
        self.mmu0_status_script = [0; TEST_MMU0_STATUS_SCRIPT_CAPACITY];
        self.mmu0_status_script_len = 0;
        self.mmu0_status_script_cursor = 0;
    }
}

#[cfg(test)]
pub fn reset_test_state() {
    TEST_STATE.with(|state| *state.borrow_mut() = TestState::empty());
}

#[cfg(test)]
pub fn set_test_mmu0_result(result: u32, status: i32, error: i32) {
    TEST_STATE.with(|state| state.borrow_mut().set_mmu0_result(result, status, error));
}

#[cfg(test)]
pub fn set_test_mmu0_status_script(result: u32, statuses: &[i32]) {
    TEST_STATE.with(|state| {
        let mut state = state.borrow_mut();
        state.mmu0_result = result as i32;
        state.mmu0_error = 0;
        state.mmu0_status_script = [0; TEST_MMU0_STATUS_SCRIPT_CAPACITY];
        state.mmu0_status_script_len = statuses.len().min(TEST_MMU0_STATUS_SCRIPT_CAPACITY);
        state.mmu0_status_script_cursor = 0;
        let mut index = 0;
        while index < state.mmu0_status_script_len {
            state.mmu0_status_script[index] = statuses[index];
            index += 1;
        }
        state.writes = TestWrites::empty();
    });
}

#[cfg(test)]
pub fn take_test_writes() -> TestWrites {
    TEST_STATE.with(|state| {
        let mut state = state.borrow_mut();
        let writes = state.writes;
        state.writes = TestWrites::empty();
        writes
    })
}

#[cfg(not(test))]
pub unsafe fn read_i32(address: u32) -> i32 {
    unsafe { core::ptr::read_volatile(address as usize as *const i32) }
}

#[cfg(test)]
pub unsafe fn read_i32(address: u32) -> i32 {
    TEST_STATE.with(|state| {
        let mut state = state.borrow_mut();
        match address {
            k16_abi::computer::mmu0::STATUS => {
                if state.mmu0_status_script_cursor < state.mmu0_status_script_len {
                    let status = state.mmu0_status_script[state.mmu0_status_script_cursor];
                    state.mmu0_status_script_cursor += 1;
                    status
                } else {
                    state.mmu0_status
                }
            }
            k16_abi::computer::mmu0::ERROR => state.mmu0_error,
            k16_abi::computer::mmu0::RESULT => state.mmu0_result,
            _ => 0,
        }
    })
}

#[cfg(not(test))]
pub unsafe fn write_i32(address: u32, value: i32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut i32, value) }
}

#[cfg(test)]
pub unsafe fn write_i32(address: u32, value: i32) {
    TEST_STATE.with(|state| {
        let mut state = state.borrow_mut();
        let len = state.writes.len;
        if len < TEST_WRITES_CAPACITY {
            state.writes.entries[len] = (address, u32::from_le_bytes(value.to_le_bytes()));
            state.writes.len = len + 1;
        }
    });
}

#[cfg(not(test))]
pub unsafe fn write_u8(address: u32, value: u8) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u8, value) }
}

#[cfg(test)]
pub unsafe fn write_u8(_address: u32, _value: u8) {}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Barrier};

    #[test]
    fn test_state_is_isolated_between_host_threads() {
        let left_ready = Arc::new(Barrier::new(2));
        let right_done = Arc::new(Barrier::new(2));

        let left = std::thread::spawn({
            let left_ready = Arc::clone(&left_ready);
            let right_done = Arc::clone(&right_done);
            move || {
                reset_test_state();
                set_test_mmu0_result(7, k16_abi::computer::mmu0::STATUS_DONE, 0);
                unsafe { write_i32(0x100, 0x11) };

                left_ready.wait();
                right_done.wait();

                let result = unsafe { read_i32(k16_abi::computer::mmu0::RESULT) };
                let writes = take_test_writes();
                (result, writes)
            }
        });

        let right = std::thread::spawn({
            let left_ready = Arc::clone(&left_ready);
            let right_done = Arc::clone(&right_done);
            move || {
                left_ready.wait();

                reset_test_state();
                set_test_mmu0_result(3, k16_abi::computer::mmu0::STATUS_DONE, 0);
                unsafe { write_i32(0x200, 0x22) };

                let result = unsafe { read_i32(k16_abi::computer::mmu0::RESULT) };
                let writes = take_test_writes();
                right_done.wait();
                (result, writes)
            }
        });

        let right = right.join().expect("right test thread exits");
        let left = left.join().expect("left test thread exits");

        assert_eq!(left.0, 7);
        assert_eq!(left.1.as_slice(), &[(0x100, 0x11)]);
        assert_eq!(right.0, 3);
        assert_eq!(right.1.as_slice(), &[(0x200, 0x22)]);
    }
}
