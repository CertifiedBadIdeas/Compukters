# Architecture Boundary Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add executable boundary checks that keep `core` Minecraft-free and keep loader modules thin, so future version and loader refactors happen against enforced architectural rules instead of informal convention.

**Architecture:** Put repository-structure assertions in `:core` tests, because `core` is the intended shared-pure module and can host architecture-focused tests without pulling in loader runtime. Enforce two concrete rules first: `core` must not import `net.minecraft.*`, and loader leaf modules must only contain the currently intentional bootstrap, registry, network, hook, and tiny shim files.

**Tech Stack:** Kotlin test, Gradle, Java NIO file walking, existing multi-module project layout.

---

### Task 1: Add a core purity boundary test

**Files:**
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt`

- [ ] **Step 1: Write the failing test file skeleton**

```kotlin
package ru.lazyhat.compukterkraft.core.architecture

import kotlin.test.Test

class ArchitectureBoundaryTest {
    @Test
    fun coreMainSourcesDoNotImportMinecraftPackages() {
        error("not implemented")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.architecture.ArchitectureBoundaryTest.coreMainSourcesDoNotImportMinecraftPackages"`
Expected: FAIL with `IllegalStateException: not implemented`.

- [ ] **Step 3: Replace the skeleton with a real repository scan**

```kotlin
package ru.lazyhat.compukterkraft.core.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureBoundaryTest {
    @Test
    fun coreMainSourcesDoNotImportMinecraftPackages() {
        val repoRoot = findRepoRoot()
        val coreMain = repoRoot.resolve("modules/core/src/main/kotlin")
        val offenders = kotlinFilesUnder(coreMain)
            .filter { file ->
                Files.readString(file).contains("import net.minecraft.")
            }
            .map { file -> repoRoot.relativize(file).invariantSeparatorsPathString }
            .sorted()

        assertTrue(
            offenders.isEmpty(),
            "core must stay Minecraft-free, but found imports in: ${offenders.joinToString()}"
        )
    }

    private fun findRepoRoot(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { current -> current.parent }
            .first { candidate -> Files.exists(candidate.resolve("settings.gradle.kts")) }

    private fun kotlinFilesUnder(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths.filter { path -> Files.isRegularFile(path) && path.extension == "kt" }
                .toList()
        }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.architecture.ArchitectureBoundaryTest.coreMainSourcesDoNotImportMinecraftPackages"`
Expected: PASS.

- [ ] **Step 5: Commit the purity test**

```bash
git add modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt
git commit -m "test: enforce core minecraft boundary"
```

### Task 2: Add a thin-loader allowlist test

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt`

- [ ] **Step 1: Add a second failing test for loader file allowlists**

```kotlin
@Test
fun loaderLeafModulesContainOnlyAllowedMainSourceFiles() {
    error("not implemented")
}
```

- [ ] **Step 2: Run the new test to verify it fails**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.architecture.ArchitectureBoundaryTest.loaderLeafModulesContainOnlyAllowedMainSourceFiles"`
Expected: FAIL with `IllegalStateException: not implemented`.

- [ ] **Step 3: Implement exact allowlists for the current loader leaves**

```kotlin
@Test
fun loaderLeafModulesContainOnlyAllowedMainSourceFiles() {
    val repoRoot = findRepoRoot()
    val allowedFiles = mapOf(
        "modules/v1_20_1/v1_20_1-fabric/src/main/kotlin" to setOf(
            "ru/lazyhat/compukterkraft/impl/ClientRegistry.kt",
            "ru/lazyhat/compukterkraft/impl/CompukterKraftClientMod.kt",
            "ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt",
            "ru/lazyhat/compukterkraft/impl/Extensions.kt",
            "ru/lazyhat/compukterkraft/impl/FabricCommonHooks.kt",
            "ru/lazyhat/compukterkraft/impl/ModRegistry.kt",
            "ru/lazyhat/compukterkraft/impl/platform/NetworkHandler.kt",
        ),
        "modules/v1_20_1/v1_20_1-forge/src/main/kotlin" to setOf(
            "ru/lazyhat/compukterkraft/impl/ClientRegistry.kt",
            "ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt",
            "ru/lazyhat/compukterkraft/impl/Extensions.kt",
            "ru/lazyhat/compukterkraft/impl/ForgeClientHooks.kt",
            "ru/lazyhat/compukterkraft/impl/ForgeCommonHooks.kt",
            "ru/lazyhat/compukterkraft/impl/ModRegistry.kt",
            "ru/lazyhat/compukterkraft/impl/block/ForgeComputerBlockEntity.kt",
            "ru/lazyhat/compukterkraft/impl/platform/NetworkHandler.kt",
        ),
        "modules/v1_21_1/v1_21_1-fabric/src/main/kotlin" to setOf(
            "ru/lazyhat/compukterkraft/impl/ClientRegistry.kt",
            "ru/lazyhat/compukterkraft/impl/CompukterKraftClientMod.kt",
            "ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt",
            "ru/lazyhat/compukterkraft/impl/Extensions.kt",
            "ru/lazyhat/compukterkraft/impl/FabricCommonHooks.kt",
            "ru/lazyhat/compukterkraft/impl/ModRegistry.kt",
            "ru/lazyhat/compukterkraft/impl/platform/NetworkHandler.kt",
        ),
        "modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin" to setOf(
            "ru/lazyhat/compukterkraft/impl/ClientRegistry.kt",
            "ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt",
            "ru/lazyhat/compukterkraft/impl/Extensions.kt",
            "ru/lazyhat/compukterkraft/impl/ForgeClientHooks.kt",
            "ru/lazyhat/compukterkraft/impl/ForgeClientRegistry.kt",
            "ru/lazyhat/compukterkraft/impl/ForgeCommonHooks.kt",
            "ru/lazyhat/compukterkraft/impl/ModRegistry.kt",
            "ru/lazyhat/compukterkraft/impl/block/NeoForgeComputerBlockEntity.kt",
            "ru/lazyhat/compukterkraft/impl/context/ComputerIdentitySavedDataAccess.kt",
            "ru/lazyhat/compukterkraft/impl/platform/NetworkHandler.kt",
        ),
    )

    val violations = allowedFiles.flatMap { (modulePath, allowed) ->
        val root = repoRoot.resolve(modulePath)
        kotlinFilesUnder(root)
            .map { file -> root.relativize(file).invariantSeparatorsPathString }
            .sorted()
            .filterNot { relative -> relative in allowed }
            .map { relative -> "$modulePath/$relative" }
    }

    assertTrue(
        violations.isEmpty(),
        "loader leaf modules must stay thin, unexpected files found: ${violations.joinToString()}"
    )
}
```

- [ ] **Step 4: Run the focused test class to verify both boundary checks pass**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.architecture.ArchitectureBoundaryTest"`
Expected: PASS with 2 tests completed.

- [ ] **Step 5: Commit the loader boundary coverage**

```bash
git add modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt
git commit -m "test: enforce thin loader module boundaries"
```

### Task 3: Document the enforced boundary in architecture docs

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt`

- [ ] **Step 1: Add a short boundary-check section under module ownership rules**

```md
### Boundary Enforcement

The repository treats these boundaries as executable rules, not just conventions:

- `core` must not import `net.minecraft.*`
- loader leaf modules must stay limited to bootstrap, registry, network, hooks, and tiny unavoidable shims

These rules are enforced by `ArchitectureBoundaryTest` in `modules/core`.
```

- [ ] **Step 2: Run the architecture test class again after the docs update**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.architecture.ArchitectureBoundaryTest"`
Expected: PASS.

- [ ] **Step 3: Commit the documentation update**

```bash
git add docs/ARCHITECTURE.md modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt
git commit -m "docs: record architecture boundary checks"
```

### Task 4: Run the minimal verification set for the new guardrails

**Files:**
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/bootstrap/CommonModBootstrapTest.kt`

- [ ] **Step 1: Run only the boundary tests and bootstrap regression test**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.architecture.ArchitectureBoundaryTest" --tests "ru.lazyhat.compukterkraft.core.bootstrap.CommonModBootstrapTest"`
Expected: PASS.

- [ ] **Step 2: Run the full `:core:test` task**

Run: `./gradlew :core:test`
Expected: PASS.

- [ ] **Step 3: Commit the verified boundary-enforcement slice**

```bash
git add docs/ARCHITECTURE.md modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt
git commit -m "test: add architecture boundary guardrails"
```

## Follow-On Plan After This One

This plan intentionally stops after executable boundary enforcement.
The next plan should cover the `v1_21_11` reuse audit:

- populate `v1_21_11-common` from `v1_21_1-common` as a baseline;
- compile against `1.21.11` conventions;
- classify every required change as mappings-only, tooling-only, or source-level drift;
- use that evidence to decide whether a broader API-family abstraction is justified.