# Typed Register VM Rewrite Implementation Plan

> **Superseded:** This plan described the first dynamic-register rewrite direction. Use `docs/superpowers/plans/2026-05-11-typed-register-bank-vm.md` for the active register-bank implementation plan.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current CKL stack image VM with a typed, predecoded, register-based Rust VM without keeping runtime fallbacks.

**Architecture:** The compiler emits one active register image format. The Rust native library decodes that format into predecoded typed instructions and executes it with register frames, compact slots, and the existing VM signal protocol. The old stack bytecode/image runner is removed after register VM parity is proven by tests and benchmarks.

**Tech Stack:** Kotlin/JVM compiler module, Rust `native/ckl-vm`, JNI bindings, Gradle profiling tasks, JUnit/Kotlin tests, Rust integration tests.

---

## Rewrite Rules

- No runtime fallback to the old stack VM.
- No Kotlin execution fallback.
- Temporary parity tests may compare old and new behavior while a task is in progress, but production entry points must fail fast rather than route to old execution.
- The final state has one image runner and one active image ABI.
- Commit after each completed task.

---

## Target File Structure

- Replace `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
  - Register image data model.
- Replace `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbi.kt`
  - Register image encoder using `CKIM` version `2`.
- Replace `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
  - Lower existing typed frontend bytecode into register instructions.
- Replace `native/ckl-vm/src/image.rs`
  - Rust decoder for register image format.
- Replace `native/ckl-vm/src/image_runner.rs`
  - Typed register interpreter.
- Keep `native/ckl-vm/src/signal.rs`, `native/ckl-vm/src/value.rs`, `native/ckl-vm/src/device_daemon.rs`, `native/ckl-vm/src/runtime_kernel.rs`
  - External signal, JNI, daemon, and hostcall boundaries remain stable.
- Update benchmark test files under `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/`
  - Report only the new CK VM plus Kotlin/Python/Rust baselines.

---

## Task 1: Replace Kotlin Image Model With Register Image Model

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbiTest.kt`

- [ ] **Step 1: Write the failing model/ABI expectation**

Update `CkVmImageAbiTest.kt` or add a new test in the same package:

```kotlin
@Test
fun encodeUsesRegisterImageVersionTwo() {
    val image =
        CkVmImage(
            languageVersion = "ckl-1",
            constants = listOf(CkVmConstant.IntConstant(7)),
            hostImports = emptyList(),
            entryFunctionIndex = 0,
            functions =
                listOf(
                    CkVmFunction(
                        name = "main.ck#main",
                        registerCount = 2,
                        parameterCount = 0,
                        instructions =
                            listOf(
                                CkVmInstruction.LoadConst(dst = 0, constantIndex = 0),
                                CkVmInstruction.Return(src = 0),
                            ),
                    ),
                ),
        )

    val bytes = CkVmImageAbi.encode(image)
    val reader = Reader(bytes)

    assertEquals("CKIM", reader.magic())
    assertEquals(2, reader.u8())
    assertEquals("ckl-1", reader.string())
    assertEquals(1, reader.i32(), "constants")
    assertEquals(CkVmImageAbi.ConstantTags.INT, reader.u8())
    assertEquals(7, reader.i32())
    assertEquals(0, reader.i32(), "host imports")
    assertEquals(0, reader.i32(), "entry function")
    assertEquals(1, reader.i32(), "functions")
    assertEquals("main.ck#main", reader.string())
    assertEquals(2, reader.i32(), "register count")
    assertEquals(0, reader.i32(), "parameter count")
    assertEquals(2, reader.i32(), "instruction count")
}
```

Expected old names like `frameSize` and `code` to be removed from test fixtures.

- [ ] **Step 2: Run and verify failure**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageAbiTest'
```

Expected: FAIL/compile fail because `CkVmFunction` still has `frameSize/code` and there is no `CkVmInstruction`.

- [ ] **Step 3: Replace the Kotlin image model**

In `CkVmImage.kt`, replace stack-code fields with:

