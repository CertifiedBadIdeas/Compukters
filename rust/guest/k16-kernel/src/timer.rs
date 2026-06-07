use k16_abi::computer::{hardware_id, profile};

use crate::{control, debug};

static mut TIMER0_IRQ_SOURCE: u32 = 0;

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

pub fn game_ticks() -> u64 {
    k16_rt::timer0_game_ticks()
}

pub fn sleep_ticks(ticks: u32) {
    let target = game_ticks().saturating_add(u64::from(ticks));
    while game_ticks() < target {
        k16_rt::yield_once();
    }
}

fn kernel_panic_forever() -> ! {
    debug::print_kernel_panic();
    control::set_panic();
    control::wait_forever()
}
