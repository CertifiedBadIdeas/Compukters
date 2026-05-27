# Rust-Owned Native Wait Scheduler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Park native `runtime.poll` waits on Rust kernel wake state instead of repeatedly yielding through Kotlin scheduler slices.

**Architecture:** Add a Rust `DeviceRuntimeKernelHandle` that owns `Mutex<DeviceRuntimeKernel>` plus `Condvar`, expose wake sequence through `WaitPoll` and JNI, and let `NativeImageVmRunner` block with a bounded native wait before replaying the poll instruction. Keep CKL APIs, process management, terminal behavior, and fallback runtime paths unchanged.

**Tech Stack:** Rust native VM (`native/ckl-vm`), JNI (`jni` crate), Kotlin runtime/compiler modules, Gradle, JUnit 5, existing runtime profiling codec/report tests.

---

## File Structure

- Modify `native/ckl-vm/src/runtime_kernel.rs`: add wake-sequence accessors and a synchronization wrapper for kernel waits.
- Modify `native/ckl-vm/src/image_runner.rs`: store the kernel handle wrapper, notify on native IPC writes/closes, and emit `WaitPoll(channel, wakeSequence)`.
- Modify `native/ckl-vm/src/jni.rs`: allocate/free the wrapper handle, expose `deviceKernelWakeSequenceNative`, and expose `waitForDeviceWakeNative`.
- Modify `native/ckl-vm/src/signal.rs`: encode `WaitPoll` with channel plus wake sequence.
- Modify `native/ckl-vm/tests/signal_codec.rs`: assert the new signal wire format.
- Modify `native/ckl-vm/tests/image_runner.rs`: use the kernel handle wrapper and assert wake sequence behavior.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmSignal.kt`: decode `WaitPoll(channel, wakeSequence)`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`: add Kotlin/native methods for wake sequence and bounded wait.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt`: use bounded native wait for `WaitPoll`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`: use bounded native wait in the existing native-backed `ipc.read` bridge loop.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt`: add native wait metrics API with default no-op behavior.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`: collect native wait calls, nanos, wakeups, and timeouts.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`: persist/read new runtime VM metric columns.
- Modify profiling formatter/codec tests under `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/`.

---

### Task 1: Rust Kernel Wait Handle

**Files:**
- Modify: `native/ckl-vm/src/runtime_kernel.rs`
- Modify: `native/ckl-vm/tests/image_runner.rs`

- [ ] **Step 1: Write failing Rust tests for wake sequence and timeout**

Add tests near existing native kernel tests:

```rust
#[test]
fn kernel_handle_advances_wake_sequence_for_poll_visible_mutations() {
    let handle = DeviceRuntimeKernelHandle::new(8, 64);
    assert_eq!(handle.wake_sequence().unwrap(), 0);

    handle
        .with_kernel_mut(|kernel| {
            assert!(kernel.enqueue_event("key", vec![VmValue::String("x".to_string())]));
        })
        .unwrap();
    assert_eq!(handle.wake_sequence().unwrap(), 1);

    let channel = handle
        .with_kernel_mut(|kernel| kernel.open_ipc_channel())
        .unwrap()
        .unwrap();
    assert_eq!(handle.wake_sequence().unwrap(), 1);

    handle
        .with_kernel_mut(|kernel| kernel.write_ipc(channel, "ready"))
        .unwrap()
        .unwrap();
    assert_eq!(handle.wake_sequence().unwrap(), 2);

    handle
        .with_kernel_mut(|kernel| kernel.close_ipc(channel))
        .unwrap()
        .unwrap();
    assert_eq!(handle.wake_sequence().unwrap(), 3);
}

