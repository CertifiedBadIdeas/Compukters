use std::ptr::null_mut;
use std::sync::{Arc, MutexGuard};
use std::time::Duration;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;

use crate::display::PixelFormat;
use crate::image_runner::ImageVmHandle;
use crate::runtime_kernel::{DeviceRuntimeKernel, DeviceRuntimeKernelHandle};
use crate::signal::decode_value;
use crate::value::VmValue;

type SharedDeviceRuntimeKernel = Arc<DeviceRuntimeKernelHandle>;

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_createImageNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    image: JByteArray<'_>,
    instruction_budget: jint,
) -> jlong {
    let image = match env.convert_byte_array(&image) {
        Ok(image) => image,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read CKIM image: {error}"),
            );
            return 0;
        }
    };

    match ImageVmHandle::create(&image, instruction_budget.max(1) as usize) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runImageUntilSignalForHandleNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    let handle = match image_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    let signal = handle.run_until_signal();
    byte_array_or_throw(&mut env, &signal)
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_resumeImageWithNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    value: JByteArray<'_>,
) {
    let handle = match image_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return,
    };
    let value = match env.convert_byte_array(&value) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native image VM resume value: {error}"),
            );
            return;
        }
    };
    if let Err(error) = handle.resume_with_value_bytes(&value) {
        let _ = env.throw_new("java/lang/IllegalStateException", error);
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_freeImageNative(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    if handle != 0 {
        unsafe { drop(Box::from_raw(handle as *mut ImageVmHandle)) };
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_createDeviceKernelNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    max_event_queue_size: jint,
    max_buffered_bytes_per_channel: jint,
) -> jlong {
    let max_event_queue_size = match usize::try_from(max_event_queue_size.max(1)) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Invalid native device runtime kernel event queue size: {error}"),
            );
            return 0;
        }
    };
    let max_buffered_bytes_per_channel =
        match usize::try_from(max_buffered_bytes_per_channel.max(1)) {
            Ok(value) => value,
            Err(error) => {
                let _ = env.throw_new(
                    "java/lang/IllegalArgumentException",
                    format!("Invalid native device runtime kernel IPC byte limit: {error}"),
                );
                return 0;
            }
        };
    Box::into_raw(Box::new(Arc::new(DeviceRuntimeKernelHandle::new(
        max_event_queue_size,
        max_buffered_bytes_per_channel,
    )))) as jlong
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_freeDeviceKernelNative(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    if handle != 0 {
        unsafe { drop(Box::from_raw(handle as *mut SharedDeviceRuntimeKernel)) };
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_enqueueDeviceEventNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    event_name: JString<'_>,
    payload: JByteArray<'_>,
) -> jboolean {
    let event_name: String = match env.get_string(&event_name) {
        Ok(name) => name.into(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native device runtime event name: {error}"),
            );
            return 0;
        }
    };
    let payload = match env.convert_byte_array(&payload) {
        Ok(payload) => payload,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native device runtime event payload: {error}"),
            );
            return 0;
        }
    };
    let arguments = match event_arguments_from_payload(&payload) {
        Ok(arguments) => arguments,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            return 0;
        }
    };
    let kernel = match shared_kernel_handle(&mut env, handle) {
        Some(kernel) => kernel,
        None => return 0,
    };
    match kernel.with_kernel_mut(|kernel| kernel.enqueue_event(&event_name, arguments)) {
        Ok(true) => 1,
        Ok(false) => 0,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_writeDeviceIpcNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    channel: jint,
    text: JString<'_>,
) -> jboolean {
    let text: String = match env.get_string(&text) {
        Ok(text) => text.into(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native IPC text: {error}"),
            );
            return 0;
        }
    };
    let kernel = match shared_kernel_handle(&mut env, handle) {
        Some(kernel) => kernel,
        None => return 0,
    };
    match kernel.with_kernel_mut(|kernel| kernel.write_ipc(channel, &text)) {
        Ok(Ok(())) => 1,
        Ok(Err(_)) => 0,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_tryReadDeviceIpcNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    channel: jint,
) -> jstring {
    let kernel_handle = match shared_kernel_handle(&mut env, handle) {
        Some(kernel) => kernel,
        None => return null_mut(),
    };
    let mut kernel = match lock_kernel_handle(&mut env, &kernel_handle) {
        Some(kernel) => kernel,
        None => return null_mut(),
    };
    match kernel.try_read_ipc(channel) {
        Ok(text) => match env.new_string(text) {
            Ok(text) => text.into_raw(),
            Err(error) => {
                let _ = env.throw_new(
                    "java/lang/IllegalArgumentException",
                    format!("Cannot return native IPC text: {error}"),
                );
                null_mut()
            }
        },
        Err(_) => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_deviceKernelWakeSequenceNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlong {
    let kernel = match shared_kernel_handle(&mut env, handle) {
        Some(kernel) => kernel,
        None => return 0,
    };
    match kernel.wake_sequence() {
        Ok(sequence) => sequence as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_waitForDeviceWakeNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    observed_wake_sequence: jlong,
    timeout_millis: jlong,
) -> jlong {
    let kernel = match shared_kernel_handle(&mut env, handle) {
        Some(kernel) => kernel,
        None => return observed_wake_sequence,
    };
    let timeout = Duration::from_millis(timeout_millis.max(0) as u64);
    match kernel.wait_for_wake(observed_wake_sequence, timeout) {
        Ok(sequence) => sequence as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            observed_wake_sequence
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_attachImageToKernelNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    image_handle: jlong,
    kernel_handle: jlong,
) {
    let handle = match image_handle_mut(&mut env, image_handle) {
        Some(handle) => handle,
        None => return,
    };
    let kernel = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return,
    };
    if let Err(error) = handle.attach_device_kernel(kernel) {
        let _ = env.throw_new("java/lang/IllegalStateException", error);
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_setImageWorkingDirectoryNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    image_handle: jlong,
    working_directory: JString<'_>,
) {
    let handle = match image_handle_mut(&mut env, image_handle) {
        Some(handle) => handle,
        None => return,
    };
    let working_directory: String = match env.get_string(&working_directory) {
        Ok(value) => value.into(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native image VM working directory: {error}"),
            );
            return;
        }
    };
    handle.set_working_directory(working_directory);
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_attachNativeFilesystemNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    root_path: JString<'_>,
    quota_bytes: jlong,
) {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return,
    };
    let mut kernel = match lock_kernel_handle(&mut env, &kernel_handle) {
        Some(kernel) => kernel,
        None => return,
    };
    let root_path: String = match env.get_string(&root_path) {
        Ok(value) => value.into(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native filesystem root path: {error}"),
            );
            return;
        }
    };
    if let Err(error) = kernel.attach_filesystem(root_path, quota_bytes) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", error);
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_attachNativeDisplayNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    display_id: jint,
    width: jint,
    height: jint,
) {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return,
    };
    let mut kernel = match lock_kernel_handle(&mut env, &kernel_handle) {
        Some(kernel) => kernel,
        None => return,
    };
    if let Err(error) = kernel
        .displays
        .attach(display_id, width, height, PixelFormat::Rgb565)
    {
        let _ = env.throw_new("java/lang/IllegalArgumentException", error);
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_detachNativeDisplayNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    display_id: jint,
) {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return,
    };
    let mut kernel = match lock_kernel_handle(&mut env, &kernel_handle) {
        Some(kernel) => kernel,
        None => return,
    };
    kernel.displays.detach(display_id);
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_nativeDisplayFillRectNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    display_id: jint,
    x: jint,
    y: jint,
    width: jint,
    height: jint,
    rgb565: jint,
) {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return,
    };
    let mut kernel = match lock_kernel_handle(&mut env, &kernel_handle) {
        Some(kernel) => kernel,
        None => return,
    };
    kernel
        .displays
        .fill_rect(display_id, x, y, width, height, rgb565 as u16);
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_nativeDisplayPresentNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    display_id: jint,
) {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return,
    };
    let mut kernel = match lock_kernel_handle(&mut env, &kernel_handle) {
        Some(kernel) => kernel,
        None => return,
    };
    kernel.displays.present(display_id);
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_drainNativeDisplayFramesNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
) -> jbyteArray {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return null_mut(),
    };
    let mut kernel = match lock_kernel_handle(&mut env, &kernel_handle) {
        Some(kernel) => kernel,
        None => return null_mut(),
    };
    let frames = kernel.displays.drain_frames();
    let bytes = encode_display_frames(&frames);
    byte_array_or_throw(&mut env, &bytes)
}

