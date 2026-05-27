# CkVmImage Backend Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first Kotlin frontend-to-`CkVmImage` backend path for minimal CKL programs.

**Architecture:** This slice keeps the current Kotlin parser, analyzer, and legacy bytecode compiler. A new image backend lowers the existing `BytecodeModule` into the new `CkVmImage` skeleton format, which is enough to prove `CKL source -> Kotlin frontend -> CkVmImage -> Rust decoder` without replacing VM execution yet.

**Tech Stack:** Kotlin/JVM, Kotlin test, Gradle, existing `CkVmImageAbi`, existing Rust image decoder tests.

---

## File Structure

- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
  - Add skeleton image opcode constants.
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
  - Add `CkVmImageCompiler`, `CkVmImageCompilationArtifact`, and `LanguageFrontend.compileImage(...)` extension functions.
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`
  - Verify empty `main`, `system::log("hi")`, frontend errors, unsupported instructions, and Rust decoder fixture generation.
- Modify: `native/ckl-vm/tests/image_decode.rs`
  - Add a Rust test for the backend-generated fixture.
- Create: `native/ckl-vm/tests/fixtures/backend-system-log.ckim`
  - Kotlin-generated image fixture from the new backend.

## Skeleton Opcodes

Add these image opcodes as byte constants:

- `PUSH_UNIT = 1`
- `RETURN = 2`
- `PUSH_CONSTANT = 3`, followed by `i32 constantIndex`
- `CALL_HOST = 4`, followed by `i32 importId`, `i32 argumentCount`
- `POP = 5`

This is intentionally minimal. Unsupported legacy bytecode instructions should fail with `UnsupportedOperationException` and a message containing `CkVmImage backend does not support`.

---

### Task 1: RED Kotlin Backend Tests

**Files:**
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`

- [ ] **Step 1: Write the failing backend tests**

Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt` with tests that call `LanguageFrontend().compileImage(...)` and expect:

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime.image

import ru.lazyhat.compukterkraft.lang.frontend.FrontendSeverity
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CkVmImageBackendTest {
    @Test
    fun compileImageCreatesEntryFunctionForEmptyMain() {
        val artifact = LanguageFrontend().compileImage("main.ck", "pub fun main() { }")
        val image = assertNotNull(artifact.image)

        assertEquals("ckl-1", image.languageVersion)
        assertEquals(1, image.targetAbiVersion)
        assertEquals(0, image.entryFunctionIndex)
        assertEquals("main.ck#main", image.functions.single().name)
        assertContentEquals(listOf(CkVmImageOpcodes.PUSH_UNIT, CkVmImageOpcodes.RETURN), image.functions.single().code)
    }

    @Test
    fun compileImageLowersSystemLogToConstantAndHostImport() {
        val artifact = LanguageFrontend().compileImage("main.ck", "pub fun main() { system::log(\"hi\"); }")
        val image = assertNotNull(artifact.image)

        assertEquals(listOf(CkVmConstant.StringConstant("hi")), image.constants)
        assertEquals(listOf(CkVmHostImport(0, "system", "log", listOf("Any"), "Unit")), image.hostImports)
        assertContentEquals(
            listOf(
                CkVmImageOpcodes.PUSH_CONSTANT, 0, 0, 0, 0,
                CkVmImageOpcodes.CALL_HOST, 0, 0, 0, 0, 1, 0, 0, 0,
                CkVmImageOpcodes.POP,
                CkVmImageOpcodes.PUSH_UNIT,
                CkVmImageOpcodes.RETURN,
            ),
            image.functions.single().code,
        )
    }

    @Test
    fun compileImageReturnsNullImageWhenFrontendHasErrors() {
        val artifact = LanguageFrontend().compileImage("main.ck", "fun main() { }")

        assertNull(artifact.image)
        assertTrue(artifact.bytecode.analysis.diagnostics.any { it.severity == FrontendSeverity.ERROR })
    }

    @Test
    fun unsupportedInstructionReportsClearError() {
        val artifact = LanguageFrontend().compile("main.ck", "pub fun main() { if (true) { system::log(\"x\"); } }")
        val module = assertNotNull(artifact.module)

        val error = assertFailsWith<UnsupportedOperationException> {
            CkVmImageCompiler.compile(module)
        }

        assertTrue(error.message.orEmpty().contains("CkVmImage backend does not support"))
    }

    @Test
    fun writesBackendFixtureWhenPathIsProvided() {
        val path = System.getProperty("ckl.image.backend.fixture.path")?.takeIf(String::isNotBlank) ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { system::log(\"hi\"); }").image)

        java.nio.file.Files.createDirectories(java.nio.file.Path.of(path).parent)
        java.nio.file.Files.write(java.nio.file.Path.of(path), CkVmImageAbi.encode(image))
    }
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --rerun-tasks
```