#[test]
fn kernel_handle_wait_returns_after_timeout_without_wake() {
    let handle = DeviceRuntimeKernelHandle::new(8, 64);
    let started = std::time::Instant::now();

    let sequence = handle.wait_for_wake(0, std::time::Duration::from_millis(1)).unwrap();

    assert_eq!(sequence, 0);
    assert!(started.elapsed() < std::time::Duration::from_secs(1));
}
```

- [ ] **Step 2: Run the Rust tests and confirm they fail**

Run: `cargo test --manifest-path native/ckl-vm/Cargo.toml kernel_handle_`

Expected: compilation fails because `DeviceRuntimeKernelHandle` does not exist.

- [ ] **Step 3: Implement the native wait handle**

In `runtime_kernel.rs`, add imports and wrapper:

```rust
use std::sync::{Condvar, Mutex, MutexGuard};
use std::time::Duration;
```

Add after `DeviceRuntimeKernel` implementation:

```rust
pub struct DeviceRuntimeKernelHandle {
    kernel: Mutex<DeviceRuntimeKernel>,
    wake: Condvar,
}

impl DeviceRuntimeKernelHandle {
    pub fn new(max_event_queue_size: usize, max_buffered_bytes_per_channel: usize) -> Self {
        Self {
            kernel: Mutex::new(DeviceRuntimeKernel::new(
                max_event_queue_size,
                max_buffered_bytes_per_channel,
            )),
            wake: Condvar::new(),
        }
    }

