use std::path::PathBuf;
use std::ptr::null_mut;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyte, jbyteArray, jint, jlong, jlongArray};
use jni::JNIEnv;

use crate::computer::stats::{K16ComputerGpuStatsSnapshot, K16ComputerStorageStatsSnapshot};
use crate::k16::K16Signal;
use crate::k16_computer::{K16ComputerHandle, K16ComputerStatsSnapshot};
use crate::low_bus::MachineBusTrafficSnapshot;

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_createK16ComputerFromBiosFlashNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    bios_flash_path: JString<'_>,
    memory_size: jint,
    max_steps: jlong,
    storage0_path: JString<'_>,
    storage1_path: JString<'_>,
) -> jlong {
    let bios_flash_path = match env.get_string(&bios_flash_path) {
        Ok(path) => path.to_string_lossy().into_owned(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read K16 BIOS flash path: {error}"),
            );
            return 0;
        }
    };
    let storage0_path = match env.get_string(&storage0_path) {
        Ok(path) => path.to_string_lossy().into_owned(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read K16 computer storage0 path: {error}"),
            );
            return 0;
        }
    };
    let storage1_path = if storage1_path.is_null() {
        None
    } else {
        match env.get_string(&storage1_path) {
            Ok(path) => Some(PathBuf::from(path.to_string_lossy().into_owned())),
            Err(error) => {
                let _ = env.throw_new(
                    "java/lang/IllegalArgumentException",
                    format!("Cannot read K16 computer storage1 path: {error}"),
                );
                return 0;
            }
        }
    };
    match K16ComputerHandle::create_k16_bios_flash_path_with_storage_paths(
        bios_flash_path,
        memory_size.max(1) as usize,
        max_steps.max(1) as u64,
        storage0_path,
        storage1_path,
    ) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_restoreK16ComputerFromBiosFlashSnapshotNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    bios_flash_path: JString<'_>,
    memory_size: jint,
    storage0_path: JString<'_>,
    storage1_path: JString<'_>,
    snapshot: JByteArray<'_>,
) -> jlong {
    let bios_flash_path = match env.get_string(&bios_flash_path) {
        Ok(path) => path.to_string_lossy().into_owned(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read K16 BIOS flash path: {error}"),
            );
            return 0;
        }
    };
    let storage0_path = match env.get_string(&storage0_path) {
        Ok(path) => path.to_string_lossy().into_owned(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read K16 computer storage0 path: {error}"),
            );
            return 0;
        }
    };
    let storage1_path = if storage1_path.is_null() {
        None
    } else {
        match env.get_string(&storage1_path) {
            Ok(path) => Some(PathBuf::from(path.to_string_lossy().into_owned())),
            Err(error) => {
                let _ = env.throw_new(
                    "java/lang/IllegalArgumentException",
                    format!("Cannot read K16 computer storage1 path: {error}"),
                );
                return 0;
            }
        }
    };
    let snapshot = match env.convert_byte_array(&snapshot) {
        Ok(bytes) => bytes,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read K16 computer snapshot bytes: {error}"),
            );
            return 0;
        }
    };
    match K16ComputerHandle::restore_k16_bios_flash_snapshot_path_with_storage_paths(
        bios_flash_path,
        memory_size.max(1) as usize,
        storage0_path,
        storage1_path,
        &snapshot,
    ) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runK16ComputerUntilSignalNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlongArray {
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    let signal = match handle.run_k16_until_signal() {
        Ok(signal) => signal,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            return null_mut();
        }
    };
    long_array_or_throw(&mut env, &k16_signal_values(signal))
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_advanceK16ComputerGameTickNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return,
    };
    if let Err(error) = handle.advance_game_tick() {
        let _ = env.throw_new("java/lang/IllegalStateException", error);
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_attachK16ComputerRetainedDisplayViewerNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    viewer_token: jlong,
    computer_id: jint,
) -> jlong {
    if viewer_token <= 0 || computer_id <= 0 {
        let _ = env.throw_new(
            "java/lang/IllegalArgumentException",
            "Retained display viewer token and computer id must be positive",
        );
        return 0;
    }
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return 0,
    };
    match handle.attach_retained_display_viewer(viewer_token as u64, computer_id as u32) {
        Ok(epoch) => epoch as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_detachK16ComputerRetainedDisplayViewerNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    viewer_token: jlong,
) -> jboolean {
    if viewer_token <= 0 {
        let _ = env.throw_new(
            "java/lang/IllegalArgumentException",
            "Retained display viewer token must be positive",
        );
        return 0;
    }
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return 0,
    };
    jboolean::from(handle.detach_retained_display_viewer(viewer_token as u64))
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_acceptK16ComputerRetainedDisplayServerboundNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    viewer_token: jlong,
    payload: JByteArray<'_>,
) -> jint {
    if viewer_token <= 0 {
        let _ = env.throw_new(
            "java/lang/IllegalArgumentException",
            "Retained display viewer token must be positive",
        );
        return 0;
    }
    let payload = match env.convert_byte_array(&payload) {
        Ok(payload) => payload,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read retained display serverbound payload: {error}"),
            );
            return 0;
        }
    };
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return 0,
    };
    match handle.accept_retained_display_serverbound(viewer_token as u64, &payload) {
        Ok(outcome) => outcome,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_drainK16ComputerRetainedDisplayPayloadNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    viewer_token: jlong,
) -> jbyteArray {
    if viewer_token <= 0 {
        let _ = env.throw_new(
            "java/lang/IllegalArgumentException",
            "Retained display viewer token must be positive",
        );
        return null_mut();
    }
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    byte_array_or_throw(
        &mut env,
        &handle.drain_retained_display_payload(viewer_token as u64),
    )
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_drainK16ComputerRetainedDisplayPayloadsNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    match handle.drain_retained_display_payloads() {
        Ok(Some(batch)) => byte_array_or_throw(&mut env, &batch),
        Ok(None) => null_mut(),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_k16ComputerControlNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlongArray {
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    let control = handle.control();
    long_array_or_throw(
        &mut env,
        &[
            i64::from(control.status),
            i64::from(control.exit_code),
            i64::from(control.panic_code),
        ],
    )
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_k16ComputerDebugOutputNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    byte_array_or_throw(&mut env, handle.debug_output_bytes())
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_drainK16ComputerDebugOutputNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    byte_array_or_throw(&mut env, &handle.drain_debug_output_bytes())
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_k16ComputerStorage0MediaSnapshotNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    let payload = handle.storage0_media_snapshot().unwrap_or_default();
    byte_array_or_throw(&mut env, &payload)
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_k16ComputerMachineSnapshotNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    let payload = match handle.snapshot_v1() {
        Ok(payload) => payload,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            return null_mut();
        }
    };
    byte_array_or_throw(&mut env, &payload)
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_k16ComputerStatsSnapshotNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlongArray {
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    long_array_or_throw(
        &mut env,
        &k16_computer_stats_snapshot_values(&handle.stats_snapshot()),
    )
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_pushK16ComputerSerialInputNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    bytes: JByteArray<'_>,
) {
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return,
    };
    let bytes = match env.convert_byte_array(&bytes) {
        Ok(bytes) => bytes,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read K16 computer serial input: {error}"),
            );
            return;
        }
    };
    handle.push_serial_input(&bytes);
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_pushK16ComputerKeyboardKeyDownNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    key: jint,
    repeat: jboolean,
    modifiers: jint,
) {
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return,
    };
    handle.push_keyboard_key_down(key as u32, repeat != 0, modifiers);
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_pushK16ComputerKeyboardKeyUpNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    key: jint,
    modifiers: jint,
) {
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return,
    };
    handle.push_keyboard_key_up(key as u32, modifiers);
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_pushK16ComputerKeyboardCharNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    value: jbyte,
) {
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return,
    };
    handle.push_keyboard_char(value as u8);
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_pushK16ComputerKeyboardPasteBytesNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    bytes: JByteArray<'_>,
) {
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return,
    };
    let bytes = match env.convert_byte_array(&bytes) {
        Ok(bytes) => bytes,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read K16 computer keyboard paste bytes: {error}"),
            );
            return;
        }
    };
    for byte in bytes {
        handle.push_keyboard_paste_byte(byte);
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_freeK16ComputerNative(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    if handle != 0 {
        unsafe { drop(Box::from_raw(handle as *mut K16ComputerHandle)) };
    }
}

fn k16_computer_handle_mut(
    env: &mut JNIEnv<'_>,
    handle: jlong,
) -> Option<&'static mut K16ComputerHandle> {
    if handle == 0 {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Native K16 computer handle is zero",
        );
        return None;
    }
    let pointer = handle as *mut K16ComputerHandle;
    if pointer.is_null() {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Native K16 computer handle is null",
        );
        return None;
    }
    Some(unsafe { &mut *pointer })
}

