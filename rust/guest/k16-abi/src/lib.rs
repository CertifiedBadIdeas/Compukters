#![no_std]

use core::marker::PhantomData;

#[derive(Clone, Copy)]
pub struct Mmio<T> {
    address: u32,
    _marker: PhantomData<*mut T>,
}

impl<T> Mmio<T> {
    pub const fn address(self) -> u32 {
        self.address
    }

    pub const fn as_ptr(self) -> *mut T {
        self.address as usize as *mut T
    }

    pub unsafe fn read(self) -> T
    where
        T: Copy,
    {
        unsafe { self.as_ptr().read_volatile() }
    }

    pub unsafe fn write(self, value: T) {
        unsafe { self.as_ptr().write_volatile(value) }
    }
}

pub const unsafe fn mmio<T>(address: u32) -> Mmio<T> {
    Mmio {
        address,
        _marker: PhantomData,
    }
}

pub mod computer {
    pub mod control {
        pub const BASE: u32 = 0x1000_0000;
        pub const STATUS: u32 = 0x1000_0000;
        pub const PANIC_CODE: u32 = 0x1000_0004;
        pub const EXIT_CODE: u32 = 0x1000_0008;
        pub const SIZE: u32 = 256;
    }

    pub mod status {
        pub const RESET: i32 = 0;
        pub const BOOTING: i32 = 1;
        pub const READY: i32 = 2;
        pub const HALTED: i32 = 3;
        pub const PANIC: i32 = 4;
    }

    pub mod debug {
        pub const BASE: u32 = 0x1000_0100;
        pub const WRITE: u32 = 0x1000_0100;
        pub const SIZE: u32 = 256;
    }

    pub mod serial_input {
        pub const BASE: u32 = 0x1000_0200;
        pub const READY: u32 = 0x1000_0200;
        pub const READ: u32 = 0x1000_0204;
        pub const SIZE: u32 = 256;
    }

    pub mod display0 {
        pub const BASE: u32 = 0x1000_0300;
        pub const COLUMNS: u32 = 0x1000_0300;
        pub const ROWS: u32 = 0x1000_0304;
        pub const CURSOR_X: u32 = 0x1000_0308;
        pub const CURSOR_Y: u32 = 0x1000_030c;
        pub const COMMAND: u32 = 0x1000_0310;
        pub const DATA: u32 = 0x1000_0314;
        pub const SEQUENCE_LOW: u32 = 0x1000_0318;
        pub const SEQUENCE_HIGH: u32 = 0x1000_031c;
        pub const SIZE: u32 = 256;

        pub const COMMAND_CLEAR: i32 = 1;
        pub const COMMAND_PUT_BYTE_AT_CURSOR: i32 = 2;
        pub const COMMAND_PUT_BYTE_AT_XY: i32 = 3;
        pub const COMMAND_NEWLINE: i32 = 4;
    }

    pub mod storage0 {
        pub const BASE: u32 = 0x1000_0400;
        pub const VERSION: u32 = 0x1000_0400;
        pub const STATUS: u32 = 0x1000_0404;
        pub const ERROR: u32 = 0x1000_0408;
        pub const COMMAND: u32 = 0x1000_040c;
        pub const BLOCK_SIZE: u32 = 0x1000_0410;
        pub const CAPACITY_BLOCKS_LOW: u32 = 0x1000_0414;
        pub const CAPACITY_BLOCKS_HIGH: u32 = 0x1000_0418;
        pub const LBA_LOW: u32 = 0x1000_041c;
        pub const LBA_HIGH: u32 = 0x1000_0420;
        pub const BLOCK_COUNT: u32 = 0x1000_0424;
        pub const BUFFER_ADDR: u32 = 0x1000_0428;
        pub const BYTES_DONE: u32 = 0x1000_042c;
        pub const SEQUENCE_LOW: u32 = 0x1000_0430;
        pub const SEQUENCE_HIGH: u32 = 0x1000_0434;
        pub const MEDIA_STATUS: u32 = 0x1000_0438;
        pub const SIZE: u32 = 256;

        pub const STORAGE_VERSION: i32 = 1;

