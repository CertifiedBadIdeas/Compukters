use crate::computer::devices::{
    BiosFlashDevice, ComputerControlDevice, ComputerTextDisplaySnapshot, DebugSerialDevice,
    K16VolumeFileStorageMedia, SerialInputDevice, StoragePortDevice, TextDisplayDevice,
};
use crate::computer::profile::{
    validate_profile_v2, ComputerHardwareDevice, ComputerMachineProfile, HardwareTableEntry,
    StorageMediaConfig,
};
use crate::computer::snapshot;
use crate::computer::snapshot::{ComputerCpuSnapshotRecord, ComputerDeviceSnapshotRecord};
use crate::computer_abi;
use crate::low_bus::{MachineBus, MmioDeviceId};
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
    bios_flash_device_id: Option<MmioDeviceId>,
    cpus: Vec<ComputerCpuContext>,
    boot_cpu: Option<CpuId>,
}

enum ComputerCpuContext {
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
    pub const RUX16_BIOS_FLASH_BASE: u32 = 0xFFF0_0000;
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
                        Some(StorageMediaConfig::K16VolumeFile { path }) => {
                            StoragePortDevice::with_media_backend(Box::new(
                                K16VolumeFileStorageMedia::open(path)?,
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
            bios_flash_device_id: None,
            cpus: Vec::new(),
            boot_cpu: None,
        })
    }

    pub fn from_rux16_bios_flash(
        bios_flash: &[u8],
        memory_size: usize,
        max_steps: u64,
    ) -> Result<(Self, CpuId), String> {
        Self::from_rux16_bios_flash_with_profile(
            bios_flash,
            ComputerMachineProfile::computer_v1(memory_size),
            max_steps,
        )
    }

    pub(crate) fn from_rux16_bios_flash_with_profile(
        bios_flash: &[u8],
        profile: ComputerMachineProfile,
        max_steps: u64,
    ) -> Result<(Self, CpuId), String> {
        if bios_flash.is_empty() {
            return Err("Rux16 BIOS flash is empty".to_string());
        }
        let bios_flash_len = u32::try_from(bios_flash.len())
            .map_err(|_| "Rux16 BIOS flash size does not fit u32".to_string())?;
        Self::RUX16_BIOS_FLASH_BASE
            .checked_add(bios_flash_len)
            .ok_or_else(|| "Rux16 BIOS flash range overflows address space".to_string())?;

        let mut machine = Self::from_profile(profile).map_err(|error| error.to_string())?;
        machine.map_rux16_bios_flash(bios_flash.to_vec())?;
        let boot_cpu = machine.spawn_rux16_boot_cpu(Self::RUX16_BIOS_FLASH_BASE, max_steps)?;
        Ok((machine, boot_cpu))
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
        let cpus = self
            .cpus
            .iter()
            .map(ComputerCpuContext::snapshot_record)
            .collect::<Vec<_>>();
        let devices = self.device_snapshot_records();
        snapshot::encode_snapshot_v1(self.memory().bytes(), self.boot_cpu, &cpus, &devices)
    }

    pub fn restore_ram_snapshot_v1(
        profile: ComputerMachineProfile,
        snapshot_bytes: &[u8],
    ) -> Result<Self, String> {
        let snapshot = snapshot::decode_snapshot_v1(snapshot_bytes)?;
        snapshot::validate_snapshot_ram_matches_profile(&profile, &snapshot)?;
        let mut machine = Self::from_profile(profile).map_err(|error| error.to_string())?;
        machine.write_guest_ram_bytes(0, snapshot.ram)?;
        Ok(machine)
    }

