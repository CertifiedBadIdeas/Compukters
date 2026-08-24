# Bounded Kotlin `when` Implementation Plan

> Issue: [#528](https://github.com/CertifiedBadIdeas/Compukters/issues/528)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the initial `IrWhen` path into a documented, compiler-tested, and VM-executed Kotlin subset for small deterministic dispatches.

**Architecture:** Keep a linear branch chain over existing equality, `Branch`, `Move`, and `Jump` instructions. Validate the K2 shapes, correct control-flow joins where necessary, and prove behavior with a compiler-produced artifact; do not add switch bytecode.

**Tech Stack:** Kotlin 2.4 K2 IR, Compukter artifact writer/validator, Rust Compukter-VM harness, Gradle.

---

### Task 1: Characterize the source contract

**Files:**
- Modify: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt`

- [ ] **Step 1: Add admitted-form tests**

Compile subject-based statement and expression forms for every admitted type:

```kotlin
fun classifyInt(value: Int): String = when (value) {
    1 -> "one"
    else -> "other"
}

fun classifyChar(value: Char): Int = when (value) {
    'x' -> 1
    else -> 0
}

fun classifyBool(value: Boolean): String = when (value) {
    true -> "yes"
    else -> "no"
}

fun classifyString(value: String): Int = when (value) {
    "run" -> 1
    else -> 0
}
```

Add subjectless statement/value cases with mutable scalar counters so later tests can prove source order and first-match semantics.

- [ ] **Step 2: Add unsupported-boundary tests**

Compile `in 1..3`, `is String`, and comma-separated branch conditions. Assert no artifact. When the Kotlin frontend rejects first, assert a compiler error; when target lowering rejects, assert:

```kotlin
assertTrue(result.diagnostics.any {
    it.category == DiagnosticCategory.TARGET && it.code == "UNSUPPORTED_IR"
})
```

- [ ] **Step 3: Run the focused tests**

```bash
./gradlew-sandbox :compiler-k2:test --tests 'ru.lazyhat.compukters.compiler.worker.k2.MinimalScriptLoweringTest.*when*'
```

Expected: at least one admitted form or explicit boundary exposes the incomplete contract. If all admitted forms already pass, retain the tests as characterization and do not manufacture a production change; Task 3 remains the runtime proof.

### Task 2: Make branch-chain lowering exact

**Files:**
- Modify if Task 1 exposes a failure: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/KotlinProjectLowering.kt`
- Test: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt`

- [ ] **Step 1: Correct statement control flow**

Use an ordinary join block and jump only from non-terminated bodies:

```kotlin
private fun compileWhenStatement(expression: IrWhen) {
    val join = createBlock()
    // Emit source-ordered condition/body/otherwise blocks.
    // Compile final else directly; non-terminated bodies jump to join.
    currentBlock = join
}
```

The join is not a loop header and must not add a loop safepoint.

- [ ] **Step 2: Correct expression initialization**

Allocate one typed destination register. Move each selected branch value into it and route every non-terminated branch to the join. Preserve final `else` so artifact data-flow sees the register initialized on every reachable path.

- [ ] **Step 3: Preserve unsupported boundaries**

Accept canonical K2 equality branches and subjectless Boolean conditions only. Let unsupported operators reach the existing `UnsupportedKotlinIr` conversion so no partially correct artifact is emitted.

- [ ] **Step 4: Run and commit compiler tests**

```bash
./gradlew-sandbox :compiler-k2:test --tests 'ru.lazyhat.compukters.compiler.worker.k2.MinimalScriptLoweringTest.*when*'
git add modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/KotlinProjectLowering.kt modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt
git commit -m "feat(language): define bounded Kotlin when lowering (#528)"
```

If production lowering required no change, omit that path from `git add` and use commit subject `test(language): define bounded Kotlin when contract (#528)`.

### Task 3: Prove source-order behavior on the Rust VM

**Files:**
- Modify: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt`
- Modify: `modules/compiler-k2/build.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `modules/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs`

- [ ] **Step 1: Generate a deterministic conformance artifact**

Use `when` after the suspend helper from #525:

```kotlin
import compukter.terminal.Terminal

suspend fun key(): Int {
    Terminal.awaitEvent()
    return Terminal.eventKey()
}

suspend fun main() {
    val text = when (key()) {
        13 -> "enter"
        27 -> "escape"
        else -> "other"
    }
    Terminal.write(text)
}
```

Compile twice, compare artifact bytes, and publish them through `compukter.vm.whenArtifact`.

- [ ] **Step 2: Add isolated Gradle tasks**

Register `generateWhenConformanceArtifact` in `modules/compiler-k2/build.gradle.kts`. Register root `testKotlinWhenVmConformance`, depend on the generator, and pass `COMPUKTER_KOTLIN_WHEN_ARTIFACT` to the Rust harness.

- [ ] **Step 3: Execute matched and fallback paths**

Add a Rust helper that starts a fresh session, resumes the event request with a key, captures terminal write argument 0, and runs to halt:

```rust
assert_eq!(utf16("enter"), execute_when_artifact(&bytes, 13));
assert_eq!(utf16("other"), execute_when_artifact(&bytes, 99));
```

- [ ] **Step 4: Run and commit vertical conformance**

```bash
./gradlew-sandbox testKotlinWhenVmConformance
git add modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt modules/compiler-k2/build.gradle.kts build.gradle.kts modules/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs
git commit -m "test(language): execute Kotlin when on the pinned VM (#528)"
```

Expected: `BUILD SUCCESSFUL` with exact output for both executions.

### Task 4: Use `when` in the guest shell

**Files:**
- Modify: `system/programs/shell.kt`
- Test: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt`
- Test: `modules/native-runtime/src/test/kotlin/ru/lazyhat/compukters/lang/runtime/integration/ShellProgram.kt`

- [ ] **Step 1: Replace repeated dispatch comparisons**

Use subject-based `when` where shell code compares one command or key repeatedly, and subjectless `when` for ordered predicates. Preserve exact output, errors, and event behavior.

- [ ] **Step 2: Run shell tests**

```bash
./gradlew-sandbox :compiler-k2:test --tests '*checked in shell compiles deterministically*' :native-runtime:test --tests '*ShellProgram*'
```

Expected: PASS with unchanged shell behavior.

- [ ] **Step 3: Commit the system-program use**

```bash
git add system/programs/shell.kt modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt modules/native-runtime/src/test/kotlin/ru/lazyhat/compukters/lang/runtime/integration/ShellProgram.kt
git commit -m "refactor(shell): use bounded Kotlin when dispatch (#528)"
```

### Task 5: Verify the complete change

**Files:**
- Verify only

- [ ] **Step 1: Run compiler and vertical conformance**

```bash
./gradlew-sandbox :compiler-k2:test testKotlinWhenVmConformance testKotlinSuspendCallVmConformance
```

- [ ] **Step 2: Run fast repository verification**

```bash
./gradlew-sandbox --parallel verifyLocalFast
```

Expected: both Gradle invocations report `BUILD SUCCESSFUL`.

- [ ] **Step 3: Confirm clean boundaries**

```bash
git -C host/compukter-vm status --short
git status --short
```

Expected: both outputs empty after commits.

