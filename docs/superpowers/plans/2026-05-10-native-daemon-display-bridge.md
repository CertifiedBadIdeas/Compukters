# Native Daemon Display Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development for each implementation task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** expose the daemon-owned Rust display registry through Kotlin/JNI using the existing native display frame ABI.

**Architecture:** add daemon display operations beside the existing daemon event/host request JNI. The daemon will call
the same `DeviceRuntimeKernelHandle.attach_display`, `detach_display`, and `drain_display_frames` methods used by the
standalone native kernel path. Kotlin will decode daemon frames with `NativeDisplayFrameCodec`.

## Task 1: Daemon Display JNI Surface

**Files:**
- Modify: `native/ckl-vm/src/device_daemon.rs`
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [ ] Add a focused JNI test that creates a daemon, attaches a display, boots a small image that presents one frame,
  ticks the daemon, drains daemon display frame bytes, and asserts an initial full refresh plus a program frame.
- [ ] Add `DeviceDaemon.attach_display`, `detach_display`, and `drain_display_frames`.
- [ ] Add JNI functions `attachDeviceDaemonDisplayNative`, `detachDeviceDaemonDisplayNative`, and
  `drainDeviceDaemonDisplayFramesNative`.
- [ ] Add Kotlin binding methods with the same validation style as existing daemon bindings.
- [ ] Run the focused compiler JNI test.
- [ ] Commit Task 1.

## Task 2: Background VM Daemon Display Wiring

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] Extend `NativeDaemonBindings` and `NativeDeviceDaemonRuntime` with attach/detach/drain display operations.
- [ ] Mirror `attachDisplay`, `resizeDisplay`, and `detachDisplay` into the daemon runtime when daemon mode is active.
- [ ] Drain daemon frames from `BackgroundDeviceVm.drainDisplayFrames` and stopped-frame collection.
- [ ] Add a daemon display test around `BackgroundDeviceVm` proving frames are drained from the daemon path.
- [ ] Run focused core tests.
- [ ] Commit Task 2.

## Task 3: Verification

- [ ] Run Rust native tests.
- [ ] Run focused compiler/core tests with the native library.
- [ ] Run the daemon smoke profiling test.
- [ ] Update this plan checklist with completed tasks.
