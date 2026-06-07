use super::ComputerMachine;
use crate::computer::devices::{
    ComputerControlDevice, DebugSerialDevice, GpuDevice, K16VolumeFileStorageMedia, KeyboardDevice,
    SerialInputDevice, StoragePortDevice, TextDisplayDevice, TimerDevice,
};
use crate::computer::profile::{
    validate_profile_v2, ComputerHardwareConfig, ComputerHardwareDevice, ComputerMachineProfile,
    HardwareTableEntry, StorageMediaConfig, StoragePortConfig,
};
use crate::computer_abi;
use crate::low_bus::{MachineBus, MmioDevice, MmioDeviceId};
use crate::low_machine::{MachineMemory, MemoryFault};

pub(super) fn from_profile(
    profile: ComputerMachineProfile,
) -> Result<ComputerMachine, MemoryFault> {
    validate_profile_v2(&profile)?;

    let mut bus = MachineBus::new(profile.memory_size)?;
    let hardware_entries = hardware_table_entries(&profile);
    let device_ids = map_profile_hardware(&mut bus, &profile)?;

    write_profile_v2_boot_info(&mut bus, &profile, &hardware_entries)?;

    Ok(ComputerMachine {
        bus,
        control_device_id: device_ids.control,
        debug_device_id: device_ids.debug_serial,
        serial_input_device_id: device_ids.serial_input,
        display0_device_id: device_ids.text_display,
        gpu0_device_id: device_ids.gpu,
        storage0_device_id: device_ids.storage_port,
        timer0_device_id: device_ids.timer,
        keyboard0_device_id: device_ids.keyboard,
        bios_flash_device_id: None,
        cpus: Vec::new(),
        boot_cpu: None,
    })
}

#[derive(Default)]
struct ConstructionDeviceIds {
    control: Option<MmioDeviceId>,
    debug_serial: Option<MmioDeviceId>,
    serial_input: Option<MmioDeviceId>,
    text_display: Option<MmioDeviceId>,
    gpu: Option<MmioDeviceId>,
    storage_port: Option<MmioDeviceId>,
    timer: Option<MmioDeviceId>,
    keyboard: Option<MmioDeviceId>,
}

fn hardware_table_entries(profile: &ComputerMachineProfile) -> Vec<HardwareTableEntry> {
    profile
        .hardware
        .iter()
        .map(|hardware| HardwareTableEntry {
            id: hardware.id,
            mmio_base: hardware.mmio_base,
            mmio_size: hardware.mmio_size(),
            irq_source: hardware.irq_source(),
        })
        .collect()
}

fn map_profile_hardware(
    bus: &mut MachineBus,
    profile: &ComputerMachineProfile,
) -> Result<ConstructionDeviceIds, MemoryFault> {
    let mut device_ids = ConstructionDeviceIds::default();

    for hardware in &profile.hardware {
        let device_id = map_hardware_device(bus, hardware)?;
        device_ids.remember(&hardware.device, device_id);
    }

    Ok(device_ids)
}

fn map_hardware_device(
    bus: &mut MachineBus,
    hardware: &ComputerHardwareConfig,
) -> Result<MmioDeviceId, MemoryFault> {
    // Profiles describe guest-visible hardware; construction materializes each
    // entry into the MMIO device that the MachineBus will route at runtime.
    let device: Box<dyn MmioDevice> = match &hardware.device {
        ComputerHardwareDevice::Control => Box::new(ComputerControlDevice::new()),
        ComputerHardwareDevice::DebugSerial => Box::new(DebugSerialDevice::new()),
        ComputerHardwareDevice::SerialInput => Box::new(SerialInputDevice::new()),
        ComputerHardwareDevice::TextDisplay => Box::new(TextDisplayDevice::new()),
        ComputerHardwareDevice::Gpu => Box::new(GpuDevice::new()),
        ComputerHardwareDevice::StoragePort(config) => Box::new(storage_port_device(config)?),
        ComputerHardwareDevice::Timer => Box::new(TimerDevice::new()),
        ComputerHardwareDevice::Keyboard => Box::new(KeyboardDevice::new()),
    };
    bus.map_mmio(hardware.mmio_base, device)
}

fn storage_port_device(config: &StoragePortConfig) -> Result<StoragePortDevice, MemoryFault> {
    match &config.media {
        Some(StorageMediaConfig::InMemory { bytes, read_only }) => {
            StoragePortDevice::with_media(bytes.clone(), *read_only)
        }
        Some(StorageMediaConfig::K16VolumeFile { path }) => {
            StoragePortDevice::with_media_backend(Box::new(K16VolumeFileStorageMedia::open(path)?))
        }
        None => Ok(StoragePortDevice::new_absent()),
    }
}

impl ConstructionDeviceIds {
    fn remember(&mut self, device: &ComputerHardwareDevice, device_id: MmioDeviceId) {
        match device {
            ComputerHardwareDevice::Control => self.control = Some(device_id),
            ComputerHardwareDevice::DebugSerial => self.debug_serial = Some(device_id),
            ComputerHardwareDevice::SerialInput => self.serial_input = Some(device_id),
            ComputerHardwareDevice::TextDisplay => self.text_display = Some(device_id),
            ComputerHardwareDevice::Gpu => self.gpu = Some(device_id),
            ComputerHardwareDevice::StoragePort(_) => self.storage_port = Some(device_id),
            ComputerHardwareDevice::Timer => self.timer = Some(device_id),
            ComputerHardwareDevice::Keyboard => self.keyboard = Some(device_id),
        }
    }
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
            entry.irq_source,
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
    irq_source: u32,
) -> Result<(), MemoryFault> {
    write_u32(memory, address, id)?;
    write_u32(memory, address + 4, mmio_base)?;
    write_u32(memory, address + 8, mmio_size)?;
    write_u32(memory, address + 12, irq_source)
}

fn write_u32(memory: &mut MachineMemory, address: u32, value: u32) -> Result<(), MemoryFault> {
    memory.store_i32(address, i32::from_le_bytes(value.to_le_bytes()))
}
