use std::ptr::null_mut;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyte, jbyteArray, jint, jlong, jlongArray};
use jni::JNIEnv;

use crate::computer::stats::K16ComputerStorageStatsSnapshot;
use crate::display::{DisplayFrameDelta, PixelFormat};
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
    match K16ComputerHandle::create_k16_bios_flash_path_with_storage0_path(
        bios_flash_path,
        memory_size.max(1) as usize,
        max_steps.max(1) as u64,
        storage0_path,
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
    match K16ComputerHandle::restore_k16_bios_flash_snapshot_path_with_storage0_path(
        bios_flash_path,
        memory_size.max(1) as usize,
        storage0_path,
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
    handle.advance_game_tick();
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
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_drainK16ComputerGpu0FramesNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    let handle = match k16_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    let frames = handle.drain_gpu0_frames();
    byte_array_or_throw(&mut env, &encode_display_frame_deltas(&frames))
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
    let mut values = Vec::with_capacity(10 + snapshot.devices.len() * 13);
    values.push(2);
    push_traffic_values(&mut values, snapshot.bus.ram);
    push_traffic_values(&mut values, snapshot.bus.mmio);
    values.push(snapshot.devices.len() as jlong);
    for device in &snapshot.devices {
        values.push(device.device_id as jlong);
        values.push(i64::from(device.base));
        values.push(i64::from(device.size));
        push_traffic_values(&mut values, device.traffic);
        push_storage_values(&mut values, device.storage);
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
}

fn push_i64(out: &mut Vec<u8>, value: i64) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn push_i32(out: &mut Vec<u8>, value: i32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn encode_display_frame_deltas(frames: &[DisplayFrameDelta]) -> Vec<u8> {
    let mut out = Vec::new();
    push_i32(&mut out, frames.len() as i32);
    for frame in frames {
        push_i32(&mut out, frame.display_id);
        push_i64(&mut out, frame.sequence);
        push_i32(&mut out, frame.width);
        push_i32(&mut out, frame.height);
        out.push(match frame.pixel_format {
            PixelFormat::Rgb565 => 0,
        });
        out.push(u8::from(frame.full_refresh));
        push_i32(&mut out, frame.tiles.len() as i32);
        for tile in &frame.tiles {
            push_i32(&mut out, tile.tile_x);
            push_i32(&mut out, tile.tile_y);
            push_i32(&mut out, tile.x);
            push_i32(&mut out, tile.y);
            push_i32(&mut out, tile.width);
            push_i32(&mut out, tile.height);
            push_i32(&mut out, tile.payload.len() as i32);
            out.extend_from_slice(&tile.payload);
        }
    }
    out
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
        K16ComputerDeviceStats, K16ComputerStatsSnapshot, K16ComputerStorageStatsSnapshot,
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
                storage: K16ComputerStorageStatsSnapshot::default(),
            }],
        };

        assert_eq!(
            k16_computer_stats_snapshot_values(&snapshot),
            vec![2, 2, 3, 4, 5, 6, 7, 8, 9, 1, 11, 0x1000, 64, 12, 13, 14, 15, 0, 0, 0, 0, 0, 0,],
        );
    }
}
