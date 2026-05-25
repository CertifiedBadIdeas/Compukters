use std::ptr::null_mut;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint, jlong, jlongArray};
use jni::JNIEnv;

use crate::rux16::Rux16Signal;
use crate::rux_computer::{RuxComputerHandle, RuxComputerTextDisplaySnapshot};

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_createRuxComputerFromBiosFlashNative(
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
                format!("Cannot read Rux16 BIOS flash path: {error}"),
            );
            return 0;
        }
    };
    let storage0_path = match env.get_string(&storage0_path) {
        Ok(path) => path.to_string_lossy().into_owned(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read Rux computer storage0 path: {error}"),
            );
            return 0;
        }
    };
    match RuxComputerHandle::create_rux16_bios_flash_path_with_storage0_path(
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
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runRux16ComputerUntilSignalNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlongArray {
    let handle = match rux_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    let signal = match handle.run_rux16_until_signal() {
        Ok(signal) => signal,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            return null_mut();
        }
    };
    long_array_or_throw(&mut env, &rux16_signal_values(signal))
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

fn rux16_signal_values(signal: Rux16Signal) -> [jlong; 2] {
    match signal {
        Rux16Signal::Halt => [1, 0],
        Rux16Signal::StepLimitExceeded => [6, 0],
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
