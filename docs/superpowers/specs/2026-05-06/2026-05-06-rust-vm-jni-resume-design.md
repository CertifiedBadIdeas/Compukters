# Rust VM JNI Resume Design

## Context

The existing Rust VM JNI bridge is an opt-in prototype. It proves that Kotlin can load the local Rust `cdylib`, encode CKL bytecode, call native code, and decode one returned VM signal. It intentionally creates a fresh Rust VM for each call and stops at the first signal.

That design matches the original JNI opt-in spec, but it is not enough for `runClientRust` or `runServerRust`. Real bundled ROM programs call host-bound builtins such as `display::primary()`, then expect the VM to continue with the host call result on the stack. The current one-shot bridge discards the Rust `VmInstance` after returning `HostCall`, so Kotlin has no way to resume execution.

## Goal

Add a second JNI phase that can keep one Rust VM instance alive across signal boundaries and resume it after Kotlin handles host calls and scheduler signals.

The Kotlin VM remains the default. Rust execution remains explicit via:

- `-Dckl.vm.runner=rust`
- `-Dckl.vm.native.library=/absolute/path/to/libckl_vm.so`

This phase should let Rust VM execution progress past the first ROM host call. It does not require full CKL opcode coverage in the same change; later unsupported instructions may still fail clearly.

## Non-Goals

- Moving filesystem, display, IPC, event, process, or monitor implementations into Rust.
- Silent fallback to the Kotlin VM after selecting Rust.
- Full object/record/list/map value transport unless a tested ROM path requires it.
- A stable public native ABI. The JNI API remains internal to this project.
- Native-side scheduling, threads, or coroutine integration.

## Native Lifecycle

Replace the runner-only JNI surface with an internal handle lifecycle:

1. `create(bytecode: ByteArray, instructionBudget: Int): Long`
   - Decodes the bytecode once.
   - Creates `VmInstance` once.
   - Returns an opaque non-zero native handle.
2. `runUntilSignal(handle: Long): ByteArray`
   - Continues the stored VM until it emits the next signal.
   - Returns the existing signal byte format.
3. `resumeWith(handle: Long, value: ByteArray): Unit`
   - Decodes one native value from Kotlin.
   - Pushes or delivers that value as the result of the suspended host call.
4. `free(handle: Long): Unit`
   - Drops the boxed native VM instance.
   - Must be safe to call exactly once from Kotlin `finally`.

Handles are raw pointers cast to `jlong`. A zero handle is invalid. Each JNI function validates the handle before dereferencing it and throws `IllegalStateException` on invalid input. Panics are caught and converted to the same encoded error signal where the method returns a signal; methods that cannot return a signal throw `IllegalStateException`.

## Rust VM State Model

`VmInstance::run_until_signal()` already keeps frames, locals, stack, and instruction pointers inside the instance. The missing piece is a suspension state.

Introduce a small state machine:

- `Ready`: normal execution can continue.
- `WaitingForResume`: the VM stopped at a builtin call that requires one resume value.
- `Halted`: execution already returned a final value.

When `Instruction::CallBuiltin` emits a signal that represents a builtin function result (`HostCall`, `Yield`, `Sleep`, and later `WaitEvent`), the VM enters `WaitingForResume`. `resume_with(value)` is valid only in that state. It pushes the value onto the current frame stack and returns to `Ready`. Calling `run_until_signal()` while waiting for resume should fail clearly.

`Pause` is different: it is a scheduler budget signal, not a builtin-call result. It leaves the VM `Ready`, and Kotlin should call `runUntilSignal(handle)` again after yielding to the runtime. `Yield` and `Sleep` should match `KotlinVmRunner`: Kotlin performs the runtime action and then resumes native execution with `Unit`.

## Value Codec

Reuse the existing value tags for both signal payloads and resume payloads:

- `0`: Unit
- `1`: Null
- `2`: Bool
- `3`: Int
- `4`: Long
- `5`: String

This is enough for the first ROM host call (`display::primary()` returns `Int`) and many display/IPC/string operations. Kotlin converts between internal native values and runtime `VmValue` for those primitive cases.

Unsupported values must fail explicitly. If a host call returns `RecordValue`, `ArrayValue`, `ListValue`, `MapValue`, or object/class values before the codec supports them, `NativeVmRunner` should throw an `UnsupportedOperationException` naming the value kind and host function. This is preferable to corrupting VM state.

Event records are expected soon because `events::tryPull` returns an `Event` record. That should be a follow-up task once primitive host-call resume is green, not hidden inside the first persistent-handle change.

## Kotlin Runner Loop

`NativeVmRunner` should mirror the high-level shape of `KotlinVmRunner`:

1. Encode the module once with `BytecodeAbi`.
2. Create a native handle in a `try` block.
3. Loop until `Halt`.
4. On `HostCall`, convert arguments to `VmValue`, call `RuntimeHostBridge.invoke(...)`, encode the returned primitive value, and call `resumeWith`.
5. On `Pause`, yield to the runtime scheduler and continue without `resumeWith`.
6. On `Yield`, call `runtime.yield()`, then `resumeWith(Unit)`.
7. On `Sleep`, call `runtime.sleep(ticks)`, then `resumeWith(Unit)`.
8. On `Error`, throw `IllegalStateException` with the native message.
9. Always free the handle in `finally`.

The first implementation can keep the old one-shot binding only for tests if useful, but production `NativeVmRunner` should use the handle lifecycle so that `runClientRust` exercises the real resume path.

## Error Handling

- Invalid bytecode during `create` throws `IllegalArgumentException` or returns an error through a creation wrapper that Kotlin turns into `IllegalStateException`.
- Native runtime panics during `runUntilSignal` return encoded `Error` signals.
- Native runtime panics during `resumeWith` throw `IllegalStateException`.
- Resuming when the VM is not waiting for a host-call result throws `IllegalStateException`.
- Running a halted VM throws `IllegalStateException`.
- Unsupported values and unsupported opcodes remain explicit errors.

## Testing Strategy

Use test-driven development for each layer:

1. Rust unit test: a program emits a host call, `resume_with(Int(7))`, then returns `8` after adding `1`.
2. Rust lifecycle test: running while waiting for resume fails; resuming before a host call fails; running after halt fails.
3. Rust JNI-adjacent test: create/run/resume/free through pure Rust lifecycle helpers without JVM objects.
4. Kotlin value codec test: primitive `VmValue` values encode/decode consistently with the native tags.
5. Kotlin runner test: a native runner can execute a bytecode module that calls a host builtin returning `Int` and then halts.
6. Optional JNI smoke test: if `ckl.vm.native.library` is configured, run the host-call resume path against the real `.so`.

Verification commands:

- `cargo test` in `native/ckl-vm`
- `./gradlew :modules:compiler:test --tests '*NativeVm*' -Dckl.vm.native.library=native/ckl-vm/target/debug/libckl_vm.so`
- `./gradlew buildRustVmNativeLibrary`
- `./gradlew runClientRust --dry-run`

## Rollout

This remains a development-only, explicit opt-in path. The default Kotlin runner is unchanged. The expected milestone after this design is not full ROM boot parity; it is that `runClientRust` no longer fails at the first `display::primary()` host call and instead progresses to the next missing VM feature with a clear error.