    pub fn lock(&self) -> Result<MutexGuard<'_, DeviceRuntimeKernel>, String> {
        self.kernel
            .lock()
            .map_err(|_| "native device runtime kernel lock is poisoned".to_string())
    }

    pub fn with_kernel_mut<T>(
        &self,
        action: impl FnOnce(&mut DeviceRuntimeKernel) -> T,
    ) -> Result<T, String> {
        let mut kernel = self.lock()?;
        let before = kernel.wake_sequence();
        let result = action(&mut kernel);
        if kernel.wake_sequence() != before {
            self.wake.notify_all();
        }
        Ok(result)
    }

    pub fn wake_sequence(&self) -> Result<i64, String> {
        Ok(self.lock()?.wake_sequence())
    }

    pub fn wait_for_wake(&self, observed_sequence: i64, timeout: Duration) -> Result<i64, String> {
        let kernel = self.lock()?;
        if kernel.wake_sequence() > observed_sequence {
            return Ok(kernel.wake_sequence());
        }
        let (kernel, _) = self
            .wake
            .wait_timeout_while(kernel, timeout, |kernel| {
                kernel.wake_sequence() <= observed_sequence
            })
            .map_err(|_| "native device runtime kernel wait lock is poisoned".to_string())?;
        Ok(kernel.wake_sequence())
    }
}
```

Add to `DeviceRuntimeKernel`:

```rust
pub fn wake_sequence(&self) -> i64 {
    self.wake_sequence
}
```

Update `close_ipc` to wake readers:

```rust
pub fn close_ipc(&mut self, channel: i32) -> Result<(), String> {
    self.ipc.close(channel)?;
    self.wake_sequence = self.wake_sequence.saturating_add(1);
    Ok(())
}
```

- [ ] **Step 4: Run the Rust tests and confirm they pass**

Run: `cargo test --manifest-path native/ckl-vm/Cargo.toml kernel_handle_`

Expected: the two new tests pass.

- [ ] **Step 5: Commit**

```bash
git add native/ckl-vm/src/runtime_kernel.rs native/ckl-vm/tests/image_runner.rs
git commit -m "feat: add native runtime kernel wait handle"
```

---

### Task 2: WaitPoll Wake Sequence Wire Format

**Files:**
- Modify: `native/ckl-vm/src/signal.rs`
- Modify: `native/ckl-vm/tests/signal_codec.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmSignal.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmSignalTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerTest.kt`

- [ ] **Step 1: Write failing codec expectations**

Rust expected bytes:

```rust
let bytes = encode_signal(&VmSignal::WaitPoll {
    channel: 7,
    wake_sequence: 42,
});
assert_eq!(bytes, vec![6, 7, 0, 0, 0, 42, 0, 0, 0, 0, 0, 0, 0]);
```

Kotlin expected decode:

```kotlin
assertEquals(
    NativeVmSignal.WaitPoll(channel = 7, wakeSequence = 42),
    NativeVmSignal.decode(byteArrayOf(6, 7, 0, 0, 0, 42, 0, 0, 0, 0, 0, 0, 0)),
)
```

- [ ] **Step 2: Run the codec tests and confirm they fail**

Run: `cargo test --manifest-path native/ckl-vm/Cargo.toml encodes_wait_poll_signal`

Run: `./gradlew :compiler:test --tests "*NativeVmSignalTest" --tests "*NativeImageVmRunnerTest"`

Expected: Rust and Kotlin tests fail because `WaitPoll` has only `channel`.

- [ ] **Step 3: Update Rust signal model**

Change:

```rust
WaitPoll {
    channel: i32,
    wake_sequence: i64,
},
```

Encode:

```rust
VmSignal::WaitPoll {
    channel,
    wake_sequence,
} => {
    writer.u8(SIGNAL_WAIT_POLL);
    writer.i32(*channel);
    writer.i64(*wake_sequence);
}
```

- [ ] **Step 4: Update Kotlin signal model**

Change:

```kotlin
data class WaitPoll(
    val channel: Int,
    val wakeSequence: Long,
) : NativeVmSignal
```

Decode:

```kotlin
6 -> {
    WaitPoll(reader.i32(), reader.i64())
}
```

- [ ] **Step 5: Run codec tests and commit**

Run: `cargo test --manifest-path native/ckl-vm/Cargo.toml encodes_wait_poll_signal`

Run: `./gradlew :compiler:test --tests "*NativeVmSignalTest" --tests "*NativeImageVmRunnerTest"`

Expected: both pass.

```bash
git add native/ckl-vm/src/signal.rs native/ckl-vm/tests/signal_codec.rs modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmSignal.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmSignalTest.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerTest.kt
git commit -m "feat: include wake sequence in native poll waits"
```

---

### Task 3: Attach Image Runner To Kernel Handle

**Files:**
- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `native/ckl-vm/tests/image_runner.rs`

- [ ] **Step 1: Update tests to use `DeviceRuntimeKernelHandle`**

Replace local test construction:

```rust
let kernel = Arc::new(Mutex::new(DeviceRuntimeKernel::new(64, 4096)));
```

with:

```rust
let kernel = Arc::new(DeviceRuntimeKernelHandle::new(64, 4096));
```

Replace direct locks with:

```rust
kernel.with_kernel_mut(|kernel| {
    kernel.displays.attach(1, 18, 18, ckl_vm::display::PixelFormat::Rgb565).unwrap();
}).unwrap();
```

For read-only assertions, use:

```rust
let frames = kernel.with_kernel_mut(|kernel| kernel.displays.drain_frames()).unwrap();
```

- [ ] **Step 2: Run image runner tests and confirm they fail**

Run: `cargo test --manifest-path native/ckl-vm/Cargo.toml attached_kernel_`

Expected: compilation fails because `ImageVmHandle::attach_device_kernel` still expects `Arc<Mutex<DeviceRuntimeKernel>>`.

- [ ] **Step 3: Update image runner to use the handle**

Imports:

```rust
use std::sync::Arc;
use crate::runtime_kernel::DeviceRuntimeKernelHandle;
```

Fields and method:

```rust
attached_kernel: Option<Arc<DeviceRuntimeKernelHandle>>,

