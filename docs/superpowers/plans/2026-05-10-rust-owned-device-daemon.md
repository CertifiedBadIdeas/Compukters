# Rust-Owned Device Daemon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce an opt-in Rust-owned device daemon that owns native process execution and moves Kotlin toward a Minecraft bridge role.

**Architecture:** Add a Rust `DeviceDaemon` around the existing `DeviceRuntimeKernelHandle`. The daemon owns process image handles, runs selected processes from the native scheduler, handles scheduler-native signals in Rust, and emits structured host requests for unresolved Kotlin services. Kotlin bindings expose the daemon API first; production native mode switches behind an opt-in flag after the daemon path has focused tests.

**Tech Stack:** Rust native library (`native/ckl-vm`), JNI bindings, Kotlin compiler/runtime modules, Gradle/JUnit tests, existing CKVM image ABI.

---

## File Structure

- Create `native/ckl-vm/src/device_daemon.rs`
  - Owns `DeviceDaemon`, daemon process image map, tick summaries, and daemon host request queue.
  - Uses `Arc<DeviceRuntimeKernelHandle>` so existing native filesystem, IPC, events, display, and process table code remain shared.
- Modify `native/ckl-vm/src/lib.rs`
  - Exposes the new Rust module.
- Modify `native/ckl-vm/src/image_runner.rs`
  - Adds an internal decoded signal API and typed resume helper so the daemon does not encode/decode through JNI bytes.
- Modify `native/ckl-vm/src/signal.rs`
  - Adds compact value/request encoding helpers only if the daemon host request queue needs byte serialization in Rust tests.
- Modify `native/ckl-vm/src/jni.rs`
  - Adds daemon lifecycle, boot image, tick, host request drain, and host request completion JNI methods.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
  - Adds Kotlin data classes and binding methods for daemon lifecycle and host requests.
- Create `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt`
  - Kotlin-side opt-in bridge that ticks the Rust daemon and services daemon host requests.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
  - Adds an opt-in daemon path behind `ckl.vm.native.daemon=true`.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
  - Adds daemon metrics.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`
  - Persists daemon profiling fields in TSV/Markdown.

---

## Task 1: Rust Device Daemon Skeleton

**Files:**
- Create: `native/ckl-vm/src/device_daemon.rs`
- Modify: `native/ckl-vm/src/lib.rs`

- [x] **Step 1: Write the failing daemon skeleton tests**

Add tests at the bottom of `native/ckl-vm/src/device_daemon.rs`:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn daemon_registers_boot_process_with_owned_kernel() {
        let mut daemon = DeviceDaemon::new(16, 1024, 128);

        let summary = daemon.boot_image(&ckim_empty_main(), "/rom/bios.ck", "", "");

        assert_eq!(
            summary,
            DeviceDaemonBootSummary {
                pid: 1,
                image_attached: true,
            }
        );
        assert_eq!(daemon.process_image_handle(1), Some(1));
    }

    fn ckim_empty_main() -> Vec<u8> {
        image_with_code(0, vec![2])
    }

    fn image_with_code(frame_size: i32, code: Vec<u8>) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(b"CKIM");
        out.push(1);
        string(&mut out, "ckl-1");
        out.extend_from_slice(&1u16.to_le_bytes());
        i32(&mut out, 0);
        i32(&mut out, 0);
        i32(&mut out, 0);
        i32(&mut out, 0);
        i32(&mut out, 1);
        string(&mut out, "main");
        i32(&mut out, frame_size);
        i32(&mut out, code.len() as i32);
        out.extend_from_slice(&code);
        out
    }

    fn string(out: &mut Vec<u8>, value: &str) {
        i32(out, value.len() as i32);
        out.extend_from_slice(value.as_bytes());
    }

    fn i32(out: &mut Vec<u8>, value: i32) {
        out.extend_from_slice(&value.to_le_bytes());
    }
}
```

- [x] **Step 2: Run the Rust test and verify it fails**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml daemon_registers_boot_process_with_owned_kernel
```

Expected: FAIL because `device_daemon` and `DeviceDaemon` do not exist.

- [x] **Step 3: Add the daemon module and minimal structs**

Add to `native/ckl-vm/src/lib.rs`:

```rust
pub mod device_daemon;
```

Create `native/ckl-vm/src/device_daemon.rs`:

```rust
use std::collections::BTreeMap;
use std::sync::Arc;

use crate::image_runner::ImageVmHandle;
use crate::runtime_kernel::DeviceRuntimeKernelHandle;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceDaemonBootSummary {
    pub pid: i32,
    pub image_attached: bool,
}

pub struct DeviceDaemon {
    kernel: Arc<DeviceRuntimeKernelHandle>,
    images: BTreeMap<i32, ImageVmHandle>,
    image_handles: BTreeMap<i32, i64>,
    next_image_handle: i64,
    instruction_budget: usize,
}

