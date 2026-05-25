use crate::computer::devices::{
    ComputerControlDevice, ComputerTextDisplaySnapshot, DebugSerialDevice,
    RuxVolumeFileStorageMedia, SerialInputDevice, StoragePortDevice, TextDisplayDevice,
};
use crate::computer::profile::{
    validate_profile_v2, ComputerHardwareDevice, ComputerMachineProfile, HardwareTableEntry,
    StorageMediaConfig,
};
use crate::computer_abi;
use crate::low_bus::{MachineBus, MmioDeviceId};
use crate::low_image::{decode_image, Image};
use crate::low_image_runner::{LowCpuContext, LowImageSignal, LowImageVm};
use crate::low_machine::{MachineMemory, MemoryFault};
use crate::rux16::{Rux16Cpu, Rux16Signal};
use std::fmt::{Display, Formatter};

pub type CpuId = usize;

pub struct ComputerMachine {
    bus: MachineBus,
    control_device_id: Option<MmioDeviceId>,
    debug_device_id: Option<MmioDeviceId>,
    serial_input_device_id: Option<MmioDeviceId>,
    display0_device_id: Option<MmioDeviceId>,
    storage0_device_id: Option<MmioDeviceId>,
    program_base: u32,
    cpus: Vec<ComputerCpuContext>,
    boot_cpu: Option<CpuId>,
}

enum ComputerCpuContext {
    LowImage(LowCpuContext),
    Rux16 { cpu: Rux16Cpu, max_steps: u64 },
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
    InvalidImage(String),
    ImageTooLarge(String),
    MachineState(String),
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
            Self::InvalidImage(message) => {
                write!(formatter, "invalid boot handoff image: {message}")
            }
            Self::ImageTooLarge(message) => {
                write!(formatter, "boot handoff image is too large: {message}")
            }
            Self::MachineState(message) => {
                write!(formatter, "boot handoff machine state error: {message}")
            }
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
    pub const CONTROL_BASE: u32 = computer_abi::CONTROL_BASE;
    pub const CONTROL_STATUS: u32 = computer_abi::CONTROL_STATUS;
    pub const CONTROL_PANIC_CODE: u32 = computer_abi::CONTROL_PANIC_CODE;
    pub const CONTROL_EXIT_CODE: u32 = computer_abi::CONTROL_EXIT_CODE;
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
    pub const STATUS_RESET: i32 = computer_abi::STATUS_RESET;
    pub const STATUS_BOOTING: i32 = computer_abi::STATUS_BOOTING;
    pub const STATUS_READY: i32 = computer_abi::STATUS_READY;
    pub const STATUS_HALTED: i32 = computer_abi::STATUS_HALTED;
    pub const STATUS_PANIC: i32 = computer_abi::STATUS_PANIC;

    pub fn new(memory_size: usize) -> Result<Self, MemoryFault> {
        Self::from_profile(ComputerMachineProfile::computer_v1(memory_size))
    }

    pub fn from_profile(profile: ComputerMachineProfile) -> Result<Self, MemoryFault> {
        validate_profile_v2(&profile)?;
        let mut bus = MachineBus::new(profile.memory_size)?;
        let mut control_device_id = None;
        let mut debug_device_id = None;
        let mut serial_input_device_id = None;
        let mut display0_device_id = None;
        let mut storage0_device_id = None;
        let hardware_entries = profile
            .hardware
            .iter()
            .map(|hardware| HardwareTableEntry {
                id: hardware.id,
                mmio_base: hardware.mmio_base,
                mmio_size: hardware.mmio_size(),
            })
            .collect::<Vec<_>>();

        for hardware in &profile.hardware {
            let device_id = match &hardware.device {
                ComputerHardwareDevice::Control => {
                    bus.map_mmio(hardware.mmio_base, Box::new(ComputerControlDevice::new()))?
                }
                ComputerHardwareDevice::DebugSerial => {
                    bus.map_mmio(hardware.mmio_base, Box::new(DebugSerialDevice::new()))?
                }
                ComputerHardwareDevice::SerialInput => {
                    bus.map_mmio(hardware.mmio_base, Box::new(SerialInputDevice::new()))?
                }
                ComputerHardwareDevice::TextDisplay => {
                    bus.map_mmio(hardware.mmio_base, Box::new(TextDisplayDevice::new()))?
                }
                ComputerHardwareDevice::StoragePort(config) => {
                    let device = match &config.media {
                        Some(StorageMediaConfig::InMemory { bytes, read_only }) => {
                            StoragePortDevice::with_media(bytes.clone(), *read_only)?
                        }
                        Some(StorageMediaConfig::RuxVolumeFile { path }) => {
                            StoragePortDevice::with_media_backend(Box::new(
                                RuxVolumeFileStorageMedia::open(path)?,
                            ))?
                        }
                        None => StoragePortDevice::new_absent(),
                    };
                    bus.map_mmio(hardware.mmio_base, Box::new(device))?
                }
            };
            match &hardware.device {
                ComputerHardwareDevice::Control => control_device_id = Some(device_id),
                ComputerHardwareDevice::DebugSerial => debug_device_id = Some(device_id),
                ComputerHardwareDevice::SerialInput => serial_input_device_id = Some(device_id),
                ComputerHardwareDevice::TextDisplay => display0_device_id = Some(device_id),
                ComputerHardwareDevice::StoragePort(_) => storage0_device_id = Some(device_id),
            }
        }

        write_profile_v2_boot_info(&mut bus, &profile, &hardware_entries)?;
        Ok(Self {
            bus,
            control_device_id,
            debug_device_id,
            serial_input_device_id,
            display0_device_id,
            storage0_device_id,
            program_base: profile.program_base,
            cpus: Vec::new(),
            boot_cpu: None,
        })
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
        self.push_memory_map_region(&mut map, self.storage0_device_id, "storage0");
        map
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

    pub fn spawn_cpu(&mut self, image: Image, slice_budget_nanos: u64) -> Result<CpuId, String> {
        let required_memory = usize::try_from(image.memory_size)
            .map_err(|_| "memory size does not fit usize".to_string())?;
        if self.bus.memory().len() < required_memory {
            return Err(format!(
                "image requires {required_memory} bytes but machine memory has {} bytes",
                self.bus.memory().len(),
            ));
        }
        load_image_sections_into_bus_at(&image, &mut self.bus, self.program_base)?;
        let cpu = LowImageVm::create_cpu_context(image, slice_budget_nanos)?;
        let cpu_id = self.cpus.len();
        self.cpus.push(ComputerCpuContext::LowImage(cpu));
        Ok(cpu_id)
    }

    pub fn spawn_boot_cpu(
        &mut self,
        kernel_image: Image,
        slice_budget_nanos: u64,
    ) -> Result<CpuId, String> {
        if self.boot_cpu.is_some() {
            return Err("boot CPU is already spawned".to_string());
        }
        let cpu_id = self.spawn_cpu(kernel_image, slice_budget_nanos)?;
        self.boot_cpu = Some(cpu_id);
        Ok(cpu_id)
    }

    pub fn boot_handoff_ruxi_from_ram(
        &mut self,
        image_addr: u32,
        image_len: u32,
        slice_budget_nanos: u64,
    ) -> Result<CpuId, BootHandoffError> {
        let boot_cpu = self.boot_cpu.ok_or(BootHandoffError::MissingBootCpu)?;
        let image_bytes = self.boot_handoff_image_bytes(image_addr, image_len)?;
        let image = decode_image(&image_bytes)
            .map_err(|error| BootHandoffError::InvalidImage(error.to_string()))?;
        let required_memory = usize::try_from(image.memory_size).map_err(|_| {
            BootHandoffError::ImageTooLarge("memory size does not fit usize".to_string())
        })?;
        if self.bus.memory().len() < required_memory {
            return Err(BootHandoffError::ImageTooLarge(format!(
                "image requires {required_memory} bytes but machine memory has {} bytes",
                self.bus.memory().len(),
            )));
        }
        let next_cpu = LowImageVm::create_cpu_context(image.clone(), slice_budget_nanos.max(1))
            .map_err(BootHandoffError::InvalidImage)?;
        load_image_sections_into_bus_at(&image, &mut self.bus, self.program_base)
            .map_err(BootHandoffError::MachineState)?;
        self.cpus[boot_cpu] = ComputerCpuContext::LowImage(next_cpu);
        Ok(boot_cpu)
    }

    pub fn boot_handoff_rux16_from_ram(
        &mut self,
        entry_pc: u32,
        byte_len: u32,
        max_steps: u64,
    ) -> Result<CpuId, BootHandoffError> {
        let boot_cpu = self.boot_cpu.ok_or(BootHandoffError::MissingBootCpu)?;
        if byte_len == 0 {
            return Err(BootHandoffError::EmptyImage);
        }
        checked_ram_range(entry_pc, byte_len, self.bus.memory().len())?;
        self.cpus[boot_cpu] = ComputerCpuContext::Rux16 {
            cpu: Rux16Cpu::new(entry_pc),
            max_steps: max_steps.max(1),
        };
        Ok(boot_cpu)
    }

    pub fn boot_cpu_id(&self) -> Option<CpuId> {
        self.boot_cpu
    }

    pub fn cpu_count(&self) -> usize {
        self.cpus.len()
    }

    pub fn run_cpu_until_signal(&mut self, cpu_id: CpuId) -> Result<LowImageSignal, String> {
        let cpu = self
            .cpus
            .get_mut(cpu_id)
            .ok_or_else(|| format!("CPU {cpu_id} is not present"))?;
        match cpu {
            ComputerCpuContext::LowImage(cpu) => cpu.run_until_signal(&mut self.bus),
            ComputerCpuContext::Rux16 { .. } => Err(format!("CPU {cpu_id} is not a LowImage CPU")),
        }
    }

    pub fn run_boot_cpu_until_signal(&mut self, cpu_id: CpuId) -> Result<LowImageSignal, String> {
        if self.boot_cpu != Some(cpu_id) {
            return Err(format!("CPU {cpu_id} is not the boot CPU"));
        }
        let signal = self.run_cpu_until_signal(cpu_id);
        match &signal {
            Ok(LowImageSignal::HaltUnit) => {
                self.set_halted_exit_code(0)?;
            }
            Ok(LowImageSignal::HaltI32(exit_code)) => {
                self.set_halted_exit_code(*exit_code)?;
            }
            Ok(LowImageSignal::HaltI64(exit_code)) => {
                self.set_halted_exit_code(
                    (*exit_code).clamp(i64::from(i32::MIN), i64::from(i32::MAX)) as i32,
                )?;
            }
            Ok(LowImageSignal::HaltAddr(exit_code)) => {
                self.set_halted_exit_code(i32::from_ne_bytes(exit_code.to_ne_bytes()))?;
            }
            Ok(LowImageSignal::HaltBool(success)) => {
                self.set_halted_exit_code(if *success { 0 } else { 1 })?;
            }
            Err(message) => {
                self.set_panic_from_fault(message)?;
            }
            Ok(LowImageSignal::Pause) => {}
        }
        signal
    }

    pub fn run_boot_rux16_until_signal(&mut self, cpu_id: CpuId) -> Result<Rux16Signal, String> {
        if self.boot_cpu != Some(cpu_id) {
            return Err(format!("CPU {cpu_id} is not the boot CPU"));
        }
        let signal = {
            let cpu = self
                .cpus
                .get_mut(cpu_id)
                .ok_or_else(|| format!("CPU {cpu_id} is not present"))?;
            match cpu {
                ComputerCpuContext::Rux16 { cpu, max_steps } => cpu
                    .run_until_signal(&mut self.bus, *max_steps)
                    .map_err(|error| error.to_string()),
                ComputerCpuContext::LowImage(_) => Err(format!("CPU {cpu_id} is not a Rux16 CPU")),
            }
        };
        match &signal {
            Ok(Rux16Signal::Halt) => {
                self.set_halted_exit_code(0)?;
            }
            Ok(Rux16Signal::StepLimitExceeded) => {}
            Err(message) => {
                self.set_panic_from_fault(message)?;
            }
        }
        signal
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

    fn push_memory_map_region(
        &self,
        map: &mut ComputerMemoryMap,
        device_id: Option<MmioDeviceId>,
        name: &'static str,
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
            readable: true,
            writable: true,
        });
    }

