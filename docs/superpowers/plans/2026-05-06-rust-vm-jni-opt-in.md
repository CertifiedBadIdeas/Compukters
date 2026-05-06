# Rust VM JNI Opt-In Runner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a real JNI-backed Rust VM runner that is enabled only by explicit system properties and executes the current pure Rust VM subset.

**Architecture:** Kotlin serializes `BytecodeModule` through `BytecodeAbi`, loads `libckl_vm` from an explicit path, calls a JNI function with bytecode bytes and an instruction budget, then decodes a compact native signal. Rust decodes CKVM bytes, runs `VmInstance` until one signal, and returns encoded signal bytes or an encoded error.

**Tech Stack:** Kotlin/JVM, Gradle test, Rust 2021, `jni` Rust crate, CKVM v1 bytecode ABI.

---

## File Structure

- Modify `native/ckl-vm/Cargo.toml`: add `jni` dependency.
- Create `native/ckl-vm/src/signal.rs`: encode `VmSignal` and errors into compact bytes.
- Create `native/ckl-vm/src/runner.rs`: JNI-facing pure Rust function `run_bytecode_until_signal`.
- Create `native/ckl-vm/src/jni.rs`: exported JNI function for Kotlin.
- Modify `native/ckl-vm/src/lib.rs`: expose new modules.
- Create `native/ckl-vm/tests/signal_codec.rs`: Rust signal encoding tests.
- Create `native/ckl-vm/tests/runner.rs`: Rust runner tests.
- Create `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/native/NativeVmSignal.kt`: Kotlin signal decoder.
- Create `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/native/NativeVmBindings.kt`: library loader and native method wrapper.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/native/NativeVmRunner.kt`: call JNI and handle signals.
- Create `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunnerFactory.kt`: explicit runner selection.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`: default runner factory uses `VmRunnerFactory`.
- Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/native/NativeVmSignalTest.kt`: Kotlin decoder tests.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunnerSelectionTest.kt`: explicit rust selection tests.
- Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/native/NativeVmRunnerJniTest.kt`: optional JNI smoke test gated by `ckl.vm.native.library`.
- Modify `docs/PROFILING.md`: document build/run command for the JNI prototype.

---

### Task 1: Rust signal codec

**Files:**
- Create: `native/ckl-vm/tests/signal_codec.rs`
- Create: `native/ckl-vm/src/signal.rs`
- Modify: `native/ckl-vm/src/lib.rs`

- [ ] **Step 1: Write failing Rust tests**

Create `native/ckl-vm/tests/signal_codec.rs` with tests for halt int, halt string, host call, and error bytes.

- [ ] **Step 2: Run RED**

Run: `cd native/ckl-vm && cargo test --test signal_codec`

Expected: FAIL because `ckl_vm::signal` does not exist.

- [ ] **Step 3: Implement codec**

Create `native/ckl-vm/src/signal.rs` with `encode_signal(signal: &VmSignal) -> Vec<u8>` and `encode_error(message: impl AsRef<str>) -> Vec<u8>`.

Encoding must use little-endian `i32` lengths and the tags from the design spec.

- [ ] **Step 4: Expose module and run GREEN**

Modify `native/ckl-vm/src/lib.rs` to add `pub mod signal;`.

Run: `cd native/ckl-vm && cargo test --test signal_codec`

Expected: PASS.

- [ ] **Step 5: Commit**

Commit message: `feat: add Rust native signal codec`.

---

### Task 2: Rust JNI-facing runner function

**Files:**
- Create: `native/ckl-vm/tests/runner.rs`
- Create: `native/ckl-vm/src/runner.rs`
- Modify: `native/ckl-vm/src/lib.rs`

- [ ] **Step 1: Write failing Rust tests**

Create `native/ckl-vm/tests/runner.rs` with tests that pass minimal CKVM bytes to `run_bytecode_until_signal` and assert:

- `PushInt(1), PushInt(2), Binary(0), Return` returns encoded `Halt(Int(3))`.
- invalid bytes return encoded `Error`.

- [ ] **Step 2: Run RED**

Run: `cd native/ckl-vm && cargo test --test runner`

Expected: FAIL because `ckl_vm::runner::run_bytecode_until_signal` does not exist.

- [ ] **Step 3: Implement runner**

Create `native/ckl-vm/src/runner.rs` with `pub fn run_bytecode_until_signal(bytecode: &[u8], instruction_budget: usize) -> Vec<u8>`.

Implementation:

- call `decode_module(bytecode)`;
- create `VmInstance::new(module, instruction_budget)`;
- call `run_until_signal()` inside `catch_unwind(AssertUnwindSafe(...))`;
- encode normal signals with `signal::encode_signal`;
- encode decode/runtime/panic errors with `signal::encode_error`.

- [ ] **Step 4: Expose module and run GREEN**

Modify `native/ckl-vm/src/lib.rs` to add `pub mod runner;`.

Run: `cd native/ckl-vm && cargo test --test runner`

Expected: PASS.

- [ ] **Step 5: Commit**

Commit message: `feat: add Rust bytecode runner entrypoint`.

---

### Task 3: JNI export

**Files:**
- Modify: `native/ckl-vm/Cargo.toml`
- Create: `native/ckl-vm/src/jni.rs`
- Modify: `native/ckl-vm/src/lib.rs`

- [ ] **Step 1: Add Rust dependency**

Add `jni = "0.21"` to `native/ckl-vm/Cargo.toml` dependencies.

- [ ] **Step 2: Add JNI export**

Create `native/ckl-vm/src/jni.rs` with exported function:

`Java_ru_lazyhat_compukterkraft_lang_runtime_native_NativeVmBindings_runUntilSignalNative`.

It must:

- read `jbyteArray` into `Vec<u8>`;
- call `runner::run_bytecode_until_signal`;
- return a new `jbyteArray`;
- throw a Java exception only if it cannot allocate/convert the byte array.

- [ ] **Step 3: Expose module**

Modify `native/ckl-vm/src/lib.rs` to add `pub mod jni;`.

- [ ] **Step 4: Verify Rust build/tests**

Run: `cd native/ckl-vm && cargo test && cargo build`

Expected: PASS and native library builds.

- [ ] **Step 5: Commit**

Commit message: `feat: expose Rust VM JNI entrypoint`.

---

### Task 4: Kotlin native signal decoder

**Files:**
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/native/NativeVmSignalTest.kt`
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/native/NativeVmSignal.kt`

- [ ] **Step 1: Write failing Kotlin tests**

Tests must assert decoding for:

- halt int bytes;
- halt string bytes;
- pause/yield/sleep tags;
- error bytes.

- [ ] **Step 2: Run RED**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.native.NativeVmSignalTest`