impl DeviceDaemon {
    pub fn new(
        max_event_queue_size: usize,
        max_buffered_bytes_per_channel: usize,
        instruction_budget: usize,
    ) -> Self {
        Self {
            kernel: Arc::new(DeviceRuntimeKernelHandle::new(
                max_event_queue_size,
                max_buffered_bytes_per_channel,
            )),
            images: BTreeMap::new(),
            image_handles: BTreeMap::new(),
            next_image_handle: 1,
            instruction_budget: instruction_budget.max(1),
        }
    }

    pub fn kernel(&self) -> Arc<DeviceRuntimeKernelHandle> {
        self.kernel.clone()
    }

    pub fn boot_image(
        &mut self,
        image_bytes: &[u8],
        program_path: &str,
        argument: &str,
        working_directory: &str,
    ) -> DeviceDaemonBootSummary {
        let pid = 1;
        let mut image = ImageVmHandle::create(image_bytes, self.instruction_budget)
            .expect("boot image must decode");
        image
            .attach_device_kernel(self.kernel.clone())
            .expect("boot image must attach to daemon kernel");
        image.set_working_directory(working_directory.to_string());
        let image_handle = self.next_image_handle;
        self.next_image_handle = self.next_image_handle.saturating_add(1);
        let registered = self
            .kernel
            .with_kernel_mut(|kernel| {
                kernel.register_process(pid, 0, program_path.to_string())
                    && kernel.attach_process_image(pid, image_handle)
            })
            .expect("daemon kernel must be lockable");
        if registered {
            self.images.insert(pid, image);
            self.image_handles.insert(pid, image_handle);
        }
        let _ = argument;
        DeviceDaemonBootSummary {
            pid,
            image_attached: registered,
        }
    }

    pub fn process_image_handle(&self, pid: i32) -> Option<i64> {
        self.image_handles.get(&pid).copied()
    }
}
```

- [x] **Step 4: Run the Rust test and verify it passes**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml daemon_registers_boot_process_with_owned_kernel
```

Expected: PASS.

- [x] **Step 5: Commit Task 1**

```bash
git add native/ckl-vm/src/device_daemon.rs native/ckl-vm/src/lib.rs docs/superpowers/plans/2026-05-10-rust-owned-device-daemon.md
git commit -m "feat: add rust device daemon skeleton"
```

---

## Task 2: Decoded Image Signal API

**Files:**
- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `native/ckl-vm/src/device_daemon.rs`

- [ ] **Step 1: Write the failing decoded signal test**

Add this test to `native/ckl-vm/src/image_runner.rs` tests or a nearby image runner test module:

```rust
#[test]
fn image_runner_can_return_decoded_signal_for_daemon() {
    let image = encode_empty_main_image();
    let mut handle = ImageVmHandle::create(&image, 128).unwrap();

    let signal = handle.run_until_signal_decoded().unwrap();

    assert_eq!(signal, VmSignal::Halt(VmValue::Unit));
}
```

- [ ] **Step 2: Run the Rust test and verify it fails**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml image_runner_can_return_decoded_signal_for_daemon
```

Expected: FAIL because `run_until_signal_decoded` is not public.

- [ ] **Step 3: Add decoded run and typed resume helpers**

In `native/ckl-vm/src/image_runner.rs`, add public methods near `run_until_signal`:

```rust
pub fn run_until_signal_decoded(&mut self) -> Result<VmSignal, String> {
    match catch_unwind(AssertUnwindSafe(|| self.run_until_signal_inner())) {
        Ok(result) => result,
        Err(payload) => Err(panic_message(payload)),
    }
}

pub fn resume_with_value(&mut self, value: VmValue) -> Result<(), String> {
    if self.state != ImageVmState::WaitingForResume {
        return Err("native image VM is not waiting for resume".to_string());
    }
    self.stack.push(value);
    self.state = ImageVmState::Ready;
    Ok(())
}
```

Refactor `resume_with_value_bytes` to decode bytes and call `resume_with_value(value)`.

- [ ] **Step 4: Run focused Rust tests**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml image_runner_can_return_decoded_signal_for_daemon
cargo test --manifest-path native/ckl-vm/Cargo.toml image_runner
```

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

```bash
git add native/ckl-vm/src/image_runner.rs native/ckl-vm/src/device_daemon.rs docs/superpowers/plans/2026-05-10-rust-owned-device-daemon.md
git commit -m "feat: expose decoded native image signals"
```

---

## Task 3: Daemon Tick Runs One Image Turn

**Files:**
- Modify: `native/ckl-vm/src/device_daemon.rs`
- Modify: `native/ckl-vm/src/runtime_kernel.rs`

- [ ] **Step 1: Write failing daemon tick tests**

Add tests to `native/ckl-vm/src/device_daemon.rs`:

```rust
#[test]
fn daemon_tick_runs_boot_image_to_halt() {
    let mut daemon = DeviceDaemon::new(16, 1024, 128);
    daemon.boot_image(&ckim_empty_main(), "/rom/bios.ck", "", "");

    let summary = daemon.tick(128, 1_000_000, 7);

    assert_eq!(
        summary,
        DeviceDaemonTickSummary {
            server_tick: 7,
            turns: 1,
            remaining_instructions: 127,
            idle: false,
            halted: 1,
            host_requests: 0,
        }
    );
    assert_eq!(daemon.process_status(1), DeviceDaemonProcessStatus::Completed(0));
}
```

- [ ] **Step 2: Run the Rust test and verify it fails**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml daemon_tick_runs_boot_image_to_halt
```

Expected: FAIL because `DeviceDaemon::tick` and daemon process status APIs do not exist.

- [ ] **Step 3: Implement tick for `Halt` and `Pause`**

In `native/ckl-vm/src/device_daemon.rs`, add:

```rust
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceDaemonTickSummary {
    pub server_tick: i64,
    pub turns: i64,
    pub remaining_instructions: i64,
    pub idle: bool,
    pub halted: i64,
    pub host_requests: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DeviceDaemonProcessStatus {
    Running,
    Completed(i32),
    Missing,
}

impl DeviceDaemon {
    pub fn tick(
        &mut self,
        instructions: i64,
        wall_nanos: i64,
        server_tick: i64,
    ) -> DeviceDaemonTickSummary {
        let _ = wall_nanos;
        self.kernel
            .with_kernel_mut(|kernel| {
                kernel.add_execution_quota(instructions, wall_nanos, server_tick);
            })
            .expect("daemon kernel must be lockable");
        let mut turns = 0;
        let mut halted = 0;
        loop {
            let step = self
                .kernel
                .with_kernel_mut(|kernel| kernel.run_scheduler_step())
                .expect("daemon kernel must be lockable");
            let Some(pid) = step.selected_pid else {
                return DeviceDaemonTickSummary {
                    server_tick,
                    turns,
                    remaining_instructions: step.remaining_instructions,
                    idle: true,
                    halted,
                    host_requests: 0,
                };
            };
            turns += 1;
            let signal = match self.images.get_mut(&pid) {
                Some(image) => image.run_until_signal_decoded(),
                None => Err(format!("daemon selected pid {pid} without image")),
            };
            match signal {
                Ok(VmSignal::Halt(_value)) => {
                    halted += 1;
                    let _ = self.kernel.with_kernel_mut(|kernel| kernel.complete_process(pid, 0));
                    self.images.remove(&pid);
                }
                Ok(VmSignal::Pause) => {}
                Ok(other) => {
                    panic!("daemon received deferred Task 4 signal during Task 3: {other:?}");
                }
                Err(message) => {
                    let _ = self.kernel.with_kernel_mut(|kernel| kernel.mark_process_crashed(pid, message));
                    self.images.remove(&pid);
                }
            }
            if step.quota_exhausted {
                return DeviceDaemonTickSummary {
                    server_tick,
                    turns,
                    remaining_instructions: step.remaining_instructions,
                    idle: false,
                    halted,
                    host_requests: 0,
                };
            }
        }
    }
}
```

The implementation may use a helper method to build the summary; keep the fields and behavior above.

- [ ] **Step 4: Run focused Rust tests**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml daemon_tick_runs_boot_image_to_halt
cargo test --manifest-path native/ckl-vm/Cargo.toml scheduler_step
```

Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```bash
git add native/ckl-vm/src/device_daemon.rs native/ckl-vm/src/runtime_kernel.rs docs/superpowers/plans/2026-05-10-rust-owned-device-daemon.md
git commit -m "feat: run daemon image turns"
```

---

## Task 4: Scheduler-Native Signal Handling

**Files:**
- Modify: `native/ckl-vm/src/device_daemon.rs`

- [ ] **Step 1: Add failing signal handling tests**

Add tests covering `Yield`, `Sleep`, `WaitProcess`, `WaitEvent`, and `WaitPoll`:

```rust
#[test]
fn daemon_handles_yield_by_resuming_unit_and_requeueing_process() {
    let mut daemon = DeviceDaemon::new(16, 1024, 128);
    daemon.boot_image(&ckim_yields_then_halts(), "/rom/yield.ck", "", "");

    let first = daemon.tick(1, 1_000_000, 10);
    let second = daemon.tick(1, 1_000_000, 11);

    assert_eq!(first.turns, 1);
    assert_eq!(daemon.process_status(1), DeviceDaemonProcessStatus::Running);
    assert_eq!(second.halted, 1);
    assert_eq!(daemon.process_status(1), DeviceDaemonProcessStatus::Completed(0));
}

#[test]
fn daemon_moves_sleeping_process_until_due_tick() {
    let mut daemon = DeviceDaemon::new(16, 1024, 128);
    daemon.boot_image(&ckim_sleeps_one_tick_then_halts(), "/rom/sleep.ck", "", "");

    let first = daemon.tick(1, 1_000_000, 20);
    let second = daemon.tick(1, 1_000_000, 20);
    let third = daemon.tick(1, 1_000_000, 21);

    assert_eq!(first.turns, 1);
    assert!(second.idle);
    assert_eq!(third.halted, 1);
}
```

- [ ] **Step 2: Run the Rust tests and verify they fail**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml daemon_handles_yield_by_resuming_unit_and_requeueing_process
cargo test --manifest-path native/ckl-vm/Cargo.toml daemon_moves_sleeping_process_until_due_tick
```

Expected: FAIL because daemon signal handling only supports `Halt` and `Pause`.

- [ ] **Step 3: Implement native signal handling**

In the daemon signal match, implement:

```rust
Ok(VmSignal::Yield) => {
    if let Some(image) = self.images.get_mut(&pid) {
        image.resume_with_value(VmValue::Unit)?;
    }
    let _ = self.kernel.with_kernel_mut(|kernel| kernel.mark_process_runnable(pid));
}
Ok(VmSignal::Sleep(ticks)) => {
    if let Some(image) = self.images.get_mut(&pid) {
        image.resume_with_value(VmValue::Unit)?;
    }
    let until_tick = server_tick.saturating_add(ticks.max(1));
    let _ = self.kernel.with_kernel_mut(|kernel| kernel.mark_process_sleeping(pid, until_tick));
}
Ok(VmSignal::WaitEvent(filter)) => {
    let _ = self.kernel.with_kernel_mut(|kernel| kernel.mark_process_waiting_for_event(pid, filter));
}
Ok(VmSignal::WaitPoll {
    channel,
    wake_sequence: _wake_sequence,
}) => {
    let _ = self.kernel.with_kernel_mut(|kernel| kernel.mark_process_waiting_for_ipc(pid, channel));
}
Ok(VmSignal::WaitProcess {
    pid: target_pid,
    wake_sequence: _wake_sequence,
}) => {
    let _ = self.kernel.with_kernel_mut(|kernel| kernel.mark_process_waiting_for_process(pid, target_pid));
}
```

Use `Result` propagation inside this helper so errors crash the process through `mark_process_crashed`:

```rust
fn handle_signal(
    &mut self,
    pid: i32,
    signal: VmSignal,
    server_tick: i64,
) -> Result<DaemonSignalOutcome, String>
```

- [ ] **Step 4: Run focused Rust tests**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml daemon_handles_
cargo test --manifest-path native/ckl-vm/Cargo.toml daemon_moves_sleeping_process_until_due_tick
cargo test --manifest-path native/ckl-vm/Cargo.toml scheduler_step
```

Expected: PASS.

- [ ] **Step 5: Commit Task 4**

```bash
git add native/ckl-vm/src/device_daemon.rs docs/superpowers/plans/2026-05-10-rust-owned-device-daemon.md
git commit -m "feat: handle daemon scheduler signals"
```

---

## Task 5: Daemon Host Request Queue

**Files:**
- Modify: `native/ckl-vm/src/device_daemon.rs`
- Modify: `native/ckl-vm/src/signal.rs`

- [ ] **Step 1: Add failing host request tests**

Add tests to `native/ckl-vm/src/device_daemon.rs`:

```rust
#[test]
fn daemon_host_call_parks_process_and_can_resume_with_value() {
    let mut daemon = DeviceDaemon::new(16, 1024, 128);
    daemon.boot_image(&ckim_calls_system_log_then_halts(), "/rom/host.ck", "", "");

    let first = daemon.tick(4, 1_000_000, 1);
    let requests = daemon.drain_host_requests();

    assert_eq!(first.host_requests, 1);
    assert_eq!(requests.len(), 1);
    assert_eq!(requests[0].pid, 1);
    assert_eq!(requests[0].module_name.as_deref(), Some("system"));
    assert_eq!(requests[0].function_name.as_deref(), Some("log"));

    daemon.complete_host_request(requests[0].request_id, VmValue::Unit).unwrap();
    let second = daemon.tick(4, 1_000_000, 2);

    assert_eq!(second.halted, 1);
    assert_eq!(daemon.process_status(1), DeviceDaemonProcessStatus::Completed(0));
}
```

- [ ] **Step 2: Run the Rust test and verify it fails**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml daemon_host_call_parks_process_and_can_resume_with_value
```

Expected: FAIL because daemon host request queue does not exist.

- [ ] **Step 3: Add host request structs and queue**

Add to `native/ckl-vm/src/device_daemon.rs`:

```rust
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DeviceDaemonHostRequestKind {
    HostCall,
    CompileProgram,
    Crash,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceDaemonHostRequest {
    pub request_id: i64,
    pub pid: i32,
    pub kind: DeviceDaemonHostRequestKind,
    pub module_name: Option<String>,
    pub function_name: Option<String>,
    pub arguments: Vec<VmValue>,
    pub path: Option<String>,
    pub working_directory: Option<String>,
}
```

Add fields to `DeviceDaemon`:

```rust
host_requests: VecDeque<DeviceDaemonHostRequest>,
pending_host_requests: BTreeMap<i64, i32>,
next_host_request_id: i64,
```

Handle `VmSignal::HostCall` by pushing a `DeviceDaemonHostRequest`, parking the process with a new native state
`WaitingHost { request_id }`, and incrementing `host_requests` in the tick summary.

- [ ] **Step 4: Add host request completion**

Add:

```rust
pub fn drain_host_requests(&mut self) -> Vec<DeviceDaemonHostRequest> {
    self.host_requests.drain(..).collect()
}

pub fn complete_host_request(
    &mut self,
    request_id: i64,
    value: VmValue,
) -> Result<(), String> {
    let pid = self
        .pending_host_requests
        .remove(&request_id)
        .ok_or_else(|| format!("daemon host request not found: {request_id}"))?;
    let image = self
        .images
        .get_mut(&pid)
        .ok_or_else(|| format!("daemon host request pid has no image: {pid}"))?;
    image.resume_with_value(value)?;
    self.kernel
        .with_kernel_mut(|kernel| kernel.mark_process_runnable(pid))?;
    Ok(())
}
```

- [ ] **Step 5: Run focused Rust tests**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml daemon_host_call_parks_process_and_can_resume_with_value
cargo test --manifest-path native/ckl-vm/Cargo.toml daemon_
```

Expected: PASS.

- [ ] **Step 6: Commit Task 5**

```bash
git add native/ckl-vm/src/device_daemon.rs native/ckl-vm/src/runtime_kernel.rs native/ckl-vm/src/signal.rs docs/superpowers/plans/2026-05-10-rust-owned-device-daemon.md
git commit -m "feat: add daemon host request queue"
```

---

## Task 6: Daemon JNI Lifecycle And Tick ABI

**Files:**
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [ ] **Step 1: Add failing Kotlin ABI reflection tests**

Add to `NativeImageVmBindingsJniTest`:

```kotlin
@Test
fun nativeDeviceDaemonMethodsExposeCompactAbi() {
    assertEquals(
        Long::class.javaPrimitiveType,
        NativeVmBindings::class.java
            .getDeclaredMethod(
                "createDeviceDaemonNative",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).returnType,
    )
    assertEquals(
        LongArray::class.java,
        NativeVmBindings::class.java
            .getDeclaredMethod(
                "tickDeviceDaemonNative",
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            ).returnType,
    )
}
```

- [ ] **Step 2: Run the Kotlin test and verify it fails**

```bash
./gradlew :compiler:test --tests '*NativeImageVmBindingsJniTest.nativeDeviceDaemonMethodsExposeCompactAbi' --rerun-tasks
```

Expected: FAIL because daemon JNI methods do not exist.

- [ ] **Step 3: Add Rust JNI handle registry**

In `native/ckl-vm/src/jni.rs`, add:

```rust
use crate::device_daemon::DeviceDaemon;

static NEXT_DEVICE_DAEMON_HANDLE: AtomicI64 = AtomicI64::new(1);
static DEVICE_DAEMON_HANDLES: OnceLock<Mutex<HashMap<jlong, DeviceDaemon>>> = OnceLock::new();
```

Add `createDeviceDaemonNative`, `freeDeviceDaemonNative`, and `tickDeviceDaemonNative`. Encode tick summary as:

```text
[serverTick, turns, remainingInstructions, idleFlag, halted, hostRequests]
```

- [ ] **Step 4: Add Kotlin binding data classes**

Add to `NativeVmBindings.kt`:

```kotlin
data class NativeDeviceDaemonTickSummary(
    val serverTick: Long,
    val turns: Long,
    val remainingInstructions: Long,
    val idle: Boolean,
    val halted: Long,
    val hostRequests: Long,
)
```

Add methods:

```kotlin
fun createDeviceDaemon(
    maxEventQueueSize: Int,
    maxBufferedBytesPerChannel: Int,
    instructionBudget: Int,
): Long

fun tickDeviceDaemon(
    daemonHandle: Long,
    instructions: Long,
    wallNanos: Long,
    serverTick: Long,
): NativeDeviceDaemonTickSummary
```

- [ ] **Step 5: Add JNI integration test**

Add:

```kotlin
@Test
fun nativeDeviceDaemonCreateTickFreeRunsWhenLibraryIsConfigured() {
    System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
    val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 128)
    try {
        assertEquals(
            NativeDeviceDaemonTickSummary(
                serverTick = 5,
                turns = 0,
                remainingInstructions = 128,
                idle = true,
                halted = 0,
                hostRequests = 0,
            ),
            NativeVmBindings.tickDeviceDaemon(handle, 128, 1_000_000, 5),
        )
    } finally {
        NativeVmBindings.freeDeviceDaemon(handle)
    }
}
```

- [ ] **Step 6: Run focused checks**

```bash
./gradlew :v1_21_1-neoforge:buildRustVmNativeLibrary
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests '*NativeImageVmBindingsJniTest' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 7: Commit Task 6**

```bash
git add native/ckl-vm/src/jni.rs modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt docs/superpowers/plans/2026-05-10-rust-owned-device-daemon.md
git commit -m "feat: expose native device daemon tick"
```

---

## Task 7: Daemon Boot Image JNI ABI

**Files:**
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [ ] **Step 1: Add failing boot image JNI test**

Add to `NativeImageVmBindingsJniTest`:

```kotlin
@Test
fun nativeDeviceDaemonBootImageRunsWhenLibraryIsConfigured() {
    System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
    val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { }").image)
    val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 128)
    try {
        assertEquals(
            NativeDeviceDaemonBootSummary(pid = 1, imageAttached = true),
            NativeVmBindings.bootDeviceDaemon(
                daemonHandle = handle,
                image = CkVmImageAbi.encode(image),
                programPath = "/rom/bios.ck",
                argument = "",
                workingDirectory = "",
            ),
        )
        assertEquals(1, NativeVmBindings.tickDeviceDaemon(handle, 128, 1_000_000, 1).halted)
    } finally {
        NativeVmBindings.freeDeviceDaemon(handle)
    }
}
```

- [ ] **Step 2: Run the Kotlin test and verify it fails**

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests '*NativeImageVmBindingsJniTest.nativeDeviceDaemonBootImageRunsWhenLibraryIsConfigured' --rerun-tasks
```

Expected: FAIL because boot daemon JNI API does not exist.

- [ ] **Step 3: Add Rust and Kotlin boot binding**

Rust JNI method:

```text
bootDeviceDaemonNative(handle, imageBytes, programPath, argument, workingDirectory) -> LongArray
```

Encode boot summary:

```text
[pid, imageAttachedFlag]
```

Kotlin data class:

```kotlin
data class NativeDeviceDaemonBootSummary(
    val pid: Int,
    val imageAttached: Boolean,
)
```

- [ ] **Step 4: Run focused checks**

```bash
./gradlew :v1_21_1-neoforge:buildRustVmNativeLibrary
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests '*NativeImageVmBindingsJniTest.nativeDeviceDaemonBootImageRunsWhenLibraryIsConfigured' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Commit Task 7**

```bash
git add native/ckl-vm/src/jni.rs modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt docs/superpowers/plans/2026-05-10-rust-owned-device-daemon.md
git commit -m "feat: boot images in native daemon"
```

---

## Task 8: Daemon Host Request JNI ABI

**Files:**
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `native/ckl-vm/src/device_daemon.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [ ] **Step 1: Add failing host request JNI test**

Add a Kotlin test that boots an image with `system.log("hi")`, ticks until a request appears, drains it, completes it
with `Unit`, and ticks to halt:

```kotlin
@Test
fun nativeDeviceDaemonHostRequestsRoundTripWhenLibraryIsConfigured() {
    System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
    val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { system.log(\"hi\") }").image)
    val handle = NativeVmBindings.createDeviceDaemon(64, 4096, 128)
    try {
        NativeVmBindings.bootDeviceDaemon(handle, CkVmImageAbi.encode(image), "/rom/host.ck", "", "")
        val first = NativeVmBindings.tickDeviceDaemon(handle, 128, 1_000_000, 1)
        val requests = NativeVmBindings.drainDeviceDaemonHostRequests(handle)
        assertEquals(1, first.hostRequests)
        assertEquals("system", requests.single().moduleName)
        assertEquals("log", requests.single().functionName)

        NativeVmBindings.completeDeviceDaemonHostRequest(handle, requests.single().requestId, VmValue.UnitValue.toNativeBytes("system", "log"))
        assertEquals(1, NativeVmBindings.tickDeviceDaemon(handle, 128, 1_000_000, 2).halted)
    } finally {
        NativeVmBindings.freeDeviceDaemon(handle)
    }
}
```

- [ ] **Step 2: Run the Kotlin test and verify it fails**

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests '*NativeImageVmBindingsJniTest.nativeDeviceDaemonHostRequestsRoundTripWhenLibraryIsConfigured' --rerun-tasks
```

Expected: FAIL because host request JNI methods do not exist.

- [ ] **Step 3: Add compact request byte encoding**

Encode drained requests as a byte array:

```text
i32 requestCount
repeat requestCount:
  i64 requestId
  i32 pid
  u8 kind
  string moduleName
  string functionName
  i32 argumentCount
  repeat argumentCount: encoded VmValue
  string path
  string workingDirectory
```

Use empty strings for absent optional strings in this first ABI. Kotlin decodes empty strings to `null`.

- [ ] **Step 4: Add complete request binding**

Rust:

```text
completeDeviceDaemonHostRequestNative(handle, requestId, encodedValueBytes) -> Boolean
```

Kotlin:

```kotlin
data class NativeDeviceDaemonHostRequest(
    val requestId: Long,
    val pid: Int,
    val kind: String,
    val moduleName: String?,
    val functionName: String?,
    val arguments: List<VmValue>,
    val path: String?,
    val workingDirectory: String?,
)
```

- [ ] **Step 5: Run focused checks**

```bash
./gradlew :v1_21_1-neoforge:buildRustVmNativeLibrary
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests '*NativeImageVmBindingsJniTest.nativeDeviceDaemonHostRequestsRoundTripWhenLibraryIsConfigured' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 6: Commit Task 8**

```bash
git add native/ckl-vm/src/jni.rs native/ckl-vm/src/device_daemon.rs modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt docs/superpowers/plans/2026-05-10-rust-owned-device-daemon.md
git commit -m "feat: bridge native daemon host requests"
```

---

## Task 9: Kotlin Native Daemon Runtime Bridge

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] **Step 1: Add failing daemon runtime unit test**

