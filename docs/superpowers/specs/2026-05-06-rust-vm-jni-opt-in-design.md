# Rust VM JNI Opt-In Runner Design

## Purpose

Add a real JNI bridge for the Rust CKL VM prototype while keeping Kotlin VM as the default execution path. This step proves the JVM can load the native library, pass CKL bytecode ABI bytes into Rust, execute the current pure VM subset, and receive a compact result signal.

This design intentionally does not claim full ROM/runtime compatibility. The current Rust VM supports a small pure subset and host-call signal emission. Runtime workloads should move to Rust only after `resumeWith`, broader instruction coverage, and selected local builtins are implemented.

## Selected Approach

Use a safe opt-in runner:

- Default `BytecodeComputerProgram` continues to use `KotlinVmRunner`.
- Rust execution is enabled only when both are set:
  - `-Dckl.vm.runner=rust`
  - `-Dckl.vm.native.library=/absolute/path/to/libckl_vm.so`
- No automatic fallback is used while Rust mode is explicitly selected. Unsupported instructions or unsupported signals should fail loudly.

## Native Boundary

The first JNI bridge should be coarse and simple:

1. Kotlin serializes `BytecodeModule` through `BytecodeAbi.encode(module)`.
2. Kotlin calls Rust through `NativeVmBindings.runUntilSignal(bytecode, instructionBudget)`.
3. Rust decodes the module, creates a `VmInstance`, runs until a signal, and returns encoded signal bytes.
4. Kotlin decodes the signal bytes.
5. For this phase:
   - `Halt(Unit|Null|Bool|Int|Long|String)` is supported.
   - `Pause`, `Yield`, `Sleep`, and `HostCall` are surfaced as unsupported native-runner signals on the Kotlin side.

This one-call bridge is intentionally not the final VM lifecycle API. It avoids native handle lifetime risks while proving load/call/value encoding first. A later phase can introduce persistent native instances, `resumeWith`, and host-call round-trips.

## Rust API

Expose a C ABI function suitable for JNI wrappers:

- input: pointer + length for CKVM bytecode bytes;
- input: instruction budget;
- output: allocated byte buffer containing encoded signal bytes;
- output: status code and error message for decode/runtime failures.

For the initial implementation, direct JNI exports are acceptable if they keep all Java object access inside a tiny `jni.rs` module and call Rust-owned pure functions for the actual VM work.

## Signal Encoding

Use a small internal signal ABI distinct from the bytecode ABI:

- byte `0`: `Halt`
- byte `1`: `Pause`
- byte `2`: `Yield`
- byte `3`: `Sleep`
- byte `4`: `HostCall`
- byte `255`: `Error`

Value payload tags:

- byte `0`: `Unit`
- byte `1`: `Null`
- byte `2`: `Bool`
- byte `3`: `Int`
- byte `4`: `Long`
- byte `5`: `String`

Only halt values are decoded into Kotlin `NativeVmSignal.Halt` during this phase. Other signal kinds are represented enough to produce clear errors.

## Kotlin Integration

Add three Kotlin units:

- `NativeVmBindings`: loads the library path once and exposes a small internal `runUntilSignal(bytecode, instructionBudget)` wrapper.
- `NativeVmSignal`: decodes the signal bytes returned by Rust.
- `NativeVmRunner`: uses `BytecodeAbi`, `NativeVmBindings`, and current profile instruction budget. It completes successfully only for supported halt signals.

Runner selection should stay explicit. A helper can choose `NativeVmRunner` only when `ckl.vm.runner=rust`; otherwise it must return Kotlin/default behavior.

## Error Handling

- Missing library path with `ckl.vm.runner=rust`: throw `IllegalStateException` with a clear message.
- Native load failure: throw `UnsatisfiedLinkError` or wrap with the library path in the message.
- Rust decode/runtime error: return an encoded error signal and map it to `IllegalStateException`.
- Unsupported Rust signal in this phase: throw `UnsupportedOperationException` that names the signal and explains that host-call resume is not implemented yet.

## Testing Strategy

Use TDD:

1. Rust unit tests for signal encoding and pure VM JNI-facing function.
2. Kotlin unit tests for `NativeVmSignal` decoding.
3. Kotlin selection tests proving default remains Kotlin and Rust mode requires explicit properties.
4. Optional Kotlin JNI integration test gated by `ckl.vm.native.library`; skipped when the library path is absent.
5. Existing Kotlin runtime tests and Rust `cargo test` must remain green.

## Non-Goals

- Enabling Rust VM by default.
- Running ROM terminal/shell on Rust in this step.
- Implementing persistent native VM handles.
- Implementing `resumeWith` over JNI.
- Moving filesystem, display, IPC, process, or event state into Rust.
- Adding platform native packaging for released mod artifacts.
