# CkVmImage JNI Runner Protocol Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a parallel JNI lifecycle for Rust-native `CkVmImage` handles: create, run until signal, resume, and free.

**Architecture:** Keep the existing bytecode JNI path unchanged. Add separate Kotlin binding methods and Rust JNI exports for image handles so the future image VM can evolve independently from `BytecodeAbi`. The first image runner executes the current skeleton opcodes enough to halt an empty `main` and emit/resume a `system::log("hi")` host call.

**Tech Stack:** Kotlin/JVM, Kotlin test, Gradle, Rust 2021, JNI, existing `CkVmImageAbi`, existing Rust image decoder, existing native signal/value protocol.

---

## File Structure

- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
  - Add image-specific create/run/resume/free binding methods.
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`
  - JNI smoke tests for empty image halt and host-call resume.
- Create: `native/ckl-vm/src/image_runner.rs`
  - Minimal Rust image VM handle and skeleton opcode executor.
- Modify: `native/ckl-vm/src/lib.rs`
  - Export `image_runner`.
- Modify: `native/ckl-vm/src/jni.rs`
  - Add image JNI exports and image handle helper.

## Skeleton Image Runner Semantics

The first image runner supports only the skeleton backend opcodes:

- `1 PUSH_UNIT`: push `VmValue::Unit`.
- `2 RETURN`: halt with top stack value or `Unit`.
- `3 PUSH_CONSTANT <i32>`: push string/int/long constant from `Image.constants`.
- `4 CALL_HOST <i32 importId> <i32 argumentCount>`: pop arguments, find host import by id, emit `VmSignal::HostCall` with module/function names from the image import table.
- `5 POP`: pop one value or ignore if stack is empty.

The runner maintains instruction pointer and stack for the entry function only. It supports resume after host calls by pushing the decoded resume value onto the stack and continuing.

---

### Task 1: RED JNI Binding Tests

**Files:**
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [ ] **Step 1: Write failing JNI tests**

Create tests that skip when `ckl.vm.native.library` is not configured. The tests should call new `NativeVmBindings.createImage(...)`, `runImageUntilSignal(...)`, `resumeImageWith(...)`, and `freeImage(...)` methods.

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :compiler:test --tests '*NativeImageVmBindingsJniTest' --rerun-tasks
```

Expected: FAIL during Kotlin compilation with unresolved image binding methods.

- [ ] **Step 3: Commit RED tests**

Run:

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt
git commit -m "test: add native image vm bindings red tests"
```

---

### Task 2: Kotlin Binding Surface

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`

- [ ] **Step 1: Add image binding methods**

Add public internal methods `createImage`, `runImageUntilSignal`, `resumeImageWith`, and `freeImage`, plus matching private external native declarations.

- [ ] **Step 2: Run RED moves to native failure or skip**

Run:

```bash
./gradlew :compiler:test --tests '*NativeImageVmBindingsJniTest' --rerun-tasks
```

Expected: PASS when no native library is configured because the tests skip, or native symbol failure when a library is configured. Compilation must pass.

- [ ] **Step 3: Commit Kotlin binding surface**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt
git commit -m "feat: add native image vm kotlin bindings"
```

---

### Task 3: Rust Image Runner and JNI Exports

**Files:**
- Create: `native/ckl-vm/src/image_runner.rs`
- Modify: `native/ckl-vm/src/lib.rs`
- Modify: `native/ckl-vm/src/jni.rs`

- [ ] **Step 1: Implement minimal image runner**

Create `ImageVmHandle` using `decode_image`, skeleton opcode execution, existing `VmSignal`, existing `VmValue`, and existing signal/value encode/decode helpers.

- [ ] **Step 2: Add JNI exports**

Add native functions matching Kotlin declarations:

- `createImageNative(image: ByteArray, instructionBudget: Int): Long`
- `runImageUntilSignalForHandleNative(handle: Long): ByteArray`
- `resumeImageWithNative(handle: Long, value: ByteArray)`
- `freeImageNative(handle: Long)`

- [ ] **Step 3: Run native and JNI tests**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml -- --nocapture
./gradlew buildRustVmNativeLibrary :compiler:test --tests '*NativeImageVmBindingsJniTest' --rerun-tasks -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so
```

Expected: both commands PASS.

- [ ] **Step 4: Commit Rust image runner protocol**

Run:

```bash
git add native/ckl-vm/src/image_runner.rs native/ckl-vm/src/lib.rs native/ckl-vm/src/jni.rs modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt
git commit -m "feat: add native image vm jni protocol"
```

---

### Task 4: Final Verification

**Files:**
- Verify all changed files.

- [ ] **Step 1: Run focused checks**

Run:

```bash
./gradlew buildRustVmNativeLibrary :compiler:test --tests '*NativeImageVmBindingsJniTest' --tests '*CkVmImageBackendTest' --tests '*CkVmHostImportRegistryTest' --rerun-tasks -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so
cargo test --manifest-path native/ckl-vm/Cargo.toml -- --nocapture
```

Expected: both commands PASS.

- [ ] **Step 2: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 3: Inspect status**

Run:

```bash
git status --short
```

Expected: clean status if every commit step was executed.

---

## Self-Review Notes

- This slice adds the JNI lifecycle and a minimal image executor only for current skeleton opcodes.
- It does not replace `NativeVmRunner` or `VmRunnerFactory`.
- It does not implement full CKL image execution, stack frames beyond entry function, memory arenas, or stable host-result type validation.