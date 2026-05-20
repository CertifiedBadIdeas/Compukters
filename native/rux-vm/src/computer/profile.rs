use crate::computer::devices::{
    ComputerControlDevice, DebugSerialDevice, SerialInputDevice, TextDisplayDevice,
};
use crate::computer_abi;
use crate::low_machine::MemoryFault;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ComputerMachineProfile {
    pub(crate) memory_size: usize,
    pub(crate) page_size: u32,
    pub(crate) program_base: u32,
    pub(crate) hardware: Vec<ComputerHardwareConfig>,
}

impl ComputerMachineProfile {
    pub fn new(memory_size: usize) -> Self {
        Self {
            memory_size,
            page_size: computer_abi::PROFILE_V2_PAGE_SIZE,
            program_base: computer_abi::PROFILE_V2_PROGRAM_BASE,
            hardware: Vec::new(),
        }
    }

    pub fn computer_v1(memory_size: usize) -> Self {
        Self::new(memory_size)
            .with_hardware(ComputerHardwareConfig::control(
                computer_abi::COMPUTER_HARDWARE_ID_CONTROL,
                computer_abi::CONTROL_BASE,
            ))
            .with_hardware(ComputerHardwareConfig::debug_serial(
                computer_abi::COMPUTER_HARDWARE_ID_DEBUG,
                computer_abi::DEBUG_BASE,
            ))
            .with_hardware(ComputerHardwareConfig::serial_input(
                computer_abi::COMPUTER_HARDWARE_ID_SERIAL_INPUT,
                computer_abi::SERIAL_INPUT_BASE,
            ))
            .with_hardware(ComputerHardwareConfig::text_display(
                computer_abi::COMPUTER_HARDWARE_ID_DISPLAY0,
                computer_abi::DISPLAY0_BASE,
            ))
    }

