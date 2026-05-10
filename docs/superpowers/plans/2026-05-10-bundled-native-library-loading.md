# Bundled Native Library Loading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Package the Rust CKL VM native library into the mod jar when available, keep the explicit `ckl.vm.native.library` override for development, and mark untagged builds as short-hash snapshots.

**Architecture:** JVM native loading resolves an explicit system property first, then falls back to an OS/arch resource under `natives/<platform>/<library>`, extracts it to a hash-addressed cache file, and calls `System.load` on that file. Gradle builds a release native artifact for the current platform and contributes it to resources, while build logic computes an effective version based on whether `HEAD` is exactly tagged.

**Tech Stack:** Kotlin/JVM, Gradle Kotlin DSL precompiled script plugins, Cargo release builds, JNI dynamic libraries.

---

### Task 1: Native Library Locator

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeLibraryLocator.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeLibraryLocatorTest.kt`

- [ ] Write tests for platform normalization, property precedence, bundled resource extraction, and missing-resource fallback.
- [ ] Implement the locator with explicit property first, bundled resource second, and `java.io.tmpdir/compukterkraft/native/<platform>/<sha256>/<library>` extraction.
- [ ] Run `./gradlew :compiler:test --tests "*NativeLibraryLocatorTest" --rerun-tasks`.

### Task 2: Native Bindings Integration

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageComputerProgram.kt`

- [ ] Update `NativeVmBindings` to resolve the default native library through `NativeLibraryLocator`.
- [ ] Update `NativeImageVmRunner.fromSystemProperty()` into default resolution while preserving `fromLibraryPath`.
- [ ] Keep error text clear when no property and no bundled native is available.
- [ ] Run `./gradlew :compiler:test --tests "*NativeImageVmRunnerTest" --tests "*NativeVmBindingsImageOnlyTest" --rerun-tasks`.

### Task 3: Universal Jar Build Pipeline

**Files:**
- Modify: `build-scripts/src/main/kotlin/loom-runs-convention.gradle.kts`
- Modify: `build-scripts/src/main/kotlin/metadata.gradle.kts`
- Modify: `native/ckl-vm/Cargo.toml`

- [ ] Add Cargo release profile stripping/LTO settings.
- [ ] Add `buildRustVmNativeLibraryRelease` that runs `cargo build --release`.
- [ ] Add a generated resources directory containing the current platform native at `natives/<platform>/<library>`.
- [ ] Make production resources include generated natives, while dev run configs keep using debug property override.
- [ ] Run `./gradlew :v1_21_1-neoforge:processResources --rerun-tasks`.

### Task 4: Snapshot Versioning

**Files:**
- Modify: `build-scripts/src/main/kotlin/BuildLogicSupport.kt`
- Test: `build-scripts/src/test/kotlin/BuildVersionSupportTest.kt`

- [ ] Extract pure version helpers so tests can assert tag matching without shelling out to git.
- [ ] Compute root effective version as base version on exact `vX.Y.Z`/`X.Y.Z` tag and `X.Y.Z-S-<hash>` otherwise.
- [ ] Use effective version in `computeModVersion()` and `computeModArchiveVersion()`.
- [ ] Run `./gradlew :build-scripts:test --tests "*BuildVersionSupportTest" --rerun-tasks`.

### Task 5: Verification and Commit

**Files:**
- All changed files.

- [ ] Run `./gradlew :compiler:test`.
- [ ] Run `./gradlew :build-scripts:test`.
- [ ] Run `./gradlew :v1_21_1-neoforge:processResources`.
- [ ] Inspect generated resources for `natives/linux-x86_64/libckl_vm.so` on Linux.
- [ ] Commit with `feat: bundle native vm library`.