    pub fn restore_snapshot_v1(
        profile: ComputerMachineProfile,
        snapshot_bytes: &[u8],
    ) -> Result<Self, String> {
        let snapshot = snapshot::decode_snapshot_v1(snapshot_bytes)?;
        snapshot::validate_snapshot_ram_matches_profile(&profile, &snapshot)?;
        let mut machine = Self::from_profile(profile).map_err(|error| error.to_string())?;
        machine.write_guest_ram_bytes(0, snapshot.ram)?;
        machine.cpus = snapshot
            .cpus
            .iter()
            .cloned()
            .map(ComputerCpuContext::from_snapshot_record)
            .collect::<Result<Vec<_>, _>>()?;
        for device in snapshot.devices {
            machine.restore_device_snapshot_record(device)?;
        }
        machine.boot_cpu = snapshot
            .header
            .boot_cpu_id
            .map(|id| {
                let id = usize::try_from(id)
                    .map_err(|_| "ComputerMachine snapshot boot CPU id does not fit usize")?;
                if id >= machine.cpus.len() {
                    return Err("ComputerMachine snapshot boot CPU id is outside CPU table");
                }
                Ok(id)
            })
            .transpose()?;
        Ok(machine)
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

    fn spawn_rux16_boot_cpu(&mut self, entry_pc: u32, max_steps: u64) -> Result<CpuId, String> {
        if self.boot_cpu.is_some() {
            return Err("boot CPU is already spawned".to_string());
        }
        let cpu_id = self.cpus.len();
        self.cpus.push(ComputerCpuContext::Rux16 {
            cpu: Rux16Cpu::new(entry_pc),
            max_steps: max_steps.max(1),
        });
        self.boot_cpu = Some(cpu_id);
        Ok(cpu_id)
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

    pub(crate) fn map_rux16_bios_flash(&mut self, bytes: Vec<u8>) -> Result<(), String> {
        if self.bios_flash_device_id.is_some() {
            return Err("Rux16 BIOS flash is already mapped".to_string());
        }
        let device = BiosFlashDevice::new(bytes).map_err(|error| error.to_string())?;
        let device_id = self
            .bus
            .map_mmio(Self::RUX16_BIOS_FLASH_BASE, Box::new(device))
            .map_err(|error| error.to_string())?;
        self.bios_flash_device_id = Some(device_id);
        Ok(())
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

    fn device_snapshot_records(&self) -> Vec<ComputerDeviceSnapshotRecord> {
        let mut devices = Vec::new();
        if let Some(control) = self.control_device() {
            devices.push(ComputerDeviceSnapshotRecord::Control {
                status: control.status,
                panic_code: control.panic_code,
                exit_code: control.exit_code,
            });
        }
        if let Some(debug) = self.debug_device() {
            devices.push(ComputerDeviceSnapshotRecord::DebugSerial {
                bytes: debug.bytes().to_vec(),
            });
        }
        if let Some(display0) = self.display0_device() {
            devices.push(ComputerDeviceSnapshotRecord::Display0 {
                snapshot: display0.snapshot(),
            });
        }
        if let Some(serial_input) = self.serial_input_device() {
            devices.push(ComputerDeviceSnapshotRecord::SerialInput {
                bytes: serial_input.bytes(),
            });
        }
        if let Some(storage0) = self.storage0_device() {
            let snapshot = storage0.controller_snapshot();
            devices.push(ComputerDeviceSnapshotRecord::Storage0 {
                status: snapshot.status,
                error: snapshot.error,
                lba_low: snapshot.lba_low,
                lba_high: snapshot.lba_high,
                block_count: snapshot.block_count,
                buffer_addr: snapshot.buffer_addr,
                bytes_done: snapshot.bytes_done,
                sequence: snapshot.sequence,
            });
        }
        devices
    }

    fn restore_device_snapshot_record(
        &mut self,
        record: ComputerDeviceSnapshotRecord,
    ) -> Result<(), String> {
        match record {
            ComputerDeviceSnapshotRecord::Control {
                status,
                panic_code,
                exit_code,
            } => {
                let control = self.control_device_mut().ok_or_else(|| {
                    "ComputerMachine snapshot contains control device state but profile has no control device"
                        .to_string()
                })?;
                control.status = status;
                control.panic_code = panic_code;
                control.exit_code = exit_code;
            }
            ComputerDeviceSnapshotRecord::DebugSerial { bytes } => {
                let debug = self.debug_device_mut().ok_or_else(|| {
                    "ComputerMachine snapshot contains debug device state but profile has no debug device"
                        .to_string()
                })?;
                debug.restore_bytes(bytes);
            }
            ComputerDeviceSnapshotRecord::Display0 { snapshot } => {
                let display0 = self.display0_device_mut().ok_or_else(|| {
                    "ComputerMachine snapshot contains display0 device state but profile has no display0 device"
                        .to_string()
                })?;
                display0.restore_snapshot(snapshot)?;
            }
            ComputerDeviceSnapshotRecord::SerialInput { bytes } => {
                let serial_input = self.serial_input_device_mut().ok_or_else(|| {
                    "ComputerMachine snapshot contains serial input device state but profile has no serial input device"
                        .to_string()
                })?;
                serial_input.restore_bytes(bytes);
            }
            ComputerDeviceSnapshotRecord::Storage0 {
                status,
                error,
                lba_low,
                lba_high,
                block_count,
                buffer_addr,
                bytes_done,
                sequence,
            } => {
                let storage0 = self.storage0_device_mut().ok_or_else(|| {
                    "ComputerMachine snapshot contains storage0 device state but profile has no storage0 device"
                        .to_string()
                })?;
                storage0.restore_controller_snapshot(
                    crate::computer::devices::StoragePortControllerSnapshot {
                        status,
                        error,
                        lba_low,
                        lba_high,
                        block_count,
                        buffer_addr,
                        bytes_done,
                        sequence,
                    },
                );
            }
        }
        Ok(())
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

    fn storage0_device(&self) -> Option<&StoragePortDevice> {
        self.storage0_device_id
            .and_then(|id| self.bus.device::<StoragePortDevice>(id))
    }

    fn storage0_device_mut(&mut self) -> Option<&mut StoragePortDevice> {
        self.storage0_device_id
            .and_then(|id| self.bus.device_mut::<StoragePortDevice>(id))
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

impl ComputerCpuContext {
    fn snapshot_record(&self) -> ComputerCpuSnapshotRecord {
        match self {
            ComputerCpuContext::Rux16 { cpu, max_steps } => ComputerCpuSnapshotRecord::Rux16 {
                cpu: cpu.snapshot(),
                max_steps: *max_steps,
            },
        }
    }

    fn from_snapshot_record(record: ComputerCpuSnapshotRecord) -> Result<Self, String> {
        match record {
            ComputerCpuSnapshotRecord::Rux16 { cpu, max_steps } => {
                if max_steps == 0 {
                    return Err(
                        "ComputerMachine snapshot Rux16 CPU max_steps must be non-zero".to_string(),
                    );
                }
                Ok(ComputerCpuContext::Rux16 {
                    cpu: Rux16Cpu::from_snapshot(cpu),
                    max_steps,
                })
            }
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
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn computer_machine_owns_shared_physical_ram() {
        let mut machine = ComputerMachine::new(1024).unwrap();

        machine.memory_mut().store_i32(128, 42).unwrap();

        assert_eq!(machine.memory().load_i32(128).unwrap(), 42);
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
        write_k16_volume(&path, &[0; 512]);
        let profile = ComputerMachineProfile::new(2048).with_hardware(
            ComputerHardwareConfig::storage_port_with_k16_volume_file(
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

    fn write_k16_volume(path: &std::path::Path, payload: &[u8]) {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(b"K16VOL");
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
            "rux-machine-{name}-{}-{nanos}.kv",
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