Add a focused test with a fake binding facade if needed:

```kotlin
@Test
fun nativeDaemonRuntimeBootsCompiledBootImageAndTicksDaemon() =
    runTest {
        val runtime = TestNativeDeviceDaemonRuntime(
            bootImage = compiledEmptyMainImageBytes(),
            bindings = RecordingDaemonBindings(),
        )

        runtime.boot()
        runtime.requestSlice(serverTick = 1)

        assertEquals(listOf(1L), runtime.bindings.tickServerTicks)
        assertTrue(runtime.bindings.bootedImages.isNotEmpty())
    }
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
./gradlew :core:test --tests '*BackgroundDeviceVmTest.*nativeDaemon*' --rerun-tasks
```

Expected: FAIL because `NativeDeviceDaemonRuntime` does not exist.

- [ ] **Step 3: Add runtime bridge skeleton**

Create `NativeDeviceDaemonRuntime.kt`:

```kotlin
internal class NativeDeviceDaemonRuntime(
    private val daemonHandle: Long,
    private val profile: DeviceProfile,
    private val bindings: NativeDaemonBindings,
    private val hostBridge: suspend (NativeDeviceDaemonHostRequest) -> ByteArray,
) {
    fun boot(
        image: ByteArray,
        programPath: String,
        argument: String,
        workingDirectory: String,
    ): NativeDeviceDaemonBootSummary =
        bindings.bootDeviceDaemon(daemonHandle, image, programPath, argument, workingDirectory)

    suspend fun requestSlice(serverTick: Long): NativeDeviceDaemonTickSummary {
        val summary =
            bindings.tickDeviceDaemon(
                daemonHandle = daemonHandle,
                instructions = profile.resources.cpu.instructionsPerSlice.toLong(),
                wallNanos = profile.resources.cpu.wallTimeGuardNanosPerSlice,
                serverTick = serverTick,
            )
        serviceHostRequests()
        return summary
    }

    private suspend fun serviceHostRequests() {
        for (request in bindings.drainDeviceDaemonHostRequests(daemonHandle)) {
            val result = hostBridge(request)
            bindings.completeDeviceDaemonHostRequest(daemonHandle, request.requestId, result)
        }
    }
}
```

