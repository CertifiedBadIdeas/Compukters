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
    process_spawns: u64,
    program_loads: u64,
    dynamic_import_loads: u64,
    library_loads: u64,
    read_dir_calls: u64,
    program_load_bytes: u64,
    dynamic_import_bytes: u64,
    library_load_bytes: u64,
    generic_file_data_read_blocks: u64,
    generic_file_data_read_bytes: u64,
    read_dir_data_read_blocks: u64,
    read_dir_data_read_bytes: u64,
    program_data_read_blocks: u64,
    program_data_read_bytes: u64,
    dynamic_import_data_read_blocks: u64,
    dynamic_import_data_read_bytes: u64,
    library_data_read_blocks: u64,
    library_data_read_bytes: u64,
    block_cache_hits: u64,
    block_cache_misses: u64,
    block_cache_batch_reads: u64,
    init_program_file_data_read_blocks: u64,
    init_program_file_data_read_bytes: u64,
    shell_program_file_data_read_blocks: u64,
    shell_program_file_data_read_bytes: u64,
    other_program_file_data_read_blocks: u64,
    other_program_file_data_read_bytes: u64,
    libkraft_library_file_data_read_blocks: u64,
    libkraft_library_file_data_read_bytes: u64,
    other_library_file_data_read_blocks: u64,
    other_library_file_data_read_bytes: u64,
    last_exited_program_heap_pages: u64,
}

const OS_STATS_SIZE: u32 = core::mem::size_of::<OsStats>() as u32;

