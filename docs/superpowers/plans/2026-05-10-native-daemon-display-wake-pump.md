# Native Daemon Display Wake Pump Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development for each task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** enable the native display frame pump for Rust daemon mode.

**Architecture:** reuse the existing Rust `DeviceRuntimeKernelHandle` display wake sequence/condition variable from the
daemon's shared kernel. JNI must clone the daemon kernel handle before waiting so the daemon registry lock is not held
across blocking waits. Kotlin exposes daemon wake methods through the existing `NativeDeviceDaemonRuntime` boundary.

**Tech Stack:** Rust native CKL VM, JNI, Kotlin/JVM coroutines, Gradle tests.

---

## File Structure

- Modify `native/ckl-vm/src/jni.rs`
  - Add daemon display wake JNI methods.
  - Add a helper that clones the daemon shared kernel handle without holding the daemon registry lock during waits.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
  - Add daemon display wake binding methods and native declarations.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`
  - Add daemon display wake JNI coverage.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt`
  - Add daemon display wake methods to the runtime and binding interface.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
  - Enable native display pump support for daemon mode and route wake calls through daemon runtime.
- Modify `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`
  - Add daemon display wake pump wiring coverage.

## Task 1: Daemon Display Wake JNI Surface

- [ ] Add failing compiler JNI tests:
  - `nativeDeviceDaemonDisplayBindingsExposeWakeWait`
  - `nativeDeviceDaemonDisplayWaitReturnsAfterPresentWhenLibraryIsConfigured`
- [ ] Verify RED with focused compiler tests.
- [ ] Add `displayWakeSequenceDeviceDaemon` and `waitForDeviceDaemonDisplayWake` Kotlin bindings.
- [ ] Add JNI functions that clone the daemon kernel handle and call `display_wake_sequence` / `wait_for_display_wake`.
- [ ] Rebuild native library and verify focused compiler tests pass.
- [ ] Commit Task 1.

## Task 2: Background VM Daemon Pump Wiring

- [ ] Add failing core test `nativeDaemonDisplayWakePumpIsSupportedAndDelegatesWakeCalls`.
- [ ] Verify RED with focused core test.
- [ ] Extend `NativeDaemonBindings` and `NativeDeviceDaemonRuntime` with display wake methods.
- [ ] Update `BackgroundDeviceVm.supportsNativeDisplayFramePump`, `nativeDisplayWakeSequence`, and
  `waitForNativeDisplayWake` to use daemon runtime when standalone native registry is absent.
- [ ] Verify focused core test passes.
- [ ] Commit Task 2.

## Task 3: Verification

- [ ] Run Rust native tests.
- [ ] Run focused compiler daemon display tests with native library.
- [ ] Run focused core daemon display tests.
- [ ] Run daemon smoke profiling test.
- [ ] Commit checklist completion.
