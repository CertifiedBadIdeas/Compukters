# Rust Image Runtime Cleanup Design

## Context

The branch has already pivoted production CKL execution to `CkVmImageComputerProgram` and `NativeImageVmRunner`. Kotlin still owns parsing, analysis, and temporary lowering through `BytecodeModule`, but the Kotlin/JVM bytecode interpreter is gone.

One large legacy runtime path remains: the Rust bytecode VM prototype. It decodes `BytecodeAbi`, executes a Rust mirror of the old bytecode interpreter, and exposes JNI methods that are no longer selected by production runtime code.

This path should be removed before adding more `CkVmImage` opcodes. Keeping two native VM formats increases maintenance cost and makes future work ambiguous.

## Goals

- Make the native runtime codebase image-only.
- Remove legacy CKVM bytecode ABI serialization and execution from Kotlin and Rust runtime code.
- Preserve the shared native host-call signal/value protocol used by the image runner.
- Update profiling test names and docs so they no longer describe JVM-vs-Rust runner comparison semantics.
- Keep `BytecodeModule` and `Instruction` as temporary compiler scaffolding only.

## Non-Goals

- Do not remove `BytecodeModule`, `Instruction`, frontend code generation, or compiler tests that validate the temporary compiler IR.
- Do not add new image opcodes in this slice.
- Do not redesign CKIM layout or host import IDs.
- Do not change `NativeImageVmRunner` public behavior except by removing legacy bytecode methods from its binding dependency.

## Design

### Native crate boundary

The native crate should expose only image runtime modules:

- `image.rs` for CKIM decoding.
- `image_runner.rs` for image execution.
- `signal.rs` for host-call signal encoding/decoding.
- `value.rs` for shared native value representation.
- `jni.rs` for image-specific JNI exports.

Remove these legacy modules:

- `abi.rs`
- `runner.rs`
- `vm.rs`

`VmSignal` currently lives in `vm.rs`, but it is not inherently tied to the legacy VM. Move `VmSignal` into `signal.rs`. `image_runner.rs` should import it from `crate::signal`, and `signal.rs` should encode it directly without depending on `crate::vm`.

### JNI boundary

`NativeVmBindings` becomes image-only.

Keep these Kotlin methods:

- `createImage(libraryPath, image, instructionBudget)`
- `runImageUntilSignal(handle)`
- `resumeImageWith(handle, value)`
- `freeImage(handle)`

Remove these legacy bytecode methods:

- `runUntilSignal(libraryPath, bytecode, instructionBudget)`
- `create(libraryPath, bytecode, instructionBudget)`
- `runUntilSignal(handle)`
- `resumeWith(handle, value)`
- `free(handle)`

The Rust JNI exports should mirror this: delete bytecode exports for `runUntilSignalNative`, `createNative`, `runUntilSignalForHandleNative`, `resumeWithNative`, and `freeNative`. Keep image exports only.

### Kotlin bytecode ABI cleanup

Delete `BytecodeAbi.kt` and its direct tests. This does not remove `BytecodeModule`; it only removes the serialized old-runtime ABI that existed to feed the legacy Rust VM.

Compiler tests that inspect `BytecodeModule` as an internal artifact can remain. Runtime tests must target `CkVmImage` or `NativeImageVmRunner`.

### Rust tests

Delete legacy bytecode tests:

- `tests/abi_decode.rs`
- `tests/runner.rs`
- `tests/pure_vm.rs`

Keep image and shared codec tests:

- `tests/image_decode.rs`
- `tests/signal_codec.rs`

If `signal_codec.rs` imports `VmSignal` from `ckl_vm::vm`, update it to `ckl_vm::signal::VmSignal`.

### Profiling naming cleanup

The previous profiling task compared JVM and Rust VM runners. That no longer matches the branch.

Remove JVM/Rust comparison-specific pieces:

- `RuntimeVmProfilingReportAggregationTest`
- Markdown comparison formatter code that renders JVM/Rust ratios.
- Gradle task and docs references to comparison reports.

Keep the raw single-run profiling codec and `RuntimeVmProfilingReportTest`, but rename terminology from runner comparison to image runtime profiling where practical. The task should be `profileRuntimeVmImage` and should run with `ckl.vm.native.library` only.

This cleanup can be mechanical. It does not need to preserve historical comparison report formats.

## Validation

Run these checks after implementation:

1. Focused Kotlin/image tests with native library:
   - `buildRustVmNativeLibrary`
   - `CkVmImageBackendTest`
   - `CkVmImageComputerProgramTest`
   - `NativeImageVmRunnerJniTest`
   - `NativeImageVmBindingsJniTest`
   - `DeviceProgramSupportTest`
2. Rust tests:
   - `cargo test --manifest-path native/ckl-vm/Cargo.toml -- --nocapture`
3. Stale reference checks:
   - no production references to `BytecodeAbi`, `NativeVmHandle`, `run_bytecode_until_signal`, legacy `createNative`, legacy `runUntilSignalNative`, or `ckl.vm.runner`.
4. `git diff --check` and clean status after commits.

## Risks

### Risk: Removing `vm.rs` also removes shared signal definitions

Mitigation: move `VmSignal` to `signal.rs` before deleting `vm.rs`. Keep signal codec tests as regression coverage.

### Risk: Old profiling tests still assume JVM/Rust comparison

Mitigation: remove comparison-only tests/formatters or rename them into single image-runtime profiling tests. Do not leave tests that mention a deleted JVM VM runner as active behavior.

### Risk: Bytecode ABI removal could be confused with compiler IR removal

Mitigation: keep `BytecodeModule` and `Instruction` unchanged. The cleanup removes only serialization/execution of the legacy runtime ABI.

## Acceptance Criteria

- The native crate no longer exports `abi`, `vm`, or `runner` modules.
- Kotlin runtime bindings expose only image VM JNI lifecycle methods.
- `BytecodeAbi.kt` and its tests are gone.
- Legacy Rust bytecode tests are gone.
- Image JNI tests and Rust image/signal tests pass.
- Active docs and Gradle tasks no longer mention `ckl.vm.runner` or JVM-vs-Rust VM comparison as current runtime behavior.