Define `NativeDaemonBindings` as an internal interface implemented by an adapter over `NativeVmBindings`.

- [ ] **Step 4: Run focused core tests**

```bash
./gradlew :core:test --tests '*BackgroundDeviceVmTest.*nativeDaemon*' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Commit Task 9**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt docs/superpowers/plans/2026-05-10-rust-owned-device-daemon.md
git commit -m "feat: add kotlin native daemon runtime bridge"
```

---

## Task 10: Opt-In BackgroundDeviceVm Daemon Boot Path

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] **Step 1: Add failing opt-in boot test**

Add a test proving `ckl.vm.native.daemon=true` routes boot through the daemon bridge:

```kotlin
@Test
fun bootUsesNativeDaemonWhenConfigured() =
    runtimeTestWorkspace("vm-native-daemon-boot") { workspace ->
        System.setProperty("ckl.vm.native.daemon", "true")
        try {
            val daemonBindings = RecordingNativeDaemonBindings()
            val vm = backgroundVmWithNativeDaemonBindings(workspace, daemonBindings)

            assertTrue(vm.boot())
            vm.requestSlice(serverTick = 1)

            assertTrue(daemonBindings.bootCalls.isNotEmpty())
            assertTrue(daemonBindings.tickCalls.isNotEmpty())
        } finally {
            System.clearProperty("ckl.vm.native.daemon")
        }
    }
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
./gradlew :core:test --tests '*BackgroundDeviceVmTest.bootUsesNativeDaemonWhenConfigured' --rerun-tasks
```