static mut OS_STATS: OsStats = OsStats {
    path_lookups: 0,
    inode_loads: 0,
    dir_entry_scans: 0,
    file_opens: 0,
    file_reads: 0,
    stat_calls: 0,
    process_spawns: 0,
    program_loads: 0,
    dynamic_import_loads: 0,
    library_loads: 0,
    read_dir_calls: 0,
    program_load_bytes: 0,
    dynamic_import_bytes: 0,
    library_load_bytes: 0,
    generic_file_data_read_blocks: 0,
    generic_file_data_read_bytes: 0,
    read_dir_data_read_blocks: 0,
    read_dir_data_read_bytes: 0,
    program_data_read_blocks: 0,
    program_data_read_bytes: 0,
    dynamic_import_data_read_blocks: 0,
    dynamic_import_data_read_bytes: 0,
    library_data_read_blocks: 0,
    library_data_read_bytes: 0,
    block_cache_hits: 0,
    block_cache_misses: 0,
    block_cache_batch_reads: 0,
    init_program_file_data_read_blocks: 0,
    init_program_file_data_read_bytes: 0,
    shell_program_file_data_read_blocks: 0,
    shell_program_file_data_read_bytes: 0,
    other_program_file_data_read_blocks: 0,
    other_program_file_data_read_bytes: 0,
    libkraft_library_file_data_read_blocks: 0,
    libkraft_library_file_data_read_bytes: 0,
    other_library_file_data_read_blocks: 0,
    other_library_file_data_read_bytes: 0,
    last_exited_program_heap_pages: 0,
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

pub fn record_process_spawn() {
    unsafe { increment(core::ptr::addr_of_mut!(OS_STATS.process_spawns)) }
}

pub fn record_program_load() {
    unsafe { increment(core::ptr::addr_of_mut!(OS_STATS.program_loads)) }
}

pub fn record_dynamic_import_load() {
    unsafe { increment(core::ptr::addr_of_mut!(OS_STATS.dynamic_import_loads)) }
}

pub fn record_library_load() {
    unsafe { increment(core::ptr::addr_of_mut!(OS_STATS.library_loads)) }
}

pub fn record_read_dir_call() {
    unsafe { increment(core::ptr::addr_of_mut!(OS_STATS.read_dir_calls)) }
}

pub fn record_program_load_bytes(bytes: u32) {
    unsafe {
        add(
            core::ptr::addr_of_mut!(OS_STATS.program_load_bytes),
            u64::from(bytes),
        )
    }
}

pub fn record_dynamic_import_bytes(bytes: u32) {
    unsafe {
        add(
            core::ptr::addr_of_mut!(OS_STATS.dynamic_import_bytes),
            u64::from(bytes),
        )
    }
}

pub fn record_library_load_bytes(bytes: u32) {
    unsafe {
        add(
            core::ptr::addr_of_mut!(OS_STATS.library_load_bytes),
            u64::from(bytes),
        )
    }
}

pub fn record_generic_file_data_read(bytes: u32) {
    unsafe {
        increment(core::ptr::addr_of_mut!(
            OS_STATS.generic_file_data_read_blocks
        ));
        add(
            core::ptr::addr_of_mut!(OS_STATS.generic_file_data_read_bytes),
            u64::from(bytes),
        );
    }
}

pub fn record_read_dir_data_read(bytes: u32) {
    unsafe {
        increment(core::ptr::addr_of_mut!(OS_STATS.read_dir_data_read_blocks));
        add(
            core::ptr::addr_of_mut!(OS_STATS.read_dir_data_read_bytes),
            u64::from(bytes),
        );
    }
}

pub fn record_program_data_read(bytes: u32) {
    unsafe {
        increment(core::ptr::addr_of_mut!(OS_STATS.program_data_read_blocks));
        add(
            core::ptr::addr_of_mut!(OS_STATS.program_data_read_bytes),
            u64::from(bytes),
        );
    }
}

pub fn record_dynamic_import_data_read(bytes: u32) {
    unsafe {
        increment(core::ptr::addr_of_mut!(
            OS_STATS.dynamic_import_data_read_blocks
        ));
        add(
            core::ptr::addr_of_mut!(OS_STATS.dynamic_import_data_read_bytes),
            u64::from(bytes),
        );
    }
}

pub fn record_library_data_read(bytes: u32) {
    unsafe {
        increment(core::ptr::addr_of_mut!(OS_STATS.library_data_read_blocks));
        add(
            core::ptr::addr_of_mut!(OS_STATS.library_data_read_bytes),
            u64::from(bytes),
        );
    }
}

pub fn record_block_cache_hit() {
    unsafe { increment(core::ptr::addr_of_mut!(OS_STATS.block_cache_hits)) }
}

pub fn record_block_cache_miss() {
    unsafe { increment(core::ptr::addr_of_mut!(OS_STATS.block_cache_misses)) }
}

pub fn record_block_cache_batch_read() {
    unsafe { increment(core::ptr::addr_of_mut!(OS_STATS.block_cache_batch_reads)) }
}

pub fn record_init_program_file_data_read(bytes: u32) {
    unsafe {
        increment(core::ptr::addr_of_mut!(
            OS_STATS.init_program_file_data_read_blocks
        ));
        add(
            core::ptr::addr_of_mut!(OS_STATS.init_program_file_data_read_bytes),
            u64::from(bytes),
        );
    }
}

pub fn record_shell_program_file_data_read(bytes: u32) {
    unsafe {
        increment(core::ptr::addr_of_mut!(
            OS_STATS.shell_program_file_data_read_blocks
        ));
        add(
            core::ptr::addr_of_mut!(OS_STATS.shell_program_file_data_read_bytes),
            u64::from(bytes),
        );
    }
}

pub fn record_other_program_file_data_read(bytes: u32) {
    unsafe {
        increment(core::ptr::addr_of_mut!(
            OS_STATS.other_program_file_data_read_blocks
        ));
        add(
            core::ptr::addr_of_mut!(OS_STATS.other_program_file_data_read_bytes),
            u64::from(bytes),
        );
    }
}

pub fn record_libkraft_library_file_data_read(bytes: u32) {
    unsafe {
        increment(core::ptr::addr_of_mut!(
            OS_STATS.libkraft_library_file_data_read_blocks
        ));
        add(
            core::ptr::addr_of_mut!(OS_STATS.libkraft_library_file_data_read_bytes),
            u64::from(bytes),
        );
    }
}

pub fn record_other_library_file_data_read(bytes: u32) {
    unsafe {
        increment(core::ptr::addr_of_mut!(
            OS_STATS.other_library_file_data_read_blocks
        ));
        add(
            core::ptr::addr_of_mut!(OS_STATS.other_library_file_data_read_bytes),
            u64::from(bytes),
        );
    }
}

pub fn record_last_exited_program_heap_pages(heap_pages: u64) {
    unsafe {
        core::ptr::write_volatile(
            core::ptr::addr_of_mut!(OS_STATS.last_exited_program_heap_pages),
            heap_pages,
        )
    };
}

unsafe fn increment(counter: *mut u64) {
    unsafe { add(counter, 1) };
}

unsafe fn add(counter: *mut u64, amount: u64) {
    let value = unsafe { core::ptr::read_volatile(counter) };
    unsafe { core::ptr::write_volatile(counter, value.wrapping_add(amount)) };
}

#[cfg(test)]
mod tests {
    use super::{record_last_exited_program_heap_pages, OsStats, OS_STATS};

    #[test]
    fn os_stats_appends_last_exited_heap_gauge_at_byte_296() {
        assert_eq!(core::mem::size_of::<OsStats>(), 304);
        assert_eq!(
            core::mem::offset_of!(OsStats, last_exited_program_heap_pages),
            296,
        );
    }

    #[test]
    fn last_exited_heap_pages_is_a_last_value_gauge() {
        record_last_exited_program_heap_pages(7);
        record_last_exited_program_heap_pages(3);

        assert_eq!(
            unsafe {
                core::ptr::read_volatile(core::ptr::addr_of!(
                    OS_STATS.last_exited_program_heap_pages
                ))
            },
            3,
        );
    }
}
