# Typed Register Bank VM Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current dynamic `Vec<VmValue>` image runner with a typed register-bank VM and defer linear RAM to a later feature.

**Architecture:** The compiler emits `CKIM` version `3`, where each function declares per-bank register counts and every operand is bank-local. Rust decodes the image into typed instructions and executes scalar hot paths directly against `Vec<i32>`, `Vec<i64>`, and `Vec<bool>`, while heap/reference values and `VmValue` remain boundary-only.

**Tech Stack:** Kotlin/JVM compiler module, Rust `native/ckl-vm`, JNI bindings, Gradle profiling tasks, Kotlin/JUnit tests, Rust unit tests.

---

## Scope Rules

- Do not add linear RAM in this plan.
- Do not keep runtime fallbacks to old image versions.
- Keep hostcall/signal/JNI protocol stable.
- Use `VmValue` only at hostcall, halt, resume, snapshot, and diagnostic boundaries.
- Commit after each task that leaves the repository compiling and tests passing.

---

## Target File Structure

- Modify `docs/superpowers/specs/2026-05-11-typed-register-vm-v2-design.md`
  - Keep the architecture aligned with typed register banks and deferred linear RAM.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
  - Add typed register references, per-bank function metadata, and bank-local instructions.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbi.kt`
  - Encode `CKIM` version `3`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
  - Allocate CKL locals and temporaries into typed banks.
- Modify `native/ckl-vm/src/image.rs`
  - Decode version `3` function metadata and typed operands.
- Modify `native/ckl-vm/src/image_runner.rs`
  - Replace `registers: Vec<VmValue>` with typed register banks.
- Keep `native/ckl-vm/src/value.rs`
  - Boundary value type only.
- Update tests under `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/`
  - Validate model, ABI, and lowering.
- Update benchmark tests under `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/`
  - Keep checksum parity and metrics reporting.

---

## Task 1: Kotlin Image Model Uses Typed Register Banks

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`

- [ ] **Step 1: Write the failing model expectation**

Update `compileImageLowersIntegerArithmeticIntoTypedRegisters` to assert that functions expose bank counts:

```kotlin
assertTrue(mainFunction.i32RegisterCount > 0)
assertTrue(mainFunction.boolRegisterCount >= 0)
assertTrue(mainFunction.refRegisterCount >= 0)
```

Expected: compile failure because `CkVmFunction` still has `registerCount`.

- [ ] **Step 2: Run the focused failing test**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest.compileImageLowersIntegerArithmeticIntoTypedRegisters'
```

Expected: FAIL during test compilation with unresolved `i32RegisterCount`.

- [ ] **Step 3: Add typed register model**

Replace `CkVmFunction.registerCount` with:

```kotlin
data class CkVmFunction(
    val name: String,
    val i32RegisterCount: Int,
    val i64RegisterCount: Int,
    val boolRegisterCount: Int,
    val refRegisterCount: Int,
    val parameters: List<CkVmTypedRegister>,
    val instructions: List<CkVmInstruction>,
)

sealed interface CkVmTypedRegister {
    val index: Int

    data class I32(override val index: Int) : CkVmTypedRegister
    data class I64(override val index: Int) : CkVmTypedRegister
    data class Bool(override val index: Int) : CkVmTypedRegister
    data class Ref(override val index: Int) : CkVmTypedRegister
}
```

Update instruction operands so integer operations use `Int` indices into the i32 bank and comparisons write bool-bank registers:

```kotlin
data class I32Add(val dst: Int, val lhs: Int, val rhs: Int) : CkVmInstruction
data class I32Eq(val dst: Int, val lhs: Int, val rhs: Int) : CkVmInstruction
data class JumpIfFalse(val cond: Int, val target: Int) : CkVmInstruction
data class CallStatic(
    val returnRegister: CkVmTypedRegister?,
    val functionIndex: Int,
    val arguments: List<CkVmTypedRegister>,
) : CkVmInstruction
data class Return(val src: CkVmTypedRegister) : CkVmInstruction
```

- [ ] **Step 4: Run compiler compile**

Run:

```bash
./gradlew :compiler:compileKotlin
```

Expected: FAIL in ABI/backend code that still references `registerCount` and untyped call/return operands.

Do not commit yet.

---

## Task 2: CKIM Version 3 ABI Encodes Register Banks

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbi.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbiTest.kt`

- [ ] **Step 1: Write ABI version test**

Add or update an ABI test to construct:

```kotlin
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
                    i32RegisterCount = 1,
                    i64RegisterCount = 0,
                    boolRegisterCount = 0,
                    refRegisterCount = 0,
                    parameters = emptyList(),
                    instructions =
                        listOf(
                            CkVmInstruction.I32Const(dst = 0, constantIndex = 0),
                            CkVmInstruction.Return(CkVmTypedRegister.I32(0)),
                        ),
                ),
            ),
    )
```

Assert byte `4` after magic is `3`.

