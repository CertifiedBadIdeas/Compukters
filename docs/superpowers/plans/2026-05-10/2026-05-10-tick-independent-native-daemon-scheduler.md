# Tick-Independent Native Daemon Scheduler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make native daemon process scheduling event-driven instead of one-process-per-Minecraft-tick.

**Architecture:** Rust keeps owning scheduler decisions and process handoffs. Kotlin refills daemon quota on server ticks
and wakes a serialized daemon executor coroutine when quota, input events, or host/compile completions can make progress.
The first implementation keeps the existing tick entry point as a compatibility wrapper while adding split refill/run
operations for the new executor.

**Tech Stack:** Rust native VM daemon, JNI, Kotlin coroutines, Gradle/JUnit tests, existing runtime profiling reports.

---

## File Structure

- `native/ckl-vm/src/device_daemon.rs`
  - Add quota refill and multi-turn execution methods.
  - Keep `tick(...)` as a compatibility wrapper.
  - Add Rust unit tests for multi-process progress inside one daemon run.
- `native/ckl-vm/src/jni.rs`
  - Add JNI bindings for `refillDeviceDaemonQuotaNative` and `runDeviceDaemonReadyNative`.
  - Reuse the existing daemon summary long-array layout.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
  - Add Kotlin binding methods for quota refill and ready-run.
  - Add facade methods so tests can fake the daemon.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt`
  - Split `requestSlice` into `refillQuota` and `runReadyUntilBlocked`.
  - Keep request servicing close to the daemon run loop.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
  - Add a serialized daemon executor coroutine.
  - Wake the executor from server ticks, accepted events, and daemon boot.
- `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`
  - Add JNI coverage for split quota/run methods.
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`
  - Add/fix tests for event wake and multi-turn daemon execution if the existing fake bindings support it.
- `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt`
  - Add an assertion or profile field showing daemon turns can exceed server tick count in terminal workloads.

---

## Task 1: Native Daemon Multi-Turn Execution

**Files:**
- Modify: `native/ckl-vm/src/device_daemon.rs`

- [x] **Step 1: Add failing Rust test for same-pass terminal/shell handoff**

Add a unit test near existing daemon process tests in `native/ckl-vm/src/device_daemon.rs`:

```rust
#[test]
fn daemon_run_ready_until_blocked_runs_multiple_processes_in_one_pass() {
    let mut daemon = DeviceDaemon::new(16, 1024, 128);
    daemon.boot_image(&ckim_yields_then_halts(), "/rom/terminal.ck", "", "");
    daemon
        .attach_child_image_for_test(2, 1, "/rom/shell.ck", &ckim_yields_then_halts(), "")
        .expect("child image should attach");

    daemon.refill_execution_quota(16, 1_000_000, 70);
    let summary = daemon.run_ready_until_blocked(16);

    assert_eq!(summary.server_tick, 70);
    assert_eq!(summary.turns, 4);
    assert_eq!(summary.halted, 2);
    assert!(summary.idle);
    assert_eq!(daemon.process_status(1), DeviceDaemonProcessStatus::Completed(0));
    assert_eq!(daemon.process_status(2), DeviceDaemonProcessStatus::Completed(0));
}
```

Also add this test helper under `impl DeviceDaemon` behind `#[cfg(test)]`:

```rust
#[cfg(test)]
fn attach_child_image_for_test(
    &mut self,
    pid: i32,
    parent_pid: i32,
    path: &str,
    image_bytes: &[u8],
    argument: &str,
) -> Result<(), String> {
    let pending = PendingCompile {
        parent_pid,
        child_pid: pid,
        path: path.to_string(),
        argument: argument.to_string(),
        working_directory: String::new(),
        mode: PendingCompileMode::Spawn,
    };
    self.attach_child_image(&pending, image_bytes)?;
    Ok(())
}
```

