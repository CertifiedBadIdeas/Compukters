# Rux ABI Changelog

## Unreleased

- Collapsed the mod runtime to a single LowVM (issue #44). The legacy
  Image-VM (`image.rs`/`image_runner.rs` + `CallHost` opcode), device
  daemon (`device_daemon.rs`), runtime kernel (`runtime_kernel.rs`),
  and host-imported filesystem (`filesystem.rs`) have been retired
  along with their JNI surface and Kotlin wrappers
  (`NativeDeviceDaemonRuntime`, daemon entries in `NativeVmBindings`,
  `NativeVmSignal`/`NativeVmValue`). The `VmSignal` / `value` encoding
  used by the host-call protocol is gone. The frozen `RUXI` low-image
  ABI v1 itself is unchanged \u2014 the notebook already boots LowVM images
  via `createRuxComputer` and continues to do so.
- Retired the CKL/CKIM bytecode stack (issue #26). The mod-side JVM no longer
  hosts a high-level language frontend, CKIM image VM, or Workbench IDE.
  All player-facing computers (Notebook) boot the Rux low-image VM directly.
  The CKIM JNI exports
  (`createImage`/`runImageUntilSignal`/`resumeImage`/`imageMetrics`/`freeImage`)
  have been removed from `native/rux-vm`. The frozen `RUXI` low-image ABI v1
  itself is unchanged.
- Added draft Rux machine profile v2. This does not change frozen `RUXI` image ABI v1; it defines a new machine profile with boot info, configurable page size, and a static MMIO hardware table.

## 2026-05-15 - RUXI v1 Frozen

Status: frozen.

- Added `RUXI` low image ABI v1 as the external compiler target.
- Added machine-readable opcode table for low image v1.
- Added reference encoder in the Rust VM crate.
- Added ABI fixtures for golden execution and negative decode/validation cases.
- Added runtime-error ABI fixtures for divide by zero, memory faults, and call/return mismatches.
- Added `rux_abi_conformance` reference runner for golden, negative, and runtime-error fixtures.
- Added `U64Shl` so `u64` has symmetric left and right shift instructions.
- Expanded opcode JSON with machine-readable reads, writes, width, signedness, high-bit result policy, and trap conditions.
- Documented canonical unsigned aliases for wrapping add/sub/mul without adding duplicate serialized opcodes.
- Added advisory C++ frontend lowering notes.
- Added pre-freeze gap review and freeze checklist documents.
- Documented entry ABI, call/return ABI, arithmetic semantics, runtime error categories, machine profile boundaries, and stability policy.

## Freeze Policy

For frozen ABI versions:

- v1 image layout, instruction tags, operand encodings, and instruction semantics are immutable;
- breaking changes require a new numeric image format version;
- old fixtures remain in the repository;
- v1 decode/run conformance tests continue to pass;
- new machine/device profiles may be versioned separately from image format versions.
