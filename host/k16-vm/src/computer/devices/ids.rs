use super::control::ComputerControlDevice;
use super::gpu::GpuDevice;
use super::keyboard::KeyboardDevice;
use super::mmu::MmuControlDevice;
use super::serial::{DebugSerialDevice, SerialInputDevice};
use super::storage::StoragePortDevice;
use super::timer::TimerDevice;
use crate::computer::profile::ComputerHardwareDevice;
use crate::low_bus::{MachineBus, MmioDeviceId};
use crate::low_machine::MemoryFault;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ComputerDeviceStatsKind {
    Generic,
    Gpu,
    Storage0,
    Storage1,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct ComputerDeviceDescriptor {
    pub(crate) id: Option<MmioDeviceId>,
    pub(crate) name: &'static str,
    pub(crate) readable: bool,
    pub(crate) writable: bool,
    pub(crate) stats_kind: ComputerDeviceStatsKind,
}

#[derive(Debug, Clone, Default)]
pub(crate) struct ComputerDeviceIds {
    control: Option<MmioDeviceId>,
    debug_serial: Option<MmioDeviceId>,
    serial_input: Option<MmioDeviceId>,
    gpu0: Option<MmioDeviceId>,
    storage0: Option<MmioDeviceId>,
    storage1: Option<MmioDeviceId>,
    timer0: Option<MmioDeviceId>,
    keyboard0: Option<MmioDeviceId>,
    mmu0: Option<MmioDeviceId>,
    bios_flash: Option<MmioDeviceId>,
}

impl ComputerDeviceIds {
    pub(crate) fn remember_hardware_device(
        &mut self,
        hardware_id: u32,
        device: &ComputerHardwareDevice,
        device_id: MmioDeviceId,
    ) -> Result<(), MemoryFault> {
        match device {
            ComputerHardwareDevice::Control => self.control = Some(device_id),
            ComputerHardwareDevice::DebugSerial => self.debug_serial = Some(device_id),
            ComputerHardwareDevice::SerialInput => self.serial_input = Some(device_id),
            ComputerHardwareDevice::Gpu => self.gpu0 = Some(device_id),
            ComputerHardwareDevice::StoragePort(_) => match hardware_id {
                crate::computer_abi::COMPUTER_HARDWARE_ID_STORAGE0 => {
                    self.storage0 = Some(device_id)
                }
                crate::computer_abi::COMPUTER_HARDWARE_ID_STORAGE1 => {
                    self.storage1 = Some(device_id)
                }
                id => {
                    return Err(MemoryFault::new(format!(
                        "unsupported computer storage hardware id {id}",
                    )))
                }
            },
            ComputerHardwareDevice::Timer => self.timer0 = Some(device_id),
            ComputerHardwareDevice::Keyboard => self.keyboard0 = Some(device_id),
            ComputerHardwareDevice::Mmu => self.mmu0 = Some(device_id),
        }
        Ok(())
    }

    pub(crate) fn has_bios_flash(&self) -> bool {
        self.bios_flash.is_some()
    }

    pub(crate) fn remember_bios_flash(&mut self, device_id: MmioDeviceId) {
        self.bios_flash = Some(device_id);
    }

    pub(crate) fn control<'a>(&self, bus: &'a MachineBus) -> Option<&'a ComputerControlDevice> {
        self.control
            .and_then(|id| bus.device::<ComputerControlDevice>(id))
    }

    pub(crate) fn control_mut<'a>(
        &self,
        bus: &'a mut MachineBus,
    ) -> Option<&'a mut ComputerControlDevice> {
        self.control
            .and_then(|id| bus.device_mut::<ComputerControlDevice>(id))
    }

    pub(crate) fn debug_serial<'a>(&self, bus: &'a MachineBus) -> Option<&'a DebugSerialDevice> {
        self.debug_serial
            .and_then(|id| bus.device::<DebugSerialDevice>(id))
    }

    pub(crate) fn debug_serial_mut<'a>(
        &self,
        bus: &'a mut MachineBus,
    ) -> Option<&'a mut DebugSerialDevice> {
        self.debug_serial
            .and_then(|id| bus.device_mut::<DebugSerialDevice>(id))
    }

    pub(crate) fn serial_input<'a>(&self, bus: &'a MachineBus) -> Option<&'a SerialInputDevice> {
        self.serial_input
            .and_then(|id| bus.device::<SerialInputDevice>(id))
    }

    pub(crate) fn serial_input_mut<'a>(
        &self,
        bus: &'a mut MachineBus,
    ) -> Option<&'a mut SerialInputDevice> {
        self.serial_input
            .and_then(|id| bus.device_mut::<SerialInputDevice>(id))
    }

    pub(crate) fn gpu0<'a>(&self, bus: &'a MachineBus) -> Option<&'a GpuDevice> {
        self.gpu0.and_then(|id| bus.device::<GpuDevice>(id))
    }

    pub(crate) fn gpu0_mut<'a>(&self, bus: &'a mut MachineBus) -> Option<&'a mut GpuDevice> {
        self.gpu0.and_then(|id| bus.device_mut::<GpuDevice>(id))
    }

    pub(crate) fn mmu0_mut<'a>(&self, bus: &'a mut MachineBus) -> Option<&'a mut MmuControlDevice> {
        self.mmu0
            .and_then(|id| bus.device_mut::<MmuControlDevice>(id))
    }

    pub(crate) fn storage0<'a>(&self, bus: &'a MachineBus) -> Option<&'a StoragePortDevice> {
        self.storage0
            .and_then(|id| bus.device::<StoragePortDevice>(id))
    }

    pub(crate) fn storage0_mut<'a>(
        &self,
        bus: &'a mut MachineBus,
    ) -> Option<&'a mut StoragePortDevice> {
        self.storage0
            .and_then(|id| bus.device_mut::<StoragePortDevice>(id))
    }

    pub(crate) fn storage1<'a>(&self, bus: &'a MachineBus) -> Option<&'a StoragePortDevice> {
        self.storage1
            .and_then(|id| bus.device::<StoragePortDevice>(id))
    }

    pub(crate) fn storage1_mut<'a>(
        &self,
        bus: &'a mut MachineBus,
    ) -> Option<&'a mut StoragePortDevice> {
        self.storage1
            .and_then(|id| bus.device_mut::<StoragePortDevice>(id))
    }

    pub(crate) fn timer0<'a>(&self, bus: &'a MachineBus) -> Option<&'a TimerDevice> {
        self.timer0.and_then(|id| bus.device::<TimerDevice>(id))
    }

    pub(crate) fn timer0_mut<'a>(&self, bus: &'a mut MachineBus) -> Option<&'a mut TimerDevice> {
        self.timer0.and_then(|id| bus.device_mut::<TimerDevice>(id))
    }

    pub(crate) fn keyboard0<'a>(&self, bus: &'a MachineBus) -> Option<&'a KeyboardDevice> {
        self.keyboard0
            .and_then(|id| bus.device::<KeyboardDevice>(id))
    }

    pub(crate) fn keyboard0_mut<'a>(
        &self,
        bus: &'a mut MachineBus,
    ) -> Option<&'a mut KeyboardDevice> {
        self.keyboard0
            .and_then(|id| bus.device_mut::<KeyboardDevice>(id))
    }

    pub(crate) fn descriptors(&self) -> [ComputerDeviceDescriptor; 10] {
        [
            self.descriptor(
                self.control,
                "control",
                true,
                true,
                ComputerDeviceStatsKind::Generic,
            ),
            self.descriptor(
                self.debug_serial,
                "debug",
                true,
                true,
                ComputerDeviceStatsKind::Generic,
            ),
            self.descriptor(
                self.serial_input,
                "serial-input",
                true,
                true,
                ComputerDeviceStatsKind::Generic,
            ),
            self.descriptor(self.gpu0, "gpu0", true, true, ComputerDeviceStatsKind::Gpu),
            self.descriptor(
                self.storage0,
                "storage0",
                true,
                true,
                ComputerDeviceStatsKind::Storage0,
            ),
            self.descriptor(
                self.storage1,
                "storage1",
                true,
                false,
                ComputerDeviceStatsKind::Storage1,
            ),
            self.descriptor(
                self.timer0,
                "timer0",
                true,
                true,
                ComputerDeviceStatsKind::Generic,
            ),
            self.descriptor(
                self.keyboard0,
                "keyboard0",
                true,
                true,
                ComputerDeviceStatsKind::Generic,
            ),
            self.descriptor(
                self.mmu0,
                "mmu0",
                true,
                true,
                ComputerDeviceStatsKind::Generic,
            ),
            self.descriptor(
                self.bios_flash,
                "bios-flash",
                true,
                false,
                ComputerDeviceStatsKind::Generic,
            ),
        ]
    }

    fn descriptor(
        &self,
        id: Option<MmioDeviceId>,
        name: &'static str,
        readable: bool,
        writable: bool,
        stats_kind: ComputerDeviceStatsKind,
    ) -> ComputerDeviceDescriptor {
        ComputerDeviceDescriptor {
            id,
            name,
            readable,
            writable,
            stats_kind,
        }
    }
}
