# Native Image VM Runner Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an explicit Kotlin runtime adapter and `DeviceProgram` for running `CkVmImage` programs through the Rust JNI image lifecycle.

**Architecture:** Keep the existing `BytecodeModule`/`BytecodeAbi` native runner unchanged. Add a separate `NativeImageVmRunner` that accepts `CkVmImage`, uses `NativeVmBindings.createImage(...)`, and mirrors the existing host-call signal loop. Add `CkVmImageComputerProgram` as an explicit opt-in program wrapper instead of changing `VmRunner` or `VmRunnerFactory`.

**Tech Stack:** Kotlin/JVM, Kotlin tests, Gradle, existing `CkVmImageAbi`, existing `NativeVmBindings` image JNI methods, existing Rust native library.

---

## File Structure

- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt`
  - Image-specific native runner that accepts `CkVmImage`.
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageComputerProgram.kt`
  - Explicit `DeviceProgram` wrapper for already-compiled images.
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`
  - JNI smoke tests for runner-level image execution.
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageComputerProgramTest.kt`
  - Program wrapper test with an injected runner factory.

## Task 1: RED Runner-Level JNI Tests

**Files:**
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`

- [ ] **Step 1: Write failing tests**

Create the test file with this content:

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

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.runtime.RecordingRuntime
import ru.lazyhat.compukterkraft.lang.runtime.image.compileImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NativeImageVmRunnerJniTest {
    @Test
    fun imageRunnerHaltsForEmptyMainWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { }").image)

        runBlocking {
            NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, RecordingRuntime())
        }
    }

    @Test
    fun imageRunnerDispatchesSystemLogHostCallWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { system::log(\"hi\"); }").image)
        val runtime = RecordingRuntime()

        runBlocking {
            NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime)
        }

        assertEquals(listOf("hi"), runtime.lines)
    }
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :compiler:test --tests '*NativeImageVmRunnerJniTest' --rerun-tasks
```

Expected: FAIL at Kotlin compilation with unresolved reference `NativeImageVmRunner`.

- [ ] **Step 3: Commit RED tests**

Run:

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt
git commit -m "test: add native image vm runner red tests"
```

## Task 2: Implement NativeImageVmRunner

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt`

- [ ] **Step 1: Add the runner implementation**

Create the file with this content:

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

import ru.lazyhat.compukterkraft.lang.runtime.DeviceRuntime
import ru.lazyhat.compukterkraft.lang.runtime.RuntimeHostBridge
import ru.lazyhat.compukterkraft.lang.runtime.VmSignalKind
import ru.lazyhat.compukterkraft.lang.runtime.VmValue
import ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImage
import ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageAbi

class NativeImageVmRunner private constructor(
    private val libraryPath: String,
) {
    suspend fun run(
        image: CkVmImage,
        runtime: DeviceRuntime,
    ) {
        val imageBytes = CkVmImageAbi.encode(image)
        val bridge = RuntimeHostBridge(runtime)
        val handle = NativeVmBindings.createImage(libraryPath, imageBytes, runtime.profile.resources.cpu.instructionsPerSlice)
        try {
            while (true) {
                val signal = NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(handle))
                if (signal !is NativeVmSignal.Error) {
                    runtime.metrics.recordVmSignal(signal.kind)
                }
                when (signal) {
                    is NativeVmSignal.Halt -> return
                    is NativeVmSignal.Error -> error("Native image VM failed for device ${runtime.system.deviceId}: ${signal.message}")
                    NativeVmSignal.Pause -> runtime.yield()
                    NativeVmSignal.Yield -> {
                        runtime.yield()
                        NativeVmBindings.resumeImageWith(handle, VmValue.UnitValue.toNativeBytes("", "yield"))
                    }
                    is NativeVmSignal.Sleep -> {
                        runtime.sleep(signal.ticks)
                        NativeVmBindings.resumeImageWith(handle, VmValue.UnitValue.toNativeBytes("", "sleep"))
                    }
                    is NativeVmSignal.WaitEvent -> {
                        val event = runtime.pullEvent(signal.filter)
                        NativeVmBindings.resumeImageWith(handle, bridge.fromEvent(event).toNativeBytes("events", "pull"))
                    }
                    is NativeVmSignal.HostCall -> {
                        val result = invokeHostCall(runtime, bridge, signal)
                        NativeVmBindings.resumeImageWith(handle, result.toNativeBytes(signal.moduleName, signal.functionName))
                    }
                }
            }
        } finally {
            NativeVmBindings.freeImage(handle)
        }
    }

    private suspend fun invokeHostCall(
        runtime: DeviceRuntime,
        bridge: RuntimeHostBridge,
        signal: NativeVmSignal.HostCall,
    ): VmValue {
        val arguments = signal.arguments.map { it.toVmValue(signal.moduleName, signal.functionName) }
        if (!runtime.metrics.collectsDetailedMetrics) {
            return bridge.invoke(signal.moduleName, signal.functionName, arguments)
        }
        val started = System.nanoTime()
        try {
            return bridge.invoke(signal.moduleName, signal.functionName, arguments)
        } finally {
            runtime.metrics.recordVmHostCall(signal.moduleName, signal.functionName, System.nanoTime() - started)
        }
    }

    companion object {
        fun isAvailable(libraryPath: String?): Boolean = !libraryPath.isNullOrBlank()

        fun fromLibraryPath(libraryPath: String): NativeImageVmRunner = NativeImageVmRunner(libraryPath)

        fun fromSystemProperty(): NativeImageVmRunner? {
            val path = System.getProperty("ckl.vm.native.library")
            return if (isAvailable(path)) fromLibraryPath(requireNotNull(path)) else null
        }
    }
}

