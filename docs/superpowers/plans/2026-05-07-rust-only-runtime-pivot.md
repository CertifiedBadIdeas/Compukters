# Rust-Only Runtime Pivot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the Kotlin/JVM CKL VM runtime from this branch and make `CkVmImage` + Rust JNI image execution the only supported runtime entry path.

**Architecture:** Keep Kotlin parser/analyzer/frontend and temporary `BytecodeModule` compiler scaffolding. Replace production runtime compilation with `LanguageFrontend.compileImage(...)` and `CkVmImageComputerProgram`. Delete JVM VM runtime classes and retire tests that validate Kotlin VM behavior instead of the Rust image VM.

**Tech Stack:** Kotlin/JVM, Gradle, Rust JNI image VM, `CkVmImage`, `CkVmImageAbi`, `NativeImageVmRunner`.

---

## File Structure

- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceProgramSupport.kt`
  - Compile runtime programs to `CkVmImageComputerProgram` instead of `BytecodeComputerProgram`.
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/runtime/DeviceProgramSupportTest.kt`
  - Assert compiler emits image programs and still reports frontend/ROM-limit errors.
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`
  - Remove `BytecodeVirtualMachine`, `KotlinVmRunner`, bytecode snapshots, bytecode signal model, and `BytecodeComputerProgram`; keep `VmValue` only.
- Delete: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunner.kt`
- Delete: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunnerFactory.kt`
- Delete: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmRunner.kt`
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RecordingRuntime.kt`
  - Move reusable test runtime support out of deleted Kotlin VM test file.
- Delete: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
- Delete: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunnerSelectionTest.kt`
- Delete: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/UserFileImportsRuntimeTest.kt`
- Delete: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmRunnerJniTest.kt`
- Delete or rewrite: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/LanguageWorkspaceRuntimeTest.kt`
  - Remove direct `BytecodeVirtualMachine` runtime test.

## Task 1: RED Program Compiler Image Runtime Test

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/runtime/DeviceProgramSupportTest.kt`

- [ ] **Step 1: Add image program assertion**

Add this import:

```kotlin
import ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageComputerProgram
```

Add this test after `reportsCompilationErrorsWithoutProducingProgram`:

```kotlin
    @Test
    fun compilesSupportedSourceToImageProgram() {
        val compiled = ComputerProgramCompiler.compile("tiny.ck", "pub fun main() { }")

        assertTrue(compiled.program is CkVmImageComputerProgram)
        assertNull(compiled.errorMessage)
    }
```

Rename `rejectsProgramWhenCompiledBytecodeExceedsRomLimit` to `rejectsProgramWhenCompiledImageExceedsRomLimit` without changing its assertions.

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests '*DeviceProgramSupportTest' --rerun-tasks
```

Expected: FAIL because `ComputerProgramCompiler.compile(...)` still returns `BytecodeComputerProgram`.

- [ ] **Step 3: Commit RED test**

Run:

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/runtime/DeviceProgramSupportTest.kt
git commit -m "test: require runtime compiler to emit ckvm image programs"
```

## Task 2: Switch Production Runtime Compiler to CkVmImage

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceProgramSupport.kt`

- [ ] **Step 1: Replace bytecode program output with image output**

Update imports:

```kotlin
import ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImage
import ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageAbi
import ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageComputerProgram
import ru.lazyhat.compukterkraft.lang.runtime.image.compileImage
```

Remove these imports:

```kotlin
import ru.lazyhat.compukterkraft.lang.runtime.BytecodeComputerProgram
```

In `ComputerProgramCompiler.compile(...)`, replace the current body starting at `val artifact = LanguageFrontend(runtimeRegistry, compilerMetricsCollector).compile(path, source, sourceLoader)` and ending after `CompiledComputerProgram(program = BytecodeComputerProgram(module))` with:

```kotlin
        val artifact = LanguageFrontend(runtimeRegistry, compilerMetricsCollector).compileImage(path, source, sourceLoader)
        val image = artifact.image
        val errorMessage =
            artifact.bytecode.analysis.diagnostics
                .filter { it.severity == FrontendSeverity.ERROR }
                .joinToString { it.message }

        return if (image == null || errorMessage.isNotEmpty()) {
            CompiledComputerProgram(
                program = null,
                errorMessage = errorMessage.ifEmpty { "Compilation failed." },
            )
        } else if (profile != null && image.estimatedRomBytes() > profile.resources.storage.programRomBytes) {
            CompiledComputerProgram(
                program = null,
                errorMessage = "Program exceeds ROM limit: ${image.estimatedRomBytes()} > ${profile.resources.storage.programRomBytes}",
            )
        } else {
            CompiledComputerProgram(program = CkVmImageComputerProgram(image))
        }
```

Remove bytecode size helpers and replace them with:

```kotlin
private fun CkVmImage.estimatedRomBytes(): Long = CkVmImageAbi.encode(this).size.toLong()
```

- [ ] **Step 2: Run GREEN for compiler support test**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests '*DeviceProgramSupportTest' --rerun-tasks
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit production compiler switch**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceProgramSupport.kt
git commit -m "feat: compile runtime programs to ckvm images"
```

## Task 3: Remove JVM VM Runtime Implementation

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`
- Delete: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunner.kt`
- Delete: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunnerFactory.kt`
- Delete: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmRunner.kt`

- [ ] **Step 1: Replace LanguageRuntime.kt with value model only**

Replace `LanguageRuntime.kt` with this content:

```kotlin
/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.lang.runtime

sealed interface VmValue {
    data object UnitValue : VmValue

    data object NullValue : VmValue

    data class BoolValue(
        val value: Boolean,
    ) : VmValue

    data class IntValue(
        val value: Int,
    ) : VmValue

    data class LongValue(
        val value: Long,
    ) : VmValue

    data class StringValue(
        val value: String,
    ) : VmValue

    data class RecordValue(
        val typeName: String,
        val fields: Map<String, VmValue>,
    ) : VmValue

    data class ObjectRef(
        val id: Int,
    ) : VmValue
}
```

- [ ] **Step 2: Delete runner selection and legacy bytecode native adapter**

Delete these files:

```text
modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunner.kt
modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunnerFactory.kt
modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmRunner.kt
```

- [ ] **Step 3: Run compile to expose stale references**

Run:

```bash
./gradlew :compiler:compileKotlin :core:compileKotlin --rerun-tasks
```

Expected: BUILD SUCCESSFUL. Production source must not reference deleted runtime classes after this step.

- [ ] **Step 4: Commit JVM VM runtime removal**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt
git rm modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunner.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunnerFactory.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmRunner.kt
git commit -m "refactor: remove kotlin vm runtime implementation"
```

## Task 4: Retire JVM VM Runtime Tests and Preserve Test Runtime Support

**Files:**
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RecordingRuntime.kt`
- Delete: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
- Delete: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunnerSelectionTest.kt`
- Delete: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/UserFileImportsRuntimeTest.kt`
- Delete: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmRunnerJniTest.kt`
- Delete: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/LanguageWorkspaceRuntimeTest.kt`

- [ ] **Step 1: Move reusable runtime test support**

Create `RecordingRuntime.kt` by moving these complete declarations from the current `LanguageRuntimeTest.kt` into the new file:

```text
internal data class CapturedMono5x7Blit
internal class RecordingRuntime
internal class RecordingDeviceRuntimeMetrics : DeviceRuntimeMetrics
```

Move the entire code block starting at the declaration `internal data class CapturedMono5x7Blit` and ending at the closing brace of `RecordingDeviceRuntimeMetrics`. Preserve the package `ru.lazyhat.compukterkraft.lang.runtime` and add the standard GPL header.

- [ ] **Step 2: Delete Kotlin VM runtime tests**

Delete these files:

```text
modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt
modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunnerSelectionTest.kt
modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/UserFileImportsRuntimeTest.kt
modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmRunnerJniTest.kt
modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/LanguageWorkspaceRuntimeTest.kt
```

- [ ] **Step 3: Verify no Kotlin/JVM VM test references remain**

Run:

```bash
grep -R "BytecodeVirtualMachine\|BytecodeComputerProgram\|KotlinVmRunner\|VmRunnerFactory\|NativeVmRunner" modules/*/src/test/kotlin || true
```

Expected: no matches except `NativeImageVmRunner` and `NativeImageVmRunnerJniTest`, which are valid image runtime references.

- [ ] **Step 4: Run compiler image tests**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --tests '*CkVmImageComputerProgramTest' --tests '*NativeImageVmRunnerJniTest' --tests '*NativeImageVmBindingsJniTest' --rerun-tasks
```

Expected: BUILD SUCCESSFUL when no native library is configured because JNI tests skip.

- [ ] **Step 5: Commit runtime test retirement**

Run:

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RecordingRuntime.kt
git rm modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmRunnerSelectionTest.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/UserFileImportsRuntimeTest.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmRunnerJniTest.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/LanguageWorkspaceRuntimeTest.kt
git commit -m "test: retire kotlin vm runtime tests"
```

## Task 5: Update Documentation and Verify Runtime Pivot

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/LANGUAGE.md` if it mentions JVM VM runner selection.

- [ ] **Step 1: Update architecture docs**

In `docs/ARCHITECTURE.md`, replace references that describe the Kotlin VM as an active runtime with a short note:

```markdown
CKL runtime execution in this branch is Rust-image based. Kotlin still owns frontend analysis and temporary image lowering scaffolding, but JVM bytecode VM execution is no longer maintained here.
```

- [ ] **Step 2: Remove runner property docs**

Search and remove or rewrite docs mentioning `ckl.vm.runner=kotlin` or legacy `ckl.vm.runner=rust` bytecode selection:

```bash
grep -R "ckl.vm.runner\|Kotlin VM\|BytecodeVirtualMachine" docs modules -n || true
```

Expected after edits: no docs claim JVM VM is an active runtime path.

- [ ] **Step 3: Run focused verification with native library**

Run:

```bash
./gradlew buildRustVmNativeLibrary :compiler:test --tests '*CkVmImageBackendTest' --tests '*CkVmImageComputerProgramTest' --tests '*NativeImageVmRunnerJniTest' --tests '*NativeImageVmBindingsJniTest' :v1_21_1-neoforge:test --tests '*DeviceProgramSupportTest' --rerun-tasks -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run Rust regression tests**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml -- --nocapture
```

Expected: all Rust tests pass.

- [ ] **Step 5: Check stale production references and whitespace**

Run:

```bash
grep -R "BytecodeVirtualMachine\|BytecodeComputerProgram\|KotlinVmRunner\|VmRunnerFactory\|NativeVmRunner" modules/*/src/main/kotlin || true
git diff --check
git status --short --untracked-files=all
```

Expected: no stale production references; no whitespace errors; clean status after commits.

- [ ] **Step 6: Commit docs and verification cleanup**

Run:

```bash
git add docs/ARCHITECTURE.md docs/LANGUAGE.md
git commit -m "docs: document rust image runtime pivot"
```