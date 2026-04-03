# Multi-Version Shared Code Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the project so shared code is split by responsibility and Minecraft version: pure shared logic lives once, Minecraft-dependent shared code is shared only within a single MC version, and loader leaf modules stay thin.

**Architecture:** Introduce one pure shared module with no Minecraft dependencies and two version-shared modules, one for `1.20.1` and one for `1.21.1`. Loader modules depend only on the version-shared module for their MC line, preventing any single module from mixing `1.20.1` and `1.21.1` dependencies.

**Tech Stack:** Kotlin, Gradle, Architectury Loom, Architectury Plugin, Fabric API, Forge, NeoForge

---

## Corrected Target Structure

```text
compiler/            # pure Kotlin, no Minecraft deps
core/                # pure shared mod/application logic, no Minecraft deps
core1201/            # Minecraft 1.20.1 shared code for Fabric + Forge
core1211/            # Minecraft 1.21.1 shared code for Fabric + NeoForge
fabric1201/          # loader glue only, depends on core1201 + core + compiler
forge1201/           # loader glue only, depends on core1201 + core + compiler
fabric1211/          # loader glue only, depends on core1211 + core + compiler
neoforge1211/        # loader glue only, depends on core1211 + core + compiler
```

## Why The Previous Plan Was Wrong

The previous plan assumed a single Minecraft-dependent `core` could be reused across both `1.20.1` and `1.21.1`. That does not hold once `core` imports Minecraft classes:

- method signatures differ between versions
- rendering APIs differ between versions
- SavedData, item data, buffer, and component APIs differ between versions
- one compiled `namedElements` artifact cannot faithfully represent both MC lines at once

So the valid sharing boundary is:

1. pure non-Minecraft logic shared across all targets
2. Minecraft-dependent logic shared only within a single MC version
3. loader bootstrap and adapter glue shared by nobody unless duplication proves it later

---

## Current State Assessment

### Already Pure And Safe To Share Everywhere

- `compiler/`
- parts of current `core/` such as application/domain logic that do not import `net.minecraft.*`
- platform SPI interfaces under `core/src/main/kotlin/ck/mod/platform/` if they remain MC-free

### Currently Version-Bound To 1.20.1

Current `core/` contains many `net.minecraft.*` imports, so today it is not a pure common module. It is effectively `core1201` under the wrong name.

### Currently Duplicated In 1.21.1 Leaves

`fabric1211/` and `neoforge1211/` contain large shared subtrees that should become `core1211/`:

- `network/`
- `menu/`
- `computer/ServerComputer.kt`
- `context/`
- `data/`, `item/`, `loot/`
- `application/`, `gui/`, `ui/`, `utils/`, likely large parts of `language/`

### Loader-Specific And Expected To Stay In Leaves

- entrypoints: `CompukterKraftMod.kt`, `CompukterKraftClientMod.kt`
- registration: `ModRegistry.kt`
- lifecycle hooks: `FabricCommonHooks.kt`, `ForgeCommonHooks.kt`, `ForgeClientHooks.kt`
- network bootstrap transport: `platform/NetworkHandler.kt`
- client registration glue

---

## Migration Strategy

Do **not** try to force everything into one shared module first.

The safe order is:

1. carve out pure shared code from current `core/` into a truly pure `core/`
2. freeze the remaining Minecraft-dependent `1.20.1` code into `core1201/`
3. create `core1211/` from the duplicated `fabric1211`/`neoforge1211` shared code
4. point each leaf module to the version-shared module for its MC line
5. only then keep extracting more pure code upward from `core1201/` and `core1211/` into pure `core/`

This keeps the project buildable while avoiding a mixed-version shared artifact.

---

