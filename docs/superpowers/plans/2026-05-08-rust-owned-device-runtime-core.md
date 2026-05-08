# Rust-Owned Device Runtime Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:
> executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move device-local runtime ownership for events, IPC, and terminal polling from Kotlin into Rust so the image
VM becomes materially more independent and the terminal hot path stops paying generic host-call overhead on every poll.

**Architecture:** Introduce a native device-runtime kernel owned by the Rust library, separate from the per-program
image runner. Kotlin continues to own Minecraft/world integration, filesystem/workspace access, display application, and
high-level process supervision, but event queues, IPC channels, and the `terminal.ck` polling path move behind a narrow
native protocol instead of the current generic `RuntimeHostBridge` host-call loop.

**Tech Stack:** Kotlin/JVM, Rust, JNI, CKVM image ABI, existing runtime profiling tasks, CKL ROM scripts.

---

## File Structure

- `native/ckl-vm/src/runtime_kernel.rs` (new): Rust-owned device-local runtime state shared by all image processes on
  one device. Owns event queue, event payload storage, IPC registry, and combined poll logic.
- `native/ckl-vm/src/image_runner.rs`: remove native handling for `events::*` and `ipc::*` from the generic host-import
  path; add kernel-backed fast path calls and new signal handling when needed.
- `native/ckl-vm/src/signal.rs`: extend the native signal/command protocol for kernel operations that still need host
  mediation.
- `native/ckl-vm/src/jni.rs`: create/free native device kernel handles and expose enqueue/read/write/poll JNI
  entrypoints.
- `native/ckl-vm/src/lib.rs`: export the new kernel module.
- `native/ckl-vm/tests/image_runner.rs`: runner-level Rust coverage for kernel-backed events, IPC, and terminal poll
  semantics.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`: JNI bindings
  for the native device kernel lifecycle and per-runner kernel attachment.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmSignal.kt`: Kotlin decoder
  for any new native VM signal or poll result payloads.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt`: wire the
  runner to a native device kernel and shrink generic `RuntimeHostBridge` use to world-facing operations only.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`: remove `events::*` and
  `ipc::*` from the generic bridge once the native kernel path is live.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`: create one native
  device kernel per device VM, feed it events, and share it with spawned image programs.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/EventManager.kt`: retained only if needed for
  non-native runtimes; otherwise narrowed or removed from the hot path.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/IpcChannelRegistry.kt`: retained only for
  non-native fallback; removed from native path ownership.
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/EventManagerTest.kt`: fallback coverage if a
  non-native path remains.
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/IpcChannelRegistryTest.kt`: fallback coverage
  if a non-native path remains.
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`: device-level
  sharing and lifecycle coverage for the native kernel.
- `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`: JNI
  lifecycle coverage for native kernel handles.
- `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`:
  end-to-end Rust runner coverage for events, IPC, and combined polling.
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`: switch from
  `ipc::tryRead + events::tryPull + yield` polling to one runtime-facing poll primitive.
- `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`:
  source-shape regression for the new terminal polling contract.
-
`modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeProfilingWorkload.kt`:
keep the profiling workloads stable while the terminal loop changes.
- `docs/ARCHITECTURE.md`: update ownership diagrams to show the native device runtime kernel.
- `docs/MACHINE.md`: update runtime/device/process flow notes.
- `docs/PROFILING.md`: document how to compare pre-kernel and post-kernel terminal runs.

## Scope Guardrails

This plan intentionally stops before a full Rust-owned process manager. `VmProcessManager` remains in Kotlin for this
slice, but every image program spawned by it attaches to the same native device kernel so IPC and event ownership can
move now without waiting for a full process-runtime rewrite.

### Task 1: Freeze the Native Device-Kernel Boundary

**Files:**

- Create: `docs/superpowers/specs/2026-05-08-rust-owned-device-runtime-core-design.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/MACHINE.md`
- Test:
  `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [ ] **Step 1: Write the failing architecture/spec regression test**

```kotlin
@Test
fun nativeKernelBindingsExposeDeviceKernelLifecycle() {
    assertTrue(
        NativeVmBindings::class.members.any { it.name == "createDeviceKernel" },
        "NativeVmBindings must expose native device-kernel lifecycle"
    )
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./gradlew :compiler:test --tests '*NativeImageVmBindingsJniTest' --rerun-tasks`
Expected: FAIL because `NativeVmBindings` does not yet expose device-kernel lifecycle calls.