Expected: FAIL because `BackgroundDeviceVm` has no daemon path.

- [ ] **Step 3: Wire daemon path behind the flag**

In `BackgroundDeviceVm`, add:

```kotlin
private val nativeDaemonEnabled: Boolean =
    System.getProperty("ckl.vm.native.daemon") == "true"
```

When enabled and native library exists:

- create the daemon handle instead of creating a Kotlin process coroutine for native execution;
- compile boot firmware in Kotlin;
- call `NativeDeviceDaemonRuntime.boot(image, programPath, argument, workingDirectory)`;
- in `requestSlice`, call `nativeDaemonRuntime.requestSlice(serverTick)` and skip `executionQuota.refill(selectedPid)` for daemon mode;
- keep the existing path unchanged when the flag is absent.

- [ ] **Step 4: Run focused tests**

```bash
./gradlew :core:test --tests '*BackgroundDeviceVmTest.bootUsesNativeDaemonWhenConfigured' --tests '*BackgroundDeviceVmTest.parentCanSpawnChildAndExchangeIpcText' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Commit Task 10**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt docs/superpowers/plans/2026-05-10-rust-owned-device-daemon.md
git commit -m "feat: add opt-in native daemon boot path"
```

---

## Task 11: Daemon Profiling Metrics

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`

- [ ] **Step 1: Add failing profiling tests**

Add metrics expectations for:

- native daemon ticks;
- daemon active nanos;
- daemon idle ticks;
- daemon turns;
- daemon halted processes;
- daemon host requests.

Use `RuntimeProfilingTest` to assert collector snapshots and report tests to assert TSV/Markdown rows.

- [ ] **Step 2: Run profiling tests and verify they fail**

```bash
./gradlew :core:test --tests '*RuntimeProfilingTest' --rerun-tasks
./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingProfileCodecTest' --tests '*RuntimeVmProfilingReportFormatterTest' --rerun-tasks
```

Expected: FAIL because daemon metrics do not exist.

- [ ] **Step 3: Add metrics fields and collector method**

Add to `RuntimeMetricsCollector`:

```kotlin
fun recordNativeDaemonTick(
    activeNanos: Long,
    turns: Long,
    halted: Long,
    hostRequests: Long,
    idle: Boolean,
)
```

Add fields to `RuntimeVmMetrics`:

```kotlin
val nativeDaemonTicks: Long = 0
val nativeDaemonActiveNanos: Long = 0
val nativeDaemonIdleTicks: Long = 0
val nativeDaemonTurns: Long = 0
val nativeDaemonHaltedProcesses: Long = 0
val nativeDaemonHostRequests: Long = 0
```

- [ ] **Step 4: Wire daemon runtime metrics**

In `NativeDeviceDaemonRuntime.requestSlice`, measure active time around `tickDeviceDaemon`, record daemon tick metrics,
then service host requests.

- [ ] **Step 5: Extend TSV and Markdown reports**

Append daemon fields to runtime VM TSV rows with zero defaults for old profiles. Add Markdown rows:

- `Native daemon ticks`
- `Native daemon active time`
- `Native daemon idle ticks`
- `Native daemon turns`
- `Native daemon halted processes`
- `Native daemon host requests`

- [ ] **Step 6: Run profiling tests**

```bash
./gradlew :core:test --tests '*RuntimeProfilingTest' --rerun-tasks
./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingProfileCodecTest' --tests '*RuntimeVmProfilingReportFormatterTest' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 7: Commit Task 11**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt docs/superpowers/plans/2026-05-10-rust-owned-device-daemon.md
git commit -m "feat: report native daemon metrics"
```

---

## Task 12: Native Daemon Profile Smoke Workload

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeProfilingWorkload.kt`

