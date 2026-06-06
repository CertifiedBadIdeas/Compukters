use crate::computer::devices::{
    ComputerControlDevice, ComputerTextDisplaySnapshot, DebugSerialDevice, FramebufferDevice,
    SerialInputDevice, StoragePortDevice, TextDisplayDevice, TimerDevice,
};
use crate::computer::profile::ComputerMachineProfile;
use crate::computer_abi;
use crate::display::DisplayFrameDelta;
use crate::k16::{K16Cpu, K16Signal, K16_INTERRUPT_SOURCE_TIMER0};
use crate::low_bus::{MachineBus, MmioDeviceId};
use crate::low_machine::{MachineMemory, MemoryFault};
use std::fmt::{Display, Formatter};

mod boot_flow;
mod construction;
mod snapshot_flow;

pub type CpuId = usize;

pub struct ComputerMachine {
    bus: MachineBus,
    control_device_id: Option<MmioDeviceId>,
    debug_device_id: Option<MmioDeviceId>,
    serial_input_device_id: Option<MmioDeviceId>,
    display0_device_id: Option<MmioDeviceId>,
    framebuffer0_device_id: Option<MmioDeviceId>,
    storage0_device_id: Option<MmioDeviceId>,
    timer0_device_id: Option<MmioDeviceId>,
    bios_flash_device_id: Option<MmioDeviceId>,
    cpus: Vec<ComputerCpuContext>,
    boot_cpu: Option<CpuId>,
}

