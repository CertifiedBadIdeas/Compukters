use crate::computer_abi;
use crate::low_bus::MmioDevice;
use crate::low_machine::MemoryFault;
use std::time::Instant;

pub(crate) struct TimerDevice {
    game_ticks: u64,
    started_at: Instant,
}

impl TimerDevice {
    pub(crate) const SIZE: u32 = computer_abi::TIMER0_SIZE;

    pub(crate) fn new() -> Self {
        Self {
            game_ticks: 0,
            started_at: Instant::now(),
        }
    }

    pub(crate) fn game_ticks(&self) -> u64 {
        self.game_ticks
    }

    pub(crate) fn advance_game_tick(&mut self) {
        self.game_ticks = self.game_ticks.saturating_add(1);
    }

    pub(crate) fn restore_game_ticks(&mut self, game_ticks: u64) {
        self.game_ticks = game_ticks;
        self.started_at = Instant::now();
    }

    fn monotonic_nanos(&self) -> u64 {
        self.started_at
            .elapsed()
            .as_nanos()
            .min(u128::from(u64::MAX)) as u64
    }

    fn value_for_offset(&self, offset: u32) -> Result<i32, MemoryFault> {
        let value = match offset {
            0 => computer_abi::TIMER0_VERSION_VALUE as u32,
            4 => self.game_ticks as u32,
            8 => (self.game_ticks >> 32) as u32,
            12 => self.monotonic_nanos() as u32,
            16 => (self.monotonic_nanos() >> 32) as u32,
            _ => {
                return Err(MemoryFault::new(format!(
                    "timer0 offset {offset} is not mapped",
                )));
            }
        };
        Ok(i32::from_le_bytes(value.to_le_bytes()))
    }
}

impl MmioDevice for TimerDevice {
    fn size(&self) -> u32 {
        Self::SIZE
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        self.value_for_offset(offset)
    }

    fn store_i32(&mut self, offset: u32, _value: i32) -> Result<(), MemoryFault> {
        Err(MemoryFault::new(format!(
            "timer0 offset {offset} is read-only",
        )))
    }
}
