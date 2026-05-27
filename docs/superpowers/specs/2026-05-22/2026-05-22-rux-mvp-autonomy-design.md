# Rux MVP autonomy: remove the CKL stack, keep Notebook on Rux

> Issue: [#26](https://github.com/lazyhat/Compukter-Kraft/issues/26)

## Why

The repo currently carries two parallel compute stacks:

- **CKL** — Kotlin frontend (`modules/compiler/.../lang/frontend`) that compiles `.ck` source into the CKIM stack-VM image, executed by `image_runner.rs` via `Java_..._{create,run,resume,metrics,free}Image*Native` JNI calls.
- **Rux** — Rust frontend (`native/rux-compiler`) that compiles `.rx` source into RUXI v1 low images, executed by `low_image_runner.rs` and wrapped by `RuxComputer` for the in-game block path.

Player-facing reality after auditing the live wiring:

- `NotebookBlockEntity` extends `ComputerBlockEntity`, which calls `ComputerRuntimeDeviceFactory.createRuxComputer(...)` → `RuxRuntimeDevice` backed by the Rust VM. It already boots the pre-baked `rux-laptop.ruxi` firmware shipped under `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/`. **The Notebook is already on Rux.**
- The only live consumer of the CKL stack is `WorkbenchBlockEntity`, which instantiates `RuntimeDeviceImpl` (CKIM-backed) for its detached compile target. `RuntimeDeviceImpl` pulls in `BackgroundDeviceVm` → `DeviceProgramSupport.ComputerProgramCompiler` → `LanguageFrontend` → `CkVmImageAbi` → `createImageNative`.
- `TerminalItem` and `SerialTerminalItem` bind to a `RuntimeDevice` looked up by id; their interaction surface is generic, but the only `RuntimeDevice` instances they meaningfully target are the Workbench's detached devices.

That makes a clean cut possible: take Workbench, Computer block, and Terminal items off the registration list. The entire CKL stack becomes unreachable in production. We then delete it. Notebook keeps working unchanged because it goes through the Rux path that already exists.

## Non-goals

- In-game Rux editor / UI DSL rewrite (tracked separately as #30).
- Exposing `rux_compiler::compile` over JNI for user-supplied programs (out of MVP; future work once the editor is back).
- Adding new MMIO devices, keyboard input, persistent storage, etc. (own issues).
- Multi-loader/Fabric/older MC versions.

## What ships

After the MVP:

- Player places a Notebook block. It boots the bundled `rux-laptop.ruxi` and displays its output. End-to-end through the Rust toolchain: `native/rux-compiler` produces the firmware at build time, `native/rux-vm` (`LowImage` + `RuxComputer`) executes it inside `RuxRuntimeDevice`.
- No CKL source, no CKIM VM, no `LanguageFrontend`, no Workbench / Computer-block / Terminal registrations.
- `./gradlew build` and `cargo test` green; no references to `LanguageFrontend`, `CkVmImageAbi`, `BackgroundDeviceVm`, `createImageNative`, `runImageUntilSignal`, `resumeImageWith`, `imageMetricsNative`, `freeImageNative`.

## Design

### 1. Gate player-facing CKL hosts out of registration

Targets in `modules/v1_21_1/v1_21_1-common/.../binding/ModObjects.kt` and the NeoForge mod entrypoint:

- Workbench: block, block entity type, menu, item.
- Computer block: block, block entity type, item. The classes `AbstractComputerBlockEntity` / `ComputerBlockEntity` / `ComputerRuntimeDeviceFactory` STAY — Notebook inherits them. We only remove the standalone Computer block registration.
- TerminalItem and SerialTerminalItem: items + their menus.

Notebook + the Rux-firmware resource + its block entity type + its menu remain registered.

Registration removal is preferred to feature flags because the goal is full deletion of code on the other side of the registration.

### 2. Delete unreachable Kotlin

After step 1, the following are unreachable. Delete:

- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceVmSupervisor.kt`
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt`
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceProgramSupport.kt` (incl. `ComputerProgramCompiler`)
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/language/LanguageServices.kt`
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/**`
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/item/TerminalItem.kt`
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/serial/**` (item + menu + helpers)
- Computer-block-only files that are not inherited by Notebook (`ComputerBlock`, `ComputerBlockEntity` direct registrations, `ComputerItem` if any). Re-evaluate during execution; if Notebook references them through composition, keep them.
- Companion NeoForge classes (`NeoForgeComputerBlockEntity`, terminal screen renderers, workbench screens).
- All tests covering deleted classes.

Trim `DeviceManager` to the surface still used by `RuxRuntimeDevice`. The `WorkbenchBlockEntity.resolveTargetComputer` path goes away.

### 3. Trim `modules/compiler` to native-runtime glue

The "compiler" module no longer contains a compiler — the Rust crate owns that. Keep only the JNI bridge plus the RuxComputer Kotlin wrapper:

- Keep: `lang/runtime/blazing/NativeVmBindings.kt` (LowImage + RuxComputer methods only; CKIM methods removed in step 4), `RuxComputerRuntime.kt`, `RuxComputerRuntimeFactory.kt`, `NativeLowImageVmSignal.kt`, `NativeLowImageVmMetrics.kt`, profiling helpers wired to LowImage.
- Delete: `lang/frontend/**`, `lang/runtime/image/**`, CKIM-only profiling/benchmark code, all `.ck` tests and resources.

Rename the Gradle module from `modules/compiler` to `modules/native-runtime` (and the corresponding Gradle project / package). Update `modules/core/build.gradle.kts` and dependants.

This rename is optional in principle but cheap to do in the same pass and prevents long-term confusion ("compiler" containing zero compilation logic).

### 4. Delete CKIM JNI + Rust VM

In `native/rux-vm`:

- Delete `src/image.rs`, `src/image_runner.rs`, `src/signal.rs` (if CKIM-only — verify; if shared, keep the LowImage parts), `src/value.rs` (verify the same way).
- Remove from `src/jni.rs` the functions `createImageNative`, `runImageUntilSignalForHandleNative`, `resumeImageWithNative`, `imageMetricsNative`, `freeImageNative`, plus their `ImageVmHandle` helpers.
- Drop the matching `pub mod image; pub mod image_runner;` lines from `src/lib.rs`.
- Delete CKIM tests under `native/rux-vm/tests/`.

`computer_abi.rs`, `device_daemon.rs`, `runtime_kernel.rs`, `microcontroller_machine.rs` need to be classified: keep if used by `RuxComputer`/`LowImage`, delete otherwise. This audit is part of the plan.

### 5. Delete CKL assets

- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck` and any other `.ck` resource files.
- Compiler-side benchmarks/profiling that target `.ck` source. Keep RUXI-targeted profiling where it stays useful (it's referenced by the `ckl.benchmark.*` system properties in `modules/compiler/build.gradle.kts` — rename properties and bench code, or delete if obsolete).

### 6. Documentation

- `docs/LANGUAGE.md` — replace with a stub stating CKL has been removed and pointing to `native/rux-compiler` and `docs/abi/`. Don't keep the old language reference inline.
- `docs/ARCHITECTURE.md` — update the diagram to remove BackgroundDeviceVm/CKIM path; promote Rux/RuxComputer as the only runtime.
- `docs/abi/CHANGELOG.md` — record CKIM retirement.
- README — drop mentions of CKL / Workbench programming flow.

### 7. Verification gate

- `./gradlew :core:test :v1_21_1:v1_21_1-neoforge:test build` clean.
- `cargo test --workspace` clean in `native/`.
- Manual dev launch: place Notebook, confirm it boots `rux-laptop.ruxi`, screen renders.
- `rg -i 'LanguageFrontend|CkVmImageAbi|BackgroundDeviceVm|createImageNative|runImageUntilSignal|resumeImageWith|imageMetricsNative|freeImageNative' modules native` returns zero hits.

## Risks and trade-offs

- **Workbench-edited save data may exist in older worlds.** No migration: removing Workbench drops any in-world workspaces. Acceptable for the MVP because no public release ships these worlds. Will be noted in the release notes when the next tagged build is cut.
- **Computer block was already wired to a Rux firmware** (`rux-firmware-resource`). Hiding it from registration means players who want to experiment with `rux-terminal.ruxi`/`rux-echo-live.ruxi` directly lose UI access. We accept this — the goal is one clean playable surface (Notebook) for the MVP.
- **`modules/compiler` rename touches many `implementation(projects.compiler)` references.** Mechanical but noisy; if the cost looks high during planning, postpone the rename to a follow-up cleanup commit and keep the directory name.
- **`computer_abi.rs` / `device_daemon.rs` audit** could uncover unexpected CKIM coupling. The plan reserves a step to classify them; if shared with LowImage, they stay.

## Out of scope (follow-ups, not in this MVP)

- Returning Workbench / Terminal / Computer-block to the player as Rux-native experiences (likely after #30, #31, #32).
- JNI `rux_compile_native(source) -> bytes` so the in-game editor can compile user code.
- Stdlib expansion (#27), disasm completeness (#28), Rux error model (#29).
