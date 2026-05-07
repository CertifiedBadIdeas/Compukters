# Rust Image Runtime Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the legacy Rust bytecode VM path and leave the branch with a single Rust-image runtime path.

**Architecture:** Kotlin keeps `BytecodeModule` only as temporary compiler scaffolding, but no longer serializes it to the old CKVM bytecode ABI. The native crate keeps CKIM/image execution plus shared `VmValue`/`VmSignal` codec; old `abi.rs`, `vm.rs`, and `runner.rs` are deleted. Profiling support becomes single image-runtime profiling instead of JVM/Rust comparison.

**Tech Stack:** Kotlin/JVM, Gradle Kotlin DSL, Rust JNI crate, CKIM `CkVmImage`, `NativeImageVmRunner`.

---

## File Structure

- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindingsImageOnlyTest.kt`
  - Architecture guard that fails while legacy bytecode JNI methods remain.
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
  - Keep only image lifecycle bindings.
- Delete: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/abi/BytecodeAbi.kt`
- Delete: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/BytecodeAbiTest.kt`
- Modify: `native/ckl-vm/src/signal.rs`
  - Own `VmSignal` directly.
- Modify: `native/ckl-vm/src/image_runner.rs`
  - Import `VmSignal` from `crate::signal`.
- Modify: `native/ckl-vm/src/jni.rs`
  - Remove legacy bytecode JNI exports and legacy handle helpers.
- Modify: `native/ckl-vm/src/lib.rs`
  - Export only image/value/signal/JNI modules.
- Delete: `native/ckl-vm/src/abi.rs`
- Delete: `native/ckl-vm/src/vm.rs`
- Delete: `native/ckl-vm/src/runner.rs`
- Delete: `native/ckl-vm/tests/abi_decode.rs`
- Delete: `native/ckl-vm/tests/pure_vm.rs`
- Delete: `native/ckl-vm/tests/runner.rs`
- Modify: `native/ckl-vm/tests/signal_codec.rs`
  - Import `VmSignal` from `ckl_vm::signal`.
- Modify: `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts`
  - Use `ckl.profiling.runtime.name` instead of runner naming.
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`
  - Rename profile model from runner-oriented to runtime-oriented and remove comparison formatter.
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt`
  - Use runtime naming property.
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt`
  - Update profile type/property names.
- Delete: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportAggregationTest.kt`
- Delete: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`
- Modify: `docs/PROFILING.md`
  - Remove comparison-report language and mention only image profiling.

## Task 1: RED Guard for Image-Only JNI Bindings

**Files:**
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindingsImageOnlyTest.kt`

- [ ] **Step 1: Add failing architecture guard**

Create `NativeVmBindingsImageOnlyTest.kt` with this content:

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

package ru.lazyhat.compukterkraft.lang.runtime.blazing

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeVmBindingsImageOnlyTest {
    @Test
    fun nativeBindingsExposeOnlyImageVmLifecycle() {
        val methodNames = NativeVmBindings::class.java.declaredMethods.map { it.name }.toSet()

        assertTrue("createImage" in methodNames)
        assertTrue("runImageUntilSignal" in methodNames)
        assertTrue("resumeImageWith" in methodNames)
        assertTrue("freeImage" in methodNames)

        assertFalse("runUntilSignal" in methodNames)
        assertFalse("create" in methodNames)
        assertFalse("resumeWith" in methodNames)
        assertFalse("free" in methodNames)
        assertFalse("runUntilSignalNative" in methodNames)
        assertFalse("createNative" in methodNames)
        assertFalse("resumeWithNative" in methodNames)
        assertFalse("freeNative" in methodNames)
    }
}
```

- [ ] **Step 2: Run RED test**

Run:

```bash
./gradlew :compiler:test --tests '*NativeVmBindingsImageOnlyTest' --rerun-tasks
```

Expected: FAIL because `NativeVmBindings` still exposes legacy bytecode methods such as `runUntilSignal`, `create`, `resumeWith`, and `free`.

- [ ] **Step 3: Commit RED guard**

