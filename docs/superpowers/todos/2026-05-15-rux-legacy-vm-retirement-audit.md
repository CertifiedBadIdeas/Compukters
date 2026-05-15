# Rux Legacy VM Retirement Audit

## Status

Working note for retiring CKL/Kotlin VM fallback paths after the Rux computer boot slice is covered by tests.

The current goal is not to delete everything immediately. The goal is to avoid accidental fallback behavior in the new Rux computer path, then remove legacy CKL runtime surfaces once Rux has terminal/display coverage.

## Production Candidates To Replace

- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt`
  - Current role: boots CKL image programs through the native daemon and services host requests.
  - Rux target: boot precompiled Rux firmware through `RuxComputerHandle`, then add explicit Rux machine/device bridges.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
  - Current role: exposes old CKIM image handles, low-image handles, Rux computer handles, and native daemon APIs from one binding object.
  - Rux target: keep Rux computer APIs first-class; quarantine old CKIM and daemon APIs behind benchmark/test/runtime migration boundaries until deleted.
- `native/rux-vm/src/device_daemon.rs`
  - Current role: Rust-owned CKL image daemon with process table, host requests, filesystem, and display bridging.
  - Rux target: keep only until the mod computer lifecycle can boot Rux firmware without CKL process semantics.
- `native/rux-vm/src/image_runner.rs`
  - Current role: old CKIM image runner.
  - Rux target: delete after no production code boots CKIM images.

## Resource Candidates To Replace Later

- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck`
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/boot.ck`
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/shell.ck`
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/bin/*.ck`

These remain legacy CKL resources until the Rux terminal/display story exists. The new `firmware/rux-terminal.ruxi` resource proves the replacement boot path but does not yet replace the ROM shell.

## Benchmark And Test-Only Candidates To Keep Temporarily

- `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/ComputeVmBenchmarkRunners.kt`
  - Keep until Rux computer benchmarks replace low-image-only benchmarks.
- `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`
  - Keep while it covers old CKIM/low-image JNI compatibility and new Rux computer JNI behavior.
- `native/rux-vm/tests/image_runner.rs`
  - Keep until old CKIM image runner is deleted.
- `native/rux-vm/tests/fixtures/*.ckim`
  - Keep only as long as CKIM decoder/runner tests exist.

## Non-Runtime Fallbacks To Ignore For This Migration

Search also finds fallback wording in UI/layout/workbench code. Those are not VM fallback paths and should not be mixed into the Rux runtime migration.

Examples:

- workbench/client UI race fallbacks;
- layout fallback width/height helpers;
- architecture test code-source fallback.

## Deletion Gates

- Rux firmware resource boots on native computer in module tests.
- Mod runtime can instantiate a Rux computer without Kotlin VM fallback.
- Native library missing or invalid image setup fails fast for Rux firmware.
- Display/input story is either implemented for Rux or explicitly deferred with no old fallback in the new path.
- Legacy APIs are classified as production, benchmark-only, test-only, or dead code.
- `RUXI` v1 ABI fixtures remain unchanged during cleanup.

## First Cleanup Targets After MVP

1. Split `NativeVmBindings` surface by runtime family or add naming/tests that prevent Rux code from calling CKIM/daemon APIs.
2. Add a search-based test that Rux firmware boot code does not reference `CkVmImageComputerProgram`, `createImageNative`, or `bootDeviceDaemon`.
3. Move old CKIM runner tests under an explicit legacy name before deletion.
4. Replace `firmware/bios.ck` boot selection with a Rux firmware selection path once display/input output exists.
