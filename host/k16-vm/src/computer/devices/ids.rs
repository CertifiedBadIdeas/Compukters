use super::control::ComputerControlDevice;
use super::gpu::GpuDevice;
use super::keyboard::KeyboardDevice;
use super::mmu::MmuControlDevice;
use super::serial::{DebugSerialDevice, SerialInputDevice};
use super::storage::StoragePortDevice;
use super::timer::TimerDevice;
use crate::low_bus::{MachineBus, MmioDeviceId};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ComputerDeviceStatsKind {
    Generic,
    Gpu,
    Storage,
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
    pub(crate) control: Option<MmioDeviceId>,
    pub(crate) debug_serial: Option<MmioDeviceId>,
    pub(crate) serial_input: Option<MmioDeviceId>,
    pub(crate) gpu0: Option<MmioDeviceId>,
    pub(crate) storage0: Option<MmioDeviceId>,
    pub(crate) timer0: Option<MmioDeviceId>,
    pub(crate) keyboard0: Option<MmioDeviceId>,
    pub(crate) mmu0: Option<MmioDeviceId>,
    pub(crate) bios_flash: Option<MmioDeviceId>,
}

impl ComputerDeviceIds {
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

    pub(crate) fn descriptors(&self) -> [ComputerDeviceDescriptor; 9] {
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
                ComputerDeviceStatsKind::Storage,
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