Run:

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindingsImageOnlyTest.kt
git commit -m "test: require native bindings to be image-only"
```

## Task 2: Remove Kotlin Bytecode ABI and Legacy Binding Methods

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Delete: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/abi/BytecodeAbi.kt`
- Delete: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/BytecodeAbiTest.kt`

- [ ] **Step 1: Make `NativeVmBindings` image-only**

Replace `NativeVmBindings.kt` with this content:

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

package ru.lazyhat.compukterkraft.lang.runtime.blazing

internal object NativeVmBindings {
    private val lock = Any()
    private var loadedPath: String? = null

    fun createImage(
        libraryPath: String,
        image: ByteArray,
        instructionBudget: Int,
    ): Long {
        load(libraryPath)
        val handle = createImageNative(image, instructionBudget.coerceAtLeast(1))
        check(handle != 0L) { "Native image VM create returned a zero handle" }
        return handle
    }

    fun runImageUntilSignal(handle: Long): ByteArray {
        require(handle != 0L) { "Native image VM handle is zero" }
        return runImageUntilSignalForHandleNative(handle)
    }

    fun resumeImageWith(
        handle: Long,
        value: ByteArray,
    ) {
        require(handle != 0L) { "Native image VM handle is zero" }
        resumeImageWithNative(handle, value)
    }

    fun freeImage(handle: Long) {
        if (handle != 0L) {
            freeImageNative(handle)
        }
    }

    private fun load(libraryPath: String) {
        synchronized(lock) {
            val current = loadedPath
            if (current == libraryPath) {
                return
            }
            require(current == null) {
                "Native VM library already loaded from $current; cannot load $libraryPath in the same JVM"
            }
            System.load(libraryPath)
            loadedPath = libraryPath
        }
    }

    @JvmStatic
    private external fun createImageNative(
        image: ByteArray,
        instructionBudget: Int,
    ): Long

    @JvmStatic
    private external fun runImageUntilSignalForHandleNative(handle: Long): ByteArray

    @JvmStatic
    private external fun resumeImageWithNative(
        handle: Long,
        value: ByteArray,
    )

    @JvmStatic
    private external fun freeImageNative(handle: Long)
}
```

- [ ] **Step 2: Delete Kotlin bytecode ABI files**

Delete:

```text
modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/abi/BytecodeAbi.kt
modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/BytecodeAbiTest.kt
```

- [ ] **Step 3: Run Kotlin binding tests**

Run:

```bash
./gradlew :compiler:test --tests '*NativeVmBindingsImageOnlyTest' --tests '*NativeImageVmBindingsJniTest' --rerun-tasks
```

Expected: BUILD SUCCESSFUL. JNI tests skip if `ckl.vm.native.library` is not configured.

- [ ] **Step 4: Verify no Kotlin Bytecode ABI references remain**

Run:

```bash
grep -R "BytecodeAbi\|runtime.abi" modules/compiler/src/main/kotlin modules/compiler/src/test/kotlin || true
```

Expected: no matches.

- [ ] **Step 5: Commit Kotlin cleanup**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindingsImageOnlyTest.kt
git rm modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/abi/BytecodeAbi.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/BytecodeAbiTest.kt
git commit -m "refactor: remove kotlin bytecode native abi"
```

## Task 3: Remove Rust Legacy Bytecode VM

**Files:**
- Modify: `native/ckl-vm/src/signal.rs`
- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `native/ckl-vm/src/lib.rs`
- Modify: `native/ckl-vm/tests/signal_codec.rs`
- Delete: `native/ckl-vm/src/abi.rs`
- Delete: `native/ckl-vm/src/vm.rs`
- Delete: `native/ckl-vm/src/runner.rs`
- Delete: `native/ckl-vm/tests/abi_decode.rs`
- Delete: `native/ckl-vm/tests/pure_vm.rs`
- Delete: `native/ckl-vm/tests/runner.rs`

- [ ] **Step 1: Move `VmSignal` into `signal.rs`**

At the top of `native/ckl-vm/src/signal.rs`, replace:

```rust
use crate::value::VmValue;
use crate::vm::VmSignal;
```

with:

```rust
use crate::value::VmValue;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VmSignal {
    Halt(VmValue),
    Pause,
    Yield,
    Sleep(i64),
    WaitEvent(Option<String>),
    HostCall {
        module_name: String,
        function_name: String,
        arguments: Vec<VmValue>,
    },
}
```

- [ ] **Step 2: Update image runner signal import**

In `native/ckl-vm/src/image_runner.rs`, replace:

```rust
use crate::signal::{decode_value, encode_error, encode_signal};
use crate::value::VmValue;
use crate::vm::VmSignal;
```

with:

```rust
use crate::signal::{decode_value, encode_error, encode_signal, VmSignal};
use crate::value::VmValue;
```

- [ ] **Step 3: Make JNI exports image-only**

In `native/ckl-vm/src/jni.rs`, remove the import:

```rust
use crate::runner::{run_bytecode_until_signal, NativeVmHandle};
```

Delete these functions completely:

```text
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runUntilSignalNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_createNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runUntilSignalForHandleNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_resumeWithNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_freeNative
handle_mut
```

Keep `createImageNative`, `runImageUntilSignalForHandleNative`, `resumeImageWithNative`, `freeImageNative`, `image_handle_mut`, and `byte_array_or_throw` unchanged except for formatting after deletions.

- [ ] **Step 4: Update native crate exports**

Replace `native/ckl-vm/src/lib.rs` with:

```rust
pub mod image;
pub mod image_runner;
pub mod jni;
pub mod signal;
pub mod value;
```

- [ ] **Step 5: Update signal codec test import**

In `native/ckl-vm/tests/signal_codec.rs`, replace any import of `VmSignal` from `ckl_vm::vm` with:

```rust
use ckl_vm::signal::VmSignal;
```

Keep existing `encode_signal`, `encode_error`, `encode_value`, and `decode_value` imports from `ckl_vm::signal`.

- [ ] **Step 6: Delete legacy Rust bytecode files**

Delete:

```text
native/ckl-vm/src/abi.rs
native/ckl-vm/src/vm.rs
native/ckl-vm/src/runner.rs
native/ckl-vm/tests/abi_decode.rs
native/ckl-vm/tests/pure_vm.rs
native/ckl-vm/tests/runner.rs
```

- [ ] **Step 7: Run Rust tests**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml -- --nocapture
```