fn k16_signal_values(signal: K16Signal) -> [jlong; 2] {
    match signal {
        K16Signal::Halt => [1, 0],
        K16Signal::Wait => [8, 0],
        K16Signal::Yield => [7, 0],
        K16Signal::StepLimitExceeded => [6, 0],
    }
}

fn k16_computer_stats_snapshot_values(snapshot: &K16ComputerStatsSnapshot) -> Vec<jlong> {
    let mut values = Vec::with_capacity(53 + snapshot.devices.len() * 36);
    values.push(17);
    push_traffic_values(&mut values, snapshot.bus.ram);
    push_traffic_values(&mut values, snapshot.bus.mmio);
    values.push(snapshot.os.path_lookups as jlong);
    values.push(snapshot.os.inode_loads as jlong);
    values.push(snapshot.os.dir_entry_scans as jlong);
    values.push(snapshot.os.file_opens as jlong);
    values.push(snapshot.os.file_reads as jlong);
    values.push(snapshot.os.stat_calls as jlong);
    values.push(snapshot.os.process_spawns as jlong);
    values.push(snapshot.os.program_loads as jlong);
    values.push(snapshot.os.dynamic_import_loads as jlong);
    values.push(snapshot.os.library_loads as jlong);
    values.push(snapshot.os.read_dir_calls as jlong);
    values.push(snapshot.os.program_load_bytes as jlong);
    values.push(snapshot.os.dynamic_import_bytes as jlong);
    values.push(snapshot.os.library_load_bytes as jlong);
    values.push(snapshot.os.generic_file_data_read_blocks as jlong);
    values.push(snapshot.os.generic_file_data_read_bytes as jlong);
    values.push(snapshot.os.read_dir_data_read_blocks as jlong);
    values.push(snapshot.os.read_dir_data_read_bytes as jlong);
    values.push(snapshot.os.program_data_read_blocks as jlong);
    values.push(snapshot.os.program_data_read_bytes as jlong);
    values.push(snapshot.os.dynamic_import_data_read_blocks as jlong);
    values.push(snapshot.os.dynamic_import_data_read_bytes as jlong);
    values.push(snapshot.os.library_data_read_blocks as jlong);
    values.push(snapshot.os.library_data_read_bytes as jlong);
    values.push(snapshot.os.block_cache_hits as jlong);
    values.push(snapshot.os.block_cache_misses as jlong);
    values.push(snapshot.os.block_cache_batch_reads as jlong);
    values.push(snapshot.os.init_program_file_data_read_blocks as jlong);
    values.push(snapshot.os.init_program_file_data_read_bytes as jlong);
    values.push(snapshot.os.shell_program_file_data_read_blocks as jlong);
    values.push(snapshot.os.shell_program_file_data_read_bytes as jlong);
    values.push(snapshot.os.other_program_file_data_read_blocks as jlong);
    values.push(snapshot.os.other_program_file_data_read_bytes as jlong);
    values.push(snapshot.os.libkraft_library_file_data_read_blocks as jlong);
    values.push(snapshot.os.libkraft_library_file_data_read_bytes as jlong);
    values.push(snapshot.os.other_library_file_data_read_blocks as jlong);
    values.push(snapshot.os.other_library_file_data_read_bytes as jlong);
    values.push(snapshot.os.last_exited_program_heap_pages as jlong);
    values.push(snapshot.decode_cache.entries as jlong);
    values.push(snapshot.decode_cache.hits as jlong);
    values.push(snapshot.decode_cache.misses as jlong);
    values.push(snapshot.cpu_steps as jlong);
    values.push(snapshot.game_ticks as jlong);
    values.push(snapshot.devices.len() as jlong);
    for device in &snapshot.devices {
        values.push(device.device_id as jlong);
        values.push(i64::from(device.base));
        values.push(i64::from(device.size));
        push_traffic_values(&mut values, device.traffic);
        push_storage_values(&mut values, device.storage);
        push_gpu_values(&mut values, device.gpu);
    }
    values
}