pub fn attach_device_kernel(
    &mut self,
    kernel: Arc<DeviceRuntimeKernelHandle>,
) -> Result<(), String> {
    self.attached_kernel = Some(kernel);
    Ok(())
}
```

At the top of `try_attached_kernel_host_import`, replace direct locking with:

```rust
let mut kernel = kernel_handle.lock()?;
```

When native `ipc.write` and `ipc.close` mutate through the image runner, do the mutation through `with_kernel_mut` so
waiters are notified:

```rust
kernel_handle.with_kernel_mut(|kernel| kernel.write_ipc(channel, text))??;
```

For `runtime.poll`, after IPC/event checks fail, capture:

```rust
let wake_sequence = kernel.wake_sequence();
```

and emit:

```rust
VmSignal::WaitPoll {
    channel,
    wake_sequence,
}
```

- [ ] **Step 4: Update JNI handle type**

In `jni.rs`, replace the alias:

```rust
type SharedDeviceRuntimeKernel = Arc<DeviceRuntimeKernelHandle>;
```

Create:

```rust
Box::into_raw(Box::new(Arc::new(DeviceRuntimeKernelHandle::new(
    max_event_queue_size,
    max_buffered_bytes_per_channel,
)))) as jlong
```

Update `locked_kernel_handle` to call `kernel.lock()` and keep its return type as `MutexGuard<'static, DeviceRuntimeKernel>`.

Update mutating JNI functions later in Task 4 to notify via wrapper.

- [ ] **Step 5: Run image runner tests and commit**

Run: `cargo test --manifest-path native/ckl-vm/Cargo.toml attached_kernel_`

Expected: tests pass, and `attached_kernel_waits_poll_without_generic_host_call` expects WaitPoll bytes with channel and wake sequence.

```bash
git add native/ckl-vm/src/image_runner.rs native/ckl-vm/src/jni.rs native/ckl-vm/tests/image_runner.rs
git commit -m "feat: attach native images to waitable kernel handles"
```

---

### Task 4: JNI Wake Sequence And Bounded Wait

**Files:**
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [ ] **Step 1: Add failing JNI API surface tests**

In `NativeImageVmBindingsJniTest`, assert native methods exist:

```kotlin
assertTrue(
    NativeVmBindings::class.java.declaredMethods.any { it.name == "deviceKernelWakeSequenceNative" },
    "NativeVmBindings must expose native device kernel wake sequence",
)
assertTrue(
    NativeVmBindings::class.java.declaredMethods.any { it.name == "waitForDeviceWakeNative" },
    "NativeVmBindings must expose native device kernel waits",
)
```

Add behavior test:

```kotlin
@Test
fun nativeDeviceWakeWaitReturnsAfterIpcWriteWhenLibraryIsConfigured() {
    val libraryPath = configuredNativeLibraryOrSkip()
    val kernelHandle = NativeVmBindings.createDeviceKernel(64, 4096)
    try {
        val initial = NativeVmBindings.deviceKernelWakeSequence(kernelHandle)
        assertEquals(initial, NativeVmBindings.waitForDeviceWake(kernelHandle, initial, timeoutMillis = 1))
        val channel = openChannelThroughSmallImage(libraryPath, kernelHandle)
        assertTrue(NativeVmBindings.writeDeviceIpc(kernelHandle, channel, "wake"))
        assertTrue(NativeVmBindings.waitForDeviceWake(kernelHandle, initial, timeoutMillis = 100) > initial)
    } finally {
        NativeVmBindings.freeDeviceKernel(kernelHandle)
    }
}
```

Use existing test image helpers in the file for opening a channel; if no helper exists, inline the existing tiny image
construction pattern already used by `nativeIpcWriteWakesWaitPollWhenLibraryIsConfigured`.

- [ ] **Step 2: Run JNI tests and confirm they fail**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/rust-owned-native-wait-scheduler/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests "*NativeImageVmBindingsJniTest" --rerun-tasks
```

Expected: tests fail before implementation because the native methods do not exist.

- [ ] **Step 3: Add Kotlin binding methods**

Add public wrappers:

```kotlin
fun deviceKernelWakeSequence(handle: Long): Long {
    require(handle != 0L) { "Native device runtime kernel handle is zero" }
    return deviceKernelWakeSequenceNative(handle)
}

