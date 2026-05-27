# CKVM Image CallFunction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `Instruction.CallFunction` support to the Kotlin `CkVmImage` backend and Rust native image runner.

**Architecture:** Encode user-function calls as `CALL_FUNCTION = 15` plus `i32 functionIndex` and `i32 argumentCount`. Rust keeps the current active frame fields and adds `call_stack: Vec<CallFrame>` for saved callers. Nested `RETURN` restores the caller and pushes the callee result onto the shared value stack.

**Tech Stack:** Kotlin/JVM Gradle compiler tests, Rust `ckl-vm` crate tests, JNI end-to-end tests through `NativeImageVmRunner`.

---

## File Structure

- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt` — add `CALL_FUNCTION = 15`.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt` — lower `Instruction.CallFunction`.
- `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt` — backend RED/GREEN coverage.
- `native/ckl-vm/src/image_runner.rs` — Rust call-frame implementation.
- `native/ckl-vm/tests/image_runner.rs` — direct Rust multi-function image tests.
- `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt` — source-level JNI test.

---

### Task 1: RED Kotlin Backend CallFunction Coverage

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`

- [ ] **Step 1: Add little-endian helper**

Add near the bottom of `CkVmImageBackendTest`, before `writesBackendFixtureWhenPathIsProvided`:

```kotlin
    private fun i32(value: Int): List<Int> =
        listOf(value and 0xff, (value ushr 8) and 0xff, (value ushr 16) and 0xff, (value ushr 24) and 0xff)