Expected: FAIL because `NativeVmSignal` does not exist.

- [ ] **Step 3: Implement decoder**

Create `NativeVmSignal.kt` with sealed `NativeVmSignal`, sealed `NativeVmValue`, and `NativeVmSignal.decode(bytes: ByteArray): NativeVmSignal`.

- [ ] **Step 4: Run GREEN**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.native.NativeVmSignalTest`

Expected: PASS.

- [ ] **Step 5: Commit**

Commit message: `feat: decode native VM signals on JVM`.

---

### Task 5: Kotlin JNI bindings and runner selection

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/native/NativeVmBindings.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/native/NativeVmRunner.kt`
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunnerFactory.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunnerSelectionTest.kt`

- [ ] **Step 1: Write failing tests**

Update `VmRunnerSelectionTest` to verify:

- default selection returns `KotlinVmRunner`;
- `ckl.vm.runner=rust` without library path throws a clear error;
- `ckl.vm.runner=rust` with a path returns `NativeVmRunner`;
- `BytecodeComputerProgram` uses the selector by default.

- [ ] **Step 2: Run RED**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.VmRunnerSelectionTest`

Expected: FAIL because `VmRunnerFactory` does not exist or selection behavior is missing.

- [ ] **Step 3: Implement bindings and selection**

Create `NativeVmBindings` with `System.load(path)` guarded by a synchronized loaded-path check and `@JvmStatic private external fun runUntilSignalNative(bytecode: ByteArray, instructionBudget: Int): ByteArray`.

Update `NativeVmRunner.run` to call JNI, decode the signal, return on `Halt`, and throw clear unsupported errors for non-halt signals.

Create `VmRunnerFactory` with `fromSystemProperties()` using `ckl.vm.runner` and `ckl.vm.native.library`.

Update `BytecodeComputerProgram` default runner factory to use `VmRunnerFactory.fromSystemProperties()`.

- [ ] **Step 4: Run GREEN**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.VmRunnerSelectionTest`

Expected: PASS.

- [ ] **Step 5: Commit**

Commit message: `feat: select opt-in native VM runner`.

---

### Task 6: Optional Kotlin JNI smoke test and docs

**Files:**
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/native/NativeVmRunnerJniTest.kt`
- Modify: `docs/PROFILING.md`

- [ ] **Step 1: Write optional integration test**

Create a test that exits early if `ckl.vm.native.library` is blank. When present, construct a minimal `BytecodeModule` manually with instructions `PushInt(1), PushInt(2), Binary(ADD), Return`, run `NativeVmRunner`, and assert it completes.

- [ ] **Step 2: Run test without native path**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.native.NativeVmRunnerJniTest`

Expected: PASS because the test is skipped by early return.

- [ ] **Step 3: Build native library and run integration test with path**

Run: `cd native/ckl-vm && cargo build`.

Then run from repo root:

`./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.native.NativeVmRunnerJniTest -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so`

Expected: PASS.

- [ ] **Step 4: Update docs**

Add commands for building `libckl_vm.so` and running the optional JNI smoke test to `docs/PROFILING.md`.

- [ ] **Step 5: Final verification**

Run:

`./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest --tests ru.lazyhat.compukterkraft.lang.runtime.BytecodeAbiTest --tests ru.lazyhat.compukterkraft.lang.runtime.VmRunnerSelectionTest --tests ru.lazyhat.compukterkraft.lang.runtime.native.NativeVmSignalTest --tests ru.lazyhat.compukterkraft.lang.runtime.native.NativeVmRunnerJniTest && cd native/ckl-vm && cargo test && cargo build`

Expected: PASS.

- [ ] **Step 6: Commit**

Commit message: `test: add native VM JNI smoke coverage`.