- [ ] **Step 3: Write the design doc before touching runtime code**

```markdown
# Rust-Owned Device Runtime Core Design

## Goal
Move device-local runtime ownership for events, IPC, and terminal polling into Rust.

## Owned by Rust
- event queue and deferred event handling
- event payload capture/lookup for `events::arg*`
- IPC channel registry and bounded buffering
- combined terminal poll primitive

## Still owned by Kotlin
- filesystem/workspace host calls
- display backend application
- process supervision and boot lifecycle
- system integration with the game/mod host
```

- [ ] **Step 4: Add the Kotlin/JNI boundary names that all later tasks must use**

```kotlin
internal object NativeVmBindings {
    external fun createDeviceKernel(maxEventQueueSize: Int, maxBufferedBytesPerChannel: Int): Long
    external fun freeDeviceKernel(handle: Long)
    external fun enqueueDeviceEvent(handle: Long, eventName: String, payload: ByteArray): Boolean
    external fun attachImageToKernel(imageHandle: Long, kernelHandle: Long)
}
```

- [ ] **Step 5: Re-run the binding test and commit the boundary freeze**

Run: `./gradlew :compiler:test --tests '*NativeImageVmBindingsJniTest' --rerun-tasks`
Expected: PASS or at least compile-green once the names/signatures exist.

```bash
git add docs/superpowers/specs/2026-05-08-rust-owned-device-runtime-core-design.md \
  docs/ARCHITECTURE.md docs/MACHINE.md \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt \
  modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt
git commit -m "docs: freeze native device runtime kernel boundary"
```

### Task 2: Add a Shared Native Device Kernel in Rust

**Files:**

- Create: `native/ckl-vm/src/runtime_kernel.rs`
- Modify: `native/ckl-vm/src/lib.rs`
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `native/ckl-vm/tests/image_runner.rs`
- Test:
  `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [ ] **Step 1: Write the failing Rust and JNI tests for kernel lifecycle**

```rust
#[test]
fn device_kernel_accepts_event_and_ipc_setup() {
    let mut kernel = DeviceRuntimeKernel::new(64, 4096);
    assert!(kernel.enqueue_event("boot", vec![]));
    let channel = kernel.open_ipc_channel().unwrap();
    assert!(channel > 0);
}
```

```kotlin
@Test
fun jniCreatesAndFreesDeviceKernel() {
    val handle = NativeVmBindings.createDeviceKernel(64, 4096)
    assertTrue(handle != 0L)
    NativeVmBindings.freeDeviceKernel(handle)
}
```

- [ ] **Step 2: Run the failing tests**

Run: `cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner`
Expected: FAIL because `runtime_kernel.rs` and the new API do not exist.

Run: `./gradlew :compiler:test --tests '*NativeImageVmBindingsJniTest' --rerun-tasks`
Expected: FAIL on missing JNI symbols or missing Kotlin bindings.

- [ ] **Step 3: Implement the shared kernel skeleton**

```rust
pub struct DeviceRuntimeKernel {
    event_queue: VecDeque<QueuedEvent>,
    deferred_events: VecDeque<QueuedEvent>,
    payloads: EventPayloadStore,
    ipc: IpcRegistry,
    max_event_queue_size: usize,
}

impl DeviceRuntimeKernel {
    pub fn new(max_event_queue_size: usize, max_buffered_bytes_per_channel: usize) -> Self { /* ... */ }
    pub fn enqueue_event(&mut self, name: &str, args: Vec<VmValue>) -> bool { /* ... */ }
    pub fn open_ipc_channel(&mut self) -> Result<i32, String> { /* ... */ }
}
```

- [ ] **Step 4: Expose JNI lifecycle and attachment entrypoints**

```rust
#[no_mangle]
pub extern "system" fn Java_..._NativeVmBindings_createDeviceKernel(...) -> jlong { /* ... */ }

