# Native Image VM Runner Adapter Design

## Goal

Add an explicit Kotlin runtime adapter for the Rust-native `CkVmImage` execution path. The adapter should connect the existing image compilation/backend work to the recently added JNI image lifecycle without replacing the legacy bytecode VM runner.

This slice creates an end-to-end opt-in harness for `CkVmImage` programs:

1. Compile source to `CkVmImage` through `LanguageFrontend.compileImage(...)`.
2. Run the image through Rust JNI image handles.
3. Dispatch host calls through the existing Kotlin `DeviceRuntime` bridge.
4. Resume the Rust image VM with encoded host results.

## Non-Goals

- Do not replace `BytecodeComputerProgram`.
- Do not change `VmRunner` or `VmRunnerFactory` selection semantics.
- Do not make `ckl.vm.runner=rust` use `CkVmImage` yet.
- Do not expand the `CkVmImage` instruction set in this slice.
- Do not introduce a new host-call signal ABI; continue using the existing native signal/value codec for now.

## Current Context

The legacy native path is `BytecodeModule -> BytecodeAbi -> NativeVmRunner -> NativeVmBindings.create(...)`. It is still the production-compatible Rust runner path.

The image path now has these pieces:

- Kotlin image model and encoder: `CkVmImage` and `CkVmImageAbi`.
- Kotlin backend skeleton: `LanguageFrontend.compileImage(...)` and `CkVmImageCompiler`.
- Stable host import registry: `CkVmHostImportRegistry`.
- Rust image lifecycle: `NativeVmBindings.createImage(...)`, `runImageUntilSignal(...)`, `resumeImageWith(...)`, and `freeImage(...)`.
- Minimal Rust image execution for skeleton opcodes: `PUSH_UNIT`, `RETURN`, `PUSH_CONSTANT`, `CALL_HOST`, and `POP`.

The missing piece is a Kotlin runtime adapter that uses these image-specific JNI methods while preserving the same host bridge behavior as `NativeVmRunner`.

## Proposed API

Add a new runner class in the native runtime package:

- `NativeImageVmRunner.fromLibraryPath(libraryPath: String): NativeImageVmRunner`
- `NativeImageVmRunner.fromSystemProperty(): NativeImageVmRunner?`
- `NativeImageVmRunner.isAvailable(libraryPath: String?): Boolean`
- `suspend fun run(image: CkVmImage, runtime: DeviceRuntime)`

Add a new `DeviceProgram` implementation:

- `CkVmImageComputerProgram(image: CkVmImage, runnerFactory: () -> NativeImageVmRunner = { ... })`

`CkVmImageComputerProgram` should be explicit and opt-in. It should not be selected by `VmRunnerFactory`, because `VmRunner` currently accepts `BytecodeModule`, not `CkVmImage`.

## Runtime Flow

`NativeImageVmRunner.run(image, runtime)` should mirror `NativeVmRunner.run(module, runtime)`:

1. Encode the image with `CkVmImageAbi.encode(image)`.
2. Create a native image handle with `NativeVmBindings.createImage(...)`.
3. Loop on `NativeVmBindings.runImageUntilSignal(handle)`.
4. Decode the signal with `NativeVmSignal.decode(...)`.
5. Record VM signal metrics for non-error signals.
6. Dispatch signals:
   - `Halt`: return.
   - `Error`: throw with device context.
   - `Pause`: call `runtime.yield()`.
   - `Yield`: call `runtime.yield()`, then resume with unit.
   - `Sleep`: call `runtime.sleep(ticks)`, then resume with unit.
   - `WaitEvent`: call `runtime.pullEvent(filter)`, convert through `RuntimeHostBridge.fromEvent(...)`, and resume.
   - `HostCall`: convert native arguments to `VmValue`, invoke `RuntimeHostBridge.invoke(...)`, and resume with `NativeVmBindings.resumeImageWith(...)`.
7. Always free the image handle in `finally`.

The first implementation should keep the image loop explicit in `NativeImageVmRunner`. Shared helpers can be extracted only for small, obvious conversions; avoid refactoring the legacy bytecode runner unless the extraction directly reduces duplicate host-call conversion code.

## Testing Strategy

Add tests that skip when `ckl.vm.native.library` is not configured, matching existing native JNI smoke tests.

Required scenarios:

1. `NativeImageVmRunner` runs an empty `main` image to halt.
2. `NativeImageVmRunner` runs `system::log("hi")` through a host call and resumes to halt.
3. `CkVmImageComputerProgram` runs an image with the image runner factory.
4. Availability/system-property helpers behave consistently with `NativeVmRunner`.

Keep the tests focused on the current skeleton opcode support. Programs that require unsupported image opcodes should remain out of scope for this slice.

## Error Handling

- Image encoding errors should surface before native handle creation.
- Native image create errors should throw `IllegalArgumentException` from JNI, as the binding already does.
- Native `Error` signals should throw `IllegalStateException` or use Kotlin `error(...)` with device id context, matching `NativeVmRunner` style.
- The native handle must be freed even when host call dispatch or signal handling throws.

## Migration Implications

This adapter gives the project a clean, explicit image runtime entry point. Future slices can use it as the integration harness for:

- More `CkVmImage` opcodes.
- A typed IR backend that no longer lowers through `BytecodeModule`.
- Numeric host-call signal encoding based on stable host import ids.
- Adding a separate image-runner system property once image execution reaches enough feature parity for broader runtime experiments.