use k16_abi::computer::control;

use crate::mmio;

#[repr(C)]
pub struct OsStats {
    path_lookups: u64,
    inode_loads: u64,
    dir_entry_scans: u64,
    file_opens: u64,
    file_reads: u64,
    stat_calls: u64,
}

const OS_STATS_SIZE: u32 = core::mem::size_of::<OsStats>() as u32;

static mut OS_STATS: OsStats = OsStats {
    path_lookups: 0,
    inode_loads: 0,
    dir_entry_scans: 0,
    file_opens: 0,
    file_reads: 0,
    stat_calls: 0,
};

pub fn register() {
    let addr = core::ptr::addr_of!(OS_STATS) as u32;
    unsafe {
        mmio::write_i32(control::OS_STATS_ADDR, addr as i32);
        mmio::write_i32(control::OS_STATS_SIZE, OS_STATS_SIZE as i32);
    }
}

pub fn record_path_lookup() {
    unsafe { increment(core::ptr::addr_of_mut!(OS_STATS.path_lookups)) }
}

pub fn record_inode_load() {
    unsafe { increment(core::ptr::addr_of_mut!(OS_STATS.inode_loads)) }
}

pub fn record_dir_entry_scan() {
    unsafe { increment(core::ptr::addr_of_mut!(OS_STATS.dir_entry_scans)) }
}

pub fn record_file_open() {
    unsafe { increment(core::ptr::addr_of_mut!(OS_STATS.file_opens)) }
}

pub fn record_file_read() {
    unsafe { increment(core::ptr::addr_of_mut!(OS_STATS.file_reads)) }
}

pub fn record_stat_call() {
    unsafe { increment(core::ptr::addr_of_mut!(OS_STATS.stat_calls)) }
}

unsafe fn increment(counter: *mut u64) {
    let value = unsafe { core::ptr::read_volatile(counter) };
    unsafe { core::ptr::write_volatile(counter, value.wrapping_add(1)) };
}