```kotlin
data class CkVmFunction(
    val name: String,
    val registerCount: Int,
    val parameterCount: Int,
    val instructions: List<CkVmInstruction>,
)

sealed interface CkVmInstruction {
    data class LoadConst(val dst: Int, val constantIndex: Int) : CkVmInstruction
    data class LoadUnit(val dst: Int) : CkVmInstruction
    data class LoadNull(val dst: Int) : CkVmInstruction
    data class LoadBool(val dst: Int, val value: Boolean) : CkVmInstruction
    data class Move(val dst: Int, val src: Int) : CkVmInstruction
    data class I32Add(val dst: Int, val lhs: Int, val rhs: Int) : CkVmInstruction
    data class I32Sub(val dst: Int, val lhs: Int, val rhs: Int) : CkVmInstruction
    data class I32Mul(val dst: Int, val lhs: Int, val rhs: Int) : CkVmInstruction
    data class I32Div(val dst: Int, val lhs: Int, val rhs: Int) : CkVmInstruction
    data class I32BitXor(val dst: Int, val lhs: Int, val rhs: Int) : CkVmInstruction
    data class I32Shl(val dst: Int, val lhs: Int, val rhs: Int) : CkVmInstruction
    data class I32Shr(val dst: Int, val lhs: Int, val rhs: Int) : CkVmInstruction
    data class I32Eq(val dst: Int, val lhs: Int, val rhs: Int) : CkVmInstruction
    data class I32Lt(val dst: Int, val lhs: Int, val rhs: Int) : CkVmInstruction
    data class Jump(val target: Int) : CkVmInstruction
    data class JumpIfFalse(val cond: Int, val target: Int) : CkVmInstruction
    data class JumpIfTrue(val cond: Int, val target: Int) : CkVmInstruction
    data class CallStatic(val returnDst: Int, val functionIndex: Int, val args: List<Int>) : CkVmInstruction
    data class Return(val src: Int) : CkVmInstruction
    data object ReturnUnit : CkVmInstruction
}
```

- [ ] **Step 4: Run focused compile**

Run:

```bash
./gradlew :compiler:compileKotlin
```

Expected: FAIL in `CkVmImageAbi.kt` and `CkVmImageBackend.kt`, because they still expect stack bytecode.

- [ ] **Step 5: Commit only if model compiles after Task 2**

Do not commit at this step if the project does not compile. Continue directly into Task 2.

---

## Task 2: Replace Kotlin Image ABI Encoder

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbi.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbiTest.kt`

- [ ] **Step 1: Implement ABI version 2 encoder**

In `CkVmImageAbi.kt`:

- Set `VERSION = 2`.
- Remove bytecode array encoding.
- Encode functions as `name`, `registerCount`, `parameterCount`, `instructions`.
- Add opcode constants for register instructions.

Use this opcode allocation:

```kotlin
object Opcodes {
    const val LOAD_CONST = 1
    const val LOAD_UNIT = 2
    const val LOAD_NULL = 3
    const val LOAD_BOOL = 4
    const val MOVE = 5
    const val I32_ADD = 20
    const val I32_SUB = 21
    const val I32_MUL = 22
    const val I32_DIV = 23
    const val I32_BIT_XOR = 24
    const val I32_SHL = 25
    const val I32_SHR = 26
    const val I32_EQ = 27
    const val I32_LT = 28
    const val JUMP = 40
    const val JUMP_IF_FALSE = 41
    const val JUMP_IF_TRUE = 42
    const val CALL_STATIC = 60
    const val RETURN = 70
    const val RETURN_UNIT = 71
}
```

- [ ] **Step 2: Run ABI tests**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageAbiTest'
```

Expected: PASS for ABI tests after fixture updates.

- [ ] **Step 3: Commit model and ABI together**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbi.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbiTest.kt
git commit -m "Replace image ABI with typed register format"
```

---

## Task 3: Replace Kotlin Image Backend For Compute Subset

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`

- [ ] **Step 1: Write failing register lowering test**

Update or add:

```kotlin
@Test
fun compileImageLowersIntegerExpressionToRegisterInstructions() {
    val image =
        assertNotNull(
            LanguageFrontend()
                .compileImage(
                    "main.ck",
                    """
                    pub fun main(): Int {
                        val a: Int = 2;
                        val b: Int = 5;
                        return a + b;
                    }
                    """.trimIndent(),
                ).image,
        )

    val main = image.functions.single { it.name == "main.ck#main" }

    assertEquals(
        listOf(
            CkVmInstruction.LoadConst(dst = 0, constantIndex = 0),
            CkVmInstruction.LoadConst(dst = 1, constantIndex = 1),
            CkVmInstruction.I32Add(dst = 2, lhs = 0, rhs = 1),
            CkVmInstruction.Return(src = 2),
        ),
        main.instructions,
    )
}
```

- [ ] **Step 2: Run and verify failure**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest'
```

Expected: FAIL because backend still emits stack bytecode or does not compile.

- [ ] **Step 3: Implement compute subset lowering**

Rewrite the lowering context to use virtual registers:

- local slots keep their local index;
- temporary expression registers start at `function.locals.size`;
- stack is only a compiler-internal lowering stack of register indices;
- emitted instructions are register instructions.

Support first:

- `PushInt`
- `PushBool`
- `PushUnit`
- `LoadLocal`
- `StoreLocal`
- `Binary` for integer arithmetic/comparison used by compute benchmark
- `Jump`
- `JumpIfFalse`
- `JumpIfTrue`
- `CallFunction`
- `Return`

Unsupported bytecode should throw `UnsupportedOperationException` with the instruction type name.

- [ ] **Step 4: Run focused tests**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest'
```

Expected: PASS for updated register-backend tests.

- [ ] **Step 5: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt
git commit -m "Lower image backend to typed registers"
```

---

## Task 4: Replace Rust Image Decoder

**Files:**
- Modify: `native/ckl-vm/src/image.rs`
- Modify: `native/ckl-vm/tests/image_decode.rs`

- [ ] **Step 1: Write failing decoder test**

Update `native/ckl-vm/tests/image_decode.rs` to expect ABI version `2`, function register counts, parameter counts, and decoded register instructions.

Expected decoded instruction example:

```rust
Instruction::LoadConst {
    dst: 0,
    constant_index: 0,
}
```

- [ ] **Step 2: Run and verify failure**

Run:

```bash
cargo test --test image_decode
```

Expected: FAIL because Rust decoder still expects stack image shape.

- [ ] **Step 3: Replace decoder data model**

In `native/ckl-vm/src/image.rs`, replace stack function/code model with:

```rust
pub struct Function {
    pub name: String,
    pub register_count: usize,
    pub parameter_count: usize,
    pub instructions: Vec<Instruction>,
}

pub enum Instruction {
    LoadConst { dst: u16, constant_index: usize },
    LoadUnit { dst: u16 },
    LoadNull { dst: u16 },
    LoadBool { dst: u16, value: bool },
    Move { dst: u16, src: u16 },
    I32Add { dst: u16, lhs: u16, rhs: u16 },
    I32Sub { dst: u16, lhs: u16, rhs: u16 },
    I32Mul { dst: u16, lhs: u16, rhs: u16 },
    I32Div { dst: u16, lhs: u16, rhs: u16 },
    I32BitXor { dst: u16, lhs: u16, rhs: u16 },
    I32Shl { dst: u16, lhs: u16, rhs: u16 },
    I32Shr { dst: u16, lhs: u16, rhs: u16 },
    I32Eq { dst: u16, lhs: u16, rhs: u16 },
    I32Lt { dst: u16, lhs: u16, rhs: u16 },
    Jump { target: usize },
    JumpIfFalse { cond: u16, target: usize },
    JumpIfTrue { cond: u16, target: usize },
    CallStatic { return_dst: u16, function_index: usize, args: Vec<u16> },
    Return { src: u16 },
    ReturnUnit,
}
```

Reject any image version other than `2`.

- [ ] **Step 4: Run decoder tests**

Run:

```bash
cargo test --test image_decode
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add native/ckl-vm/src/image.rs native/ckl-vm/tests/image_decode.rs
git commit -m "Replace Rust image decoder with register format"
```

---

## Task 5: Replace Rust Image Runner With Register Interpreter

**Files:**
- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `native/ckl-vm/tests/image_runner.rs`

- [ ] **Step 1: Write failing register runner tests**

Update `native/ckl-vm/tests/image_runner.rs` to build decoded register `Image` values and assert:

- halt with int constant;
- integer arithmetic;
- jumps;
- static function calls;
- pause after instruction budget.

- [ ] **Step 2: Run and verify failure**

Run:

```bash
cargo test --test image_runner
```

Expected: FAIL because runner still uses stack bytecode fields.

- [ ] **Step 3: Replace stack runner state**

Replace:

- `stack: Vec<VmValue>`
- `locals: Vec<VmValue>`
- `CallFrame { locals: Vec<VmValue> }`

with:

```rust
enum ValueSlot {
    Unit,
    Null,
    Bool(bool),
    I32(i32),
    I64(i64),
    String(u32),
    Object(u32),
}

