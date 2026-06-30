use crate::low_bus::MmioDeviceId;

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