#[no_mangle]
pub extern "system" fn Java_..._NativeVmBindings_attachImageToKernel(...) { /* ... */ }
```

- [ ] **Step 5: Run tests and commit the kernel skeleton**

Run: `cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner`
Expected: PASS for the new kernel lifecycle tests.

Run: `./gradlew :compiler:test --tests '*NativeImageVmBindingsJniTest' --rerun-tasks`
Expected: PASS.

```bash
git add native/ckl-vm/src/runtime_kernel.rs native/ckl-vm/src/lib.rs native/ckl-vm/src/jni.rs \
  native/ckl-vm/tests/image_runner.rs \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt \
  modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt
git commit -m "feat: add native device runtime kernel skeleton"
```

### Task 3: Move Event Ownership to the Native Kernel

**Files:**

- Modify: `native/ckl-vm/src/runtime_kernel.rs`
- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] **Step 1: Write the failing end-to-end event ownership tests**

```kotlin
@Test
fun nativeImageRunnerConsumesTryPullFromNativeKernel() = runBlocking {
    val runtime = RecordingRuntime()
    runtime.enqueueEvent(VmEvent("char", listOf("a")))
    NativeImageVmRunner.fromLibraryPath(libraryPath).run(imageUsingTryPull(), runtime)
    assertEquals(listOf("char"), runtime.observedEventNames)
}
```

```kotlin
@Test
fun backgroundDeviceVmFeedsDisplayAttachIntoNativeKernel() {
    val vm = newVm()
    vm.attachDisplay(displayId = 9, width = 96, height = 48)
    assertEquals(1, vm.snapshot().queuedEvents)
}
```

- [ ] **Step 2: Run the targeted tests to verify failure**

Run: `./gradlew :compiler:test --tests '*NativeImageVmRunnerJniTest' --rerun-tasks`
Expected: FAIL because events still flow through `RuntimeHostBridge.invokeEvents(...)`.

Run: `./gradlew :core:test --tests '*BackgroundDeviceVmTest' --rerun-tasks`
Expected: FAIL or require update because event ownership is still Kotlin-only.

- [ ] **Step 3: Implement native-kernel event queue semantics**

```rust
pub fn try_pull_event(&mut self, filter: Option<&str>) -> Option<QueuedEvent> {
    if let Some(event) = self.deferred_match(filter) {
        return Some(event);
    }
    let event = self.next_event_match(filter)?;
    Some(event)
}

pub fn defer_event(&mut self, event: QueuedEvent) {
    self.deferred_events.push_back(event);
}
```

- [ ] **Step 4: Remove generic `events::*` dispatch from the hot path**

```kotlin
private suspend fun invokeHostCall(...): VmValue {
    return when (signal.moduleName) {
        "filesystem", "display", "process", "system" -> bridge.invoke(...)
        "events", "ipc" -> error("events/ipc must not use generic host bridge after native kernel migration")
        else -> bridge.invoke(...)
    }
}
```

- [ ] **Step 5: Run tests and commit the event migration**

Run: `cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner`
Expected: PASS.

Run: `./gradlew :compiler:test --tests '*NativeImageVmRunnerJniTest' --rerun-tasks`
Expected: PASS with event tests green.

Run: `./gradlew :core:test --tests '*BackgroundDeviceVmTest' --rerun-tasks`
Expected: PASS.

```bash
git add native/ckl-vm/src/runtime_kernel.rs native/ckl-vm/src/image_runner.rs \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
  modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt
git commit -m "feat: move runtime events into native device kernel"
```

### Task 4: Move IPC Ownership to the Native Kernel

**Files:**

- Modify: `native/ckl-vm/src/runtime_kernel.rs`
- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/IpcChannelRegistry.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/IpcChannelRegistryTest.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`

- [ ] **Step 1: Write the failing IPC-sharing tests**

```kotlin
@Test
fun spawnedProgramsShareOneNativeKernelIpcRegistry() = runBlocking {
    val runtime = RecordingRuntime()
    NativeImageVmRunner.fromLibraryPath(libraryPath).run(parentImageThatSpawnsChild(), runtime)
    assertEquals("child-output", runtime.capturedStdout)
}
```