enum ComputerCpuContext {
    K16 { cpu: K16Cpu, max_steps: u64 },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BootHandoffError {
    MissingBootCpu,
    EmptyImage,
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
    pub const HARDWARE_ID_DISPLAY0: u32 = computer_abi::COMPUTER_HARDWARE_ID_DISPLAY0;
    pub const HARDWARE_ID_STORAGE0: u32 = computer_abi::COMPUTER_HARDWARE_ID_STORAGE0;
    pub const HARDWARE_ID_FRAMEBUFFER0: u32 = computer_abi::COMPUTER_HARDWARE_ID_FRAMEBUFFER0;
    pub const HARDWARE_ID_TIMER0: u32 = computer_abi::COMPUTER_HARDWARE_ID_TIMER0;
    pub const CONTROL_BASE: u32 = computer_abi::CONTROL_BASE;
    pub const CONTROL_STATUS: u32 = computer_abi::CONTROL_STATUS;
    pub const CONTROL_PANIC_CODE: u32 = computer_abi::CONTROL_PANIC_CODE;
    pub const CONTROL_EXIT_CODE: u32 = computer_abi::CONTROL_EXIT_CODE;
    pub const CONTROL_YIELD: u32 = computer_abi::CONTROL_YIELD;
    pub const CONTROL_SIZE: u32 = computer_abi::CONTROL_SIZE;
    pub const DEBUG_BASE: u32 = computer_abi::DEBUG_BASE;
    pub const DEBUG_WRITE: u32 = computer_abi::DEBUG_WRITE;
    pub const DEBUG_SIZE: u32 = computer_abi::DEBUG_SIZE;
    pub const SERIAL_INPUT_BASE: u32 = computer_abi::SERIAL_INPUT_BASE;
    pub const SERIAL_INPUT_READY: u32 = computer_abi::SERIAL_INPUT_READY;
    pub const SERIAL_INPUT_READ: u32 = computer_abi::SERIAL_INPUT_READ;
    pub const SERIAL_INPUT_SIZE: u32 = computer_abi::SERIAL_INPUT_SIZE;
    pub const DISPLAY0_BASE: u32 = computer_abi::DISPLAY0_BASE;
    pub const DISPLAY0_COLUMNS: u32 = computer_abi::DISPLAY0_COLUMNS;
    pub const DISPLAY0_ROWS: u32 = computer_abi::DISPLAY0_ROWS;
    pub const DISPLAY0_CURSOR_X: u32 = computer_abi::DISPLAY0_CURSOR_X;
    pub const DISPLAY0_CURSOR_Y: u32 = computer_abi::DISPLAY0_CURSOR_Y;
    pub const DISPLAY0_COMMAND: u32 = computer_abi::DISPLAY0_COMMAND;
    pub const DISPLAY0_DATA: u32 = computer_abi::DISPLAY0_DATA;
    pub const DISPLAY0_SEQUENCE_LOW: u32 = computer_abi::DISPLAY0_SEQUENCE_LOW;
    pub const DISPLAY0_SEQUENCE_HIGH: u32 = computer_abi::DISPLAY0_SEQUENCE_HIGH;
    pub const DISPLAY0_SIZE: u32 = computer_abi::DISPLAY0_SIZE;
    pub const DISPLAY0_COMMAND_CLEAR: i32 = computer_abi::DISPLAY0_COMMAND_CLEAR;
    pub const DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR: i32 =
        computer_abi::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR;
    pub const DISPLAY0_COMMAND_PUT_BYTE_AT_XY: i32 = computer_abi::DISPLAY0_COMMAND_PUT_BYTE_AT_XY;
    pub const DISPLAY0_COMMAND_NEWLINE: i32 = computer_abi::DISPLAY0_COMMAND_NEWLINE;
    pub const FRAMEBUFFER0_BASE: u32 = computer_abi::FRAMEBUFFER0_BASE;
    pub const FRAMEBUFFER0_WIDTH: u32 = computer_abi::FRAMEBUFFER0_WIDTH;
    pub const FRAMEBUFFER0_HEIGHT: u32 = computer_abi::FRAMEBUFFER0_HEIGHT;
    pub const FRAMEBUFFER0_STRIDE_BYTES: u32 = computer_abi::FRAMEBUFFER0_STRIDE_BYTES;
    pub const FRAMEBUFFER0_PIXEL_FORMAT: u32 = computer_abi::FRAMEBUFFER0_PIXEL_FORMAT;
    pub const FRAMEBUFFER0_COMMAND: u32 = computer_abi::FRAMEBUFFER0_COMMAND;
    pub const FRAMEBUFFER0_STATUS: u32 = computer_abi::FRAMEBUFFER0_STATUS;
    pub const FRAMEBUFFER0_ERROR: u32 = computer_abi::FRAMEBUFFER0_ERROR;
    pub const FRAMEBUFFER0_X: u32 = computer_abi::FRAMEBUFFER0_X;
    pub const FRAMEBUFFER0_Y: u32 = computer_abi::FRAMEBUFFER0_Y;
    pub const FRAMEBUFFER0_RECT_WIDTH: u32 = computer_abi::FRAMEBUFFER0_RECT_WIDTH;
    pub const FRAMEBUFFER0_RECT_HEIGHT: u32 = computer_abi::FRAMEBUFFER0_RECT_HEIGHT;
    pub const FRAMEBUFFER0_BUFFER_ADDR: u32 = computer_abi::FRAMEBUFFER0_BUFFER_ADDR;
    pub const FRAMEBUFFER0_BUFFER_STRIDE_BYTES: u32 =
        computer_abi::FRAMEBUFFER0_BUFFER_STRIDE_BYTES;
    pub const FRAMEBUFFER0_COLOR: u32 = computer_abi::FRAMEBUFFER0_COLOR;
    pub const FRAMEBUFFER0_SEQUENCE_LOW: u32 = computer_abi::FRAMEBUFFER0_SEQUENCE_LOW;
    pub const FRAMEBUFFER0_SEQUENCE_HIGH: u32 = computer_abi::FRAMEBUFFER0_SEQUENCE_HIGH;
    pub const FRAMEBUFFER0_SIZE: u32 = computer_abi::FRAMEBUFFER0_SIZE;
    pub const FRAMEBUFFER0_PIXEL_FORMAT_RGB565: i32 =
        computer_abi::FRAMEBUFFER0_PIXEL_FORMAT_RGB565;
    pub const FRAMEBUFFER0_STATUS_READY: i32 = computer_abi::FRAMEBUFFER0_STATUS_READY;
    pub const FRAMEBUFFER0_STATUS_DONE: i32 = computer_abi::FRAMEBUFFER0_STATUS_DONE;
    pub const FRAMEBUFFER0_STATUS_ERROR: i32 = computer_abi::FRAMEBUFFER0_STATUS_ERROR;
    pub const FRAMEBUFFER0_ERROR_NONE: i32 = computer_abi::FRAMEBUFFER0_ERROR_NONE;
    pub const FRAMEBUFFER0_ERROR_INVALID_COMMAND: i32 =
        computer_abi::FRAMEBUFFER0_ERROR_INVALID_COMMAND;
    pub const FRAMEBUFFER0_ERROR_BUFFER_OUT_OF_BOUNDS: i32 =
        computer_abi::FRAMEBUFFER0_ERROR_BUFFER_OUT_OF_BOUNDS;
    pub const FRAMEBUFFER0_ERROR_INVALID_RECT: i32 = computer_abi::FRAMEBUFFER0_ERROR_INVALID_RECT;
    pub const FRAMEBUFFER0_ERROR_INVALID_STRIDE: i32 =
        computer_abi::FRAMEBUFFER0_ERROR_INVALID_STRIDE;
    pub const FRAMEBUFFER0_COMMAND_NOP: i32 = computer_abi::FRAMEBUFFER0_COMMAND_NOP;
    pub const FRAMEBUFFER0_COMMAND_CLEAR: i32 = computer_abi::FRAMEBUFFER0_COMMAND_CLEAR;
    pub const FRAMEBUFFER0_COMMAND_BLIT_BUFFER: i32 =
        computer_abi::FRAMEBUFFER0_COMMAND_BLIT_BUFFER;
    pub const FRAMEBUFFER0_COMMAND_PRESENT: i32 = computer_abi::FRAMEBUFFER0_COMMAND_PRESENT;
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
    pub const TIMER0_BASE: u32 = computer_abi::TIMER0_BASE;
    pub const TIMER0_VERSION: u32 = computer_abi::TIMER0_VERSION;
    pub const TIMER0_GAME_TICKS_LOW: u32 = computer_abi::TIMER0_GAME_TICKS_LOW;
    pub const TIMER0_GAME_TICKS_HIGH: u32 = computer_abi::TIMER0_GAME_TICKS_HIGH;
    pub const TIMER0_MONOTONIC_NANOS_LOW: u32 = computer_abi::TIMER0_MONOTONIC_NANOS_LOW;
    pub const TIMER0_MONOTONIC_NANOS_HIGH: u32 = computer_abi::TIMER0_MONOTONIC_NANOS_HIGH;
    pub const TIMER0_SIZE: u32 = computer_abi::TIMER0_SIZE;
    pub const TIMER0_VERSION_VALUE: i32 = computer_abi::TIMER0_VERSION_VALUE;
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
        self.push_memory_map_region(&mut map, self.control_device_id, "control");
        self.push_memory_map_region(&mut map, self.debug_device_id, "debug");
        self.push_memory_map_region(&mut map, self.serial_input_device_id, "serial-input");
        self.push_memory_map_region(&mut map, self.display0_device_id, "display0");
        self.push_memory_map_region(&mut map, self.framebuffer0_device_id, "framebuffer0");
        self.push_memory_map_region(&mut map, self.storage0_device_id, "storage0");
        self.push_memory_map_region(&mut map, self.timer0_device_id, "timer0");
        self.push_memory_map_region_with_flags(
            &mut map,
            self.bios_flash_device_id,
            "bios-flash",
            true,
            false,
        );
        map
    }

