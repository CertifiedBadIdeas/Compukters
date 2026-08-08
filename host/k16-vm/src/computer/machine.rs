use crate::computer::devices::{
    ComputerControlDevice, ComputerDeviceDescriptor, ComputerDeviceIds, ComputerDeviceStatsKind,
    DebugSerialDevice, GpuDevice, KeyboardDevice, MmuControlCommand, MmuControlDevice,
    SerialInputDevice, StoragePortDevice, TimerDevice,
};
use crate::computer::profile::ComputerMachineProfile;
use crate::computer::stats::{
    K16ComputerDecodeCacheStatsSnapshot, K16ComputerDeviceStats, K16ComputerGpuStatsSnapshot,
    K16ComputerOsStatsSnapshot, K16ComputerStatsSnapshot, K16ComputerStorageStatsSnapshot,
};
use crate::computer_abi;
use crate::k16::{
    K16AddressMode, K16CachedDecoder, K16Cpu, K16PrivilegeMode, K16Signal,
    K16_INTERRUPT_SOURCE_KEYBOARD0, K16_INTERRUPT_SOURCE_TIMER0,
};
use crate::low_bus::{MachineBus, MachineBusStatsSnapshot};
use crate::low_machine::{MachineMemory, MemoryFault};
use crate::mmu::{MmuAddressSpaceId, MmuAddressSpaces, MmuFault, MmuMapFlags};
use crate::retained_gpu::{ServerboundOutcome, ServerboundRejection};
use std::fmt::{Display, Formatter};

mod boot_flow;
mod construction;
mod snapshot_flow;

pub type CpuId = usize;

pub struct ComputerMachine {
    bus: MachineBus,
    devices: ComputerDeviceIds,
    cpus: Vec<ComputerCpuContext>,
    boot_cpu: Option<CpuId>,
    address_spaces: MmuAddressSpaces,
}

enum ComputerCpuContext {
    K16 {
        cpu: K16Cpu,
        decoder: K16CachedDecoder,
        max_steps: u64,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BootHandoffError {
    MissingBootCpu,
    EmptyImage,
    StackTopMisaligned {
        stack_top: u32,
    },
    StackTopOutOfBounds {
        stack_top: u32,
        ram_len: usize,
    },
    RamRangeOverflow {
        image_addr: u32,
        image_len: u32,
    },
    RamRangeOutOfBounds {
        image_addr: u32,
        image_len: u32,
        ram_len: usize,
    },
}

impl Display for BootHandoffError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::MissingBootCpu => formatter.write_str("boot CPU is not spawned"),
            Self::EmptyImage => formatter.write_str("boot handoff image is empty"),
            Self::StackTopMisaligned { stack_top } => write!(
                formatter,
                "boot handoff stack top {stack_top:#010x} is not 8-byte aligned",
            ),
            Self::StackTopOutOfBounds { stack_top, ram_len } => write!(
                formatter,
                "boot handoff stack top {stack_top:#010x} is outside {ram_len} bytes",
            ),
            Self::RamRangeOverflow {
                image_addr,
                image_len,
            } => write!(
                formatter,
                "boot handoff RAM range {image_addr:#010x} with length {image_len} overflows address space",
            ),
            Self::RamRangeOutOfBounds {
                image_addr,
                image_len,
                ram_len,
            } => write!(
                formatter,
                "boot handoff RAM range {image_addr:#010x} with length {image_len} is outside {ram_len} bytes",
            ),
        }
    }
}

impl std::error::Error for BootHandoffError {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ComputerMemoryMap {
    regions: Vec<ComputerMemoryRegion>,
}

impl ComputerMemoryMap {
    pub fn region(&self, name: &str) -> Option<&ComputerMemoryRegion> {
        self.regions.iter().find(|region| region.name == name)
    }

