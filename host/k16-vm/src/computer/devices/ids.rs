use crate::low_bus::MmioDeviceId;

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
