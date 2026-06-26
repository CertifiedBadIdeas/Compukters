use crate::low_bus::{MachineBusStatsSnapshot, MachineBusTrafficSnapshot, MmioDeviceId};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct K16ComputerStatsSnapshot {
    pub bus: MachineBusStatsSnapshot,
    pub devices: Vec<K16ComputerDeviceStats>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct K16ComputerDeviceStats {
    pub name: &'static str,
    pub device_id: MmioDeviceId,
    pub base: u32,
    pub size: u32,
    pub traffic: MachineBusTrafficSnapshot,
}
