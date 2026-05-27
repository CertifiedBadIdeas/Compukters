# CKVM Image Bundled Resource Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a compile-to-image parity audit for every bundled firmware and ROM CKL resource.

**Architecture:** Create a dedicated NeoForge implementation test that loads bundled resources from the test classpath, builds a classpath source map, and compiles each resource with `LanguageFrontend.compileImage`. The test aggregates image-lowering failures so the next parity blocker is visible across all bundled CKL programs.

**Tech Stack:** Kotlin, kotlin.test, Gradle, CKL `LanguageFrontend`, `LanguageBuiltins.defaultRuntimeRegistry`, `MapSourceLoader`, `CkVmImage` backend.

---

## File Structure

- Create: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/CkVmImageBundledResourceCompileTest.kt`
  - Responsibility: resource discovery, classpath source loading, compile-to-image audit, aggregated diagnostics.
- Read/reference: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`
  - Responsibility: existing classpath resource loading and ROM index parsing patterns.
- Read/reference: `docs/superpowers/specs/2026-05-07/2026-05-07-ckvm-image-bundled-resource-audit-design.md`
  - Responsibility: accepted design and acceptance criteria.

---

### Task 1: Add RED compile-to-image audit test

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/CkVmImageBundledResourceCompileTest.kt`

- [ ] **Step 1: Create the failing test file**

Create `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/CkVmImageBundledResourceCompileTest.kt` with this content:

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
package ru.lazyhat.compukterkraft.impl

import ru.lazyhat.compukterkraft.lang.frontend.LanguageBuiltins
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.frontend.MapSourceLoader
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail

class CkVmImageBundledResourceCompileTest {
    private val classLoader: ClassLoader = CkVmImageBundledResourceCompileTest::class.java.classLoader

    @Test
    fun bundledFirmwareAndRomCompileToCkVmImage() {
        val sources = bundledSources()
        val sourceLoader = MapSourceLoader(sources)
        val frontend = LanguageFrontend(LanguageBuiltins.defaultRuntimeRegistry)

        val failures =
            sources.entries.mapNotNull { (path, source) ->
                val artifact = frontend.compileImage(path, source, sourceLoader)
                if (artifact.image != null) {
                    null
                } else {
                    val diagnostics =
                        artifact.analysis.diagnostics.joinToString(separator = "\n") { diagnostic ->
                            val range = diagnostic.range
                            if (range == null) {
                                "  - ${diagnostic.message}"
                            } else {
                                "  - ${diagnostic.message} @ ${range.start.line}:${range.start.column}-${range.end.line}:${range.end.column}"
                            }
                        }
                    buildString {
                        appendLine(path)
                        if (artifact.errorMessage != null) {
                            appendLine("  error: ${artifact.errorMessage}")
                        }
                        if (diagnostics.isNotBlank()) {
                            appendLine(diagnostics)
                        }
                    }
                }
            }

        if (failures.isNotEmpty()) {
            fail("Bundled CKL resources failed to compile to CkVmImage:\n" + failures.joinToString("\n"))
        }
    }

    private fun bundledSources(): Map<String, String> {
        val romFiles = romIndex()
        assertNotNull(romFiles.firstOrNull(), "rom.index is empty")
        return buildMap {
            put("firmware/bios.ck", resourceText("firmware/bios.ck"))
            for (romFile in romFiles) {
                put(romFile, resourceText("rom/$romFile"))
            }
        }
    }

    private fun romIndex(): List<String> =
        resourceText("rom/rom.index")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("#") }
            .toList()

    private fun resourceText(path: String): String =
        classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
            ?: fail("$path missing from classpath")
}
```

- [ ] **Step 2: Run the focused test to verify RED**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.CkVmImageBundledResourceCompileTest'
```

Expected outcome: one of these is acceptable for RED:

- FAIL with `Bundled CKL resources failed to compile to CkVmImage:` and one or more resource diagnostics; or
- PASS, which means bundled resources already lower to `CkVmImage` and there is no implementation blocker in this audit slice.

If the test does not compile because of an API mismatch, fix the test helper code only and re-run until it reaches either the expected RED failure or a valid PASS.

---

### Task 2: Stabilize audit helper behavior

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/CkVmImageBundledResourceCompileTest.kt`

- [ ] **Step 1: If Task 1 failed because imports did not resolve, adjust the source map keys**

Use this exact `bundledSources()` implementation if imports expect both bare ROM names and prefixed resource paths:

```kotlin
private fun bundledSources(): Map<String, String> {
    val romFiles = romIndex()
    assertNotNull(romFiles.firstOrNull(), "rom.index is empty")
    return buildMap {
        put("firmware/bios.ck", resourceText("firmware/bios.ck"))
        for (romFile in romFiles) {
            val source = resourceText("rom/$romFile")
            put(romFile, source)
            put("rom/$romFile", source)
        }
    }
}
```

- [ ] **Step 2: Re-run focused test**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.CkVmImageBundledResourceCompileTest'
```

Expected outcome: the test reaches the real image parity result:

- PASS if all resources lower to image; or
- FAIL with aggregated `CkVmImage` diagnostics that identify unsupported instruction/runtime parity blockers.

- [ ] **Step 3: Commit the audit test**

Run:

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/CkVmImageBundledResourceCompileTest.kt
git commit -m "test: audit bundled ck scripts for ckvm image"
```

If the test fails with a real parity blocker, commit the RED audit test anyway. The next plan should use that failure as the RED test for the next implementation slice.

---

### Task 3: Report next parity blocker

**Files:**
- No code changes unless Task 2 required source map stabilization.

- [ ] **Step 1: Capture focused test output**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.CkVmImageBundledResourceCompileTest'
```

Expected outcome:

- If PASS: report that compile-to-image bundled resource parity is clear, and propose native-run smoke as the next ordered step.
- If FAIL: report the first unsupported instruction or frontend diagnostic from the aggregated output, and propose that blocker as the next implementation slice.

- [ ] **Step 2: Check workspace status**

Run:

```bash
git status --short --untracked-files=all
git log --oneline --decorate -5
```

Expected outcome: only intentional committed changes, or a clear explanation of any remaining working tree changes.
