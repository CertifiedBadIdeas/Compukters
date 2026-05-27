# Rust-Owned Process Spawn Registration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tighten the already-added Rust process table so spawn registration and completion propagation have explicit native success/failure semantics and visible lifecycle metrics, while Kotlin keeps the JVM-backed launch bridge.

**Architecture:** Kotlin still starts the actual child program, but once launch succeeds it registers the child in the Rust-owned process table and later propagates completion back into that same table. Rust already owns process identity, parent/child links, running/completed state, exit codes, and wakeups for native waits; this slice makes that ownership stricter by rejecting invalid duplicate/stale updates instead of silently accepting everything. Kotlin remains the launch bridge and external integration layer, and the runtime profiler records process lifecycle activity so the native boundary stays observable.

**Tech Stack:** Kotlin/JVM, Rust, JNI, CKVM image ABI, Gradle test tasks, native `cargo test`, runtime profiling snapshots and Markdown reports.

---

## File Structure

- Modify `native/ckl-vm/src/runtime_kernel.rs`: tighten native process-table semantics for duplicate registration, unknown completion, completed-state preservation, and safe stale-completion handling.
- Modify `native/ckl-vm/src/jni.rs`: expose native process registration and completion JNI entrypoints with explicit success/failure results.
- Modify `native/ckl-vm/src/image_runner.rs`: keep native `process.wait` on the Rust path and verify it still resolves waits against the tightened process table.
- Modify `native/ckl-vm/tests/image_runner.rs`: add Rust tests for registration, completion, missing pid behavior, and wait wakeups.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`: make the process bridge surface explicit success/failure for registration and completion.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`: cover the JNI-facing process bridge contract.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeProcessBridge.kt`: forward process registration/completion results from JNI back into Kotlin.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`: keep registering spawned children in Rust, propagate completion back into Rust, and record process lifecycle metrics from bridge success/failure.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`: thread the runtime metrics collector into the process manager and keep the native bridge wired only when a native kernel exists.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`: add process registration/completion lifecycle counters to the runtime profiling snapshot.
- Modify `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt`: verify native registration/completion calls and stale completion handling.
- Modify `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`: verify the native bridge is attached only when a native kernel exists and that process lifecycle events still work through the VM.
- Modify `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt`: assert the new process lifecycle counters appear in the runtime summary.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`: add process lifecycle rows to TSV serialization and Markdown rendering.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`: assert the new process lifecycle metrics appear in current and historical reports.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt`: keep TSV round-tripping stable with the new counters.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt`: assert archived run reports contain the new process lifecycle rows.

## Scope Guardrails

This plan stops before moving JVM child launch itself into Rust. Kotlin still starts the child process, but Rust owns the process table that says whether the child exists, whether it is running, what it exited with, and which waiters need to wake up.

The previous `process.wait` slice already added a native process table and basic registration/completion calls. This plan should not reimplement that work. Its value is in making the boundary authoritative: registration succeeds only for a valid new running child, completion succeeds only for a known running child, and Kotlin/profiling can observe when the bridge rejected a stale update.

### Task 1: Tighten Native Process Table Semantics and Return Explicit Success

**Files:**
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeProcessBridge.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`
- Test: `native/ckl-vm/tests/image_runner.rs`

- [ ] **Step 1: Add failing tests for registration/completion return values and stale updates**

Add this Rust test:

```rust
#[test]
fn process_registration_and_completion_report_success() {
    let mut kernel = ckl_vm::runtime_kernel::DeviceRuntimeKernel::new(8, 64);
    assert!(kernel.register_process(2, 1, "shell.ck".to_string()));
    assert_eq!(kernel.process_status(2), ckl_vm::runtime_kernel::ProcessStatus::Running);
    assert!(kernel.complete_process(2, 0));
    assert_eq!(kernel.process_status(2), ckl_vm::runtime_kernel::ProcessStatus::Completed(0));
}