- [ ] **Step 1: Add failing daemon smoke workload**

Add a small workload that boots an empty or logging firmware with `ckl.vm.native.daemon=true` and asserts:

- native daemon ticks are greater than zero;
- native daemon turns are greater than zero;
- Kotlin process scheduler permit waits are zero or lower than the non-daemon baseline for the same workload.

- [ ] **Step 2: Run the profiling smoke test and verify it fails**

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.daemon=true :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingReportTest' --rerun-tasks
```

Expected: FAIL until daemon runtime is wired into the profiling test harness.

- [ ] **Step 3: Wire daemon mode into profiling harness**

Ensure the workload sets and clears `ckl.vm.native.daemon` locally around the daemon profile run. Keep existing profile
runs unchanged.

- [ ] **Step 4: Run profile smoke checks**

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.daemon=true :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingReportTest' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Commit Task 12**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeProfilingWorkload.kt docs/superpowers/plans/2026-05-10-rust-owned-device-daemon.md
git commit -m "test: add native daemon profiling smoke workload"
```

---

## Final Verification

Run these after all tasks complete:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml
./gradlew :compiler:test
./gradlew :core:test
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.daemon=true :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingReportTest' --rerun-tasks
```

Expected: all commands pass.

## Implementation Notes

- Keep daemon mode opt-in until terminal workloads pass with `ckl.vm.native.daemon=true`.
- Do not delete the existing `NativeImageVmRunner` path in this plan.
- Do not rewrite the CKL compiler in this plan.
- Keep host request ABI compact and versionable. If the byte format needs to change, add a leading version byte before
  enabling daemon mode in runtime tests.
- Prefer moving behavior into Rust tests first, then expose JNI, then wire Kotlin runtime.
