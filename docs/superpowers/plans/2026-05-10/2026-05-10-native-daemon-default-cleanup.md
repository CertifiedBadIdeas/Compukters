# Native Daemon Default Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `BackgroundDeviceVm` use the Rust native daemon by default and remove stale Kotlin/non-daemon runtime fallback branches.

**Architecture:** `BackgroundDeviceVm` owns one `NativeDeviceDaemonRuntime` for all production execution. Kotlin remains the host bridge for compilation, host requests, filesystem/world integration, and client display frame delivery. Old property-gated selection between daemon, standalone native kernel, and Kotlin program runner is removed.

**Tech Stack:** Kotlin, coroutines, Gradle, CKL frontend/image lowering, JNI native daemon bindings, Rust native VM.

---

## File Structure

- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
  - Remove property-gated runtime selection.
  - Remove standalone native kernel/display/process bridge fields.
  - Make daemon creation, boot, display, event ingress, and scheduler refill direct.
- Modify `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`
  - Update daemon tests to no longer set `ckl.vm.native.daemon`.
  - Update display tests to no longer set `ckl.vm.native.display` or use `nativeDisplayEnabled`.
  - Remove or rewrite tests that only validate the old standalone native kernel dry-run path.
  - Inject `RecordingNativeDaemonBindings` into tests that should not load the real native library.
- Review `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt`
  - Keep only if still needed for host/runtime bridge contracts.
  - Remove no code here unless compilation reveals a now-unused constructor path.
- Review `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/NativeDisplayRegistry.kt`
  - Remove only if no production or test references remain after `BackgroundDeviceVm` cleanup.
- Review `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeVmProcessBridge.kt`
  - Remove only if no production or test references remain after `BackgroundDeviceVm` cleanup.

---

### Task 1: Add Default Daemon Regression Tests

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] **Step 1: Rename and update the daemon boot test**

Change `bootUsesNativeDaemonWhenConfigured` to `bootUsesNativeDaemonByDefault` and remove all `System.setProperty("ckl.vm.native.daemon", "true")` / `System.clearProperty("ckl.vm.native.daemon")` wrapping.

The test body should directly create the VM with fake bindings:

```kotlin
@Test
fun bootUsesNativeDaemonByDefault() {
    runtimeTestWorkspace("vm-native-daemon-boot") { workspace ->
        val daemonBindings = RecordingNativeDaemonBindings()
        val vm = backgroundVmWithNativeDaemonBindings(workspace.host, daemonBindings)

        assertTrue(vm.boot())
        vm.requestSlice(serverTick = 1)
        runBlocking {
            repeat(20) {
                if (daemonBindings.runReadyMaxTurns.isNotEmpty()) return@runBlocking
                kotlinx.coroutines.delay(5)
            }
        }

        assertTrue(daemonBindings.createdDaemons.isNotEmpty())
        assertTrue(daemonBindings.bootedImages.isNotEmpty())
        assertTrue(daemonBindings.refillQuotaCalls.isNotEmpty())
        assertTrue(daemonBindings.runReadyMaxTurns.isNotEmpty())
    }
}
```

- [ ] **Step 2: Rename and update daemon display tests**

Remove property setup from:

- `nativeDaemonDisplayFramesAreMirroredAndDrained`;
- `nativeDaemonDisplayWakePumpIsSupportedAndDelegatesWakeCalls`.

The expected assertions stay the same. These tests should pass only when display calls go directly to `NativeDeviceDaemonRuntime`.

- [ ] **Step 3: Add a fail-fast binding test**

Add this fake binding near `RecordingNativeDaemonBindings`:

```kotlin
private class FailingNativeDaemonBindings : RecordingNativeDaemonBindings() {
    override fun createDeviceDaemon(
        maxEventQueueSize: Int,
        maxBufferedBytesPerChannel: Int,
        instructionBudget: Int,
    ): Long = error("native daemon unavailable")
}
```

Make `RecordingNativeDaemonBindings` `open`, and make `createDeviceDaemon` `open` so the override compiles.

Add this test:

```kotlin
@Test
fun constructionFailsFastWhenNativeDaemonCannotBeCreated() {
    runtimeTestWorkspace("vm-native-daemon-fail-fast") { workspace ->
        val failure =
            assertFailsWith<IllegalStateException> {
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    firmwareLoader = StaticFirmwareLoader("pub fun main() { }"),
                    nativeDaemonBindings = FailingNativeDaemonBindings(),
                )
            }

        assertTrue(failure.message?.contains("native daemon unavailable") == true)
    }
}
```