fn push_traffic_values(values: &mut Vec<jlong>, traffic: MachineBusTrafficSnapshot) {
    values.push(traffic.loads as jlong);
    values.push(traffic.stores as jlong);
    values.push(traffic.bytes_read as jlong);
    values.push(traffic.bytes_written as jlong);
}

fn push_storage_values(values: &mut Vec<jlong>, storage: K16ComputerStorageStatsSnapshot) {
    values.push(storage.read_commands as jlong);
    values.push(storage.write_commands as jlong);
    values.push(storage.flush_commands as jlong);
    values.push(storage.bytes_read as jlong);
    values.push(storage.bytes_written as jlong);
    values.push(storage.failed_commands as jlong);
    values.push(storage.media_read_blocks as jlong);
    values.push(storage.media_write_blocks as jlong);
    values.push(storage.unique_read_blocks as jlong);
    values.push(storage.repeated_read_blocks as jlong);
    values.push(storage.partition_table_read_blocks as jlong);
    values.push(storage.boot_metadata_read_blocks as jlong);
    values.push(storage.boot_data_read_blocks as jlong);
    values.push(storage.root_metadata_read_blocks as jlong);
    values.push(storage.root_data_read_blocks as jlong);
    values.push(storage.unknown_read_blocks as jlong);
    values.push(storage.requested_read_blocks as jlong);
    values.push(storage.requested_read_bytes as jlong);
}