Expected: FAIL during Kotlin compilation with unresolved `compileImage`, `CkVmImageOpcodes`, and `CkVmImageCompiler`.

- [ ] **Step 3: Commit RED test**

Run:

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt
git commit -m "test: add ckvm image backend red tests"
```

---

### Task 2: GREEN Kotlin Backend Implementation

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`

- [ ] **Step 1: Add opcode constants**

Append to `CkVmImage.kt`:

```kotlin
object CkVmImageOpcodes {
    const val PUSH_UNIT = 1
    const val RETURN = 2
    const val PUSH_CONSTANT = 3
    const val CALL_HOST = 4
    const val POP = 5
}
```

- [ ] **Step 2: Add the backend compiler and frontend extension**

Create `CkVmImageBackend.kt` with:

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime.image

import ru.lazyhat.compukterkraft.lang.api.BytecodeFunction
import ru.lazyhat.compukterkraft.lang.api.BytecodeModule
import ru.lazyhat.compukterkraft.lang.api.Instruction
import ru.lazyhat.compukterkraft.lang.frontend.CompilationArtifact
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.frontend.NoOpSourceLoader
import ru.lazyhat.compukterkraft.lang.frontend.SourceLoader

data class CkVmImageCompilationArtifact(
    val image: CkVmImage?,
    val bytecode: CompilationArtifact,
)

fun LanguageFrontend.compileImage(
    name: String,
    source: String,
): CkVmImageCompilationArtifact = compileImage(name, source, NoOpSourceLoader)

fun LanguageFrontend.compileImage(
    name: String,
    source: String,
    loader: SourceLoader,
): CkVmImageCompilationArtifact {
    val bytecode = compile(name, source, loader)
    return CkVmImageCompilationArtifact(
        image = bytecode.module?.let(CkVmImageCompiler::compile),
        bytecode = bytecode,
    )
}

object CkVmImageCompiler {
    fun compile(module: BytecodeModule): CkVmImage {
        val hostImports = collectHostImports(module)
        val context = LoweringContext(hostImports)
        return CkVmImage(
            languageVersion = "ckl-1",
            targetAbiVersion = CkVmImageAbi.VERSION,
            capabilities = if (hostImports.isEmpty()) emptyList() else listOf("host-import-ids"),
            constants = context.constants,
            hostImports = hostImports,
            entryFunctionIndex = module.entryFunctionIndex,
            functions = module.functions.map { function -> context.lower(function) },
        )
    }

    private fun collectHostImports(module: BytecodeModule): List<CkVmHostImport> =
        module.functions
            .flatMap { function -> function.instructions.filterIsInstance<Instruction.CallBuiltin>() }
            .filter { instruction -> instruction.moduleName != null }
            .map { instruction -> HostImportKey(requireNotNull(instruction.moduleName), instruction.functionName, instruction.argumentCount) }
            .distinct()
            .sortedWith(compareBy<HostImportKey> { it.moduleName }.thenBy { it.functionName }.thenBy { it.argumentCount })
            .mapIndexed { index, key ->
                CkVmHostImport(index, key.moduleName, key.functionName, List(key.argumentCount) { "Any" }, "Unit")
            }

    private data class HostImportKey(
        val moduleName: String,
        val functionName: String,
        val argumentCount: Int,
    )

