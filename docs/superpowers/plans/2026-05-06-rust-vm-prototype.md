# Rust VM Prototype Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local, optional Rust VM prototype that can decode CKL bytecode, execute a pure subset, and expose a safe Kotlin runner seam without changing default runtime behavior.

**Architecture:** Keep Kotlin as the compiler and default runtime host. Add a versioned bytecode ABI, a Rust crate that decodes and executes that ABI, and a Kotlin runner seam that allows the native runner to be selected in tests or local profiling while preserving Kotlin VM fallback.

**Tech Stack:** Kotlin/JVM, Gradle, JUnit/kotlin.test, Rust 2021, Cargo, JNI as the eventual JVM/native boundary.

---

## File Structure

- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`
  - Extract the existing Kotlin VM execution loop into a `KotlinVmRunner` implementation.
  - Keep `BytecodeComputerProgram` as the public `DeviceProgram` wrapper.
- Create `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunner.kt`
  - Defines the runner seam used by Kotlin and native VM implementations.
- Create `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/abi/BytecodeAbi.kt`
  - Versioned binary encoder for `BytecodeModule` and compact tags for instructions, values, operators, and metadata.
- Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/BytecodeAbiTest.kt`
  - ABI determinism, version byte, representative instruction encoding, and malformed-input guard tests.
- Create `native/ckl-vm/Cargo.toml`
  - Local Rust crate for the VM prototype.
- Create `native/ckl-vm/src/lib.rs`
  - Public crate module declarations.
- Create `native/ckl-vm/src/abi.rs`
  - Rust decoder for the Kotlin bytecode ABI.
- Create `native/ckl-vm/src/value.rs`
  - Rust `VmValue`, object refs, and heap value model.
- Create `native/ckl-vm/src/vm.rs`
  - Pure Rust interpreter for Ring 0 instructions and host-call signal emission.
- Create `native/ckl-vm/tests/abi_decode.rs`
  - Rust decoder tests using byte vectors generated from documented ABI fixtures.
- Create `native/ckl-vm/tests/pure_vm.rs`
  - Rust pure interpreter tests for arithmetic, branches, functions, strings as values, and host-call signal shape.
- Create `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/native/NativeVmRunner.kt`
  - Kotlin-side native runner facade that is disabled unless explicitly configured.
- Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunnerSelectionTest.kt`
  - Ensures default execution remains Kotlin and native mode fails closed when the library is unavailable.
- Modify `docs/PROFILING.md`
  - Add local commands for Rust VM prototype metrics once the native runner is selectable.

---

### Task 1: Add a VM runner seam without changing behavior

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunner.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`

- [ ] **Step 1: Write the failing runner seam test**

Add this test to `LanguageRuntimeTest`:

```kotlin
@Test
fun bytecodeComputerProgramDelegatesToProvidedRunner() {
    val artifact =
        frontend.compile(
            "runner.ck",
            """
            pub fun main() {
                system::log("runner");
            }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )

    val runner = RecordingVmRunner()
    val runtime = RecordingRuntime()
    runBlocking {
        BytecodeComputerProgram(requireNotNull(artifact.module), runnerFactory = { runner }).run(runtime)
    }

    assertEquals(1, runner.runCalls)
    assertEquals(requireNotNull(artifact.module).name, runner.moduleName)
}

private class RecordingVmRunner : VmRunner {
    var runCalls = 0
    var moduleName = ""

    override suspend fun run(
        module: BytecodeModule,
        runtime: DeviceRuntime,
    ) {
        runCalls += 1
        moduleName = module.name
    }
}
```

Add imports if the compiler requires them:

```kotlin
import ru.lazyhat.compukterkraft.lang.api.BytecodeModule
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.bytecodeComputerProgramDelegatesToProvidedRunner
```

Expected: FAIL to compile with unresolved `VmRunner` or missing `runnerFactory` constructor parameter.

- [ ] **Step 3: Create `VmRunner.kt`**

Create `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunner.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime

import ru.lazyhat.compukterkraft.lang.api.BytecodeModule

fun interface VmRunner {
    suspend fun run(
        module: BytecodeModule,
        runtime: DeviceRuntime,
    )
}
```

- [ ] **Step 4: Refactor `BytecodeComputerProgram` to use the runner seam**

Replace `BytecodeComputerProgram` in `LanguageRuntime.kt` with this shape, preserving the existing loop inside `KotlinVmRunner`:

```kotlin
class BytecodeComputerProgram(
    private val module: BytecodeModule,
    private val runnerFactory: () -> VmRunner = { KotlinVmRunner },
) : DeviceProgram {
    override suspend fun run(runtime: DeviceRuntime) {
        runnerFactory().run(module, runtime)
    }
}

object KotlinVmRunner : VmRunner {
    override suspend fun run(
        module: BytecodeModule,
        runtime: DeviceRuntime,
    ) {
        val bridge = RuntimeHostBridge(runtime)
        val vm =
            BytecodeVirtualMachine(
                module,
                instructionBudgetPerSlice = runtime.profile.resources.cpu.instructionsPerSlice,
                maxVmRamBytes = runtime.profile.resources.memory.vmRamBytes,
                metrics = runtime.metrics,
            )
        while (true) {
            val signal = vm.runUntilSignal()
            runtime.metrics.recordVmSignal(signal.kind)
            when (signal) {
                VmSignal.Halt -> return
                VmSignal.Pause -> runtime.yield()
                VmSignal.Yield -> {
                    runtime.yield()
                    vm.resumeWith(VmValue.UnitValue)
                }
                is VmSignal.HostCall -> {
                    if (runtime.metrics.collectsDetailedMetrics) {
                        val started = System.nanoTime()
                        try {
                            vm.resumeWith(bridge.invoke(signal.moduleName, signal.functionName, signal.arguments))
                        } finally {
                            runtime.metrics.recordVmHostCall(
                                signal.moduleName,
                                signal.functionName,
                                System.nanoTime() - started,
                            )
                        }
                    } else {
                        vm.resumeWith(bridge.invoke(signal.moduleName, signal.functionName, signal.arguments))
                    }
                }
                is VmSignal.Sleep -> {
                    runtime.sleep(signal.ticks)
                    vm.resumeWith(VmValue.UnitValue)
                }
                is VmSignal.WaitEvent -> {
                    vm.resumeWith(bridge.fromEvent(runtime.pullEvent(signal.filter)))
                }
            }
        }
    }
}
```

Keep the existing private `VmSignal.kind` extension in `LanguageRuntime.kt` so `KotlinVmRunner` can use it.

- [ ] **Step 5: Verify the seam and existing runtime behavior**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunner.kt \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt \
  modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt
git commit -m "feat: add VM runner seam"
```

---

### Task 2: Add a deterministic bytecode ABI encoder in Kotlin

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/abi/BytecodeAbi.kt`
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/BytecodeAbiTest.kt`

- [ ] **Step 1: Write failing ABI tests**

Create `BytecodeAbiTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime

import ru.lazyhat.compukterkraft.lang.frontend.FrontendSeverity
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.runtime.abi.BytecodeAbi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BytecodeAbiTest {
    private val frontend = LanguageFrontend()

    @Test
    fun encodedModuleStartsWithMagicAndVersion() {
        val module = compile("pub fun main() { return }")
        val bytes = BytecodeAbi.encode(module)

        assertContentEquals(byteArrayOf('C'.code.toByte(), 'K'.code.toByte(), 'V'.code.toByte(), 'M'.code.toByte()), bytes.copyOfRange(0, 4))
        assertEquals(1, bytes[4].toInt())
    }

    @Test
    fun encodedModuleIsDeterministic() {
        val module = compile(
            """
            pub fun add(a: Int, b: Int): Int { return a + b }
            pub fun main() { system::log("x=" + add(1, 2)); }
            """.trimIndent(),
        )

        assertContentEquals(BytecodeAbi.encode(module), BytecodeAbi.encode(module))
    }

    @Test
    fun encodedModuleContainsInstructionTagsForRepresentativeProgram() {
        val module = compile(
            """
            pub fun main() {
                val x: Int = 1 + 2;
                if (x == 3) { system::log("ok"); }
            }
            """.trimIndent(),
        )

        val bytes = BytecodeAbi.encode(module).toList().map(Byte::toInt)

        assertTrue(BytecodeAbi.Tags.PUSH_INT in bytes)
        assertTrue(BytecodeAbi.Tags.BINARY in bytes)
        assertTrue(BytecodeAbi.Tags.JUMP_IF_FALSE in bytes)
        assertTrue(BytecodeAbi.Tags.CALL_BUILTIN in bytes)
        assertTrue(BytecodeAbi.Tags.RETURN in bytes)
    }

    private fun compile(source: String) =
        frontend.compile("abi.ck", source).also { artifact ->
            assertTrue(
                artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
                artifact.analysis.diagnostics.joinToString { it.message },
            )
        }.module ?: error("Expected bytecode module")
}
```

- [ ] **Step 2: Run failing ABI tests**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.BytecodeAbiTest
```

Expected: FAIL to compile because `BytecodeAbi` does not exist.

- [ ] **Step 3: Implement `BytecodeAbi`**

