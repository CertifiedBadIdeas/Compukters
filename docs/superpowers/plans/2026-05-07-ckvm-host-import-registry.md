# CkVm Host Import Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace temporary per-image host import ids with stable numeric host import ids and signatures for `CkVmImage`.

**Architecture:** Add a Kotlin `CkVmHostImportRegistry` beside the image backend. The registry maps CKL builtin module/function/signature triples to stable ids, validates uniqueness, and is used by `CkVmImageCompiler` when lowering `Instruction.CallBuiltin`. The Rust decoder remains unchanged because the image already carries import ids and signatures.

**Tech Stack:** Kotlin/JVM, Kotlin test, Gradle, existing `CkVmImageBackend`, existing Rust image fixture tests.

---

## File Structure

- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmHostImportRegistry.kt`
  - Stable host import descriptor table and lookup helpers.
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmHostImportRegistryTest.kt`
  - Stable id/signature tests and builtin coverage tests.
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
  - Use stable registry descriptors instead of per-image ids.
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`
  - Update `system::log` expectations to stable id/signature.
- Modify: `native/ckl-vm/tests/image_decode.rs`
  - Update backend fixture expectations for stable id bytes.
- Regenerate: `native/ckl-vm/tests/fixtures/backend-system-log.ckim`
  - New backend-generated fixture containing stable `system::log` id.

## Stable ID Scheme

Use reserved ranges by builtin module:

- `display`: `1000..1999`
- `filesystem`: `2000..2999`
- `system`: `3000..3999`
- `events`: `4000..4999`
- `ipc`: `5000..5999`
- `process`: `6000..6999`
- `strings`: `7000..7999`

The first implemented concrete ids must include:

- `display::present(Int): Unit = 1011`
- `system::log(String): Unit = 3004`

The registry should cover every module builtin in `LanguageBuiltins.defaultRuntimeRegistry` so unsupported host imports fail at registry-definition time rather than during backend lowering.

---

### Task 1: RED Registry and Backend Tests

**Files:**
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmHostImportRegistryTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt`

- [ ] **Step 1: Add registry tests**

Create `CkVmHostImportRegistryTest.kt` with tests asserting stable ids, unique ids, and coverage against `LanguageBuiltins.defaultRuntimeRegistry`.

- [ ] **Step 2: Update backend expectations**

Change `CkVmImageBackendTest.compileImageLowersSystemLogToConstantAndHostImport` to expect `CkVmHostImport(3004, "system", "log", listOf("String"), "Unit")` and code bytes containing little-endian `3004` (`188, 11, 0, 0`).

- [ ] **Step 3: Run RED**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmHostImportRegistryTest' --tests '*CkVmImageBackendTest' --rerun-tasks
```

Expected: FAIL with unresolved `CkVmHostImportRegistry` and/or backend expectation failure because the backend still emits per-image id `0` and parameter type `Any`.

- [ ] **Step 4: Commit RED tests**

Run:

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmHostImportRegistryTest.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt
git commit -m "test: add ckvm host import registry red tests"
```

---

### Task 2: GREEN Registry Implementation

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmHostImportRegistry.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`

- [ ] **Step 1: Add registry implementation**

Create `CkVmHostImportRegistry.kt` with explicit stable descriptors for all current module builtins.

- [ ] **Step 2: Use registry in backend**

Modify `CkVmImageCompiler.collectHostImports` to call `CkVmHostImportRegistry.require(...)`, deduplicate descriptors, and sort by stable id.

- [ ] **Step 3: Run GREEN**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmHostImportRegistryTest' --tests '*CkVmImageBackendTest' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 4: Commit registry implementation**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmHostImportRegistry.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmHostImportRegistryTest.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackendTest.kt
git commit -m "feat: add stable ckvm host import registry"
```

---

### Task 3: Regenerate Backend Fixture

**Files:**
- Modify: `native/ckl-vm/tests/image_decode.rs`
- Modify: `native/ckl-vm/tests/fixtures/backend-system-log.ckim`

- [ ] **Step 1: Regenerate fixture**

Run:

```bash
JAVA_TOOL_OPTIONS="-Dckl.image.backend.fixture.path=$PWD/native/ckl-vm/tests/fixtures/backend-system-log.ckim" ./gradlew :compiler:test --tests '*CkVmImageBackendTest.writesBackendFixtureWhenPathIsProvided' --rerun-tasks
```

Expected: PASS and fixture bytes change.

- [ ] **Step 2: Update Rust fixture expectation**

Change `decodes_backend_generated_system_log_fixture` expected host id/code bytes to stable id `3004`.

- [ ] **Step 3: Run Kotlin and Rust tests**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageBackendTest' --tests '*CkVmHostImportRegistryTest' --tests '*CkVmImageAbiTest' --rerun-tasks
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_decode -- --nocapture
```

Expected: both commands PASS.

- [ ] **Step 4: Commit fixture update**

Run:

```bash
git add native/ckl-vm/tests/fixtures/backend-system-log.ckim native/ckl-vm/tests/image_decode.rs
git commit -m "test: update ckvm backend fixture for stable host ids"
```

---

### Task 4: Final Verification

**Files:**
- Verify all changed files.

- [ ] **Step 1: Run focused tests**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageAbiTest' --tests '*CkVmImageBackendTest' --tests '*CkVmHostImportRegistryTest' --tests '*BytecodeAbiTest' --rerun-tasks
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_decode -- --nocapture
```

Expected: both commands PASS.

- [ ] **Step 2: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 3: Inspect status**

Run:

```bash
git status --short
```

Expected: clean status if every commit step was executed.

---

## Self-Review Notes

- This slice stabilizes host import ids in generated images.
- It does not add Rust execution for `CALL_HOST`.
- It does not replace `RuntimeHostBridge`; the registry mirrors current compiler/runtime builtin signatures for future image runner use.