- [x] **Step 2: Run the Rust daemon test and confirm it fails**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml daemon_run_ready_until_blocked_runs_multiple_processes_in_one_pass
```

Expected: fail to compile because `refill_execution_quota` and `run_ready_until_blocked` do not exist.

- [x] **Step 3: Implement split refill and multi-turn run**

In `impl DeviceDaemon`, add:

```rust
pub fn refill_execution_quota(
    &mut self,
    instructions: i64,
    wall_nanos: i64,
    server_tick: i64,
) {
    self.kernel
        .with_kernel_mut(|kernel| {
            kernel.add_execution_quota(instructions, wall_nanos, server_tick);
        })
        .expect("daemon kernel must be lockable");
}

pub fn run_ready_until_blocked(&mut self, max_turns: i64) -> DeviceDaemonTickSummary {
    let mut turns = 0;
    let mut halted = 0;
    let mut host_requests = 0;
    let mut remaining_instructions = 0;
    let mut server_tick = 0;
    let max_turns = max_turns.max(1);

    while turns < max_turns {
        let step = self
            .kernel
            .with_kernel_mut(|kernel| kernel.run_scheduler_step())
            .expect("daemon kernel must be lockable");
        server_tick = step.server_tick;
        remaining_instructions = step.remaining_instructions;
        let Some(pid) = step.selected_pid else {
            return DeviceDaemonTickSummary {
                server_tick,
                turns,
                remaining_instructions,
                idle: true,
                halted,
                host_requests,
            };
        };

        turns += 1;
        let signal = match self.images.get_mut(&pid) {
            Some(image) => image.run_until_signal_decoded(),
            None => Err(format!("daemon selected pid {pid} without image")),
        };
        match signal {
            Ok(signal) => match self.handle_signal(pid, signal, server_tick) {
                Ok(DaemonSignalOutcome::Halted) => halted += 1,
                Ok(DaemonSignalOutcome::HostRequest) => host_requests += 1,
                Ok(DaemonSignalOutcome::Runnable | DaemonSignalOutcome::Waiting) => {}
                Err(message) => self.crash_process(pid, message),
            },
            Err(message) => self.crash_process(pid, message),
        }
    }

    DeviceDaemonTickSummary {
        server_tick,
        turns,
        remaining_instructions,
        idle: false,
        halted,
        host_requests,
    }
}

fn crash_process(&mut self, pid: i32, message: String) {
    let _ = self
        .kernel
        .with_kernel_mut(|kernel| kernel.mark_process_crashed(pid, message));
    self.images.remove(&pid);
    self.image_handles.remove(&pid);
}
```

Update `tick(...)` to:

```rust
pub fn tick(
    &mut self,
    instructions: i64,
    wall_nanos: i64,
    server_tick: i64,
) -> DeviceDaemonTickSummary {
    self.refill_execution_quota(instructions, wall_nanos, server_tick);
    self.run_ready_until_blocked(self.instruction_budget as i64)
}
```

- [x] **Step 4: Run Rust daemon tests**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml device_daemon
```

Expected: all `device_daemon` tests pass.

- [x] **Step 5: Commit**

```bash
git add native/ckl-vm/src/device_daemon.rs
git commit -m "feat: run daemon processes until blocked"
```

---

## Task 2: JNI And Kotlin Split Scheduler API

**Files:**
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [x] **Step 1: Add JNI test for split refill/run API**

Add a test in `NativeImageVmBindingsJniTest.kt`:

```kotlin
@Test
fun nativeDeviceDaemonCanRefillQuotaAndRunReadyProcessesSeparately() {
    val handle = NativeVmBindings.createDeviceDaemon(
        maxEventQueueSize = 16,
        maxBufferedBytesPerChannel = 1024,
        instructionBudget = 64,
    )
    try {
        val image = compileImage("pub fun main() { yield(); }")
        NativeVmBindings.bootDeviceDaemon(handle, image, "/rom/boot.ck", "", "")

        NativeVmBindings.refillDeviceDaemonQuota(handle, instructions = 8, wallNanos = 1_000_000, serverTick = 91)
        val first = NativeVmBindings.runDeviceDaemonReady(handle, maxTurns = 8)

        assertEquals(91, first.serverTick)
        assertTrue(first.turns >= 1)
        assertTrue(first.remainingInstructions >= 0)
    } finally {
        NativeVmBindings.freeDeviceDaemon(handle)
    }
}
```