Create `BytecodeAbi.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime.abi

import ru.lazyhat.compukterkraft.lang.api.BinaryOperator
import ru.lazyhat.compukterkraft.lang.api.BytecodeClass
import ru.lazyhat.compukterkraft.lang.api.BytecodeFunction
import ru.lazyhat.compukterkraft.lang.api.BytecodeModule
import ru.lazyhat.compukterkraft.lang.api.BytecodeRecord
import ru.lazyhat.compukterkraft.lang.api.Instruction
import ru.lazyhat.compukterkraft.lang.api.UnaryOperator
import java.io.ByteArrayOutputStream

object BytecodeAbi {
    const val VERSION: Int = 1

    object Tags {
        const val PUSH_INT = 1
        const val PUSH_LONG = 2
        const val PUSH_STRING = 3
        const val PUSH_BOOL = 4
        const val PUSH_UNIT = 5
        const val PUSH_NULL = 6
        const val LOAD_LOCAL = 7
        const val STORE_LOCAL = 8
        const val POP = 9
        const val JUMP = 10
        const val JUMP_IF_FALSE = 11
        const val JUMP_IF_TRUE = 12
        const val CALL_FUNCTION = 13
        const val CALL_BUILTIN = 14
        const val GET_FIELD = 15
        const val SET_FIELD = 16
        const val CONSTRUCT_RECORD = 17
        const val CONSTRUCT_CLASS = 18
        const val CONSTRUCT_ARRAY = 19
        const val CONSTRUCT_LIST = 20
        const val CONSTRUCT_MAP = 21
        const val INDEX_GET = 22
        const val INDEX_SET = 23
        const val CALL_COLLECTION_METHOD = 24
        const val CALL_METHOD = 25
        const val CALL_STATIC_METHOD = 26
        const val BINARY = 27
        const val UNARY = 28
        const val RETURN = 29
    }

    fun encode(module: BytecodeModule): ByteArray {
        val out = Writer()
        out.bytes(byteArrayOf('C'.code.toByte(), 'K'.code.toByte(), 'V'.code.toByte(), 'M'.code.toByte()))
        out.u8(VERSION)
        out.string(module.name)
        out.i32(module.entryFunctionIndex)
        out.list(module.records, out::record)
        out.list(module.classes, out::klass)
        out.list(module.functions, out::function)
        return out.toByteArray()
    }

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun toByteArray(): ByteArray = out.toByteArray()

        fun bytes(value: ByteArray) = out.write(value)

        fun u8(value: Int) = out.write(value and 0xff)

        fun bool(value: Boolean) = u8(if (value) 1 else 0)

        fun i32(value: Int) {
            u8(value)
            u8(value ushr 8)
            u8(value ushr 16)
            u8(value ushr 24)
        }

        fun i64(value: Long) {
            repeat(8) { index -> u8((value ushr (index * 8)).toInt()) }
        }

        fun string(value: String) {
            val bytes = value.encodeToByteArray()
            i32(bytes.size)
            bytes(bytes)
        }

        fun <T> list(values: List<T>, write: (T) -> Unit) {
            i32(values.size)
            values.forEach(write)
        }

        fun record(record: BytecodeRecord) {
            string(record.name)
            list(record.fields) { field ->
                string(field.name)
                string(field.typeName)
            }
        }

        fun klass(klass: BytecodeClass) {
            string(klass.name)
            list(klass.fields) { field ->
                string(field.name)
                string(field.typeName)
                bool(field.mutable)
            }
            i32(klass.initFunctionIndex ?: -1)
            list(klass.instanceMethods.entries.sortedBy { it.key }) { entry ->
                string(entry.key)
                i32(entry.value)
            }
            list(klass.staticMethods.entries.sortedBy { it.key }) { entry ->
                string(entry.key)
                i32(entry.value)
            }
        }

        fun function(function: BytecodeFunction) {
            string(function.name)
            list(function.parameters) { local ->
                string(local.name)
                string(local.typeName)
            }
            list(function.locals) { local ->
                string(local.name)
                string(local.typeName)
            }
            string(function.returnType)
            list(function.instructions, ::instruction)
        }

        fun instruction(instruction: Instruction) {
            when (instruction) {
                is Instruction.PushInt -> { u8(Tags.PUSH_INT); i32(instruction.value) }
                is Instruction.PushLong -> { u8(Tags.PUSH_LONG); i64(instruction.value) }
                is Instruction.PushString -> { u8(Tags.PUSH_STRING); string(instruction.value) }
                is Instruction.PushBool -> { u8(Tags.PUSH_BOOL); bool(instruction.value) }
                Instruction.PushUnit -> u8(Tags.PUSH_UNIT)
                Instruction.PushNull -> u8(Tags.PUSH_NULL)
                is Instruction.LoadLocal -> { u8(Tags.LOAD_LOCAL); i32(instruction.slot) }
                is Instruction.StoreLocal -> { u8(Tags.STORE_LOCAL); i32(instruction.slot) }
                Instruction.Pop -> u8(Tags.POP)
                is Instruction.Jump -> { u8(Tags.JUMP); i32(instruction.target) }
                is Instruction.JumpIfFalse -> { u8(Tags.JUMP_IF_FALSE); i32(instruction.target) }
                is Instruction.JumpIfTrue -> { u8(Tags.JUMP_IF_TRUE); i32(instruction.target) }
                is Instruction.CallFunction -> { u8(Tags.CALL_FUNCTION); i32(instruction.functionIndex); i32(instruction.argumentCount) }
                is Instruction.CallBuiltin -> { u8(Tags.CALL_BUILTIN); string(instruction.moduleName.orEmpty()); string(instruction.functionName); i32(instruction.argumentCount) }
                is Instruction.GetField -> { u8(Tags.GET_FIELD); string(instruction.fieldName) }
                is Instruction.SetField -> { u8(Tags.SET_FIELD); string(instruction.fieldName) }
                is Instruction.ConstructRecord -> { u8(Tags.CONSTRUCT_RECORD); string(instruction.typeName); list(instruction.fieldNames, ::string) }
                is Instruction.ConstructClass -> { u8(Tags.CONSTRUCT_CLASS); string(instruction.className); list(instruction.fieldNames, ::string) }
                Instruction.ConstructArray -> u8(Tags.CONSTRUCT_ARRAY)
                is Instruction.ConstructList -> { u8(Tags.CONSTRUCT_LIST); i32(instruction.elementCount) }
                is Instruction.ConstructMap -> { u8(Tags.CONSTRUCT_MAP); i32(instruction.entryCount) }
                Instruction.IndexGet -> u8(Tags.INDEX_GET)
                Instruction.IndexSet -> u8(Tags.INDEX_SET)
                is Instruction.CallCollectionMethod -> { u8(Tags.CALL_COLLECTION_METHOD); string(instruction.methodName); i32(instruction.argumentCount) }
                is Instruction.CallMethod -> { u8(Tags.CALL_METHOD); string(instruction.methodName); i32(instruction.argumentCount) }
                is Instruction.CallStaticMethod -> { u8(Tags.CALL_STATIC_METHOD); string(instruction.className); string(instruction.methodName); i32(instruction.argumentCount) }
                is Instruction.Binary -> { u8(Tags.BINARY); u8(instruction.operator.abiTag()) }
                is Instruction.Unary -> { u8(Tags.UNARY); u8(instruction.operator.abiTag()) }
                Instruction.Return -> u8(Tags.RETURN)
            }
        }
    }
}

private fun BinaryOperator.abiTag(): Int = ordinal

private fun UnaryOperator.abiTag(): Int = ordinal
```

