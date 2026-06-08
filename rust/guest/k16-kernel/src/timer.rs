use k16_abi::computer::{hardware_id, profile, timer0};

use crate::{control, debug, mmio};

static mut TIMER0_IRQ_SOURCE: u32 = 0;

#[derive(Copy, Clone)]
pub struct U64Parts {
    pub high: u32,
    pub low: u32,
}

pub fn register_driver() -> u32 {
    let timer0 = unsafe { profile::find_hardware_entry(hardware_id::TIMER0) };
    let Some(timer0) = timer0 else {
        kernel_panic_forever();
    };
    if timer0.irq_source == 0 {
        kernel_panic_forever();
    }
    unsafe {
        TIMER0_IRQ_SOURCE = timer0.irq_source;
    }
    timer0.irq_source
}

pub fn handles_interrupt(source: u32) -> bool {
    (unsafe { TIMER0_IRQ_SOURCE }) == source
}

pub fn handle_interrupt() {
    debug::print_byte(b'|');
}

pub fn game_ticks() -> U64Parts {
    read_split_u64_parts(timer0::GAME_TICKS_LOW, timer0::GAME_TICKS_HIGH)
}

pub fn sleep_ticks(ticks: u32) {
    let target = add_ticks(game_ticks(), ticks);
    while !has_reached(game_ticks(), target) {
        k16_rt::yield_once();
    }
}

fn read_split_u64_parts(low_addr: u32, high_addr: u32) -> U64Parts {
    loop {
        let high_before = read_mmio_u32(high_addr);
        let low = read_mmio_u32(low_addr);
        let high_after = read_mmio_u32(high_addr);
        if high_before == high_after {
            return U64Parts {
                high: high_after,
                low,
            };
        }
    }
}

fn read_mmio_u32(address: u32) -> u32 {
    unsafe { mmio::read_i32(address) as u32 }
}

fn add_ticks(start: U64Parts, ticks: u32) -> U64Parts {
    let (low, carry) = start.low.overflowing_add(ticks);
    if carry {
        if start.high == u32::MAX {
            return U64Parts {
                high: u32::MAX,
                low: u32::MAX,
            };
        }
        return U64Parts {
            high: start.high + 1,
            low,
        };
    }
    U64Parts {
        high: start.high,
        low,
    }
}

fn has_reached(now: U64Parts, target: U64Parts) -> bool {
    now.high > target.high || (now.high == target.high && now.low >= target.low)
}

fn kernel_panic_forever() -> ! {
    debug::print_kernel_panic();
    control::set_panic();
    control::wait_forever()
}