    private class LoweringContext(
        hostImports: List<CkVmHostImport>,
    ) {
        private val hostImportIds = hostImports.associateBy { Triple(it.moduleName, it.functionName, it.parameterTypes.size) }
        val constants = mutableListOf<CkVmConstant>()

        fun lower(function: BytecodeFunction): CkVmFunction =
            CkVmFunction(
                name = function.name,
                frameSize = function.parameters.size + function.locals.size,
                code = function.instructions.flatMap(::lowerInstruction),
            )

        private fun lowerInstruction(instruction: Instruction): List<Int> =
            when (instruction) {
                Instruction.PushUnit -> listOf(CkVmImageOpcodes.PUSH_UNIT)
                Instruction.Return -> listOf(CkVmImageOpcodes.RETURN)
                Instruction.Pop -> listOf(CkVmImageOpcodes.POP)
                is Instruction.PushString -> pushConstant(CkVmConstant.StringConstant(instruction.value))
                is Instruction.PushInt -> pushConstant(CkVmConstant.IntConstant(instruction.value))
                is Instruction.PushLong -> pushConstant(CkVmConstant.LongConstant(instruction.value))
                is Instruction.CallBuiltin -> callBuiltin(instruction)
                else -> throw UnsupportedOperationException("CkVmImage backend does not support ${instruction::class.simpleName}")
            }

        private fun pushConstant(constant: CkVmConstant): List<Int> {
            val index = constants.indexOf(constant).takeIf { it >= 0 } ?: constants.size.also { constants += constant }
            return listOf(CkVmImageOpcodes.PUSH_CONSTANT) + i32(index)
        }

        private fun callBuiltin(instruction: Instruction.CallBuiltin): List<Int> {
            val moduleName = instruction.moduleName ?: throw UnsupportedOperationException("CkVmImage backend does not support global builtin ${instruction.functionName}")
            val import = hostImportIds.getValue(Triple(moduleName, instruction.functionName, instruction.argumentCount))
            return listOf(CkVmImageOpcodes.CALL_HOST) + i32(import.id) + i32(instruction.argumentCount)
        }

        private fun i32(value: Int): List<Int> =
            listOf(value and 0xff, (value ushr 8) and 0xff, (value ushr 16) and 0xff, (value ushr 24) and 0xff)
    }
}
```

- [ ] **Step 3: Run GREEN**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 4: Commit backend implementation**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt
git commit -m "feat: add ckvm image backend skeleton"
```

---

### Task 3: Cross-Language Backend Fixture

**Files:**
- Modify: `native/ckl-vm/tests/image_decode.rs`
- Create: `native/ckl-vm/tests/fixtures/backend-system-log.ckim`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`

- [ ] **Step 1: Generate backend fixture from Kotlin**

Run:

```bash
JAVA_TOOL_OPTIONS="-Dckl.image.backend.fixture.path=$PWD/native/ckl-vm/tests/fixtures/backend-system-log.ckim" ./gradlew :compiler:test --tests '*CkVmImageBackendTest.writesBackendFixtureWhenPathIsProvided' --rerun-tasks
```

Expected: PASS and `native/ckl-vm/tests/fixtures/backend-system-log.ckim` exists.

- [ ] **Step 2: Add Rust fixture decode test**

Add to `native/ckl-vm/tests/image_decode.rs`:

```rust
#[test]
fn decodes_backend_generated_system_log_fixture() {
    let bytes = include_bytes!("fixtures/backend-system-log.ckim");
    let image = decode_image(bytes).expect("backend fixture decodes");

    assert_eq!(image.language_version, "ckl-1");
    assert_eq!(image.capabilities, vec!["host-import-ids"]);
    assert_eq!(image.constants, vec![Constant::String("hi".to_string())]);
    assert_eq!(image.host_imports.len(), 1);
    assert_eq!(image.host_imports[0].module_name, "system");
    assert_eq!(image.host_imports[0].function_name, "log");
    assert_eq!(image.functions[0].code, vec![3, 0, 0, 0, 0, 4, 0, 0, 0, 0, 1, 0, 0, 0, 5, 1, 2]);
}
```

- [ ] **Step 3: Run Kotlin and Rust tests**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --tests '*CkVmImageAbiTest' --rerun-tasks
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_decode -- --nocapture
```

Expected: both commands PASS.

- [ ] **Step 4: Commit backend fixture**

Run:

```bash
git add native/ckl-vm/tests/fixtures/backend-system-log.ckim native/ckl-vm/tests/image_decode.rs modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt
git commit -m "test: add ckvm image backend fixture"
```

---

### Task 4: Final Verification

**Files:**
- Verify all changed files.

- [ ] **Step 1: Run focused Kotlin and Rust tests**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageAbiTest' --tests '*CkVmImageBackendTest' --tests '*BytecodeAbiTest' --rerun-tasks
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_decode -- --nocapture
```

Expected: both commands PASS.

- [ ] **Step 2: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 3: Inspect git status**

Run:

```bash
git status --short
```

Expected: clean status if every commit step was executed.

---

## Self-Review Notes

- This plan implements only the backend skeleton from real CKL source to `CkVmImage`.
- It intentionally does not execute the image in Rust.
- It intentionally does not add a stable host import registry beyond deterministic per-image import ids.
- It intentionally uses current `BytecodeModule` as a temporary lowering source; a shared typed IR remains a later slice.