fn push_gpu_values(values: &mut Vec<jlong>, gpu: K16ComputerGpuStatsSnapshot) {
    values.push(gpu.submission_attempts as jlong);
    values.push(gpu.committed_submissions as jlong);
    values.push(gpu.rejected_submissions as jlong);
    values.push(gpu.submitted_bytes as jlong);
    values.push(gpu.resource_count as jlong);
    values.push(gpu.authoritative_payload_bytes as jlong);
    values.push(gpu.viewer_count as jlong);
    values.push(gpu.snapshot_payloads as jlong);
    values.push(gpu.delta_payloads as jlong);
    values.push(gpu.network_payload_bytes as jlong);
    values.push(gpu.resync_requests as jlong);
}

fn byte_array_or_throw(env: &mut JNIEnv<'_>, bytes: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(bytes) {
        Ok(array) => array.into_raw(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                format!("Cannot allocate native VM signal: {error}"),
            );
            null_mut()
        }
    }
}

fn long_array_or_throw(env: &mut JNIEnv<'_>, values: &[jlong]) -> jlongArray {
    let array = match env.new_long_array(values.len() as i32) {
        Ok(array) => array,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                format!("Cannot allocate native process scheduler tick: {error}"),
            );
            return null_mut();
        }
    };
    if let Err(error) = env.set_long_array_region(&array, 0, values) {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            format!("Cannot write native process scheduler tick: {error}"),
        );
        return null_mut();
    }
    array.into_raw()
}

#[cfg(test)]
mod tests {
    use super::k16_computer_stats_snapshot_values;
    use crate::computer::stats::{
        K16ComputerDecodeCacheStatsSnapshot, K16ComputerDeviceStats, K16ComputerGpuStatsSnapshot,
        K16ComputerOsStatsSnapshot, K16ComputerStatsSnapshot, K16ComputerStorageStatsSnapshot,
    };
    use crate::low_bus::{MachineBusStatsSnapshot, MachineBusTrafficSnapshot};

