use compukter_vm::{HostFailure, HostFailureKind};
use jni::{
    errors::ThrowRuntimeExAndDefault,
    jni_str,
    objects::{JByteArray, JCharArray, JClass},
    sys::{jint, jlong},
    Env, EnvUnowned,
};

use crate::{
    bridge::{self, BridgeError, CreateError, OwnedResponse},
    handle_table::HandleError,
    wire,
};

const MAXIMUM_INBOUND_UTF16_CODE_UNITS: usize = 4096;

#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_lazyhat_compukters_lang_runtime_vm_NativeBridge_nativeCreate<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    artifact: JByteArray<'local>,
) -> JByteArray<'local> {
    unowned_env
        .with_env(|env| {
            let outcome =
                if artifact.len(env)? > compukter_vm::ArtifactLimits::default().artifact_bytes {
                    Err(CreateError::Verification)
                } else {
                    bridge::create(env.convert_byte_array(&artifact)?)
                };
            env.byte_array_from_slice(&wire::encode_create(outcome))
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_lazyhat_compukters_lang_runtime_vm_NativeBridge_nativeAdvance<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    guest_budget: jint,
    maintenance_budget: jint,
) -> JByteArray<'local> {
    unowned_env
        .with_env(|env| {
            let Some(guest_budget) = u32::try_from(guest_budget).ok() else {
                return throw_argument(env, "guest budget must be non-negative");
            };
            let Some(maintenance_budget) = u32::try_from(maintenance_budget).ok() else {
                return throw_argument(env, "maintenance budget must be non-negative");
            };
            match bridge::advance(handle as u64, guest_budget, maintenance_budget) {
                Ok(outcome) => env.byte_array_from_slice(&wire::encode_outcome(outcome)),
                Err(error) => throw_bridge(env, error),
            }
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_lazyhat_compukters_lang_runtime_vm_NativeBridge_nativeResumeUnit<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    request_id: jlong,
) {
    resume(
        &mut unowned_env,
        handle,
        request_id,
        OwnedResponse::SuccessUnit,
    );
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_lazyhat_compukters_lang_runtime_vm_NativeBridge_nativeResumeString<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    request_id: jlong,
    value: JCharArray<'local>,
) {
    let outcome = unowned_env.with_env(|env| {
        let length = value.len(env)?;
        if length > MAXIMUM_INBOUND_UTF16_CODE_UNITS {
            return throw_argument_void(env, "host string response exceeds its fixed limit");
        }
        let mut units = vec![0_u16; length];
        value.get_region(env, 0, &mut units)?;
        resume_with_env(
            env,
            handle,
            request_id,
            &OwnedResponse::SuccessString(units),
        )
    });
    outcome.resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_lazyhat_compukters_lang_runtime_vm_NativeBridge_nativeResumeFailure<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    request_id: jlong,
    kind: jint,
    code: jlong,
) {
    let outcome = unowned_env.with_env(|env| {
        let Some(kind) = failure_kind(kind) else {
            return throw_argument_void(env, "invalid host failure kind");
        };
        let Some(code) = u32::try_from(code).ok() else {
            return throw_argument_void(env, "host failure code is outside u32");
        };
        resume_with_env(
            env,
            handle,
            request_id,
            &OwnedResponse::Failure(HostFailure::new(kind, code)),
        )
    });
    outcome.resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ru_lazyhat_compukters_lang_runtime_vm_NativeBridge_nativeClose<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    unowned_env
        .with_env(|env| match bridge::close(handle as u64) {
            Ok(()) => Ok(()),
            Err(error) => throw_bridge_void(env, error),
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn resume(
    unowned_env: &mut EnvUnowned<'_>,
    handle: jlong,
    request_id: jlong,
    response: OwnedResponse,
) {
    unowned_env
        .with_env(|env| resume_with_env(env, handle, request_id, &response))
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn resume_with_env(
    env: &mut Env<'_>,
    handle: jlong,
    request_id: jlong,
    response: &OwnedResponse,
) -> jni::errors::Result<()> {
    match bridge::resume(handle as u64, request_id as u64, response) {
        Ok(()) => Ok(()),
        Err(error) => throw_bridge_void(env, error),
    }
}

fn throw_bridge<'local>(
    env: &mut Env<'local>,
    error: BridgeError,
) -> jni::errors::Result<JByteArray<'local>> {
    throw_bridge_void(env, error)?;
    Ok(JByteArray::null())
}

fn throw_bridge_void(env: &mut Env<'_>, error: BridgeError) -> jni::errors::Result<()> {
    match error {
        BridgeError::Handle(HandleError::Poisoned) => env.throw_new(
            jni_str!("java/lang/RuntimeException"),
            jni_str!("Compukter VM handle table is poisoned"),
        ),
        BridgeError::Handle(_) => env.throw_new(
            jni_str!("java/lang/IllegalStateException"),
            jni_str!("invalid, stale, or busy Compukter VM handle"),
        ),
        BridgeError::Run(_) | BridgeError::Resume(_) | BridgeError::InvalidRequestId => env
            .throw_new(
                jni_str!("java/lang/IllegalStateException"),
                jni_str!("invalid Compukter VM session operation"),
            ),
    }
}

fn throw_argument<'local>(
    env: &mut Env<'local>,
    message: &str,
) -> jni::errors::Result<JByteArray<'local>> {
    throw_argument_void(env, message)?;
    Ok(JByteArray::null())
}

fn throw_argument_void(env: &mut Env<'_>, _message: &str) -> jni::errors::Result<()> {
    env.throw_new(
        jni_str!("java/lang/IllegalArgumentException"),
        jni_str!("invalid Compukter VM native argument"),
    )
}

fn failure_kind(value: jint) -> Option<HostFailureKind> {
    match value {
        0 => Some(HostFailureKind::EndOfFile),
        1 => Some(HostFailureKind::Unavailable),
        2 => Some(HostFailureKind::InputOutput),
        3 => Some(HostFailureKind::Cancelled),
        4 => Some(HostFailureKind::Other),
        _ => None,
    }
}
