# Native Standalone Kernel API Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the old Kotlin/JNI standalone native-kernel API while keeping the Rust daemon-owned kernel internals.

**Architecture:** Kotlin should only expose daemon bindings plus the image-runner bindings still needed by `NativeImageVmRunner`. The Rust JNI layer should keep daemon handles and image handles, but stop storing standalone kernel handles. Rust `DeviceRuntimeKernel` stays available to `DeviceDaemon` and Rust tests.

**Tech Stack:** Kotlin, Gradle, JNI, Rust, `ckl-vm`, CKIM image runner.

---

## File Structure

- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt`
  - Remove `NativeDeviceKernelProvider` casts and standalone kernel attach/wait branches.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`
  - Remove standalone native IPC fast path.
- Delete `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/NativeDeviceKernelProvider.kt`
  - Remove obsolete runtime-to-kernel handle contract.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt`
  - Stop implementing `NativeDeviceKernelProvider`.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
  - Remove `nativeDeviceKernelHandle` and `nativeWorkingDirectory` constructor arguments from `VmRuntime`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
  - Keep image-runner and daemon methods.
  - Remove standalone kernel wrapper methods, standalone scheduler data classes, and standalone native external declarations.
- Modify `native/ckl-vm/src/jni.rs`
  - Remove standalone kernel handle registry and standalone kernel JNI exports.
  - Keep daemon JNI exports and `shared_device_daemon_kernel_handle`.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerTest.kt`
  - Remove kernel-aware runner tests and fake runtime.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`
  - Remove tests that only exercise deleted standalone kernel bindings.
  - Keep daemon and image-runner tests.

---

### Task 1: Remove Kotlin Runtime Kernel Provider

**Files:**
- Delete: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/NativeDeviceKernelProvider.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`

- [ ] **Step 1: Delete the obsolete interface**

Remove the file:

```bash
git rm modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/NativeDeviceKernelProvider.kt
```

- [ ] **Step 2: Update `VmRuntime` declaration**

In `VmRuntime.kt`, remove this import:

```kotlin
import ru.lazyhat.compukterkraft.lang.runtime.NativeDeviceKernelProvider
```

Remove these constructor parameters:

```kotlin
override val nativeDeviceKernelHandle: Long = 0L,
override val nativeWorkingDirectory: String = "",
```

Change the class tail from:

```kotlin
) : DeviceRuntime,
    NativeDeviceKernelProvider {
    override val nativeProcessId: Int = processId
```

to:

```kotlin
) : DeviceRuntime {
```

- [ ] **Step 3: Update `BackgroundDeviceVm.createRuntime`**

Remove these arguments from the `VmRuntime(...)` call:

```kotlin
nativeDeviceKernelHandle = 0L,
nativeWorkingDirectory = workingDirectory,
```

- [ ] **Step 4: Run compile check**

Run:

```bash
./gradlew :core:compileKotlin :compiler:compileKotlin
```

Expected: compile fails only on remaining references to `NativeDeviceKernelProvider` in `NativeImageVmRunner`, `RuntimeHostBridge`, or tests.

---