- [x] **Step 2: Run the JNI test and confirm it fails**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests "*NativeImageVmBindingsJniTest.nativeDeviceDaemonCanRefillQuotaAndRunReadyProcessesSeparately" --rerun-tasks
```

Expected: fail to compile because the Kotlin binding methods do not exist.

- [x] **Step 3: Add JNI native functions**

In `native/ckl-vm/src/jni.rs`, add functions next to `tickDeviceDaemonNative`:

```rust
#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_refillDeviceDaemonQuotaNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    instructions: jlong,
    wall_nanos: jlong,
    server_tick: jlong,
) {
    let _ = with_device_daemon_mut(&mut env, handle, |daemon| {
        daemon.refill_execution_quota(instructions, wall_nanos, server_tick);
    });
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runDeviceDaemonReadyNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    max_turns: jlong,
) -> jlongArray {
    let summary = match with_device_daemon_mut(&mut env, handle, |daemon| {
        daemon.run_ready_until_blocked(max_turns)
    }) {
        Some(summary) => summary,
        None => return null_mut(),
    };
    long_array_or_throw(
        &mut env,
        &[
            summary.server_tick,
            summary.turns,
            summary.remaining_instructions,
            i64::from(summary.idle),
            summary.halted,
            summary.host_requests,
        ],
    )
}
```

- [x] **Step 4: Add Kotlin binding methods**

In `NativeVmBindings.kt`, add public methods:

```kotlin
fun refillDeviceDaemonQuota(
    daemonHandle: Long,
    instructions: Long,
    wallNanos: Long,
    serverTick: Long,
) {
    require(daemonHandle != 0L) { "Native device daemon handle is zero" }
    refillDeviceDaemonQuotaNative(daemonHandle, instructions, wallNanos, serverTick)
}

fun runDeviceDaemonReady(
    daemonHandle: Long,
    maxTurns: Long,
): NativeDeviceDaemonTickSummary {
    require(daemonHandle != 0L) { "Native device daemon handle is zero" }
    return runDeviceDaemonReadyNative(daemonHandle, maxTurns.coerceAtLeast(1)).toNativeDeviceDaemonTickSummary()
}
```

Add external declarations:

```kotlin
private external fun refillDeviceDaemonQuotaNative(
    daemonHandle: Long,
    instructions: Long,
    wallNanos: Long,
    serverTick: Long,
)

private external fun runDeviceDaemonReadyNative(
    daemonHandle: Long,
    maxTurns: Long,
): LongArray
```

- [x] **Step 5: Extend `NativeDaemonBindings` facade**

In `NativeDeviceDaemonRuntime.kt`, add to `NativeDaemonBindings`:

```kotlin
fun refillDeviceDaemonQuota(
    daemonHandle: Long,
    instructions: Long,
    wallNanos: Long,
    serverTick: Long,
)

fun runDeviceDaemonReady(
    daemonHandle: Long,
    maxTurns: Long,
): NativeDeviceDaemonTickSummary
```

Implement them in `NativeVmDaemonBindings` by delegating to `NativeVmBindings`.

- [x] **Step 6: Run JNI test**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests "*NativeImageVmBindingsJniTest.nativeDeviceDaemonCanRefillQuotaAndRunReadyProcessesSeparately" --rerun-tasks
```

Expected: pass.

- [x] **Step 7: Commit**

```bash
git add native/ckl-vm/src/jni.rs modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt
git commit -m "feat: expose split daemon scheduler api"
```

---

## Task 3: Kotlin Daemon Executor Pump

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [x] **Step 1: Add a core test for event-triggered daemon execution**

Add a fake-binding test that boots a daemon device, calls `enqueueEvent`, and verifies the fake binding saw
`runDeviceDaemonReady` without a following `requestSlice`.

Expected shape:

```kotlin
@Test
fun nativeDaemonExecutorRunsAfterAcceptedEventWithoutWaitingForNextSlice() = runTest {
    val bindings = RecordingNativeDaemonBindings()
    val vm = backgroundDeviceVm(nativeDaemonBindings = bindings)

    assertTrue(vm.boot())
    vm.requestSlice(1)
    bindings.clearRunCalls()

    vm.enqueueEvent(VmEvent("char", listOf("a")))
    advanceUntilIdle()

    assertTrue(bindings.runReadyCalls.isNotEmpty())
}
```

If `BackgroundDeviceVmTest` uses a different helper name, add the same assertion to the existing native daemon fake test
class instead of creating a parallel fixture.

- [x] **Step 2: Run core test and confirm it fails**

Run:

```bash
./gradlew :core:test --tests "*BackgroundDeviceVmTest.nativeDaemonExecutorRunsAfterAcceptedEventWithoutWaitingForNextSlice" --rerun-tasks
```

Expected: fail because accepted events only enqueue into Rust and do not wake a daemon executor.

- [x] **Step 3: Split runtime methods**

In `NativeDeviceDaemonRuntime.kt`, replace `requestSlice(serverTick)` implementation with:

```kotlin
fun refillQuota(serverTick: Long) {
    bindings.refillDeviceDaemonQuota(
        daemonHandle = daemonHandle,
        instructions = profile.resources.cpu.instructionsPerSlice.toLong(),
        wallNanos = profile.resources.cpu.wallTimeGuardNanosPerSlice,
        serverTick = serverTick,
    )
}

suspend fun runReadyUntilBlocked(): NativeDeviceDaemonTickSummary {
    val started = System.nanoTime()
    val summary =
        bindings.runDeviceDaemonReady(
            daemonHandle = daemonHandle,
            maxTurns = profile.resources.cpu.instructionsPerSlice.toLong(),
        )
    runtimeMetricsCollector.recordNativeDaemonTick(
        activeNanos = System.nanoTime() - started,
        turns = summary.turns,
        halted = summary.halted,
        hostRequests = summary.hostRequests,
        idle = summary.idle,
    )
    serviceHostRequests()
    return summary
}
```

Keep `requestSlice(serverTick)` as:

```kotlin
suspend fun requestSlice(serverTick: Long): NativeDeviceDaemonTickSummary {
    refillQuota(serverTick)
    return runReadyUntilBlocked()
}
```

- [x] **Step 4: Add serialized daemon executor in `BackgroundDeviceVm`**

Add fields:

```kotlin
private val daemonWakeSignal = kotlinx.coroutines.channels.Channel<Unit>(capacity = kotlinx.coroutines.channels.Channel.CONFLATED)
private var daemonExecutor: Job? = null
```

Add helper:

```kotlin
private fun wakeNativeDaemonExecutor() {
    if (nativeDaemonRuntime != null) {
        daemonWakeSignal.trySend(Unit)
    }
}
```

Start the executor when daemon runtime exists:

```kotlin
private fun startNativeDaemonExecutor() {
    if (nativeDaemonRuntime == null || daemonExecutor?.isActive == true) return
    daemonExecutor =
        scope.launch {
            for (ignored in daemonWakeSignal) {
                var keepRunning = true
                while (keepRunning && isActive) {
                    val summary = nativeDaemonRuntime.runReadyUntilBlocked()
                    runtimeMetricsCollector.recordSliceRequest(sent = !summary.idle, sleepGated = false)
                    keepRunning = summary.turns > 0 || summary.hostRequests > 0
                }
            }
        }
}
```

Call `startNativeDaemonExecutor()` from daemon boot path after boot image is attached.

- [x] **Step 5: Wake executor from slice and event paths**

In `requestSlice(serverTick)`, daemon branch becomes:

```kotlin
nativeDaemonRuntime?.let { daemon ->
    daemon.refillQuota(serverTick)
    wakeNativeDaemonExecutor()
    return
}
```

In `enqueueEvent(event)`, after `nativeDaemonRuntime?.enqueueEvent(event)`, call:

```kotlin
wakeNativeDaemonExecutor()
```