#[test]
fn process_registration_rejects_duplicates_and_completion_rejects_stale_pid() {
    let mut kernel = ckl_vm::runtime_kernel::DeviceRuntimeKernel::new(8, 64);

    assert!(kernel.register_process(2, 1, "first.ck".to_string()));
    assert!(!kernel.register_process(2, 1, "second.ck".to_string()));
    assert!(!kernel.complete_process(99, 1));
    assert!(kernel.complete_process(2, 0));
    assert!(!kernel.complete_process(2, 1));

    assert_eq!(kernel.process_status(2), ckl_vm::runtime_kernel::ProcessStatus::Completed(0));
    assert_eq!(kernel.process_status(99), ckl_vm::runtime_kernel::ProcessStatus::Missing);
}
```

Add this Kotlin test:

```kotlin
@Test
fun nativeProcessBridgeMethodsExposeBooleanSuccess() {
    assertEquals(
        Boolean::class.javaPrimitiveType,
        NativeVmBindings::class.java.getDeclaredMethod(
            "registerProcessNative",
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        ).returnType,
    )
    assertEquals(
        Boolean::class.javaPrimitiveType,
        NativeVmBindings::class.java.getDeclaredMethod(
            "completeProcessNative",
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).returnType,
    )
}
```

- [ ] **Step 2: Run the focused tests and confirm they fail first**

Run:

```bash
cd native/ckl-vm && cargo test process_registration_and_completion_report_success
```

Expected: fail because duplicate registration and stale completion are still accepted by the native table.

Run:

```bash
./gradlew :compiler:test --tests '*NativeImageVmBindingsJniTest' --rerun-tasks
```

Expected: fail because the Kotlin/JNI bridge signatures do not yet expose boolean success.

- [ ] **Step 3: Implement explicit success/failure in the native bridge**

Use these signatures as the contract:

```kotlin
internal interface NativeProcessBridge {
    fun registerProcess(
        pid: Int,
        parentPid: Int,
        programPath: String,
    ): Boolean

    fun completeProcess(
        pid: Int,
        exitCode: Int,
    ): Boolean
}
```

```rust
pub fn register_process(&mut self, pid: i32, parent_pid: i32, program_path: String) -> bool;
pub fn complete_process(&mut self, pid: i32, exit_code: i32) -> bool;
```

Implement native table semantics like this:

```rust
pub fn register_process(&mut self, pid: i32, parent_pid: i32, program_path: String) -> bool {
    if pid <= 0 || self.processes.contains_key(&pid) {
        return false;
    }
    self.processes.insert(
        pid,
        ProcessEntry {
            parent_pid,
            program_path,
            state: ProcessState::Running,
        },
    );
    true
}

pub fn complete_process(&mut self, pid: i32, exit_code: i32) -> bool {
    let Some(entry) = self.processes.get_mut(&pid) else {
        return false;
    };
    if !matches!(entry.state, ProcessState::Running) {
        return false;
    }
    entry.state = ProcessState::Completed { exit_code };
    self.wake_sequence = self.wake_sequence.saturating_add(1);
    true
}
```

Implement JNI so `registerProcessNative` and `completeProcessNative` return `jboolean` based on whether the process table accepted the update.

- [ ] **Step 4: Re-run the focused tests and commit the bridge contract**

Run:

```bash
cd native/ckl-vm && cargo test process_registration_and_completion_report_success
```

Expected: PASS.

Run:

```bash
./gradlew :compiler:test --tests '*NativeImageVmBindingsJniTest' --rerun-tasks
```

Expected: PASS.

Commit:

```bash
git add native/ckl-vm/src/jni.rs native/ckl-vm/tests/image_runner.rs \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt \
  modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeProcessBridge.kt
git commit -m "feat: make native process bridge report success"
```

### Task 2: Thread Native Registration and Completion Through the Kotlin VM Path

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt`

- [ ] **Step 1: Add failing Kotlin tests for process lifecycle metrics**

Add this assertion to `RuntimeProfilingTest.kt`:

```kotlin
assertTrue(
    summary.contains(
        "  process: registrations=1, completions=1, staleCompletions=0"
    ),
    summary,
)
```

Add this test to `VmProcessManagerTest.kt`:

```kotlin
@Test
fun spawnRegistersAndCompletesNativeProcessWithMetrics() {
    val bridge = RecordingNativeProcessBridge()
    val collector = RecordingRuntimeMetricsCollector()
    val manager =
        VmProcessManager(
            scope = scope,
            ctx = ctx,
            deviceId = 42,
            programLoader = programLoader,
            profile = profile,
            runtimeCreator = runtimeCreator,
            compilerMetricsCollector = NoOpCompilerMetricsCollector,
            runtimeMetricsCollector = collector,
            nativeProcessBridge = bridge,
        )

    val pid = manager.spawn("missing.ck", "", "/")

    assertEquals(2, pid)
    assertEquals(1, bridge.registrations.size)
    assertEquals(1, bridge.completions.size)
    assertEquals(1, collector.snapshot().vm.nativeProcessRegistrations)
    assertEquals(1, collector.snapshot().vm.nativeProcessCompletions)
}
```

- [ ] **Step 2: Run the focused Kotlin tests and confirm they fail first**

Run:

```bash
./gradlew :core:test --tests '*VmProcessManagerTest' --tests '*BackgroundDeviceVmTest' --tests '*RuntimeProfilingTest' --rerun-tasks
```

Expected: fail because `RuntimeMetricsCollector` does not yet expose process lifecycle counters and `VmProcessManager`
does not yet record them.