## Task 1: Introduce Version-Shared Modules In Gradle

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core1201/build.gradle.kts`
- Create: `core1211/build.gradle.kts`
- Create: `core1201/gradle.properties`
- Create: `core1211/gradle.properties`

- [ ] **Step 1: Add the new modules to settings**

  In `settings.gradle.kts`, add:

  ```kotlin
  include("core1201")
  include("core1211")
  ```

- [ ] **Step 2: Create `core1201/build.gradle.kts` by copying current `core/build.gradle.kts`**

  Keep it on the current `1.20.1` setup:
  - `minecraft(libs.minecraft.asProvider())`
  - `forge(libs.forge)`
  - layered mappings with parchment 1.20.1
  - `architectury { common("forge", "fabric") }`

- [ ] **Step 3: Create `core1201/gradle.properties` with**

  ```properties
  loom.platform = forge
  ```

- [ ] **Step 4: Create `core1211/build.gradle.kts` from the 1.21.1 common shape**

  Use:

  ```kotlin
  plugins {
      alias(libs.plugins.kotlinConvention)
      alias(libs.plugins.architectury.loom)
      alias(libs.plugins.architectury.plugin)
  }

  repositories {
      maven("https://maven.neoforged.net/releases/")
  }

  kotlin {
      jvmToolchain(21)
  }

  java {
      sourceCompatibility = JavaVersion.VERSION_21
      targetCompatibility = JavaVersion.VERSION_21
  }

  architectury {
      minecraft = libs.versions.minecraft.v1211.get()
      common("neoforge", "fabric")
  }

  dependencies {
      minecraft(libs.minecraft.v1211)
      neoForge(libs.neoforge)
      mappings(loom.officialMojangMappings())

      implementation(projects.compiler)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlin.stdlib)
      implementation(libs.kotlin.logging)

      testImplementation(kotlin("test"))
      testImplementation(libs.kotlinx.coroutines.test)
  }
  ```

- [ ] **Step 5: Create `core1211/gradle.properties` with**

  ```properties
  loom.platform = neoforge
  ```

- [ ] **Step 6: Verify the empty modules configure**

  Run:

  ```bash
  ./gradlew :core1201:tasks :core1211:tasks --no-daemon
  ```

- [ ] **Step 7: Commit**

  ```bash
  git add settings.gradle.kts core1201 core1211
  git commit -m "build: introduce version-shared core1201 and core1211 modules"
  ```

---

## Task 2: Convert Existing `core/` Into A Pure Shared Module

**Files:**
- Modify: `core/build.gradle.kts`
- Modify: `core/gradle.properties` if still version-specific
- Move MC-dependent files out of `core/src/main/kotlin/ck/mod/`

**Goal:** after this task, `core/` must have **zero** `net.minecraft.*` imports.

- [ ] **Step 1: Remove Loom and Minecraft dependencies from `core/build.gradle.kts`**

  Replace the current build script with a plain Kotlin/JVM module:

  ```kotlin
  plugins {
      alias(libs.plugins.kotlinConvention)
  }

  dependencies {
      implementation(projects.compiler)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlin.stdlib)
      implementation(libs.kotlin.logging)

      testImplementation(kotlin("test"))
      testImplementation(libs.kotlinx.coroutines.test)
  }
  ```

- [ ] **Step 2: Remove `loom.platform` from `core/gradle.properties`**

  `core/` is no longer a Loom module.

- [ ] **Step 3: Audit all `net.minecraft.*` imports under `core/src/`**

  Run:

  ```bash
  rg -n "^import net\.minecraft" core/src
  ```

  Every file found must be moved either to `core1201/` or later to `core1211/` if it is version-shared for that line.

- [ ] **Step 4: Move all current MC-dependent files from `core/` to `core1201/`**

  This includes current files such as:

  - `block/ComputerFamily.kt`
  - `block/ComputerState.kt`
  - `context/ComputerIdentitySavedData.kt`
  - `data/IContainerData.kt`
  - `ui/`, `gui/`, `utils/` files with MC imports
  - any `menu/`, `computer/`, or rendering helpers with MC imports

  Copy first, then delete from `core/`.

- [ ] **Step 5: Keep only MC-free code in `core/`**

  Candidates to keep in pure `core/`:

  - SPI interfaces in `platform/api/`
  - pure application state and orchestration
  - workbench state models that do not import MC classes
  - VM/domain integration code that only depends on `compiler`

- [ ] **Step 6: Verify `core` no longer imports MC**

  ```bash
  rg -n "^import net\.minecraft" core/src
  ```

  Expected: no output.

- [ ] **Step 7: Compile `core`**

  ```bash
  ./gradlew :core:compileKotlin --no-daemon
  ```

- [ ] **Step 8: Compile `core1201`**

  ```bash
  ./gradlew :core1201:compileKotlin --no-daemon
  ```

- [ ] **Step 9: Commit**

  ```bash
  git add core core1201
  git commit -m "refactor: split pure core from 1.20.1 shared code"
  ```

---

## Task 3: Repoint 1.20.1 Leaves To `core1201`

**Files:**
- Modify: `fabric1201/build.gradle.kts`
- Modify: `forge1201/build.gradle.kts`

- [ ] **Step 1: Replace dependency on `:core` with `:core1201`**

  In both build files, change:

  ```kotlin
  implementation(project(path = ":core", configuration = "namedElements"))
  ```

  to:

  ```kotlin
  implementation(project(path = ":core1201", configuration = "namedElements"))
  implementation(projects.core)
  ```

  The version-shared module provides MC-bound code. Pure `core` provides MC-free logic.

- [ ] **Step 2: Compile `fabric1201` and `forge1201`**

  ```bash
  ./gradlew :fabric1201:compileKotlin :forge1201:compileKotlin --no-daemon
  ```

- [ ] **Step 3: Commit**

  ```bash
  git add fabric1201/build.gradle.kts forge1201/build.gradle.kts
  git commit -m "build: point 1.20.1 leaves at core1201 and pure core"
  ```

---

## Task 4: Create `core1211/` From Shared 1.21.1 Leaf Code

**Files:**
- Create and populate: `core1211/src/main/kotlin/ck/mod/**`

**Goal:** move code duplicated between `fabric1211` and `neoforge1211` into `core1211/`.

- [ ] **Step 1: Confirm identical subtrees between the 1.21.1 leaves**

  Run:

  ```bash
  diff -r fabric1211/src/main/kotlin/ck/mod/network/ neoforge1211/src/main/kotlin/ck/mod/network/
  diff -r fabric1211/src/main/kotlin/ck/mod/menu/ neoforge1211/src/main/kotlin/ck/mod/menu/
  diff -r fabric1211/src/main/kotlin/ck/mod/context/ neoforge1211/src/main/kotlin/ck/mod/context/
  diff -r fabric1211/src/main/kotlin/ck/mod/computer/ neoforge1211/src/main/kotlin/ck/mod/computer/
  diff -r fabric1211/src/main/kotlin/ck/mod/data/ neoforge1211/src/main/kotlin/ck/mod/data/
  diff -r fabric1211/src/main/kotlin/ck/mod/item/ neoforge1211/src/main/kotlin/ck/mod/item/
  diff -r fabric1211/src/main/kotlin/ck/mod/loot/ neoforge1211/src/main/kotlin/ck/mod/loot/
  ```

- [ ] **Step 2: Copy identical shared files into `core1211`**

  First targets:

  - `network/` except loader transport bootstrap if it differs
  - `menu/`
  - `computer/ServerComputer.kt`
  - `context/`
  - `data/`, `item/`, `loot/`

- [ ] **Step 3: Compile `core1211`**

  ```bash
  ./gradlew :core1211:compileKotlin --no-daemon
  ```

- [ ] **Step 4: Move additional shared 1.21.1 code into `core1211`**

  Continue with:

  - `gui/`
  - `ui/`
  - `utils/`
  - `application/`
  - `language/` as appropriate

  Do this only after diffing the two leaves and confirming the files are shared.

- [ ] **Step 5: Compile `core1211` again**

  ```bash
  ./gradlew :core1211:compileKotlin --no-daemon
  ```

- [ ] **Step 6: Commit**

  ```bash
  git add core1211
  git commit -m "refactor: create core1211 from shared 1.21.1 code"
  ```

---

## Task 5: Repoint 1.21.1 Leaves To `core1211`

**Files:**
- Modify: `fabric1211/build.gradle.kts`
- Modify: `neoforge1211/build.gradle.kts`

- [ ] **Step 1: Add dependencies on `core1211` and pure `core`**

  In both modules add:

  ```kotlin
  implementation(project(path = ":core1211", configuration = "namedElements"))
  implementation(projects.core)
  ```

- [ ] **Step 2: Compile both 1.21.1 leaves before deleting duplicates**

  ```bash
  ./gradlew :fabric1211:compileKotlin :neoforge1211:compileKotlin --no-daemon
  ```

- [ ] **Step 3: Commit**

  ```bash
  git add fabric1211/build.gradle.kts neoforge1211/build.gradle.kts
  git commit -m "build: point 1.21.1 leaves at core1211 and pure core"
  ```

---

## Task 6: Delete Duplicates From 1.21.1 Leaves

**Goal:** after this task, `fabric1211/` and `neoforge1211/` contain only loader-specific glue.

- [ ] **Step 1: Delete files now provided by `core1211`**

  Remove duplicated trees from both leaves, keeping only:

  - entrypoints
  - `ModRegistry.kt`
  - loader event hooks
  - client registration glue
  - `platform/NetworkHandler.kt`

- [ ] **Step 2: Compile both 1.21.1 leaves**

  ```bash
  ./gradlew :fabric1211:compileKotlin :neoforge1211:compileKotlin --no-daemon
  ```

- [ ] **Step 3: Audit remaining files**

  ```bash
  find fabric1211/src/main/kotlin -name "*.kt" | sort
  find neoforge1211/src/main/kotlin -name "*.kt" | sort
  ```

- [ ] **Step 4: Commit**

  ```bash
  git add -A
  git commit -m "refactor: trim 1.21.1 leaves to loader-specific glue"
  ```

---

## Task 7: Extract More Pure Code Upward From `core1201/` And `core1211/`

**Goal:** reduce duplication between version-shared modules without violating the version boundary.

- [ ] **Step 1: Compare `core1201` and `core1211` for MC-free files**

  Run:

  ```bash
  diff -r --brief core1201/src/main/kotlin/ck/mod/ core1211/src/main/kotlin/ck/mod/
  ```

- [ ] **Step 2: For each identical file, check imports**

  Only move files upward into pure `core/` if they do **not** import:

  - `net.minecraft.*`
  - loader APIs

- [ ] **Step 3: Move safe files from `core1201/` and `core1211/` into `core/`**

  This is the only correct way to maximize sharing across versions.

- [ ] **Step 4: Recompile all four version-shared and leaf modules**

  ```bash
  ./gradlew :core:compileKotlin :core1201:compileKotlin :core1211:compileKotlin \
            :fabric1201:compileKotlin :forge1201:compileKotlin \
            :fabric1211:compileKotlin :neoforge1211:compileKotlin --no-daemon
  ```

- [ ] **Step 5: Commit**

  ```bash
  git add -A
  git commit -m "refactor: extract additional pure shared code from version modules"
  ```

---

## Task 8: Verification

- [ ] **Step 1: Run full build**

  ```bash
  ./gradlew build --no-daemon
  ```

- [ ] **Step 2: Run tests**

  ```bash
  ./gradlew test --no-daemon
  ```

- [ ] **Step 3: Verify no mixed-version dependency remains**

  Check:

  - `core/` has no `net.minecraft.*` imports
  - `core1201/` only uses `1.20.1` dependencies
  - `core1211/` only uses `1.21.1` dependencies
  - `fabric1201/` and `forge1201/` do not depend on `core1211`
  - `fabric1211/` and `neoforge1211/` do not depend on `core1201`

- [ ] **Step 4: Final commit**

  ```bash
  git add -A
  git commit -m "refactor: complete version-sliced shared code architecture"
  ```

---

## Success Criteria

The migration is successful when:

- no module mixes `1.20.1` and `1.21.1` dependencies
- pure common code lives once in `core/`
- Minecraft-bound shared code is shared only within one version line
- leaf modules are loader glue instead of second copies of the whole mod
- future feature work lands in one of three explicit places:
  - `core/` for pure logic
  - `core1201/` or `core1211/` for version-bound shared logic
  - a leaf module for loader-specific integration
