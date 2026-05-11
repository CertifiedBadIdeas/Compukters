use std::collections::HashMap;
use std::ptr::null_mut;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jlongArray};
use jni::JNIEnv;

use crate::device_daemon::{DeviceDaemon, DeviceDaemonHostRequest, DeviceDaemonHostRequestKind};
use crate::display::PixelFormat;
use crate::image_runner::ImageVmHandle;
use crate::low_image::decode_image as decode_low_image;
use crate::low_image_runner::{LowImageSignal, LowImageVm};
use crate::runtime_kernel::DeviceRuntimeKernelHandle;
use crate::signal::{decode_value, encode_value};
use crate::value::VmValue;

type SharedDeviceRuntimeKernel = Arc<DeviceRuntimeKernelHandle>;

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
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_imageMetricsNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlongArray {
    let handle = match image_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    let metrics = handle.metrics_snapshot();
    let mut values = vec![
        metrics.executed_instructions as jlong,
        metrics.instruction_clones as jlong,
        metrics.value_clones as jlong,
        metrics.register_reads as jlong,
        metrics.register_writes as jlong,
        metrics.function_calls as jlong,
        metrics.function_returns as jlong,
        metrics.host_call_attempts as jlong,
        metrics.native_host_calls as jlong,
        metrics.jvm_host_call_signals as jlong,
        metrics.pause_signals as jlong,
        metrics.string_allocations as jlong,
        metrics.record_allocations as jlong,
    ];
    values.extend(metrics.opcode_counts.iter().map(|count| *count as jlong));
    long_array_or_throw(&mut env, &values)
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
                format!("Cannot read CKIM v5 low image: {error}"),
            );
            return 0;
        }
    };
    let image = match decode_low_image(&image) {
        Ok(image) => image,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot decode CKIM v5 low image: {error}"),
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
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_createDeviceDaemonNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    max_event_queue_size: jint,
    max_buffered_bytes_per_channel: jint,
    instruction_budget: jint,
    device_id: jint,
    profile_name: JString<'_>,
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
    let profile_name: String = match env.get_string(&profile_name) {
        Ok(value) => value.into(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native device daemon profile name: {error}"),
            );
            return 0;
        }
    };
    let daemon = DeviceDaemon::new(
        max_event_queue_size,
        max_buffered_bytes_per_channel,
        instruction_budget,
        device_id,
        profile_name,
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
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_refillDeviceDaemonQuotaNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    instructions: jlong,
    wall_nanos: jlong,
    server_tick: jlong,
) {
    let _ = with_device_daemon_mut(&mut env, handle, |daemon| {
        daemon.refill_execution_quota(instructions, wall_nanos, server_tick);
    });
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runDeviceDaemonReadyNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    max_turns: jlong,
) -> jlongArray {
    let summary = match with_device_daemon_mut(&mut env, handle, |daemon| {
        daemon.run_ready_until_blocked(max_turns)
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
    let requests =
        match with_device_daemon_mut(&mut env, handle, |daemon| daemon.drain_host_requests()) {
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
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_completeDeviceDaemonCompileProgramNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    request_id: jlong,
    image: JByteArray<'_>,
    exit_code: jint,
) -> jboolean {
    let image = match env.convert_byte_array(&image) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native device daemon compiled image: {error}"),
            );
            return false as jboolean;
        }
    };
    let image = if image.is_empty() {
        None
    } else {
        Some(image.as_slice())
    };
    match with_device_daemon_mut(&mut env, handle, |daemon| {
        daemon.complete_compile_program(request_id, image, exit_code)
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
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_attachDeviceDaemonFilesystemNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    root_path: JString<'_>,
    quota_bytes: jlong,
) {
    let root_path: String = match env.get_string(&root_path) {
        Ok(value) => value.into(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read native daemon filesystem root path: {error}"),
            );
            return;
        }
    };
    match with_device_daemon_mut(&mut env, handle, |daemon| {
        daemon.attach_filesystem(root_path, quota_bytes)
    }) {
        Some(Ok(())) | None => {}
        Some(Err(error)) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_attachDeviceDaemonDisplayNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    display_id: jint,
    width: jint,
    height: jint,
) {
    match with_device_daemon_mut(&mut env, handle, |daemon| {
        daemon.attach_display(display_id, width, height, PixelFormat::Rgb565)
    }) {
        Some(Ok(())) | None => {}
        Some(Err(error)) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_detachDeviceDaemonDisplayNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    display_id: jint,
) {
    match with_device_daemon_mut(&mut env, handle, |daemon| daemon.detach_display(display_id)) {
        Some(Ok(())) | None => {}
        Some(Err(error)) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_drainDeviceDaemonDisplayFramesNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    let frames =
        match with_device_daemon_mut(&mut env, handle, |daemon| daemon.drain_display_frames()) {
            Some(Ok(frames)) => frames,
            Some(Err(error)) => {
                let _ = env.throw_new("java/lang/IllegalStateException", error);
                return null_mut();
            }
            None => return null_mut(),
        };
    byte_array_or_throw(&mut env, &encode_display_frames(&frames))
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_deviceDaemonDisplayWakeSequenceNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlong {
    let kernel = match shared_device_daemon_kernel_handle(&mut env, handle) {
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
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_waitForDeviceDaemonDisplayWakeNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    observed_wake_sequence: jlong,
    timeout_millis: jlong,
) -> jlong {
    let kernel = match shared_device_daemon_kernel_handle(&mut env, handle) {
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

fn shared_device_daemon_kernel_handle(
    env: &mut JNIEnv<'_>,
    handle: jlong,
) -> Option<SharedDeviceRuntimeKernel> {
    if handle == 0 {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Native device daemon handle is zero",
        );
        return None;
    }
    match device_daemon_handles().lock() {
        Ok(handles) => match handles.get(&handle) {
            Some(daemon) => Some(daemon.kernel()),
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
