use crate::low_bus::{MachineBusStatsSnapshot, MachineBusTrafficSnapshot, MmioDeviceId};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct K16ComputerStatsSnapshot {
    pub bus: MachineBusStatsSnapshot,
    pub os: K16ComputerOsStatsSnapshot,
    pub decode_cache: K16ComputerDecodeCacheStatsSnapshot,
    pub devices: Vec<K16ComputerDeviceStats>,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct K16ComputerDecodeCacheStatsSnapshot {
    pub entries: u64,
    pub hits: u64,
    pub misses: u64,
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
    pub media_read_blocks: u64,
    pub media_write_blocks: u64,
    pub unique_read_blocks: u64,
    pub repeated_read_blocks: u64,
    pub partition_table_read_blocks: u64,
    pub boot_metadata_read_blocks: u64,
    pub boot_data_read_blocks: u64,
    pub root_metadata_read_blocks: u64,
    pub root_data_read_blocks: u64,
    pub unknown_read_blocks: u64,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct K16ComputerOsStatsSnapshot {
    pub path_lookups: u64,
    pub inode_loads: u64,
    pub dir_entry_scans: u64,
    pub file_opens: u64,
    pub file_reads: u64,
    pub stat_calls: u64,
    pub process_spawns: u64,
    pub program_loads: u64,
    pub dynamic_import_loads: u64,
    pub library_loads: u64,
    pub read_dir_calls: u64,
    pub program_load_bytes: u64,
    pub dynamic_import_bytes: u64,
    pub library_load_bytes: u64,
    pub generic_file_data_read_blocks: u64,
    pub generic_file_data_read_bytes: u64,
    pub read_dir_data_read_blocks: u64,
    pub read_dir_data_read_bytes: u64,
    pub program_data_read_blocks: u64,
    pub program_data_read_bytes: u64,
    pub dynamic_import_data_read_blocks: u64,
    pub dynamic_import_data_read_bytes: u64,
    pub library_data_read_blocks: u64,
    pub library_data_read_bytes: u64,
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
