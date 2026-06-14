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

pub mod cpu {
    pub mod csr {
        pub const TRAP_VECTOR: u32 = 1;
        pub const TRAP_CAUSE: u32 = 2;
        pub const TRAP_PC: u32 = 3;
        pub const TRAP_VALUE: u32 = 4;
        pub const INTERRUPT_ENABLE: u32 = 5;
        pub const INTERRUPT_MASK: u32 = 6;
        pub const INTERRUPT_PENDING: u32 = 7;
        pub const TRAP_ARG0: u32 = 8;
        pub const TRAP_ARG1: u32 = 9;
        pub const TRAP_ARG2: u32 = 10;
        pub const TRAP_FRAME_INDEX: u32 = 11;
        pub const TRAP_FRAME_REGISTER: u32 = 12;
        pub const TRAP_RESUME_PC: u32 = 13;
        pub const TRAP_STACK_POINTER: u32 = 14;
        pub const TRAP_INTERRUPT_ENABLE: u32 = 15;
    }

    pub mod trap_cause {
        pub const ILLEGAL_INSTRUCTION: u32 = 1;
        pub const INSTRUCTION_FETCH_FAULT: u32 = 2;
        pub const LOAD_FAULT: u32 = 3;
        pub const STORE_FAULT: u32 = 4;
        pub const EXPLICIT_TRAP: u32 = 5;
        pub const TIMER0_INTERRUPT: u32 = 0x8000_0001;
        pub const KEYBOARD0_INTERRUPT: u32 = 0x8000_0002;

        pub const fn is_interrupt(cause: u32) -> bool {
            (cause as i32) < 0
        }

        pub const fn interrupt_source(cause: u32) -> u32 {
            (cause << 1) >> 1
        }
    }

    pub mod interrupt_source {
        pub const TIMER0: u32 = 0x0000_0001;
        pub const KEYBOARD0: u32 = 0x0000_0002;
    }
}

pub mod syscall {
    pub const DEBUG_MARKER: u32 = 2;
    pub const DEBUG_WRITE_BYTE: u32 = 3;
    pub const YIELD: u32 = 4;
    pub const SLEEP_TICKS: u32 = 5;
    pub const EXIT: u32 = 6;
    pub const WRITE: u32 = 7;
    pub const READ: u32 = 8;
    pub const RUN: u32 = 9;
    pub const OPEN: u32 = 10;
    pub const CLOSE: u32 = 11;
    pub const BRK: u32 = 12;
    pub const SBRK: u32 = 13;
    pub const READ_DIR: u32 = 14;
    pub const STAT: u32 = 15;
    pub const RUN_FORMAT_PATH: u32 = 0;
    pub const RUN_FORMAT_ARGV: u32 = 1;
    pub const RUN_ARGV_MAGIC: u32 = u32::from_le_bytes(*b"RARG");
    pub const MAX_RUN_ARGS: usize = 4;
    pub const MAX_RUN_PATH_BYTES: usize = 61;
    pub const MAX_RUN_ARG_BYTES: usize = 128;
    pub const MAX_RUN_ARGV_REQUEST_BYTES: usize =
        12 + MAX_RUN_ARGS * 4 + MAX_RUN_PATH_BYTES + MAX_RUN_ARGS * MAX_RUN_ARG_BYTES;
    pub const READ_DIR_REQUEST_MAGIC: u32 = u32::from_le_bytes(*b"RDIR");
    pub const MAX_READ_DIR_PATH_BYTES: usize = 228;
    pub const MAX_READ_DIR_REQUEST_BYTES: usize = 16 + MAX_READ_DIR_PATH_BYTES;
    pub const MAX_STAT_PATH_BYTES: usize = MAX_READ_DIR_PATH_BYTES;
    pub const STAT_METADATA_BYTES: usize = 16;
    pub const FILE_TYPE_REGULAR: u32 = 1;
    pub const FILE_TYPE_DIRECTORY: u32 = 2;
    pub const FD_STDIN: u32 = 0;
    pub const FD_STDOUT: u32 = 1;
    pub const FD_STDERR: u32 = 2;
    pub const ERROR_BAD_FD: u32 = 0xffff_fff7;
    pub const ERROR_BUSY: u32 = 0xffff_fff0;
    pub const ERROR_EXEC_FORMAT: u32 = 0xffff_fff8;
    pub const ERROR_FAULT: u32 = 0xffff_fff2;
    pub const ERROR_INVALID: u32 = 0xffff_ffea;
    pub const ERROR_NO_FD: u32 = 0xffff_ffe8;
    pub const ERROR_NO_ENTRY: u32 = 0xffff_fffe;
    pub const ERROR_NO_MEMORY: u32 = 0xffff_fff4;
    pub const DEBUG_MARKER_RETURN: u32 = 0x53;
    pub const STATUS_OK: u32 = 0;
}