Add imports if missing:

```kotlin
import kotlin.test.assertFailsWith
```

- [ ] **Step 4: Run tests and verify RED**

Run:

```bash
./gradlew :core:test --tests "*BackgroundDeviceVmTest.bootUsesNativeDaemonByDefault" --tests "*BackgroundDeviceVmTest.nativeDaemonDisplayFramesAreMirroredAndDrained" --tests "*BackgroundDeviceVmTest.nativeDaemonDisplayWakePumpIsSupportedAndDelegatesWakeCalls" --tests "*BackgroundDeviceVmTest.constructionFailsFastWhenNativeDaemonCannotBeCreated" --rerun-tasks
```

Expected before implementation:

- boot/default daemon test fails because daemon is still property-gated;
- display daemon tests fail without the property;
- fail-fast test may pass if construction already throws, but the set proves the new expected behavior.

---

### Task 2: Make Daemon Creation Unconditional

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`

- [ ] **Step 1: Remove property fields and old native availability field**

Delete constructor parameter:

```kotlin
private val nativeDisplayEnabled: Boolean = System.getProperty("ckl.vm.native.display") == "true",
```

Delete fields:

```kotlin
private val nativeLibraryAvailable: Boolean = NativeImageVmRunner.isDefaultLibraryAvailable()
private val nativeDaemonEnabled: Boolean =
    System.getProperty("ckl.vm.native.daemon") == "true" &&
        (nativeDaemonBindings !== NativeVmDaemonBindings || nativeLibraryAvailable)
```

Remove the now-unused `NativeImageVmRunner` import.

- [ ] **Step 2: Replace conditional daemon handle creation**

Replace the `nativeDeviceDaemonHandle` initializer with direct creation:

```kotlin
private val nativeDeviceDaemonHandle: Long =
    nativeDaemonBindings.createDeviceDaemon(
        maxEventQueueSize = profile.resources.queues.eventQueueSlots,
        maxBufferedBytesPerChannel = profile.resources.queues.ipcChannelBytes,
        instructionBudget = profile.resources.cpu.instructionsPerSlice,
    ).also { handle ->
        effectiveNativeFilesystemRoot?.let { root ->
            nativeDaemonBindings.attachDeviceDaemonFilesystem(
                daemonHandle = handle,
                rootPath = root.toAbsolutePath().normalize().toString(),
                quotaBytes = profile.resources.storage.diskBytes,
            )
        }
    }
```

Use `Long`, not `Long?`, because daemon creation is mandatory.

- [ ] **Step 3: Replace nullable daemon runtime**

Replace:

```kotlin
private val nativeDaemonRuntime: NativeDeviceDaemonRuntime? =
    nativeDeviceDaemonHandle?.let { handle ->
        NativeDeviceDaemonRuntime(...)
    }
```

with:

```kotlin
private val nativeDaemonRuntime: NativeDeviceDaemonRuntime =
    NativeDeviceDaemonRuntime(
        daemonHandle = nativeDeviceDaemonHandle,
        profile = profile,
        bindings = nativeDaemonBindings,
        runtimeMetricsCollector = runtimeMetricsCollector,
        hostBridge = ::handleNativeDaemonHostRequest,
        compileBridge = ::handleNativeDaemonCompileProgram,
    )
```

- [ ] **Step 4: Remove standalone native kernel fields**

Delete:

```kotlin
private val nativeDeviceKernelHandle: Long? = ...
private val nativeDisplayRegistry: NativeDisplayRegistry? = ...
private val nativeProcessBridge: NativeProcessBridge =
    nativeDeviceKernelHandle?.let(::NativeVmProcessBridge) ?: NoOpNativeProcessBridge
```

Remove imports that become unused:

```kotlin
import ru.lazyhat.compukterkraft.core.device.vm.display.NativeDisplayRegistry
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmBindings
```

If `NativeProcessBridge` is only referenced by deleted code, remove its import too.

- [ ] **Step 5: Update process manager construction**

Replace:

```kotlin
nativeProcessBridge = nativeProcessBridge,
```

with:

```kotlin
nativeProcessBridge = NoOpNativeProcessBridge,
```

This keeps the constructor stable while daemon process execution remains daemon-owned.

- [ ] **Step 6: Run compile and targeted tests**

Run:

```bash
./gradlew :core:test --tests "*BackgroundDeviceVmTest.bootUsesNativeDaemonByDefault" --tests "*BackgroundDeviceVmTest.constructionFailsFastWhenNativeDaemonCannotBeCreated" --rerun-tasks
```

Expected: these tests pass or reveal compile errors from nullable daemon call sites that Task 3 will fix.

---

### Task 3: Remove Non-Daemon Boot And Scheduler Branches

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`

