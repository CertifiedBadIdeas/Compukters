# Collapse to a Single VM (LowVM, flat RAM, no daemon)

> Issue: [#44](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/44)

## Problem

The repository ships two parallel VMs:

1. **Image-VM** — typed-register bytecode (`Instruction::CallHost { import_id, ... }`), `image.rs` decoder, `image_runner.rs` interpreter, plus a `device_daemon.rs` + `runtime_kernel.rs` host that owns a process table, scheduler, optional filesystem, display registry, IPC, events. Host-imports are a separate ABI surface (~30 numbered IDs across `process.*`, `system.*`, `events.*`, `ipc.*`, `filesystem.*`).
2. **LowVM** — flat-RAM machine (`computer/machine.rs`, `low_image.rs`, `low_image_runner.rs`, `low_bus.rs`). Opcodes work on linear address space; "devices" are MMIO regions on the bus (control, debug serial, serial input, text display).

After #26 the Image-VM no longer has a producer: `native/rux-compiler` already emits LowVM (`use rux_vm::low_image::{Function, Image, Instruction}` in `backend/codegen.rs`). The in-game Notebook also already runs on LowVM via `RuxComputerRuntimeFactory` → `createRuxComputer` (LowVM JNI), not via the device daemon. The Image-VM track is only kept alive by one Kotlin consumer (`BackgroundDeviceVm.kt`) and its tests.

Maintaining both costs: duplicated opcode table, duplicated metrics, host-import surface, JNI surface area, and architectural confusion about where new devices live (MMIO bus vs host-import ID).

## Decision

Delete the Image-VM track entirely. LowVM with MMIO devices becomes the only VM. One process per machine, forever. No filesystem (host-imported FS goes away with the daemon; an MMIO block device is a future, separate piece of work).

## Scope

### Delete (Rust)

- `native/rux-vm/src/image.rs`
- `native/rux-vm/src/image_runner.rs`
- `native/rux-vm/src/device_daemon.rs`
- `native/rux-vm/src/runtime_kernel.rs`
- `native/rux-vm/src/filesystem.rs`
- `native/rux-vm/tests/image_decode.rs`
- `native/rux-vm/tests/image_runner.rs`
- Module declarations in `native/rux-vm/src/lib.rs` for the five deleted modules
- `signal.rs::VmSignal::HostCall` variant and its codec
- All `Java_*_*DeviceDaemon*Native` JNI functions in `jni.rs`, plus the helper statics (`DEVICE_DAEMON_HANDLES`, `NEXT_DEVICE_DAEMON_HANDLE`, host-request encoders)

### Delete (Kotlin)

- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt`
- All `*DeviceDaemon*` `external fun` declarations in `NativeVmBindings.kt`
- The data classes `NativeDeviceDaemonBootSummary`, `NativeDeviceDaemonTickSummary`, `NativeDeviceDaemonHostRequest`
- All device-daemon plumbing inside `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`: daemon construction, executor, host-request loop, display-frame drain, native-daemon bindings parameter

### Keep (Rust, LowVM-only)

- `native/rux-vm/src/computer/**` (machine, devices, handle)
- `native/rux-vm/src/low_*.rs` (image, runner, machine, bus, disasm)
- `native/rux-vm/src/microcontroller_machine.rs`
- `native/rux-vm/src/computer_abi.rs`, `computer_machine.rs`, `rux_computer.rs`
- `native/rux-vm/src/display.rs`, `signal.rs`, `value.rs` (with HostCall variant pruned)
- `native/rux-vm/src/jni.rs` LowImage + RuxComputer entry points

### Keep (Kotlin, LowVM-only)

- LowVM JNI surface in `NativeVmBindings.kt` (`createLowImage*`, `runLowImageUntilSignal*`, `createRuxComputer*`, `runRuxComputerUntilSignal*`, `pushRuxComputerSerialInput*`, display snapshot, control, debug output, free)
- `RuxComputerRuntimeFactory`, `ComputerRuntimeDeviceFactory`, `NotebookBlockEntity` boot path
- `BackgroundDeviceVm.kt` — but rewritten to use LowVM directly if it still has a non-daemon responsibility (TBD during execution, see open questions)

### Firmware

The bundled `*.ruxi` resources in `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/` are LowVM `low_image` artifacts despite the extension; `createRuxComputer` decodes them via `low_image::decode_image`. They stay as-is. The extension may be renamed in a follow-up but is out of scope for this issue.

### stdlib

`native/rux-compiler/stdlib/std/*.rx` is already pure LowVM (no host imports). No changes needed.

### Docs

- `docs/abi/CHANGELOG.md` — record the Image-VM and host-import retirement.
- `docs/abi/PRE-FREEZE-GAPS.md`, `docs/abi/FREEZE-CHECKLIST.md` — audit and remove any Image-VM references; the freeze candidate is now exclusively the LowVM ABI (`rux-low-image-v1*`, `rux-computer-profile-v1.md`, `rux-machine-profile-v2.md`).
- `docs/ARCHITECTURE.md` — collapse the "two VMs" section to "one VM".

## Non-goals

- Filesystem device (any kind). Future MMIO block device is a separate issue.
- Multi-process / scheduler model. One process per machine, period.
- Renaming the `.ruxi` extension to `.lowi`.
- New shell, new firmware, or any UI work.
- LLVM backend (#41). It will target LowVM when it lands.
- Cleaning up the now-confusing `signal.rs::VmSignal` enum beyond removing `HostCall`.

## Acceptance

1. `cd native/rux-vm && cargo test` is green.
2. `cd native/rux-compiler && cargo test` is green.
3. `./gradlew build` (or at least `:compiler:test :core:test :v1_21_1-common:test :v1_21_1-neoforge:test :native-runtime:test`) is green.
4. `grep -RIn -E 'image_runner|device_daemon|runtime_kernel|CallHost|DeviceDaemon|host_import' native/rux-vm/src native/rux-vm/tests modules` returns nothing meaningful (only doc / changelog references, not code).
5. `NotebookBlockEntity` in a dev-launched Minecraft still boots `rux-laptop.ruxi`; the Image-VM boot path is no longer reachable.
6. Docs under `docs/abi/` and `docs/ARCHITECTURE.md` reflect the single-VM reality.

## Open questions

- **What is `BackgroundDeviceVm` for?** If after removing daemon plumbing the class has no remaining responsibility, it should be deleted too. Decided during execution after reading the file.
- **`signal.rs` cleanup depth.** Removing only the `HostCall` variant vs trimming the whole enum to LowVM-relevant signals. Default: minimum-viable trim — only delete what becomes dead.