struct RegisterFrame {
    function_index: usize,
    instruction_pointer: usize,
    base_register: usize,
    return_register: Option<u16>,
}
```

Use one `registers: Vec<ValueSlot>` for all active frames.

- [ ] **Step 4: Implement register instruction execution**

Implement first:

- load constants/unit/null/bool;
- move;
- integer arithmetic and comparisons;
- jumps;
- static calls;
- return;
- pause budget.

- [ ] **Step 5: Run Rust image runner tests**

Run:

```bash
cargo test --test image_runner
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add native/ckl-vm/src/image_runner.rs native/ckl-vm/tests/image_runner.rs
git commit -m "Replace image runner with register interpreter"
```

---

## Task 6: Reconnect JNI And Compiler Tests

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`
- Modify: `native/ckl-vm/src/jni.rs` if necessary.

- [ ] **Step 1: Run JNI tests and collect failures**

Run:

```bash
./gradlew :v1_21_1-neoforge:buildRustVmNativeLibrary :compiler:test --tests '*NativeImageVmBindingsJniTest' --tests '*NativeImageVmRunnerJniTest' -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so
```

Expected: failures for unsupported register VM instructions or tests still asserting stack-specific behavior.

- [ ] **Step 2: Update tests to assert register VM behavior**

Keep behavior assertions:

- empty main halts;
- arithmetic logs correct values;
- function calls work;
- yield/sleep signals work.

Remove stack bytecode shape assertions.

- [ ] **Step 3: Add missing runner instructions only when required by tests**

Implement minimal additional register instructions for failing tests. Do not add generic fallback dispatch.

- [ ] **Step 4: Run JNI tests again**

Run the same Gradle command.

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt native/ckl-vm/src/jni.rs native/ckl-vm/src/image_runner.rs
git commit -m "Reconnect JNI tests to register VM"
```

---

## Task 7: Restore Compute Benchmark On Register VM

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/ComputeVmBenchmarkReport.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/ComputeVmBenchmarkRunners.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/ComputeVmBenchmarkReportTest.kt`

- [ ] **Step 1: Update report terminology**

Report should show:

- `ck_vm_best_ns`
- `kotlin_jvm_best_ns`
- `python_best_ns`
- `rust_native_best_ns`

Do not add v1/v2 columns.

- [ ] **Step 2: Run benchmark smoke**

Run:

```bash
./gradlew -Dckl.benchmark.iterations=1000 -Dckl.benchmark.warmup.iterations=100 -Dckl.benchmark.samples=2 profileComputeVmBenchmark
```

Expected: PASS. Checksums match Kotlin/Python/Rust.

- [ ] **Step 3: Run default benchmark**

Run:

```bash
./gradlew profileComputeVmBenchmark
```

Expected: PASS and report contains only one CK VM implementation.

