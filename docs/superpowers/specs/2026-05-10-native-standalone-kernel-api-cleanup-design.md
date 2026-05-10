# Native Standalone Kernel API Cleanup Design

## Summary

Remove the old external standalone native-kernel API now that `BackgroundDeviceVm` runs through the Rust native daemon by default.

This cleanup targets the public Kotlin/JNI surface that lets Kotlin create a `DeviceRuntimeKernel`, attach an image to it, and drive native IPC, event, display, process, and scheduler operations directly. The Rust daemon still owns and uses `DeviceRuntimeKernel` internally; that internal architecture is not legacy and must remain.

## Goals

- Remove Kotlin production access to standalone native device-kernel handles.
- Remove `NativeDeviceKernelProvider` and the fake `nativeDeviceKernelHandle = 0L` implementation from `VmRuntime`.
- Remove kernel-aware branches from `NativeImageVmRunner`.
- Remove standalone native-kernel methods from `NativeVmBindings` and their JNI exports from Rust.
- Keep daemon JNI bindings intact.
- Keep Rust `DeviceRuntimeKernel`, `DeviceRuntimeKernelHandle`, and `ImageVmHandle` where the daemon still needs them.
- Move or rewrite tests so daemon-owned behavior remains covered without preserving the old public API.

## Non-Goals

- Do not remove the Rust daemon.
- Do not remove Rust `DeviceRuntimeKernel` internals.
- Do not remove `ImageVmHandle`; daemon boot and child process execution still use it.
- Do not remove `CkVmImageComputerProgram`, `NativeImageVmRunner`, `RuntimeHostBridge`, or `VmProcessManager` in this slice unless compilation proves a tiny piece is already unused after the standalone-kernel cleanup.
- Do not change Minecraft-facing device behavior.
- Do not redesign host-call handling or process spawning in this slice.

## Current Legacy Shape

The current code still exposes an old migration-time path:

- Kotlin can call `NativeVmBindings.createDeviceKernel` and receive a standalone kernel handle.
- `NativeDeviceKernelProvider` lets a `DeviceRuntime` advertise that handle.
- `VmRuntime` implements `NativeDeviceKernelProvider`, but the real daemon-default runtime passes `0L`, making the production path inert.
- `NativeImageVmRunner` checks for that provider and conditionally attaches the image to the standalone kernel.
- `RuntimeHostBridge` has a native IPC fast path through the standalone kernel handle.
- Rust `jni.rs` stores standalone kernel handles in a global registry separate from daemon handles.
- Several JNI tests exercise this old public API directly.

That surface is now confusing because the daemon already owns the kernel lifecycle. Keeping both entry points makes it look like there are still two supported runtime modes.

## Target Architecture

There should be one supported production native runtime owner: `NativeDeviceDaemonRuntime`.

Kotlin should interact with the native runtime through daemon bindings:

- create/free daemon;
- boot daemon with CKIM bytes;
- run daemon ready work;
- refill daemon quota;
- enqueue daemon events;
- attach daemon filesystem/display;
- drain daemon host requests and display frames;
- complete daemon host/compile requests;
- wait on daemon display wake.

The standalone kernel remains a Rust implementation detail behind `DeviceDaemon`. No Kotlin production type should receive, store, or branch on a standalone kernel handle.

## Kotlin Cleanup

Remove the `NativeDeviceKernelProvider` interface and all production casts to it.

Update `VmRuntime` so it implements only `DeviceRuntime`. Remove:

- `nativeDeviceKernelHandle`;
- `nativeProcessId` override;
- `nativeWorkingDirectory` override.

Update `NativeImageVmRunner` so it:

- creates an image handle;
- optionally sets image working directory only if that remains a direct runner requirement;
- runs until signals;
- handles `WaitPoll` and `WaitProcess` by yielding instead of waiting on a standalone native kernel;
- no longer attaches images to a kernel;
- no longer attaches process images.