        pub const STATUS_READY: i32 = 0;
        pub const STATUS_BUSY: i32 = 1;
        pub const STATUS_DONE: i32 = 2;
        pub const STATUS_ERROR: i32 = 3;

        pub const ERROR_NONE: i32 = 0;
        pub const ERROR_INVALID_COMMAND: i32 = 1;
        pub const ERROR_MEDIA_ABSENT: i32 = 2;
        pub const ERROR_BUFFER_OUT_OF_BOUNDS: i32 = 3;
        pub const ERROR_LBA_OUT_OF_BOUNDS: i32 = 4;
        pub const ERROR_BYTE_COUNT_OVERFLOW: i32 = 5;
        pub const ERROR_WRITE_PROTECTED: i32 = 6;
        pub const ERROR_IO_ERROR: i32 = 7;

        pub const COMMAND_NOP: i32 = 0;
        pub const COMMAND_READ_BLOCKS: i32 = 1;
        pub const COMMAND_WRITE_BLOCKS: i32 = 2;
        pub const COMMAND_FLUSH: i32 = 3;

        pub const MEDIA_ABSENT: i32 = 0;
        pub const MEDIA_PRESENT: i32 = 1;
        pub const MEDIA_READ_ONLY: i32 = 2;
        pub const MEDIA_ERROR: i32 = 3;
    }

    pub mod framebuffer0 {
        pub const BASE: u32 = 0x1000_0500;
        pub const WIDTH: u32 = 0x1000_0500;
        pub const HEIGHT: u32 = 0x1000_0504;
        pub const STRIDE_BYTES: u32 = 0x1000_0508;
        pub const PIXEL_FORMAT: u32 = 0x1000_050c;
        pub const COMMAND: u32 = 0x1000_0510;
        pub const STATUS: u32 = 0x1000_0514;
        pub const ERROR: u32 = 0x1000_0518;
        pub const X: u32 = 0x1000_051c;
        pub const Y: u32 = 0x1000_0520;
        pub const RECT_WIDTH: u32 = 0x1000_0524;
        pub const RECT_HEIGHT: u32 = 0x1000_0528;
        pub const BUFFER_ADDR: u32 = 0x1000_052c;
        pub const BUFFER_STRIDE_BYTES: u32 = 0x1000_0530;
        pub const COLOR: u32 = 0x1000_0534;
        pub const SEQUENCE_LOW: u32 = 0x1000_0538;
        pub const SEQUENCE_HIGH: u32 = 0x1000_053c;
        pub const SIZE: u32 = 256;

        pub const PIXEL_FORMAT_RGB565: i32 = 1;

        pub const STATUS_READY: i32 = 0;
        pub const STATUS_DONE: i32 = 1;
        pub const STATUS_ERROR: i32 = 2;

        pub const ERROR_NONE: i32 = 0;
        pub const ERROR_INVALID_COMMAND: i32 = 1;
        pub const ERROR_BUFFER_OUT_OF_BOUNDS: i32 = 2;
        pub const ERROR_INVALID_RECT: i32 = 3;
        pub const ERROR_INVALID_STRIDE: i32 = 4;

        pub const COMMAND_NOP: i32 = 0;
        pub const COMMAND_CLEAR: i32 = 1;
        pub const COMMAND_BLIT_BUFFER: i32 = 2;
        pub const COMMAND_PRESENT: i32 = 3;
    }

    pub mod memory {
        pub const RAM_BASE: u32 = 0x0000_0000;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn computer_mmio_constants_match_current_k16_profile() {
        assert_eq!(computer::control::BASE, 0x1000_0000);
        assert_eq!(computer::debug::WRITE, 0x1000_0100);
        assert_eq!(computer::serial_input::READ, 0x1000_0204);
        assert_eq!(computer::display0::COMMAND, 0x1000_0310);
        assert_eq!(computer::storage0::COMMAND, 0x1000_040c);
        assert_eq!(computer::framebuffer0::COMMAND, 0x1000_0510);
    }

    #[test]
    fn mmio_helper_preserves_typed_address() {
        let register = unsafe { mmio::<u32>(computer::display0::DATA) };

        assert_eq!(register.as_ptr() as usize, 0x1000_0314);
    }
}