fun waitForDeviceWake(
    handle: Long,
    observedWakeSequence: Long,
    timeoutMillis: Long,
): Long {
    require(handle != 0L) { "Native device runtime kernel handle is zero" }
    return waitForDeviceWakeNative(handle, observedWakeSequence, timeoutMillis.coerceAtLeast(0))
}
```

Add native declarations:

```kotlin
private external fun deviceKernelWakeSequenceNative(handle: Long): Long

private external fun waitForDeviceWakeNative(
    handle: Long,
    observedWakeSequence: Long,
    timeoutMillis: Long,
): Long
```

- [ ] **Step 4: Add Rust JNI methods**

In `jni.rs`:

```rust
use std::time::Duration;
```

Add:

```rust
#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_deviceKernelWakeSequenceNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlong {
    let kernel = match shared_kernel_handle(&mut env, handle) {
        Some(kernel) => kernel,
        None => return 0,
    };
    match kernel.wake_sequence() {
        Ok(sequence) => sequence as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_waitForDeviceWakeNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    observed_wake_sequence: jlong,
    timeout_millis: jlong,
) -> jlong {
    let kernel = match shared_kernel_handle(&mut env, handle) {
        Some(kernel) => kernel,
        None => return observed_wake_sequence,
    };
    let timeout = Duration::from_millis(timeout_millis.max(0) as u64);
    match kernel.wait_for_wake(observed_wake_sequence, timeout) {
        Ok(sequence) => sequence as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            observed_wake_sequence
        }
    }
}
```

Update `enqueueDeviceEventNative` and `writeDeviceIpcNative` to call `with_kernel_mut(...)` so the condition variable is notified.

- [ ] **Step 5: Build native library, run JNI tests, and commit**

Run: `./gradlew :v1_21_1-neoforge:buildRustVmNativeLibrary`

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/rust-owned-native-wait-scheduler/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests "*NativeImageVmBindingsJniTest" --rerun-tasks
```

Expected: JNI tests pass.

```bash
git add native/ckl-vm/src/jni.rs modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt
git commit -m "feat: expose native device wake waits over jni"
```

---

### Task 5: Kotlin Runner Native Wait Scheduling

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerTest.kt`

- [ ] **Step 1: Extend facade and fake bindings test**

Add to `NativeVmBindingsFacade`:

```kotlin
fun waitForDeviceWake(
    handle: Long,
    observedWakeSequence: Long,
    timeoutMillis: Long,
): Long
```

In `RecordingBindings`, record:

```kotlin
var waitForDeviceWakeCalls = 0
var waitForDeviceWakeResult = 0L

override fun waitForDeviceWake(
    handle: Long,
    observedWakeSequence: Long,
    timeoutMillis: Long,
): Long {
    waitForDeviceWakeCalls++
    return waitForDeviceWakeResult
}
```

Update the existing wait-poll test to assert:

```kotlin
assertEquals(1, bindings.waitForDeviceWakeCalls)
assertEquals(0, runtime.yieldCalls)
```

for an advanced sequence, and add a timeout test that returns the same sequence and expects one yield.

- [ ] **Step 2: Run runner tests and confirm they fail**

Run: `./gradlew :compiler:test --tests "*NativeImageVmRunnerTest"`

Expected: tests fail because `WaitPoll` still unconditionally yields.

- [ ] **Step 3: Implement bounded wait handling**

In `NativeImageVmRunner.kt`, add:

```kotlin
private const val NATIVE_POLL_WAIT_TIMEOUT_MILLIS = 50L
```

Replace WaitPoll handling:

```kotlin
is NativeVmSignal.WaitPoll -> {
    val kernelHandle = kernelHandleOrNull(runtime)
    if (kernelHandle == null) {
        runtime.yield()
    } else {
        val started = System.nanoTime()
        val latest =
            bindings.waitForDeviceWake(
                kernelHandle,
                signal.wakeSequence,
                NATIVE_POLL_WAIT_TIMEOUT_MILLIS,
            )
        runtime.metrics.recordNativeWait("runtime.poll", System.nanoTime() - started, latest > signal.wakeSequence)
        if (latest <= signal.wakeSequence) {
            runtime.yield()
        }
    }
}
```

- [ ] **Step 4: Run tests and commit**

Run: `./gradlew :compiler:test --tests "*NativeImageVmRunnerTest"`

Expected: runner tests pass.

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerTest.kt
git commit -m "feat: park native poll waits on kernel wake state"
```