pub mod computer {
    pub mod profile {
        pub const BOOT_INFO_MAGIC: u32 = u32::from_le_bytes(*b"RXBI");
        pub const VERSION: u32 = 2;
        pub const BOOT_INFO_ADDR: u32 = 0x0000_0000;
        pub const BOOT_INFO_SIZE: u32 = 28;
        pub const HARDWARE_ENTRY_SIZE: u32 = 16;

        #[derive(Clone, Copy, Debug, PartialEq, Eq)]
        pub struct BootInfo {
            pub ram_size: u32,
            pub page_size: u32,
            pub program_base: u32,
            pub hardware_table_addr: u32,
            pub hardware_count: u32,
        }

        #[derive(Clone, Copy, Debug, PartialEq, Eq)]
        pub struct HardwareEntry {
            pub id: u32,
            pub mmio_base: u32,
            pub mmio_size: u32,
            pub irq_source: u32,
        }

        pub unsafe fn read_boot_info() -> Option<BootInfo> {
            let base = BOOT_INFO_ADDR;
            let magic = unsafe { read_u32(base) };
            if magic != BOOT_INFO_MAGIC {
                return None;
            }
            let version = unsafe { read_u32(base + 4) };
            if version != VERSION {
                return None;
            }
            Some(BootInfo {
                ram_size: unsafe { read_u32(base + 8) },
                page_size: unsafe { read_u32(base + 12) },
                program_base: unsafe { read_u32(base + 16) },
                hardware_table_addr: unsafe { read_u32(base + 20) },
                hardware_count: unsafe { read_u32(base + 24) },
            })
        }

        pub unsafe fn read_hardware_entry(address: u32) -> HardwareEntry {
            HardwareEntry {
                id: unsafe { read_u32(address) },
                mmio_base: unsafe { read_u32(address + 4) },
                mmio_size: unsafe { read_u32(address + 8) },
                irq_source: unsafe { read_u32(address + 12) },
            }
        }

        pub unsafe fn find_hardware_entry(id: u32) -> Option<HardwareEntry> {
            let boot_info = unsafe { read_boot_info()? };
            let mut index = 0;
            while index < boot_info.hardware_count {
                let address = boot_info
                    .hardware_table_addr
                    .wrapping_add(index.wrapping_mul(HARDWARE_ENTRY_SIZE));
                let entry = unsafe { read_hardware_entry(address) };
                if entry.id == id {
                    return Some(entry);
                }
                index += 1;
            }
            None
        }

        unsafe fn read_u32(address: u32) -> u32 {
            unsafe { core::ptr::read_volatile(address as usize as *const u32) }
        }
    }

    pub mod hardware_id {
        pub const CONTROL: u32 = 1;
        pub const DEBUG: u32 = 2;
        pub const SERIAL_INPUT: u32 = 3;
        pub const STORAGE0: u32 = 5;
        pub const GPU0: u32 = 6;
        pub const TIMER0: u32 = 7;
        pub const KEYBOARD0: u32 = 8;
        pub const MMU0: u32 = 9;
    }

    pub mod control {
        pub const BASE: u32 = 0x1000_0000;
        pub const STATUS: u32 = 0x1000_0000;
        pub const PANIC_CODE: u32 = 0x1000_0004;
        pub const EXIT_CODE: u32 = 0x1000_0008;
        pub const YIELD: u32 = 0x1000_000c;
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

    pub mod gpu0 {
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

    pub mod timer0 {
        pub const BASE: u32 = 0x1000_0600;
        pub const VERSION: u32 = 0x1000_0600;
        pub const GAME_TICKS_LOW: u32 = 0x1000_0604;
        pub const GAME_TICKS_HIGH: u32 = 0x1000_0608;
        pub const MONOTONIC_NANOS_LOW: u32 = 0x1000_060c;
        pub const MONOTONIC_NANOS_HIGH: u32 = 0x1000_0610;
        pub const SIZE: u32 = 256;