    fn control_device(&self) -> Option<&ComputerControlDevice> {
        self.control_device_id
            .and_then(|id| self.bus.device::<ComputerControlDevice>(id))
    }

    fn boot_handoff_image_bytes(
        &self,
        image_addr: u32,
        image_len: u32,
    ) -> Result<Vec<u8>, BootHandoffError> {
        if image_len == 0 {
            return Err(BootHandoffError::EmptyImage);
        }
        let end = checked_ram_range(image_addr, image_len, self.bus.memory().len())?;
        Ok(self.bus.memory().bytes()[image_addr as usize..end].to_vec())
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

    fn control_device_mut(&mut self) -> Option<&mut ComputerControlDevice> {
        self.control_device_id
            .and_then(|id| self.bus.device_mut::<ComputerControlDevice>(id))
    }

    fn set_halted_exit_code(&mut self, exit_code: i32) -> Result<(), String> {
        if let Some(control) = self.control_device_mut() {
            control.status = Self::STATUS_HALTED;
            control.exit_code = exit_code;
        }
        Ok(())
    }

    fn set_panic_from_fault(&mut self, message: &str) -> Result<(), String> {
        if let Some(control) = self.control_device_mut() {
            control.status = Self::STATUS_PANIC;
            control.panic_code = stable_panic_code(message);
        }
        Err(message.to_string())
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

fn write_profile_v2_boot_info(
    bus: &mut MachineBus,
    profile: &ComputerMachineProfile,
    hardware_entries: &[HardwareTableEntry],
) -> Result<(), MemoryFault> {
    let hardware_table_addr = if hardware_entries.is_empty() {
        0
    } else {
        computer_abi::PROFILE_V2_BOOT_INFO_SIZE
    };
    let hardware_count = hardware_entries.len() as u32;
    let ram_size = bus.memory().len() as u32;

    write_u32(
        bus.memory_mut(),
        computer_abi::PROFILE_V2_BOOT_INFO_ADDR,
        computer_abi::PROFILE_V2_BOOT_INFO_MAGIC,
    )?;
    write_u32(bus.memory_mut(), 0x04, computer_abi::PROFILE_V2_VERSION)?;
    write_u32(bus.memory_mut(), 0x08, ram_size)?;
    write_u32(bus.memory_mut(), 0x0C, profile.page_size)?;
    write_u32(bus.memory_mut(), 0x10, profile.program_base)?;
    write_u32(bus.memory_mut(), 0x14, hardware_table_addr)?;
    write_u32(bus.memory_mut(), 0x18, hardware_count)?;

    for (index, entry) in hardware_entries.iter().enumerate() {
        write_hardware_entry(
            bus.memory_mut(),
            hardware_table_addr + computer_abi::PROFILE_V2_HARDWARE_ENTRY_SIZE * index as u32,
            entry.id,
            entry.mmio_base,
            entry.mmio_size,
        )?;
    }
    Ok(())
}

fn write_hardware_entry(
    memory: &mut MachineMemory,
    address: u32,
    id: u32,
    mmio_base: u32,
    mmio_size: u32,
) -> Result<(), MemoryFault> {
    write_u32(memory, address, id)?;
    write_u32(memory, address + 4, mmio_base)?;
    write_u32(memory, address + 8, mmio_size)
}

fn write_u32(memory: &mut MachineMemory, address: u32, value: u32) -> Result<(), MemoryFault> {
    memory.store_i32(address, i32::from_le_bytes(value.to_le_bytes()))
}

fn load_image_sections_into_bus_at(
    image: &Image,
    bus: &mut MachineBus,
    base: u32,
) -> Result<(), String> {
    let initialized = image
        .rodata
        .len()
        .checked_add(image.data.len())
        .and_then(|value| value.checked_add(image.bss_size as usize))
        .ok_or_else(|| "memory sections overflow".to_string())?;
    let initialized_end = (base as usize)
        .checked_add(initialized)
        .ok_or_else(|| "memory sections overflow".to_string())?;
    if initialized_end > bus.memory().len() {
        return Err(format!(
            "memory sections require {initialized} bytes at base {base:#010x} but machine memory has {} bytes",
            bus.memory().len(),
        ));
    }

    for (offset, byte) in image.rodata.iter().copied().enumerate() {
        bus.store_u8(base + offset as u32, byte)
            .map_err(|error| error.to_string())?;
    }
    let data_start = image.rodata.len();
    for (offset, byte) in image.data.iter().copied().enumerate() {
        bus.store_u8(base + (data_start + offset) as u32, byte)
            .map_err(|error| error.to_string())?;
    }
    let bss_start = data_start + image.data.len();
    for offset in 0..image.bss_size as usize {
        bus.store_u8(base + (bss_start + offset) as u32, 0)
            .map_err(|error| error.to_string())?;
    }
    Ok(())
}

fn stable_panic_code(message: &str) -> i32 {
    message.bytes().fold(0_i32, |hash, byte| {
        hash.wrapping_mul(31).wrapping_add(i32::from(byte))
    })
}

#[cfg(test)]
mod tests {
    use super::ComputerMachine;
    use crate::computer::devices::{
        ComputerControlDevice, DebugSerialDevice, SerialInputDevice, TextDisplayDevice,
    };
    use crate::computer::profile::{ComputerHardwareConfig, ComputerMachineProfile};
    use crate::computer_abi;
    use crate::low_bus::MmioDevice;
    use crate::low_image::{Function, Image, Instruction};
    use crate::low_image_runner::LowImageSignal;
    use crate::low_machine::MemoryFault;
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    // Legacy CKL OS research fixtures. Keep these tests as reference material, but do not
    // treat the guest process table/scheduler path as the current bare-metal MVP direction.
    const OS_STATE_BASE: u32 = 0x0001_0000;
    const OS_MAGIC: i32 = 0x434B_4F53;
    const OS_CURRENT_PID: u32 = OS_STATE_BASE + 4;
    const OS_PROCESS_COUNT: u32 = OS_STATE_BASE + 8;
    const INITIAL_PROCESS_READY: i32 = 1;
    const PROCESS_TABLE_BASE: u32 = OS_STATE_BASE + 0x100;
    const PROCESS_ENTRY_SIZE: u32 = 16;
    const PROCESS_STATE_OFFSET: u32 = 0;
    const PROCESS_ENTRY_OFFSET: u32 = 4;
    const PROCESS_STACK_PTR_OFFSET: u32 = 8;
    const PROCESS_EXIT_CODE_OFFSET: u32 = 12;
    const PROCESS_RUNNABLE: i32 = 1;
    const PROCESS_RUNNING: i32 = 2;
    const PROCESS_EXITED: i32 = 3;
    const USER_PROCESS_FUNCTION_INDEX: i32 = 1;

    struct LatchDevice {
        value: i32,
    }

    impl MmioDevice for LatchDevice {
        fn size(&self) -> u32 {
            4
        }

        fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
            assert_eq!(offset, 0);
            Ok(self.value)
        }

        fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
            assert_eq!(offset, 0);
            self.value = value;
            Ok(())
        }
    }

    #[test]
    fn computer_machine_owns_shared_physical_ram() {
        let mut machine = ComputerMachine::new(1024).unwrap();

        machine.memory_mut().store_i32(128, 42).unwrap();

        assert_eq!(machine.memory().load_i32(128).unwrap(), 42);
    }

    #[test]
    fn computer_machine_runs_cpu_contexts_against_shared_ram() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let writer = image(
            vec![
                Instruction::AddrConst { dst: 0, value: 128 },
                Instruction::I32Const { dst: 1, value: 91 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::ReturnUnit,
            ],
            2,
        );
        let reader = image(
            vec![
                Instruction::AddrConst { dst: 0, value: 128 },
                Instruction::Load32 { dst: 1, addr: 0 },
                Instruction::ReturnI32 { src: 1 },
            ],
            2,
        );

        let writer_cpu_id = machine.spawn_cpu(writer, 128).unwrap();
        assert_eq!(
            machine.run_cpu_until_signal(writer_cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        let reader_cpu_id = machine.spawn_cpu(reader, 128).unwrap();
        assert_eq!(
            machine.run_cpu_until_signal(reader_cpu_id).unwrap(),
            LowImageSignal::HaltI32(91),
        );
    }

    #[test]
    fn computer_machine_owns_boot_cpu_context() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let kernel = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::CONTROL_STATUS,
                },
                Instruction::I32Const {
                    dst: 1,
                    value: ComputerMachine::STATUS_READY,
                },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::ReturnUnit,
            ],
            2,
        );