fn image_handle_mut(env: &mut JNIEnv<'_>, handle: jlong) -> Option<&'static mut ImageVmHandle> {
    if handle == 0 {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Native image VM handle is zero",
        );
        return None;
    }
    let pointer = handle as *mut ImageVmHandle;
    if pointer.is_null() {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Native image VM handle is null",
        );
        return None;
    }
    Some(unsafe { &mut *pointer })
}

fn shared_kernel_handle(env: &mut JNIEnv<'_>, handle: jlong) -> Option<SharedDeviceRuntimeKernel> {
    if handle == 0 {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Native device runtime kernel handle is zero",
        );
        return None;
    }
    let pointer = handle as *mut SharedDeviceRuntimeKernel;
    if pointer.is_null() {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Native device runtime kernel handle is null",
        );
        return None;
    }
    Some(Arc::clone(unsafe { &*pointer }))
}

fn lock_kernel_handle<'a>(
    env: &mut JNIEnv<'_>,
    kernel: &'a SharedDeviceRuntimeKernel,
) -> Option<MutexGuard<'a, DeviceRuntimeKernel>> {
    match kernel.lock() {
        Ok(guard) => Some(guard),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            None
        }
    }
}

fn event_arguments_from_payload(payload: &[u8]) -> Result<Vec<VmValue>, String> {
    if payload.is_empty() {
        return Ok(Vec::new());
    }
    match decode_value(payload)? {
        VmValue::Record { fields, .. } => Ok(fields.into_iter().map(|(_, value)| value).collect()),
        value => Ok(vec![value]),
    }
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

fn encode_display_frames(frames: &[crate::display::DisplayFrameDelta]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&(frames.len() as i32).to_le_bytes());
    for frame in frames {
        out.extend_from_slice(&frame.display_id.to_le_bytes());
        out.extend_from_slice(&frame.sequence.to_le_bytes());
        out.extend_from_slice(&frame.width.to_le_bytes());
        out.extend_from_slice(&frame.height.to_le_bytes());
        out.push(match frame.pixel_format {
            PixelFormat::Rgb565 => 0,
        });
        out.push(if frame.full_refresh { 1 } else { 0 });
        out.extend_from_slice(&(frame.tiles.len() as i32).to_le_bytes());
        for tile in &frame.tiles {
            out.extend_from_slice(&tile.tile_x.to_le_bytes());
            out.extend_from_slice(&tile.tile_y.to_le_bytes());
            out.extend_from_slice(&tile.x.to_le_bytes());
            out.extend_from_slice(&tile.y.to_le_bytes());
            out.extend_from_slice(&tile.width.to_le_bytes());
            out.extend_from_slice(&tile.height.to_le_bytes());
            out.extend_from_slice(&(tile.payload.len() as i32).to_le_bytes());
            out.extend_from_slice(&tile.payload);
        }
    }
    out
}
