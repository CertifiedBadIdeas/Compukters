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
    pub storage: K16ComputerStorageStatsSnapshot,
    pub gpu: K16ComputerGpuStatsSnapshot,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct K16ComputerStorageStatsSnapshot {
    pub read_commands: u64,
    pub write_commands: u64,
    pub flush_commands: u64,
    pub bytes_read: u64,
    pub bytes_written: u64,
    pub failed_commands: u64,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct K16ComputerGpuStatsSnapshot {
    pub blit_buffer_commands: u64,
    pub blit_pixels: u64,
    pub blit_source_bytes: u64,
    pub present_commands: u64,
    pub frames: u64,
    pub frame_tiles: u64,
    pub frame_payload_bytes: u64,
}
