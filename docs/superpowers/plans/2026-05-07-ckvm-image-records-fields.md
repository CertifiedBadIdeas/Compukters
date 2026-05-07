# CKVM Image Records and Field Access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add native `CkVmImage` support for CKL struct record construction and field reads.

**Architecture:** Append `CONSTRUCT_RECORD = 16` and `GET_FIELD = 17` to the existing image opcode ABI. Kotlin lowering stores record type names and field names in the existing constant pool as strings; Rust validates those metadata constants, constructs `VmValue::Record`, and reads fields from record values. `SetField`, class/object mutation, and collections stay unsupported.

**Tech Stack:** Kotlin/JVM compiler tests with Gradle, Rust `ckl-vm` crate tests with Cargo, JNI integration through `NativeImageVmRunner`.

**Working location:** Current branch `dev` per user choice. Do not create a worktree for this plan.

---

## Files and Responsibilities

- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
  - Owns Kotlin opcode constants. Add `CONSTRUCT_RECORD` and `GET_FIELD` after `CALL_FUNCTION`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
  - Owns bytecode-to-image lowering. Add instruction lengths and lowering for `Instruction.ConstructRecord` and `Instruction.GetField`; reuse the existing constant pool for metadata strings.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`
  - Owns focused Kotlin image lowering tests. Add RED tests for record construction/field access and move unsupported diagnostics to `ConstructList`.
- Modify `native/ckl-vm/src/image_runner.rs`
  - Owns native image execution. Add opcode constants, metadata-string validation, record construction, and record field access.
- Modify `native/ckl-vm/tests/image_runner.rs`
  - Owns direct Rust image runner tests. Add RED/GREEN tests for record construction, field access, ordering, metadata errors, receiver errors, and stack underflow.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`
  - Owns JNI integration tests. Add native-runner test for compiled CKL struct construction and field reads.

---

