# CKVM Image Global Scheduler Builtins Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lower and execute global CKL scheduler builtins `yield()` and `sleep(Long)` in `CkVmImage`.

**Architecture:** Append two image opcodes, lower global `Instruction.CallBuiltin` calls to those opcodes, and have the Rust native runner return existing `Yield` and `Sleep` native signals. The Kotlin runner already resumes those signals with `Unit`, so no new signal protocol is needed.

**Tech Stack:** Kotlin compiler backend, Rust native `ckl-vm`, JNI runner tests, Gradle, Cargo.

---

## File Structure

- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
  - Add opcode constants `YIELD = 24` and `SLEEP = 25`.
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
  - Lower global builtins `yield` and `sleep`; keep host imports for module builtins only.
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`
  - Add backend lowering tests.
- Modify: `native/ckl-vm/src/image_runner.rs`
  - Execute `YIELD` and `SLEEP` opcodes.
- Modify: `native/ckl-vm/tests/image_runner.rs`
  - Add native runner tests for yield/sleep signals and resume behavior.
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`
  - Add JNI runner test verifying runtime `yield` and `sleep` calls.
- Use existing uncommitted audit test: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/CkVmImageBundledResourceCompileTest.kt`
  - Final parity check after scheduler builtins are implemented.

---

### Task 1: Kotlin backend RED test

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`

- [ ] **Step 1: Add lowering test**

Add this test before `compileImageReturnsNullImageWhenFrontendHasErrors`:

```kotlin
    @Test
    fun compileImageLowersGlobalSchedulerBuiltins() {
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    yield();
                    sleep(2L);
                    system::log("done");
                }
                """.trimIndent(),
            ).image,
        )

        assertEquals(
            listOf(
                CkVmConstant.LongConstant(2),
                CkVmConstant.StringConstant("done"),
            ),
            image.constants,
        )
        assertEquals(
            listOf(CkVmHostImportRegistry.require("system", "log", 1)),
            image.hostImports,
        )
        assertContentEquals(
            listOf(
                CkVmImageOpcodes.YIELD,
                CkVmImageOpcodes.PUSH_CONSTANT, 0, 0, 0, 0,
                CkVmImageOpcodes.SLEEP,
                CkVmImageOpcodes.PUSH_CONSTANT, 1, 0, 0, 0,
                CkVmImageOpcodes.CALL_HOST, 188, 11, 0, 0, 1, 0, 0, 0,
                CkVmImageOpcodes.POP,
                CkVmImageOpcodes.PUSH_UNIT,
                CkVmImageOpcodes.RETURN,
            ),
            image.functions.single().code,
        )
    }
```

- [ ] **Step 2: Run focused Kotlin backend test for RED**

Run:

```bash
./gradlew :compiler:test --tests 'ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageBackendTest'
```

Expected: FAIL during test compilation with unresolved `CkVmImageOpcodes.YIELD` and `CkVmImageOpcodes.SLEEP`, or FAIL at runtime with `CkVmImage backend does not support global builtin yield`.

---

### Task 2: Kotlin backend GREEN implementation

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`

- [ ] **Step 1: Add opcode constants**

In `CkVmImageOpcodes`, append:

```kotlin
    const val YIELD = 24
    const val SLEEP = 25
```

- [ ] **Step 2: Update instruction length for global builtins**

Replace the `is Instruction.CallBuiltin -> 9` length handling with a helper:

```kotlin
                is Instruction.CallFunction,
                is Instruction.CallCollectionMethod,
                -> 9
                is Instruction.CallBuiltin -> callBuiltinLength(instruction)
```

Add this helper in `LoweringContext`:

```kotlin
        private fun callBuiltinLength(instruction: Instruction.CallBuiltin): Int =
            when {
                instruction.moduleName == null && instruction.functionName == "yield" && instruction.argumentCount == 0 -> 1
                instruction.moduleName == null && instruction.functionName == "sleep" && instruction.argumentCount == 1 -> 1
                instruction.moduleName != null -> 9
                else -> throw UnsupportedOperationException("CkVmImage backend does not support global builtin ${instruction.functionName}")
            }