```

- [ ] **Step 2: Add failing lowering test**

Insert after `compileImageLowersBinaryAndUnaryOperators`:

```kotlin
    @Test
    fun compileImageLowersUserFunctionCall() {
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                fun add(a: Int, b: Int): Int {
                    return a + b;
                }

                pub fun main() {
                    val result: Int = add(2, 5);
                }
                """.trimIndent(),
            ).image,
        )
        val addIndex = image.functions.indexOfFirst { it.name == "main.ck#add" }
        val mainFunction = image.functions.single { it.name == "main.ck#main" }
        val addFunction = image.functions.single { it.name == "main.ck#add" }

        assertTrue(addIndex >= 0)
        assertEquals(2, addFunction.frameSize)
        assertEquals(1, mainFunction.frameSize)
        assertEquals(listOf(CkVmConstant.IntConstant(2), CkVmConstant.IntConstant(5)), image.constants)
        assertContentEquals(
            listOf(
                CkVmImageOpcodes.LOAD_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.LOAD_LOCAL, 1, 0, 0, 0,
                CkVmImageOpcodes.BINARY, 0,
                CkVmImageOpcodes.RETURN,
            ),
            addFunction.code,
        )
        assertContentEquals(
            listOf(
                CkVmImageOpcodes.PUSH_CONSTANT, 0, 0, 0, 0,
                CkVmImageOpcodes.PUSH_CONSTANT, 1, 0, 0, 0,
                CkVmImageOpcodes.CALL_FUNCTION,
            ) + i32(addIndex) + i32(2) + listOf(
                CkVmImageOpcodes.STORE_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.PUSH_UNIT,
                CkVmImageOpcodes.RETURN,
            ),
            mainFunction.code,
        )
    }
```

- [ ] **Step 3: Move unsupported coverage to records**

Replace `unsupportedInstructionReportsClearError`:

```kotlin
    @Test
    fun unsupportedInstructionReportsClearError() {
        val artifact = LanguageFrontend().compile(
            "main.ck",
            """
            struct Box { value: Int }
            pub fun main() { val box: Box = Box(value = 1); }
            """.trimIndent(),
        )
        val module = assertNotNull(artifact.module)

        val error = assertFailsWith<UnsupportedOperationException> {
            CkVmImageCompiler.compile(module)
        }

        assertTrue(error.message.orEmpty().contains("CkVmImage backend does not support ConstructRecord"))
    }
```

- [ ] **Step 4: Run RED**

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --rerun-tasks --console=plain
```

Expected: compile fails on missing `CkVmImageOpcodes.CALL_FUNCTION`, or the new test fails with `CkVmImage backend does not support CallFunction` if the constant was added first.

- [ ] **Step 5: Commit RED tests**

```bash
git diff --check
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt
git commit -m "test: require ckvm image function calls"
```

---

### Task 2: Implement Kotlin Image CallFunction Lowering

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`

- [ ] **Step 1: Add opcode constant**

In `CkVmImageOpcodes`, append:

```kotlin
    const val CALL_FUNCTION = 15
```

- [ ] **Step 2: Add instruction length**

In `instructionLength`, before the final `else`:

```kotlin
                is Instruction.CallFunction -> 9
```

- [ ] **Step 3: Add lowering case**

In `lowerInstruction`, before `Instruction.CallBuiltin`:

```kotlin
                is Instruction.CallFunction -> listOf(CkVmImageOpcodes.CALL_FUNCTION) + i32(instruction.functionIndex) + i32(instruction.argumentCount)
```

- [ ] **Step 4: Run GREEN**

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --rerun-tasks --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit Kotlin lowering**

```bash
git diff --check
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt
git commit -m "feat: lower ckvm image function calls"
```

---

### Task 3: RED Rust Image Runner Function Call Tests

**Files:**
- Modify: `native/ckl-vm/tests/image_runner.rs`

- [ ] **Step 1: Add test opcode constant**

```rust
const OP_CALL_FUNCTION: u8 = 15;
```

- [ ] **Step 2: Add multi-function fixtures**

Add below `enum ConstantFixture`:

```rust
struct FunctionFixture {
    name: String,
    frame_size: i32,
    code: Vec<u8>,
}
```

Refactor `image_with_constants_and_optional_host_import(...)` to delegate to a new helper that writes `entry_function_index` and a `Vec<FunctionFixture>`. Preserve the existing one-function helpers by wrapping the single existing `main` function in that vector.

The new helper must write the same `CKIM` header, constants, optional host import, entry index, and then all functions. For the optional host import used by the new host-call test, use import id `1`, module `test`, function `log`, parameter list `String`, and return type `Unit`.

- [ ] **Step 3: Add success tests**

Add direct image runner tests that hand-build multi-function images:

1. `calls_function_and_returns_value_to_entry_frame`
   - constants: `Int(2)`, `Int(5)`.
   - function `main`, frame size `0`: push both constants, `OP_CALL_FUNCTION` function index `1` with argument count `2`, `OP_RETURN`.
   - function `add`, frame size `2`: `LOAD_LOCAL 0`, `LOAD_LOCAL 1`, `OP_BINARY ADD`, `OP_RETURN`.
   - expected signal: `vec![0, 3, 7, 0, 0, 0]`.
2. `restores_caller_locals_after_return`
   - `main` stores `Int(9)` in local `0`, calls a zero-arg callee, pops callee result, loads local `0`, returns.
   - expected signal: `vec![0, 3, 9, 0, 0, 0]`.
3. `supports_nested_function_calls`
   - `main -> first -> second`, with `second` returning `3`, `first` adding `4`, and `main` returning the result.
   - expected signal: `vec![0, 3, 7, 0, 0, 0]`.
4. `resumes_host_call_inside_callee`
   - `main` calls `callee`.
   - `callee` pushes string constant `"callee"`, executes `OP_CALL_HOST` import id `1` with argument count `1`, then `OP_RETURN`.
   - first signal has byte `4`; after `resume_with_value_bytes(&encode_value(&VmValue::Unit))`, final signal is `vec![0, 0]`.

- [ ] **Step 4: Add error tests**

Add direct image runner tests:

1. `rejects_call_function_out_of_bounds`
   - code: `OP_CALL_FUNCTION` with function index `99`, argument count `0`.
   - expected signal kind `255`, message contains `function index 99 is out of bounds`.
2. `rejects_call_function_argument_count_exceeding_frame_size`
   - `main` calls callee frame size `0` with argument count `1`.
   - expected signal kind `255`, message contains `argument count 1 exceeds frame size 0`.
3. `rejects_call_function_stack_underflow`
   - `main` calls callee frame size `1` with argument count `1` but pushes no arguments.
   - expected signal kind `255`, message contains `stack underflow`.

- [ ] **Step 5: Run RED**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner -- --nocapture
```

Expected: test binary compiles, and the new tests fail with `unknown CkVmImage opcode 15`.

- [ ] **Step 6: Format and commit RED tests**

```bash
cargo fmt --manifest-path native/ckl-vm/Cargo.toml
git diff --check
git add native/ckl-vm/tests/image_runner.rs
git commit -m "test: require rust image function calls"
```

---

### Task 4: Implement Rust Function Call Execution

**Files:**
- Modify: `native/ckl-vm/src/image_runner.rs`

- [ ] **Step 1: Add opcode and frame type**

```rust
const OP_CALL_FUNCTION: u8 = 15;

struct CallFrame {
    function_index: usize,
    instruction_pointer: usize,
    locals: Vec<VmValue>,
}
```

- [ ] **Step 2: Add `call_stack` field**

Add to `ImageVmHandle` after `locals`:

```rust
    call_stack: Vec<CallFrame>,
```

Initialize in `ImageVmHandle::create`:

```rust
            call_stack: Vec::new(),
```

- [ ] **Step 3: Implement nested return**

Replace `OP_RETURN` handling:

```rust
                OP_RETURN => {
                    let result = self.stack.pop().unwrap_or(VmValue::Unit);
                    if let Some(frame) = self.call_stack.pop() {
                        self.function_index = frame.function_index;
                        self.instruction_pointer = frame.instruction_pointer;
                        self.locals = frame.locals;
                        self.stack.push(result);
                    } else {
                        return self.halt(result);
                    }
                }
```

- [ ] **Step 4: Add opcode dispatch**

```rust
                OP_CALL_FUNCTION => {
                    let function_index = self.read_i32()?;
                    let argument_count = self.read_i32()?;
                    self.call_function(function_index, argument_count)?;
                }
```

- [ ] **Step 5: Add helper methods**

Add after `jump` inside `impl ImageVmHandle`:

```rust
    fn call_function(&mut self, function_index: i32, argument_count: i32) -> Result<(), String> {
        let function_index = self.checked_function_index(function_index)?;
        if argument_count < 0 {
            return Err(format!("negative CkVmImage argument count {argument_count}"));
        }
        let argument_count = argument_count as usize;
        let frame_size = checked_frame_size(&self.image, function_index)?;
        if argument_count > frame_size {
            return Err(format!(
                "CkVmImage argument count {argument_count} exceeds frame size {frame_size} for function {function_index}"
            ));
        }
        let arguments = self.pop_many(argument_count as i32)?;
        let caller_frame = CallFrame {
            function_index: self.function_index,
            instruction_pointer: self.instruction_pointer,
            locals: std::mem::take(&mut self.locals),
        };
        self.call_stack.push(caller_frame);
        self.function_index = function_index;
        self.instruction_pointer = 0;
        self.locals = vec![VmValue::Unit; frame_size];
        for (slot, argument) in arguments.into_iter().enumerate() {
            self.locals[slot] = argument;
        }
        Ok(())
    }

    fn checked_function_index(&self, function_index: i32) -> Result<usize, String> {
        if function_index < 0 {
            return Err(format!("negative CkVmImage function index {function_index}"));
        }
        let function_index = function_index as usize;
        if function_index >= self.image.functions.len() {
            return Err(format!(
                "CkVmImage function index {function_index} is out of bounds for {} functions",
                self.image.functions.len()
            ));
        }
        Ok(function_index)
    }
```

- [ ] **Step 6: Run GREEN**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml -- --nocapture
```

Expected: all Rust tests pass.

- [ ] **Step 7: Format and commit Rust implementation**

```bash
cargo fmt --manifest-path native/ckl-vm/Cargo.toml
git diff --check
git add native/ckl-vm/src/image_runner.rs native/ckl-vm/tests/image_runner.rs
git commit -m "feat: execute ckvm image function calls"
```

---

### Task 5: JNI End-to-End Function Call Test

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`

- [ ] **Step 1: Add JNI test**

Insert after `imageRunnerExecutesOperatorsThroughJniWhenLibraryIsConfigured`:

```kotlin
    @Test
    fun imageRunnerExecutesUserFunctionCallsThroughJniWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                fun add(a: Int, b: Int): Int {
                    return a + b;
                }

                fun label(value: Int): String {
                    return "value=" + value;
                }

                pub fun main() {
                    val result: Int = add(2, 5);
                    system::log(label(result));
                }
                """.trimIndent(),
            ).image,
        )
        val runtime = RecordingRuntime()

        runBlocking {
            NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime)
        }

        assertEquals(listOf("value=7"), runtime.lines)
    }
