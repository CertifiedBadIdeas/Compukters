# CkVmImage Control Flow and Locals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add local variables and basic branch/jump execution to the Rust `CkVmImage` runtime path.

**Architecture:** Keep Kotlin `BytecodeModule` as compiler scaffolding, but lower supported `Instruction` variants into image bytecode. Convert source instruction-index jump targets into absolute image byte offsets in the Kotlin backend. Execute the new opcodes in Rust with deterministic encoded error signals.

**Tech Stack:** Kotlin/JVM, Gradle Kotlin DSL, Rust JNI crate, CKIM `CkVmImage`, `NativeImageVmRunner`.

---

## File Structure

- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
  - Add image opcode constants for booleans, null, locals, and jumps.
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
  - Replace one-pass instruction lowering with measured two-pass lowering so jump operands become byte offsets.
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`
  - Add Kotlin lowering tests for locals, bool/null, and byte-offset jumps.
  - Move the unsupported-instruction test away from `if` to a still-unsupported `Binary` expression.
- Create: `native/ckl-vm/tests/image_runner.rs`
  - Add Rust image runner tests for locals, jumps, conditional jumps, and deterministic errors.
- Modify: `native/ckl-vm/src/image_runner.rs`
  - Add local storage and opcode execution for the new image operations.
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`
  - Add an end-to-end CKL `if` plus host-call test through the JNI image runner.

## Opcode Values

Use these numeric opcode assignments consistently in Kotlin and Rust:

| Name | Value |
|---|---:|
| `PUSH_UNIT` | 1 |
| `RETURN` | 2 |
| `PUSH_CONSTANT` | 3 |
| `CALL_HOST` | 4 |
| `POP` | 5 |
| `PUSH_BOOL` | 6 |
| `PUSH_NULL` | 7 |
| `LOAD_LOCAL` | 8 |
| `STORE_LOCAL` | 9 |
| `JUMP` | 10 |
| `JUMP_IF_FALSE` | 11 |
| `JUMP_IF_TRUE` | 12 |

## Task 1: RED Kotlin Backend Coverage

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`

- [ ] **Step 1: Add backend tests for bool/null, locals, and byte-offset jumps**

Append these tests inside `CkVmImageBackendTest`:

```kotlin
    @Test
    fun compileImageLowersBoolNullAndLocalSlots() {
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    val enabled: Bool = true;
                    val missing: String? = null;
                    if (enabled) {
                        system::log("yes");
                    }
                }
                """.trimIndent(),
            ).image,
        )

        assertEquals(2, image.functions.single().frameSize)
        assertEquals(listOf(CkVmConstant.StringConstant("yes")), image.constants)
        assertEquals(listOf(CkVmHostImport(3004, "system", "log", listOf("String"), "Unit")), image.hostImports)
        assertContentEquals(
            listOf(
                CkVmImageOpcodes.PUSH_BOOL, 1,
                CkVmImageOpcodes.STORE_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.PUSH_NULL,
                CkVmImageOpcodes.STORE_LOCAL, 1, 0, 0, 0,
                CkVmImageOpcodes.LOAD_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.JUMP_IF_FALSE, 38, 0, 0, 0,
                CkVmImageOpcodes.PUSH_CONSTANT, 0, 0, 0, 0,
                CkVmImageOpcodes.CALL_HOST, 188, 11, 0, 0, 1, 0, 0, 0,
                CkVmImageOpcodes.POP,
                CkVmImageOpcodes.PUSH_UNIT,
                CkVmImageOpcodes.RETURN,
            ),
            image.functions.single().code,
        )
    }

    @Test
    fun compileImageLowersForwardAndBackwardJumpsToByteOffsets() {
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    while (false) {
                        system::log("loop");
                    }
                }
                """.trimIndent(),
            ).image,
        )

        assertContentEquals(
            listOf(
                CkVmImageOpcodes.PUSH_BOOL, 0,
                CkVmImageOpcodes.JUMP_IF_FALSE, 27, 0, 0, 0,
                CkVmImageOpcodes.PUSH_CONSTANT, 0, 0, 0, 0,
                CkVmImageOpcodes.CALL_HOST, 188, 11, 0, 0, 1, 0, 0, 0,
                CkVmImageOpcodes.POP,
                CkVmImageOpcodes.JUMP, 0, 0, 0, 0,
                CkVmImageOpcodes.PUSH_UNIT,
                CkVmImageOpcodes.RETURN,
            ),
            image.functions.single().code,
        )
    }