        pub const TIMER_VERSION: i32 = 1;
    }

    pub mod keyboard0 {
        pub const BASE: u32 = 0x1000_0700;
        pub const VERSION: u32 = 0x1000_0700;
        pub const QUEUE_LEN: u32 = 0x1000_0704;
        pub const STATUS: u32 = 0x1000_0708;
        pub const EVENT_KIND: u32 = 0x1000_070c;
        pub const CODE: u32 = 0x1000_0710;
        pub const MODIFIERS: u32 = 0x1000_0714;
        pub const FLAGS: u32 = 0x1000_0718;
        pub const SEQUENCE_LOW: u32 = 0x1000_071c;
        pub const SEQUENCE_HIGH: u32 = 0x1000_0720;
        pub const COMMAND: u32 = 0x1000_0724;
        pub const DROPPED_COUNT: u32 = 0x1000_0728;
        pub const SIZE: u32 = 256;

        pub const KEYBOARD_VERSION: i32 = 1;

        pub const EVENT_NONE: i32 = 0;
        pub const EVENT_KEY_DOWN: i32 = 1;
        pub const EVENT_KEY_UP: i32 = 2;
        pub const EVENT_CHAR: i32 = 3;
        pub const EVENT_PASTE_BYTE: i32 = 4;

        pub const STATUS_EMPTY: i32 = 0;
        pub const STATUS_READY: i32 = 1;
        pub const STATUS_OVERFLOW: i32 = 2;

        pub const COMMAND_NOP: i32 = 0;
        pub const COMMAND_CONSUME: i32 = 1;
        pub const COMMAND_CLEAR: i32 = 2;

        pub const FLAG_REPEAT: i32 = 0x0000_0001;

        pub const MOD_SHIFT: i32 = 0x0000_0001;
        pub const MOD_CONTROL: i32 = 0x0000_0002;
        pub const MOD_ALT: i32 = 0x0000_0004;
        pub const MOD_SUPER: i32 = 0x0000_0008;

        pub const KEY_ENTER: i32 = 257;
        pub const KEY_KP_ENTER: i32 = 335;
        pub const KEY_BACKSPACE: i32 = 259;
    }

    pub mod mmu0 {
        pub const BASE: u32 = 0x1000_0800;
        pub const VERSION: u32 = 0x1000_0800;
        pub const STATUS: u32 = 0x1000_0804;
        pub const ERROR: u32 = 0x1000_0808;
        pub const COMMAND: u32 = 0x1000_080c;
        pub const ADDRESS_SPACE: u32 = 0x1000_0810;
        pub const VIRTUAL_START: u32 = 0x1000_0814;
        pub const PHYSICAL_START: u32 = 0x1000_0818;
        pub const PAGE_COUNT: u32 = 0x1000_081c;
        pub const FLAGS: u32 = 0x1000_0820;
        pub const ENTRY_PC: u32 = 0x1000_0824;
        pub const STACK_POINTER: u32 = 0x1000_0828;
        pub const RESULT: u32 = 0x1000_082c;
        pub const SIZE: u32 = 256;