- [ ] **Step 3: Implement the runtime collector and VM wiring**

Add these methods to `RuntimeMetricsCollector`:

```kotlin
fun recordNativeProcessRegistration()
fun recordNativeProcessCompletion()
fun recordNativeProcessStaleCompletion()
```

Add these fields to `RuntimeVmMetrics`:

```kotlin
val nativeProcessRegistrations: Long = 0,
val nativeProcessCompletions: Long = 0,
val nativeProcessStaleCompletions: Long = 0,
```

Thread the collector through `BackgroundDeviceVm` into `VmProcessManager`, and record the metrics when the native bridge
accepts the registration/completion update. Treat stale completions as their own counter so future debugging can
distinguish a real exit from a late callback.

Use these rules in `VmProcessManager`:

```kotlin
val registered = nativeProcessBridge.registerProcess(pid = pid, parentPid = 1, programPath = path)
if (registered) {
    runtimeMetricsCollector.recordNativeProcessRegistration()
}
```

```kotlin
val completed = nativeProcessBridge.completeProcess(pid, code)
if (completed) {
    runtimeMetricsCollector.recordNativeProcessCompletion()
} else if (registered) {
    runtimeMetricsCollector.recordNativeProcessStaleCompletion()
}
```

The no-op fallback bridge should return `false`; that keeps native lifecycle counters scoped to native kernels only.

- [ ] **Step 4: Re-run the focused Kotlin tests and commit the lifecycle wiring**

Run:

```bash
./gradlew :core:test --tests '*VmProcessManagerTest' --tests '*BackgroundDeviceVmTest' --tests '*RuntimeProfilingTest' --rerun-tasks
```

Expected: PASS.

Commit:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt
git commit -m "feat: wire native process lifecycle through the VM"
```

### Task 3: Surface Process Lifecycle Metrics in Profiling Reports

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt`

- [ ] **Step 1: Add failing report assertions for native process lifecycle rows**

Add these assertions to `RuntimeVmProfilingReportFormatterTest.kt`:

```kotlin
assertTrue(markdown.contains("| Native process registrations | 2 | 1 |"), markdown)
assertTrue(markdown.contains("| Native process completions | 2 | 1 |"), markdown)
assertTrue(markdown.contains("| Native process stale completions | 0 | 0 |"), markdown)
```

Add this assertion to `RuntimeVmProfilingReportTest.kt`:

```kotlin
assertContains(markdown, "Native process registrations")
assertContains(markdown, "Native process completions")
```

- [ ] **Step 2: Run the report tests and confirm they fail first**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingReportFormatterTest' --tests '*RuntimeVmProfilingReportTest' --tests '*RuntimeVmProfilingProfileCodecTest' --rerun-tasks
```

Expected: fail because the report format and codec do not yet know about the new process lifecycle counters.

- [ ] **Step 3: Add process lifecycle counters to the profiling snapshot and markdown output**

Extend the `runtimeVm` TSV row and the markdown tables with the new process lifecycle counters:

```kotlin
appendLine("| Native process registrations | ${workload.runtime.vm.nativeProcessRegistrations} |")
appendLine("| Native process completions | ${workload.runtime.vm.nativeProcessCompletions} |")
appendLine("| Native process stale completions | ${workload.runtime.vm.nativeProcessStaleCompletions} |")
```

Make the TSV reader tolerant of older rows by defaulting the new fields to zero when the columns are absent.

- [ ] **Step 4: Re-run the report tests and commit the profiling/report update**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingReportFormatterTest' --tests '*RuntimeVmProfilingReportTest' --tests '*RuntimeVmProfilingProfileCodecTest' --rerun-tasks
```

Expected: PASS.

Commit:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt
git commit -m "feat: report native process lifecycle metrics"
```

### Task 4: Verify Native and Kotlin Paths Together

**Files:**
- Test: `native/ckl-vm/tests/image_runner.rs`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`
- Test: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`

- [ ] **Step 1: Run Rust process-table coverage**

Run:

```bash
cd native/ckl-vm && cargo test process_
```

Expected: PASS.

- [ ] **Step 2: Run Kotlin core coverage with native library enabled**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true :core:test --tests '*VmProcessManagerTest' --tests '*BackgroundDeviceVmTest' --tests '*RuntimeProfilingTest' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 3: Run compiler and profiling-report coverage**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true :compiler:test --tests '*NativeImageVmBindingsJniTest' --rerun-tasks
```

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingReportFormatterTest' --tests '*RuntimeVmProfilingReportTest' --tests '*RuntimeVmProfilingProfileCodecTest' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 4: Confirm the feature branch is clean after verification**

```bash
git status --short
```

Expected: no uncommitted changes except unrelated user edits that were present before executing the plan.