- [ ] **Step 2: Run ABI test and verify failure**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageAbiTest*'
```

Expected: FAIL because encoder still emits version `2` and old function layout.

- [ ] **Step 3: Implement version 3 function layout**

Encode each function as:

```text
name: string
i32_register_count: i32
i64_register_count: i32
bool_register_count: i32
ref_register_count: i32
parameters: list<TypedRegister>
instructions: list<Instruction>
```

Encode `TypedRegister` as:

```text
tag 1 = I32
tag 2 = I64
tag 3 = Bool
tag 4 = Ref
index: i32
```

- [ ] **Step 4: Run ABI tests**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageAbiTest*'
```

Expected: PASS.

Do not commit until Task 3 also compiles.

---

## Task 3: Kotlin Lowerer Allocates Bank-Local Registers

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`

- [ ] **Step 1: Write compute lowering test**

Add a test:

```kotlin
@Test
fun compileImageAllocatesIntegerLocalsInI32Bank() {
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

    val main = image.functions.single()

    assertEquals(3, main.i32RegisterCount)
    assertEquals(0, main.boolRegisterCount)
    assertEquals(0, main.refRegisterCount)
    assertEquals(
        listOf(
            CkVmInstruction.I32Const(dst = 0, constantIndex = 0),
            CkVmInstruction.I32Const(dst = 1, constantIndex = 1),
            CkVmInstruction.I32Add(dst = 2, lhs = 0, rhs = 1),
            CkVmInstruction.Return(CkVmTypedRegister.I32(2)),
        ),
        main.instructions,
    )
}
```

- [ ] **Step 2: Run and verify failure**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest.compileImageAllocatesIntegerLocalsInI32Bank'
```

Expected: FAIL until lowerer maps Int locals and temporaries to the i32 bank.

- [ ] **Step 3: Implement a typed lowering stack**

Replace `ArrayDeque<Int>` with:

```kotlin
private val stack = ArrayDeque<CkVmTypedRegister>()
```

Track local slots with:

```kotlin
private val locals: List<CkVmTypedRegister> =
    function.locals.map { local ->
        when (local.typeName) {
            "Int" -> CkVmTypedRegister.I32(nextI32++)
            "Long" -> CkVmTypedRegister.I64(nextI64++)
            "Bool" -> CkVmTypedRegister.Bool(nextBool++)
            else -> CkVmTypedRegister.Ref(nextRef++)
        }
    }
```

Use `I32Const`, `BoolConst`, `RefConst`, `I32Move`, `BoolMove`, and `RefMove` based on the typed register being written.

- [ ] **Step 4: Run compiler tests**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest*' --tests '*CkVmImageAbiTest*'
```

Expected: PASS.

- [ ] **Step 5: Commit Kotlin model, ABI, and lowerer**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image
git commit -m "Emit typed register-bank VM images"
```

---

## Task 4: Rust Decoder Reads CKIM Version 3

**Files:**
- Modify: `native/ckl-vm/src/image.rs`

- [ ] **Step 1: Add Rust decode test**

Add a Rust unit test that decodes a Kotlin-generated version `3` fixture with one function, one `I32Const`, and one `Return(I32(0))`.

- [ ] **Step 2: Run and verify failure**

Run:

```bash
cargo test -p ckl-vm decode_typed_register_bank_image
```

Expected: FAIL because Rust decoder still expects version `2`.

- [ ] **Step 3: Implement typed image structs**

Replace `Function.register_count` and `parameter_count` with:

```rust
pub struct Function {
    pub name: String,
    pub i32_register_count: usize,
    pub i64_register_count: usize,
    pub bool_register_count: usize,
    pub ref_register_count: usize,
    pub parameters: Vec<TypedRegister>,
    pub instructions: Vec<Instruction>,
}

pub enum TypedRegister {
    I32(u16),
    I64(u16),
    Bool(u16),
    Ref(u16),
}
```

- [ ] **Step 4: Run Rust decoder tests**

Run:

```bash
cargo test -p ckl-vm image
```

Expected: PASS.

Do not commit until Task 5 also executes a basic program.

---

## Task 5: Rust Runner Uses Typed Register Banks For Compute

**Files:**
- Modify: `native/ckl-vm/src/image_runner.rs`

- [ ] **Step 1: Add runner test**

Add a Rust test that builds an in-memory `Image` with:

```text
i32 registers: 3
instructions:
  I32Const r0, 2
  I32Const r1, 5
  I32Add r2, r0, r1
  Return I32 r2
```

Assert `VmSignal::Halt(VmValue::Int(7))`.

- [ ] **Step 2: Run and verify failure**

Run:

```bash
cargo test -p ckl-vm image_runner::typed_i32_add_halts_with_int
```

Expected: FAIL or compile fail while runner still has `registers: Vec<VmValue>`.

- [ ] **Step 3: Replace runtime register storage**

Change `ImageVmHandle` fields from:

```rust
base_register: usize,
registers: Vec<VmValue>,
pending_resume_register: Option<u16>,
```

to:

```rust
i32_base: usize,
i64_base: usize,
bool_base: usize,
ref_base: usize,
i32_registers: Vec<i32>,
i64_registers: Vec<i64>,
bool_registers: Vec<bool>,
ref_registers: Vec<HeapRef>,
pending_resume_register: Option<TypedRegister>,
```

Use direct helpers:

```rust
fn i32_register(&self, register: u16) -> Result<i32, String>;
fn write_i32_register(&mut self, register: u16, value: i32) -> Result<(), String>;
fn bool_register(&self, register: u16) -> Result<bool, String>;
fn write_bool_register(&mut self, register: u16, value: bool) -> Result<(), String>;
```

- [ ] **Step 4: Implement compute opcodes directly**

Implement hot paths as primitive operations:

```rust
Instruction::I32Add { dst, lhs, rhs } => {
    let value = self.i32_register(lhs)?.wrapping_add(self.i32_register(rhs)?);
    self.write_i32_register(dst, value)?;
}
```

Implement `I32Sub`, `I32Mul`, `I32Div`, bitwise ops, shifts, comparisons, bool ops, jump ops, `Return`, and `ReturnUnit`.

- [ ] **Step 5: Run Rust tests**

Run:

```bash
cargo test -p ckl-vm image_runner
```

Expected: PASS.

- [ ] **Step 6: Run Gradle native image tests**

Run:

```bash
./gradlew :v1_21_1-neoforge:buildRustVmNativeLibrary :compiler:test
```

Expected: PASS.

- [ ] **Step 7: Commit Rust decoder and compute runner**

Run:

```bash
git add native/ckl-vm/src/image.rs native/ckl-vm/src/image_runner.rs
git commit -m "Execute compute images with typed register banks"
```

---

## Task 6: Hostcalls, Yield, Sleep, Records, And ROM Parity

**Files:**
- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
- Modify tests under `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/`
- Modify tests under `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/`

- [ ] **Step 1: Add boundary conversion helpers**

Implement Rust helpers:

```rust
fn typed_register_to_value(&self, register: TypedRegister) -> Result<VmValue, String>;
fn write_typed_register(&mut self, register: TypedRegister, value: VmValue) -> Result<(), String>;
```

- [ ] **Step 2: Use boundary helpers for hostcalls**

Marshal `CallHost.arguments` through `typed_register_to_value` and write handled or resumed results through `write_typed_register`.

- [ ] **Step 3: Implement reference-backed strings and records**

Keep string constants and records in the managed heap/ref bank. Do not add linear RAM.

- [ ] **Step 4: Run ROM/resource tests**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests '*CkVmImageBundledResourceCompileTest*'
```

Expected: PASS.

- [ ] **Step 5: Run full relevant suite**

Run:

```bash
./gradlew :compiler:test :v1_21_1-neoforge:test
```

Expected: PASS.

- [ ] **Step 6: Commit runtime boundary parity**

Run:

```bash
git add native/ckl-vm/src modules/compiler/src/main/kotlin modules/compiler/src/test/kotlin modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin
git commit -m "Complete typed register-bank runtime parity"
```

---

## Task 7: Benchmark And Remove Dynamic Register Runner Paths

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/ComputeVmBenchmarkReport.kt`
- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `native/ckl-vm/src/image.rs`

- [ ] **Step 1: Run benchmark**

Run:

```bash
./gradlew --rerun-tasks -Dckl.benchmark.iterations=500000 -Dckl.benchmark.warmup.iterations=50000 -Dckl.benchmark.samples=3 profileComputeVmBenchmark
```

Expected: PASS and checksum parity for all workloads.

- [ ] **Step 2: Confirm scalar hot path counters**

Inspect `modules/compiler/build/reports/profiling/compute-vm-benchmark.md`.

Expected:

- `value_clones` is zero or near-zero for compute workloads.
- `CK VM iter/s` improves over the pre-bank baseline for integer-heavy workloads.

- [ ] **Step 3: Delete version 2 dynamic-register support**

Ensure Rust decoder rejects `CKIM` version `2` with `unsupported image version 2`.

- [ ] **Step 4: Run production jar checks**

Run:

```bash
./gradlew :v1_21_1-neoforge:buildProductionUniversalJar
```

Expected: PASS.

- [ ] **Step 5: Commit cleanup and benchmark parity**

Run:

```bash
git add native/ckl-vm modules/compiler docs/superpowers
git commit -m "Remove dynamic register image runtime"
```

---

## Verification Checklist

- `./gradlew :compiler:test`
- `./gradlew :v1_21_1-neoforge:test`
- `./gradlew --rerun-tasks -Dckl.benchmark.iterations=500000 -Dckl.benchmark.warmup.iterations=50000 -Dckl.benchmark.samples=3 profileComputeVmBenchmark`
- `./gradlew :v1_21_1-neoforge:buildProductionUniversalJar`

Success means the active runtime uses typed register banks, linear RAM remains deferred, and old dynamic-register image execution is gone.