    #[test]
    fn k16_computer_stats_snapshot_values_encode_versioned_long_array() {
        let snapshot = K16ComputerStatsSnapshot {
            bus: MachineBusStatsSnapshot {
                ram: MachineBusTrafficSnapshot {
                    loads: 2,
                    stores: 3,
                    bytes_read: 4,
                    bytes_written: 5,
                },
                mmio: MachineBusTrafficSnapshot {
                    loads: 6,
                    stores: 7,
                    bytes_read: 8,
                    bytes_written: 9,
                },
                mmio_devices: Vec::new(),
            },
            os: K16ComputerOsStatsSnapshot {
                path_lookups: 23,
                inode_loads: 24,
                dir_entry_scans: 25,
                file_opens: 26,
                file_reads: 27,
                stat_calls: 28,
                process_spawns: 29,
                program_loads: 30,
                dynamic_import_loads: 31,
                library_loads: 32,
                read_dir_calls: 33,
                program_load_bytes: 34,
                dynamic_import_bytes: 35,
                library_load_bytes: 36,
                generic_file_data_read_blocks: 37,
                generic_file_data_read_bytes: 38,
                read_dir_data_read_blocks: 39,
                read_dir_data_read_bytes: 40,
                program_data_read_blocks: 41,
                program_data_read_bytes: 42,
                dynamic_import_data_read_blocks: 43,
                dynamic_import_data_read_bytes: 44,
                library_data_read_blocks: 45,
                library_data_read_bytes: 46,
                block_cache_hits: 47,
                block_cache_misses: 48,
                block_cache_batch_reads: 49,
                init_program_file_data_read_blocks: 50,
                init_program_file_data_read_bytes: 51,
                shell_program_file_data_read_blocks: 52,
                shell_program_file_data_read_bytes: 53,
                other_program_file_data_read_blocks: 54,
                other_program_file_data_read_bytes: 55,
                libkraft_library_file_data_read_blocks: 56,
                libkraft_library_file_data_read_bytes: 57,
                other_library_file_data_read_blocks: 58,
                other_library_file_data_read_bytes: 59,
                last_exited_program_heap_pages: 60,
            },
            decode_cache: K16ComputerDecodeCacheStatsSnapshot {
                entries: 61,
                hits: 62,
                misses: 63,
            },
            cpu_steps: 64,
            game_ticks: 65,
            devices: vec![K16ComputerDeviceStats {
                name: "debug",
                device_id: 11,
                base: 0x1000,
                size: 64,
                traffic: MachineBusTrafficSnapshot {
                    loads: 12,
                    stores: 13,
                    bytes_read: 14,
                    bytes_written: 15,
                },
                storage: K16ComputerStorageStatsSnapshot {
                    read_commands: 16,
                    write_commands: 17,
                    flush_commands: 18,
                    bytes_read: 19,
                    bytes_written: 20,
                    failed_commands: 21,
                    media_read_blocks: 22,
                    media_write_blocks: 23,
                    unique_read_blocks: 24,
                    repeated_read_blocks: 25,
                    partition_table_read_blocks: 26,
                    boot_metadata_read_blocks: 27,
                    boot_data_read_blocks: 28,
                    root_metadata_read_blocks: 29,
                    root_data_read_blocks: 30,
                    unknown_read_blocks: 31,
                    requested_read_blocks: 32,
                    requested_read_bytes: 33,
                },
                gpu: K16ComputerGpuStatsSnapshot {
                    submission_attempts: 34,
                    committed_submissions: 35,
                    rejected_submissions: 36,
                    submitted_bytes: 37,
                    resource_count: 38,
                    authoritative_payload_bytes: 39,
                    viewer_count: 40,
                    snapshot_payloads: 41,
                    delta_payloads: 42,
                    network_payload_bytes: 43,
                    resync_requests: 44,
                },
            }],
        };

        assert_eq!(
            k16_computer_stats_snapshot_values(&snapshot),
            vec![
                17, 2, 3, 4, 5, 6, 7, 8, 9, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36,
                37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57,
                58, 59, 60, 61, 62, 63, 64, 65, 1, 11, 0x1000, 64, 12, 13, 14, 15, 16, 17, 18, 19,
                20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
                41, 42, 43, 44,
            ],
        );
    }
}