### Task 2: Make `NativeImageVmRunner` Standalone-Image Only

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerTest.kt`

- [ ] **Step 1: Remove provider import and attach branch**

In `NativeImageVmRunner.kt`, remove:

```kotlin
import ru.lazyhat.compukterkraft.lang.runtime.NativeDeviceKernelProvider
```

Delete this block inside `run`:

```kotlin
nativeWorkingDirectoryOrNull(runtime)?.let { bindings.setImageWorkingDirectory(handle, it) }
nativeKernelProviderOrNull(runtime)?.let { provider ->
    bindings.attachImageToKernel(handle, provider.nativeDeviceKernelHandle)
    if (provider.nativeProcessId > 0) {
        check(bindings.attachProcessImage(provider.nativeDeviceKernelHandle, provider.nativeProcessId, handle)) {
            "Native image VM failed to attach image handle $handle to process ${provider.nativeProcessId}"
        }
    }
}
```

- [ ] **Step 2: Simplify waits**

Replace `WaitPoll` handling with:

```kotlin
is NativeVmSignal.WaitPoll -> {
    runtime.yield()
}
```

Replace `WaitProcess` handling with:

```kotlin
is NativeVmSignal.WaitProcess -> {
    runtime.yield()
}
```

Delete helper methods:

```kotlin
private fun kernelHandleOrNull(runtime: DeviceRuntime): Long?
private fun nativeKernelProviderOrNull(runtime: DeviceRuntime): NativeDeviceKernelProvider?
private fun nativeWorkingDirectoryOrNull(runtime: DeviceRuntime): String?
```

Delete constants:

```kotlin
private const val NATIVE_POLL_WAIT_TIMEOUT_MILLIS = 50L
private const val NATIVE_PROCESS_WAIT_TIMEOUT_MILLIS = 50L
```

- [ ] **Step 3: Update runner tests**

In `NativeImageVmRunnerTest.kt`, remove:

```kotlin
import ru.lazyhat.compukterkraft.lang.runtime.NativeDeviceKernelProvider
```

Delete tests:

```kotlin
fun attachesImageToNativeKernelWhenRuntimeProvidesKernelHandle()
fun waitPollParksOnNativeKernelWakeWithoutResumingImage()
fun waitPollYieldsAfterNativeKernelWaitTimeout()
fun waitProcessParksOnNativeKernelWakeWithoutResumingImage()
fun waitProcessYieldsAfterNativeKernelWaitTimeout()
```

Delete fake types that only support those tests:

```kotlin
private class KernelAwareRuntime
private data class ProcessImageAttachment
private data class WaitCall
```

Remove `RecordingBindings` fields and methods that only support standalone kernel attachment/wait assertions:

```kotlin
val attachments
val processImageAttachments
val workingDirectories
val waitForDeviceWakeCalls
val waitForProcessWakeCalls
var waitForDeviceWakeResult
var waitForProcessWakeResult
override fun attachImageToKernel(...)
override fun attachProcessImage(...)
override fun setImageWorkingDirectory(...)
override fun waitForDeviceWake(...)
override fun waitForProcessWake(...)
```

- [ ] **Step 4: Commit Kotlin runner cleanup**

Run:

```bash
./gradlew :compiler:test --tests "*NativeImageVmRunnerTest" --rerun-tasks
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerTest.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/NativeDeviceKernelProvider.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt
git commit -m "Remove standalone native kernel provider path"
```

Expected: runner tests pass after test cleanup.

---

### Task 3: Remove Standalone Native IPC Fast Path

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`

- [ ] **Step 1: Simplify IPC read**

Remove this import:

```kotlin
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmBindings
```

Replace the `"read"` branch from:

```kotlin
"read" -> {
    measuredHostCallWait("ipc", "read") {
        readNativeIpc(arguments[0].asInt())?.let(VmValue::StringValue) ?:
        VmValue.StringValue(runtime.ipc.read(arguments[0].asInt()))
    }
}
```

with:

```kotlin
"read" -> {
    measuredHostCallWait("ipc", "read") {
        VmValue.StringValue(runtime.ipc.read(arguments[0].asInt()))
    }
}
```

Delete:

```kotlin
private suspend fun readNativeIpc(channel: Int): String?
private const val NATIVE_IPC_READ_WAIT_TIMEOUT_MILLIS = 50L
```

- [ ] **Step 2: Run bridge tests**

Run:

```bash
./gradlew :compiler:test --tests "*RuntimeHostBridge*" --rerun-tasks
```

Expected: bridge tests pass without native bindings.

