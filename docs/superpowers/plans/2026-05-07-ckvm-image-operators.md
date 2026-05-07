# CKVM Image Operators Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `Instruction.Binary` and `Instruction.Unary` support to the Kotlin `CkVmImage` backend and Rust native image runner.

**Architecture:** Encode operators as two-byte instructions: image opcode plus a stable operator tag byte. Kotlin lowering appends `BINARY = 13` and `UNARY = 14`; Rust dispatches those opcodes and evaluates primitive CKL operator semantics over stack values.

**Tech Stack:** Kotlin/JVM Gradle compiler tests, Rust `ckl-vm` crate tests, JNI end-to-end tests through `NativeImageVmRunner`.

---

## File Structure

- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
  - Add `BINARY = 13` and `UNARY = 14` opcode constants.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
  - Add two-byte lowering for `Instruction.Binary` and `Instruction.Unary`.
  - Add stable private tag mapping functions for `BinaryOperator` and `UnaryOperator`.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`
  - Add backend bytecode tests for binary and unary lowering.
  - Change unsupported-instruction coverage to the next unsupported instruction type after operators.
- Modify `native/ckl-vm/src/image_runner.rs`
  - Add operator opcode constants.
  - Add stack execution for binary and unary operators.
  - Add helper functions for primitive arithmetic, logical, comparison, bitwise, and value-to-string semantics.
- Modify `native/ckl-vm/tests/image_runner.rs`
  - Add Rust RED/GREEN execution tests for operator success and error cases.
  - Extend the test image helper with constant-pool support.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`
  - Add a CKL source → image → native runner test combining arithmetic, comparison, unary, and logical operators.

---