```kotlin
@Test
fun tryReadDoesNotUseRunBlockingOnNativePath() {
    val source = Path("modules/core/src/main/kotlin/.../VmIpcApi.kt").readText()
    assertFalse(source.contains("runBlocking { tryRead("))
}
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./gradlew :compiler:test --tests '*NativeImageVmRunnerJniTest' --rerun-tasks`
Expected: FAIL because IPC still goes through Kotlin registry ownership.

Run: `./gradlew :core:test --tests '*IpcChannelRegistryTest' --rerun-tasks`
Expected: FAIL or unchanged coverage that now needs replacement.

- [ ] **Step 3: Implement native IPC registry with bounded buffering**

```rust
pub struct IpcRegistry {
    next_id: i32,
    channels: BTreeMap<i32, IpcChannel>,
    max_buffered_bytes_per_channel: usize,
}

impl IpcRegistry {
    pub fn open(&mut self) -> Result<i32, String> { /* ... */ }
    pub fn write(&mut self, channel_id: i32, text: &str) -> Result<(), String> { /* ... */ }
    pub fn try_read(&mut self, channel_id: i32) -> Result<String, String> { /* ... */ }
    pub fn read(&mut self, channel_id: i32) -> KernelReadResult { /* ... */ }
}
```

- [ ] **Step 4: Keep blocking `ipc.read` resumable instead of blocking the Rust thread**

```rust
enum KernelReadResult {
    Ready(String),
    Pending,
    Closed,
}

if let KernelReadResult::Pending = self.kernel.read(channel_id) {
    self.state = ImageVmState::WaitingForResume;
    return Ok(VmSignal::PauseForKernelRead { channel_id });
}
```

- [ ] **Step 5: Run tests and commit the IPC migration**

Run: `cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner`
Expected: PASS with IPC open/write/tryRead/read coverage.

Run: `./gradlew :compiler:test --tests '*NativeImageVmRunnerJniTest' --rerun-tasks`
Expected: PASS.

Run: `./gradlew :core:test --tests '*IpcChannelRegistryTest' --rerun-tasks`
Expected: PASS, or green after the test class is narrowed to fallback-only behavior.

```bash
git add native/ckl-vm/src/runtime_kernel.rs native/ckl-vm/src/image_runner.rs native/ckl-vm/src/jni.rs \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/IpcChannelRegistry.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/IpcChannelRegistryTest.kt \
  modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt
git commit -m "feat: move runtime ipc into native device kernel"
```

### Task 5: Replace Terminal Polling with One Runtime Poll Primitive

**Files:**

