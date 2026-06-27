use k16_abi::computer::{hardware_id, profile};

use crate::{control, debug};

static mut TIMER0_IRQ_SOURCE: u32 = 0;

pub type U64Parts = k16_rt::U64Parts;

#[derive(Copy, Clone)]
pub struct TickInstant {
    parts: U64Parts,
}

#[derive(Copy, Clone)]
pub struct TickDuration {
    ticks: u32,
}

impl TickInstant {
    pub fn now() -> Self {
        let mut low = 0;
        let mut high = 0;
        read_game_ticks_words(&mut low, &mut high);
        Self {
            parts: U64Parts { high, low },
        }
    }

    pub fn checked_add(self, duration: TickDuration) -> Self {
        let (low, carry) = self.parts.low.overflowing_add(duration.ticks);
        if carry {
            if self.parts.high == u32::MAX {
                return Self {
                    parts: U64Parts {
                        high: u32::MAX,
                        low: u32::MAX,
                    },
                };
            }
            return Self {
                parts: U64Parts {
                    high: self.parts.high + 1,
                    low,
                },
            };
        }
        Self {
            parts: U64Parts {
                high: self.parts.high,
                low,
            },
        }
    }

    pub fn has_reached(self, deadline: Self) -> bool {
        self.parts.high > deadline.parts.high
            || (self.parts.high == deadline.parts.high && self.parts.low >= deadline.parts.low)
    }
}

impl TickDuration {
    pub fn from_ticks(ticks: u32) -> Self {
        Self { ticks }
    }
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

pub fn now_ticks() -> TickInstant {
    TickInstant::now()
}

pub fn game_ticks() -> U64Parts {
    let mut low = 0;
    let mut high = 0;
    read_game_ticks_words(&mut low, &mut high);
    U64Parts { high, low }
}

pub fn read_game_ticks_words(low: &mut u32, high: &mut u32) {
    loop {
        let high_before = k16_rt::timer0_game_ticks_high();
        let current_low = k16_rt::timer0_game_ticks_low();
        let high_after = k16_rt::timer0_game_ticks_high();
        if high_before == high_after {
            *low = current_low;
            *high = high_after;
            return;
        }
    }
}

pub fn monotonic_nanos() -> U64Parts {
    k16_rt::timer0_monotonic_nanos_parts()
}

pub fn sleep_ticks(ticks: u32) {
    let target = TickInstant::now().checked_add(TickDuration::from_ticks(ticks));
    while !TickInstant::now().has_reached(target) {
        k16_rt::wait_once();
    }
}

fn kernel_panic_forever() -> ! {
    debug::print_kernel_panic();
    control::set_panic();
    control::wait_forever()
}
