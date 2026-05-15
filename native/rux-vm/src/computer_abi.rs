pub const RAM_BASE: u32 = 0x0000_0000;

pub const CONTROL_BASE: u32 = 0x1000_0000;
pub const CONTROL_STATUS: u32 = CONTROL_BASE;
pub const CONTROL_PANIC_CODE: u32 = CONTROL_BASE + 4;
pub const CONTROL_EXIT_CODE: u32 = CONTROL_BASE + 8;
pub const CONTROL_SIZE: u32 = 12;

pub const DEBUG_BASE: u32 = 0x1000_0100;
pub const DEBUG_WRITE: u32 = DEBUG_BASE;
pub const DEBUG_SIZE: u32 = 4;

pub const SERIAL_INPUT_BASE: u32 = 0x1000_0200;
pub const SERIAL_INPUT_READY: u32 = SERIAL_INPUT_BASE;
pub const SERIAL_INPUT_READ: u32 = SERIAL_INPUT_BASE + 4;
pub const SERIAL_INPUT_SIZE: u32 = 8;

pub const STATUS_RESET: i32 = 0;
pub const STATUS_BOOTING: i32 = 1;
pub const STATUS_READY: i32 = 2;
pub const STATUS_HALTED: i32 = 3;
pub const STATUS_PANIC: i32 = 4;
