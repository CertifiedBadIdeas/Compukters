use std::ptr::null_mut;

use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jint, jlong};
use jni::JNIEnv;

use crate::image_runner::ImageVmHandle;

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