```

- [ ] **Step 2: Move unsupported test to `Binary`**

Replace `unsupportedInstructionReportsClearError` with:

```kotlin
    @Test
    fun unsupportedInstructionReportsClearError() {
        val artifact = LanguageFrontend().compile("main.ck", "pub fun main() { val x: Int = 1 + 2; }")
        val module = assertNotNull(artifact.module)

        val error = assertFailsWith<UnsupportedOperationException> {
            CkVmImageCompiler.compile(module)
        }

        assertTrue(error.message.orEmpty().contains("CkVmImage backend does not support Binary"))
    }
```

- [ ] **Step 3: Run RED Kotlin backend tests**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --rerun-tasks --console=plain
```

Expected: FAIL. The new tests reference opcode constants that do not exist yet and/or lowering still rejects `PushBool`, `PushNull`, `LoadLocal`, `StoreLocal`, and jump instructions.

- [ ] **Step 4: Commit RED tests**

Run:

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt
git commit -m "test: require ckvm image locals and jumps"
```

## Task 2: Implement Kotlin Image Lowering

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`

- [ ] **Step 1: Add opcode constants**

In `CkVmImageOpcodes`, add constants after `POP`:

```kotlin
    const val PUSH_BOOL = 6
    const val PUSH_NULL = 7
    const val LOAD_LOCAL = 8
    const val STORE_LOCAL = 9
    const val JUMP = 10
    const val JUMP_IF_FALSE = 11
    const val JUMP_IF_TRUE = 12
```

- [ ] **Step 2: Replace one-pass lowering with measured lowering**

In `CkVmImageBackend.kt`, replace the entire private `LoweringContext` class with:

```kotlin
    private class LoweringContext(
        hostImports: List<CkVmHostImport>,
    ) {
        private val hostImportIds = hostImports.associateBy { Triple(it.moduleName, it.functionName, it.parameterTypes.size) }
        val constants = mutableListOf<CkVmConstant>()

        fun lower(function: BytecodeFunction): CkVmFunction {
            val offsets = instructionOffsets(function.instructions)
            val code =
                function.instructions.flatMapIndexed { index, instruction ->
                    lowerInstruction(instruction, offsets, function.instructions.size, index)
                }
            return CkVmFunction(
                name = function.name,
                frameSize = function.parameters.size + function.locals.size,
                code = code,
            )
        }

        private fun instructionOffsets(instructions: List<Instruction>): List<Int> {
            val offsets = mutableListOf<Int>()
            var offset = 0
            instructions.forEach { instruction ->
                offsets += offset
                offset += instructionLength(instruction)
            }
            offsets += offset
            return offsets
        }

        private fun instructionLength(instruction: Instruction): Int =
            when (instruction) {
                Instruction.PushUnit,
                Instruction.PushNull,
                Instruction.Return,
                Instruction.Pop,
                -> 1
                is Instruction.PushBool -> 2
                is Instruction.PushString,
                is Instruction.PushInt,
                is Instruction.PushLong,
                is Instruction.LoadLocal,
                is Instruction.StoreLocal,
                is Instruction.Jump,
                is Instruction.JumpIfFalse,
                is Instruction.JumpIfTrue,
                -> 5
                is Instruction.CallBuiltin -> 9
                else -> throw UnsupportedOperationException("CkVmImage backend does not support ${instruction::class.simpleName}")
            }

        private fun lowerInstruction(
            instruction: Instruction,
            offsets: List<Int>,
            instructionCount: Int,
            instructionIndex: Int,
        ): List<Int> =
            when (instruction) {
                Instruction.PushUnit -> listOf(CkVmImageOpcodes.PUSH_UNIT)
                Instruction.PushNull -> listOf(CkVmImageOpcodes.PUSH_NULL)
                Instruction.Return -> listOf(CkVmImageOpcodes.RETURN)
                Instruction.Pop -> listOf(CkVmImageOpcodes.POP)
                is Instruction.PushBool -> listOf(CkVmImageOpcodes.PUSH_BOOL, if (instruction.value) 1 else 0)
                is Instruction.PushString -> pushConstant(CkVmConstant.StringConstant(instruction.value))
                is Instruction.PushInt -> pushConstant(CkVmConstant.IntConstant(instruction.value))
                is Instruction.PushLong -> pushConstant(CkVmConstant.LongConstant(instruction.value))
                is Instruction.LoadLocal -> listOf(CkVmImageOpcodes.LOAD_LOCAL) + i32(instruction.slot)
                is Instruction.StoreLocal -> listOf(CkVmImageOpcodes.STORE_LOCAL) + i32(instruction.slot)
                is Instruction.Jump -> listOf(CkVmImageOpcodes.JUMP) + i32(resolveJumpTarget(instruction.target, offsets, instructionCount, instructionIndex))
                is Instruction.JumpIfFalse -> listOf(CkVmImageOpcodes.JUMP_IF_FALSE) + i32(resolveJumpTarget(instruction.target, offsets, instructionCount, instructionIndex))
                is Instruction.JumpIfTrue -> listOf(CkVmImageOpcodes.JUMP_IF_TRUE) + i32(resolveJumpTarget(instruction.target, offsets, instructionCount, instructionIndex))
                is Instruction.CallBuiltin -> callBuiltin(instruction)
                else -> throw UnsupportedOperationException("CkVmImage backend does not support ${instruction::class.simpleName}")
            }

        private fun resolveJumpTarget(
            target: Int,
            offsets: List<Int>,
            instructionCount: Int,
            instructionIndex: Int,
        ): Int {
            require(target in 0..instructionCount) {
                "CkVmImage jump target $target at instruction $instructionIndex is outside 0..$instructionCount"
            }
            return offsets[target]
        }

        private fun pushConstant(constant: CkVmConstant): List<Int> {
            val existing = constants.indexOf(constant)
            val index = if (existing >= 0) existing else constants.size.also { constants += constant }
            return listOf(CkVmImageOpcodes.PUSH_CONSTANT) + i32(index)
        }

        private fun callBuiltin(instruction: Instruction.CallBuiltin): List<Int> {
            val moduleName = instruction.moduleName
                ?: throw UnsupportedOperationException("CkVmImage backend does not support global builtin ${instruction.functionName}")
            val import = hostImportIds.getValue(Triple(moduleName, instruction.functionName, instruction.argumentCount))
            return listOf(CkVmImageOpcodes.CALL_HOST) + i32(import.id) + i32(instruction.argumentCount)
        }

        private fun i32(value: Int): List<Int> =
            listOf(value and 0xff, (value ushr 8) and 0xff, (value ushr 16) and 0xff, (value ushr 24) and 0xff)
    }
```