        pub const MMU_VERSION: i32 = 1;
        pub const STATUS_READY: i32 = 0;
        pub const STATUS_DONE: i32 = 1;
        pub const STATUS_ERROR: i32 = 2;
        pub const ERROR_NONE: i32 = 0;
        pub const ERROR_INVALID_COMMAND: i32 = 1;
        pub const ERROR_INVALID_ARGUMENT: i32 = 2;
        pub const COMMAND_NOP: i32 = 0;
        pub const COMMAND_CREATE_ADDRESS_SPACE: i32 = 1;
        pub const COMMAND_MAP_PAGES: i32 = 2;
        pub const COMMAND_PROTECT_PAGES: i32 = 3;
        pub const COMMAND_ACTIVATE_USER_ADDRESS_SPACE: i32 = 4;
        pub const FLAG_USER_ACCESSIBLE: i32 = 0x0000_0001;
        pub const FLAG_WRITABLE: i32 = 0x0000_0002;
        pub const FLAG_EXECUTABLE: i32 = 0x0000_0004;
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
        assert_eq!(computer::control::YIELD, 0x1000_000c);
        assert_eq!(computer::debug::WRITE, 0x1000_0100);
        assert_eq!(computer::serial_input::READ, 0x1000_0204);
        assert_eq!(computer::storage0::COMMAND, 0x1000_040c);
        assert_eq!(computer::gpu0::COMMAND, 0x1000_0510);
        assert_eq!(computer::timer0::BASE, 0x1000_0600);
        assert_eq!(computer::timer0::VERSION, 0x1000_0600);
        assert_eq!(computer::timer0::GAME_TICKS_LOW, 0x1000_0604);
        assert_eq!(computer::timer0::GAME_TICKS_HIGH, 0x1000_0608);
        assert_eq!(computer::timer0::MONOTONIC_NANOS_LOW, 0x1000_060c);
        assert_eq!(computer::timer0::MONOTONIC_NANOS_HIGH, 0x1000_0610);
        assert_eq!(computer::timer0::TIMER_VERSION, 1);
        assert_eq!(computer::keyboard0::BASE, 0x1000_0700);
        assert_eq!(computer::keyboard0::VERSION, 0x1000_0700);
        assert_eq!(computer::keyboard0::QUEUE_LEN, 0x1000_0704);
        assert_eq!(computer::keyboard0::STATUS, 0x1000_0708);
        assert_eq!(computer::keyboard0::EVENT_KIND, 0x1000_070c);
        assert_eq!(computer::keyboard0::CODE, 0x1000_0710);
        assert_eq!(computer::keyboard0::MODIFIERS, 0x1000_0714);
        assert_eq!(computer::keyboard0::FLAGS, 0x1000_0718);
        assert_eq!(computer::keyboard0::SEQUENCE_LOW, 0x1000_071c);
        assert_eq!(computer::keyboard0::SEQUENCE_HIGH, 0x1000_0720);
        assert_eq!(computer::keyboard0::COMMAND, 0x1000_0724);
        assert_eq!(computer::keyboard0::DROPPED_COUNT, 0x1000_0728);
        assert_eq!(computer::keyboard0::SIZE, 256);
        assert_eq!(computer::keyboard0::KEYBOARD_VERSION, 1);
        assert_eq!(computer::keyboard0::EVENT_NONE, 0);
        assert_eq!(computer::keyboard0::EVENT_KEY_DOWN, 1);
        assert_eq!(computer::keyboard0::EVENT_KEY_UP, 2);
        assert_eq!(computer::keyboard0::EVENT_CHAR, 3);
        assert_eq!(computer::keyboard0::EVENT_PASTE_BYTE, 4);
        assert_eq!(computer::keyboard0::STATUS_EMPTY, 0);
        assert_eq!(computer::keyboard0::STATUS_READY, 1);
        assert_eq!(computer::keyboard0::STATUS_OVERFLOW, 2);
        assert_eq!(computer::keyboard0::COMMAND_NOP, 0);
        assert_eq!(computer::keyboard0::COMMAND_CONSUME, 1);
        assert_eq!(computer::keyboard0::COMMAND_CLEAR, 2);
        assert_eq!(computer::keyboard0::FLAG_REPEAT, 0x0000_0001);
        assert_eq!(computer::keyboard0::MOD_SHIFT, 0x0000_0001);
        assert_eq!(computer::keyboard0::MOD_CONTROL, 0x0000_0002);
        assert_eq!(computer::keyboard0::MOD_ALT, 0x0000_0004);
        assert_eq!(computer::keyboard0::MOD_SUPER, 0x0000_0008);
        assert_eq!(computer::keyboard0::KEY_ENTER, 257);
        assert_eq!(computer::keyboard0::KEY_KP_ENTER, 335);
        assert_eq!(computer::keyboard0::KEY_BACKSPACE, 259);
        assert_eq!(computer::mmu0::BASE, 0x1000_0800);
        assert_eq!(computer::mmu0::VERSION, 0x1000_0800);
        assert_eq!(computer::mmu0::STATUS, 0x1000_0804);
        assert_eq!(computer::mmu0::ERROR, 0x1000_0808);
        assert_eq!(computer::mmu0::COMMAND, 0x1000_080c);
        assert_eq!(computer::mmu0::ADDRESS_SPACE, 0x1000_0810);
        assert_eq!(computer::mmu0::VIRTUAL_START, 0x1000_0814);
        assert_eq!(computer::mmu0::PHYSICAL_START, 0x1000_0818);
        assert_eq!(computer::mmu0::PAGE_COUNT, 0x1000_081c);
        assert_eq!(computer::mmu0::FLAGS, 0x1000_0820);
        assert_eq!(computer::mmu0::ENTRY_PC, 0x1000_0824);
        assert_eq!(computer::mmu0::STACK_POINTER, 0x1000_0828);
        assert_eq!(computer::mmu0::RESULT, 0x1000_082c);
        assert_eq!(computer::mmu0::SIZE, 256);
        assert_eq!(computer::mmu0::MMU_VERSION, 1);
        assert_eq!(computer::mmu0::STATUS_READY, 0);
        assert_eq!(computer::mmu0::STATUS_DONE, 1);
        assert_eq!(computer::mmu0::STATUS_ERROR, 2);
        assert_eq!(computer::mmu0::ERROR_NONE, 0);
        assert_eq!(computer::mmu0::ERROR_INVALID_COMMAND, 1);
        assert_eq!(computer::mmu0::ERROR_INVALID_ARGUMENT, 2);
        assert_eq!(computer::mmu0::COMMAND_NOP, 0);
        assert_eq!(computer::mmu0::COMMAND_CREATE_ADDRESS_SPACE, 1);
        assert_eq!(computer::mmu0::COMMAND_MAP_PAGES, 2);
        assert_eq!(computer::mmu0::COMMAND_PROTECT_PAGES, 3);
        assert_eq!(computer::mmu0::COMMAND_ACTIVATE_USER_ADDRESS_SPACE, 4);
        assert_eq!(computer::mmu0::FLAG_USER_ACCESSIBLE, 0x0000_0001);
        assert_eq!(computer::mmu0::FLAG_WRITABLE, 0x0000_0002);
        assert_eq!(computer::mmu0::FLAG_EXECUTABLE, 0x0000_0004);
        assert_eq!(computer::profile::BOOT_INFO_MAGIC, 0x4942_5852);
        assert_eq!(computer::profile::VERSION, 2);
        assert_eq!(computer::profile::BOOT_INFO_SIZE, 28);
        assert_eq!(computer::profile::HARDWARE_ENTRY_SIZE, 16);
        assert_eq!(computer::hardware_id::TIMER0, 7);
        assert_eq!(computer::hardware_id::KEYBOARD0, 8);
        assert_eq!(computer::hardware_id::MMU0, 9);
    }