In the daemon boot path, after `enqueueEvent(VmEvent("boot"))`, call `wakeNativeDaemonExecutor()`.

- [x] **Step 6: Run core test**

Run:

```bash
./gradlew :core:test --tests "*BackgroundDeviceVmTest.nativeDaemonExecutorRunsAfterAcceptedEventWithoutWaitingForNextSlice" --rerun-tasks
```

Expected: pass.

- [x] **Step 7: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt
git commit -m "feat: run native daemon from event-driven executor"
```

---

## Task 4: Scheduler Metrics And Profiling

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/RuntimeMetricsCollector.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt`
- Modify profiling report writer files located by `rg -n "nativeDaemon|recordNativeDaemonTick|RuntimeVmProfile" modules`.

- [x] **Step 1: Add profiling assertion for multi-turn daemon passes**

In `RuntimeVmProfilingReportTest.kt`, add to daemon smoke or terminal workload assertions:

```kotlin
assertTrue(
    run.metrics.nativeDaemonTurns > run.metrics.nativeDaemonTickCount,
    "native daemon should run more process turns than server tick calls under terminal workload",
)
```

Use the existing metric names if `RuntimeMetricsSnapshot` already exposes different fields.

- [x] **Step 2: Run profiling test and confirm it fails**

Run:

```bash
./gradlew profileRuntimeVmImage
```

Expected: fail until daemon executor metrics expose the needed counts or until the workload observes multi-turn passes.

- [x] **Step 3: Record executor pass metrics**

Extend runtime metrics so each daemon executor pass records:

```text
nativeDaemonExecutorPasses += 1
nativeDaemonExecutorTurns += summary.turns
nativeDaemonExecutorHostRequests += summary.hostRequests
nativeDaemonExecutorIdleStops += if (summary.idle) 1 else 0
```

Add Markdown rows:

```text
native daemon executor:
  passes
  turns
  avg turns/pass
  idle stops
  host requests
```

- [x] **Step 4: Run profiling comparison**

Run:

```bash
./gradlew profileRuntimeVmComparison
```

Expected: task passes and Markdown report shows daemon executor pass/turn counts.

- [x] **Step 5: Commit**

```bash
git add modules docs
git commit -m "test: profile tick-independent daemon scheduling"
```

---

## Task 5: Full Verification And Plan Completion

**Files:**
- Modify: `docs/superpowers/plans/2026-05-10/2026-05-10-tick-independent-native-daemon-scheduler.md`

Verification notes:

- `cargo test --manifest-path native/ckl-vm/Cargo.toml` passes.
- `./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests "*NativeImageVmBindingsJniTest" --rerun-tasks` passes.
- `./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :core:test` passes.
- `./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true -Dckl.vm.native.daemon=true :v1_21_1-neoforge:test` passes.
- `./gradlew profileRuntimeVmComparison` passes.
- `./gradlew :core:test` without a native library still does not prove this slice because the current image backend requires
  `-Dckl.vm.native.library` for image runner tests.
- Non-daemon `-Dckl.vm.native.display=true` terminal display failures are tracked as a separate display/runtime mode concern;
  this plan verifies the daemon scheduler path.

- [x] **Step 1: Run Rust tests**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml
```

Expected: all Rust tests pass.

- [x] **Step 2: Run compiler JNI tests**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests "*NativeImageVmBindingsJniTest" --rerun-tasks
```

Expected: all selected compiler JNI tests pass.

- [x] **Step 3: Run core tests**

Run:

```bash
./gradlew :core:test
```

Expected: all core tests pass.

- [x] **Step 4: Run NeoForge tests**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true :v1_21_1-neoforge:test
```

Expected: all NeoForge tests pass.

- [x] **Step 5: Mark completed plan tasks**

Change each completed task checkbox in this file from `- [ ]` to `- [x]`.

- [x] **Step 6: Commit plan completion**

```bash
git add docs/superpowers/plans/2026-05-10/2026-05-10-tick-independent-native-daemon-scheduler.md
git commit -m "docs: complete tick-independent daemon scheduler plan"
```