- [ ] **Step 3: Run Kotlin backend tests**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --rerun-tasks --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit Kotlin implementation**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt
git commit -m "feat: lower ckvm image locals and jumps"
```

## Task 3: RED Rust Image Runner Tests

**Files:**
- Create: `native/ckl-vm/tests/image_runner.rs`

- [ ] **Step 1: Add Rust tests for new opcodes**

Create `native/ckl-vm/tests/image_runner.rs` with:

```rust
use ckl_vm::image_runner::ImageVmHandle;

const OP_PUSH_UNIT: u8 = 1;
const OP_RETURN: u8 = 2;
const OP_PUSH_CONSTANT: u8 = 3;
const OP_PUSH_BOOL: u8 = 6;
const OP_PUSH_NULL: u8 = 7;
const OP_LOAD_LOCAL: u8 = 8;
const OP_STORE_LOCAL: u8 = 9;
const OP_JUMP: u8 = 10;
const OP_JUMP_IF_FALSE: u8 = 11;
const OP_JUMP_IF_TRUE: u8 = 12;

#[test]
fn stores_and_loads_local_value() {
    let code = vec![
        OP_PUSH_BOOL, 1,
        OP_STORE_LOCAL, 0, 0, 0, 0,
        OP_LOAD_LOCAL, 0, 0, 0, 0,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(&image_with_code(1, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 2, 1]);
}

#[test]
fn supports_null_values() {
    let code = vec![OP_PUSH_NULL, OP_RETURN];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 1]);
}

#[test]
fn jumps_over_unreachable_code() {
    let code = vec![
        OP_JUMP, 7, 0, 0, 0,
        OP_PUSH_UNIT,
        OP_RETURN,
        OP_PUSH_BOOL, 1,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 2, 1]);
}

#[test]
fn conditional_false_jump_takes_branch() {
    let code = vec![
        OP_PUSH_BOOL, 0,
        OP_JUMP_IF_FALSE, 10, 0, 0, 0,
        OP_PUSH_BOOL, 1,
        OP_RETURN,
        OP_PUSH_NULL,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 1]);
}

#[test]
fn conditional_true_jump_takes_branch() {
    let code = vec![
        OP_PUSH_BOOL, 1,
        OP_JUMP_IF_TRUE, 9, 0, 0, 0,
        OP_PUSH_NULL,
        OP_RETURN,
        OP_PUSH_BOOL, 1,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 2, 1]);
}

#[test]
fn rejects_non_bool_condition() {
    let code = vec![OP_PUSH_UNIT, OP_JUMP_IF_FALSE, 0, 0, 0, 0];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("requires Bool condition"));
}