    #[test]
    fn profile_structs_parse_expected_values() {
        let boot_info = computer::profile::BootInfo {
            ram_size: 1024,
            page_size: 256,
            program_base: 256,
            hardware_table_addr: 28,
            hardware_count: 7,
        };
        let timer0 = computer::profile::HardwareEntry {
            id: computer::hardware_id::TIMER0,
            mmio_base: computer::timer0::BASE,
            mmio_size: computer::timer0::SIZE,
            irq_source: cpu::interrupt_source::TIMER0,
        };
        let keyboard0 = computer::profile::HardwareEntry {
            id: computer::hardware_id::KEYBOARD0,
            mmio_base: computer::keyboard0::BASE,
            mmio_size: computer::keyboard0::SIZE,
            irq_source: cpu::interrupt_source::KEYBOARD0,
        };

        assert_eq!(boot_info.hardware_table_addr, 28);
        assert_eq!(timer0.irq_source, 1);
        assert_eq!(keyboard0.irq_source, 2);
    }

    #[test]
    fn cpu_interrupt_constants_match_current_k16_profile() {
        assert_eq!(cpu::csr::TRAP_VECTOR, 1);
        assert_eq!(cpu::csr::INTERRUPT_ENABLE, 5);
        assert_eq!(cpu::csr::INTERRUPT_MASK, 6);
        assert_eq!(cpu::csr::INTERRUPT_PENDING, 7);
        assert_eq!(cpu::csr::TRAP_ARG0, 8);
        assert_eq!(cpu::csr::TRAP_ARG1, 9);
        assert_eq!(cpu::csr::TRAP_ARG2, 10);
        assert_eq!(cpu::csr::TRAP_FRAME_INDEX, 11);
        assert_eq!(cpu::csr::TRAP_FRAME_REGISTER, 12);
        assert_eq!(cpu::csr::TRAP_RESUME_PC, 13);
        assert_eq!(cpu::csr::TRAP_STACK_POINTER, 14);
        assert_eq!(cpu::csr::TRAP_INTERRUPT_ENABLE, 15);
        assert_eq!(cpu::interrupt_source::TIMER0, 0x0000_0001);
        assert_eq!(cpu::interrupt_source::KEYBOARD0, 0x0000_0002);
        assert_eq!(cpu::trap_cause::TIMER0_INTERRUPT, 0x8000_0001);
        assert_eq!(cpu::trap_cause::KEYBOARD0_INTERRUPT, 0x8000_0002);
        assert!(cpu::trap_cause::is_interrupt(
            cpu::trap_cause::TIMER0_INTERRUPT
        ));
        assert!(cpu::trap_cause::is_interrupt(
            cpu::trap_cause::KEYBOARD0_INTERRUPT
        ));
        assert!(!cpu::trap_cause::is_interrupt(
            cpu::trap_cause::ILLEGAL_INSTRUCTION
        ));
        assert_eq!(
            cpu::trap_cause::interrupt_source(cpu::trap_cause::TIMER0_INTERRUPT),
            cpu::interrupt_source::TIMER0
        );
        assert_eq!(
            cpu::trap_cause::interrupt_source(cpu::trap_cause::KEYBOARD0_INTERRUPT),
            cpu::interrupt_source::KEYBOARD0
        );
    }