### Task 1: Kotlin Backend RED Tests

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`

- [ ] **Step 1: Add failing record lowering test**

Add this test after `compileImageLowersUserFunctionCall()` and before `compileImageReturnsNullImageWhenFrontendHasErrors()`:

```kotlin
    @Test
    fun compileImageLowersRecordConstructionAndFieldAccess() {
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                struct Point { x: Int, y: Int }

                pub fun main() {
                    val point: Point = Point(x = 2, y = 5);
                    val delta: Int = point.x - point.y;
                }
                """.trimIndent(),
            ).image,
        )
        val mainFunction = image.functions.single { it.name == "main.ck#main" }

        assertEquals(
            listOf(
                CkVmConstant.IntConstant(2),
                CkVmConstant.IntConstant(5),
                CkVmConstant.StringConstant("Point"),
                CkVmConstant.StringConstant("x"),
                CkVmConstant.StringConstant("y"),
            ),
            image.constants,
        )
        assertEquals(2, mainFunction.frameSize)
        assertContentEquals(
            listOf(
                CkVmImageOpcodes.PUSH_CONSTANT, 0, 0, 0, 0,
                CkVmImageOpcodes.PUSH_CONSTANT, 1, 0, 0, 0,
                CkVmImageOpcodes.CONSTRUCT_RECORD,
            ) + i32(2) + i32(2) + i32(3) + i32(4) + listOf(
                CkVmImageOpcodes.STORE_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.LOAD_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.GET_FIELD,
            ) + i32(3) + listOf(
                CkVmImageOpcodes.LOAD_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.GET_FIELD,
            ) + i32(4) + listOf(
                CkVmImageOpcodes.BINARY, 1,
                CkVmImageOpcodes.STORE_LOCAL, 1, 0, 0, 0,
                CkVmImageOpcodes.PUSH_UNIT,
                CkVmImageOpcodes.RETURN,
            ),
            mainFunction.code,
        )
    }
```

- [ ] **Step 2: Move unsupported diagnostic test to `ConstructList`**

Replace `unsupportedInstructionReportsClearError()` with this version:

```kotlin
    @Test
    fun unsupportedInstructionReportsClearError() {
        val base = assertNotNull(LanguageFrontend().compile("main.ck", "pub fun main() { }").module)
        val function = base.functions.single().copy(instructions = listOf(Instruction.ConstructList(0), Instruction.Return))

        val error = assertFailsWith<UnsupportedOperationException> {
            CkVmImageCompiler.compile(base.copy(functions = listOf(function)))
        }

        assertTrue(error.message.orEmpty().contains("CkVmImage backend does not support ConstructList"))
    }
```

- [ ] **Step 3: Run Kotlin backend test and verify RED**

Run:

```bash
./gradlew :compiler:test --tests 'ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageBackendTest'
```

Expected: FAIL. The record test should fail to compile or execute because `CkVmImageOpcodes.CONSTRUCT_RECORD` / `GET_FIELD` do not exist yet, or lowering still reports unsupported `ConstructRecord`.

---

### Task 2: Kotlin Backend Implementation

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`

- [ ] **Step 1: Add Kotlin opcode constants**

In `CkVmImageOpcodes`, append the new constants after `CALL_FUNCTION`:

```kotlin
    const val CALL_FUNCTION = 15
    const val CONSTRUCT_RECORD = 16
    const val GET_FIELD = 17
```

- [ ] **Step 2: Add instruction lengths**

In `instructionLength`, add `GetField` to the 5-byte group and add a dedicated `ConstructRecord` branch:

```kotlin
                is Instruction.PushString,
                is Instruction.PushInt,
                is Instruction.PushLong,
                is Instruction.LoadLocal,
                is Instruction.StoreLocal,
                is Instruction.Jump,
                is Instruction.JumpIfFalse,
                is Instruction.JumpIfTrue,
                is Instruction.GetField,
                -> 5
                is Instruction.ConstructRecord -> 9 + 4 * instruction.fieldNames.size
                is Instruction.Binary,
                is Instruction.Unary,
                -> 2
```

- [ ] **Step 3: Lower `ConstructRecord` and `GetField`**

In `lowerInstruction`, add these branches before `Instruction.CallFunction`:

```kotlin
                is Instruction.Binary -> listOf(CkVmImageOpcodes.BINARY, binaryOperatorTag(instruction.operator))
                is Instruction.Unary -> listOf(CkVmImageOpcodes.UNARY, unaryOperatorTag(instruction.operator))
                is Instruction.ConstructRecord ->
                    listOf(CkVmImageOpcodes.CONSTRUCT_RECORD) +
                        stringConstantIndexBytes(instruction.typeName) +
                        i32(instruction.fieldNames.size) +
                        instruction.fieldNames.flatMap(::stringConstantIndexBytes)
                is Instruction.GetField -> listOf(CkVmImageOpcodes.GET_FIELD) + stringConstantIndexBytes(instruction.fieldName)
                is Instruction.CallFunction -> listOf(CkVmImageOpcodes.CALL_FUNCTION) + i32(instruction.functionIndex) + i32(instruction.argumentCount)
```

- [ ] **Step 4: Reuse constant-index logic for metadata strings**

Replace `pushConstant` with a `constantIndex` helper plus `stringConstantIndexBytes`:

```kotlin
        private fun pushConstant(constant: CkVmConstant): List<Int> =
            listOf(CkVmImageOpcodes.PUSH_CONSTANT) + i32(constantIndex(constant))

        private fun stringConstantIndexBytes(value: String): List<Int> =
            i32(constantIndex(CkVmConstant.StringConstant(value)))

        private fun constantIndex(constant: CkVmConstant): Int {
            val existing = constants.indexOf(constant)
            return if (existing >= 0) existing else constants.size.also { constants += constant }
        }
```

- [ ] **Step 5: Run Kotlin backend tests and verify GREEN**

Run:

```bash
./gradlew :compiler:test --tests 'ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageBackendTest'
```

Expected: PASS.

- [ ] **Step 6: Commit Kotlin lowering**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt
git commit -m "feat: lower ckvm image records fields"
```

---

### Task 3: Rust Image Runner RED Tests

**Files:**
- Modify: `native/ckl-vm/tests/image_runner.rs`

- [ ] **Step 1: Add Rust test opcode constants and halt helper**

After `const OP_CALL_FUNCTION: u8 = 15;`, add:

```rust
const OP_CONSTRUCT_RECORD: u8 = 16;
const OP_GET_FIELD: u8 = 17;

fn halt_signal(value: &VmValue) -> Vec<u8> {
    let mut signal = vec![0];
    signal.extend_from_slice(&encode_value(value));
    signal
}
```

- [ ] **Step 2: Add construction and field-access tests**

Add these tests before `rejects_string_concatenation_with_record_value()`:

```rust
#[test]
fn constructs_record_with_ordered_fields() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_CONSTRUCT_RECORD,
        2,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        3,
        0,
        0,
        0,
        4,
        0,
        0,
        0,
        OP_RETURN,
    ];
    let expected = VmValue::Record {
        type_name: "Point".to_string(),
        fields: vec![
            ("x".to_string(), VmValue::Int(2)),
            ("y".to_string(), VmValue::Int(5)),
        ],
    };
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::Int(5),
                ConstantFixture::String("Point".to_string()),
                ConstantFixture::String("x".to_string()),
                ConstantFixture::String("y".to_string()),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), halt_signal(&expected));
}

#[test]
fn gets_record_field() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_CONSTRUCT_RECORD,
        2,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        3,
        0,
        0,
        0,
        4,
        0,
        0,
        0,
        OP_GET_FIELD,
        4,
        0,
        0,
        0,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::Int(5),
                ConstantFixture::String("Point".to_string()),
                ConstantFixture::String("x".to_string()),
                ConstantFixture::String("y".to_string()),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 5, 0, 0, 0]);
}

#[test]
fn preserves_record_field_order_for_get_field() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_CONSTRUCT_RECORD,
        2,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        3,
        0,
        0,
        0,
        4,
        0,
        0,
        0,
        OP_STORE_LOCAL,
        0,
        0,
        0,
        0,
        OP_LOAD_LOCAL,
        0,
        0,
        0,
        0,
        OP_GET_FIELD,
        3,
        0,
        0,
        0,
        OP_LOAD_LOCAL,
        0,
        0,
        0,
        0,
        OP_GET_FIELD,
        4,
        0,
        0,
        0,
        OP_BINARY,
        1,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::Int(5),
                ConstantFixture::String("Point".to_string()),
                ConstantFixture::String("x".to_string()),
                ConstantFixture::String("y".to_string()),
            ],
            1,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 253, 255, 255, 255]);
}
```

- [ ] **Step 3: Add record error tests**

Add these tests after the construction/field tests:

```rust
#[test]
fn rejects_record_type_metadata_that_is_not_string() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_CONSTRUCT_RECORD,
        1,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::Int(99),
                ConstantFixture::String("x".to_string()),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("record type name constant index 1 must be String"));
}

#[test]
fn rejects_record_field_metadata_that_is_not_string() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_CONSTRUCT_RECORD,
        1,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::String("Point".to_string()),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("record field name constant index 0 must be String"));
}

#[test]
fn rejects_missing_record_field() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_CONSTRUCT_RECORD,
        1,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        OP_GET_FIELD,
        3,
        0,
        0,
        0,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::String("Point".to_string()),
                ConstantFixture::String("x".to_string()),
                ConstantFixture::String("y".to_string()),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("record `Point` has no field `y`"));
}

#[test]
fn rejects_get_field_on_non_record() {
    let code = vec![OP_PUSH_CONSTANT, 0, 0, 0, 0, OP_GET_FIELD, 1, 0, 0, 0];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![ConstantFixture::Int(2), ConstantFixture::String("x".to_string())],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("GET_FIELD requires Record receiver"));
}

#[test]
fn rejects_construct_record_stack_underflow() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_CONSTRUCT_RECORD,
        1,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        3,
        0,
        0,
        0,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::String("Point".to_string()),
                ConstantFixture::String("x".to_string()),
                ConstantFixture::String("y".to_string()),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("need 2 arguments but stack has 1"));
}
```

- [ ] **Step 4: Run Rust tests and verify RED**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner
```

Expected: FAIL because opcodes `16` and `17` are unknown.

---

### Task 4: Rust Image Runner Implementation

**Files:**
- Modify: `native/ckl-vm/src/image_runner.rs`
- Test: `native/ckl-vm/tests/image_runner.rs`

- [ ] **Step 1: Add Rust opcode constants**

After `const OP_CALL_FUNCTION: u8 = 15;`, add:

```rust
const OP_CONSTRUCT_RECORD: u8 = 16;
const OP_GET_FIELD: u8 = 17;
```

- [ ] **Step 2: Dispatch the new opcodes**

In `run_until_signal_inner`, add branches after `OP_CALL_FUNCTION`:

```rust
                OP_CALL_FUNCTION => {
                    let function_index = self.read_i32()?;
                    let argument_count = self.read_i32()?;
                    self.call_function(function_index, argument_count)?;
                }
                OP_CONSTRUCT_RECORD => self.construct_record()?,
                OP_GET_FIELD => self.get_field()?,
                other => return Err(format!("unknown CkVmImage opcode {other}")),
```

- [ ] **Step 3: Add metadata-string validation and record helpers**

Inside `impl ImageVmHandle`, add these methods after `constant_value` and before `host_import`:

```rust
    fn constant_string_metadata(
        &self,
        constant_index: i32,
        metadata_name: &str,
    ) -> Result<String, String> {
        if constant_index < 0 {
            return Err(format!(
                "negative CkVmImage {metadata_name} constant index {constant_index}"
            ));
        }
        match self.image.constants.get(constant_index as usize) {
            Some(Constant::String(value)) => Ok(value.clone()),
            Some(other) => Err(format!(
                "CkVmImage {metadata_name} constant index {constant_index} must be String metadata but found {other:?}"
            )),
            None => Err(format!(
                "CkVmImage {metadata_name} constant index {constant_index} is out of bounds"
            )),
        }
    }

    fn construct_record(&mut self) -> Result<(), String> {
        let type_name_index = self.read_i32()?;
        let type_name = self.constant_string_metadata(type_name_index, "record type name")?;
        let field_count = self.read_i32()?;
        if field_count < 0 {
            return Err(format!(
                "negative CkVmImage record field count {field_count}"
            ));
        }
        let field_count = field_count as usize;
        let mut field_names = Vec::with_capacity(field_count);
        for _ in 0..field_count {
            let field_name_index = self.read_i32()?;
            field_names.push(self.constant_string_metadata(field_name_index, "record field name")?);
        }
        let values = self.pop_many(field_count as i32)?;
        let fields = field_names.into_iter().zip(values).collect();
        self.stack.push(VmValue::Record { type_name, fields });
        Ok(())
    }

    fn get_field(&mut self) -> Result<(), String> {
        let field_name_index = self.read_i32()?;
        let field_name = self.constant_string_metadata(field_name_index, "field name")?;
        let receiver = self.pop_one("get field receiver")?;
        match receiver {
            VmValue::Record { type_name, fields } => {
                if let Some((_, value)) = fields.into_iter().find(|(name, _)| name == &field_name) {
                    self.stack.push(value);
                    Ok(())
                } else {
                    Err(format!(
                        "CkVmImage record `{type_name}` has no field `{field_name}`"
                    ))
                }
            }
            other => Err(format!(
                "CkVmImage GET_FIELD requires Record receiver but found {other:?}"
            )),
        }
    }
```

- [ ] **Step 4: Run Rust image runner tests and verify GREEN**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner
```

Expected: PASS.

- [ ] **Step 5: Commit Rust execution**

Run:

```bash
git add native/ckl-vm/src/image_runner.rs native/ckl-vm/tests/image_runner.rs
git commit -m "feat: execute ckvm image records fields"
```

---

### Task 5: JNI Integration Test

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`

- [ ] **Step 1: Add JNI record/field test**

Add this test after `imageRunnerExecutesUserFunctionCallsThroughJniWhenLibraryIsConfigured()`:

```kotlin
    @Test
    fun imageRunnerExecutesRecordConstructionAndFieldAccessThroughJniWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                struct Point { x: Int, y: Int }

                pub fun main() {
                    val point: Point = Point(x = 2, y = 5);
                    val delta: Int = point.x - point.y;
                    system::log("value=" + delta);
                }
                """.trimIndent(),
            ).image,
        )
        val runtime = RecordingRuntime()

        runBlocking {
            NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime)
        }

        assertEquals(listOf("value=-3"), runtime.lines)
    }