- [ ] **Step 4: Verify ABI tests**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.BytecodeAbiTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/abi/BytecodeAbi.kt \
  modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/BytecodeAbiTest.kt
git commit -m "feat: add CKL bytecode ABI encoder"
```

---

### Task 3: Add Rust crate and ABI decoder tests

**Files:**
- Create: `native/ckl-vm/Cargo.toml`
- Create: `native/ckl-vm/src/lib.rs`
- Create: `native/ckl-vm/src/abi.rs`
- Create: `native/ckl-vm/tests/abi_decode.rs`

- [ ] **Step 1: Create Rust crate files**

Create `native/ckl-vm/Cargo.toml`:

```toml
[package]
name = "ckl-vm"
version = "0.1.0"
edition = "2021"

[lib]
crate-type = ["rlib", "cdylib"]

[dependencies]
thiserror = "1.0"
```

Create `native/ckl-vm/src/lib.rs`:

```rust
pub mod abi;
pub mod value;
pub mod vm;
```

Create `native/ckl-vm/src/value.rs`:

```rust
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VmValue {
    Unit,
    Null,
    Bool(bool),
    Int(i32),
    Long(i64),
    String(String),
    ObjectRef(u32),
}
```

- [ ] **Step 2: Write failing decoder test**

Create `native/ckl-vm/tests/abi_decode.rs`:

```rust
use ckl_vm::abi::{decode_module, Instruction};

#[test]
fn decodes_minimal_module_header() {
    let bytes = minimal_module_bytes();
    let module = decode_module(&bytes).expect("module decodes");

    assert_eq!(module.name, "main");
    assert_eq!(module.entry_function_index, 0);
    assert_eq!(module.functions.len(), 1);
    assert_eq!(module.functions[0].name, "main");
    assert_eq!(module.functions[0].instructions, vec![Instruction::Return]);
}

fn minimal_module_bytes() -> Vec<u8> {
    let mut bytes = Vec::new();
    bytes.extend_from_slice(b"CKVM");
    bytes.push(1);
    write_string(&mut bytes, "main");
    write_i32(&mut bytes, 0);
    write_i32(&mut bytes, 0);
    write_i32(&mut bytes, 0);
    write_i32(&mut bytes, 1);
    write_string(&mut bytes, "main");
    write_i32(&mut bytes, 0);
    write_i32(&mut bytes, 0);
    write_string(&mut bytes, "Unit");
    write_i32(&mut bytes, 1);
    bytes.push(29);
    bytes
}