---

### Task 6: Metrics For Native Waits

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt`

- [ ] **Step 1: Add failing metrics test**

In `RuntimeProfilingTest`, add:

```kotlin
@Test
fun recordsNativeWaitMetrics() {
    val collector = RecordingRuntimeMetricsCollector()

    collector.recordNativeWait("runtime.poll", nanos = 100, woke = true)
    collector.recordNativeWait("runtime.poll", nanos = 50, woke = false)

    val snapshot = collector.snapshot()
    assertEquals(2, snapshot.vm.nativeWaitCalls)
    assertEquals(150, snapshot.vm.nativeWaitNanos)
    assertEquals(1, snapshot.vm.nativeWaitWakeups)
    assertEquals(1, snapshot.vm.nativeWaitTimeouts)
}
```

- [ ] **Step 2: Run metrics test and confirm it fails**

Run: `./gradlew :core:test --tests "*RuntimeProfilingTest.recordsNativeWaitMetrics"`

Expected: compilation fails because native wait metric fields do not exist.

- [ ] **Step 3: Add metrics API and collector fields**

In `DeviceRuntimeMetrics`, add default method:

```kotlin
fun recordNativeWait(
    kind: String,
    nanos: Long,
    woke: Boolean,
) = Unit
```

In `RuntimeVmMetrics`, add:

```kotlin
val nativeWaitCalls: Long = 0,
val nativeWaitNanos: Long = 0,
val nativeWaitWakeups: Long = 0,
val nativeWaitTimeouts: Long = 0,
```

In `RecordingRuntimeMetricsCollector`, add atomic counters and implementation:

```kotlin
override fun recordNativeWait(
    kind: String,
    nanos: Long,
    woke: Boolean,
) {
    nativeWaitCalls.incrementAndGet()
    nativeWaitNanos.addAndGet(nanos.coerceAtLeast(0))
    if (woke) nativeWaitWakeups.incrementAndGet() else nativeWaitTimeouts.incrementAndGet()
}
```

Populate the new `RuntimeVmMetrics` fields in `snapshot()`.

- [ ] **Step 4: Run metrics tests and commit**

Run: `./gradlew :core:test --tests "*RuntimeProfilingTest"`

Expected: core runtime profiling tests pass.

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt
git commit -m "feat: record native runtime wait metrics"
```

---

### Task 7: Native Wait-Backed IPC Read Bridge

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridgeMetricsTest.kt`

- [ ] **Step 1: Add bridge behavior test**

Add a focused test with a fake native kernel provider if existing fixtures support it. The expected behavior is:

```kotlin
assertEquals("ready", bridge.invoke("ipc", "read", listOf(VmValue.IntValue(channel))))
assertEquals(1, nativeWaitCalls)
assertEquals(0, yieldCallsBeforeWake)
```

If the current test fixture cannot inject `NativeVmBindings`, keep this test at metrics level by verifying `recordNativeWait`
is called from `readNativeIpc` after introducing a small injectable internal binding facade.

- [ ] **Step 2: Implement native wait-backed read loop**

Replace `runtime.yield()` in `readNativeIpc` with:

```kotlin
val observed = NativeVmBindings.deviceKernelWakeSequence(handle)
val started = System.nanoTime()
val latest = NativeVmBindings.waitForDeviceWake(handle, observed, NATIVE_IPC_READ_WAIT_TIMEOUT_MILLIS)
runtime.metrics.recordNativeWait("ipc.read", System.nanoTime() - started, latest > observed)
if (latest <= observed) {
    runtime.yield()
}
```

Use:

```kotlin
private const val NATIVE_IPC_READ_WAIT_TIMEOUT_MILLIS = 50L
```

- [ ] **Step 3: Run bridge tests and commit**

Run: `./gradlew :compiler:test --tests "*RuntimeHostBridgeMetricsTest"`

Expected: bridge metrics tests pass.

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridgeMetricsTest.kt
git commit -m "feat: wait native ipc reads on kernel wake state"
```