#[test]
fn rejects_out_of_range_jump_target() {
    let code = vec![OP_JUMP, 99, 0, 0, 0];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("outside function code"));
}

#[test]
fn rejects_out_of_range_local_slot() {
    let code = vec![OP_LOAD_LOCAL, 1, 0, 0, 0, OP_RETURN];
    let mut vm = ImageVmHandle::create(&image_with_code(1, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("local slot 1 is out of bounds"));
}

#[test]
fn rejects_store_local_stack_underflow() {
    let code = vec![OP_STORE_LOCAL, 0, 0, 0, 0, OP_RETURN];
    let mut vm = ImageVmHandle::create(&image_with_code(1, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("stack underflow"));
}

fn image_with_code(frame_size: i32, code: Vec<u8>) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"CKIM");
    out.push(1);
    string(&mut out, "ckl-1");
    u16(&mut out, 1);
    list_len(&mut out, 0);
    list_len(&mut out, 0);
    list_len(&mut out, 0);
    i32(&mut out, 0);
    list_len(&mut out, 1);
    string(&mut out, "main");
    i32(&mut out, frame_size);
    list_len(&mut out, code.len() as i32);
    out.extend_from_slice(&code);
    out
}

fn list_len(out: &mut Vec<u8>, value: i32) {
    i32(out, value);
}

fn string(out: &mut Vec<u8>, value: &str) {
    i32(out, value.len() as i32);
    out.extend_from_slice(value.as_bytes());
}

fn u16(out: &mut Vec<u8>, value: u16) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn i32(out: &mut Vec<u8>, value: i32) {
    out.extend_from_slice(&value.to_le_bytes());
}
```

- [ ] **Step 2: Run RED Rust tests**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner -- --nocapture
```

Expected: FAIL with unknown opcode errors for the new opcodes.

- [ ] **Step 3: Commit RED Rust tests**

Run:

```bash
git add native/ckl-vm/tests/image_runner.rs
git commit -m "test: require rust image locals and jumps"
```

## Task 4: Implement Rust Image Runner Opcodes

**Files:**
- Modify: `native/ckl-vm/src/image_runner.rs`

- [ ] **Step 1: Add opcode constants and locals field**

Add constants after `OP_POP`:

```rust
const OP_PUSH_BOOL: u8 = 6;
const OP_PUSH_NULL: u8 = 7;
const OP_LOAD_LOCAL: u8 = 8;
const OP_STORE_LOCAL: u8 = 9;
const OP_JUMP: u8 = 10;
const OP_JUMP_IF_FALSE: u8 = 11;
const OP_JUMP_IF_TRUE: u8 = 12;
```

Add a `locals` field to `ImageVmHandle` after `stack`:

```rust
    locals: Vec<VmValue>,
```

In `create(...)`, build locals after `function_index` is validated:

```rust
        let frame_size = checked_frame_size(&image, function_index)?;
```

and initialize the struct with:

```rust
            locals: vec![VmValue::Unit; frame_size],
```

- [ ] **Step 2: Add opcode execution cases**

In `run_until_signal_inner`, add match cases after existing `OP_POP` or before the `other` case:

```rust
                OP_PUSH_BOOL => {
                    let value = self.read_u8()?.ok_or_else(|| {
                        "unexpected end of CkVmImage instruction stream".to_string()
                    })?;
                    match value {
                        0 => self.stack.push(VmValue::Bool(false)),
                        1 => self.stack.push(VmValue::Bool(true)),
                        other => return Err(format!("invalid CkVmImage bool byte {other}")),
                    }
                }
                OP_PUSH_NULL => self.stack.push(VmValue::Null),
                OP_LOAD_LOCAL => {
                    let slot = self.read_i32()?;
                    let value = self.local(slot)?.clone();
                    self.stack.push(value);
                }
                OP_STORE_LOCAL => {
                    let slot = self.read_i32()?;
                    let value = self.pop_one("store local")?;
                    *self.local_mut(slot)? = value;
                }
                OP_JUMP => {
                    let target = self.read_i32()?;
                    self.jump(target)?;
                }
                OP_JUMP_IF_FALSE => {
                    let target = self.read_i32()?;
                    if !self.pop_bool_condition("JUMP_IF_FALSE")? {
                        self.jump(target)?;
                    }
                }
                OP_JUMP_IF_TRUE => {
                    let target = self.read_i32()?;
                    if self.pop_bool_condition("JUMP_IF_TRUE")? {
                        self.jump(target)?;
                    }
                }
```

- [ ] **Step 3: Add helper methods**

Inside `impl ImageVmHandle`, add these methods before `current_function`:

```rust
    fn pop_one(&mut self, operation: &str) -> Result<VmValue, String> {
        self.stack
            .pop()
            .ok_or_else(|| format!("CkVmImage stack underflow during {operation}"))
    }

    fn pop_bool_condition(&mut self, opcode_name: &str) -> Result<bool, String> {
        match self.pop_one(opcode_name)? {
            VmValue::Bool(value) => Ok(value),
            other => Err(format!(
                "CkVmImage {opcode_name} requires Bool condition but found {other:?}"
            )),
        }
    }

    fn local(&self, slot: i32) -> Result<&VmValue, String> {
        if slot < 0 {
            return Err(format!("CkVmImage local slot {slot} is negative"));
        }
        self.locals
            .get(slot as usize)
            .ok_or_else(|| format!("CkVmImage local slot {slot} is out of bounds for {} locals", self.locals.len()))
    }

    fn local_mut(&mut self, slot: i32) -> Result<&mut VmValue, String> {
        if slot < 0 {
            return Err(format!("CkVmImage local slot {slot} is negative"));
        }
        let local_count = self.locals.len();
        self.locals
            .get_mut(slot as usize)
            .ok_or_else(|| format!("CkVmImage local slot {slot} is out of bounds for {local_count} locals"))
    }

    fn jump(&mut self, target: i32) -> Result<(), String> {
        if target < 0 {
            return Err(format!("CkVmImage jump target {target} is negative"));
        }
        let target = target as usize;
        let code_len = self.current_function()?.code.len();
        if target > code_len {
            return Err(format!(
                "CkVmImage jump target {target} is outside function code length {code_len}"
            ));
        }
        self.instruction_pointer = target;
        Ok(())
    }
```

- [ ] **Step 4: Add frame-size validation helper**

After `checked_entry_function_index`, add:

```rust
fn checked_frame_size(image: &Image, function_index: usize) -> Result<usize, String> {
    let frame_size = image.functions[function_index].frame_size;
    if frame_size < 0 {
        return Err(format!("negative CkVmImage frame size {frame_size}"));
    }
    Ok(frame_size as usize)
}
```

- [ ] **Step 5: Run Rust tests**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml -- --nocapture
```

Expected: BUILD SUCCESSFUL. `image_runner` tests should pass along with `image_decode` and `signal_codec`.

- [ ] **Step 6: Format and commit Rust implementation**

Run:

```bash
cargo fmt --manifest-path native/ckl-vm/Cargo.toml
git add native/ckl-vm/src/image_runner.rs native/ckl-vm/tests/image_runner.rs
git commit -m "feat: execute ckvm image locals and jumps"
```

## Task 5: JNI End-to-End Control Flow Test

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`

- [ ] **Step 1: Add JNI image runner test for `if` plus host call**

Append this test inside `NativeImageVmRunnerJniTest`:

```kotlin
    @Test
    fun imageRunnerExecutesIfConditionAndLocalThroughJniWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    val enabled: Bool = true;
                    if (enabled) {
                        system::log("yes");
                    }
                }
                """.trimIndent(),
            ).image,
        )
        val runtime = RecordingRuntime()

        runBlocking {
            NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime)
        }

        assertEquals(listOf("yes"), runtime.lines)
    }
```

- [ ] **Step 2: Run JNI/control-flow verification**

Run:

```bash
./gradlew buildRustVmNativeLibrary --console=plain
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --tests '*NativeImageVmRunnerJniTest' --rerun-tasks -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit JNI test**

Run:

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt
git commit -m "test: run ckvm image control flow through jni"
```

## Task 6: Final Verification

**Files:**
- No planned source edits.

- [ ] **Step 1: Run focused Kotlin/JNI verification**

Run:

```bash
./gradlew buildRustVmNativeLibrary --console=plain
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --tests '*CkVmImageAbiTest' --tests '*CkVmImageComputerProgramTest' --tests '*NativeImageVmRunnerJniTest' --tests '*NativeImageVmBindingsJniTest' :v1_21_1-neoforge:test --tests '*DeviceProgramSupportTest' --rerun-tasks -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run Rust verification**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml -- --nocapture
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run source checks**

Run:

```bash
grep -R "CkVmImage backend does not support PushBool\|CkVmImage backend does not support PushNull\|CkVmImage backend does not support LoadLocal\|CkVmImage backend does not support StoreLocal\|CkVmImage backend does not support Jump" modules/compiler/src/main/kotlin modules/compiler/src/test/kotlin -n || true
git diff --check
git status --short --untracked-files=all
```

Expected: no stale unsupported matches; no whitespace errors; clean status after commits.