- Modify: `native/ckl-vm/src/runtime_kernel.rs`
- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmHostImportRegistry.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`
- Test:
  `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeProfilingWorkload.kt`

- [ ] **Step 1: Write the failing terminal source-shape and profiling expectations**

```kotlin
@Test
fun terminalUsesSingleRuntimePollInsteadOfSplitIpcAndEventPolling() {
    val source = resourceText("rom/terminal.ck")
    assertTrue(source.contains("runtime::pollTerminal"))
    assertFalse(source.contains("ipc::tryRead(stream)"))
    assertFalse(source.contains("events::tryPull()"))
}
```

```kotlin
@Test
fun bundledTerminalWorkloadStopsDominatingOnEventsTryPullAndIpcTryRead() {
    val profile = runBundledTerminalProfile()
    assertFalse(profile.hostCalls.keys.any { it == "events.tryPull" || it == "ipc.tryRead" })
}
```

- [ ] **Step 2: Run the tests to verify failure**

Run: `./gradlew :v1_21_1-neoforge:test --tests '*RomScriptCompileTest' --rerun-tasks`
Expected: FAIL because `terminal.ck` still uses split polling.

Run: `./gradlew profileRuntimeVmComparison`
Expected: historical report still shows `events.tryPull` and `ipc.tryRead` in terminal workloads.

- [ ] **Step 3: Add a narrow runtime-facing poll contract**

```kotlin
descriptor(8000, "runtime", "pollTerminal", listOf("Int"), "TerminalPoll")
```

```rust
pub enum TerminalPoll {
    StreamChunk(String),
    InputEvent(QueuedEvent),
    Idle,
}
```

- [ ] **Step 4: Rewrite `terminal.ck` around the single poll primitive**

```ckl
while true {
    val poll: TerminalPoll = runtime::pollTerminal(stream)
    if (poll.kind == "chunk") {
        buffer = appendText(displayId, buffer, poll.chunk)
    } else if (poll.kind == "event") {
        handleInputEvent(poll.event)
    } else {
        yield()
    }
}
```

- [ ] **Step 5: Re-profile and commit the terminal poll migration**

Run: `./gradlew :v1_21_1-neoforge:test --tests '*RomScriptCompileTest' --rerun-tasks`
Expected: PASS.

Run: `./gradlew profileRuntimeVmComparison`
Expected: the newest run no longer shows `events.tryPull`/`ipc.tryRead` as terminal hot-path host calls; remaining
terminal cost should shift toward display or genuinely blocking waits.

```bash
git add native/ckl-vm/src/runtime_kernel.rs native/ckl-vm/src/image_runner.rs \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmHostImportRegistry.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt
git commit -m "feat: collapse terminal polling into native runtime primitive"
```

### Task 6: Shrink the Generic Host Bridge and Refresh Docs/Profiling

**Files:**

- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/EventManager.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/IpcChannelRegistry.kt`
- Modify: `docs/PROFILING.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/MACHINE.md`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`

- [ ] **Step 1: Write the failing bridge-regression test**

```kotlin
@Test
fun runtimeHostBridgeNoLongerDispatchesEventsOrIpc() {
    val source = Path("modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt").readText()
    assertFalse(source.contains("\"events\" ->"))
    assertFalse(source.contains("\"ipc\" ->"))
}
```

- [ ] **Step 2: Run the bridge test to verify failure**

Run: `./gradlew :compiler:test --tests '*NativeImageVmRunnerJniTest' --rerun-tasks`
Expected: FAIL because `RuntimeHostBridge` still exposes `events` and `ipc`.

- [ ] **Step 3: Remove the obsolete hot-path ownership from Kotlin**

```kotlin
return when (moduleName) {
    "filesystem" -> invokeFilesystem(functionName, arguments)
    "system" -> invokeSystem(functionName, arguments)
    "display" -> invokeDisplay(functionName, arguments)
    "process" -> invokeProcess(functionName, arguments)
    "strings" -> invokeStrings(functionName, arguments)
    "monitor" -> invokeMonitor(functionName, arguments)
    else -> error("Unknown or native-owned module $moduleName")
}
```

- [ ] **Step 4: Document the profiling expectations after migration**

```markdown
After the native device-kernel migration, terminal workloads should no longer show `events.tryPull` or `ipc.tryRead` as high-volume generic host calls. Remaining `ipc.read` time should represent true blocking waits or should appear under the new native-kernel wait metric.
```

- [ ] **Step 5: Run full verification and commit the cleanup**

Run: `cargo test --manifest-path native/ckl-vm/Cargo.toml`
Expected: PASS.

Run:
`./gradlew :compiler:test --tests '*NativeImageVmRunnerJniTest' --tests '*NativeImageVmBindingsJniTest' --rerun-tasks`
Expected: PASS.

Run: `./gradlew :v1_21_1-neoforge:test --tests '*RomScriptCompileTest' --rerun-tasks`
Expected: PASS.

Run: `./gradlew profileRuntimeVmComparison`
Expected: PASS and produce a fresh dated historical comparison report.

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/EventManager.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/IpcChannelRegistry.kt \
  docs/PROFILING.md docs/ARCHITECTURE.md docs/MACHINE.md
git commit -m "refactor: narrow host bridge after native runtime core migration"
```

## Self-Review

- Spec coverage: this plan covers the first large independence slice only: native ownership for events, IPC, and
  terminal polling. It intentionally does not include a full Rust-owned process manager, filesystem migration, or
  display backend migration.
- Placeholder scan: no `TODO`/`TBD` steps remain; every task names exact files, target APIs, tests, and commands.
- Type consistency: the plan consistently uses `DeviceRuntimeKernel`, `createDeviceKernel`, `attachImageToKernel`, and
  `runtime::pollTerminal`.
- Execution consistency: the plan assumes the native image runner remains the only runtime for bundled image programs.
  If a JVM fallback path still needs `events/ipc`, keep the Kotlin registries as fallback-only until the fallback is
  explicitly removed in a separate slice.
