# CKVM Image Native Strings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute hot `strings::*` host imports directly in the Rust CKVM image runner for ASCII inputs.

**Architecture:** Keep the image ABI unchanged. `OP_CALL_HOST` validates the declared import, asks a local native-string dispatcher whether it can handle the call, and either pushes the native result or emits the existing host-call signal for Kotlin fallback.

**Tech Stack:** Rust CKVM image runner, existing CKL image binary format, existing Gradle profiling tasks.

---

### Task 1: Rust Image Runner Tests

**Files:**
- Modify: `native/ckl-vm/tests/image_runner.rs`

- [ ] **Step 1: Add host-import fixture support for arbitrary string imports**

Add a small `HostImportFixture` struct and helper that can encode ids `7000` through `7006`.

- [ ] **Step 2: Add failing ASCII native tests**

Add tests that call `strings::length`, `strings::charAt`, and the whitespace helpers through `OP_CALL_HOST`. Each test should assert that `run_until_signal()` returns a halt signal, not signal tag `4`.

- [ ] **Step 3: Add failing Unicode fallback test**

Add a test that calls `strings::length("é")` and asserts signal tag `4`, proving non-ASCII still goes through Kotlin fallback.

- [ ] **Step 4: Run the Rust test and confirm RED**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner native_strings
```

Expected: at least one ASCII native test fails because current `OP_CALL_HOST` always returns a host-call signal.

### Task 2: Rust Native Strings Dispatcher

**Files:**
- Modify: `native/ckl-vm/src/image_runner.rs`

- [ ] **Step 1: Add string import id constants**

Define local constants for ids `7000` through `7006` near the opcode constants.

- [ ] **Step 2: Add a native host-call result enum**

Add an internal enum with `Handled(VmValue)` and `Fallback(Vec<VmValue>)` so non-ASCII calls can preserve the original arguments for the existing host-call path.

- [ ] **Step 3: Add `try_native_host_import` and string helpers**

Implement ASCII-only handlers for `trim`, `beforeSpace`, `afterSpace`, `isBlank`, `toInt`, `length`, and `charAt`.

- [ ] **Step 4: Route `OP_CALL_HOST` through the dispatcher**

After validating and cloning import metadata, call the dispatcher. Push handled results onto the stack and continue execution. For fallback, set `WaitingForResume` and return the existing `VmSignal::HostCall`.

- [ ] **Step 5: Run the Rust tests and confirm GREEN**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner native_strings
```

Expected: the native string tests pass.

### Task 3: Verification

**Files:**
- No production changes expected.

- [ ] **Step 1: Run the full Rust image-runner test file**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner
```

Expected: all image-runner tests pass.

- [ ] **Step 2: Run JVM profile smoke test**

Run:

```bash
./gradlew profileRuntimeVmImage
```

Expected: profile task succeeds. The terminal profile should show fewer `strings::length` and `strings::charAt` host-call rows, ideally zero for the ASCII terminal path.

- [ ] **Step 3: Check whitespace**

Run:

```bash
git diff --check
```

Expected: no whitespace errors.