- [ ] **Step 4: Commit**

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/ComputeVmBenchmarkReport.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/ComputeVmBenchmarkRunners.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/ComputeVmBenchmarkReportTest.kt
git commit -m "Restore compute benchmark on register VM"
```

---

## Task 8: Hostcalls, Yield, Sleep, And Daemon Integration

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbi.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
- Modify: `native/ckl-vm/src/image.rs`
- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `native/ckl-vm/src/device_daemon.rs`

- [ ] **Step 1: Add failing tests for yield/sleep/hostcall**

Use existing `NativeImageVmRunnerJniTest` behavior tests:

- `yield();`
- `sleep(3);`
- `system::log("hi");`

They should run on the register VM, not a stack fallback.

- [ ] **Step 2: Add register opcodes**

Add:

- `CallHost return_dst, import_id, arg_registers`
- `Yield`
- `Sleep ticks_reg`

- [ ] **Step 3: Implement signal behavior**

The register runner emits the same existing `VmSignal` variants.

- [ ] **Step 4: Run daemon/native tests**

Run:

```bash
./gradlew :v1_21_1-neoforge:buildRustVmNativeLibrary :compiler:test --tests '*NativeImageVmBindingsJniTest' --tests '*NativeImageVmRunnerJniTest' -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image native/ckl-vm/src/image.rs native/ckl-vm/src/image_runner.rs native/ckl-vm/src/device_daemon.rs
git commit -m "Add register VM host signals"
```

---

## Task 9: Strings, Records, Collections, And ROM

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbi.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
- Modify: `native/ckl-vm/src/image.rs`
- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/CkVmImageBundledResourceCompileTest.kt`

- [ ] **Step 1: Run bundled ROM compile test**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests '*CkVmImageBundledResourceCompileTest'
```

Expected: FAIL on unsupported register instructions for strings/records/collections.

- [ ] **Step 2: Add only required typed/object instructions**

Add heap-backed instructions required by ROM:

- string constants and concat;
- record construction/get field;
- list/map/array operations currently used by ROM;
- string intrinsics currently implemented natively.

- [ ] **Step 3: Run ROM compile test again**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests '*CkVmImageBundledResourceCompileTest'
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image native/ckl-vm/src/image.rs native/ckl-vm/src/image_runner.rs modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/CkVmImageBundledResourceCompileTest.kt
git commit -m "Add register VM heap-backed language support"
```

---

## Task 10: Delete Stack VM Remnants

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `native/ckl-vm/src/image.rs`
- Search all project files.

- [ ] **Step 1: Search for stack VM remnants**

Run:

```bash
rg -n "PUSH_|POP|LOAD_LOCAL|STORE_LOCAL|OP_BINARY|frameSize|code: List|stack: Vec|locals: Vec|CallFrame" modules native
```

Expected: only historical docs/plans or irrelevant test text remain. Runtime/source hits must be removed or renamed to register equivalents.

- [ ] **Step 2: Delete old opcode constants and stack helpers**

Remove stack-only constants and helper methods from Kotlin and Rust runtime source.

- [ ] **Step 3: Run full verification**

Run:

```bash
./gradlew :compiler:test
./gradlew :v1_21_1-neoforge:test
./gradlew profileComputeVmBenchmark
cargo test
```

Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add modules native docs
git commit -m "Remove stack VM execution remnants"
```

---

## Final Verification

- [ ] **Step 1: Build production jar**

Run:

```bash
./gradlew :v1_21_1-neoforge:buildProductionUniversalJar
```

Expected: PASS.

- [ ] **Step 2: Run native display enabled core tests**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true :core:test :compiler:test :v1_21_1-neoforge:test
```

Expected: PASS.

- [ ] **Step 3: Confirm no fallback language remains**

Run:

```bash
rg -n "fallback|Fallback|stack VM|stack image|CKIM.*1|VERSION: Int = 1" modules native
```

Expected: no active runtime fallback paths. Any docs hits must clearly describe removed legacy behavior.

---

## Self-Review Notes

- This is a hard rewrite plan, not a side-by-side VM v2 plan.
- Runtime entry points must not route unsupported work to the old stack runner.
- Temporary failing tests are expected during intermediate tasks, but every commit should leave its scoped test target passing.
- The user explicitly prefers no fallbacks and no extra worktree for this phase.