Update `RuntimeHostBridge` so IPC reads use the Kotlin runtime contract only. Remove the standalone native IPC fast path because daemon-owned IPC no longer routes through this bridge.

Update `NativeVmBindings` to remove standalone-kernel wrapper methods and data classes that have no daemon consumer:

- `NativeProcessSchedulerTick`;
- `NativeDeviceExecutionQuota`;
- `NativeDeviceSchedulerDryRun`;
- `NativeDeviceSchedulerStep`;
- `createDeviceKernel`;
- `freeDeviceKernel`;
- `attachImageToKernel`;
- `attachNativeFilesystem`;
- standalone native display operations;
- standalone event/IPC/wake operations;
- standalone process scheduler and process-state operations.

Keep image runner methods if `NativeImageVmRunner` still uses them:

- `createImage`;
- `runImageUntilSignal`;
- `resumeImageWith`;
- `freeImage`;
- `setImageWorkingDirectory`, if still needed.

Keep daemon methods.

## Rust JNI Cleanup

Remove the standalone kernel handle registry from `jni.rs`:

- `NEXT_DEVICE_KERNEL_HANDLE`;
- `DEVICE_KERNEL_HANDLES`;
- `register_device_kernel_handle`;
- `unregister_device_kernel_handle`;
- `device_kernel_handles`;
- `shared_kernel_handle`;
- standalone kernel lock helpers that are not used by daemon wrappers.

Remove JNI exports that only serve the standalone kernel API:

- create/free device kernel;
- enqueue standalone device event;
- standalone device IPC write/read;
- standalone device wake waits;
- standalone display wake waits;
- attach image to kernel;
- standalone process registration/state/scheduler functions;
- standalone native filesystem/display attach/drain/present functions.

Keep daemon JNI exports. Daemon display wake helpers may continue to use `shared_device_daemon_kernel_handle`, because that exposes daemon-owned kernel state without exposing a standalone handle to Kotlin.

## Tests

Rewrite or remove tests that only validate the deleted public API.

Keep Rust unit/integration coverage for kernel internals where useful. The Rust tests can still instantiate `DeviceRuntimeKernel` directly because they validate Rust internals, not Kotlin production modes.

Convert Kotlin JNI coverage to daemon-facing tests where behavior matters:

- scheduler/quota behavior should be covered by daemon tick/run-ready tests;
- display lifecycle and wake should use daemon display bindings;
- filesystem behavior should be covered through daemon-attached filesystem or Rust tests;
- event and IPC behavior should use daemon enqueue/read paths where available.

Remove Kotlin tests that only assert method exposure for deleted methods.

## Error Handling

After cleanup, attempts to use the old standalone kernel API should fail at compile time because the methods and interfaces no longer exist.

Daemon creation and boot remain fail-fast through existing daemon bindings. There should be no fallback from daemon failure to standalone kernel execution.

## Verification

Run:

- `./gradlew :compiler:test`;
- `./gradlew :core:test`;
- targeted NeoForge runtime/display tests if shared contracts change;
- Rust native tests for `ckl-vm` if JNI or Rust files change.

Also scan for deleted API names:

- `NativeDeviceKernelProvider`;
- `nativeDeviceKernelHandle`;
- `createDeviceKernel`;
- `freeDeviceKernel`;
- `attachImageToKernel`;
- `attachNativeFilesystem`;
- `attachNativeDisplay`;
- `runDeviceSchedulerDryRun`;
- `runDeviceSchedulerStep`;
- `processSchedulerTickNative`.

## Acceptance Criteria

- Kotlin production code has no standalone native kernel handle provider or casts.
- `NativeImageVmRunner` no longer attaches images to a standalone kernel.
- `RuntimeHostBridge` no longer calls standalone native IPC bindings.
- `NativeVmBindings` exposes daemon bindings and image-runner bindings, not standalone kernel bindings.
- Rust JNI no longer stores standalone kernel handles.
- Daemon-owned Rust kernel internals still compile and pass tests.
- No production runtime fallback path through the old standalone kernel remains.