```

- [ ] **Step 2: Build the native library**

Run:

```bash
cargo build --manifest-path native/ckl-vm/Cargo.toml
```

Expected: PASS and creates `native/ckl-vm/target/debug/libckl_vm.so` on Linux.

- [ ] **Step 3: Run JNI test and verify GREEN**

Run:

```bash
./gradlew :compiler:test --tests 'ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeImageVmRunnerJniTest' -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so
```

Expected: PASS, including `imageRunnerExecutesRecordConstructionAndFieldAccessThroughJniWhenLibraryIsConfigured`.

- [ ] **Step 4: Commit JNI coverage**

Run:

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt
git commit -m "test: run ckvm image records fields through jni"
```

---

### Task 6: Final Verification and Cleanup

**Files:**
- Inspect: all modified files
- No planned source edits unless verification exposes a defect

- [ ] **Step 1: Run focused Kotlin backend tests**

Run:

```bash
./gradlew :compiler:test --tests 'ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageBackendTest'
```

Expected: PASS.

- [ ] **Step 2: Run Rust crate tests**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml
```

Expected: PASS.

- [ ] **Step 3: Run focused JNI tests with native library configured**

Run:

```bash
cargo build --manifest-path native/ckl-vm/Cargo.toml && ./gradlew :compiler:test --tests 'ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeImageVmRunnerJniTest' -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so
```

Expected: PASS.

- [ ] **Step 4: Check for stale unsupported references**

Run:

```bash
grep -R "does not support ConstructRecord\|unknown CkVmImage opcode 16\|unknown CkVmImage opcode 17" -n modules native || true
```

Expected: no stale production/test expectation references. A plain mention in design or plan docs is acceptable.

- [ ] **Step 5: Inspect git diff**

Run:

```bash
git --no-pager diff --stat && git --no-pager diff --check
```

Expected: `git diff --check` exits with status `0` and no whitespace errors.

- [ ] **Step 6: Commit verification fixes if any were needed**

If Step 1-5 required additional edits, commit them:

```bash
git add modules/compiler native/ckl-vm
git commit -m "fix: harden ckvm image records fields"
```

If no edits were needed, do not create an empty commit.

---

## Implementation Notes

- Keep opcode numbering synchronized between Kotlin and Rust. Do not renumber existing opcodes.
- Reuse `pop_many` for record construction so field order matches function-call argument order and frontend expression order.
- `GET_FIELD` consumes the receiver. Use locals in tests when reading multiple fields from the same record.
- Use deterministic error messages that include the failing metadata kind or receiver kind.
- Do not add `SET_FIELD`, object heap, class metadata, or collection heap support in this plan.
