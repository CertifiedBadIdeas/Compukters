use k16_abi::computer::{hardware_id, profile};

use crate::{console, control, debug};

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
        Self {
            parts: k16_rt::timer0_game_ticks_parts(),
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

    pub fn write_decimal(self) {
        let mut digits = [0_u8; 20];

        append_word_bits(&mut digits, self.parts.high);
        append_word_bits(&mut digits, self.parts.low);
        write_decimal_digits(&digits);
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
    k16_rt::timer0_game_ticks_parts()
}

pub fn monotonic_nanos() -> U64Parts {
    k16_rt::timer0_monotonic_nanos_parts()
}

pub fn sleep_ticks(ticks: u32) {
    let target = TickInstant::now().checked_add(TickDuration::from_ticks(ticks));
    while !TickInstant::now().has_reached(target) {
        k16_rt::yield_once();
    }
}

fn append_word_bits(digits: &mut [u8; 20], word: u32) {
    let mut bit_index = 0;
    while bit_index < 32 {
        let bit = ((word >> (31 - bit_index)) & 1) as u8;
        double_decimal_digits_and_add_bit(digits, bit);
        bit_index += 1;
    }
}

fn double_decimal_digits_and_add_bit(digits: &mut [u8; 20], bit: u8) {
    let mut carry = bit;
    let mut index = digits.len();
    while index > 0 {
        index -= 1;
        let doubled = digits[index] * 2 + carry;
        if doubled >= 10 {
            digits[index] = doubled - 10;
            carry = 1;
        } else {
            digits[index] = doubled;
            carry = 0;
        }
    }
}

fn write_decimal_digits(digits: &[u8; 20]) {
    let mut started = false;
    let mut index = 0;
    while index < digits.len() {
        let digit = digits[index];
        if digit != 0 || started {
            console::write_byte(b'0' + digit);
            started = true;
        }
        index += 1;
    }
    if !started {
        console::write_byte(b'0');
    }
}

fn kernel_panic_forever() -> ! {
    debug::print_kernel_panic();
    control::set_panic();
    control::wait_forever()
}