- [ ] **Step 1: Remove the old single-process runtime field**

Delete:

```kotlin
private var runner: Job? = null
private val runtime: VmRuntime = createRuntime(processId = 1, parentProcessId = 0, workingDirectory = "", argument = "")
```

Keep `createRuntime` only if `VmProcessManager` still needs it for non-daemon-compatible compile/test contracts. If the compiler reports it unused after Task 4, delete it in Task 6.

- [ ] **Step 2: Simplify boot**

Replace `boot()` with:

```kotlin
override fun boot(): Boolean = bootNativeDaemon(nativeDaemonRuntime)
```

Remove the old coroutine body that compiles and runs `DeviceProgram`.

- [ ] **Step 3: Simplify requestSlice**

Replace `requestSlice` with:

```kotlin
override fun requestSlice(serverTick: Long) {
    stateManager.updateCurrentTick(serverTick)
    nativeDaemonRuntime.refillQuota(serverTick)
    wakeNativeDaemonExecutor()
}
```

Delete all dry-run code using `NativeVmBindings.addDeviceExecutionQuota` and `NativeVmBindings.runDeviceSchedulerDryRun`.

- [ ] **Step 4: Simplify stop**

Replace native frame draining in `stopInternal` with direct daemon draining:

```kotlin
if (!nativeDeviceKernelFreed) {
    nativeDeviceKernelLock.read {
        if (!nativeDeviceKernelFreed) {
            stoppedNativeDisplayFrames.addAll(nativeDaemonRuntime.drainDisplayFrames())
        }
    }
}
```

Replace free logic:

```kotlin
nativeDaemonBindings.freeDeviceDaemon(nativeDeviceDaemonHandle)
nativeDeviceKernelFreed = true
```

Remove `runner?.cancel()` and `runner = null`.

- [ ] **Step 5: Run compile**

Run:

```bash
./gradlew :core:compileKotlin
```

Expected: compilation points to remaining nullable `nativeDaemonRuntime?` or removed `runner` references, which Task 4 handles.

---

### Task 4: Make Display And Event Paths Daemon-Only

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`

- [ ] **Step 1: Update event ingress**

Replace the body of accepted event mirroring with:

```kotlin
nativeDeviceKernelLock.read {
    if (!nativeDeviceKernelFreed && nativeDaemonRuntime.enqueueEvent(event)) {
        wakeNativeDaemonExecutor()
    }
}
```

Keep `eventManager.enqueueEvent(event)` for now so `snapshot().queuedEvents` and any Kotlin host-facing event contracts remain stable.

- [ ] **Step 2: Update display attach/resize/detach**

In `attachDisplay`, replace native mirroring with:

```kotlin
nativeDeviceKernelLock.read {
    if (!nativeDeviceKernelFreed) {
        nativeDaemonRuntime.attachDisplay(displayId, width, height, pixelFormat)
    }
}
```

In `resizeDisplay`, use the same direct daemon attach call.

In `detachDisplay`, replace native mirroring with:

```kotlin
nativeDeviceKernelLock.read {
    if (!nativeDeviceKernelFreed) {
        nativeDaemonRuntime.detachDisplay(displayId)
    }
}
```

Keep `displayRegistry.attach/resize/detach` initially for metadata and existing host API behavior.

- [ ] **Step 3: Update frame drain and wake helpers**

Replace `drainDisplayFrames()` native branch with:

```kotlin
val nativeFrames =
    nativeDeviceKernelLock.read {
        if (!nativeDeviceKernelFreed) {
            nativeDaemonRuntime.drainDisplayFrames()
        } else {
            emptyList()
        }
    }
displayRegistry.drainFrames()
return buildList {
    addAll(stoppedNativeDisplayFrames)
    stoppedNativeDisplayFrames.clear()
    addAll(nativeFrames)
}
```

Replace helper methods with:

```kotlin
fun supportsNativeDisplayFramePump(): Boolean =
    nativeDeviceKernelLock.read { !nativeDeviceKernelFreed }

fun nativeDisplayWakeSequence(): Long? =
    nativeDeviceKernelLock.read {
        if (!nativeDeviceKernelFreed) nativeDaemonRuntime.displayWakeSequence() else null
    }

