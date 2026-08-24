# Guest Suspend Call Execution Implementation Plan

> Issue: [#525](https://github.com/CertifiedBadIdeas/Compukters/issues/525)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute compiler-produced guest `CallSuspend` instructions on the bounded Rust frame stack, including asynchronous capability waits and exact continuation resumption.

**Architecture:** Resolve `CallSuspend` beside `CallDirect`, but store an explicit `{block, instruction}` caller continuation in each callee frame. Reuse preallocated frame/register storage and the existing capability suspension path; do not add coroutine objects, scheduler state, or a bytecode revision.

**Tech Stack:** Rust 2024, Compukter-VM execution image and machine, Kotlin 2.4 K2, Gradle compiler-to-VM conformance.

---

### Task 1: Prove the missing runtime behavior

**Files:**
- Modify: `host/compukter-vm/src/execution/fixtures.rs`
- Modify: `host/compukter-vm/src/execution/tests.rs`

- [ ] **Step 1: Add a verified suspend-call fixture**

Use the existing fixture builders to encode a suspending entry with `CallSuspend`, a value-returning suspending child, and an entry resume block:

```rust
// entry block 0: call_suspend r0, child(), resume block 1
// entry block 1: return r0
// child block 0: const r0 = 42; return r0
Instruction::CallSuspend {
    dst: 0,
    function_ref: local_function_ref(1),
    args: Box::new([]),
    resume_block: 1,
}
```

- [ ] **Step 2: Add execution and overflow tests**

```rust
#[test]
fn suspend_call_returns_through_the_encoded_resume_block() {
    let mut machine = fixtures::started_zero_arg(fixtures::suspend_value_call_artifact());
    assert_eq!(
        Outcome::Halted(Some(RuntimeValue::I32(42))),
        machine.run_slice(128, 0).unwrap(),
    );
    assert_eq!(2, machine.maximum_observed_frame_depth_for_test());
}

#[test]
fn suspend_call_obeys_the_existing_call_depth_limit() {
    let mut profile = fixtures::profile();
    profile.maximum_call_depth = 1;
    let mut machine = fixtures::started_with_profile(
        fixtures::suspend_value_call_artifact(),
        profile,
    );
    assert_eq!(
        Outcome::Crashed(GuestTrap::StackOverflow),
        machine.run_slice(128, 0).unwrap(),
    );
    assert_eq!(1, machine.frame_depth());
}
```

- [ ] **Step 3: Run the tests and verify RED**

Run from `host/compukter-vm`:

```bash
CARGO_TARGET_DIR=../../.toolchain/build/cargo/compukter-vm cargo test --locked --offline suspend_call -- --nocapture
```

Expected: FAIL during execution-image admission with `InvalidEntry`.

### Task 2: Resolve and execute suspend calls

**Files:**
- Modify: `host/compukter-vm/src/execution/image.rs`
- Modify: `host/compukter-vm/src/execution/machine.rs`

- [ ] **Step 1: Add the resolved instruction**

```rust
CallSuspend {
    dst: u16,
    target: usize,
    args: Box<[u16]>,
    resume_block: usize,
},
```

Resolve the target with the same checked offset arithmetic as `CallDirect` and resolve the function-local resume block with the existing block closure.

- [ ] **Step 2: Make frame continuations explicit**

```rust
pub(super) struct Frame {
    pub(super) function: usize,
    pub(super) block: usize,
    pub(super) instruction: usize,
    pub(super) caller_block: usize,
    pub(super) caller_instruction: usize,
    pub(super) destination: u16,
}
```

Initialize entry and empty sentinels consistently. `CallDirect` stores the current block plus `instruction_index + 1`; `CallSuspend` stores `resume_block` plus instruction `0`.

- [ ] **Step 3: Share bounded call entry**

Introduce a private helper with this responsibility and signature:

```rust
fn enter_call(
    &mut self,
    caller_index: usize,
    target: usize,
    args: &[u16],
    destination: u16,
    continuation_block: usize,
    continuation_instruction: usize,
) -> Result<(), CallEntryFailure>
```

It validates arguments before mutation, checks depth before exposing a frame, clears the preallocated register window, copies arguments, installs the callee frame, and updates maximum observed depth. Map depth exhaustion to `StackOverflow` and corrupt storage to existing VM faults.

- [ ] **Step 4: Restore both coordinates on return**

After clearing and popping the callee:

```rust
self.frames[caller_index].block = continuation_block;
self.frames[caller_index].instruction = continuation_instruction;
```

Then publish the optional result to the caller destination.

- [ ] **Step 5: Run focused tests and verify GREEN**

```bash
CARGO_TARGET_DIR=../../.toolchain/build/cargo/compukter-vm cargo test --locked --offline suspend_call -- --nocapture
CARGO_TARGET_DIR=../../.toolchain/build/cargo/compukter-vm cargo test --locked --offline direct_calls_copy_arguments_and_publish_results_on_return
CARGO_TARGET_DIR=../../.toolchain/build/cargo/compukter-vm cargo test --locked --offline stack_overflow_happens_before_a_new_frame_exists
```

Expected: all tests PASS.

- [ ] **Step 6: Commit the VM implementation**

Run from `host/compukter-vm`:

```bash
git add src/execution/fixtures.rs src/execution/tests.rs src/execution/image.rs src/execution/machine.rs
git commit -m "feat(execution): execute guest suspend calls (#525)"
```

### Task 3: Prove asynchronous suspension inside the callee

**Files:**
- Modify: `host/compukter-vm/src/execution/fixtures.rs`
- Modify: `host/compukter-vm/src/execution/session_tests.rs`

- [ ] **Step 1: Add a nested capability fixture**

Encode this control flow with one asynchronous `I32` capability result:

```text
entry:        call_suspend child -> entry.resume
child:        capability_call_async read -> child.resume
child.resume: return response
entry.resume: return child result
```

- [ ] **Step 2: Add the session test**

```rust
#[test]
fn suspend_callee_can_wait_for_and_return_a_host_response() {
    let mut session = fixtures::suspend_capability_session();
    session.start(&[]).unwrap();
    let request_id = match session.advance(128, 0).unwrap() {
        AdvanceOutcome::HostRequest(request) => request.id(),
        other => panic!("expected host request, got {other:?}"),
    };
    session
        .resume(request_id, HostResponse::Success(HostValueInput::I32(7)))
        .unwrap();
    assert_halts_with_i32(&mut session, 7);
}
```

Use the neighboring session-test assertion style rather than adding public VM APIs.

- [ ] **Step 3: Run and commit the test**

```bash
CARGO_TARGET_DIR=../../.toolchain/build/cargo/compukter-vm cargo test --locked --offline suspend_callee_can_wait -- --nocapture
git add src/execution/fixtures.rs src/execution/session_tests.rs
git commit -m "test(execution): cover nested suspend capability calls (#525)"
```

Expected: PASS with one host request and final value `7`.

### Task 4: Add compiler-produced vertical conformance

**Files:**
- Modify: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt`
- Modify: `modules/compiler-k2/build.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `modules/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs`

- [ ] **Step 1: Generate a deterministic K2 artifact**

```kotlin
import compukter.terminal.Terminal

suspend fun readKind(): Int {
    Terminal.awaitEvent()
    return Terminal.eventKey()
}

suspend fun main() {
    Terminal.write(if (readKind() == 13) "enter" else "other")
}
```

Compile twice, compare bytes, and write to the `compukter.vm.suspendCallArtifact` property path.

- [ ] **Step 2: Add isolated Gradle tasks**

Register `generateSuspendCallConformanceArtifact` in `modules/compiler-k2/build.gradle.kts`. Register root `testKotlinSuspendCallVmConformance`, depend on the generator, and pass `COMPUKTER_KOTLIN_SUSPEND_CALL_ARTIFACT` to the Rust harness.

- [ ] **Step 3: Execute it in `kotlin_writer.rs`**

Admit with the terminal binding, observe the async event request, resume it, observe the synchronous write of `"enter"`, and run to `Halted(None)`.

- [ ] **Step 4: Run and commit vertical conformance**

```bash
./gradlew-sandbox testKotlinSuspendCallVmConformance
git add host/compukter-vm modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt modules/compiler-k2/build.gradle.kts build.gradle.kts modules/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs
git commit -m "test(language): prove suspend calls across K2 and VM (#525)"
```

Expected: `BUILD SUCCESSFUL` and a parent commit that records the updated submodule pointer.

### Task 5: Verify the complete change

**Files:**
- Verify only

- [ ] **Step 1: Run Rust checks**

```bash
cargo fmt --manifest-path host/compukter-vm/Cargo.toml --check
CARGO_TARGET_DIR=.toolchain/build/cargo/compukter-vm cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline
CARGO_TARGET_DIR=.toolchain/build/cargo/compukter-vm cargo clippy --manifest-path host/compukter-vm/Cargo.toml --locked --offline --all-targets -- -D warnings
```

- [ ] **Step 2: Run repository verification**

```bash
./gradlew-sandbox --parallel verifyLocalFast testKotlinSuspendCallVmConformance
```

Expected: all commands exit 0 and Gradle reports `BUILD SUCCESSFUL`.

- [ ] **Step 3: Confirm clean boundaries**

```bash
git -C host/compukter-vm status --short
git status --short
```

Expected: both outputs empty after commits.