Expected: BUILD SUCCESSFUL with only image/signal tests remaining.

- [ ] **Step 8: Verify no Rust legacy VM references remain**

Run:

```bash
grep -R "run_bytecode_until_signal\|NativeVmHandle\|crate::abi\|crate::vm\|pub mod abi\|pub mod vm\|pub mod runner" native/ckl-vm/src native/ckl-vm/tests || true
```

Expected: no matches.

- [ ] **Step 9: Commit Rust cleanup**

Run:

```bash
git add native/ckl-vm/src/signal.rs native/ckl-vm/src/image_runner.rs native/ckl-vm/src/jni.rs native/ckl-vm/src/lib.rs native/ckl-vm/tests/signal_codec.rs
git rm native/ckl-vm/src/abi.rs native/ckl-vm/src/vm.rs native/ckl-vm/src/runner.rs native/ckl-vm/tests/abi_decode.rs native/ckl-vm/tests/pure_vm.rs native/ckl-vm/tests/runner.rs
git commit -m "refactor: remove legacy rust bytecode vm"
```

## Task 4: Convert Profiling Support to Single Image Runtime Semantics

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt`
- Delete: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportAggregationTest.kt`
- Delete: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`

- [ ] **Step 1: Rename Gradle profiling property**

In `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts`, replace:

```kotlin
systemProperty("ckl.profiling.runner.name", "Rust image")
```

with:

```kotlin
systemProperty("ckl.profiling.runtime.name", "Rust image")
```

- [ ] **Step 2: Rename profile model and codec header**

In `RuntimeVmProfilingReport.kt`:

Replace:

```kotlin
internal data class VmRunnerProfile(
    val runnerName: String,
    val workloads: List<RuntimeWorkloadProfile>,
)
```

with:

```kotlin
internal data class RuntimeVmProfile(
    val runtimeName: String,
    val workloads: List<RuntimeWorkloadProfile>,
)
```

Update `RuntimeVmProfileCodec.write(...)` signature from `profile: VmRunnerProfile` to `profile: RuntimeVmProfile`, and replace:

```kotlin
appendLine("runner\t${profile.runnerName}")
```

with:

```kotlin
appendLine("runtime\t${profile.runtimeName}")
```

Update `RuntimeVmProfileCodec.read(...)` return type to `RuntimeVmProfile`, replace `runnerName` local variable with `runtimeName`, read both current and old headers for compatibility:

```kotlin
var runtimeName: String? = null
```

and in the line parser use:

```kotlin
"runtime", "runner" -> runtimeName = parts[1]
```

Return:

```kotlin
return RuntimeVmProfile(
    runtimeName = runtimeName ?: error("Missing runtime line in $path"),
    workloads = workloads.map { it.build() },
)
```

- [ ] **Step 3: Delete comparison formatter from report model file**

In `RuntimeVmProfilingReport.kt`, delete the entire `internal object RuntimeVmProfilingReportFormatter` block, starting at:

```kotlin
internal object RuntimeVmProfilingReportFormatter {
```

and ending at the file's final closing brace for that object.

After deletion, remove the now-unused import:

```kotlin
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeInstructionMetrics
```

Keep `RuntimeHostCallMetrics`; `RuntimeVmProfileCodec` still writes and reads host-call metrics.

- [ ] **Step 4: Update profiling report test naming**

In `RuntimeVmProfilingReportTest.kt`, replace:

```kotlin
val runnerName = System.getProperty(RUNNER_NAME_PROPERTY, "Rust image")
```

with:

```kotlin
val runtimeName = System.getProperty(RUNTIME_NAME_PROPERTY, "Rust image")
```

Replace:

```kotlin
val profile = profileRunner(runnerName)
RuntimeVmProfileCodec.write(profile, profilePath)
println("Runtime VM $runnerName profiling data: ${profilePath.absolutePathString()}")
```

with:

```kotlin
val profile = profileRuntime(runtimeName)
RuntimeVmProfileCodec.write(profile, profilePath)
println("Runtime VM $runtimeName profiling data: ${profilePath.absolutePathString()}")
```

Rename `profileRunner(runnerName: String): VmRunnerProfile` to:

```kotlin
private fun profileRuntime(runtimeName: String): RuntimeVmProfile =
    RuntimeVmProfile(
        runtimeName = runtimeName,
```

and replace the companion property:

```kotlin
const val RUNNER_NAME_PROPERTY = "ckl.profiling.runner.name"
```

with:

```kotlin
const val RUNTIME_NAME_PROPERTY = "ckl.profiling.runtime.name"
```

- [ ] **Step 5: Update codec test naming**

In `RuntimeVmProfilingProfileCodecTest.kt`, replace:

```kotlin
VmRunnerProfile(
    runnerName = "JVM",
```

with:

```kotlin
RuntimeVmProfile(
    runtimeName = "Rust image",
```

- [ ] **Step 6: Delete comparison-only profiling tests**

Delete:

```text
modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportAggregationTest.kt
modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt
```

- [ ] **Step 7: Run profiling codec/report tests**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingProfileCodecTest' --tests '*RuntimeVmProfilingReportTest' --rerun-tasks
```

Expected: BUILD SUCCESSFUL. `RuntimeVmProfilingReportTest` skips unless the Gradle profiling task provides `ckl.profiling.profile.path`.

- [ ] **Step 8: Commit profiling cleanup**

Run:

```bash
git add modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt
git rm modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportAggregationTest.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt
git commit -m "test: simplify runtime vm profiling to image runtime"
```

## Task 5: Docs and Final Verification

**Files:**
- Modify: `docs/PROFILING.md`

- [ ] **Step 1: Update profiling docs**

In `docs/PROFILING.md`, replace references to runtime VM comparison reports with single image profiling. Use this wording for the runtime profiling section:

```markdown
Runtime VM profiling now records the Rust image runtime path only. Use `profileRuntimeVmImage` to build the native library, run the profiling workload, and write `build/reports/profiling/runtime-vm-image.tsv`.
```

Remove active instructions that mention `profileRuntimeVmComparison`, `profileRuntimeVmJvm`, JVM/Rust ratios, or `ckl.vm.runner`.

- [ ] **Step 2: Run final focused verification with native library**

Run:

```bash
./gradlew buildRustVmNativeLibrary :compiler:test --tests '*CkVmImageBackendTest' --tests '*CkVmImageComputerProgramTest' --tests '*NativeImageVmRunnerJniTest' --tests '*NativeImageVmBindingsJniTest' --tests '*NativeVmBindingsImageOnlyTest' :v1_21_1-neoforge:test --tests '*DeviceProgramSupportTest' --tests '*RuntimeVmProfilingProfileCodecTest' --rerun-tasks -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run Rust verification**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml -- --nocapture
```

Expected: BUILD SUCCESSFUL with no `abi_decode`, `pure_vm`, or `runner` test binaries.

- [ ] **Step 4: Run stale-reference and whitespace checks**

Run:

```bash
grep -R "BytecodeAbi\|run_bytecode_until_signal\|NativeVmHandle\|createNative\|runUntilSignalNative\|resumeWithNative\|freeNative\|ckl.vm.runner\|profileRuntimeVmComparison\|profileRuntimeVmJvm" modules build-scripts native docs/ARCHITECTURE.md docs/MACHINE.md docs/PROFILING.md -n || true
git diff --check
git status --short --untracked-files=all
```

Expected: no stale matches; no whitespace errors; clean status after commits.

- [ ] **Step 5: Commit docs cleanup**

Run:

```bash
git add docs/PROFILING.md
git commit -m "docs: describe image-only runtime profiling"
```