### Task 1: RED Kotlin Backend Operator Coverage

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`

- [ ] **Step 1: Add imports for operator enums**

Add these imports near the existing `Instruction` import:

```kotlin
import ru.lazyhat.compukterkraft.lang.api.BinaryOperator
import ru.lazyhat.compukterkraft.lang.api.UnaryOperator
```

- [ ] **Step 2: Add a failing backend lowering test**

Insert this test after `compileImageLowersForwardAndBackwardJumpsToByteOffsets`:

```kotlin
    @Test
    fun compileImageLowersBinaryAndUnaryOperators() {
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    val value: Int = 1 + 2 * 3;
                    val ok: Bool = value >= 7 && !false;
                    if (ok) {
                        system::log("ok");
                    }
                }
                """.trimIndent(),
            ).image,
        )

        assertEquals(
            listOf(
                CkVmConstant.IntConstant(1),
                CkVmConstant.IntConstant(2),
                CkVmConstant.IntConstant(3),
                CkVmConstant.IntConstant(7),
                CkVmConstant.StringConstant("ok"),
            ),
            image.constants,
        )
        assertContentEquals(
            listOf(
                CkVmImageOpcodes.PUSH_CONSTANT, 0, 0, 0, 0,
                CkVmImageOpcodes.PUSH_CONSTANT, 1, 0, 0, 0,
                CkVmImageOpcodes.PUSH_CONSTANT, 2, 0, 0, 0,
                CkVmImageOpcodes.BINARY, BinaryOperator.MULTIPLY.ordinal,
                CkVmImageOpcodes.BINARY, BinaryOperator.ADD.ordinal,
                CkVmImageOpcodes.STORE_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.LOAD_LOCAL, 0, 0, 0, 0,
                CkVmImageOpcodes.PUSH_CONSTANT, 3, 0, 0, 0,
                CkVmImageOpcodes.BINARY, BinaryOperator.GREATER_EQUALS.ordinal,
                CkVmImageOpcodes.PUSH_BOOL, 0,
                CkVmImageOpcodes.UNARY, UnaryOperator.NOT.ordinal,
                CkVmImageOpcodes.BINARY, BinaryOperator.AND.ordinal,
                CkVmImageOpcodes.STORE_LOCAL, 1, 0, 0, 0,
                CkVmImageOpcodes.LOAD_LOCAL, 1, 0, 0, 0,
                CkVmImageOpcodes.JUMP_IF_FALSE, 77, 0, 0, 0,
                CkVmImageOpcodes.PUSH_CONSTANT, 4, 0, 0, 0,
                CkVmImageOpcodes.CALL_HOST, 188, 11, 0, 0, 1, 0, 0, 0,
                CkVmImageOpcodes.POP,
                CkVmImageOpcodes.JUMP, 77, 0, 0, 0,
                CkVmImageOpcodes.PUSH_UNIT,
                CkVmImageOpcodes.RETURN,
            ),
            image.functions.single().code,
        )
    }
```

- [ ] **Step 3: Move unsupported-instruction coverage past operators**

Replace `unsupportedInstructionReportsClearError` with this version:

```kotlin
    @Test
    fun unsupportedInstructionReportsClearError() {
        val artifact = LanguageFrontend().compile(
            "main.ck",
            """
            fun helper(): Int { return 1; }
            pub fun main() { val x: Int = helper(); }
            """.trimIndent(),
        )
        val module = assertNotNull(artifact.module)

        val error = assertFailsWith<UnsupportedOperationException> {
            CkVmImageCompiler.compile(module)
        }

        assertTrue(error.message.orEmpty().contains("CkVmImage backend does not support CallFunction"))
    }
```

- [ ] **Step 4: Run Kotlin backend test and verify RED**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --rerun-tasks --console=plain
```

Expected: compile fails because `CkVmImageOpcodes.BINARY` and `CkVmImageOpcodes.UNARY` do not exist yet, or the test fails with `CkVmImage backend does not support Binary` if constants were added before this task is run.

- [ ] **Step 5: Commit RED tests**

Run:

```bash
git diff --check
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt
git commit -m "test: require ckvm image operators"
```

Expected: commit succeeds and contains only test changes.

---

### Task 2: Implement Kotlin Image Operator Lowering

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`

- [ ] **Step 1: Add opcode constants**

In `CkVmImageOpcodes`, append:

```kotlin
    const val BINARY = 13
    const val UNARY = 14
```

- [ ] **Step 2: Import operator enums in the backend**

At the top of `CkVmImageBackend.kt`, add:

```kotlin
import ru.lazyhat.compukterkraft.lang.api.BinaryOperator
import ru.lazyhat.compukterkraft.lang.api.UnaryOperator
```

- [ ] **Step 3: Add operator instruction lengths**

In `instructionLength`, add these cases before the final `else`:

```kotlin
                is Instruction.Binary,
                is Instruction.Unary,
                -> 2
```

- [ ] **Step 4: Add operator lowering cases**

In `lowerInstruction`, add these cases before `Instruction.CallBuiltin`:

```kotlin
                is Instruction.Binary -> listOf(CkVmImageOpcodes.BINARY, binaryOperatorTag(instruction.operator))
                is Instruction.Unary -> listOf(CkVmImageOpcodes.UNARY, unaryOperatorTag(instruction.operator))
```

- [ ] **Step 5: Add stable operator tag functions**

Inside `LoweringContext`, after `callBuiltin`, add:

```kotlin
        private fun binaryOperatorTag(operator: BinaryOperator): Int =
            when (operator) {
                BinaryOperator.ADD -> 0
                BinaryOperator.SUBTRACT -> 1
                BinaryOperator.MULTIPLY -> 2
                BinaryOperator.DIVIDE -> 3
                BinaryOperator.EQUALS -> 4
                BinaryOperator.NOT_EQUALS -> 5
                BinaryOperator.LESS -> 6
                BinaryOperator.LESS_EQUALS -> 7
                BinaryOperator.GREATER -> 8
                BinaryOperator.GREATER_EQUALS -> 9
                BinaryOperator.AND -> 10
                BinaryOperator.OR -> 11
                BinaryOperator.BIT_AND -> 12
                BinaryOperator.BIT_OR -> 13
                BinaryOperator.BIT_XOR -> 14
                BinaryOperator.SHIFT_LEFT -> 15
                BinaryOperator.SHIFT_RIGHT -> 16
            }

        private fun unaryOperatorTag(operator: UnaryOperator): Int =
            when (operator) {
                UnaryOperator.NEGATE -> 0
                UnaryOperator.NOT -> 1
                UnaryOperator.BIT_NOT -> 2
            }
```

These values intentionally match the current enum ordinals while avoiding direct enum ordinal use in production image encoding.

- [ ] **Step 6: Run Kotlin backend test and verify GREEN**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --rerun-tasks --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit Kotlin lowering**

Run:

```bash
git diff --check
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt
git commit -m "feat: lower ckvm image operators"
```

Expected: commit succeeds with Kotlin production and test changes.

---

### Task 3: RED Rust Image Runner Operator Tests

**Files:**
- Modify: `native/ckl-vm/tests/image_runner.rs`

- [ ] **Step 1: Add operator opcode constants to the Rust test**

Near the existing opcode constants, add:

```rust
const OP_PUSH_CONSTANT: u8 = 3;
const OP_BINARY: u8 = 13;
const OP_UNARY: u8 = 14;
```

- [ ] **Step 2: Add constant fixture enum and helper**

Above `image_with_code`, add:

```rust
enum ConstantFixture {
    String(String),
    Int(i32),
    Long(i64),
}

fn image_with_constants_and_code(
    constants: Vec<ConstantFixture>,
    frame_size: i32,
    code: Vec<u8>,
) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"CKIM");
    out.push(1);
    string(&mut out, "ckl-1");
    u16(&mut out, 1);
    list_len(&mut out, 0);
    list_len(&mut out, constants.len() as i32);
    for constant in constants {
        match constant {
            ConstantFixture::String(value) => {
                out.push(1);
                string(&mut out, &value);
            }
            ConstantFixture::Int(value) => {
                out.push(2);
                i32(&mut out, value);
            }
            ConstantFixture::Long(value) => {
                out.push(3);
                out.extend_from_slice(&value.to_le_bytes());
            }
        }
    }
    list_len(&mut out, 0);
    i32(&mut out, 0);
    list_len(&mut out, 1);
    string(&mut out, "main");
    i32(&mut out, frame_size);
    list_len(&mut out, code.len() as i32);
    out.extend_from_slice(&code);
    out
}
```

- [ ] **Step 3: Simplify `image_with_code` to reuse the new helper**

Replace `image_with_code` with:

```rust
fn image_with_code(frame_size: i32, code: Vec<u8>) -> Vec<u8> {
    image_with_constants_and_code(Vec::new(), frame_size, code)
}
```

- [ ] **Step 4: Add operator execution tests**

Insert these tests before `image_with_code`:

```rust
#[test]
fn executes_int_arithmetic_and_comparison() {
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
        OP_BINARY,
        0,
        OP_PUSH_CONSTANT,
        2,
        0,
        0,
        0,
        OP_BINARY,
        9,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::Int(5),
                ConstantFixture::Int(7),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 2, 1]);
}

#[test]
fn executes_bool_logic_and_unary_not() {
    let code = vec![
        OP_PUSH_BOOL,
        1,
        OP_PUSH_BOOL,
        0,
        OP_UNARY,
        1,
        OP_BINARY,
        10,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 2, 1]);
}

#[test]
fn executes_string_concatenation() {
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
        OP_BINARY,
        0,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::String("hello ".to_string()),
                ConstantFixture::Int(42),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(
        vm.run_until_signal(),
        vec![0, 5, 8, 0, 0, 0, b'h', b'e', b'l', b'l', b'o', b' ', b'4', b'2'],
    );
}

#[test]
fn executes_long_bitwise_and_unary_bit_not() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_UNARY,
        2,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_BINARY,
        12,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![ConstantFixture::Long(0), ConstantFixture::Long(255)],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 4, 255, 0, 0, 0, 0, 0, 0, 0]);
}

#[test]
fn rejects_binary_wrong_operand_type() {
    let code = vec![OP_PUSH_UNIT, OP_PUSH_BOOL, 1, OP_BINARY, 0, OP_RETURN];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("requires"));
}

#[test]
fn rejects_division_by_zero() {
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
        OP_BINARY,
        3,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![ConstantFixture::Int(1), ConstantFixture::Int(0)],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("division by zero"));
}

#[test]
fn rejects_unknown_operator_tag() {
    let code = vec![OP_PUSH_BOOL, 1, OP_PUSH_BOOL, 1, OP_BINARY, 99, OP_RETURN];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("unknown CkVmImage binary operator tag 99"));
}
```

- [ ] **Step 5: Run Rust operator test and verify RED**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner -- --nocapture
```

Expected: test binary compiles, and the new tests fail with `unknown CkVmImage opcode 13` or `unknown CkVmImage opcode 14`.

- [ ] **Step 6: Format and commit RED Rust tests**

Run:

```bash
cargo fmt --manifest-path native/ckl-vm/Cargo.toml
git diff --check
git add native/ckl-vm/tests/image_runner.rs
git commit -m "test: require rust image operators"
```

Expected: commit succeeds with Rust test changes.

---

### Task 4: Implement Rust Operator Execution

**Files:**
- Modify: `native/ckl-vm/src/image_runner.rs`

- [ ] **Step 1: Add operator opcode constants**

Add after `OP_JUMP_IF_TRUE`:

```rust
const OP_BINARY: u8 = 13;
const OP_UNARY: u8 = 14;
```

- [ ] **Step 2: Add opcode dispatch cases**

In `run_until_signal_inner`, add before the unknown opcode case:

```rust
                OP_BINARY => {
                    let operator = self.read_u8()?.ok_or_else(|| {
                        "unexpected end of CkVmImage instruction stream".to_string()
                    })?;
                    let right = self.pop_one("binary right operand")?;
                    let left = self.pop_one("binary left operand")?;
                    self.stack.push(apply_binary_operator(operator, left, right)?);
                }
                OP_UNARY => {
                    let operator = self.read_u8()?.ok_or_else(|| {
                        "unexpected end of CkVmImage instruction stream".to_string()
                    })?;
                    let operand = self.pop_one("unary operand")?;
                    self.stack.push(apply_unary_operator(operator, operand)?);
                }
```

- [ ] **Step 3: Add operator helper functions**

Add these free functions after `checked_frame_size`:

```rust
fn apply_binary_operator(operator: u8, left: VmValue, right: VmValue) -> Result<VmValue, String> {
    match operator {
        0 => binary_add(left, right),
        1 => numeric_binary(left, right, "-", |a, b| a.wrapping_sub(b), |a, b| a.wrapping_sub(b)),
        2 => numeric_binary(left, right, "*", |a, b| a.wrapping_mul(b), |a, b| a.wrapping_mul(b)),
        3 => binary_divide(left, right),
        4 => Ok(VmValue::Bool(value_equals(&left, &right))),
        5 => Ok(VmValue::Bool(!value_equals(&left, &right))),
        6 => compare_values(left, right, "<", |ordering| ordering.is_lt()),
        7 => compare_values(left, right, "<=", |ordering| !ordering.is_gt()),
        8 => compare_values(left, right, ">", |ordering| ordering.is_gt()),
        9 => compare_values(left, right, ">=", |ordering| !ordering.is_lt()),
        10 => bool_binary(left, right, "&&", |a, b| a && b),
        11 => bool_binary(left, right, "||", |a, b| a || b),
        12 => numeric_binary(left, right, "&", |a, b| a & b, |a, b| a & b),
        13 => numeric_binary(left, right, "|", |a, b| a | b, |a, b| a | b),
        14 => numeric_binary(left, right, "^", |a, b| a ^ b, |a, b| a ^ b),
        15 => shift_binary(left, right, "<<", |a, b| a.wrapping_shl(b), |a, b| a.wrapping_shl(b)),
        16 => shift_binary(left, right, ">>", |a, b| a.wrapping_shr(b), |a, b| a.wrapping_shr(b)),
        other => Err(format!("unknown CkVmImage binary operator tag {other}")),
    }
}

fn apply_unary_operator(operator: u8, operand: VmValue) -> Result<VmValue, String> {
    match operator {
        0 => match operand {
            VmValue::Int(value) => Ok(VmValue::Int(value.wrapping_neg())),
            VmValue::Long(value) => Ok(VmValue::Long(value.wrapping_neg())),
            other => Err(format!("CkVmImage unary - requires Int or Long but found {other:?}")),
        },
        1 => match operand {
            VmValue::Bool(value) => Ok(VmValue::Bool(!value)),
            other => Err(format!("CkVmImage unary ! requires Bool but found {other:?}")),
        },
        2 => match operand {
            VmValue::Int(value) => Ok(VmValue::Int(!value)),
            VmValue::Long(value) => Ok(VmValue::Long(!value)),
            other => Err(format!("CkVmImage unary ~ requires Int or Long but found {other:?}")),
        },
        other => Err(format!("unknown CkVmImage unary operator tag {other}")),
    }
}

fn binary_add(left: VmValue, right: VmValue) -> Result<VmValue, String> {
    match (left, right) {
        (VmValue::String(left), right) => Ok(VmValue::String(left + &value_to_string(&right))),
        (left, VmValue::String(right)) => Ok(VmValue::String(value_to_string(&left) + &right)),
        (VmValue::Int(left), VmValue::Int(right)) => Ok(VmValue::Int(left.wrapping_add(right))),
        (VmValue::Long(left), VmValue::Long(right)) => Ok(VmValue::Long(left.wrapping_add(right))),
        (VmValue::Int(left), VmValue::Long(right)) => Ok(VmValue::Long((left as i64).wrapping_add(right))),
        (VmValue::Long(left), VmValue::Int(right)) => Ok(VmValue::Long(left.wrapping_add(right as i64))),
        (left, right) => Err(format!("CkVmImage binary + requires numbers or strings but found {left:?} and {right:?}")),
    }
}

fn binary_divide(left: VmValue, right: VmValue) -> Result<VmValue, String> {
    match (left, right) {
        (_, VmValue::Int(0)) | (_, VmValue::Long(0)) => Err("CkVmImage division by zero".to_string()),
        (VmValue::Int(left), VmValue::Int(right)) => Ok(VmValue::Int(left.wrapping_div(right))),
        (VmValue::Long(left), VmValue::Long(right)) => Ok(VmValue::Long(left.wrapping_div(right))),
        (VmValue::Int(left), VmValue::Long(right)) => Ok(VmValue::Long((left as i64).wrapping_div(right))),
        (VmValue::Long(left), VmValue::Int(right)) => Ok(VmValue::Long(left.wrapping_div(right as i64))),
        (left, right) => Err(format!("CkVmImage binary / requires Int or Long but found {left:?} and {right:?}")),
    }
}

fn numeric_binary(
    left: VmValue,
    right: VmValue,
    symbol: &str,
    int_op: fn(i32, i32) -> i32,
    long_op: fn(i64, i64) -> i64,
) -> Result<VmValue, String> {
    match (left, right) {
        (VmValue::Int(left), VmValue::Int(right)) => Ok(VmValue::Int(int_op(left, right))),
        (VmValue::Long(left), VmValue::Long(right)) => Ok(VmValue::Long(long_op(left, right))),
        (VmValue::Int(left), VmValue::Long(right)) => Ok(VmValue::Long(long_op(left as i64, right))),
        (VmValue::Long(left), VmValue::Int(right)) => Ok(VmValue::Long(long_op(left, right as i64))),
        (left, right) => Err(format!("CkVmImage binary {symbol} requires Int or Long but found {left:?} and {right:?}")),
    }
}

fn shift_binary(
    left: VmValue,
    right: VmValue,
    symbol: &str,
    int_op: fn(i32, u32) -> i32,
    long_op: fn(i64, u32) -> i64,
) -> Result<VmValue, String> {
    let shift = match right {
        VmValue::Int(value) => value as u32,
        VmValue::Long(value) => value as u32,
        other => return Err(format!("CkVmImage binary {symbol} shift count requires Int or Long but found {other:?}")),
    };
    match left {
        VmValue::Int(value) => Ok(VmValue::Int(int_op(value, shift))),
        VmValue::Long(value) => Ok(VmValue::Long(long_op(value, shift))),
        other => Err(format!("CkVmImage binary {symbol} requires Int or Long left operand but found {other:?}")),
    }
}

fn bool_binary(
    left: VmValue,
    right: VmValue,
    symbol: &str,
    op: fn(bool, bool) -> bool,
) -> Result<VmValue, String> {
    match (left, right) {
        (VmValue::Bool(left), VmValue::Bool(right)) => Ok(VmValue::Bool(op(left, right))),
        (left, right) => Err(format!("CkVmImage binary {symbol} requires Bool but found {left:?} and {right:?}")),
    }
}

fn compare_values(
    left: VmValue,
    right: VmValue,
    symbol: &str,
    predicate: fn(std::cmp::Ordering) -> bool,
) -> Result<VmValue, String> {
    let ordering = match (left, right) {
        (VmValue::Int(left), VmValue::Int(right)) => left.cmp(&right),
        (VmValue::Long(left), VmValue::Long(right)) => left.cmp(&right),
        (VmValue::Int(left), VmValue::Long(right)) => (left as i64).cmp(&right),
        (VmValue::Long(left), VmValue::Int(right)) => left.cmp(&(right as i64)),
        (VmValue::String(left), VmValue::String(right)) => left.cmp(&right),
        (left, right) => return Err(format!("CkVmImage binary {symbol} requires comparable values but found {left:?} and {right:?}")),
    };
    Ok(VmValue::Bool(predicate(ordering)))
}

fn value_equals(left: &VmValue, right: &VmValue) -> bool {
    match (left, right) {
        (VmValue::Int(left), VmValue::Long(right)) => i64::from(*left) == *right,
        (VmValue::Long(left), VmValue::Int(right)) => *left == i64::from(*right),
        _ => left == right,
    }
}

fn value_to_string(value: &VmValue) -> String {
    match value {
        VmValue::Unit => "unit".to_string(),
        VmValue::Null => "null".to_string(),
        VmValue::Bool(value) => value.to_string(),
        VmValue::Int(value) => value.to_string(),
        VmValue::Long(value) => value.to_string(),
        VmValue::String(value) => value.clone(),
        VmValue::Record { type_name, .. } => format!("{type_name}(...)"),
        VmValue::ObjectRef(value) => format!("object#{value}"),
    }
}
```

- [ ] **Step 4: Run Rust tests and verify GREEN**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml -- --nocapture
```

Expected: all Rust tests pass.

- [ ] **Step 5: Format and commit Rust implementation**

Run:

```bash
cargo fmt --manifest-path native/ckl-vm/Cargo.toml
git diff --check
git add native/ckl-vm/src/image_runner.rs native/ckl-vm/tests/image_runner.rs
git commit -m "feat: execute ckvm image operators"
```

Expected: commit succeeds with Rust production and test changes.

---

### Task 5: JNI End-to-End Operator Test

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`

- [ ] **Step 1: Add JNI source-level operator test**

Insert this test after `imageRunnerExecutesIfConditionAndLocalThroughJniWhenLibraryIsConfigured`:

```kotlin
    @Test
    fun imageRunnerExecutesOperatorsThroughJniWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(
            LanguageFrontend().compileImage(
                "main.ck",
                """
                pub fun main() {
                    val value: Int = 1 + 2 * 3;
                    val ok: Bool = value >= 7 && !false;
                    if (ok) {
                        system::log("value=" + value);
                    }
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

Run:

```bash
./gradlew buildRustVmNativeLibrary --console=plain
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --tests '*NativeImageVmRunnerJniTest' --rerun-tasks -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so --console=plain
```

Expected: both Gradle commands end with `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit JNI operator test**

Run:

```bash
git diff --check
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt
git commit -m "test: run ckvm image operators through jni"
```

Expected: commit succeeds with JNI test changes.

---

### Task 6: Final Verification

**Files:**
- Verify all files changed by Tasks 1-5.

- [ ] **Step 1: Run focused Kotlin/JNI verification**

Run:

```bash
./gradlew buildRustVmNativeLibrary --console=plain
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --tests '*CkVmImageAbiTest' --tests '*CkVmImageComputerProgramTest' --tests '*NativeImageVmRunnerJniTest' --tests '*NativeImageVmBindingsJniTest' :v1_21_1-neoforge:test --tests '*DeviceProgramSupportTest' --rerun-tasks -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so --console=plain
```

Expected: both Gradle commands end with `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run Rust verification**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml -- --nocapture
```

Expected: all Rust tests pass.

- [ ] **Step 3: Check stale unsupported-operator references**

Run:

```bash
grep -R -n -E 'CkVmImage backend does not support (Binary|Unary)' modules/compiler/src/main/kotlin modules/compiler/src/test/kotlin || true
```

Expected: no output.

- [ ] **Step 4: Check whitespace and working tree**

Run:

```bash
git diff --check
git status --short --untracked-files=all
```

Expected: no output from either command.

- [ ] **Step 5: Request code review**

Review the commits created by this plan against `HEAD` before Task 1 started. Ask the reviewer to focus on:

- Kotlin/Rust opcode value synchronization.
- Operator tag synchronization.
- Runtime type errors and division by zero.
- JNI source-level operator coverage.
- Preservation of image-only runtime architecture.

Expected: no blocking review findings remain.