- [ ] **Step 3: Commit bridge cleanup**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt
git commit -m "Remove standalone native IPC bridge path"
```

---

### Task 4: Remove Standalone Kotlin Bindings

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [ ] **Step 1: Shrink `NativeVmBindingsFacade`**

Keep only these methods in `NativeVmBindingsFacade`:

```kotlin
fun createImage(libraryPath: String, image: ByteArray, instructionBudget: Int): Long
fun runImageUntilSignal(handle: Long): ByteArray
fun resumeImageWith(handle: Long, value: ByteArray)
fun freeImage(handle: Long)
```

- [ ] **Step 2: Remove standalone data classes**

Delete:

```kotlin
data class NativeProcessSchedulerTick
data class NativeDeviceExecutionQuota
data class NativeDeviceSchedulerDryRun
data class NativeDeviceSchedulerStep
```

Keep:

```kotlin
data class NativeDeviceDaemonTickSummary
data class NativeDeviceDaemonBootSummary
data class NativeDeviceDaemonHostRequest
```

- [ ] **Step 3: Remove standalone wrapper methods from `NativeVmBindings`**

Delete all methods whose receiver is a standalone `kernelHandle` or standalone `handle`:

```kotlin
createDeviceKernel
freeDeviceKernel
enqueueDeviceEvent
writeDeviceIpc
tryReadDeviceIpc
deviceKernelWakeSequence
waitForDeviceWake
waitForProcessWake
registerProcess
completeProcess
markProcessRunnable
markProcessWaitingForProcess
markProcessWaitingForEvent
markProcessWaitingForIpc
markProcessSleeping
markProcessCrashed
processSchedulerTick
addDeviceExecutionQuota
runDeviceSchedulerDryRun
runDeviceSchedulerStep
attachProcessImage
attachImageToKernel
setImageWorkingDirectory
attachNativeFilesystem
attachNativeDisplay
detachNativeDisplay
nativeDisplayFillRect
nativeDisplayPresent
drainNativeDisplayFrames
displayWakeSequence
waitForDisplayWake
```

Keep daemon methods and image lifecycle methods.

- [ ] **Step 4: Remove standalone external declarations**

Delete external declarations for the removed wrappers:

```kotlin
createDeviceKernelNative
freeDeviceKernelNative
enqueueDeviceEventNative
writeDeviceIpcNative
tryReadDeviceIpcNative
deviceKernelWakeSequenceNative
waitForDeviceWakeNative
waitForProcessWakeNative
registerProcessNative
attachProcessImageNative
completeProcessNative
markProcessRunnableNative
markProcessWaitingForProcessNative
markProcessWaitingForEventNative
markProcessWaitingForIpcNative
markProcessSleepingNative
markProcessCrashedNative
processSchedulerTickNative
addDeviceExecutionQuotaNative
runDeviceSchedulerDryRunNative
runDeviceSchedulerStepNative
displayWakeSequenceNative
waitForDisplayWakeNative
attachImageToKernelNative
setImageWorkingDirectoryNative
attachNativeFilesystemNative
attachNativeDisplayNative
detachNativeDisplayNative
nativeDisplayFillRectNative
nativeDisplayPresentNative
drainNativeDisplayFramesNative
```

- [ ] **Step 5: Remove obsolete decoder helpers**

Delete conversion helpers that only decode removed standalone data classes:

```kotlin
LongArray.toNativeProcessSchedulerTick()
LongArray.toNativeDeviceExecutionQuota()
LongArray.toNativeDeviceSchedulerDryRun()
LongArray.toNativeDeviceSchedulerStep()
```

- [ ] **Step 6: Update JNI tests**

In `NativeImageVmBindingsJniTest.kt`, remove tests that reference deleted methods. Keep tests that reference:

```kotlin
createImage
runImageUntilSignal
resumeImageWith
freeImage
createDeviceDaemon
freeDeviceDaemon
tickDeviceDaemon
refillDeviceDaemonQuota
runDeviceDaemonReady
bootDeviceDaemon
drainDeviceDaemonHostRequests
completeDeviceDaemonHostRequest
completeDeviceDaemonCompileProgram
enqueueDeviceDaemonEvent
attachDeviceDaemonFilesystem
attachDeviceDaemonDisplay
detachDeviceDaemonDisplay
drainDeviceDaemonDisplayFrames
deviceDaemonDisplayWakeSequence
waitForDeviceDaemonDisplayWake
```

- [ ] **Step 7: Run compiler tests**

Run:

```bash
./gradlew :compiler:test --rerun-tasks
```

Expected: Kotlin compiler module passes after deleted tests are removed.

- [ ] **Step 8: Commit Kotlin binding cleanup**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt
git commit -m "Remove standalone native kernel Kotlin bindings"
```

---

### Task 5: Remove Standalone Rust JNI Exports

**Files:**
- Modify: `native/ckl-vm/src/jni.rs`

- [ ] **Step 1: Remove standalone kernel registry imports and statics**

Remove unused imports after Kotlin binding cleanup:

```rust
use crate::runtime_kernel::DeviceRuntimeKernel;
```

Change:

```rust
type SharedDeviceRuntimeKernel = Arc<DeviceRuntimeKernelHandle>;

static NEXT_DEVICE_KERNEL_HANDLE: AtomicI64 = AtomicI64::new(1);
static DEVICE_KERNEL_HANDLES: OnceLock<Mutex<HashMap<jlong, SharedDeviceRuntimeKernel>>> =
    OnceLock::new();
static NEXT_DEVICE_DAEMON_HANDLE: AtomicI64 = AtomicI64::new(1);
```

to:

```rust
type SharedDeviceRuntimeKernel = Arc<DeviceRuntimeKernelHandle>;

static NEXT_DEVICE_DAEMON_HANDLE: AtomicI64 = AtomicI64::new(1);
```

- [ ] **Step 2: Remove standalone JNI export functions**

Delete JNI functions whose names match deleted Kotlin externals:

```rust
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_createDeviceKernelNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_freeDeviceKernelNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_enqueueDeviceEventNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_writeDeviceIpcNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_tryReadDeviceIpcNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_deviceKernelWakeSequenceNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_waitForDeviceWakeNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_displayWakeSequenceNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_waitForDisplayWakeNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_waitForProcessWakeNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_attachImageToKernelNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_registerProcessNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_attachProcessImageNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_completeProcessNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_markProcessRunnableNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_markProcessWaitingForProcessNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_markProcessWaitingForEventNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_markProcessWaitingForIpcNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_markProcessSleepingNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_markProcessCrashedNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runDeviceSchedulerDryRunNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runDeviceSchedulerStepNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_processSchedulerTickNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_attachNativeFilesystemNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_attachNativeDisplayNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_detachNativeDisplayNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_nativeDisplayFillRectNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_nativeDisplayPresentNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_drainNativeDisplayFramesNative
```

- [ ] **Step 3: Remove standalone helper functions**

Delete:

```rust
register_device_kernel_handle
unregister_device_kernel_handle
device_kernel_handles
shared_kernel_handle
lock_kernel_handle
```

Keep:

```rust
shared_device_daemon_kernel_handle
```

- [ ] **Step 4: Run Rust and JNI compile checks**

Run:

```bash
./gradlew :v1_21_1-neoforge:buildRustVmNativeLibrary
./gradlew :compiler:compileKotlin
```

Expected: Rust native library builds and Kotlin externals match the remaining JNI surface.

- [ ] **Step 5: Commit Rust JNI cleanup**

Run:

```bash
git add native/ckl-vm/src/jni.rs
git commit -m "Remove standalone native kernel JNI exports"
```

---

### Task 6: Final Verification And Dead-Name Scan

**Files:**
- No source changes expected unless verification finds missed references.

- [ ] **Step 1: Scan deleted API names**

Run:

```bash
rg -n "NativeDeviceKernelProvider|nativeDeviceKernelHandle|createDeviceKernel|freeDeviceKernel|attachImageToKernel|attachNativeFilesystem|attachNativeDisplay|runDeviceSchedulerDryRun|runDeviceSchedulerStep|processSchedulerTickNative|DEVICE_KERNEL_HANDLES|NEXT_DEVICE_KERNEL_HANDLE" modules native/ckl-vm/src native/ckl-vm/tests -g '!**/build/**'
```

Expected: no production references. Mentions in historical docs/specs are acceptable only outside the scanned source paths.

- [ ] **Step 2: Run full relevant tests**

Run:

```bash
./gradlew :compiler:test --rerun-tasks
./gradlew :core:test --rerun-tasks
./gradlew :v1_21_1-neoforge:test --tests "*BackgroundDeviceVmTest*" --tests "*RuntimeVmProfilingReportFormatterTest" --tests "*RuntimeVmProfilingProfileCodecTest" --rerun-tasks
```

Expected: all tests pass.

- [ ] **Step 3: Commit any verification fixes**

If verification required small fixes, commit them:

```bash
git add <changed-files>
git commit -m "Fix standalone native kernel cleanup verification"
```

- [ ] **Step 4: Report final state**

Report:

- commits created;
- verification commands and outcomes;
- any intentionally remaining Rust kernel internals.