```

- [ ] **Step 3: Lower global scheduler builtins**

Replace `callBuiltin(...)` with:

```kotlin
        private fun callBuiltin(instruction: Instruction.CallBuiltin): List<Int> {
            if (instruction.moduleName == null) {
                return when {
                    instruction.functionName == "yield" && instruction.argumentCount == 0 -> listOf(CkVmImageOpcodes.YIELD)
                    instruction.functionName == "sleep" && instruction.argumentCount == 1 -> listOf(CkVmImageOpcodes.SLEEP)
                    else -> throw UnsupportedOperationException("CkVmImage backend does not support global builtin ${instruction.functionName}")
                }
            }
            val import = hostImportIds.getValue(Triple(instruction.moduleName, instruction.functionName, instruction.argumentCount))
            return listOf(CkVmImageOpcodes.CALL_HOST) + i32(import.id) + i32(instruction.argumentCount)
        }
```

- [ ] **Step 4: Run focused Kotlin backend test for GREEN**

Run:

```bash
./gradlew :compiler:test --tests 'ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageBackendTest'
```

Expected: PASS.

---

### Task 3: Rust native runner RED tests

**Files:**
- Modify: `native/ckl-vm/tests/image_runner.rs`

- [ ] **Step 1: Add opcode constants to tests**

After `OP_CALL_COLLECTION_METHOD`, add:

```rust
const OP_YIELD: u8 = 24;
const OP_SLEEP: u8 = 25;
```

- [ ] **Step 2: Add runner tests**

Add these tests near the collection/signal execution tests:

```rust
#[test]
fn executes_yield_signal_and_resumes_with_unit() {
    let code = vec![OP_YIELD, OP_PUSH_CONSTANT, 0, 0, 0, 0, OP_RETURN];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(vec![ConstantFixture::Int(7)], 0, code),
        64,
    ).unwrap();

    assert_eq!(vm.run_until_signal(), vec![2]);
    vm.resume_with(&encode_value(&VmValue::Unit)).unwrap();
    assert_eq!(vm.run_until_signal(), vec![0, 3, 7, 0, 0, 0]);
}

#[test]
fn executes_sleep_signal_and_resumes_with_unit() {
    let code = vec![OP_PUSH_CONSTANT, 0, 0, 0, 0, OP_SLEEP, OP_PUSH_CONSTANT, 1, 0, 0, 0, OP_RETURN];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(vec![ConstantFixture::Long(9), ConstantFixture::Int(3)], 0, code),
        64,
    ).unwrap();

    assert_eq!(vm.run_until_signal(), vec![3, 9, 0, 0, 0, 0, 0, 0, 0]);
    vm.resume_with(&encode_value(&VmValue::Unit)).unwrap();
    assert_eq!(vm.run_until_signal(), vec![0, 3, 3, 0, 0, 0]);
}

#[test]
fn rejects_sleep_with_non_long_ticks() {
    let code = vec![OP_PUSH_CONSTANT, 0, 0, 0, 0, OP_SLEEP];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(vec![ConstantFixture::Int(9)], 0, code),
        64,
    ).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("CkVmImage SLEEP requires Long ticks"));
}
```

- [ ] **Step 3: Run Rust focused test for RED**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner
```

Expected: FAIL with unknown opcode `24` or `25`.

---

### Task 4: Rust native runner GREEN implementation

**Files:**
- Modify: `native/ckl-vm/src/image_runner.rs`

- [ ] **Step 1: Add opcode constants**

After `OP_CALL_COLLECTION_METHOD`, add:

```rust
const OP_YIELD: u8 = 24;
const OP_SLEEP: u8 = 25;
```

- [ ] **Step 2: Add dispatch**

In the opcode `match`, add:

```rust
                OP_YIELD => {
                    self.state = ImageVmState::WaitingForResume;
                    return Ok(VmSignal::Yield);
                }
                OP_SLEEP => {
                    let ticks = self.sleep_ticks()?;
                    self.state = ImageVmState::WaitingForResume;
                    return Ok(VmSignal::Sleep(ticks));
                }
```

- [ ] **Step 3: Add sleep helper**

Add this helper near other stack/type helpers:

```rust
    fn sleep_ticks(&mut self) -> Result<i64, String> {
        match self.pop_one("sleep ticks")? {
            VmValue::Long(ticks) => Ok(ticks),
            other => Err(format!("CkVmImage SLEEP requires Long ticks but found {other:?}")),
        }
    }
```

- [ ] **Step 4: Run Rust focused test for GREEN**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner
```

Expected: PASS.

---

### Task 5: JNI scheduler builtin coverage

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`

- [ ] **Step 1: Add JNI test**

Add this test near the other JNI runner tests:

```kotlin
    @Test
    fun imageRunnerExecutesSchedulerBuiltinsThroughJniWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    yield();
                    sleep(3L);
                    system::log("done");
                }
                """.trimIndent(),
            ).image,
        )
        val runtime = RecordingRuntime()

        runBlocking { NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime) }

        assertEquals(1, runtime.yieldCalls)
        assertEquals(3, runtime.sleepCalls)
        assertEquals(listOf("done"), runtime.lines)
    }
```

- [ ] **Step 2: Build native library and run focused JNI test**

Run:

```bash
cargo build --manifest-path native/ckl-vm/Cargo.toml && ./gradlew :compiler:test --tests 'ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeImageVmRunnerJniTest' -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so
```

Expected: PASS.

---

### Task 6: Audit GREEN check and commits

**Files:**
- Create/commit: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/CkVmImageBundledResourceCompileTest.kt`
- Commit modifications from Tasks 1-5.

- [ ] **Step 1: Run bundled audit test**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.CkVmImageBundledResourceCompileTest'
```

Expected:

- PASS if scheduler builtins were the only remaining compile-to-image blocker.
- FAIL with a new aggregated blocker. If it fails, stop and report the new blocker before implementing another slice.

- [ ] **Step 2: Commit scheduler implementation and tests**

If Task 6 Step 1 passes, run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt \
    modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt \
    modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt \
    native/ckl-vm/src/image_runner.rs \
    native/ckl-vm/tests/image_runner.rs \
    modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt \
    modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/CkVmImageBundledResourceCompileTest.kt
git commit -m "feat: execute ckvm image scheduler builtins"
```

If Task 6 Step 1 fails with a new blocker, do not commit the implementation as complete. Report the blocker and ask whether to continue to the next slice.

---

### Task 7: Final verification

**Files:**
- No code changes.

- [ ] **Step 1: Run Kotlin backend tests**

```bash
./gradlew :compiler:test --tests 'ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageBackendTest'
```

Expected: PASS.

- [ ] **Step 2: Run Rust crate tests**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml
```

Expected: PASS.

- [ ] **Step 3: Run focused JNI tests**

```bash
cargo build --manifest-path native/ckl-vm/Cargo.toml && ./gradlew :compiler:test --tests 'ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeImageVmRunnerJniTest' -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so
```

Expected: PASS.

- [ ] **Step 4: Run bundled image audit**

```bash
./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.CkVmImageBundledResourceCompileTest'
```

Expected: PASS, or a new blocker that must be reported before claiming completion.

- [ ] **Step 5: Check stale scheduler unsupported messages**

```bash
grep -R "does not support global builtin yield\|does not support global builtin sleep\|unknown CkVmImage opcode 24\|unknown CkVmImage opcode 25" -n modules native || true
```

Expected: no stale unsupported messages except intentional generic unsupported code paths if they do not mention `yield` or `sleep` specifically.

- [ ] **Step 6: Check status and diff**

```bash
git status --short --untracked-files=all
git --no-pager diff --stat
git --no-pager diff --check
```

Expected: clean working tree after commits and no whitespace errors.
