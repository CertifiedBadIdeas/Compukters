use std::ptr::null_mut;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint, jlong, jlongArray};
use jni::JNIEnv;

use crate::low_image::decode_image as decode_low_image;
use crate::low_image_runner::{LowImageSignal, LowImageVm};
use crate::rux_computer::{RuxComputerHandle, RuxComputerTextDisplaySnapshot};

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_createLowImageNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    image: JByteArray<'_>,
    slice_budget_nanos: jint,
) -> jlong {
    let image = match env.convert_byte_array(&image) {
        Ok(image) => image,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read RUXI v1 low image: {error}"),
            );
            return 0;
        }
    };
    let image = match decode_low_image(&image) {
        Ok(image) => image,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot decode RUXI v1 low image: {error}"),
            );
            return 0;
        }
    };
    match LowImageVm::create(image, slice_budget_nanos.max(1) as u64) {
        Ok(vm) => Box::into_raw(Box::new(vm)) as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runLowImageUntilSignalNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlongArray {
    let handle = match low_image_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    let signal = match handle.run_until_signal() {
        Ok(signal) => signal,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            return null_mut();
        }
    };
    long_array_or_throw(&mut env, &low_image_signal_values(signal))
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_lowImageMetricsNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlongArray {
    let handle = match low_image_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    let metrics = handle.metrics_snapshot();
    let values = vec![
        metrics.run_invocations as jlong,
        metrics.elapsed_nanos as jlong,
        metrics.pause_signals as jlong,
    ];
    long_array_or_throw(&mut env, &values)
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_freeLowImageNative(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    if handle != 0 {
        unsafe { drop(Box::from_raw(handle as *mut LowImageVm)) };
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_createRuxComputerNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    image: JByteArray<'_>,
    memory_size: jint,
    slice_budget_nanos: jlong,
    storage0_media: JByteArray<'_>,
    storage0_path: JString<'_>,
) -> jlong {
    let image = match env.convert_byte_array(&image) {
        Ok(image) => image,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read Rux computer image: {error}"),
            );
            return 0;
        }
    };
    let storage0_media = if storage0_media.is_null() {
        None
    } else {
        match env.convert_byte_array(&storage0_media) {
            Ok(bytes) => Some(bytes),
            Err(error) => {
                let _ = env.throw_new(
                    "java/lang/IllegalArgumentException",
                    format!("Cannot read Rux computer storage0 media: {error}"),
                );
                return 0;
            }
        }
    };
    let storage0_path = if storage0_path.is_null() {
        None
    } else {
        match env.get_string(&storage0_path) {
            Ok(path) => Some(path.to_string_lossy().into_owned()),
            Err(error) => {
                let _ = env.throw_new(
                    "java/lang/IllegalArgumentException",
                    format!("Cannot read Rux computer storage0 path: {error}"),
                );
                return 0;
            }
        }
    };
    if storage0_media.is_some() && storage0_path.is_some() {
        let _ = env.throw_new(
            "java/lang/IllegalArgumentException",
            "storage0 media and storage0 path are mutually exclusive",
        );
        return 0;
    }
    let result = if let Some(path) = storage0_path {
        RuxComputerHandle::create_with_storage0_path(
            &image,
            memory_size.max(1) as usize,
            slice_budget_nanos.max(1) as u64,
            path,
        )
    } else if let Some(media) = storage0_media {
        RuxComputerHandle::create_with_storage0_media(
            &image,
            memory_size.max(1) as usize,
            slice_budget_nanos.max(1) as u64,
            media,
        )
    } else {
        RuxComputerHandle::create(
            &image,
            memory_size.max(1) as usize,
            slice_budget_nanos.max(1) as u64,
        )
    };
    match result {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runRuxComputerUntilSignalNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlongArray {
    let handle = match rux_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    let signal = match handle.run_until_signal() {
        Ok(signal) => signal,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            return null_mut();
        }
    };
    long_array_or_throw(&mut env, &low_image_signal_values(signal))
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_ruxComputerControlNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlongArray {
    let handle = match rux_computer_handle_mut(&mut env, handle) {
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
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_ruxComputerDebugOutputNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    let handle = match rux_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    byte_array_or_throw(&mut env, handle.debug_output_bytes())
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_drainRuxComputerDebugOutputNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    let handle = match rux_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    byte_array_or_throw(&mut env, &handle.drain_debug_output_bytes())
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_ruxComputerDisplay0SnapshotNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    let handle = match rux_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    let payload = match handle.display0_snapshot() {
        Some(snapshot) => encode_rux_computer_text_display_snapshot(&snapshot),
        None => Vec::new(),
    };
    byte_array_or_throw(&mut env, &payload)
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_ruxComputerStorage0MediaSnapshotNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    let handle = match rux_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    let payload = handle.storage0_media_snapshot().unwrap_or_default();
    byte_array_or_throw(&mut env, &payload)
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_pushRuxComputerSerialInputNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    bytes: JByteArray<'_>,
) {
    let handle = match rux_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return,
    };
    let bytes = match env.convert_byte_array(&bytes) {
        Ok(bytes) => bytes,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read Rux computer serial input: {error}"),
            );
            return;
        }
    };
    handle.push_serial_input(&bytes);
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_freeRuxComputerNative(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    if handle != 0 {
        unsafe { drop(Box::from_raw(handle as *mut RuxComputerHandle)) };
    }
}
fn low_image_handle_mut(env: &mut JNIEnv<'_>, handle: jlong) -> Option<&'static mut LowImageVm> {
    if handle == 0 {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Native low image VM handle is zero",
        );
        return None;
    }
    let pointer = handle as *mut LowImageVm;
    if pointer.is_null() {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Native low image VM handle is null",
        );
        return None;
    }
    Some(unsafe { &mut *pointer })
}

fn rux_computer_handle_mut(
    env: &mut JNIEnv<'_>,
    handle: jlong,
) -> Option<&'static mut RuxComputerHandle> {
    if handle == 0 {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Native Rux computer handle is zero",
        );
        return None;
    }
    let pointer = handle as *mut RuxComputerHandle;
    if pointer.is_null() {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Native Rux computer handle is null",
        );
        return None;
    }
    Some(unsafe { &mut *pointer })
}

fn low_image_signal_values(signal: LowImageSignal) -> [jlong; 2] {
    match signal {
        LowImageSignal::HaltUnit => [1, 0],
        LowImageSignal::HaltI32(value) => [2, value as jlong],
        LowImageSignal::HaltI64(value) => [3, value as jlong],
        LowImageSignal::HaltAddr(value) => [4, value as jlong],
        LowImageSignal::HaltBool(value) => [5, i64::from(value) as jlong],
        LowImageSignal::Pause => [6, 0],
    }
}

fn push_u32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn push_u64(out: &mut Vec<u8>, value: u64) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn encode_rux_computer_text_display_snapshot(snapshot: &RuxComputerTextDisplaySnapshot) -> Vec<u8> {
    let mut out = Vec::with_capacity(28 + snapshot.cells.len());
    push_u32(&mut out, snapshot.columns);
    push_u32(&mut out, snapshot.rows);
    push_u32(&mut out, snapshot.cursor_x);
    push_u32(&mut out, snapshot.cursor_y);
    push_u64(&mut out, snapshot.sequence);
    push_u32(&mut out, snapshot.cells.len() as u32);
    out.extend_from_slice(&snapshot.cells);
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