fun waitForNativeDisplayWake(
    observedWakeSequence: Long,
    timeoutMillis: Long,
): Long? =
    nativeDeviceKernelLock.read {
        if (!nativeDeviceKernelFreed) {
            nativeDaemonRuntime.waitForDisplayWake(observedWakeSequence, timeoutMillis)
        } else {
            null
        }
    }

fun drainNativeDisplayFrameBytes(): ByteArray? =
    nativeDeviceKernelLock.read {
        if (!nativeDeviceKernelFreed) nativeDaemonRuntime.drainDisplayFrameBytes() else null
    }
```

- [ ] **Step 4: Update IPC write**

If `writeIpc` still targets only the standalone native kernel, replace it with Kotlin IPC for now:

```kotlin
ipcRegistry.write(channel, text)
```

This method belongs to the old `VmRuntime` path; daemon-owned programs should use native daemon IPC internally. If `createRuntime` becomes unused in Task 6, remove this method with it.

- [ ] **Step 5: Update daemon executor helpers**

Replace:

```kotlin
if (nativeDaemonRuntime == null || daemonExecutor?.isActive == true) return
```

with:

```kotlin
if (daemonExecutor?.isActive == true) return
```

Replace:

```kotlin
if (nativeDaemonRuntime != null) {
    daemonWakeSignal.trySend(Unit)
}
```

with:

```kotlin
daemonWakeSignal.trySend(Unit)
```

- [ ] **Step 6: Run targeted daemon/display tests**

Run:

```bash
./gradlew :core:test --tests "*BackgroundDeviceVmTest.bootUsesNativeDaemonByDefault" --tests "*BackgroundDeviceVmTest.enqueueEventForwardsAcceptedEventsToNativeDaemon" --tests "*BackgroundDeviceVmTest.nativeDaemonExecutorRunsAfterAcceptedEventWithoutWaitingForNextSlice" --tests "*BackgroundDeviceVmTest.nativeDaemonDisplayFramesAreMirroredAndDrained" --tests "*BackgroundDeviceVmTest.nativeDaemonDisplayWakePumpIsSupportedAndDelegatesWakeCalls" --rerun-tasks
```

Expected: all listed tests pass.

---

### Task 5: Remove Or Rewrite Old Property-Gated Tests

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] **Step 1: Remove `System.setProperty("ckl.vm.native.daemon", "true")` usage**

Search:

```bash
rg -n "ckl\\.vm\\.native\\.daemon" modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt
```

Expected after edits: no matches.

- [ ] **Step 2: Remove `nativeDisplayEnabled` constructor usage**

Delete `nativeDisplayEnabled = true` arguments from tests.

Search:

```bash
rg -n "nativeDisplayEnabled|ckl\\.vm\\.native\\.display" modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt
```

Expected after edits: no matches.

- [ ] **Step 3: Remove obsolete standalone native kernel quota/dry-run tests**

Delete these tests because they validate the removed non-daemon native kernel path:

```kotlin
requestSliceMirrorsExecutionQuotaToNativeKernelWhenConfigured
requestSliceRecordsNativeSchedulerDryRunWhenConfigured
```

Replace scheduler coverage with daemon default assertions already covered by:

- `bootUsesNativeDaemonByDefault`;
- `requestSliceDoesNotGateSharedQuotaOnSleepState`, updated in Step 4.

- [ ] **Step 4: Update `requestSliceDoesNotGateSharedQuotaOnSleepState`**

Construct the VM with fake daemon bindings:

```kotlin
val daemonBindings = RecordingNativeDaemonBindings()
val vm =
    BackgroundDeviceVm(
        deviceId = 1,
        profile = firmwareTestProfile(),
        dispatcher = Dispatchers.Default,
        labelProvider = { null },
        logger = DeviceVmLogger { },
        workspace = workspace.host,
        runtimeMetricsCollector = metrics,
        nativeDaemonBindings = daemonBindings,
    )
```

Assert daemon quota refill instead of Kotlin execution quota:

```kotlin
assertEquals(listOf(Triple(1L, firmwareTestProfile().resources.cpu.wallTimeGuardNanosPerSlice, 1L)), daemonBindings.refillQuotaCalls)
assertEquals(1, snapshot.vm.sliceRequests)
assertEquals(0, snapshot.vm.sleepGatedSliceRequests)
```

If `sliceRequests` is now recorded by the daemon executor instead of `requestSlice`, assert only the daemon binding calls and remove legacy execution quota assertions from this test.

- [ ] **Step 5: Run full BackgroundDeviceVmTest**

Run:

```bash
./gradlew :core:test --tests "*BackgroundDeviceVmTest" --rerun-tasks
```

Expected: all `BackgroundDeviceVmTest` tests pass.

---

### Task 6: Clean Up Dead Production References

**Files:**
- Modify as needed:
  - `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
  - `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/NativeDisplayRegistry.kt`
  - `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeVmProcessBridge.kt`
  - `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`