    pub fn regions(&self) -> &[ComputerMemoryRegion] {
        &self.regions
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ComputerMemoryRegion {
    pub name: &'static str,
    pub base: u32,
    pub size: u32,
    pub readable: bool,
    pub writable: bool,
}

impl ComputerMachine {
    pub const PROFILE_V2_BOOT_INFO_MAGIC: u32 = computer_abi::PROFILE_V2_BOOT_INFO_MAGIC;
    pub const PROFILE_V2_VERSION: u32 = computer_abi::PROFILE_V2_VERSION;
    pub const PROFILE_V2_PAGE_SIZE: u32 = computer_abi::PROFILE_V2_PAGE_SIZE;
    pub const PROFILE_V2_BOOT_INFO_ADDR: u32 = computer_abi::PROFILE_V2_BOOT_INFO_ADDR;
    pub const PROFILE_V2_PROGRAM_BASE: u32 = computer_abi::PROFILE_V2_PROGRAM_BASE;
    pub const PROFILE_V2_BOOT_INFO_SIZE: u32 = computer_abi::PROFILE_V2_BOOT_INFO_SIZE;
    pub const PROFILE_V2_HARDWARE_ENTRY_SIZE: u32 = computer_abi::PROFILE_V2_HARDWARE_ENTRY_SIZE;
    pub const HARDWARE_ID_CONTROL: u32 = computer_abi::COMPUTER_HARDWARE_ID_CONTROL;
    pub const HARDWARE_ID_DEBUG: u32 = computer_abi::COMPUTER_HARDWARE_ID_DEBUG;
    pub const HARDWARE_ID_SERIAL_INPUT: u32 = computer_abi::COMPUTER_HARDWARE_ID_SERIAL_INPUT;
    pub const HARDWARE_ID_STORAGE0: u32 = computer_abi::COMPUTER_HARDWARE_ID_STORAGE0;
    pub const HARDWARE_ID_GPU0: u32 = computer_abi::COMPUTER_HARDWARE_ID_GPU0;
    pub const HARDWARE_ID_TIMER0: u32 = computer_abi::COMPUTER_HARDWARE_ID_TIMER0;
    pub const HARDWARE_ID_KEYBOARD0: u32 = computer_abi::COMPUTER_HARDWARE_ID_KEYBOARD0;
    pub const HARDWARE_ID_MMU0: u32 = computer_abi::COMPUTER_HARDWARE_ID_MMU0;
    pub const HARDWARE_ID_STORAGE1: u32 = computer_abi::COMPUTER_HARDWARE_ID_STORAGE1;
    pub const CONTROL_BASE: u32 = computer_abi::CONTROL_BASE;
    pub const CONTROL_STATUS: u32 = computer_abi::CONTROL_STATUS;
    pub const CONTROL_PANIC_CODE: u32 = computer_abi::CONTROL_PANIC_CODE;
    pub const CONTROL_EXIT_CODE: u32 = computer_abi::CONTROL_EXIT_CODE;
    pub const CONTROL_YIELD: u32 = computer_abi::CONTROL_YIELD;
    pub const CONTROL_OS_STATS_ADDR: u32 = computer_abi::CONTROL_OS_STATS_ADDR;
    pub const CONTROL_OS_STATS_SIZE: u32 = computer_abi::CONTROL_OS_STATS_SIZE;
    pub const CONTROL_SIZE: u32 = computer_abi::CONTROL_SIZE;
    pub const DEBUG_BASE: u32 = computer_abi::DEBUG_BASE;
    pub const DEBUG_WRITE: u32 = computer_abi::DEBUG_WRITE;
    pub const DEBUG_SIZE: u32 = computer_abi::DEBUG_SIZE;
    pub const SERIAL_INPUT_BASE: u32 = computer_abi::SERIAL_INPUT_BASE;
    pub const SERIAL_INPUT_READY: u32 = computer_abi::SERIAL_INPUT_READY;
    pub const SERIAL_INPUT_READ: u32 = computer_abi::SERIAL_INPUT_READ;
    pub const SERIAL_INPUT_SIZE: u32 = computer_abi::SERIAL_INPUT_SIZE;
    pub const GPU0_BASE: u32 = computer_abi::GPU0_BASE;
    pub const GPU0_DEVICE_ABI_VERSION: u32 = computer_abi::GPU0_DEVICE_ABI_VERSION;
    pub const GPU0_WIDTH: u32 = computer_abi::GPU0_WIDTH;
    pub const GPU0_HEIGHT: u32 = computer_abi::GPU0_HEIGHT;
    pub const GPU0_PACKET_VERSION: u32 = computer_abi::GPU0_PACKET_VERSION;
    pub const GPU0_MAX_PACKET_BYTES: u32 = computer_abi::GPU0_MAX_PACKET_BYTES;
    pub const GPU0_MAX_TRANSACTION_OPERATIONS: u32 = computer_abi::GPU0_MAX_TRANSACTION_OPERATIONS;
    pub const GPU0_MAX_RESOURCES: u32 = computer_abi::GPU0_MAX_RESOURCES;
    pub const GPU0_MAX_RESOURCE_BYTES: u32 = computer_abi::GPU0_MAX_RESOURCE_BYTES;
    pub const GPU0_MAX_TOTAL_RESOURCE_BYTES: u32 = computer_abi::GPU0_MAX_TOTAL_RESOURCE_BYTES;
    pub const GPU0_MAX_DRAW_LIST_BYTES: u32 = computer_abi::GPU0_MAX_DRAW_LIST_BYTES;
    pub const GPU0_MAX_DRAW_COMMANDS: u32 = computer_abi::GPU0_MAX_DRAW_COMMANDS;
    pub const GPU0_MAX_CLIP_DEPTH: u32 = computer_abi::GPU0_MAX_CLIP_DEPTH;
    pub const GPU0_SUBMISSION_ADDRESS: u32 = computer_abi::GPU0_SUBMISSION_ADDRESS;
    pub const GPU0_SUBMISSION_LENGTH: u32 = computer_abi::GPU0_SUBMISSION_LENGTH;
    pub const GPU0_SUBMIT: u32 = computer_abi::GPU0_SUBMIT;
    pub const GPU0_RESULT_CODE: u32 = computer_abi::GPU0_RESULT_CODE;
    pub const GPU0_ERROR_OPERATION_INDEX: u32 = computer_abi::GPU0_ERROR_OPERATION_INDEX;
    pub const GPU0_ERROR_BYTE_OFFSET: u32 = computer_abi::GPU0_ERROR_BYTE_OFFSET;
    pub const GPU0_COMMITTED_SEQUENCE_LOW: u32 = computer_abi::GPU0_COMMITTED_SEQUENCE_LOW;
    pub const GPU0_COMMITTED_SEQUENCE_HIGH: u32 = computer_abi::GPU0_COMMITTED_SEQUENCE_HIGH;
    pub const GPU0_SIZE: u32 = computer_abi::GPU0_SIZE;
    pub const GPU0_DEVICE_ABI_VERSION_VALUE: i32 = computer_abi::GPU0_DEVICE_ABI_VERSION_VALUE;
    pub const GPU0_PACKET_VERSION_VALUE: i32 = computer_abi::GPU0_PACKET_VERSION_VALUE;
    pub const STORAGE0_BASE: u32 = computer_abi::STORAGE0_BASE;
    pub const STORAGE0_VERSION: u32 = computer_abi::STORAGE0_VERSION;
    pub const STORAGE0_STATUS: u32 = computer_abi::STORAGE0_STATUS;
    pub const STORAGE0_ERROR: u32 = computer_abi::STORAGE0_ERROR;
    pub const STORAGE0_COMMAND: u32 = computer_abi::STORAGE0_COMMAND;
    pub const STORAGE0_BLOCK_SIZE: u32 = computer_abi::STORAGE0_BLOCK_SIZE;
    pub const STORAGE0_CAPACITY_BLOCKS_LOW: u32 = computer_abi::STORAGE0_CAPACITY_BLOCKS_LOW;
    pub const STORAGE0_CAPACITY_BLOCKS_HIGH: u32 = computer_abi::STORAGE0_CAPACITY_BLOCKS_HIGH;
    pub const STORAGE0_LBA_LOW: u32 = computer_abi::STORAGE0_LBA_LOW;
    pub const STORAGE0_LBA_HIGH: u32 = computer_abi::STORAGE0_LBA_HIGH;
    pub const STORAGE0_BLOCK_COUNT: u32 = computer_abi::STORAGE0_BLOCK_COUNT;
    pub const STORAGE0_BUFFER_ADDR: u32 = computer_abi::STORAGE0_BUFFER_ADDR;
    pub const STORAGE0_BYTES_DONE: u32 = computer_abi::STORAGE0_BYTES_DONE;
    pub const STORAGE0_SEQUENCE_LOW: u32 = computer_abi::STORAGE0_SEQUENCE_LOW;
    pub const STORAGE0_SEQUENCE_HIGH: u32 = computer_abi::STORAGE0_SEQUENCE_HIGH;
    pub const STORAGE0_MEDIA_STATUS: u32 = computer_abi::STORAGE0_MEDIA_STATUS;
    pub const STORAGE0_SIZE: u32 = computer_abi::STORAGE0_SIZE;
    pub const STORAGE1_BASE: u32 = computer_abi::STORAGE1_BASE;
    pub const STORAGE1_VERSION: u32 = computer_abi::STORAGE1_VERSION;
    pub const STORAGE1_STATUS: u32 = computer_abi::STORAGE1_STATUS;
    pub const STORAGE1_ERROR: u32 = computer_abi::STORAGE1_ERROR;
    pub const STORAGE1_COMMAND: u32 = computer_abi::STORAGE1_COMMAND;
    pub const STORAGE1_BLOCK_SIZE: u32 = computer_abi::STORAGE1_BLOCK_SIZE;
    pub const STORAGE1_CAPACITY_BLOCKS_LOW: u32 = computer_abi::STORAGE1_CAPACITY_BLOCKS_LOW;
    pub const STORAGE1_CAPACITY_BLOCKS_HIGH: u32 = computer_abi::STORAGE1_CAPACITY_BLOCKS_HIGH;
    pub const STORAGE1_LBA_LOW: u32 = computer_abi::STORAGE1_LBA_LOW;
    pub const STORAGE1_LBA_HIGH: u32 = computer_abi::STORAGE1_LBA_HIGH;
    pub const STORAGE1_BLOCK_COUNT: u32 = computer_abi::STORAGE1_BLOCK_COUNT;
    pub const STORAGE1_BUFFER_ADDR: u32 = computer_abi::STORAGE1_BUFFER_ADDR;
    pub const STORAGE1_BYTES_DONE: u32 = computer_abi::STORAGE1_BYTES_DONE;
    pub const STORAGE1_SEQUENCE_LOW: u32 = computer_abi::STORAGE1_SEQUENCE_LOW;
    pub const STORAGE1_SEQUENCE_HIGH: u32 = computer_abi::STORAGE1_SEQUENCE_HIGH;
    pub const STORAGE1_MEDIA_STATUS: u32 = computer_abi::STORAGE1_MEDIA_STATUS;
    pub const STORAGE1_SIZE: u32 = computer_abi::STORAGE1_SIZE;
    pub const TIMER0_BASE: u32 = computer_abi::TIMER0_BASE;
    pub const TIMER0_VERSION: u32 = computer_abi::TIMER0_VERSION;
    pub const TIMER0_GAME_TICKS_LOW: u32 = computer_abi::TIMER0_GAME_TICKS_LOW;
    pub const TIMER0_GAME_TICKS_HIGH: u32 = computer_abi::TIMER0_GAME_TICKS_HIGH;
    pub const TIMER0_MONOTONIC_NANOS_LOW: u32 = computer_abi::TIMER0_MONOTONIC_NANOS_LOW;
    pub const TIMER0_MONOTONIC_NANOS_HIGH: u32 = computer_abi::TIMER0_MONOTONIC_NANOS_HIGH;
    pub const TIMER0_SIZE: u32 = computer_abi::TIMER0_SIZE;
    pub const TIMER0_VERSION_VALUE: i32 = computer_abi::TIMER0_VERSION_VALUE;
    pub const KEYBOARD0_BASE: u32 = computer_abi::KEYBOARD0_BASE;
    pub const KEYBOARD0_VERSION: u32 = computer_abi::KEYBOARD0_VERSION;
    pub const KEYBOARD0_QUEUE_LEN: u32 = computer_abi::KEYBOARD0_QUEUE_LEN;
    pub const KEYBOARD0_STATUS: u32 = computer_abi::KEYBOARD0_STATUS;
    pub const KEYBOARD0_EVENT_KIND: u32 = computer_abi::KEYBOARD0_EVENT_KIND;
    pub const KEYBOARD0_CODE: u32 = computer_abi::KEYBOARD0_CODE;
    pub const KEYBOARD0_MODIFIERS: u32 = computer_abi::KEYBOARD0_MODIFIERS;
    pub const KEYBOARD0_FLAGS: u32 = computer_abi::KEYBOARD0_FLAGS;
    pub const KEYBOARD0_SEQUENCE_LOW: u32 = computer_abi::KEYBOARD0_SEQUENCE_LOW;
    pub const KEYBOARD0_SEQUENCE_HIGH: u32 = computer_abi::KEYBOARD0_SEQUENCE_HIGH;
    pub const KEYBOARD0_COMMAND: u32 = computer_abi::KEYBOARD0_COMMAND;
    pub const KEYBOARD0_DROPPED_COUNT: u32 = computer_abi::KEYBOARD0_DROPPED_COUNT;
    pub const KEYBOARD0_SIZE: u32 = computer_abi::KEYBOARD0_SIZE;
    pub const MMU0_BASE: u32 = computer_abi::MMU0_BASE;
    pub const MMU0_VERSION: u32 = computer_abi::MMU0_VERSION;
    pub const MMU0_STATUS: u32 = computer_abi::MMU0_STATUS;
    pub const MMU0_ERROR: u32 = computer_abi::MMU0_ERROR;
    pub const MMU0_COMMAND: u32 = computer_abi::MMU0_COMMAND;
    pub const MMU0_ADDRESS_SPACE: u32 = computer_abi::MMU0_ADDRESS_SPACE;
    pub const MMU0_VIRTUAL_START: u32 = computer_abi::MMU0_VIRTUAL_START;
    pub const MMU0_PHYSICAL_START: u32 = computer_abi::MMU0_PHYSICAL_START;
    pub const MMU0_PAGE_COUNT: u32 = computer_abi::MMU0_PAGE_COUNT;
    pub const MMU0_BYTE_COUNT: u32 = computer_abi::MMU0_BYTE_COUNT;
    pub const MMU0_FLAGS: u32 = computer_abi::MMU0_FLAGS;
    pub const MMU0_ENTRY_PC: u32 = computer_abi::MMU0_ENTRY_PC;
    pub const MMU0_STACK_POINTER: u32 = computer_abi::MMU0_STACK_POINTER;
    pub const MMU0_RESULT: u32 = computer_abi::MMU0_RESULT;
    pub const MMU0_SIZE: u32 = computer_abi::MMU0_SIZE;
    pub const MMU0_VERSION_VALUE: i32 = computer_abi::MMU0_VERSION_VALUE;
    pub const MMU0_STATUS_READY: i32 = computer_abi::MMU0_STATUS_READY;
    pub const MMU0_STATUS_DONE: i32 = computer_abi::MMU0_STATUS_DONE;
    pub const MMU0_STATUS_ERROR: i32 = computer_abi::MMU0_STATUS_ERROR;
    pub const MMU0_ERROR_NONE: i32 = computer_abi::MMU0_ERROR_NONE;
    pub const MMU0_ERROR_INVALID_COMMAND: i32 = computer_abi::MMU0_ERROR_INVALID_COMMAND;
    pub const MMU0_ERROR_INVALID_ARGUMENT: i32 = computer_abi::MMU0_ERROR_INVALID_ARGUMENT;
    pub const MMU0_ERROR_INVALID_ADDRESS_SPACE: i32 =
        computer_abi::MMU0_ERROR_INVALID_ADDRESS_SPACE;
    pub const MMU0_ERROR_TRANSLATION_FAULT: i32 = computer_abi::MMU0_ERROR_TRANSLATION_FAULT;
    pub const MMU0_ERROR_PHYSICAL_OUT_OF_BOUNDS: i32 =
        computer_abi::MMU0_ERROR_PHYSICAL_OUT_OF_BOUNDS;
    pub const MMU0_ERROR_BYTE_COUNT_OVERFLOW: i32 = computer_abi::MMU0_ERROR_BYTE_COUNT_OVERFLOW;
    pub const MMU0_COMMAND_NOP: i32 = computer_abi::MMU0_COMMAND_NOP;
    pub const MMU0_COMMAND_CREATE_ADDRESS_SPACE: i32 =
        computer_abi::MMU0_COMMAND_CREATE_ADDRESS_SPACE;
    pub const MMU0_COMMAND_MAP_PAGES: i32 = computer_abi::MMU0_COMMAND_MAP_PAGES;
    pub const MMU0_COMMAND_PROTECT_PAGES: i32 = computer_abi::MMU0_COMMAND_PROTECT_PAGES;
    pub const MMU0_COMMAND_ACTIVATE_USER_ADDRESS_SPACE: i32 =
        computer_abi::MMU0_COMMAND_ACTIVATE_USER_ADDRESS_SPACE;
    pub const MMU0_COMMAND_COPY_FROM_USER: i32 = computer_abi::MMU0_COMMAND_COPY_FROM_USER;
    pub const MMU0_COMMAND_COPY_TO_USER: i32 = computer_abi::MMU0_COMMAND_COPY_TO_USER;
    pub const MMU0_COMMAND_SET_TRAP_RETURN_PHYSICAL: i32 =
        computer_abi::MMU0_COMMAND_SET_TRAP_RETURN_PHYSICAL;
    pub const MMU0_COMMAND_SET_TRAP_RETURN_ADDRESS_SPACE: i32 =
        computer_abi::MMU0_COMMAND_SET_TRAP_RETURN_ADDRESS_SPACE;
    pub const MMU0_COMMAND_DESTROY_ADDRESS_SPACE: i32 =
        computer_abi::MMU0_COMMAND_DESTROY_ADDRESS_SPACE;
    pub const MMU0_FLAG_USER_ACCESSIBLE: i32 = computer_abi::MMU0_FLAG_USER_ACCESSIBLE;
    pub const MMU0_FLAG_WRITABLE: i32 = computer_abi::MMU0_FLAG_WRITABLE;
    pub const MMU0_FLAG_EXECUTABLE: i32 = computer_abi::MMU0_FLAG_EXECUTABLE;
    pub const K16_BIOS_FLASH_BASE: u32 = 0xFFF0_0000;
    pub const STATUS_RESET: i32 = computer_abi::STATUS_RESET;
    pub const STATUS_BOOTING: i32 = computer_abi::STATUS_BOOTING;
    pub const STATUS_READY: i32 = computer_abi::STATUS_READY;
    pub const STATUS_HALTED: i32 = computer_abi::STATUS_HALTED;
    pub const STATUS_PANIC: i32 = computer_abi::STATUS_PANIC;

    pub fn new(memory_size: usize) -> Result<Self, MemoryFault> {
        Self::from_profile(ComputerMachineProfile::computer_v1(memory_size))
    }

    pub fn from_profile(profile: ComputerMachineProfile) -> Result<Self, MemoryFault> {
        construction::from_profile(profile)
    }

    pub fn from_k16_bios_flash(
        bios_flash: &[u8],
        memory_size: usize,
        max_steps: u64,
    ) -> Result<(Self, CpuId), String> {
        boot_flow::from_k16_bios_flash(bios_flash, memory_size, max_steps)
    }

    pub(crate) fn from_k16_bios_flash_with_profile(
        bios_flash: &[u8],
        profile: ComputerMachineProfile,
        max_steps: u64,
    ) -> Result<(Self, CpuId), String> {
        boot_flow::from_k16_bios_flash_with_profile(bios_flash, profile, max_steps)
    }

    pub fn memory(&self) -> &MachineMemory {
        self.bus.memory()
    }

    pub fn memory_mut(&mut self) -> &mut MachineMemory {
        self.bus.memory_mut()
    }

    pub fn memory_map(&self) -> ComputerMemoryMap {
        let mut map = ComputerMemoryMap {
            regions: vec![ComputerMemoryRegion {
                name: "ram",
                base: 0,
                size: self.memory().len() as u32,
                readable: true,
                writable: true,
            }],
        };
        for descriptor in self.devices.descriptors() {
            self.push_memory_map_region(&mut map, descriptor);
        }
        map
    }

    pub fn snapshot_v1(&self) -> Result<Vec<u8>, String> {
        snapshot_flow::snapshot_v1(self)
    }

    pub fn stats_snapshot(&self) -> K16ComputerStatsSnapshot {
        let bus = self.bus.stats_snapshot();
        let os = self.os_stats_snapshot();
        let decode_cache = self.decode_cache_stats_snapshot();
        let mut devices = Vec::new();
        for descriptor in self.devices.descriptors() {
            self.push_stats_device(&bus, &mut devices, descriptor);
        }
        K16ComputerStatsSnapshot {
            bus,
            os,
            decode_cache,
            devices,
        }
    }

    fn decode_cache_stats_snapshot(&self) -> K16ComputerDecodeCacheStatsSnapshot {
        self.cpus
            .iter()
            .map(|cpu| match cpu {
                ComputerCpuContext::K16 { decoder, .. } => decoder.stats(),
            })
            .fold(
                K16ComputerDecodeCacheStatsSnapshot::default(),
                |mut total, stats| {
                    total.entries = total.entries.saturating_add(stats.entries as u64);
                    total.hits = total.hits.saturating_add(stats.hits);
                    total.misses = total.misses.saturating_add(stats.misses);
                    total
                },
            )
    }

    fn os_stats_snapshot(&self) -> K16ComputerOsStatsSnapshot {
        let Some((addr, size)) = self
            .control_device()
            .and_then(ComputerControlDevice::os_stats_region)
        else {
            return K16ComputerOsStatsSnapshot::default();
        };
        if size < 48 {
            return K16ComputerOsStatsSnapshot::default();
        }
        let memory = self.bus.memory();
        let read = |offset: u32| {
            if offset.checked_add(8).is_none_or(|end| end > size) {
                return 0;
            }
            addr.checked_add(offset)
                .and_then(|address| memory.load_u64(address).ok())
                .unwrap_or_default()
        };
        K16ComputerOsStatsSnapshot {
            path_lookups: read(0),
            inode_loads: read(8),
            dir_entry_scans: read(16),
            file_opens: read(24),
            file_reads: read(32),
            stat_calls: read(40),
            process_spawns: read(48),
            program_loads: read(56),
            dynamic_import_loads: read(64),
            library_loads: read(72),
            read_dir_calls: read(80),
            program_load_bytes: read(88),
            dynamic_import_bytes: read(96),
            library_load_bytes: read(104),
            generic_file_data_read_blocks: read(112),
            generic_file_data_read_bytes: read(120),
            read_dir_data_read_blocks: read(128),
            read_dir_data_read_bytes: read(136),
            program_data_read_blocks: read(144),
            program_data_read_bytes: read(152),
            dynamic_import_data_read_blocks: read(160),
            dynamic_import_data_read_bytes: read(168),
            library_data_read_blocks: read(176),
            library_data_read_bytes: read(184),
            block_cache_hits: read(192),
            block_cache_misses: read(200),
            block_cache_batch_reads: read(208),
            init_program_file_data_read_blocks: read(216),
            init_program_file_data_read_bytes: read(224),
            shell_program_file_data_read_blocks: read(232),
            shell_program_file_data_read_bytes: read(240),
            other_program_file_data_read_blocks: read(248),
            other_program_file_data_read_bytes: read(256),
            libkraft_library_file_data_read_blocks: read(264),
            libkraft_library_file_data_read_bytes: read(272),
            other_library_file_data_read_blocks: read(280),
            other_library_file_data_read_bytes: read(288),
        }
    }

    pub fn restore_ram_snapshot_v1(
        profile: ComputerMachineProfile,
        snapshot_bytes: &[u8],
    ) -> Result<Self, String> {
        snapshot_flow::restore_ram_snapshot_v1(profile, snapshot_bytes)
    }

    pub fn restore_snapshot_v1(
        profile: ComputerMachineProfile,
        snapshot_bytes: &[u8],
    ) -> Result<Self, String> {
        snapshot_flow::restore_snapshot_v1(profile, snapshot_bytes)
    }

    pub fn bus_load_i32(&self, address: u32) -> Result<i32, MemoryFault> {
        self.bus.load_i32(address)
    }

    pub fn bus_store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault> {
        self.bus.store_i32(address, value)
    }

    pub fn storage0_media_bytes(&self) -> Option<Vec<u8>> {
        self.storage0_device()
            .and_then(StoragePortDevice::media_bytes)
    }

    pub fn write_guest_ram_bytes(&mut self, address: u32, bytes: &[u8]) -> Result<(), String> {
        checked_ram_range(
            address,
            u32::try_from(bytes.len())
                .map_err(|_| "guest RAM write length does not fit u32".to_string())?,
            self.bus.memory().len(),
        )
        .map_err(|error| error.to_string())?;
        for (offset, byte) in bytes.iter().copied().enumerate() {
            self.bus
                .memory_mut()
                .store_u8(address + offset as u32, byte)
                .map_err(|error| error.to_string())?;
        }
        Ok(())
    }

    pub fn read_guest_ram_bytes(&self, address: u32, byte_len: u32) -> Result<Vec<u8>, String> {
        checked_ram_range(address, byte_len, self.bus.memory().len())
            .map_err(|error| error.to_string())?;
        let mut bytes = Vec::with_capacity(byte_len as usize);
        for offset in 0..byte_len {
            bytes.push(
                self.bus
                    .memory()
                    .load_u8(address + offset)
                    .map_err(|error| error.to_string())?,
            );
        }
        Ok(bytes)
    }

    pub fn boot_handoff_k16_from_ram(
        &mut self,
        entry_pc: u32,
        byte_len: u32,
        max_steps: u64,
    ) -> Result<CpuId, BootHandoffError> {
        boot_flow::boot_handoff_k16_from_ram(self, entry_pc, byte_len, max_steps)
    }

    pub fn boot_handoff_k16_from_ram_with_stack(
        &mut self,
        entry_pc: u32,
        byte_len: u32,
        max_steps: u64,
        stack_top: u32,
    ) -> Result<CpuId, BootHandoffError> {
        boot_flow::boot_handoff_k16_from_ram_with_stack(
            self, entry_pc, byte_len, max_steps, stack_top,
        )
    }

    pub fn boot_cpu_id(&self) -> Option<CpuId> {
        self.boot_cpu
    }

    pub fn cpu_count(&self) -> usize {
        self.cpus.len()
    }

    pub fn run_boot_k16_until_signal(&mut self, cpu_id: CpuId) -> Result<K16Signal, String> {
        boot_flow::run_boot_k16_until_signal(self, cpu_id)
    }

    pub fn create_mmu_address_space(&mut self) -> Result<MmuAddressSpaceId, MmuFault> {
        self.address_spaces.create(self.memory().len() as u32)
    }

    pub fn destroy_mmu_address_space(&mut self, address_space: MmuAddressSpaceId) -> bool {
        self.address_spaces.destroy(address_space)
    }

    pub fn map_mmu_pages(
        &mut self,
        address_space: MmuAddressSpaceId,
        virtual_start: u32,
        physical_start: u32,
        page_count: u32,
        flags: MmuMapFlags,
    ) -> Result<(), MmuFault> {
        self.address_spaces
            .get_mut(address_space)
            .ok_or(MmuFault {
                address: virtual_start,
                access: crate::mmu::MmuAccess::Load,
                kind: crate::mmu::MmuFaultKind::InvalidMapping,
            })?
            .map_pages(virtual_start, physical_start, page_count, flags)
    }

    pub fn protect_mmu_pages(
        &mut self,
        address_space: MmuAddressSpaceId,
        virtual_start: u32,
        page_count: u32,
        flags: MmuMapFlags,
    ) -> Result<(), MmuFault> {
        self.address_spaces
            .get_mut(address_space)
            .ok_or(MmuFault {
                address: virtual_start,
                access: crate::mmu::MmuAccess::Load,
                kind: crate::mmu::MmuFaultKind::InvalidMapping,
            })?
            .protect_pages(virtual_start, page_count, flags)
    }

    pub fn set_k16_cpu_address_mode(
        &mut self,
        cpu_id: CpuId,
        address_mode: K16AddressMode,
    ) -> Result<(), String> {
        let cpu = self.k16_cpu_mut(cpu_id)?;
        cpu.set_address_mode(address_mode);
        Ok(())
    }

    pub fn set_k16_cpu_privilege_mode(
        &mut self,
        cpu_id: CpuId,
        privilege_mode: K16PrivilegeMode,
    ) -> Result<(), String> {
        let cpu = self.k16_cpu_mut(cpu_id)?;
        cpu.set_privilege_mode(privilege_mode);
        Ok(())
    }

    pub(crate) fn take_pending_mmu0_command(&mut self) -> Option<MmuControlCommand> {
        self.mmu0_device_mut()
            .and_then(MmuControlDevice::take_pending_command)
    }

    pub(crate) fn finish_mmu0_success(&mut self, result: u32) {
        if let Some(device) = self.mmu0_device_mut() {
            device.finish_success(result);
        }
    }

    pub(crate) fn finish_mmu0_error(&mut self, error: i32) {
        if let Some(device) = self.mmu0_device_mut() {
            device.finish_error(error);
        }
    }

    pub fn control_status(&self) -> i32 {
        self.control_device()
            .map(|device| device.status)
            .unwrap_or(Self::STATUS_RESET)
    }

    pub fn panic_code(&self) -> i32 {
        self.control_device()
            .map(|device| device.panic_code)
            .unwrap_or(0)
    }

    pub fn exit_code(&self) -> i32 {
        self.control_device()
            .map(|device| device.exit_code)
            .unwrap_or(0)
    }

    pub fn debug_output_bytes(&self) -> &[u8] {
        self.debug_device()
            .map(|device| device.bytes())
            .unwrap_or(&[])
    }

    pub fn debug_output_string(&self) -> String {
        String::from_utf8_lossy(self.debug_output_bytes()).into_owned()
    }

    pub fn drain_debug_output_bytes(&mut self) -> Vec<u8> {
        self.debug_device_mut()
            .map(DebugSerialDevice::drain)
            .unwrap_or_default()
    }

    pub fn push_serial_input(&mut self, bytes: &[u8]) {
        if let Some(device) = self.serial_input_device_mut() {
            device.push_bytes(bytes);
        }
    }

    pub fn serial_input_len(&self) -> usize {
        self.serial_input_device()
            .map(SerialInputDevice::len)
            .unwrap_or(0)
    }

    pub fn push_keyboard_key_down(&mut self, code: u32, repeat: bool, modifiers: i32) {
        let became_ready = if let Some(device) = self.keyboard0_device_mut() {
            let was_empty = device.len() == 0;
            device.push_key_down(code, repeat, u32::from_le_bytes(modifiers.to_le_bytes()));
            was_empty && device.len() > 0
        } else {
            false
        };
        self.request_keyboard0_interrupt_if_ready(became_ready);
    }

    pub fn push_keyboard_key_up(&mut self, code: u32, modifiers: i32) {
        let became_ready = if let Some(device) = self.keyboard0_device_mut() {
            let was_empty = device.len() == 0;
            device.push_key_up(code, u32::from_le_bytes(modifiers.to_le_bytes()));
            was_empty && device.len() > 0
        } else {
            false
        };
        self.request_keyboard0_interrupt_if_ready(became_ready);
    }

    pub fn push_keyboard_char(&mut self, byte: u8) {
        let became_ready = if let Some(device) = self.keyboard0_device_mut() {
            let was_empty = device.len() == 0;
            device.push_char(byte);
            was_empty && device.len() > 0
        } else {
            false
        };
        self.request_keyboard0_interrupt_if_ready(became_ready);
    }

    pub fn push_keyboard_paste_byte(&mut self, byte: u8) {
        let became_ready = if let Some(device) = self.keyboard0_device_mut() {
            let was_empty = device.len() == 0;
            device.push_paste_byte(byte);
            was_empty && device.len() > 0
        } else {
            false
        };
        self.request_keyboard0_interrupt_if_ready(became_ready);
    }

    pub fn keyboard0_len(&self) -> usize {
        self.keyboard0_device()
            .map(KeyboardDevice::len)
            .unwrap_or(0)
    }

    pub fn attach_retained_display_viewer(
        &mut self,
        viewer_token: u64,
        computer_id: u32,
    ) -> Result<u64, String> {
        self.required_gpu0_mut()?
            .attach_viewer(viewer_token, computer_id)
            .map_err(|error| error.to_string())
    }

    pub fn detach_retained_display_viewer(&mut self, viewer_token: u64) -> Result<bool, String> {
        Ok(self.required_gpu0_mut()?.detach_viewer(viewer_token))
    }

    pub fn accept_retained_display_serverbound(
        &mut self,
        viewer_token: u64,
        payload: &[u8],
    ) -> Result<i32, String> {
        let outcome = self
            .required_gpu0_mut()?
            .accept_serverbound(viewer_token, payload)
            .map_err(|error| error.to_string())?;
        Ok(match outcome {
            ServerboundOutcome::Acknowledged => 1,
            ServerboundOutcome::Resynchronized { .. } => 2,
            ServerboundOutcome::ReattachRequired => 3,
            ServerboundOutcome::Rejected(ServerboundRejection::UnknownViewer) => -1,
            ServerboundOutcome::Rejected(ServerboundRejection::Malformed) => -2,
            ServerboundOutcome::Rejected(ServerboundRejection::AckMismatch) => -3,
        })
    }

    pub fn drain_retained_display_payload(
        &mut self,
        viewer_token: u64,
    ) -> Result<Option<Vec<u8>>, String> {
        Ok(self.required_gpu0_mut()?.drain_payload(viewer_token))
    }

    pub fn drain_retained_display_payloads(&mut self) -> Result<Option<Vec<u8>>, String> {
        self.required_gpu0_mut()?
            .drain_payload_batch()
            .map_err(|error| error.to_string())
    }

    pub fn advance_retained_display_tick(&mut self) -> Result<(), String> {
        self.required_gpu0_mut()?
            .advance_tick()
            .map_err(|error| error.to_string())
    }

    pub fn gpu0_authoritative_payload_bytes(&self) -> usize {
        self.gpu0_device()
            .map(GpuDevice::authoritative_payload_bytes)
            .unwrap_or_default()
    }

    pub fn advance_game_tick(&mut self) {
        let game_ticks = if let Some(timer0) = self.timer0_device_mut() {
            timer0.advance_game_tick();
            Some(timer0.game_ticks())
        } else {
            None
        };
        if let Some(game_ticks) = game_ticks {
            self.request_boot_cpu_interrupt(K16_INTERRUPT_SOURCE_TIMER0, game_ticks as u32);
        }
    }

    pub fn timer0_game_ticks(&self) -> Option<u64> {
        self.timer0_device().map(TimerDevice::game_ticks)
    }

    fn push_memory_map_region(
        &self,
        map: &mut ComputerMemoryMap,
        descriptor: ComputerDeviceDescriptor,
    ) {
        let Some(device_id) = descriptor.id else {
            return;
        };
        let Some((base, size)) = self.bus.mmio_region_bounds(device_id) else {
            return;
        };
        map.regions.push(ComputerMemoryRegion {
            name: descriptor.name,
            base,
            size,
            readable: descriptor.readable,
            writable: descriptor.writable,
        });
    }

    fn push_stats_device(
        &self,
        bus: &MachineBusStatsSnapshot,
        devices: &mut Vec<K16ComputerDeviceStats>,
        descriptor: ComputerDeviceDescriptor,
    ) {
        let Some(device_id) = descriptor.id else {
            return;
        };
        let Some(device) = bus
            .mmio_devices
            .iter()
            .find(|device| device.device_id == device_id)
        else {
            return;
        };
        devices.push(K16ComputerDeviceStats {
            name: descriptor.name,
            device_id,
            base: device.base,
            size: device.size,
            traffic: device.traffic,
            storage: self.stats_storage_snapshot(descriptor.stats_kind),
            gpu: self.stats_gpu_snapshot(descriptor.stats_kind),
        });
    }

    fn stats_storage_snapshot(
        &self,
        stats_kind: ComputerDeviceStatsKind,
    ) -> K16ComputerStorageStatsSnapshot {
        match stats_kind {
            ComputerDeviceStatsKind::Storage0 => self
                .storage0_device()
                .map(StoragePortDevice::stats_snapshot)
                .unwrap_or_default(),
            ComputerDeviceStatsKind::Storage1 => self
                .storage1_device()
                .map(StoragePortDevice::stats_snapshot)
                .unwrap_or_default(),
            ComputerDeviceStatsKind::Generic | ComputerDeviceStatsKind::Gpu => {
                K16ComputerStorageStatsSnapshot::default()
            }
        }
    }

    fn stats_gpu_snapshot(
        &self,
        stats_kind: ComputerDeviceStatsKind,
    ) -> K16ComputerGpuStatsSnapshot {
        match stats_kind {
            ComputerDeviceStatsKind::Gpu => self
                .gpu0_device()
                .map(GpuDevice::stats_snapshot)
                .unwrap_or_default(),
            ComputerDeviceStatsKind::Generic
            | ComputerDeviceStatsKind::Storage0
            | ComputerDeviceStatsKind::Storage1 => K16ComputerGpuStatsSnapshot::default(),
        }
    }

    fn control_device(&self) -> Option<&ComputerControlDevice> {
        self.devices.control(&self.bus)
    }

    fn debug_device(&self) -> Option<&DebugSerialDevice> {
        self.devices.debug_serial(&self.bus)
    }

    fn debug_device_mut(&mut self) -> Option<&mut DebugSerialDevice> {
        self.devices.debug_serial_mut(&mut self.bus)
    }

    fn serial_input_device(&self) -> Option<&SerialInputDevice> {
        self.devices.serial_input(&self.bus)
    }

    fn serial_input_device_mut(&mut self) -> Option<&mut SerialInputDevice> {
        self.devices.serial_input_mut(&mut self.bus)
    }

    fn gpu0_device_mut(&mut self) -> Option<&mut GpuDevice> {
        self.devices.gpu0_mut(&mut self.bus)
    }

    fn gpu0_device(&self) -> Option<&GpuDevice> {
        self.devices.gpu0(&self.bus)
    }

    fn required_gpu0_mut(&mut self) -> Result<&mut GpuDevice, String> {
        self.gpu0_device_mut()
            .ok_or_else(|| "computer profile has no gpu0 retained display".to_string())
    }

    fn mmu0_device_mut(&mut self) -> Option<&mut MmuControlDevice> {
        self.devices.mmu0_mut(&mut self.bus)
    }

    fn storage0_device(&self) -> Option<&StoragePortDevice> {
        self.devices.storage0(&self.bus)
    }

    fn storage1_device(&self) -> Option<&StoragePortDevice> {
        self.devices.storage1(&self.bus)
    }

    fn timer0_device(&self) -> Option<&TimerDevice> {
        self.devices.timer0(&self.bus)
    }

    fn timer0_device_mut(&mut self) -> Option<&mut TimerDevice> {
        self.devices.timer0_mut(&mut self.bus)
    }

    fn keyboard0_device(&self) -> Option<&KeyboardDevice> {
        self.devices.keyboard0(&self.bus)
    }

    fn keyboard0_device_mut(&mut self) -> Option<&mut KeyboardDevice> {
        self.devices.keyboard0_mut(&mut self.bus)
    }

    fn request_boot_cpu_interrupt(&mut self, source: u32, value: u32) {
        let Some(cpu_id) = self.boot_cpu else {
            return;
        };
        let Some(cpu) = self.cpus.get_mut(cpu_id) else {
            return;
        };
        match cpu {
            ComputerCpuContext::K16 { cpu, .. } => cpu.request_interrupt(source, value),
        }
    }

    fn request_keyboard0_interrupt_if_ready(&mut self, became_ready: bool) {
        if became_ready {
            self.request_boot_cpu_interrupt(K16_INTERRUPT_SOURCE_KEYBOARD0, 0);
        }
    }

    fn k16_cpu_mut(&mut self, cpu_id: CpuId) -> Result<&mut K16Cpu, String> {
        let cpu = self
            .cpus
            .get_mut(cpu_id)
            .ok_or_else(|| format!("CPU {cpu_id} is not present"))?;
        match cpu {
            ComputerCpuContext::K16 { cpu, .. } => Ok(cpu),
        }
    }

    pub(super) fn clear_k16_decode_cache(&mut self, cpu_id: CpuId) -> Result<(), String> {
        let cpu = self
            .cpus
            .get_mut(cpu_id)
            .ok_or_else(|| format!("CPU {cpu_id} is not present"))?;
        match cpu {
            ComputerCpuContext::K16 { decoder, .. } => decoder.clear(),
        }
        Ok(())
    }

    fn control_device_mut(&mut self) -> Option<&mut ComputerControlDevice> {
        self.devices.control_mut(&mut self.bus)
    }

    #[cfg(test)]
    pub(crate) fn install_k16_boot_cpu_for_tests(
        &mut self,
        entry_pc: u32,
        max_steps: u64,
    ) -> CpuId {
        let cpu_id = self.cpus.len();
        self.cpus.push(ComputerCpuContext::K16 {
            cpu: K16Cpu::new(entry_pc),
            decoder: K16CachedDecoder::new(),
            max_steps: max_steps.max(1),
        });
        self.boot_cpu = Some(cpu_id);
        cpu_id
    }

    #[cfg(test)]
    pub(crate) fn k16_decode_cache_stats_for_tests(
        &self,
        cpu_id: CpuId,
    ) -> Result<crate::k16::K16DecodeCacheStats, String> {
        let cpu = self
            .cpus
            .get(cpu_id)
            .ok_or_else(|| format!("CPU {cpu_id} is not present"))?;
        match cpu {
            ComputerCpuContext::K16 { decoder, .. } => Ok(decoder.stats()),
        }
    }
}

fn checked_ram_range(
    image_addr: u32,
    image_len: u32,
    ram_len: usize,
) -> Result<usize, BootHandoffError> {
    let image_end =
        image_addr
            .checked_add(image_len)
            .ok_or(BootHandoffError::RamRangeOverflow {
                image_addr,
                image_len,
            })?;
    let start = image_addr as usize;
    let end = image_end as usize;
    if start > ram_len || end > ram_len {
        return Err(BootHandoffError::RamRangeOutOfBounds {
            image_addr,
            image_len,
            ram_len,
        });
    }
    Ok(end)
}

#[cfg(test)]
mod tests;
