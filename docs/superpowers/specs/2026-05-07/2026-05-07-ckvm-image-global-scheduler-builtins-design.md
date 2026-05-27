# CKVM Image Global Scheduler Builtins Design

## Goal

Unblock bundled CKL image lowering for the global scheduler builtins used by firmware and ROM scripts:

- `yield()`
- `sleep(ticks: Long)`

The implementation must use the existing native signal protocol and `NativeImageVmRunner` resume loop rather than modeling scheduler behavior inside bytecode execution.

## Scope

Included:

- Add `CkVmImage` opcodes for global scheduler builtins.
- Lower global `Instruction.CallBuiltin` calls for `yield` and `sleep` when `moduleName == null`.
- Execute those opcodes in the Rust image runner by returning existing `VmSignal::Yield` and `VmSignal::Sleep(ticks)` signals.
- Keep VM state resumable so `NativeImageVmRunner` can call `runtime.yield()` or `runtime.sleep(...)`, resume with `Unit`, and continue after the builtin call.
- Add Kotlin backend tests, Rust runner tests, JNI tests, and use the bundled-resource audit as the parity checkpoint.

Excluded:

- Do not add host imports for global `yield` or `sleep`.
- Do not change the native signal format; it already has `Yield` and `Sleep`.
- Do not implement class/object opcodes in this slice.
- Do not change CKL source syntax or scheduler semantics.

## ABI

Append two opcodes after collection methods:

- `YIELD = 24`
- `SLEEP = 25`

Encoding:

- `YIELD`: opcode only. No stack operands. Returns a native `Yield` signal and waits for resume.
- `SLEEP`: opcode only. Pops one stack operand, requiring `Long`. Returns a native `Sleep(ticks)` signal and waits for resume.

The runner does not push `Unit` before yielding/sleeping. The existing resume path pushes the resume value; `NativeImageVmRunner` already resumes yield and sleep with `Unit`.

## Lowering Rules

For `Instruction.CallBuiltin` with `moduleName == null`:

- `functionName == "yield"` and `argumentCount == 0` lowers to `YIELD`.
- `functionName == "sleep"` and `argumentCount == 1` lowers to `SLEEP`.
- Any other global builtin remains unsupported with a deterministic error.

`collectHostImports` continues to ignore global builtins, because these opcodes do not use host import IDs.

## Runtime Semantics

Rust `ImageVmHandle` behavior:

- `YIELD` sets the VM state to `WaitingForResume` and returns `VmSignal::Yield`.
- `SLEEP` pops one value. If it is `VmValue::Long(ticks)`, set state to `WaitingForResume` and return `VmSignal::Sleep(ticks)`. Otherwise return a deterministic type error.
- Resuming after either signal uses the existing `resume_with` path and pushes the supplied value onto the stack. Kotlin runner supplies `Unit`, matching CKL return type `Unit`.

No validation is added for negative sleep ticks in this slice. The language/runtime contract already exposes `sleep(Long)`, and tick validation belongs to `DeviceRuntime.sleep` if needed.

## Acceptance Criteria

- `compileImage` lowers `yield()` and `sleep(1L)` to `YIELD` and `SLEEP` opcodes.
- Rust runner emits `Yield` and `Sleep` signals and continues after resume.
- JNI runner calls `runtime.yield()` and `runtime.sleep(...)` for compiled image programs using the global builtins.
- The bundled-resource image audit no longer fails on global builtin `yield` or `sleep`.
- Any remaining bundled-resource audit failure becomes the next ordered runtime parity blocker.
