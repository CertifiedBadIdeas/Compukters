# Rust-Owned Process Table and Wait/Wake Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `process.wait` bookkeeping and child completion wakeups into the Rust-owned native device runtime kernel while keeping JVM child spawning and Minecraft integration in Kotlin.

**Architecture:** Rust becomes the source of truth for the process table, child completion state, and wait queues. Kotlin still launches JVM-backed child programs, but after launch it registers the child in the native table and forwards completion back into Rust so waits can wake natively. Native image runners handle supported process imports directly and fall back to the existing Kotlin host-call path when no native kernel is attached.

**Tech Stack:** Kotlin/JVM, Rust JNI library, CKVM image ABI/signal codec, Gradle tests, native `cargo test`.

---

## File Structure

- Modify `native/ckl-vm/src/runtime_kernel.rs`: add a native process table, process lookup helpers, wait queues, child completion bookkeeping, and process wake notifications.
- Modify `native/ckl-vm/src/signal.rs`: add a dedicated native wait signal for `process.wait`.
- Modify `native/ckl-vm/src/image_runner.rs`: fast-path `process.wait` for native images and return a native wait signal when the target is still running.
- Modify `native/ckl-vm/src/jni.rs`: expose JNI methods for process registration, completion updates, and process wait wake polling from Kotlin.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`: add Kotlin wrappers for the new JNI process methods.
- Create `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeProcessBridge.kt`: define the Kotlin-facing process bridge that `VmProcessManager` uses.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`: register spawned child processes in the native table and forward completion into Rust.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`: wire native process registration/wakeup into the device lifecycle.
- Modify `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`: cover native-wait integration from CKL `process.wait`.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`: cover the new JNI process methods.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerTest.kt`: cover native process wait handling in the Kotlin runner.
- Modify `native/ckl-vm/tests/image_runner.rs`: cover native `process.wait` fast-path and fallback behavior.
- Modify `native/ckl-vm/tests/signal_codec.rs`: cover the new native process wait signal encoding.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`: keep process metrics readable in markdown.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`: add native process wait counters and native wait timing.

## Task 1: Add Native Process Table and Wait Signal in Rust

**Files:**
- Modify: `native/ckl-vm/src/runtime_kernel.rs`
- Modify: `native/ckl-vm/src/signal.rs`
- Test: `native/ckl-vm/tests/image_runner.rs`
- Test: `native/ckl-vm/tests/signal_codec.rs`

- [ ] **Step 1: Write the failing Rust tests**

Add these tests to `native/ckl-vm/tests/image_runner.rs`:

```rust
#[test]
fn native_process_wait_returns_immediately_for_completed_child() {
    let mut kernel = ckl_vm::runtime_kernel::DeviceRuntimeKernel::new(8, 64);
    let child = kernel.register_process(1, Some("child.ck".to_string())).unwrap();
    kernel.mark_process_completed(child, 7).unwrap();

    let result = kernel.wait_for_process(child).unwrap();

    assert_eq!(result, Some(7));
}

#[test]
fn native_process_wait_suspends_for_running_child() {
    let mut kernel = ckl_vm::runtime_kernel::DeviceRuntimeKernel::new(8, 64);
    let child = kernel.register_process(1, Some("child.ck".to_string())).unwrap();

    let result = kernel.wait_for_process(child).unwrap();

    assert!(result.is_none());
}
```

Add this test to `native/ckl-vm/tests/signal_codec.rs`:

```rust
#[test]
fn encodes_wait_process_signal() {
    let bytes = ckl_vm::signal::encode_signal(&ckl_vm::signal::VmSignal::WaitProcess {
        pid: 42,
        wake_sequence: 7,
    });

    assert_eq!(bytes[0], 7);
}
```

- [ ] **Step 2: Run the Rust tests and verify they fail**

Run:

```bash
cd native/ckl-vm && cargo test native_process_wait -- --nocapture
```

Expected: compilation or assertion failures because the native process table and `WaitProcess` signal do not exist yet.

- [ ] **Step 3: Implement the native process table and wait signal**

In `native/ckl-vm/src/runtime_kernel.rs`, add a process table alongside the existing event/IPC/display state:

```rust
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NativeProcessEntry {
    pub pid: i32,
    pub parent_pid: i32,
    pub program: Option<String>,
    pub running: bool,
    pub exit_code: Option<i32>,
}
```

Add a process registry and wait bookkeeping to `DeviceRuntimeKernel`:

```rust
processes: BTreeMap<i32, NativeProcessEntry>,
process_waiters: BTreeMap<i32, bool>,
next_process_id: i32,
process_wake_sequence: i64,
```

Add process methods:

```rust
pub fn register_process(&mut self, parent_pid: i32, program: Option<String>) -> Result<i32, String> {
    let pid = self.next_process_id;
    self.next_process_id = self.next_process_id.saturating_add(1).max(1);
    self.processes.insert(pid, NativeProcessEntry {
        pid,
        parent_pid,
        program,
        running: true,
        exit_code: None,
    });
    Ok(pid)
}

