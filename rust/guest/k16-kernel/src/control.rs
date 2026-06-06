use k16_abi::computer::{control as control_mmio, status};

use crate::mmio;

pub fn set_ready() {
    unsafe {
        mmio::write_i32(control_mmio::PANIC_CODE, 0);
        mmio::write_i32(control_mmio::STATUS, status::READY);
    }
}

pub fn set_panic() {
    unsafe {
        mmio::write_i32(control_mmio::PANIC_CODE, status::PANIC);
        mmio::write_i32(control_mmio::STATUS, status::PANIC);
    }
}

pub fn wait_forever() -> ! {
    k16_rt::halt_forever()
}