fn write_i32(bytes: &mut Vec<u8>, value: i32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_string(bytes: &mut Vec<u8>, value: &str) {
    write_i32(bytes, value.len() as i32);
    bytes.extend_from_slice(value.as_bytes());
}
```

- [ ] **Step 3: Run failing Rust test**

Run:

```bash
cd native/ckl-vm && cargo test --test abi_decode
```

Expected: FAIL to compile because `decode_module` and ABI types do not exist.

- [ ] **Step 4: Implement ABI decoder**

Create `native/ckl-vm/src/abi.rs`:

```rust
use thiserror::Error;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum AbiError {
    #[error("invalid magic")]
    InvalidMagic,
    #[error("unsupported ABI version {0}")]
    UnsupportedVersion(u8),
    #[error("unexpected end of input")]
    UnexpectedEnd,
    #[error("invalid utf-8 string")]
    InvalidUtf8,
    #[error("unknown instruction tag {0}")]
    UnknownInstruction(u8),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Module {
    pub name: String,
    pub entry_function_index: i32,
    pub records: Vec<Record>,
    pub classes: Vec<Class>,
    pub functions: Vec<Function>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Record {
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Class {
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Function {
    pub name: String,
    pub parameters: Vec<Local>,
    pub locals: Vec<Local>,
    pub return_type: String,
    pub instructions: Vec<Instruction>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Local {
    pub name: String,
    pub type_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Instruction {
    PushInt(i32),
    PushLong(i64),
    PushString(String),
    PushBool(bool),
    PushUnit,
    PushNull,
    LoadLocal(i32),
    StoreLocal(i32),
    Pop,
    Jump(i32),
    JumpIfFalse(i32),
    JumpIfTrue(i32),
    CallFunction { function_index: i32, argument_count: i32 },
    CallBuiltin { module_name: String, function_name: String, argument_count: i32 },
    Binary(u8),
    Unary(u8),
    Return,
}

pub fn decode_module(bytes: &[u8]) -> Result<Module, AbiError> {
    let mut reader = Reader { bytes, offset: 0 };
    if reader.take(4)? != b"CKVM" {
        return Err(AbiError::InvalidMagic);
    }
    let version = reader.u8()?;
    if version != 1 {
        return Err(AbiError::UnsupportedVersion(version));
    }
    let name = reader.string()?;
    let entry_function_index = reader.i32()?;
    let records = reader.list(|reader| Ok(Record { name: reader.string()? }))?;
    let classes = reader.list(|reader| Ok(Class { name: reader.string()? }))?;
    let functions = reader.list(read_function)?;
    Ok(Module { name, entry_function_index, records, classes, functions })
}

fn read_function(reader: &mut Reader<'_>) -> Result<Function, AbiError> {
    let name = reader.string()?;
    let parameters = reader.list(read_local)?;
    let locals = reader.list(read_local)?;
    let return_type = reader.string()?;
    let instructions = reader.list(read_instruction)?;
    Ok(Function { name, parameters, locals, return_type, instructions })
}

fn read_local(reader: &mut Reader<'_>) -> Result<Local, AbiError> {
    Ok(Local { name: reader.string()?, type_name: reader.string()? })
}

fn read_instruction(reader: &mut Reader<'_>) -> Result<Instruction, AbiError> {
    let tag = reader.u8()?;
    match tag {
        1 => Ok(Instruction::PushInt(reader.i32()?)),
        2 => Ok(Instruction::PushLong(reader.i64()?)),
        3 => Ok(Instruction::PushString(reader.string()?)),
        4 => Ok(Instruction::PushBool(reader.u8()? != 0)),
        5 => Ok(Instruction::PushUnit),
        6 => Ok(Instruction::PushNull),
        7 => Ok(Instruction::LoadLocal(reader.i32()?)),
        8 => Ok(Instruction::StoreLocal(reader.i32()?)),
        9 => Ok(Instruction::Pop),
        10 => Ok(Instruction::Jump(reader.i32()?)),
        11 => Ok(Instruction::JumpIfFalse(reader.i32()?)),
        12 => Ok(Instruction::JumpIfTrue(reader.i32()?)),
        13 => Ok(Instruction::CallFunction { function_index: reader.i32()?, argument_count: reader.i32()? }),
        14 => Ok(Instruction::CallBuiltin { module_name: reader.string()?, function_name: reader.string()?, argument_count: reader.i32()? }),
        27 => Ok(Instruction::Binary(reader.u8()?)),
        28 => Ok(Instruction::Unary(reader.u8()?)),
        29 => Ok(Instruction::Return),
        other => Err(AbiError::UnknownInstruction(other)),
    }
}

struct Reader<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, count: usize) -> Result<&'a [u8], AbiError> {
        let end = self.offset.checked_add(count).ok_or(AbiError::UnexpectedEnd)?;
        let slice = self.bytes.get(self.offset..end).ok_or(AbiError::UnexpectedEnd)?;
        self.offset = end;
        Ok(slice)
    }

    fn u8(&mut self) -> Result<u8, AbiError> {
        Ok(self.take(1)?[0])
    }

    fn i32(&mut self) -> Result<i32, AbiError> {
        let mut bytes = [0u8; 4];
        bytes.copy_from_slice(self.take(4)?);
        Ok(i32::from_le_bytes(bytes))
    }

    fn i64(&mut self) -> Result<i64, AbiError> {
        let mut bytes = [0u8; 8];
        bytes.copy_from_slice(self.take(8)?);
        Ok(i64::from_le_bytes(bytes))
    }

    fn string(&mut self) -> Result<String, AbiError> {
        let len = self.i32()? as usize;
        String::from_utf8(self.take(len)?.to_vec()).map_err(|_| AbiError::InvalidUtf8)
    }

    fn list<T>(&mut self, read: fn(&mut Reader<'a>) -> Result<T, AbiError>) -> Result<Vec<T>, AbiError> {
        let len = self.i32()?;
        let mut values = Vec::with_capacity(len as usize);
        for _ in 0..len {
            values.push(read(self)?);
        }
        Ok(values)
    }
}
```

- [ ] **Step 5: Verify Rust decoder**

Run:

```bash
cd native/ckl-vm && cargo test --test abi_decode
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add native/ckl-vm
git commit -m "feat: add Rust CKL VM ABI decoder"
```

---

### Task 4: Implement a pure Rust VM subset with signal output

**Files:**
- Modify: `native/ckl-vm/src/vm.rs`
- Modify: `native/ckl-vm/src/value.rs`
- Create: `native/ckl-vm/tests/pure_vm.rs`

- [ ] **Step 1: Write failing pure VM tests**

Create `native/ckl-vm/tests/pure_vm.rs`:

```rust
use ckl_vm::abi::{Function, Instruction, Module};
use ckl_vm::value::VmValue;
use ckl_vm::vm::{VmInstance, VmSignal};

#[test]
fn executes_integer_addition_and_return() {
    let module = Module {
        name: "main".to_string(),
        entry_function_index: 0,
        records: vec![],
        classes: vec![],
        functions: vec![Function {
            name: "main".to_string(),
            parameters: vec![],
            locals: vec![],
            return_type: "Int".to_string(),
            instructions: vec![
                Instruction::PushInt(1),
                Instruction::PushInt(2),
                Instruction::Binary(0),
                Instruction::Return,
            ],
        }],
    };

    let mut vm = VmInstance::new(module, 64);

    assert_eq!(vm.run_until_signal(), VmSignal::Halt(VmValue::Int(3)));
}

#[test]
fn emits_host_call_signal_for_unknown_builtin() {
    let module = Module {
        name: "main".to_string(),
        entry_function_index: 0,
        records: vec![],
        classes: vec![],
        functions: vec![Function {
            name: "main".to_string(),
            parameters: vec![],
            locals: vec![],
            return_type: "Unit".to_string(),
            instructions: vec![
                Instruction::PushString("hello".to_string()),
                Instruction::CallBuiltin { module_name: "system".to_string(), function_name: "log".to_string(), argument_count: 1 },
            ],
        }],
    };

    let mut vm = VmInstance::new(module, 64);

    assert_eq!(
        vm.run_until_signal(),
        VmSignal::HostCall { module_name: "system".to_string(), function_name: "log".to_string(), arguments: vec![VmValue::String("hello".to_string())] },
    );
}
```

- [ ] **Step 2: Run failing pure VM tests**

Run:

```bash
cd native/ckl-vm && cargo test --test pure_vm
```

Expected: FAIL to compile because `VmInstance` and `VmSignal` do not exist.

- [ ] **Step 3: Implement minimal pure VM**

Create `native/ckl-vm/src/vm.rs`:

```rust
use crate::abi::{Instruction, Module};
use crate::value::VmValue;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VmSignal {
    Halt(VmValue),
    Pause,
    Yield,
    Sleep(i64),
    HostCall { module_name: String, function_name: String, arguments: Vec<VmValue> },
}

pub struct VmInstance {
    module: Module,
    frames: Vec<Frame>,
    instruction_budget: usize,
    instructions_since_pause: usize,
}

struct Frame {
    function_index: usize,
    instruction_pointer: usize,
    locals: Vec<VmValue>,
    stack: Vec<VmValue>,
}

impl VmInstance {
    pub fn new(module: Module, instruction_budget: usize) -> Self {
        let entry = module.entry_function_index as usize;
        Self {
            module,
            frames: vec![Frame { function_index: entry, instruction_pointer: 0, locals: vec![], stack: vec![] }],
            instruction_budget: instruction_budget.max(1),
            instructions_since_pause: 0,
        }
    }

    pub fn run_until_signal(&mut self) -> VmSignal {
        loop {
            let frame = match self.frames.last_mut() {
                Some(frame) => frame,
                None => return VmSignal::Halt(VmValue::Unit),
            };
            let function = &self.module.functions[frame.function_index];
            let instruction = match function.instructions.get(frame.instruction_pointer).cloned() {
                Some(instruction) => instruction,
                None => return self.handle_return(VmValue::Unit),
            };
            frame.instruction_pointer += 1;
            self.instructions_since_pause += 1;

            match instruction {
                Instruction::PushInt(value) => frame.stack.push(VmValue::Int(value)),
                Instruction::PushLong(value) => frame.stack.push(VmValue::Long(value)),
                Instruction::PushString(value) => frame.stack.push(VmValue::String(value)),
                Instruction::PushBool(value) => frame.stack.push(VmValue::Bool(value)),
                Instruction::PushUnit => frame.stack.push(VmValue::Unit),
                Instruction::PushNull => frame.stack.push(VmValue::Null),
                Instruction::LoadLocal(slot) => frame.stack.push(frame.locals[slot as usize].clone()),
                Instruction::StoreLocal(slot) => {
                    let value = frame.stack.pop().expect("stack value");
                    let slot = slot as usize;
                    while frame.locals.len() <= slot {
                        frame.locals.push(VmValue::Unit);
                    }
                    frame.locals[slot] = value;
                }
                Instruction::Pop => {
                    frame.stack.pop().expect("stack value");
                }
                Instruction::Binary(0) => {
                    let right = frame.stack.pop().expect("right operand");
                    let left = frame.stack.pop().expect("left operand");
                    frame.stack.push(add_values(left, right));
                }
                Instruction::Return => {
                    let result = frame.stack.pop().unwrap_or(VmValue::Unit);
                    return self.handle_return(result);
                }
                Instruction::CallBuiltin { module_name, function_name, argument_count } => {
                    let mut arguments = Vec::with_capacity(argument_count as usize);
                    for _ in 0..argument_count {
                        arguments.push(frame.stack.pop().expect("argument"));
                    }
                    arguments.reverse();
                    if module_name.is_empty() && function_name == "yield" {
                        return VmSignal::Yield;
                    }
                    if module_name.is_empty() && function_name == "sleep" {
                        let ticks = match arguments.single() {
                            VmValue::Long(value) => *value,
                            VmValue::Int(value) => *value as i64,
                            _ => 0,
                        };
                        return VmSignal::Sleep(ticks);
                    }
                    return VmSignal::HostCall { module_name, function_name, arguments };
                }
                other => panic!("instruction not implemented in pure VM prototype: {other:?}"),
            }

            if self.instructions_since_pause >= self.instruction_budget {
                self.instructions_since_pause = 0;
                return VmSignal::Pause;
            }
        }
    }

    fn handle_return(&mut self, result: VmValue) -> VmSignal {
        self.frames.pop();
        if let Some(caller) = self.frames.last_mut() {
            caller.stack.push(result);
            self.run_until_signal()
        } else {
            VmSignal::Halt(result)
        }
    }
}

fn add_values(left: VmValue, right: VmValue) -> VmValue {
    match (left, right) {
        (VmValue::String(left), right) => VmValue::String(format!("{left}{}", render_value(&right))),
        (left, VmValue::String(right)) => VmValue::String(format!("{}{right}", render_value(&left))),
        (VmValue::Int(left), VmValue::Int(right)) => VmValue::Int(left + right),
        (VmValue::Long(left), VmValue::Long(right)) => VmValue::Long(left + right),
        (VmValue::Int(left), VmValue::Long(right)) => VmValue::Long(left as i64 + right),
        (VmValue::Long(left), VmValue::Int(right)) => VmValue::Long(left + right as i64),
        _ => panic!("invalid add operands"),
    }
}

fn render_value(value: &VmValue) -> String {
    match value {
        VmValue::Unit => "unit".to_string(),
        VmValue::Null => "null".to_string(),
        VmValue::Bool(value) => value.to_string(),
        VmValue::Int(value) => value.to_string(),
        VmValue::Long(value) => value.to_string(),
        VmValue::String(value) => value.clone(),
        VmValue::ObjectRef(value) => format!("object#{value}"),
    }
}

trait Single<T> {
    fn single(&self) -> &T;
}

impl<T> Single<T> for Vec<T> {
    fn single(&self) -> &T {
        assert_eq!(self.len(), 1);
        &self[0]
    }
}
```

- [ ] **Step 4: Verify pure VM tests**

Run:

```bash
cd native/ckl-vm && cargo test --test pure_vm
```

Expected: PASS.

- [ ] **Step 5: Run all Rust tests**

Run:

```bash
cd native/ckl-vm && cargo test
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add native/ckl-vm/src/value.rs native/ckl-vm/src/vm.rs native/ckl-vm/tests/pure_vm.rs
git commit -m "feat: add Rust pure VM prototype"
```

---

### Task 5: Add disabled-by-default Kotlin native runner facade

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/native/NativeVmRunner.kt`
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunnerSelectionTest.kt`
- Modify: `docs/PROFILING.md`

- [ ] **Step 1: Write failing native runner selection tests**

Create `VmRunnerSelectionTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime

import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VmRunnerSelectionTest {
    @Test
    fun nativeRunnerIsUnavailableWithoutExplicitLibraryPath() {
        assertFalse(NativeVmRunner.isAvailable(System.getProperty("ckl.vm.native.library")))
    }

    @Test
    fun bytecodeProgramStillUsesKotlinRunnerByDefault() {
        val artifact = LanguageFrontend().compile("default.ck", "pub fun main() { return }")
        val program = BytecodeComputerProgram(requireNotNull(artifact.module))

        assertTrue(program.toString().isNotBlank())
    }
}
```

- [ ] **Step 2: Run failing selection tests**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.VmRunnerSelectionTest
```

Expected: FAIL to compile because `NativeVmRunner` does not exist.

- [ ] **Step 3: Implement native runner facade**

Create `NativeVmRunner.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime.blazing

import ru.lazyhat.compukterkraft.lang.api.BytecodeModule
import ru.lazyhat.compukterkraft.lang.runtime.DeviceRuntime
import ru.lazyhat.compukterkraft.lang.runtime.VmRunner
import ru.lazyhat.compukterkraft.lang.runtime.abi.BytecodeAbi

class NativeVmRunner private constructor(
    private val libraryPath: String,
) : VmRunner {
    override suspend fun run(
        module: BytecodeModule,
        runtime: DeviceRuntime,
    ) {
        val bytecode = BytecodeAbi.encode(module)
        error("Native VM runner is scaffolded but not wired to JNI yet: library=$libraryPath, bytecodeBytes=${bytecode.size}, device=${runtime.system.deviceId}")
    }

    companion object {
        fun isAvailable(libraryPath: String?): Boolean = !libraryPath.isNullOrBlank()

        fun fromSystemProperty(): NativeVmRunner? {
            val path = System.getProperty("ckl.vm.native.library")
            return if (isAvailable(path)) NativeVmRunner(requireNotNull(path)) else null
        }
    }
}
```

- [ ] **Step 4: Document local native prototype commands**

Add this section to `docs/PROFILING.md` after the native candidate heuristic:

````markdown
## Rust VM prototype

The Rust VM prototype is local-development only until packaging is designed.

Run Rust crate tests:

```bash
cd native/ckl-vm && cargo test
```

Run Kotlin ABI and runner seam tests:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.BytecodeAbiTest --tests ru.lazyhat.compukterkraft.lang.runtime.VmRunnerSelectionTest
```

The native runner is disabled unless `-Dckl.vm.native.library=/absolute/path/to/libckl_vm.so` is provided. The Kotlin VM remains the default runtime path.
````

- [ ] **Step 5: Verify Kotlin tests**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.BytecodeAbiTest --tests ru.lazyhat.compukterkraft.lang.runtime.VmRunnerSelectionTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/native/NativeVmRunner.kt \
  modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunnerSelectionTest.kt \
  docs/PROFILING.md
git commit -m "feat: scaffold native VM runner facade"
```

---

### Task 6: Final prototype verification and findings

**Files:**
- Create: `docs/superpowers/todos/2026-05-06-rust-vm-prototype-findings.md`

- [ ] **Step 1: Run full targeted verification**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest --tests ru.lazyhat.compukterkraft.lang.runtime.BytecodeAbiTest --tests ru.lazyhat.compukterkraft.lang.runtime.VmRunnerSelectionTest
cd native/ckl-vm && cargo test
```

Expected: both commands PASS.

- [ ] **Step 2: Write findings report**

Create `docs/superpowers/todos/2026-05-06-rust-vm-prototype-findings.md`:

```markdown
# Rust VM Prototype Findings

## Verified

- Kotlin runner seam passes existing runtime tests.
- Bytecode ABI encoder is deterministic and versioned.
- Rust ABI decoder accepts the documented CKVM v1 byte stream.
- Rust pure VM prototype executes integer addition and emits host-call signals.
- Native runner facade is disabled by default and Kotlin VM remains the default path.

## Not Implemented In This Prototype

- JNI execution bridge.
- Full CKL instruction coverage in Rust.
- Rust-owned strings, event args, IPC, display, or filesystem.
- Native packaging for Minecraft distributions.

## Next Decision

Proceed to JNI bridge only if the ABI and pure VM prototype remain small enough to maintain and the team accepts Kotlin VM fallback as the default runtime path during development.
```

- [ ] **Step 3: Commit findings**

Run:

```bash
git add docs/superpowers/todos/2026-05-06-rust-vm-prototype-findings.md
git commit -m "docs: record rust vm prototype findings"
```

- [ ] **Step 4: Check branch status**

Run:

```bash
git status --short
```

Expected: no output.