pub fn mark_process_completed(&mut self, pid: i32, exit_code: i32) -> Result<(), String> {
    let entry = self.processes.get_mut(&pid).ok_or_else(|| format!("unknown process: {pid}"))?;
    entry.running = false;
    entry.exit_code = Some(exit_code);
    self.process_wake_sequence = self.process_wake_sequence.saturating_add(1);
    self.process_waiters.insert(pid, true);
    Ok(())
}

pub fn wait_for_process(&mut self, pid: i32) -> Result<Option<i32>, String> {
    let entry = self.processes.get(&pid).ok_or_else(|| format!("unknown process: {pid}"))?;
    if !entry.running {
        return Ok(entry.exit_code);
    }
    Ok(None)
}
```

Extend `VmSignal` in `native/ckl-vm/src/signal.rs`:

```rust
pub enum VmSignal {
    // ...
    WaitProcess {
        pid: i32,
        wake_sequence: i64,
    },
}
```

Add encoding for the new signal with a dedicated tag and update the decoder tests accordingly.

- [ ] **Step 4: Run the Rust tests again and verify they pass**

Run:

```bash
cd native/ckl-vm && cargo test native_process_wait -- --nocapture
```

Expected: the new process table and signal tests pass, and the wait signal is encoded with its own tag.

- [ ] **Step 5: Commit**

```bash
git add native/ckl-vm/src/runtime_kernel.rs native/ckl-vm/src/signal.rs native/ckl-vm/tests/image_runner.rs native/ckl-vm/tests/signal_codec.rs
git commit -m "feat: add native process table and wait signal"
```

## Task 2: Fast-Path `process.wait` in the Native Image Runner

**Files:**
- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Test: `native/ckl-vm/tests/image_runner.rs`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerTest.kt`

- [ ] **Step 1: Write failing integration tests for native `process.wait`**

Add this Rust test to `native/ckl-vm/tests/image_runner.rs`:

```rust
#[test]
fn attached_kernel_waits_process_without_generic_host_call() {
    let kernel = std::sync::Arc::new(ckl_vm::runtime_kernel::DeviceRuntimeKernelHandle::new(8, 64));
    let child = kernel
        .with_kernel_mut(|kernel| kernel.register_process(1, Some("child.ck".to_string())))
        .unwrap()
        .unwrap();

    let mut code = Vec::new();
    push_constant(&mut code, 0);
    call_host(&mut code, 1200, 1);
    code.push(OP_RETURN);

    let mut vm = ckl_vm::image_runner::ImageVmHandle::create(
        &image_with_constants_host_imports_and_code(
            vec![ConstantFixture::Int(child)],
            vec![HostImportFixture {
                id: 1200,
                module_name: "process".to_string(),
                function_name: "wait".to_string(),
                parameter_types: vec!["Int".to_string()],
                return_type: "Int".to_string(),
            }],
            code,
        ),
        64,
    )
    .unwrap();
    vm.attach_kernel(kernel);

    match vm.run_until_signal().unwrap() {
        ckl_vm::signal::VmSignal::WaitProcess { pid, .. } => assert_eq!(pid, child),
        other => panic!("expected WaitProcess signal, got {other:?}"),
    }
}
```

Add a Kotlin binding test in `NativeImageVmBindingsJniTest.kt` that verifies the new JNI methods are present and can
register a process, mark it completed, and return the exit code to Kotlin.

Add a Kotlin runner test in `NativeImageVmRunnerTest.kt` that verifies a native `process.wait` suspend/resume path does
not fall back to a generic host call when the native kernel is attached.

- [ ] **Step 2: Run the tests and verify they fail**

Run:

```bash
./gradlew :compiler:test --tests '*NativeImageVmBindingsJniTest' --tests '*NativeImageVmRunnerTest' --rerun-tasks
```

Expected: failures because the JNI process methods and `WaitProcess` handling do not exist yet.

- [ ] **Step 3: Implement the native fast-path**

In `native/ckl-vm/src/image_runner.rs`, add a `process` module branch:

```rust
if module_name == "process" {
    return match function_name {
        "wait" => {
            let pid = int_argument(&arguments, 0, "process.wait pid")?;
            let mut kernel = kernel_handle.lock()?;
            if let Some(exit_code) = kernel.wait_for_process(pid)? {
                Ok(NativeHostImportResult::Handled(VmValue::Int(exit_code)))
            } else {
                let wake_sequence = kernel.process_wake_sequence();
                Ok(NativeHostImportResult::SignalNoResume {
                    signal: VmSignal::WaitProcess { pid, wake_sequence },
                    arguments,
                })
            }
        }
        _ => Ok(NativeHostImportResult::Fallback(arguments)),
    };
}
```

Add the corresponding JNI exports in `native/ckl-vm/src/jni.rs`:

```rust
#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_registerNativeProcessNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    parent_pid: jint,
    program: JString<'_>,
) -> jint { ... }

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_completeNativeProcessNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    pid: jint,
    exit_code: jint,
) { ... }
```

Add Kotlin wrappers in `NativeVmBindings.kt` that expose the new JNI methods with the same argument validation style as
the existing display/kernel methods.

