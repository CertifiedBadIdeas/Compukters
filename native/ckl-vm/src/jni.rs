use std::collections::HashMap;
use std::ptr::null_mut;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};
use std::time::Duration;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jlongArray, jstring};
use jni::JNIEnv;

use crate::device_daemon::{DeviceDaemon, DeviceDaemonHostRequest, DeviceDaemonHostRequestKind};
use crate::display::PixelFormat;
use crate::image_runner::ImageVmHandle;
use crate::runtime_kernel::{DeviceRuntimeKernel, DeviceRuntimeKernelHandle};
use crate::signal::{decode_value, encode_value};
use crate::value::VmValue;

type SharedDeviceRuntimeKernel = Arc<DeviceRuntimeKernelHandle>;

static NEXT_DEVICE_KERNEL_HANDLE: AtomicI64 = AtomicI64::new(1);
static DEVICE_KERNEL_HANDLES: OnceLock<Mutex<HashMap<jlong, SharedDeviceRuntimeKernel>>> =
    OnceLock::new();
static NEXT_DEVICE_DAEMON_HANDLE: AtomicI64 = AtomicI64::new(1);
static DEVICE_DAEMON_HANDLES: OnceLock<Mutex<HashMap<jlong, DeviceDaemon>>> = OnceLock::new();

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
    let kernel = Arc::new(DeviceRuntimeKernelHandle::new(
        max_event_queue_size,
        max_buffered_bytes_per_channel,
    ));
    match register_device_kernel_handle(kernel) {
        Ok(handle) => handle,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_freeDeviceKernelNative(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    if handle != 0 {
        let _ = unregister_device_kernel_handle(handle);
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_createDeviceDaemonNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    max_event_queue_size: jint,
    max_buffered_bytes_per_channel: jint,
    instruction_budget: jint,
) -> jlong {
    let max_event_queue_size = match usize::try_from(max_event_queue_size.max(1)) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Invalid native device daemon event queue size: {error}"),
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
                    format!("Invalid native device daemon IPC buffer size: {error}"),
                );
                return 0;
            }
        };
    let instruction_budget = match usize::try_from(instruction_budget.max(1)) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Invalid native device daemon instruction budget: {error}"),
            );
            return 0;
        }
    };
    let daemon = DeviceDaemon::new(
        max_event_queue_size,
        max_buffered_bytes_per_channel,
        instruction_budget,
    );
    match register_device_daemon_handle(daemon) {
        Ok(handle) => handle,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_freeDeviceDaemonNative(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    if handle != 0 {
        let _ = unregister_device_daemon_handle(handle);
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_tickDeviceDaemonNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    instructions: jlong,
    wall_nanos: jlong,
    server_tick: jlong,
) -> jlongArray {
    let summary = match with_device_daemon_mut(&mut env, handle, |daemon| {
        daemon.tick(instructions, wall_nanos, server_tick)
    }) {
        Some(summary) => summary,
        None => return null_mut(),
    };
    long_array_or_throw(
        &mut env,
        &[
            summary.server_tick,
            summary.turns,
            summary.remaining_instructions,
            i64::from(summary.idle),
            summary.halted,
            summary.host_requests,
        ],
    )
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_bootDeviceDaemonNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    image: JByteArray<'_>,
    program_path: JString<'_>,
    argument: JString<'_>,
    working_directory: JString<'_>,
) -> jlongArray {
    let image = match env.convert_byte_array(&image) {
        Ok(image) => image,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native device daemon boot image: {error}"),
            );
            return null_mut();
        }
    };
    let program_path: String = match env.get_string(&program_path) {
        Ok(value) => value.into(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native device daemon program path: {error}"),
            );
            return null_mut();
        }
    };
    let argument: String = match env.get_string(&argument) {
        Ok(value) => value.into(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native device daemon argument: {error}"),
            );
            return null_mut();
        }
    };
    let working_directory: String = match env.get_string(&working_directory) {
        Ok(value) => value.into(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native device daemon working directory: {error}"),
            );
            return null_mut();
        }
    };
    let summary = match with_device_daemon_mut(&mut env, handle, |daemon| {
        daemon.boot_image(&image, &program_path, &argument, &working_directory)
    }) {
        Some(summary) => summary,
        None => return null_mut(),
    };
    long_array_or_throw(
        &mut env,
        &[summary.pid as i64, i64::from(summary.image_attached)],
    )
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_drainDeviceDaemonHostRequestsNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    let requests = match with_device_daemon_mut(&mut env, handle, |daemon| {
        daemon.drain_host_requests()
    }) {
        Some(requests) => requests,
        None => return null_mut(),
    };
    byte_array_or_throw(&mut env, &encode_device_daemon_host_requests(&requests))
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_completeDeviceDaemonHostRequestNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    request_id: jlong,
    value: JByteArray<'_>,
) -> jboolean {
    let value = match env.convert_byte_array(&value) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native device daemon host request value: {error}"),
            );
            return false as jboolean;
        }
    };
    let value = match decode_value(&value) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            return false as jboolean;
        }
    };
    match with_device_daemon_mut(&mut env, handle, |daemon| {
        daemon.complete_host_request(request_id, value)
    }) {
        Some(Ok(())) => true as jboolean,
        Some(Err(error)) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            false as jboolean
        }
        None => false as jboolean,
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_enqueueDeviceDaemonEventNative(
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
                format!("Cannot read native device daemon event name: {error}"),
            );
            return 0;
        }
    };
    let payload = match env.convert_byte_array(&payload) {
        Ok(payload) => payload,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native device daemon event payload: {error}"),
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
    match with_device_daemon_mut(&mut env, handle, |daemon| {
        daemon.enqueue_event(&event_name, arguments)
    }) {
        Some(true) => 1,
        Some(false) | None => 0,
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
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_displayWakeSequenceNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlong {
    let kernel = match shared_kernel_handle(&mut env, handle) {
        Some(kernel) => kernel,
        None => return 0,
    };
    match kernel.display_wake_sequence() {
        Ok(sequence) => sequence as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_waitForDisplayWakeNative(
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
    match kernel.wait_for_display_wake(observed_wake_sequence, timeout) {
        Ok(sequence) => sequence as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            observed_wake_sequence
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_waitForProcessWakeNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    _pid: jint,
    observed_wake_sequence: jlong,
    timeout_millis: jlong,
) -> jlong {
    let kernel = match shared_kernel_handle(&mut env, handle) {
        Some(kernel) => kernel,
        None => return observed_wake_sequence,
    };
    let timeout = Duration::from_millis(timeout_millis.max(0) as u64);
    match kernel.wait_for_process_wake(0, observed_wake_sequence, timeout) {
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
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_registerProcessNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    pid: jint,
    parent_pid: jint,
    program_path: JString<'_>,
) -> jboolean {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return false as jboolean,
    };
    let program_path: String = match env.get_string(&program_path) {
        Ok(value) => value.into(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native process program path: {error}"),
            );
            return false as jboolean;
        }
    };
    match kernel_handle.with_kernel_mut(|kernel| kernel.register_process(pid, parent_pid, program_path)) {
        Ok(registered) => registered as jboolean,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            false as jboolean
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_attachProcessImageNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    pid: jint,
    image_handle: jlong,
) -> jboolean {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return false as jboolean,
    };
    match kernel_handle.with_kernel_mut(|kernel| kernel.attach_process_image(pid, image_handle)) {
        Ok(attached) => attached as jboolean,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            false as jboolean
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_completeProcessNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    pid: jint,
    exit_code: jint,
) -> jboolean {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return false as jboolean,
    };
    match kernel_handle.with_kernel_mut(|kernel| kernel.complete_process(pid, exit_code)) {
        Ok(completed) => completed as jboolean,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            false as jboolean
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_markProcessRunnableNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    pid: jint,
) -> jboolean {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return false as jboolean,
    };
    match kernel_handle.with_kernel_mut(|kernel| kernel.mark_process_runnable(pid)) {
        Ok(updated) => updated as jboolean,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            false as jboolean
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_markProcessWaitingForProcessNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    pid: jint,
    target_pid: jint,
) -> jboolean {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return false as jboolean,
    };
    match kernel_handle
        .with_kernel_mut(|kernel| kernel.mark_process_waiting_for_process(pid, target_pid))
    {
        Ok(updated) => updated as jboolean,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            false as jboolean
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_markProcessWaitingForEventNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    pid: jint,
    filter: JString<'_>,
) -> jboolean {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return false as jboolean,
    };
    let filter = if filter.is_null() {
        None
    } else {
        match env.get_string(&filter) {
            Ok(value) => Some(String::from(value)),
            Err(error) => {
                let _ = env.throw_new(
                    "java/lang/IllegalArgumentException",
                    format!("Cannot read native process event filter: {error}"),
                );
                return false as jboolean;
            }
        }
    };
    match kernel_handle.with_kernel_mut(|kernel| kernel.mark_process_waiting_for_event(pid, filter))
    {
        Ok(updated) => updated as jboolean,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            false as jboolean
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_markProcessWaitingForIpcNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    pid: jint,
    channel_id: jint,
) -> jboolean {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return false as jboolean,
    };
    match kernel_handle.with_kernel_mut(|kernel| kernel.mark_process_waiting_for_ipc(pid, channel_id)) {
        Ok(updated) => updated as jboolean,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            false as jboolean
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_markProcessSleepingNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    pid: jint,
    until_tick: jlong,
) -> jboolean {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return false as jboolean,
    };
    match kernel_handle.with_kernel_mut(|kernel| kernel.mark_process_sleeping(pid, until_tick)) {
        Ok(updated) => updated as jboolean,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            false as jboolean
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_markProcessCrashedNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    pid: jint,
    message: JString<'_>,
) -> jboolean {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return false as jboolean,
    };
    let message: String = match env.get_string(&message) {
        Ok(value) => value.into(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native process crash message: {error}"),
            );
            return false as jboolean;
        }
    };
    match kernel_handle.with_kernel_mut(|kernel| kernel.mark_process_crashed(pid, message)) {
        Ok(updated) => updated as jboolean,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            false as jboolean
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_addDeviceExecutionQuotaNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    instructions: jlong,
    wall_nanos: jlong,
    server_tick: jlong,
) -> jlongArray {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return long_array_or_throw(&mut env, &[]),
    };
    match kernel_handle.with_kernel_mut(|kernel| {
        kernel.add_execution_quota(instructions, wall_nanos, server_tick)
    }) {
        Ok(snapshot) => long_array_or_throw(
            &mut env,
            &[
                snapshot.instructions,
                snapshot.wall_nanos,
                snapshot.server_tick,
            ],
        ),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            long_array_or_throw(&mut env, &[])
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runDeviceSchedulerDryRunNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    max_turns: jint,
) -> jlongArray {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return long_array_or_throw(&mut env, &[]),
    };
    match kernel_handle.with_kernel_mut(|kernel| kernel.run_scheduler_dry_run(max_turns as i64)) {
        Ok(result) => {
            let mut values = Vec::with_capacity(4 + result.selected_pids.len());
            values.push(result.server_tick);
            values.push(result.turns);
            values.push(result.remaining_instructions);
            values.push(result.selected_pids.len() as jlong);
            values.extend(result.selected_pids.into_iter().map(|pid| pid as jlong));
            long_array_or_throw(&mut env, &values)
        }
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            long_array_or_throw(&mut env, &[])
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runDeviceSchedulerStepNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
) -> jlongArray {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return long_array_or_throw(&mut env, &[]),
    };
    match kernel_handle.with_kernel_mut(|kernel| kernel.run_scheduler_step()) {
        Ok(result) => {
            let mut values = Vec::with_capacity(6 + result.woken_pids.len());
            values.push(result.server_tick);
            values.push(result.selected_pid.unwrap_or(0) as jlong);
            values.push(result.selected_image_handle.unwrap_or(0) as jlong);
            values.push(result.remaining_instructions);
            values.push(if result.quota_exhausted { 1 } else { 0 });
            values.push(result.woken_pids.len() as jlong);
            values.extend(result.woken_pids.into_iter().map(|pid| pid as jlong));
            long_array_or_throw(&mut env, &values)
        }
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            long_array_or_throw(&mut env, &[])
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_processSchedulerTickNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    current_tick: jlong,
) -> jlongArray {
    let kernel_handle = match shared_kernel_handle(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return long_array_or_throw(&mut env, &[]),
    };
    match kernel_handle.with_kernel_mut(|kernel| kernel.scheduler_tick(current_tick)) {
        Ok(tick) => {
            let mut values = Vec::with_capacity(3 + tick.woken_pids.len());
            values.push(tick.current_tick);
            values.push(tick.selected_pid.unwrap_or(0) as jlong);
            values.push(tick.woken_pids.len() as jlong);
            values.extend(tick.woken_pids.into_iter().map(|pid| pid as jlong));
            long_array_or_throw(&mut env, &values)
        }
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            long_array_or_throw(&mut env, &[])
        }
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
    if let Err(error) = kernel_handle.attach_display(display_id, width, height, PixelFormat::Rgb565)
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
    if let Err(error) = kernel_handle.detach_display(display_id) {
        let _ = env.throw_new("java/lang/IllegalStateException", error);
    }
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
    if let Err(error) = kernel_handle.present_display(display_id) {
        let _ = env.throw_new("java/lang/IllegalStateException", error);
    }
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
    match kernel_handle.drain_display_frames() {
        Ok(frames) => {
            let bytes = encode_display_frames(&frames);
            byte_array_or_throw(&mut env, &bytes)
        }
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            null_mut()
        }
    }
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

fn register_device_kernel_handle(kernel: SharedDeviceRuntimeKernel) -> Result<jlong, String> {
    let handle = NEXT_DEVICE_KERNEL_HANDLE.fetch_add(1, Ordering::Relaxed);
    if handle <= 0 {
        return Err(format!("Invalid native device runtime kernel handle id: {handle}"));
    }
    let mut handles = device_kernel_handles()
        .lock()
        .map_err(|error| format!("Native device runtime kernel registry lock failed: {error}"))?;
    handles.insert(handle, kernel);
    Ok(handle)
}

fn unregister_device_kernel_handle(handle: jlong) -> Result<(), String> {
    let mut handles = device_kernel_handles()
        .lock()
        .map_err(|error| format!("Native device runtime kernel registry lock failed: {error}"))?;
    handles.remove(&handle);
    Ok(())
}

fn device_kernel_handles() -> &'static Mutex<HashMap<jlong, SharedDeviceRuntimeKernel>> {
    DEVICE_KERNEL_HANDLES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn register_device_daemon_handle(daemon: DeviceDaemon) -> Result<jlong, String> {
    let handle = NEXT_DEVICE_DAEMON_HANDLE.fetch_add(1, Ordering::Relaxed);
    if handle <= 0 {
        return Err(format!("Invalid native device daemon handle id: {handle}"));
    }
    let mut handles = device_daemon_handles()
        .lock()
        .map_err(|error| format!("Native device daemon registry lock failed: {error}"))?;
    handles.insert(handle, daemon);
    Ok(handle)
}

fn unregister_device_daemon_handle(handle: jlong) -> Result<(), String> {
    let mut handles = device_daemon_handles()
        .lock()
        .map_err(|error| format!("Native device daemon registry lock failed: {error}"))?;
    handles.remove(&handle);
    Ok(())
}

fn device_daemon_handles() -> &'static Mutex<HashMap<jlong, DeviceDaemon>> {
    DEVICE_DAEMON_HANDLES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn with_device_daemon_mut<T>(
    env: &mut JNIEnv<'_>,
    handle: jlong,
    action: impl FnOnce(&mut DeviceDaemon) -> T,
) -> Option<T> {
    if handle == 0 {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Native device daemon handle is zero",
        );
        return None;
    }
    match device_daemon_handles().lock() {
        Ok(mut handles) => match handles.get_mut(&handle) {
            Some(daemon) => Some(action(daemon)),
            None => {
                let _ = env.throw_new(
                    "java/lang/IllegalStateException",
                    format!("Native device daemon handle not found: {handle}"),
                );
                None
            }
        },
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                format!("Native device daemon registry lock failed: {error}"),
            );
            None
        }
    }
}

fn shared_kernel_handle(env: &mut JNIEnv<'_>, handle: jlong) -> Option<SharedDeviceRuntimeKernel> {
    if handle == 0 {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Native device runtime kernel handle is zero",
        );
        return None;
    }
    match device_kernel_handles().lock() {
        Ok(handles) => handles.get(&handle).cloned(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                format!("Native device runtime kernel registry lock failed: {error}"),
            );
            None
        }
    }
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