        let cpu_id = machine.spawn_boot_cpu(kernel, 128).unwrap();

        assert_eq!(machine.boot_cpu_id(), Some(cpu_id));
        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_READY);
    }

    #[test]
    fn computer_machine_rejects_second_boot_cpu() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let first = image(vec![Instruction::ReturnUnit], 0);
        let second = image(vec![Instruction::ReturnUnit], 0);

        assert_eq!(machine.spawn_boot_cpu(first, 128).unwrap(), 0);

        let error = machine.spawn_boot_cpu(second, 128).unwrap_err();
        assert_eq!(error, "boot CPU is already spawned");
    }

    #[test]
    fn computer_machine_rejects_missing_cpu_id() {
        let mut machine = ComputerMachine::new(1024).unwrap();

        let error = machine.run_cpu_until_signal(7).unwrap_err();

        assert_eq!(error, "CPU 7 is not present");
    }

    #[test]
    fn computer_machine_runs_cpu_contexts_against_mmio_bus_devices() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let device_id = machine
            .bus
            .map_mmio(0x1000_1000, Box::new(LatchDevice { value: 0 }))
            .unwrap();
        let writer = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: 0x1000_1000,
                },
                Instruction::I32Const { dst: 1, value: 77 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::ReturnUnit,
            ],
            2,
        );
        let reader = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: 0x1000_1000,
                },
                Instruction::Load32 { dst: 1, addr: 0 },
                Instruction::ReturnI32 { src: 1 },
            ],
            2,
        );

        let writer_cpu_id = machine.spawn_cpu(writer, 128).unwrap();
        assert_eq!(
            machine.run_cpu_until_signal(writer_cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(
            machine.bus.device::<LatchDevice>(device_id).unwrap().value,
            77
        );
        let reader_cpu_id = machine.spawn_cpu(reader, 128).unwrap();
        assert_eq!(
            machine.run_cpu_until_signal(reader_cpu_id).unwrap(),
            LowImageSignal::HaltI32(77),
        );
    }

    #[test]
    fn computer_serial_input_device_reports_ready_and_consumes_bytes() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        machine.push_serial_input(b"OK");

        assert_eq!(machine.serial_input_len(), 2);
        assert_eq!(
            machine
                .bus
                .load_i32(ComputerMachine::SERIAL_INPUT_READY)
                .unwrap(),
            1
        );
        assert_eq!(
            machine
                .bus
                .load_u8(ComputerMachine::SERIAL_INPUT_READ)
                .unwrap(),
            b'O'
        );
        assert_eq!(machine.serial_input_len(), 1);
        assert_eq!(
            machine
                .bus
                .load_u8(ComputerMachine::SERIAL_INPUT_READ)
                .unwrap(),
            b'K'
        );
        assert_eq!(
            machine
                .bus
                .load_i32(ComputerMachine::SERIAL_INPUT_READY)
                .unwrap(),
            0
        );
        assert_eq!(
            machine
                .bus
                .load_u8(ComputerMachine::SERIAL_INPUT_READ)
                .unwrap(),
            0
        );
    }

    #[test]
    fn computer_machine_writes_display0_hardware_entry() {
        let machine = ComputerMachine::new(1024).unwrap();

        assert_eq!(read_u32(machine.memory(), 0x18), 5);
        assert_hardware_entry(
            machine.memory(),
            64,
            computer_abi::COMPUTER_HARDWARE_ID_DISPLAY0,
            computer_abi::DISPLAY0_BASE,
            computer_abi::DISPLAY0_SIZE,
        );
    }

    #[test]
    fn computer_display0_mmio_reports_dimensions() {
        let machine = ComputerMachine::new(1024).unwrap();

        assert_eq!(
            machine
                .bus
                .load_i32(ComputerMachine::DISPLAY0_COLUMNS)
                .unwrap(),
            80,
        );
        assert_eq!(
            machine
                .bus
                .load_i32(ComputerMachine::DISPLAY0_ROWS)
                .unwrap(),
            25,
        );
    }

    #[test]
    fn computer_display0_put_byte_updates_snapshot_and_sequence() {
        let mut machine = ComputerMachine::new(1024).unwrap();

        machine
            .bus
            .store_i32(ComputerMachine::DISPLAY0_DATA, i32::from(b'R'))
            .unwrap();
        machine
            .bus
            .store_i32(
                ComputerMachine::DISPLAY0_COMMAND,
                ComputerMachine::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR,
            )
            .unwrap();

        let snapshot = machine.display0_snapshot().unwrap();
        assert_eq!(snapshot.columns, 80);
        assert_eq!(snapshot.rows, 25);
        assert_eq!(snapshot.cursor_x, 1);
        assert_eq!(snapshot.cursor_y, 0);
        assert_eq!(snapshot.sequence, 1);
        assert_eq!(snapshot.cells[0], b'R');
    }

    #[test]
    fn computer_display0_clear_and_newline_are_deterministic() {
        let mut machine = ComputerMachine::new(1024).unwrap();

        machine
            .bus
            .store_i32(ComputerMachine::DISPLAY0_DATA, i32::from(b'A'))
            .unwrap();
        machine
            .bus
            .store_i32(
                ComputerMachine::DISPLAY0_COMMAND,
                ComputerMachine::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR,
            )
            .unwrap();
        machine
            .bus
            .store_i32(
                ComputerMachine::DISPLAY0_COMMAND,
                ComputerMachine::DISPLAY0_COMMAND_NEWLINE,
            )
            .unwrap();
        machine
            .bus
            .store_i32(ComputerMachine::DISPLAY0_DATA, i32::from(b'B'))
            .unwrap();
        machine
            .bus
            .store_i32(
                ComputerMachine::DISPLAY0_COMMAND,
                ComputerMachine::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR,
            )
            .unwrap();
        machine
            .bus
            .store_i32(
                ComputerMachine::DISPLAY0_COMMAND,
                ComputerMachine::DISPLAY0_COMMAND_CLEAR,
            )
            .unwrap();

        let snapshot = machine.display0_snapshot().unwrap();
        assert_eq!(snapshot.cursor_x, 0);
        assert_eq!(snapshot.cursor_y, 0);
        assert_eq!(snapshot.sequence, 4);
        assert!(snapshot.cells.iter().all(|cell| *cell == 0));
    }

    #[test]
    fn computer_debug_serial_output_can_be_drained_incrementally() {
        let mut machine = ComputerMachine::new(1024).unwrap();

        machine
            .bus
            .store_u8(ComputerMachine::DEBUG_WRITE, b'O')
            .unwrap();
        machine
            .bus
            .store_u8(ComputerMachine::DEBUG_WRITE, b'K')
            .unwrap();

        assert_eq!(machine.drain_debug_output_bytes(), b"OK");
        assert_eq!(machine.drain_debug_output_bytes(), b"");
    }

    #[test]
    fn computer_kernel_can_write_machine_control_status() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let kernel = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::CONTROL_STATUS,
                },
                Instruction::I32Const { dst: 1, value: 2 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::ReturnUnit,
            ],
            2,
        );

        let cpu_id = machine.spawn_cpu(kernel, 128).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.control_status(), 2);
    }

    #[test]
    fn computer_kernel_can_write_machine_panic_code() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let kernel = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::CONTROL_PANIC_CODE,
                },
                Instruction::I32Const {
                    dst: 1,
                    value: 0x55AA,
                },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::Load32 { dst: 2, addr: 0 },
                Instruction::ReturnI32 { src: 2 },
            ],
            3,
        );

        let cpu_id = machine.spawn_cpu(kernel, 128).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltI32(0x55AA),
        );
        assert_eq!(machine.panic_code(), 0x55AA);
    }

    #[test]
    fn computer_starts_in_reset_status() {
        let machine = ComputerMachine::new(1024).unwrap();

        assert_eq!(machine.control_status(), ComputerMachine::STATUS_RESET);
        assert_eq!(machine.panic_code(), 0);
    }

    #[test]
    fn computer_machine_writes_machine_profile_v2_boot_info() {
        let machine = ComputerMachine::new(1024).unwrap();

        assert_eq!(
            read_u32(machine.memory(), 0x00),
            u32::from_le_bytes(*b"RXBI")
        );
        assert_eq!(
            read_u32(machine.memory(), 0x04),
            ComputerMachine::PROFILE_V2_VERSION
        );
        assert_eq!(read_u32(machine.memory(), 0x08), 1024);
        assert_eq!(
            read_u32(machine.memory(), 0x0C),
            ComputerMachine::PROFILE_V2_PAGE_SIZE
        );
        assert_eq!(
            read_u32(machine.memory(), 0x10),
            ComputerMachine::PROFILE_V2_PROGRAM_BASE
        );
        assert_eq!(
            read_u32(machine.memory(), 0x14),
            ComputerMachine::PROFILE_V2_BOOT_INFO_SIZE
        );
        assert_eq!(read_u32(machine.memory(), 0x18), 5);
    }

    #[test]
    fn computer_machine_writes_static_hardware_table_for_mmio_ranges() {
        let machine = ComputerMachine::new(1024).unwrap();

        assert_hardware_entry(
            machine.memory(),
            28,
            computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
            computer_abi::CONTROL_BASE,
            computer_abi::PROFILE_V2_PAGE_SIZE,
        );
        assert_hardware_entry(
            machine.memory(),
            40,
            computer_abi::COMPUTER_HARDWARE_ID_DEBUG,
            computer_abi::DEBUG_BASE,
            computer_abi::PROFILE_V2_PAGE_SIZE,
        );
        assert_hardware_entry(
            machine.memory(),
            52,
            computer_abi::COMPUTER_HARDWARE_ID_SERIAL_INPUT,
            computer_abi::SERIAL_INPUT_BASE,
            computer_abi::PROFILE_V2_PAGE_SIZE,
        );
        assert_hardware_entry(
            machine.memory(),
            64,
            computer_abi::COMPUTER_HARDWARE_ID_DISPLAY0,
            computer_abi::DISPLAY0_BASE,
            computer_abi::PROFILE_V2_PAGE_SIZE,
        );
        assert_hardware_entry(
            machine.memory(),
            76,
            computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
            computer_abi::STORAGE0_BASE,
            computer_abi::PROFILE_V2_PAGE_SIZE,
        );
    }

    #[test]
    fn computer_machine_can_be_created_from_explicit_computer_v1_profile() {
        let profile = ComputerMachineProfile::computer_v1(1024);
        let machine = ComputerMachine::from_profile(profile).unwrap();

        assert_eq!(read_u32(machine.memory(), 0x18), 5);
        assert_hardware_entry(
            machine.memory(),
            28,
            computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
            computer_abi::CONTROL_BASE,
            computer_abi::PROFILE_V2_PAGE_SIZE,
        );
        assert_hardware_entry(
            machine.memory(),
            40,
            computer_abi::COMPUTER_HARDWARE_ID_DEBUG,
            computer_abi::DEBUG_BASE,
            computer_abi::PROFILE_V2_PAGE_SIZE,
        );
        assert_hardware_entry(
            machine.memory(),
            52,
            computer_abi::COMPUTER_HARDWARE_ID_SERIAL_INPUT,
            computer_abi::SERIAL_INPUT_BASE,
            computer_abi::PROFILE_V2_PAGE_SIZE,
        );
        assert_hardware_entry(
            machine.memory(),
            64,
            computer_abi::COMPUTER_HARDWARE_ID_DISPLAY0,
            computer_abi::DISPLAY0_BASE,
            computer_abi::PROFILE_V2_PAGE_SIZE,
        );
        assert_hardware_entry(
            machine.memory(),
            76,
            computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
            computer_abi::STORAGE0_BASE,
            computer_abi::PROFILE_V2_PAGE_SIZE,
        );
    }

    #[test]
    fn computer_profile_can_expose_storage0_without_attached_media() {
        let profile =
            ComputerMachineProfile::new(1024).with_hardware(ComputerHardwareConfig::storage_port(
                computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
                computer_abi::STORAGE0_BASE,
            ));

        let machine = ComputerMachine::from_profile(profile).unwrap();

        assert_eq!(read_u32(machine.memory(), 0x18), 1);
        assert_hardware_entry(
            machine.memory(),
            28,
            computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
            computer_abi::STORAGE0_BASE,
            computer_abi::STORAGE0_SIZE,
        );
        assert!(machine.memory_map().region("storage0").is_some());
    }

    #[test]
    fn storage0_absent_media_reports_zero_capacity_and_media_absent_errors() {
        let mut machine = ComputerMachine::new(1024).unwrap();

        assert_eq!(
            machine
                .bus_load_i32(computer_abi::STORAGE0_VERSION)
                .unwrap(),
            computer_abi::STORAGE_VERSION,
        );
        assert_eq!(
            machine
                .bus_load_i32(computer_abi::STORAGE0_MEDIA_STATUS)
                .unwrap(),
            computer_abi::STORAGE_MEDIA_ABSENT,
        );
        assert_eq!(
            machine
                .bus_load_i32(computer_abi::STORAGE0_CAPACITY_BLOCKS_LOW)
                .unwrap(),
            0,
        );

        machine
            .bus_store_i32(
                computer_abi::STORAGE0_COMMAND,
                computer_abi::STORAGE_COMMAND_READ_BLOCKS,
            )
            .unwrap();

        assert_eq!(
            machine.bus_load_i32(computer_abi::STORAGE0_STATUS).unwrap(),
            computer_abi::STORAGE_STATUS_ERROR,
        );
        assert_eq!(
            machine.bus_load_i32(computer_abi::STORAGE0_ERROR).unwrap(),
            computer_abi::STORAGE_ERROR_MEDIA_ABSENT,
        );
    }

    #[test]
    fn storage0_read_blocks_copies_media_into_guest_ram() {
        let media = vec![0xA5; 512];
        let profile = ComputerMachineProfile::new(2048).with_hardware(
            ComputerHardwareConfig::storage_port_with_media(
                computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
                computer_abi::STORAGE0_BASE,
                media,
                false,
            ),
        );
        let mut machine = ComputerMachine::from_profile(profile).unwrap();

        machine
            .bus_store_i32(computer_abi::STORAGE0_LBA_LOW, 0)
            .unwrap();
        machine
            .bus_store_i32(computer_abi::STORAGE0_LBA_HIGH, 0)
            .unwrap();
        machine
            .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 1)
            .unwrap();
        machine
            .bus_store_i32(computer_abi::STORAGE0_BUFFER_ADDR, 512)
            .unwrap();
        machine
            .bus_store_i32(
                computer_abi::STORAGE0_COMMAND,
                computer_abi::STORAGE_COMMAND_READ_BLOCKS,
            )
            .unwrap();

        assert_eq!(
            machine.bus_load_i32(computer_abi::STORAGE0_STATUS).unwrap(),
            computer_abi::STORAGE_STATUS_DONE,
        );
        assert_eq!(
            machine
                .bus_load_i32(computer_abi::STORAGE0_BYTES_DONE)
                .unwrap(),
            512,
        );
        assert_eq!(machine.memory().bytes()[512], 0xA5);
        assert_eq!(machine.memory().bytes()[1023], 0xA5);
    }

    #[test]
    fn storage0_write_blocks_copies_guest_ram_into_media() {
        let profile = ComputerMachineProfile::new(2048).with_hardware(
            ComputerHardwareConfig::storage_port_with_media(
                computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
                computer_abi::STORAGE0_BASE,
                vec![0; 512],
                false,
            ),
        );
        let mut machine = ComputerMachine::from_profile(profile).unwrap();
        machine.memory_mut().store_u8(512, 0x5A).unwrap();
        machine.memory_mut().store_u8(1023, 0xC3).unwrap();

        machine
            .bus_store_i32(computer_abi::STORAGE0_LBA_LOW, 0)
            .unwrap();
        machine
            .bus_store_i32(computer_abi::STORAGE0_LBA_HIGH, 0)
            .unwrap();
        machine
            .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 1)
            .unwrap();
        machine
            .bus_store_i32(computer_abi::STORAGE0_BUFFER_ADDR, 512)
            .unwrap();
        machine
            .bus_store_i32(
                computer_abi::STORAGE0_COMMAND,
                computer_abi::STORAGE_COMMAND_WRITE_BLOCKS,
            )
            .unwrap();

        let media = machine.storage0_media_bytes().unwrap();
        assert_eq!(media[0], 0x5A);
        assert_eq!(media[511], 0xC3);
    }

    #[test]
    fn storage0_file_media_write_blocks_flushes_payload_file() {
        let path = temp_volume_path("machine-storage0-file");
        write_rux_volume(&path, &[0; 512]);
        let profile = ComputerMachineProfile::new(2048).with_hardware(
            ComputerHardwareConfig::storage_port_with_rux_volume_file(
                computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
                computer_abi::STORAGE0_BASE,
                &path,
            ),
        );
        let mut machine = ComputerMachine::from_profile(profile).unwrap();
        machine.memory_mut().store_u8(512, 0x7E).unwrap();

        machine
            .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 1)
            .unwrap();
        machine
            .bus_store_i32(computer_abi::STORAGE0_BUFFER_ADDR, 512)
            .unwrap();
        machine
            .bus_store_i32(
                computer_abi::STORAGE0_COMMAND,
                computer_abi::STORAGE_COMMAND_WRITE_BLOCKS,
            )
            .unwrap();
        machine
            .bus_store_i32(
                computer_abi::STORAGE0_COMMAND,
                computer_abi::STORAGE_COMMAND_FLUSH,
            )
            .unwrap();

        let bytes = fs::read(&path).unwrap();
        assert_eq!(bytes[16], 0x7E);
        fs::remove_file(path).unwrap();
    }

    #[test]
    fn storage0_read_blocks_rejects_out_of_bounds_lba() {
        let mut machine = storage0_machine_with_media(vec![0; 512], false);

        machine
            .bus_store_i32(computer_abi::STORAGE0_LBA_LOW, 1)
            .unwrap();
        machine
            .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 1)
            .unwrap();
        machine
            .bus_store_i32(computer_abi::STORAGE0_BUFFER_ADDR, 512)
            .unwrap();
        machine
            .bus_store_i32(
                computer_abi::STORAGE0_COMMAND,
                computer_abi::STORAGE_COMMAND_READ_BLOCKS,
            )
            .unwrap();

        assert_storage_error(&machine, computer_abi::STORAGE_ERROR_LBA_OUT_OF_BOUNDS);
    }

    #[test]
    fn storage0_read_blocks_rejects_out_of_bounds_guest_buffer() {
        let mut machine = storage0_machine_with_media(vec![0; 512], false);

        machine
            .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 1)
            .unwrap();
        machine
            .bus_store_i32(computer_abi::STORAGE0_BUFFER_ADDR, 1800)
            .unwrap();
        machine
            .bus_store_i32(
                computer_abi::STORAGE0_COMMAND,
                computer_abi::STORAGE_COMMAND_READ_BLOCKS,
            )
            .unwrap();

        assert_storage_error(&machine, computer_abi::STORAGE_ERROR_BUFFER_OUT_OF_BOUNDS);
    }

    #[test]
    fn storage0_write_blocks_rejects_read_only_media() {
        let mut machine = storage0_machine_with_media(vec![0; 512], true);

        machine
            .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, 1)
            .unwrap();
        machine
            .bus_store_i32(computer_abi::STORAGE0_BUFFER_ADDR, 512)
            .unwrap();
        machine
            .bus_store_i32(
                computer_abi::STORAGE0_COMMAND,
                computer_abi::STORAGE_COMMAND_WRITE_BLOCKS,
            )
            .unwrap();

        assert_storage_error(&machine, computer_abi::STORAGE_ERROR_WRITE_PROTECTED);
    }

    #[test]
    fn storage0_invalid_command_sets_invalid_command_error() {
        let mut machine = storage0_machine_with_media(vec![0; 512], false);

        machine
            .bus_store_i32(computer_abi::STORAGE0_COMMAND, 99)
            .unwrap();

        assert_storage_error(&machine, computer_abi::STORAGE_ERROR_INVALID_COMMAND);
    }

    #[test]
    fn storage0_read_blocks_rejects_byte_count_overflow() {
        let mut machine = storage0_machine_with_media(vec![0; 512], false);

        machine
            .bus_store_i32(computer_abi::STORAGE0_BLOCK_COUNT, -1)
            .unwrap();
        machine
            .bus_store_i32(
                computer_abi::STORAGE0_COMMAND,
                computer_abi::STORAGE_COMMAND_READ_BLOCKS,
            )
            .unwrap();

        assert_storage_error(&machine, computer_abi::STORAGE_ERROR_BYTE_COUNT_OVERFLOW);
    }

    #[test]
    fn computer_machine_profile_controls_which_hardware_entries_are_visible() {
        let profile = ComputerMachineProfile::new(1024)
            .with_hardware(ComputerHardwareConfig::control(
                computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
                computer_abi::CONTROL_BASE,
            ))
            .with_hardware(ComputerHardwareConfig::debug_serial(
                computer_abi::COMPUTER_HARDWARE_ID_DEBUG,
                computer_abi::DEBUG_BASE,
            ));
        let machine = ComputerMachine::from_profile(profile).unwrap();

        assert_eq!(
            read_u32(machine.memory(), 0x14),
            ComputerMachine::PROFILE_V2_BOOT_INFO_SIZE,
        );
        assert_eq!(read_u32(machine.memory(), 0x18), 2);
        assert_hardware_entry(
            machine.memory(),
            28,
            computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
            computer_abi::CONTROL_BASE,
            computer_abi::PROFILE_V2_PAGE_SIZE,
        );
        assert_hardware_entry(
            machine.memory(),
            40,
            computer_abi::COMPUTER_HARDWARE_ID_DEBUG,
            computer_abi::DEBUG_BASE,
            computer_abi::PROFILE_V2_PAGE_SIZE,
        );
        assert!(machine.memory_map().region("display0").is_none());
        assert!(machine.display0_snapshot().is_none());
    }

    #[test]
    fn computer_machine_profile_rejects_invalid_page_sizes() {
        assert_profile_error(
            ComputerMachineProfile {
                page_size: 128,
                ..ComputerMachineProfile::new(1024)
            },
            "computer profile page size 128 is smaller than minimum 256",
        );
        assert_profile_error(
            ComputerMachineProfile {
                page_size: 384,
                ..ComputerMachineProfile::new(1152)
            },
            "computer profile page size 384 is not a power of two",
        );
        assert_profile_error(
            ComputerMachineProfile {
                page_size: 131072,
                ..ComputerMachineProfile::new(131072)
            },
            "computer profile page size 131072 exceeds maximum 65536",
        );
    }

    #[test]
    fn computer_machine_profile_rejects_invalid_program_base() {
        assert_profile_error(
            ComputerMachineProfile {
                program_base: 128,
                ..ComputerMachineProfile::new(1024)
            },
            "computer profile program base 0x00000080 is below first page size 256",
        );
        assert_profile_error(
            ComputerMachineProfile {
                program_base: 384,
                ..ComputerMachineProfile::new(1024)
            },
            "computer profile program base 0x00000180 is not aligned to page size 256",
        );
        assert_profile_error(
            ComputerMachineProfile {
                program_base: 1024,
                ..ComputerMachineProfile::new(1024)
            },
            "computer profile program base 0x00000400 is outside RAM size 1024",
        );
    }

    #[test]
    fn computer_machine_profile_rejects_invalid_hardware_ids() {
        assert_profile_error(
            ComputerMachineProfile::new(1024).with_hardware(ComputerHardwareConfig::control(
                0,
                computer_abi::CONTROL_BASE,
            )),
            "computer hardware id must be non-zero",
        );
        assert_profile_error(
            ComputerMachineProfile::new(1024)
                .with_hardware(ComputerHardwareConfig::control(
                    computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
                    computer_abi::CONTROL_BASE,
                ))
                .with_hardware(ComputerHardwareConfig::debug_serial(
                    computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
                    computer_abi::DEBUG_BASE,
                )),
            "computer hardware id 1 is duplicated",
        );
    }

    #[test]
    fn computer_machine_profile_rejects_invalid_mmio_ranges() {
        assert_profile_error(
            ComputerMachineProfile::new(1024).with_hardware(ComputerHardwareConfig::control(
                computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
                0,
            )),
            "computer hardware id 1 mmio base must be non-zero",
        );
        assert_profile_error(
            ComputerMachineProfile::new(1024).with_hardware(ComputerHardwareConfig::control(
                computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
                computer_abi::CONTROL_BASE + 1,
            )),
            "computer hardware id 1 mmio base 0x10000001 is not aligned to page size 256",
        );
        assert_profile_error(
            ComputerMachineProfile {
                page_size: 512,
                program_base: 512,
                ..ComputerMachineProfile::new(1024).with_hardware(ComputerHardwareConfig::control(
                    computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
                    computer_abi::CONTROL_BASE,
                ))
            },
            "computer hardware id 1 mmio size 256 is not aligned to page size 512",
        );
        assert_profile_error(
            ComputerMachineProfile::new(1024).with_hardware(ComputerHardwareConfig::control(
                computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
                512,
            )),
            "computer hardware id 1 mmio range 0x00000200..0x00000300 overlaps RAM 0x00000000..0x00000400",
        );
        assert_profile_error(
            ComputerMachineProfile::new(1024).with_hardware(ComputerHardwareConfig::control(
                computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
                u32::MAX - 127,
            )),
            "computer hardware id 1 mmio range 0xffffff80 with size 256 overflows address space",
        );
    }

    #[test]
    fn computer_machine_profile_rejects_overlapping_mmio_ranges() {
        assert_profile_error(
            ComputerMachineProfile::new(1024)
                .with_hardware(ComputerHardwareConfig::control(
                    computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
                    computer_abi::CONTROL_BASE,
                ))
                .with_hardware(ComputerHardwareConfig::debug_serial(
                    computer_abi::COMPUTER_HARDWARE_ID_DEBUG,
                    computer_abi::CONTROL_BASE,
                )),
            "computer hardware id 2 mmio range 0x10000000..0x10000100 overlaps hardware id 1 range 0x10000000..0x10000100",
        );
    }

    #[test]
    fn computer_machine_profile_rejects_hardware_table_that_does_not_fit_boot_page() {
        let mut profile = ComputerMachineProfile::new(4096);
        for id in 1..=20 {
            profile = profile.with_hardware(ComputerHardwareConfig::control(
                id,
                computer_abi::CONTROL_BASE + (id - 1) * computer_abi::PROFILE_V2_PAGE_SIZE,
            ));
        }

        assert_profile_error(
            profile,
            "computer hardware table with 20 entries does not fit boot page size 256",
        );
    }

    #[test]
    fn computer_machine_rejects_memory_smaller_than_profile_page() {
        let error = match ComputerMachine::new(128) {
            Ok(_) => panic!("computer machine should reject memory smaller than profile page"),
            Err(error) => error,
        };

        assert_eq!(
            error.to_string(),
            "computer memory size 128 is smaller than profile page size 256",
        );
    }

    #[test]
    fn computer_machine_rejects_memory_that_is_not_page_aligned() {
        let error = match ComputerMachine::new(1000) {
            Ok(_) => panic!("computer machine should reject unaligned memory"),
            Err(error) => error,
        };

        assert_eq!(
            error.to_string(),
            "computer memory size 1000 is not a multiple of profile page size 256",
        );
    }

    #[test]
    fn computer_machine_rejects_memory_that_exceeds_u32_address_space() {
        if usize::BITS <= u32::BITS {
            return;
        }
        let memory_size = u32::MAX as usize + 1;
        let error = match ComputerMachine::new(memory_size) {
            Ok(_) => panic!("computer machine should reject memory above u32 address space"),
            Err(error) => error,
        };

        assert_eq!(
            error.to_string(),
            "computer memory size 4294967296 exceeds profile u32 address space",
        );
    }

    #[test]
    fn computer_machine_loads_image_sections_at_profile_program_base() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let mut firmware = image(vec![Instruction::ReturnUnit], 0);
        firmware.rodata = vec![0xA5, 0x5A];

        machine.spawn_boot_cpu(firmware, 128).unwrap();

        assert_eq!(
            read_u32(machine.memory(), 0x00),
            ComputerMachine::PROFILE_V2_BOOT_INFO_MAGIC
        );
        assert_eq!(
            machine
                .memory()
                .load_u8(ComputerMachine::PROFILE_V2_PROGRAM_BASE)
                .unwrap(),
            0xA5,
        );
        assert_eq!(
            machine
                .memory()
                .load_u8(ComputerMachine::PROFILE_V2_PROGRAM_BASE + 1)
                .unwrap(),
            0x5A,
        );
    }

    #[test]
    fn computer_machine_constants_match_profile_v2_abi() {
        assert_eq!(
            ComputerMachine::PROFILE_V2_BOOT_INFO_MAGIC,
            computer_abi::PROFILE_V2_BOOT_INFO_MAGIC,
        );
        assert_eq!(
            ComputerMachine::PROFILE_V2_VERSION,
            computer_abi::PROFILE_V2_VERSION,
        );
        assert_eq!(
            ComputerMachine::PROFILE_V2_PAGE_SIZE,
            computer_abi::PROFILE_V2_PAGE_SIZE,
        );
        assert_eq!(
            ComputerMachine::PROFILE_V2_BOOT_INFO_ADDR,
            computer_abi::PROFILE_V2_BOOT_INFO_ADDR,
        );
        assert_eq!(
            ComputerMachine::PROFILE_V2_PROGRAM_BASE,
            computer_abi::PROFILE_V2_PROGRAM_BASE,
        );
        assert_eq!(
            ComputerMachine::PROFILE_V2_BOOT_INFO_SIZE,
            computer_abi::PROFILE_V2_BOOT_INFO_SIZE,
        );
        assert_eq!(
            ComputerMachine::PROFILE_V2_HARDWARE_ENTRY_SIZE,
            computer_abi::PROFILE_V2_HARDWARE_ENTRY_SIZE,
        );
        assert_eq!(
            ComputerMachine::HARDWARE_ID_CONTROL,
            computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
        );
        assert_eq!(
            ComputerMachine::HARDWARE_ID_DEBUG,
            computer_abi::COMPUTER_HARDWARE_ID_DEBUG,
        );
        assert_eq!(
            ComputerMachine::HARDWARE_ID_SERIAL_INPUT,
            computer_abi::COMPUTER_HARDWARE_ID_SERIAL_INPUT,
        );
        assert_eq!(
            ComputerMachine::HARDWARE_ID_DISPLAY0,
            computer_abi::COMPUTER_HARDWARE_ID_DISPLAY0,
        );
        assert_eq!(ComputerMachine::CONTROL_BASE, computer_abi::CONTROL_BASE);
        assert_eq!(
            ComputerMachine::CONTROL_STATUS,
            computer_abi::CONTROL_STATUS
        );
        assert_eq!(
            ComputerMachine::CONTROL_PANIC_CODE,
            computer_abi::CONTROL_PANIC_CODE,
        );
        assert_eq!(
            ComputerMachine::CONTROL_EXIT_CODE,
            computer_abi::CONTROL_EXIT_CODE,
        );
        assert_eq!(ComputerMachine::CONTROL_SIZE, computer_abi::CONTROL_SIZE);
        assert_eq!(ComputerMachine::DEBUG_BASE, computer_abi::DEBUG_BASE);
        assert_eq!(ComputerMachine::DEBUG_WRITE, computer_abi::DEBUG_WRITE);
        assert_eq!(ComputerMachine::DEBUG_SIZE, computer_abi::DEBUG_SIZE);
        assert_eq!(
            ComputerMachine::SERIAL_INPUT_BASE,
            computer_abi::SERIAL_INPUT_BASE,
        );
        assert_eq!(
            ComputerMachine::SERIAL_INPUT_READY,
            computer_abi::SERIAL_INPUT_READY,
        );
        assert_eq!(
            ComputerMachine::SERIAL_INPUT_READ,
            computer_abi::SERIAL_INPUT_READ,
        );
        assert_eq!(
            ComputerMachine::SERIAL_INPUT_SIZE,
            computer_abi::SERIAL_INPUT_SIZE,
        );
        assert_eq!(ComputerMachine::DISPLAY0_BASE, computer_abi::DISPLAY0_BASE);
        assert_eq!(
            ComputerMachine::DISPLAY0_COLUMNS,
            computer_abi::DISPLAY0_COLUMNS,
        );
        assert_eq!(ComputerMachine::DISPLAY0_ROWS, computer_abi::DISPLAY0_ROWS);
        assert_eq!(
            ComputerMachine::DISPLAY0_CURSOR_X,
            computer_abi::DISPLAY0_CURSOR_X,
        );
        assert_eq!(
            ComputerMachine::DISPLAY0_CURSOR_Y,
            computer_abi::DISPLAY0_CURSOR_Y,
        );
        assert_eq!(
            ComputerMachine::DISPLAY0_COMMAND,
            computer_abi::DISPLAY0_COMMAND,
        );
        assert_eq!(ComputerMachine::DISPLAY0_DATA, computer_abi::DISPLAY0_DATA);
        assert_eq!(
            ComputerMachine::DISPLAY0_SEQUENCE_LOW,
            computer_abi::DISPLAY0_SEQUENCE_LOW,
        );
        assert_eq!(
            ComputerMachine::DISPLAY0_SEQUENCE_HIGH,
            computer_abi::DISPLAY0_SEQUENCE_HIGH,
        );
        assert_eq!(ComputerMachine::DISPLAY0_SIZE, computer_abi::DISPLAY0_SIZE);
        assert_eq!(
            ComputerMachine::DISPLAY0_COMMAND_CLEAR,
            computer_abi::DISPLAY0_COMMAND_CLEAR,
        );
        assert_eq!(
            ComputerMachine::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR,
            computer_abi::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR,
        );
        assert_eq!(
            ComputerMachine::DISPLAY0_COMMAND_PUT_BYTE_AT_XY,
            computer_abi::DISPLAY0_COMMAND_PUT_BYTE_AT_XY,
        );
        assert_eq!(
            ComputerMachine::DISPLAY0_COMMAND_NEWLINE,
            computer_abi::DISPLAY0_COMMAND_NEWLINE,
        );
        assert_eq!(ComputerMachine::STATUS_RESET, computer_abi::STATUS_RESET);
        assert_eq!(
            ComputerMachine::STATUS_BOOTING,
            computer_abi::STATUS_BOOTING
        );
        assert_eq!(ComputerMachine::STATUS_READY, computer_abi::STATUS_READY);
        assert_eq!(ComputerMachine::STATUS_HALTED, computer_abi::STATUS_HALTED);
        assert_eq!(ComputerMachine::STATUS_PANIC, computer_abi::STATUS_PANIC);
    }

    #[test]
    fn computer_mmio_device_sizes_match_profile_v2_abi() {
        let control = ComputerControlDevice::new();
        let debug = DebugSerialDevice::new();
        let serial_input = SerialInputDevice::new();
        let display = TextDisplayDevice::new();

        assert_eq!(control.size(), computer_abi::CONTROL_SIZE);
        assert_eq!(debug.size(), computer_abi::DEBUG_SIZE);
        assert_eq!(serial_input.size(), computer_abi::SERIAL_INPUT_SIZE);
        assert_eq!(display.size(), computer_abi::DISPLAY0_SIZE);
    }

    #[test]
    fn bare_metal_program_halt_sets_machine_halted_status_and_exit_code() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let firmware = image(
            vec![
                Instruction::I32Const { dst: 0, value: 7 },
                Instruction::ReturnI32 { src: 0 },
            ],
            1,
        );

        let cpu_id = machine.spawn_boot_cpu(firmware, 128).unwrap();

        assert_eq!(
            machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltI32(7),
        );
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
        assert_eq!(machine.exit_code(), 7);
        assert_eq!(machine.panic_code(), 0);
    }

    #[test]
    fn bare_metal_program_writes_debug_serial_output() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let firmware = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::DEBUG_WRITE,
                },
                Instruction::I32Const {
                    dst: 1,
                    value: i32::from(b'H'),
                },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::I32Const {
                    dst: 2,
                    value: i32::from(b'I'),
                },
                Instruction::Store32 { addr: 0, src: 2 },
                Instruction::ReturnUnit,
            ],
            3,
        );

        let cpu_id = machine.spawn_boot_cpu(firmware, 128).unwrap();

        assert_eq!(
            machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.debug_output_bytes(), b"HI");
        assert_eq!(machine.debug_output_string(), "HI");
    }

    #[test]
    fn bare_metal_firmware_marks_ready_writes_debug_and_halts() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let firmware = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: computer_abi::CONTROL_STATUS,
                },
                Instruction::AddrConst {
                    dst: 1,
                    value: computer_abi::DEBUG_WRITE,
                },
                Instruction::I32Const {
                    dst: 2,
                    value: computer_abi::STATUS_BOOTING,
                },
                Instruction::Store32 { addr: 0, src: 2 },
                Instruction::I32Const {
                    dst: 3,
                    value: i32::from(b'O'),
                },
                Instruction::Store32 { addr: 1, src: 3 },
                Instruction::I32Const {
                    dst: 4,
                    value: i32::from(b'K'),
                },
                Instruction::Store32 { addr: 1, src: 4 },
                Instruction::I32Const {
                    dst: 5,
                    value: computer_abi::STATUS_READY,
                },
                Instruction::Store32 { addr: 0, src: 5 },
                Instruction::ReturnUnit,
            ],
            6,
        );

        let cpu_id = machine.spawn_boot_cpu(firmware, 128).unwrap();

        assert_eq!(
            machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.debug_output_string(), "OK");
        assert_eq!(machine.control_status(), computer_abi::STATUS_HALTED);
        assert_eq!(machine.exit_code(), 0);
    }

    #[test]
    fn bare_metal_program_fault_marks_machine_panicked() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let firmware = image(
            vec![
                Instruction::I32Const { dst: 0, value: 10 },
                Instruction::I32Const { dst: 1, value: 0 },
                Instruction::I32Div {
                    dst: 2,
                    lhs: 0,
                    rhs: 1,
                },
                Instruction::ReturnUnit,
            ],
            3,
        );

        let cpu_id = machine.spawn_boot_cpu(firmware, 128).unwrap();
        let error = machine.run_boot_cpu_until_signal(cpu_id).unwrap_err();

        assert_eq!(error, "division by zero");
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_PANIC);
        assert_ne!(machine.panic_code(), 0);
    }

    #[test]
    fn boot_cpu_runs_kernel_that_marks_machine_ready() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let kernel = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::CONTROL_STATUS,
                },
                Instruction::I32Const {
                    dst: 1,
                    value: ComputerMachine::STATUS_BOOTING,
                },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::I32Const {
                    dst: 2,
                    value: ComputerMachine::STATUS_READY,
                },
                Instruction::Store32 { addr: 0, src: 2 },
                Instruction::ReturnUnit,
            ],
            3,
        );

        let cpu_id = machine.spawn_boot_cpu(kernel, 128).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_READY);
    }

    #[test]
    fn legacy_ckl_os_research_boot_kernel_initializes_os_state_and_marks_machine_ready() {
        let mut machine = ComputerMachine::new(0x0002_0000).unwrap();
        let kernel = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::CONTROL_STATUS,
                },
                Instruction::I32Const {
                    dst: 1,
                    value: ComputerMachine::STATUS_BOOTING,
                },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::AddrConst {
                    dst: 2,
                    value: OS_STATE_BASE,
                },
                Instruction::I32Const {
                    dst: 3,
                    value: OS_MAGIC,
                },
                Instruction::Store32 { addr: 2, src: 3 },
                Instruction::AddrConst {
                    dst: 4,
                    value: OS_STATE_BASE + 4,
                },
                Instruction::I32Const {
                    dst: 5,
                    value: INITIAL_PROCESS_READY,
                },
                Instruction::Store32 { addr: 4, src: 5 },
                Instruction::I32Const {
                    dst: 6,
                    value: ComputerMachine::STATUS_READY,
                },
                Instruction::Store32 { addr: 0, src: 6 },
                Instruction::ReturnUnit,
            ],
            7,
        );

        let cpu_id = machine.spawn_boot_cpu(kernel, 512).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_READY);
        assert_eq!(machine.memory().load_i32(OS_STATE_BASE).unwrap(), OS_MAGIC);
        assert_eq!(
            machine.memory().load_i32(OS_STATE_BASE + 4).unwrap(),
            INITIAL_PROCESS_READY,
        );
    }

    #[test]
    fn legacy_ckl_os_research_boot_kernel_initializes_guest_process_table() {
        let mut machine = ComputerMachine::new(0x0002_0000).unwrap();
        let kernel = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::CONTROL_STATUS,
                },
                Instruction::I32Const {
                    dst: 1,
                    value: ComputerMachine::STATUS_BOOTING,
                },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::AddrConst {
                    dst: 2,
                    value: OS_STATE_BASE,
                },
                Instruction::I32Const {
                    dst: 3,
                    value: OS_MAGIC,
                },
                Instruction::Store32 { addr: 2, src: 3 },
                Instruction::AddrConst {
                    dst: 4,
                    value: OS_CURRENT_PID,
                },
                Instruction::I32Const { dst: 5, value: 0 },
                Instruction::Store32 { addr: 4, src: 5 },
                Instruction::AddrConst {
                    dst: 6,
                    value: OS_PROCESS_COUNT,
                },
                Instruction::I32Const { dst: 7, value: 2 },
                Instruction::Store32 { addr: 6, src: 7 },
                Instruction::AddrConst {
                    dst: 8,
                    value: PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET,
                },
                Instruction::I32Const {
                    dst: 9,
                    value: PROCESS_RUNNING,
                },
                Instruction::Store32 { addr: 8, src: 9 },
                Instruction::AddrConst {
                    dst: 10,
                    value: PROCESS_TABLE_BASE + PROCESS_ENTRY_OFFSET,
                },
                Instruction::AddrConst {
                    dst: 11,
                    value: 0x0008_0000,
                },
                Instruction::Store32 { addr: 10, src: 11 },
                Instruction::AddrConst {
                    dst: 12,
                    value: PROCESS_TABLE_BASE + PROCESS_STACK_PTR_OFFSET,
                },
                Instruction::AddrConst {
                    dst: 13,
                    value: 0x0010_0000,
                },
                Instruction::Store32 { addr: 12, src: 13 },
                Instruction::AddrConst {
                    dst: 14,
                    value: PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STATE_OFFSET,
                },
                Instruction::I32Const {
                    dst: 15,
                    value: PROCESS_RUNNABLE,
                },
                Instruction::Store32 { addr: 14, src: 15 },
                Instruction::AddrConst {
                    dst: 16,
                    value: PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_ENTRY_OFFSET,
                },
                Instruction::AddrConst {
                    dst: 17,
                    value: 0x0008_0100,
                },
                Instruction::Store32 { addr: 16, src: 17 },
                Instruction::AddrConst {
                    dst: 18,
                    value: PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STACK_PTR_OFFSET,
                },
                Instruction::AddrConst {
                    dst: 19,
                    value: 0x0010_1000,
                },
                Instruction::Store32 { addr: 18, src: 19 },
                Instruction::I32Const {
                    dst: 20,
                    value: ComputerMachine::STATUS_READY,
                },
                Instruction::Store32 { addr: 0, src: 20 },
                Instruction::ReturnUnit,
            ],
            21,
        );

        let cpu_id = machine.spawn_boot_cpu(kernel, 1024).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_READY);
        assert_eq!(machine.memory().load_i32(OS_STATE_BASE).unwrap(), OS_MAGIC);
        assert_eq!(machine.memory().load_i32(OS_CURRENT_PID).unwrap(), 0);
        assert_eq!(machine.memory().load_i32(OS_PROCESS_COUNT).unwrap(), 2);
        assert_eq!(
            machine
                .memory()
                .load_i32(PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET)
                .unwrap(),
            PROCESS_RUNNING,
        );
        assert_eq!(
            machine
                .memory()
                .load_i32(PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STATE_OFFSET)
                .unwrap(),
            PROCESS_RUNNABLE,
        );
    }

    #[test]
    fn boot_cpu_runs_kernel_that_marks_machine_panicked() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let kernel = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::CONTROL_STATUS,
                },
                Instruction::AddrConst {
                    dst: 1,
                    value: ComputerMachine::CONTROL_PANIC_CODE,
                },
                Instruction::I32Const {
                    dst: 2,
                    value: ComputerMachine::STATUS_PANIC,
                },
                Instruction::I32Const { dst: 3, value: 404 },
                Instruction::Store32 { addr: 0, src: 2 },
                Instruction::Store32 { addr: 1, src: 3 },
                Instruction::ReturnUnit,
            ],
            4,
        );

        let cpu_id = machine.spawn_boot_cpu(kernel, 128).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_PANIC);
        assert_eq!(machine.panic_code(), 404);
    }

    #[test]
    fn boot_kernel_can_panic_through_control_mmio() {
        let mut machine = ComputerMachine::new(0x0002_0000).unwrap();
        let kernel = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::CONTROL_STATUS,
                },
                Instruction::AddrConst {
                    dst: 1,
                    value: ComputerMachine::CONTROL_PANIC_CODE,
                },
                Instruction::I32Const {
                    dst: 2,
                    value: ComputerMachine::STATUS_BOOTING,
                },
                Instruction::Store32 { addr: 0, src: 2 },
                Instruction::I32Const {
                    dst: 3,
                    value: 0x0BAD,
                },
                Instruction::Store32 { addr: 1, src: 3 },
                Instruction::I32Const {
                    dst: 4,
                    value: ComputerMachine::STATUS_PANIC,
                },
                Instruction::Store32 { addr: 0, src: 4 },
                Instruction::ReturnUnit,
            ],
            5,
        );

        let cpu_id = machine.spawn_boot_cpu(kernel, 512).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_PANIC);
        assert_eq!(machine.panic_code(), 0x0BAD);
    }

    #[test]
    fn legacy_ckl_os_research_scheduler_fixture_rotates_running_process_state() {
        let mut machine = ComputerMachine::new(0x0002_0000).unwrap();
        machine.memory_mut().store_i32(OS_CURRENT_PID, 0).unwrap();
        machine
            .memory_mut()
            .store_i32(PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET, PROCESS_RUNNING)
            .unwrap();
        machine
            .memory_mut()
            .store_i32(
                PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STATE_OFFSET,
                PROCESS_RUNNABLE,
            )
            .unwrap();
        let scheduler = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: OS_CURRENT_PID,
                },
                Instruction::Load32 { dst: 1, addr: 0 },
                Instruction::I32Const { dst: 2, value: 0 },
                Instruction::I32Eq {
                    dst: 3,
                    lhs: 1,
                    rhs: 2,
                },
                Instruction::JumpIfFalse {
                    cond: 3,
                    target: 18,
                },
                Instruction::AddrConst {
                    dst: 4,
                    value: PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STATE_OFFSET,
                },
                Instruction::Load32 { dst: 5, addr: 4 },
                Instruction::I32Const {
                    dst: 6,
                    value: PROCESS_RUNNABLE,
                },
                Instruction::I32Eq {
                    dst: 7,
                    lhs: 5,
                    rhs: 6,
                },
                Instruction::JumpIfFalse {
                    cond: 7,
                    target: 18,
                },
                Instruction::AddrConst {
                    dst: 8,
                    value: PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET,
                },
                Instruction::I32Const {
                    dst: 9,
                    value: PROCESS_RUNNABLE,
                },
                Instruction::Store32 { addr: 8, src: 9 },
                Instruction::I32Const {
                    dst: 10,
                    value: PROCESS_RUNNING,
                },
                Instruction::Store32 { addr: 4, src: 10 },
                Instruction::I32Const { dst: 11, value: 1 },
                Instruction::Store32 { addr: 0, src: 11 },
                Instruction::ReturnUnit,
                Instruction::ReturnUnit,
            ],
            12,
        );

        let cpu_id = machine.spawn_cpu(scheduler, 1024).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.memory().load_i32(OS_CURRENT_PID).unwrap(), 1);
        assert_eq!(
            machine
                .memory()
                .load_i32(PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET)
                .unwrap(),
            PROCESS_RUNNABLE,
        );
        assert_eq!(
            machine
                .memory()
                .load_i32(PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STATE_OFFSET)
                .unwrap(),
            PROCESS_RUNNING,
        );
    }

    #[test]
    fn legacy_ckl_os_research_kernel_launches_static_user_process_and_records_exit_code() {
        let mut machine = ComputerMachine::new(0x0002_0000).unwrap();
        let kernel = Image {
            memory_size: 0x0002_0000,
            rodata: Vec::new(),
            data: Vec::new(),
            bss_size: 0,
            entry_function_index: 0,
            functions: vec![
                Function {
                    name: "kernel".to_string(),
                    register_count: 18,
                    parameters: Vec::new(),
                    instructions: vec![
                        Instruction::AddrConst {
                            dst: 0,
                            value: ComputerMachine::CONTROL_STATUS,
                        },
                        Instruction::I32Const {
                            dst: 1,
                            value: ComputerMachine::STATUS_BOOTING,
                        },
                        Instruction::Store32 { addr: 0, src: 1 },
                        Instruction::AddrConst {
                            dst: 2,
                            value: OS_STATE_BASE,
                        },
                        Instruction::I32Const {
                            dst: 3,
                            value: OS_MAGIC,
                        },
                        Instruction::Store32 { addr: 2, src: 3 },
                        Instruction::AddrConst {
                            dst: 4,
                            value: OS_CURRENT_PID,
                        },
                        Instruction::I32Const { dst: 5, value: 0 },
                        Instruction::Store32 { addr: 4, src: 5 },
                        Instruction::AddrConst {
                            dst: 6,
                            value: OS_PROCESS_COUNT,
                        },
                        Instruction::I32Const { dst: 7, value: 1 },
                        Instruction::Store32 { addr: 6, src: 7 },
                        Instruction::AddrConst {
                            dst: 8,
                            value: PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET,
                        },
                        Instruction::I32Const {
                            dst: 9,
                            value: PROCESS_RUNNING,
                        },
                        Instruction::Store32 { addr: 8, src: 9 },
                        Instruction::AddrConst {
                            dst: 10,
                            value: PROCESS_TABLE_BASE + PROCESS_ENTRY_OFFSET,
                        },
                        Instruction::I32Const {
                            dst: 11,
                            value: USER_PROCESS_FUNCTION_INDEX,
                        },
                        Instruction::Store32 { addr: 10, src: 11 },
                        Instruction::Load32 { dst: 12, addr: 10 },
                        Instruction::I32Eq {
                            dst: 13,
                            lhs: 12,
                            rhs: 11,
                        },
                        Instruction::JumpIfFalse {
                            cond: 13,
                            target: 29,
                        },
                        Instruction::CallStatic {
                            return_register: Some(14),
                            function_index: USER_PROCESS_FUNCTION_INDEX as usize,
                            arguments: Vec::new(),
                        },
                        Instruction::AddrConst {
                            dst: 15,
                            value: PROCESS_TABLE_BASE + PROCESS_EXIT_CODE_OFFSET,
                        },
                        Instruction::Store32 { addr: 15, src: 14 },
                        Instruction::I32Const {
                            dst: 16,
                            value: PROCESS_EXITED,
                        },
                        Instruction::Store32 { addr: 8, src: 16 },
                        Instruction::I32Const {
                            dst: 17,
                            value: ComputerMachine::STATUS_READY,
                        },
                        Instruction::Store32 { addr: 0, src: 17 },
                        Instruction::ReturnUnit,
                        Instruction::I32Const {
                            dst: 17,
                            value: ComputerMachine::STATUS_PANIC,
                        },
                        Instruction::Store32 { addr: 0, src: 17 },
                        Instruction::ReturnUnit,
                    ],
                },
                Function {
                    name: "user_main".to_string(),
                    register_count: 1,
                    parameters: Vec::new(),
                    instructions: vec![
                        Instruction::I32Const { dst: 0, value: 42 },
                        Instruction::ReturnI32 { src: 0 },
                    ],
                },
            ],
        };

        let cpu_id = machine.spawn_boot_cpu(kernel, 1024).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_READY);
        assert_eq!(
            machine
                .memory()
                .load_i32(PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET)
                .unwrap(),
            PROCESS_EXITED,
        );
        assert_eq!(
            machine
                .memory()
                .load_i32(PROCESS_TABLE_BASE + PROCESS_EXIT_CODE_OFFSET)
                .unwrap(),
            42,
        );
    }

    #[test]
    fn computer_memory_map_describes_ram_region() {
        let machine = ComputerMachine::new(1024).unwrap();
        let map = machine.memory_map();
        let ram = map.region("ram").unwrap();

        assert_eq!(ram.base, computer_abi::RAM_BASE);
        assert_eq!(ram.size, 1024);
        assert!(ram.readable);
        assert!(ram.writable);
    }

    #[test]
    fn computer_memory_map_describes_control_mmio_region() {
        let machine = ComputerMachine::new(1024).unwrap();
        let map = machine.memory_map();
        let control = map.region("control").unwrap();

        assert_eq!(control.base, computer_abi::CONTROL_BASE);
        assert_eq!(control.size, computer_abi::CONTROL_SIZE);
        assert!(control.readable);
        assert!(control.writable);
    }

    #[test]
    fn computer_memory_map_describes_debug_serial_mmio_region() {
        let machine = ComputerMachine::new(1024).unwrap();
        let map = machine.memory_map();
        let debug = map.region("debug").unwrap();

        assert_eq!(debug.base, computer_abi::DEBUG_BASE);
        assert_eq!(debug.size, computer_abi::DEBUG_SIZE);
        assert!(debug.readable);
        assert!(debug.writable);
    }

    #[test]
    fn computer_memory_map_describes_serial_input_mmio_region() {
        let machine = ComputerMachine::new(1024).unwrap();
        let map = machine.memory_map();
        let serial_input = map.region("serial-input").unwrap();

        assert_eq!(serial_input.base, computer_abi::SERIAL_INPUT_BASE);
        assert_eq!(serial_input.size, computer_abi::SERIAL_INPUT_SIZE);
        assert!(serial_input.readable);
        assert!(serial_input.writable);
    }

    #[test]
    fn computer_memory_map_describes_display0_mmio_region() {
        let machine = ComputerMachine::new(1024).unwrap();
        let map = machine.memory_map();
        let display = map.region("display0").unwrap();

        assert_eq!(display.base, computer_abi::DISPLAY0_BASE);
        assert_eq!(display.size, computer_abi::DISPLAY0_SIZE);
        assert!(display.readable);
        assert!(display.writable);
    }

    fn image(instructions: Vec<Instruction>, register_count: u16) -> Image {
        Image {
            memory_size: 1024,
            rodata: Vec::new(),
            data: Vec::new(),
            bss_size: 0,
            entry_function_index: 0,
            functions: vec![Function {
                name: "main".to_string(),
                register_count,
                parameters: Vec::new(),
                instructions,
            }],
        }
    }

    fn read_u32(memory: &crate::low_machine::MachineMemory, address: u32) -> u32 {
        u32::from_le_bytes(memory.load_i32(address).unwrap().to_le_bytes())
    }

    fn assert_hardware_entry(
        memory: &crate::low_machine::MachineMemory,
        address: u32,
        id: u32,
        mmio_base: u32,
        mmio_size: u32,
    ) {
        assert_eq!(read_u32(memory, address), id);
        assert_eq!(read_u32(memory, address + 4), mmio_base);
        assert_eq!(read_u32(memory, address + 8), mmio_size);
    }

    fn storage0_machine_with_media(media: Vec<u8>, read_only: bool) -> ComputerMachine {
        let profile = ComputerMachineProfile::new(2048).with_hardware(
            ComputerHardwareConfig::storage_port_with_media(
                computer_abi::COMPUTER_HARDWARE_ID_STORAGE0,
                computer_abi::STORAGE0_BASE,
                media,
                read_only,
            ),
        );
        ComputerMachine::from_profile(profile).unwrap()
    }

    fn write_rux_volume(path: &std::path::Path, payload: &[u8]) {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(b"RUXVOL");
        bytes.extend_from_slice(&1u16.to_le_bytes());
        bytes.extend_from_slice(&(payload.len() as u64).to_le_bytes());
        bytes.extend_from_slice(payload);
        fs::write(path, bytes).unwrap();
    }

    fn temp_volume_path(name: &str) -> std::path::PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!(
            "rux-machine-{name}-{}-{nanos}.ruxvol",
            std::process::id()
        ))
    }

    fn assert_storage_error(machine: &ComputerMachine, error: i32) {
        assert_eq!(
            machine.bus_load_i32(computer_abi::STORAGE0_STATUS).unwrap(),
            computer_abi::STORAGE_STATUS_ERROR,
        );
        assert_eq!(
            machine.bus_load_i32(computer_abi::STORAGE0_ERROR).unwrap(),
            error,
        );
        assert_eq!(
            machine
                .bus_load_i32(computer_abi::STORAGE0_BYTES_DONE)
                .unwrap(),
            0,
        );
    }

    fn assert_profile_error(profile: ComputerMachineProfile, expected: &str) {
        let error = match ComputerMachine::from_profile(profile) {
            Ok(_) => panic!("computer machine should reject invalid profile"),
            Err(error) => error,
        };

        assert_eq!(error.to_string(), expected);
    }
}