```

- [ ] **Step 2: Build native library and run focused JNI tests**

```bash
./gradlew buildRustVmNativeLibrary --console=plain
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --tests '*NativeImageVmRunnerJniTest' --rerun-tasks -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so --console=plain
```

Expected: both Gradle commands end with `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit JNI test**

```bash
git diff --check
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt
git commit -m "test: run ckvm image function calls through jni"
```

---

### Task 6: Final Verification

- [ ] **Step 1: Run focused Kotlin/JNI verification**

```bash
./gradlew buildRustVmNativeLibrary --console=plain
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --tests '*CkVmImageAbiTest' --tests '*CkVmImageComputerProgramTest' --tests '*NativeImageVmRunnerJniTest' --tests '*NativeImageVmBindingsJniTest' :v1_21_1-neoforge:test --tests '*DeviceProgramSupportTest' --rerun-tasks -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so --console=plain
```

Expected: both Gradle commands end with `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run Rust verification**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml -- --nocapture
```

- [ ] **Step 3: Check stale unsupported references and whitespace**

```bash
grep -R -n -E 'CkVmImage backend does not support CallFunction' modules/compiler/src/main/kotlin modules/compiler/src/test/kotlin || true
git diff --check
git status --short --untracked-files=all
```

Expected: grep has no output, `git diff --check` has no output, and working tree is clean.

- [ ] **Step 4: Request code review**

Review the commits created by this plan against `HEAD` before Task 1 started. Focus on:

- Kotlin/Rust opcode synchronization for `CALL_FUNCTION = 15`.
- Call-frame save/restore correctness.
- Return value placement on caller stack.
- Host-call resume behavior inside callees.
- Argument-count/function-index errors.
- Preservation of image-only runtime architecture.

Expected: no blocking review findings remain.