- [ ] **Step 4: Run the tests again and verify they pass**

Run:

```bash
./gradlew :compiler:test --tests '*NativeImageVmBindingsJniTest' --tests '*NativeImageVmRunnerTest' --rerun-tasks
```

Expected: JNI/native runner tests pass and native `process.wait` returns a wait signal or exit code as appropriate.

- [ ] **Step 5: Commit**

```bash
git add native/ckl-vm/src/image_runner.rs native/ckl-vm/src/jni.rs modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt
git commit -m "feat: fast-path native process wait"
```

## Task 3: Wire Kotlin Child Spawning and Completion into the Native Table

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeProcessBridge.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] **Step 1: Write failing Kotlin tests for registration and completion forwarding**

Create `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeProcessBridge.kt`:

```kotlin
internal interface NativeProcessBridge {
    fun registerProcess(
        pid: Int,
        parentPid: Int,
        programPath: String,
    )
    fun completeProcess(pid: Int, exitCode: Int)
}
```

Add a `BackgroundDeviceVmTest.kt` test that uses the same `runtimeTestWorkspace` pattern as `parentCanSpawnChildAndExchangeIpcText`.
The test should write a tiny child program, boot a VM with the native library configured so the native kernel is
available, spawn the child with `process::spawn`, wait with `process::wait`, and assert that the waiting parent logs
the child exit code after the child exits.

Use this CKL parent program in the test:

```kotlin
pub fun main() {
    val pid: Int = process::spawn("child.ck", "")
    val code: Int = process::wait(pid)
    system::log("child-code=" + code)
    while true { sleep(20L) }
}
```

Use this CKL child program in the test:

```kotlin
pub fun main() {
    system::log("child-running")
}
```

- [ ] **Step 2: Run the Kotlin tests and verify they fail**

Run:

```bash
./gradlew :core:test --tests '*VmProcessManagerTest' --tests '*BackgroundDeviceVmTest' --rerun-tasks
```

Expected: the test code fails because `VmProcessManager` does not yet talk to the native process table.

- [ ] **Step 3: Implement the Kotlin bridge**

In `VmProcessManager.kt`, after successful `spawn`, register the child in the native process table using the same pid:

```kotlin
nativeProcessBridge.registerProcess(pid = pid, parentPid = 1, programPath = path)
```

On child completion, forward the exit code into Rust:

```kotlin
nativeProcessBridge.completeProcess(nativePid, code)
```

Keep Kotlin fallback behavior for non-native runs unchanged.

In `BackgroundDeviceVm.kt`, ensure the native kernel handle is available to `VmProcessManager` and is cleared or
cancelled with the same lifecycle as the existing device teardown path.

- [ ] **Step 4: Run the Kotlin tests again and verify they pass**

Run:

```bash
./gradlew :core:test --tests '*VmProcessManagerTest' --tests '*BackgroundDeviceVmTest' --rerun-tasks
```

Expected: the process manager test and the native wait integration test both pass.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt
git commit -m "feat: register spawned processes in native table"
```

## Task 4: Add Profiling and Report Coverage for Native Process Waits

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt`

- [ ] **Step 1: Write failing profiling tests**

Add assertions to `RuntimeProfilingTest.kt` that a profile snapshot can report native process waits separately from total
host-call waits:

```kotlin
assertTrue(snapshot.vm.nativeProcessWaitCalls >= 0)
assertTrue(snapshot.vm.nativeProcessWaitNanos >= 0)
```

Extend the markdown formatter test to check for a row like:

```kotlin
assertTrue(markdown.contains("| Native process waits |"), markdown)
```

- [ ] **Step 2: Run the profiling tests and verify they fail**

Run:

```bash
./gradlew :core:test --tests '*RuntimeProfilingTest' --tests '*RuntimeVmProfilingReportFormatterTest' --rerun-tasks
```

Expected: failures because the new process wait fields are not present yet.

- [ ] **Step 3: Implement the profiling counters and report rows**

In `RuntimeProfiling.kt`, add counters and snapshot fields for:

```kotlin
nativeProcessWaitCalls: Long
nativeProcessWaitNanos: Long
nativeProcessWaitWakeups: Long
```

In `RuntimeVmProfilingReport.kt`, include a row for native process wait counts/timing and keep historical comparison
columns readable.

- [ ] **Step 4: Run the profiling tests again and verify they pass**

Run:

```bash
./gradlew :core:test --tests '*RuntimeProfilingTest' --tests '*RuntimeVmProfilingReportFormatterTest' --rerun-tasks
```

Expected: profiling tests pass and the markdown report includes native process wait metrics.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt
git commit -m "feat: report native process wait metrics"
```

## Self-Review Checklist

- The plan covers the native process table, native wait signal, Kotlin spawn bridge, and profiling/report updates.
- There are no placeholder tasks or vague “handle edge cases” statements.
- The file list matches the concrete code paths currently present in the repository.
- The plan stays focused on `process.wait` and child completion wakeups instead of jumping to full process migration.
