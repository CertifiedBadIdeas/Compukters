# Native Daemon Event Ingress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mirror accepted Kotlin VM events into the Rust-owned device daemon.

**Architecture:** Reuse the existing native event payload bytes and decoding path. Add a daemon-specific JNI entrypoint
that forwards decoded event arguments into the daemon's internal `DeviceRuntimeKernel`, then wire
`BackgroundDeviceVm.enqueueEvent` to call it when daemon mode is active.

**Tech Stack:** Kotlin/JVM, JNI, Rust native `ckl-vm`, CKVM image tests, Gradle.

---

### Task 1: Native Daemon Event Binding

**Files:**
- Modify: `native/ckl-vm/src/device_daemon.rs`
- Modify: `native/ckl-vm/src/runtime_kernel.rs`
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [x] **Step 1: Write failing JNI daemon event test**

Add a test that boots a daemon image which blocks on `events::pull("char")`, enqueues a daemon event, then verifies the
image reaches `system::log("char:x")`.

- [x] **Step 2: Run test and verify it fails**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests '*NativeImageVmBindingsJniTest.nativeDeviceDaemonEventsWakeImagesWhenLibraryIsConfigured' --rerun-tasks
```

Expected: FAIL because `enqueueDeviceDaemonEvent` does not exist.

- [x] **Step 3: Implement Rust and Kotlin binding**

Add `DeviceDaemon.enqueue_event`, JNI `enqueueDeviceDaemonEventNative`, Kotlin `NativeVmBindings.enqueueDeviceDaemonEvent`,
and Rust kernel wakeups for `runtime::poll` waiters.

- [x] **Step 4: Run test and verify it passes**

Run the same Gradle command. Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add native/ckl-vm/src/device_daemon.rs native/ckl-vm/src/runtime_kernel.rs native/ckl-vm/src/jni.rs modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt docs/superpowers/specs/2026-05-10-native-daemon-event-ingress-design.md docs/superpowers/plans/2026-05-10-native-daemon-event-ingress.md
git commit -m "feat: add native daemon event ingress"
```

### Task 2: BackgroundDeviceVm Daemon Event Wiring

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [x] **Step 1: Write failing Kotlin wiring test**

Extend the fake daemon bindings to record event enqueue calls, then assert `BackgroundDeviceVm.enqueueEvent` forwards
accepted events to the daemon when `ckl.vm.native.daemon=true`.

- [x] **Step 2: Run test and verify it fails**

Run:

```bash
./gradlew :core:test --tests '*BackgroundDeviceVmTest.enqueueEventForwardsAcceptedEventsToNativeDaemon' --rerun-tasks
```

Expected: FAIL because `NativeDaemonBindings` has no event enqueue method and `BackgroundDeviceVm` does not call it.

- [x] **Step 3: Implement Kotlin wiring**

Add `enqueueEvent` to `NativeDeviceDaemonRuntime` and `NativeDaemonBindings`, delegate to `NativeVmBindings`, and call
it from `BackgroundDeviceVm.enqueueEvent`.

- [x] **Step 4: Run verification**

Run:

```bash
./gradlew :core:test --tests '*BackgroundDeviceVmTest.enqueueEventForwardsAcceptedEventsToNativeDaemon' --rerun-tasks
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.daemon=true :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingReportTest.nativeDaemonSmokeWorkloadRecordsDaemonMetrics' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt docs/superpowers/plans/2026-05-10-native-daemon-event-ingress.md
git commit -m "feat: forward vm events to native daemon"
```