    pub fn snapshot_v1(&self) -> Result<Vec<u8>, String> {
        snapshot_flow::snapshot_v1(self)
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
        self.storage0_device_id
            .and_then(|id| self.bus.device::<StoragePortDevice>(id))
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

    pub fn boot_cpu_id(&self) -> Option<CpuId> {
        self.boot_cpu
    }

    pub fn cpu_count(&self) -> usize {
        self.cpus.len()
    }

    pub fn run_boot_k16_until_signal(&mut self, cpu_id: CpuId) -> Result<K16Signal, String> {
        boot_flow::run_boot_k16_until_signal(self, cpu_id)
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

    pub fn display0_snapshot(&self) -> Option<ComputerTextDisplaySnapshot> {
        self.display0_device().map(TextDisplayDevice::snapshot)
    }

    pub fn display0_sequence(&self) -> Option<u64> {
        self.display0_device().map(TextDisplayDevice::sequence)
    }

    pub fn drain_framebuffer0_frames(&mut self) -> Vec<DisplayFrameDelta> {
        self.framebuffer0_device_mut()
            .map(FramebufferDevice::drain_frames)
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
        device_id: Option<MmioDeviceId>,
        name: &'static str,
    ) {
        self.push_memory_map_region_with_flags(map, device_id, name, true, true);
    }

    fn push_memory_map_region_with_flags(
        &self,
        map: &mut ComputerMemoryMap,
        device_id: Option<MmioDeviceId>,
        name: &'static str,
        readable: bool,
        writable: bool,
    ) {
        let Some(device_id) = device_id else {
            return;
        };
        let Some((base, size)) = self.bus.mmio_region_bounds(device_id) else {
            return;
        };
        map.regions.push(ComputerMemoryRegion {
            name,
            base,
            size,
            readable,
            writable,
        });
    }

    pub(crate) fn map_k16_bios_flash(&mut self, bytes: Vec<u8>) -> Result<(), String> {
        boot_flow::map_k16_bios_flash(self, bytes)
    }

    fn control_device(&self) -> Option<&ComputerControlDevice> {
        self.control_device_id
            .and_then(|id| self.bus.device::<ComputerControlDevice>(id))
    }

    fn debug_device(&self) -> Option<&DebugSerialDevice> {
        self.debug_device_id
            .and_then(|id| self.bus.device::<DebugSerialDevice>(id))
    }

    fn debug_device_mut(&mut self) -> Option<&mut DebugSerialDevice> {
        self.debug_device_id
            .and_then(|id| self.bus.device_mut::<DebugSerialDevice>(id))
    }

    fn serial_input_device(&self) -> Option<&SerialInputDevice> {
        self.serial_input_device_id
            .and_then(|id| self.bus.device::<SerialInputDevice>(id))
    }

    fn serial_input_device_mut(&mut self) -> Option<&mut SerialInputDevice> {
        self.serial_input_device_id
            .and_then(|id| self.bus.device_mut::<SerialInputDevice>(id))
    }

    fn display0_device(&self) -> Option<&TextDisplayDevice> {
        self.display0_device_id
            .and_then(|id| self.bus.device::<TextDisplayDevice>(id))
    }

    fn display0_device_mut(&mut self) -> Option<&mut TextDisplayDevice> {
        self.display0_device_id
            .and_then(|id| self.bus.device_mut::<TextDisplayDevice>(id))
    }

    fn framebuffer0_device_mut(&mut self) -> Option<&mut FramebufferDevice> {
        self.framebuffer0_device_id
            .and_then(|id| self.bus.device_mut::<FramebufferDevice>(id))
    }

    fn storage0_device(&self) -> Option<&StoragePortDevice> {
        self.storage0_device_id
            .and_then(|id| self.bus.device::<StoragePortDevice>(id))
    }

    fn storage0_device_mut(&mut self) -> Option<&mut StoragePortDevice> {
        self.storage0_device_id
            .and_then(|id| self.bus.device_mut::<StoragePortDevice>(id))
    }

    fn timer0_device(&self) -> Option<&TimerDevice> {
        self.timer0_device_id
            .and_then(|id| self.bus.device::<TimerDevice>(id))
    }

    fn timer0_device_mut(&mut self) -> Option<&mut TimerDevice> {
        self.timer0_device_id
            .and_then(|id| self.bus.device_mut::<TimerDevice>(id))
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

    fn control_device_mut(&mut self) -> Option<&mut ComputerControlDevice> {
        self.control_device_id
            .and_then(|id| self.bus.device_mut::<ComputerControlDevice>(id))
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
