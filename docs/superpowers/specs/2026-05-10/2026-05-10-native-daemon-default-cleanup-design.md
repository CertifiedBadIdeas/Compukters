# Native Daemon Default Cleanup Design

## Summary

Make the Rust native device daemon the default and only production runtime path for `BackgroundDeviceVm`.

The current code still carries property-gated branches from earlier migration stages:

- `ckl.vm.native.daemon` enables the daemon path;
- `ckl.vm.native.display` enables native display mirroring for the older non-daemon native kernel path;
- `BackgroundDeviceVm` can still boot through the old Kotlin-managed program runner path when the daemon is disabled;
- display frame draining and wake checks still branch between a standalone native kernel, a daemon, and Kotlin fallback display state.

This cleanup removes those stale runtime choices. The device VM should fail fast if the native Rust runtime is not available, matching the Rust-only direction already used by image programs.

## Goals

- Always create and use a `NativeDeviceDaemonRuntime` for `BackgroundDeviceVm`.
- Remove runtime selection through `ckl.vm.native.daemon`.
- Remove display selection through `ckl.vm.native.display`.
- Remove the old non-daemon native kernel path from `BackgroundDeviceVm`.
- Remove the old Kotlin coroutine boot path that runs a compiled `DeviceProgram` directly.
- Keep Kotlin as the host integration and bridge layer for Minecraft-facing work.
- Keep tests able to inject fake daemon bindings without requiring the real native library.

## Non-Goals

- Do not remove the compiler frontend or CKIM lowering scaffolding.
- Do not remove `RuntimeHostBridge` or `DeviceRuntime` contracts in this slice.
- Do not remove client-side display frame decoding or packet transport.
- Do not perform a broad deletion of every Kotlin runtime-looking data structure unless it becomes unused after the daemon-only cleanup.
- Do not mix this cleanup with unrelated glyph or ROM changes.

## Runtime Behavior

`BackgroundDeviceVm` should construct the native daemon unconditionally during initialization.

If the production native bindings cannot create a daemon because the Rust library is unavailable or fails to load, construction or boot should fail with a clear error. There should be no silent fallback to the old Kotlin-managed runtime path.

Test code can still pass fake `NativeDaemonBindings`; those tests should not depend on native library discovery.

## Boot Flow

The boot flow should always use `bootNativeDaemon`.

The old branch that:

1. compiles firmware through `ComputerProgramCompiler.compile`,
2. obtains a `DeviceProgram`,
3. waits for a Kotlin slice permit,
4. runs `program.run(runtime)` in a coroutine,

should be removed from `BackgroundDeviceVm`.

Daemon boot continues to:

1. load the boot script;
2. compile it to `CkVmImage`;
3. encode the image with `CkVmImageAbi`;
4. call `NativeDeviceDaemonRuntime.boot`;
5. start the daemon executor;
6. enqueue the boot event and wake the executor.

## Display Flow

Display attach, resize, and detach should always mirror to the daemon. The standalone `NativeDisplayRegistry` path should be removed from `BackgroundDeviceVm`.

Frame draining and wake helpers should ask the daemon directly:

- `supportsNativeDisplayFramePump` returns true while the daemon is alive;
- `nativeDisplayWakeSequence` delegates to the daemon;
- `waitForNativeDisplayWake` delegates to the daemon;
- `drainNativeDisplayFrameBytes` delegates to the daemon;
- `drainDisplayFrames` drains daemon frames and discards any stale Kotlin display frames if the Kotlin display registry remains as metadata.

The Kotlin display registry may remain temporarily if it still provides metadata, metrics, or compatibility for host APIs. It must no longer be treated as a production rendering fallback.

## Events, IPC, And Processes

Event ingress should enqueue into the daemon and wake the daemon executor.

The old standalone native kernel event enqueue path should be removed.

Kotlin `EventManager`, `EventPayloadStore`, and `IpcChannelRegistry` should be reviewed after the daemon-only branch is in place. If they become unused production state, remove them. If a contract still requires them for test scaffolding or host bridge compatibility, keep them but document that they are not a runtime fallback path.

`VmProcessManager` should no longer rely on a regular native process bridge for daemon execution. Process creation and scheduling should use the daemon flow.

## Scheduling

`requestSlice` should always refill daemon quota and wake the daemon executor.

The old scheduler dry-run comparison against the standalone native kernel should be removed because it only exists to compare the prior Kotlin scheduler path against native scheduler decisions.

The daemon executor remains a Kotlin coroutine for now, but scheduling decisions and runnable process execution remain daemon-owned.

## Cleanup Targets

Primary cleanup targets:

- `BackgroundDeviceVm.nativeDisplayEnabled`;
- `BackgroundDeviceVm.nativeDaemonEnabled`;
- `BackgroundDeviceVm.nativeDeviceKernelHandle`;
- `BackgroundDeviceVm.nativeDisplayRegistry`;
- `BackgroundDeviceVm.nativeProcessBridge`;
- `BackgroundDeviceVm.runtime` and the old single-process boot runner;
- `BackgroundDeviceVm.requestSlice` non-daemon branch;
- display helper branches that choose between standalone native display and daemon display;
- old tests that only verify property-gated daemon/display opt-in behavior.

Secondary cleanup targets, only if they become unused:

- Kotlin IPC/event fallback structures;
- fallback display glyph or pixel rendering state;
- regular native kernel methods that no production code or tests still need after daemon-only migration.

## Testing Strategy

Add or update focused tests before implementation:

- `BackgroundDeviceVm` creates and boots a daemon without setting `ckl.vm.native.daemon`.
- display attach mirrors to the daemon without setting `ckl.vm.native.display`.
- `requestSlice` refills daemon quota and wakes the daemon executor without property setup.
- production construction fails clearly when production bindings cannot create a daemon.
- old property-gated behavior is no longer required by tests.

Verification should include:

- `./gradlew :core:test`;
- `./gradlew :compiler:test`;
- relevant `./gradlew :v1_21_1-neoforge:test` filters around background VM and display frame pump behavior;
- Rust native daemon tests if JNI/native changes are needed.

## Acceptance Criteria

- `BackgroundDeviceVm` has no runtime branch controlled by `ckl.vm.native.daemon`.
- `BackgroundDeviceVm` has no display branch controlled by `ckl.vm.native.display`.
- Boot uses the native daemon path only.
- Display frame pump support is daemon-based by default.
- Missing native runtime support fails loudly instead of falling back to Kotlin execution.
- Existing daemon tests are updated to no longer set daemon/display opt-in properties.
- No removed fallback path is still referenced by production code.