    pub fn with_hardware(mut self, hardware: ComputerHardwareConfig) -> Self {
        self.hardware.push(hardware);
        self
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ComputerHardwareConfig {
    pub(crate) id: u32,
    pub(crate) mmio_base: u32,
    pub(crate) device: ComputerHardwareDevice,
}

impl ComputerHardwareConfig {
    pub fn control(id: u32, mmio_base: u32) -> Self {
        Self {
            id,
            mmio_base,
            device: ComputerHardwareDevice::Control,
        }
    }

    pub fn debug_serial(id: u32, mmio_base: u32) -> Self {
        Self {
            id,
            mmio_base,
            device: ComputerHardwareDevice::DebugSerial,
        }
    }

    pub fn serial_input(id: u32, mmio_base: u32) -> Self {
        Self {
            id,
            mmio_base,
            device: ComputerHardwareDevice::SerialInput,
        }
    }

    pub fn text_display(id: u32, mmio_base: u32) -> Self {
        Self {
            id,
            mmio_base,
            device: ComputerHardwareDevice::TextDisplay,
        }
    }

    pub(crate) fn mmio_size(&self) -> u32 {
        self.device.mmio_size()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ComputerHardwareDevice {
    Control,
    DebugSerial,
    SerialInput,
    TextDisplay,
}

impl ComputerHardwareDevice {
    fn mmio_size(self) -> u32 {
        match self {
            Self::Control => ComputerControlDevice::SIZE,
            Self::DebugSerial => DebugSerialDevice::SIZE,
            Self::SerialInput => SerialInputDevice::SIZE,
            Self::TextDisplay => TextDisplayDevice::SIZE,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct HardwareTableEntry {
    pub(crate) id: u32,
    pub(crate) mmio_base: u32,
    pub(crate) mmio_size: u32,
}

pub(crate) fn validate_profile_v2(profile: &ComputerMachineProfile) -> Result<(), MemoryFault> {
    validate_profile_v2_page_size(profile.page_size)?;
    validate_profile_v2_memory_size(profile.memory_size, profile.page_size)?;
    validate_profile_v2_program_base(profile)?;
    validate_profile_v2_hardware_table_size(profile)?;
    validate_profile_v2_hardware(profile)
}

fn validate_profile_v2_page_size(page_size: u32) -> Result<(), MemoryFault> {
    if page_size < 256 {
        return Err(MemoryFault::new(format!(
            "computer profile page size {page_size} is smaller than minimum 256",
        )));
    }
    if !page_size.is_power_of_two() {
        return Err(MemoryFault::new(format!(
            "computer profile page size {page_size} is not a power of two",
        )));
    }
    if page_size > 65536 {
        return Err(MemoryFault::new(format!(
            "computer profile page size {page_size} exceeds maximum 65536",
        )));
    }
    Ok(())
}

fn validate_profile_v2_memory_size(memory_size: usize, page_size: u32) -> Result<(), MemoryFault> {
    let page_size = page_size as usize;
    if memory_size < page_size {
        return Err(MemoryFault::new(format!(
            "computer memory size {memory_size} is smaller than profile page size {page_size}",
        )));
    }
    if memory_size % page_size != 0 {
        return Err(MemoryFault::new(format!(
            "computer memory size {memory_size} is not a multiple of profile page size {page_size}",
        )));
    }
    if memory_size > u32::MAX as usize {
        return Err(MemoryFault::new(format!(
            "computer memory size {memory_size} exceeds profile u32 address space",
        )));
    }
    Ok(())
}

fn validate_profile_v2_program_base(profile: &ComputerMachineProfile) -> Result<(), MemoryFault> {
    if profile.program_base < profile.page_size {
        return Err(MemoryFault::new(format!(
            "computer profile program base {:#010x} is below first page size {}",
            profile.program_base, profile.page_size,
        )));
    }
    if profile.program_base % profile.page_size != 0 {
        return Err(MemoryFault::new(format!(
            "computer profile program base {:#010x} is not aligned to page size {}",
            profile.program_base, profile.page_size,
        )));
    }
    if profile.program_base as usize >= profile.memory_size {
        return Err(MemoryFault::new(format!(
            "computer profile program base {:#010x} is outside RAM size {}",
            profile.program_base, profile.memory_size,
        )));
    }
    Ok(())
}

fn validate_profile_v2_hardware_table_size(
    profile: &ComputerMachineProfile,
) -> Result<(), MemoryFault> {
    let table_size = (profile.hardware.len() as u64)
        .checked_mul(u64::from(computer_abi::PROFILE_V2_HARDWARE_ENTRY_SIZE))
        .and_then(|value| value.checked_add(u64::from(computer_abi::PROFILE_V2_BOOT_INFO_SIZE)))
        .ok_or_else(|| {
            MemoryFault::new("computer hardware table size overflows address space".to_string())
        })?;
    if table_size > u64::from(profile.page_size) {
        return Err(MemoryFault::new(format!(
            "computer hardware table with {} entries does not fit boot page size {}",
            profile.hardware.len(),
            profile.page_size,
        )));
    }
    Ok(())
}

fn validate_profile_v2_hardware(profile: &ComputerMachineProfile) -> Result<(), MemoryFault> {
    let ram_start = 0_u64;
    let ram_end = profile.memory_size as u64;
    let mut ids = Vec::new();
    let mut ranges = Vec::new();

    for hardware in &profile.hardware {
        if hardware.id == 0 {
            return Err(MemoryFault::new(
                "computer hardware id must be non-zero".to_string(),
            ));
        }
        if ids.contains(&hardware.id) {
            return Err(MemoryFault::new(format!(
                "computer hardware id {} is duplicated",
                hardware.id,
            )));
        }
        ids.push(hardware.id);

        let mmio_size = hardware.mmio_size();
        if hardware.mmio_base == 0 {
            return Err(MemoryFault::new(format!(
                "computer hardware id {} mmio base must be non-zero",
                hardware.id,
            )));
        }
        if mmio_size == 0 {
            return Err(MemoryFault::new(format!(
                "computer hardware id {} mmio size must be non-zero",
                hardware.id,
            )));
        }
        let range_start = u64::from(hardware.mmio_base);
        let range_end = range_start + u64::from(mmio_size);
        if range_end > u64::from(u32::MAX) + 1 {
            return Err(MemoryFault::new(format!(
                "computer hardware id {} mmio range {:#010x} with size {} overflows address space",
                hardware.id, hardware.mmio_base, mmio_size,
            )));
        }
        if hardware.mmio_base % profile.page_size != 0 {
            return Err(MemoryFault::new(format!(
                "computer hardware id {} mmio base {:#010x} is not aligned to page size {}",
                hardware.id, hardware.mmio_base, profile.page_size,
            )));
        }
        if mmio_size % profile.page_size != 0 {
            return Err(MemoryFault::new(format!(
                "computer hardware id {} mmio size {} is not aligned to page size {}",
                hardware.id, mmio_size, profile.page_size,
            )));
        }
        if range_start < ram_end && ram_start < range_end {
            return Err(MemoryFault::new(format!(
                "computer hardware id {} mmio range {:#010x}..{:#010x} overlaps RAM {:#010x}..{:#010x}",
                hardware.id, hardware.mmio_base, range_end, ram_start, ram_end,
            )));
        }
        for (existing_id, existing_start, existing_end) in &ranges {
            if range_start < *existing_end && *existing_start < range_end {
                return Err(MemoryFault::new(format!(
                    "computer hardware id {} mmio range {:#010x}..{:#010x} overlaps hardware id {} range {:#010x}..{:#010x}",
                    hardware.id,
                    hardware.mmio_base,
                    range_end,
                    existing_id,
                    existing_start,
                    existing_end,
                )));
            }
        }
        ranges.push((hardware.id, range_start, range_end));
    }

    Ok(())
}