- [ ] **Step 1: Search for removed property gates**

Run:

```bash
rg -n "ckl\\.vm\\.native\\.daemon|ckl\\.vm\\.native\\.display|nativeDisplayEnabled|nativeDaemonEnabled" modules docs -g '!**/build/**'
```

Expected:

- no production references;
- test/profiling references only if they intentionally run daemon workloads by setting properties outside `BackgroundDeviceVm`.

- [ ] **Step 2: Search for standalone native kernel production references**

Run:

```bash
rg -n "createDeviceKernel|attachNativeFilesystem|attachImageToKernel|NativeDisplayRegistry|NativeVmProcessBridge|nativeDeviceKernelHandle" modules/core/src/main modules/compiler/src/main -g '!**/build/**'
```

Remove files or methods only when there are no remaining production references.

Do not remove JNI methods that are still used by compiler JNI tests unless this task also updates those tests to daemon equivalents.

- [ ] **Step 3: Remove unused imports and fields**

Run:

```bash
./gradlew :core:compileKotlin
```

Fix compile errors caused by unused/deleted symbols. Kotlin warnings are acceptable only if they existed before this cleanup.

- [ ] **Step 4: Commit production cleanup**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt
git commit -m "Make native daemon the default device runtime"
```

Add any additional deleted files to `git add` before committing if this task removed them.

---

### Task 7: Full Verification

**Files:**
- No code edits unless verification exposes failures.

- [ ] **Step 1: Run core tests**

Run:

```bash
./gradlew :core:test --rerun-tasks
```

Expected: build successful.

- [ ] **Step 2: Run compiler tests**

Run:

```bash
./gradlew :compiler:test --rerun-tasks
```

Expected: build successful.

- [ ] **Step 3: Run NeoForge background/display tests**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests "*BackgroundDeviceVmTest" --tests "*NativeDisplay*" --tests "*RuntimeVmProfiling*" --rerun-tasks
```

Expected: build successful or no matching tests for a filter. If a test still depends on property-gated daemon/display opt-in, update it to the daemon-default model.

- [ ] **Step 4: Run Rust daemon tests if JNI/native changes were made**

If this cleanup edits `native/ckl-vm`, run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml
```

Expected: all Rust tests pass.

- [ ] **Step 5: Final dead-reference scan**

Run:

```bash
rg -n "ckl\\.vm\\.native\\.daemon|ckl\\.vm\\.native\\.display|nativeDisplayEnabled|nativeDaemonEnabled" modules/core/src/main modules/core/src/test modules/v1_21_1/v1_21_1-neoforge/src/test -g '!**/build/**'
```

Expected: no `BackgroundDeviceVm` runtime selection references remain.

- [ ] **Step 6: Review diff**

Run:

```bash
git diff --stat HEAD
git diff -- modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt
```

Expected:

- daemon path is direct;
- old non-daemon boot branch is gone;
- no property-gated display branch remains;
- no unrelated formatting churn.

- [ ] **Step 7: Commit final fixes**

If verification required follow-up changes, commit them:

```bash
git add <changed-files>
git commit -m "Clean up daemon default fallout"
```

Skip this step if Task 6 already committed all implementation changes and verification produced no diff.

---

## Self-Review

- Spec coverage: The plan covers daemon default creation, fail-fast behavior, removal of `ckl.vm.native.daemon`, removal of `ckl.vm.native.display`, non-daemon boot deletion, display daemon-only draining, event daemon ingress, scheduler quota refill, stale test cleanup, and verification.
- Placeholder scan: No placeholder markers or intentionally vague implementation steps remain.
- Type consistency: The plan uses existing types from the current codebase: `BackgroundDeviceVm`, `NativeDeviceDaemonRuntime`, `NativeDaemonBindings`, `NativeDeviceDaemonTickSummary`, `RecordingRuntimeMetricsCollector`, and `DisplayFrameDelta`.
- Execution consistency: The plan keeps fake bindings for tests, avoids requiring the real native library in core unit tests, and only removes secondary files after reference scans prove they are unused.