private val NativeVmSignal.kind: VmSignalKind
    get() =
        when (this) {
            is NativeVmSignal.Halt -> VmSignalKind.HALT
            NativeVmSignal.Pause -> VmSignalKind.PAUSE
            NativeVmSignal.Yield -> VmSignalKind.YIELD
            is NativeVmSignal.Sleep -> VmSignalKind.SLEEP
            is NativeVmSignal.WaitEvent -> VmSignalKind.WAIT_EVENT
            is NativeVmSignal.HostCall -> VmSignalKind.HOST_CALL
            is NativeVmSignal.Error -> error("Native image VM errors are not runtime VM signals")
        }
```

- [ ] **Step 2: Run GREEN with native library**

Run:

```bash
./gradlew buildRustVmNativeLibrary :compiler:test --tests '*NativeImageVmRunnerJniTest' --rerun-tasks -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit runner implementation**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt
git commit -m "feat: add native image vm runner adapter"
```

## Task 3: RED Program Wrapper Test

**Files:**
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageComputerProgramTest.kt`

- [ ] **Step 1: Write failing program test**

Create the file with this content:

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

package ru.lazyhat.compukterkraft.lang.runtime.image

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.runtime.DeviceRuntime
import ru.lazyhat.compukterkraft.lang.runtime.RecordingRuntime
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeImageRuntimeRunner
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CkVmImageComputerProgramTest {
    @Test
    fun imageProgramUsesInjectedRunnerFactory() {
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { }").image)
        var invoked = false
        val program = CkVmImageComputerProgram(
            image = image,
            runnerFactory = {
                object : NativeImageRuntimeRunner {
                    override suspend fun run(image: CkVmImage, runtime: DeviceRuntime) {
                        invoked = true
                    }
                }
            },
        )

        runBlocking { program.run(RecordingRuntime()) }

        assertTrue(invoked)
    }
}
```

Expected note: this test intentionally references `CkVmImageComputerProgram` and `NativeImageRuntimeRunner`, which do not exist yet. Task 4 defines the small runtime interface so the program wrapper is testable without loading JNI.

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageComputerProgramTest' --rerun-tasks
```

Expected: FAIL at Kotlin compilation with unresolved references `CkVmImageComputerProgram` and `NativeImageRuntimeRunner`.

- [ ] **Step 3: Commit RED test**

Run:

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageComputerProgramTest.kt
git commit -m "test: add ckvm image computer program red test"
```

## Task 4: Implement CkVmImageComputerProgram

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt`
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageComputerProgram.kt`

- [ ] **Step 1: Add testable runner interface**

In `NativeImageVmRunner.kt`, change the class declaration to implement a new interface and add the interface above the class:

```kotlin
interface NativeImageRuntimeRunner {
    suspend fun run(
        image: CkVmImage,
        runtime: DeviceRuntime,
    )
}

class NativeImageVmRunner private constructor(
    private val libraryPath: String,
) : NativeImageRuntimeRunner {
```

- [ ] **Step 2: Add program wrapper**

Create `CkVmImageComputerProgram.kt` with this content:

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

package ru.lazyhat.compukterkraft.lang.runtime.image

import ru.lazyhat.compukterkraft.lang.runtime.DeviceProgram
import ru.lazyhat.compukterkraft.lang.runtime.DeviceRuntime
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeImageRuntimeRunner
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeImageVmRunner

class CkVmImageComputerProgram(
    private val image: CkVmImage,
    private val runnerFactory: () -> NativeImageRuntimeRunner = {
        NativeImageVmRunner.fromSystemProperty()
            ?: error("Rust image VM runner requires -Dckl.vm.native.library=/absolute/path/to/libckl_vm.so")
    },
) : DeviceProgram {
    override suspend fun run(runtime: DeviceRuntime) {
        runnerFactory().run(image, runtime)
    }
}
```

- [ ] **Step 3: Run GREEN**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageComputerProgramTest' --rerun-tasks
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit program wrapper**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageComputerProgram.kt
git commit -m "feat: add ckvm image computer program"
```

## Task 5: Final Verification

**Files:**
- Verify all changed files.

- [ ] **Step 1: Run focused Kotlin/JNI tests**

Run:

```bash
./gradlew buildRustVmNativeLibrary :compiler:test --tests '*NativeImageVmRunnerJniTest' --tests '*NativeImageVmBindingsJniTest' --tests '*CkVmImageComputerProgramTest' --tests '*CkVmImageBackendTest' --rerun-tasks -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run Rust regression tests**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml -- --nocapture
```

Expected: all Rust tests pass.

- [ ] **Step 3: Check whitespace and status**

Run:

```bash
git diff --check && git status --short --untracked-files=all
```

Expected: no whitespace errors; status is clean after commits.