    #[test]
    fn syscall_constants_match_current_k16_kernel_proof_surface() {
        assert_eq!(syscall::DEBUG_MARKER, 2);
        assert_eq!(syscall::DEBUG_WRITE_BYTE, 3);
        assert_eq!(syscall::YIELD, 4);
        assert_eq!(syscall::SLEEP_TICKS, 5);
        assert_eq!(syscall::EXIT, 6);
        assert_eq!(syscall::WRITE, 7);
        assert_eq!(syscall::READ, 8);
        assert_eq!(syscall::RUN, 9);
        assert_eq!(syscall::OPEN, 10);
        assert_eq!(syscall::CLOSE, 11);
        assert_eq!(syscall::BRK, 12);
        assert_eq!(syscall::SBRK, 13);
        assert_eq!(syscall::READ_DIR, 14);
        assert_eq!(syscall::STAT, 15);
        assert_eq!(syscall::RUN_ARGV_MAGIC, 0x4752_4152);
        assert_eq!(syscall::MAX_RUN_ARGS, 4);
        assert_eq!(syscall::MAX_RUN_PATH_BYTES, 61);
        assert_eq!(syscall::MAX_RUN_ARG_BYTES, 128);
        assert_eq!(syscall::MAX_RUN_ARGV_REQUEST_BYTES, 601);
        assert_eq!(syscall::READ_DIR_REQUEST_MAGIC, 0x5249_4452);
        assert_eq!(syscall::MAX_READ_DIR_PATH_BYTES, 228);
        assert_eq!(syscall::MAX_READ_DIR_REQUEST_BYTES, 244);
        assert_eq!(syscall::MAX_STAT_PATH_BYTES, 228);
        assert_eq!(syscall::STAT_METADATA_BYTES, 16);
        assert_eq!(syscall::FILE_TYPE_REGULAR, 1);
        assert_eq!(syscall::FILE_TYPE_DIRECTORY, 2);
        assert_eq!(syscall::FD_STDIN, 0);
        assert_eq!(syscall::FD_STDOUT, 1);
        assert_eq!(syscall::FD_STDERR, 2);
        assert_eq!(syscall::ERROR_BAD_FD, 0xffff_fff7);
        assert_eq!(syscall::ERROR_BUSY, 0xffff_fff0);
        assert_eq!(syscall::ERROR_EXEC_FORMAT, 0xffff_fff8);
        assert_eq!(syscall::ERROR_FAULT, 0xffff_fff2);
        assert_eq!(syscall::ERROR_INVALID, 0xffff_ffea);
        assert_eq!(syscall::ERROR_NO_FD, 0xffff_ffe8);
        assert_eq!(syscall::ERROR_NO_ENTRY, 0xffff_fffe);
        assert_eq!(syscall::ERROR_NO_MEMORY, 0xffff_fff4);
        assert_eq!(syscall::DEBUG_MARKER_RETURN, 0x53);
        assert_eq!(syscall::STATUS_OK, 0);
    }

    #[test]
    fn mmio_helper_preserves_typed_address() {
        let register = unsafe { mmio::<u32>(computer::gpu0::COMMAND) };

        assert_eq!(register.as_ptr() as usize, 0x1000_0510);
    }
}
