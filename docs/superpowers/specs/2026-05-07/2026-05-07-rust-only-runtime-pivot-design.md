# Rust-Only Runtime Pivot Design

## Status

Draft approved for design on 2026-05-07.

## Context

This branch is now allowed to break compatibility with the Kotlin/JVM VM because that implementation remains available in another branch. The mod is still early enough that maintaining two VM implementations is more expensive than useful.

The previous migration plan kept the Kotlin VM as a temporary fallback while the Rust image VM matured. That is no longer the desired direction. The runtime source of truth should become the Rust `CkVmImage` path, and Kotlin/JVM VM execution should be removed from this branch.

The current image path already has a working end-to-end harness for a small subset:

- Kotlin frontend compiles source through `LanguageFrontend.compileImage(...)`.
- `CkVmImageAbi` encodes `CkVmImage` bytes.
- Rust JNI image lifecycle creates, runs, resumes, and frees image handles.
- `NativeImageVmRunner` dispatches native VM signals through `RuntimeHostBridge`.
- `CkVmImageComputerProgram` wraps an already compiled image as a `DeviceProgram`.

The current image VM supports only skeleton execution: `PUSH_UNIT`, `RETURN`, `PUSH_CONSTANT`, `CALL_HOST`, and `POP`. This limits which runtime tests can remain active immediately after the pivot.

## Decision

Make Rust `CkVmImage` execution the only supported CKL runtime path in this branch.

Remove the Kotlin/JVM VM as a maintained runtime implementation:

- Remove `BytecodeVirtualMachine`.
- Remove `KotlinVmRunner`.
- Remove runner selection between Kotlin and Rust bytecode runners.
- Remove `BytecodeComputerProgram` as the primary runtime program wrapper.
- Remove or retire the legacy `BytecodeAbi -> NativeVmRunner` execution path that mirrors the Kotlin VM model.

Keep Kotlin compiler/frontend code temporarily:

- Keep parser, analyzer, diagnostics, formatter, and IDE support in Kotlin for now.
- Keep `BytecodeModule` only as a temporary compiler implementation detail while `CkVmImageCompiler` still lowers from it.
- Do not treat `BytecodeModule` as a runtime VM artifact after this pivot.

## Non-Goals

- Do not migrate parser, analyzer, formatter, or IDE services to Rust in this slice.
- Do not remove `BytecodeModule` until a typed IR or direct image backend exists.
- Do not reach full CKL runtime feature parity in this slice.
- Do not keep JVM VM tests as authoritative conformance tests.
- Do not preserve `ckl.vm.runner=kotlin` or legacy `ckl.vm.runner=rust` bytecode selection semantics.

## Runtime Architecture After Pivot

The active runtime chain should be:

1. Kotlin frontend compiles source to `CkVmImage`.
2. Kotlin runtime wraps the image in `CkVmImageComputerProgram`.
3. `NativeImageVmRunner` starts the Rust image VM through JNI.
4. Rust image VM runs until a signal.
5. Kotlin dispatches scheduling and host calls through `DeviceRuntime` and `RuntimeHostBridge`.
6. Kotlin resumes the Rust image VM with encoded host results.

The old chain should no longer be a supported runtime path:

`BytecodeModule -> BytecodeComputerProgram -> KotlinVmRunner -> BytecodeVirtualMachine`

The legacy Rust bytecode chain should also be retired because it preserves the old Kotlin VM bytecode shape:

`BytecodeModule -> BytecodeAbi -> NativeVmRunner -> Rust legacy VmInstance`

Implementation should split the pivot into two cleanup boundaries: first remove the JVM VM runtime, then remove the legacy Rust bytecode path. This keeps the first implementation slice focused while preserving the design target of deleting both old runtime paths from this branch.

## Compiler Boundary During Pivot

`BytecodeModule` is still allowed inside the compiler module for one reason: the current image compiler lowers from existing bytecode instructions. This is an implementation bridge, not a runtime contract.

Short-term rule:

- `LanguageFrontend.compile(...)` may continue to exist for frontend/compiler tests and for `compileImage(...)` internals.
- Runtime code should prefer `LanguageFrontend.compileImage(...)` and `CkVmImageComputerProgram`.
- New runtime tests should not instantiate `BytecodeVirtualMachine`, `KotlinVmRunner`, or `BytecodeComputerProgram`.

Long-term rule:

- Replace the bytecode-shaped compiler bridge with a typed IR or direct image backend.
- Delete `BytecodeModule`, `Instruction`, `BytecodeAbi`, and legacy Rust `abi.rs` once the image backend no longer depends on them.

## Test Strategy

The pivot should intentionally shrink the active runtime test surface to the image-supported subset.

Keep and expand tests for:

- `CkVmImageAbi` encoding.
- Rust image decoding.
- `CkVmImageCompiler` lowering for supported instructions.
- `NativeImageVmBindings` lifecycle.
- `NativeImageVmRunner` and `CkVmImageComputerProgram` end-to-end image execution.

Remove or retire tests that only validate the Kotlin/JVM VM runtime:

- Direct `BytecodeVirtualMachine` tests.
- `KotlinVmRunner` selection tests.
- Runtime behavior tests that rely on bytecode instructions not yet supported by `CkVmImage`.

Preserve important language semantics as future Rust image VM conformance backlog items. Do not treat deleted Kotlin VM runtime tests as lost requirements; treat them as pending image VM features.

## Migration Slices

### Slice 1 — Runtime Entry Pivot

- Make image runtime wrappers the only supported runtime entry in new code.
- Remove `VmRunnerFactory` selection semantics or reduce them to image-runner library availability.
- Remove `KotlinVmRunner` and `BytecodeVirtualMachine`.
- Update tests to stop depending on JVM VM execution.

### Slice 2 — Legacy Bytecode Native Cleanup

- Remove `NativeVmRunner` and legacy `NativeVmBindings` bytecode methods.
- Remove Rust `abi.rs`, `vm.rs`, and `runner.rs` once no tests or JNI exports require them.
- Keep Rust `signal.rs` and `value.rs` if still used by image JNI protocol.

### Slice 3 — Image VM Feature Expansion

- Add primitive operations.
- Add locals and control flow.
- Add function calls.
- Add records/classes/collections and VM-owned memory.

### Slice 4 — Compiler IR Cleanup

- Introduce typed IR or direct image backend.
- Remove `BytecodeModule` as a compiler bridge.
- Move compiler services toward Rust libraries.

## Risks and Mitigations

### Risk: Too many runtime tests disappear at once

Mitigation: keep a conformance backlog in docs and reintroduce tests as image VM features land.

### Risk: Kotlin runtime integration code still assumes `BytecodeComputerProgram`

Mitigation: update integration points to accept explicit `DeviceProgram` instances or use `CkVmImageComputerProgram` where CKL programs are constructed.

### Risk: `compileImage(...)` still depends on bytecode-shaped lowering

Mitigation: accept this as temporary. The pivot removes runtime support first; compiler bridge removal is a later slice.

### Risk: Removing legacy native bytecode runner breaks profiling comparison tasks

Mitigation: replace profiling tasks with image VM profiling baselines, or remove bytecode comparison tasks from this branch.

## Success Criteria

- No production runtime code instantiates `BytecodeVirtualMachine`.
- No production runtime code selects `KotlinVmRunner`.
- New CKL runtime execution uses `CkVmImageComputerProgram` and `NativeImageVmRunner`.
- Focused image runtime tests pass with the Rust native library.
- Remaining Kotlin frontend/compiler tests pass without depending on JVM VM execution.
- Documentation states that `BytecodeModule` is temporary compiler scaffolding, not a supported runtime VM format.