fn encode_device_daemon_host_requests(requests: &[DeviceDaemonHostRequest]) -> Vec<u8> {
    let mut out = Vec::new();
    push_i32(&mut out, requests.len() as i32);
    for request in requests {
        push_i64(&mut out, request.request_id);
        push_i32(&mut out, request.pid);
        out.push(match request.kind {
            DeviceDaemonHostRequestKind::HostCall => 0,
            DeviceDaemonHostRequestKind::CompileProgram => 1,
            DeviceDaemonHostRequestKind::Crash => 2,
        });
        push_string(&mut out, request.module_name.as_deref().unwrap_or(""));
        push_string(&mut out, request.function_name.as_deref().unwrap_or(""));
        push_i32(&mut out, request.arguments.len() as i32);
        for argument in &request.arguments {
            out.extend_from_slice(&encode_value(argument));
        }
        push_string(&mut out, request.path.as_deref().unwrap_or(""));
        push_string(&mut out, request.working_directory.as_deref().unwrap_or(""));
    }
    out
}

fn push_i32(out: &mut Vec<u8>, value: i32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn push_i64(out: &mut Vec<u8>, value: i64) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn push_string(out: &mut Vec<u8>, value: &str) {
    push_i32(out, value.len() as i32);
    out.extend_from_slice(value.as_bytes());
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