---

### Task 8: Profile Codec And Markdown Report

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`

- [ ] **Step 1: Add failing codec and formatter expectations**

Codec should round-trip:

```kotlin
nativeWaitCalls = 12,
nativeWaitNanos = 345_000,
nativeWaitWakeups = 10,
nativeWaitTimeouts = 2,
```

Markdown formatter should contain labels:

```kotlin
assertTrue(markdown.contains("Native wait calls"))
assertTrue(markdown.contains("Native wait time"))
assertTrue(markdown.contains("Native wait wakeups"))
assertTrue(markdown.contains("Native wait timeouts"))
```

- [ ] **Step 2: Run profiling tests and confirm they fail**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests "*RuntimeVmProfilingProfileCodecTest" --tests "*RuntimeVmProfilingReportFormatterTest"
```

Expected: tests fail because codec/report do not include native wait metric fields.

- [ ] **Step 3: Extend TSV codec compatibly**

Append fields to the end of `runtimeVm` lines:

```kotlin
"runtimeVm\t...\t$nativeFastPathCalls\t$nativeWaitCalls\t$nativeWaitNanos\t$nativeWaitWakeups\t$nativeWaitTimeouts"
```

Read with defaults:

```kotlin
nativeFastPathCalls = if (v.size >= 17) v[16] else 0,
nativeWaitCalls = if (v.size >= 18) v[17] else 0,
nativeWaitNanos = if (v.size >= 19) v[18] else 0,
nativeWaitWakeups = if (v.size >= 20) v[19] else 0,
nativeWaitTimeouts = if (v.size >= 21) v[20] else 0,
```

- [ ] **Step 4: Extend Markdown report rows**

In the runtime VM metrics table, add rows after native wait signals:

```kotlin
row("Native wait calls", vm.nativeWaitCalls)
row("Native wait time", vm.nativeWaitNanos.humanReadableNanos())
row("Native wait wakeups", vm.nativeWaitWakeups)
row("Native wait timeouts", vm.nativeWaitTimeouts)
```

Keep sorting of host-call/instruction metric keys unchanged.

- [ ] **Step 5: Run profiling tests and commit**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests "*RuntimeVmProfilingProfileCodecTest" --tests "*RuntimeVmProfilingReportFormatterTest"
```

Expected: tests pass.

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt
git commit -m "test: report native runtime wait metrics"
```

---

### Task 9: Full Verification And Runtime Profile

**Files:**
- No source files expected unless verification exposes a real defect.

- [ ] **Step 1: Run Rust native tests**

Run: `cargo test --manifest-path native/ckl-vm/Cargo.toml`

Expected: all Rust native VM tests pass.

- [ ] **Step 2: Build native library**

Run: `./gradlew :v1_21_1-neoforge:buildRustVmNativeLibrary`

Expected: native library builds in the worktree.

- [ ] **Step 3: Run Kotlin test suite with native display enabled**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/rust-owned-native-wait-scheduler/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true --no-parallel :compiler:test :core:test :v1_21_1-neoforge:test
```

Expected: all selected Kotlin tests pass.

- [ ] **Step 4: Run profiling task**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/rust-owned-native-wait-scheduler/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true --no-parallel profileRuntimeVmImage
```

Expected: profiling succeeds and Markdown contains native wait metrics. Compare `Native wait signals` and scheduler/yield counts against previous reports.

- [ ] **Step 5: Commit any verification-driven fixes**

If verification required fixes:

```bash
git add <changed-files>
git commit -m "fix: stabilize native wait scheduler"
```

If no fixes were required, do not create an empty commit.
