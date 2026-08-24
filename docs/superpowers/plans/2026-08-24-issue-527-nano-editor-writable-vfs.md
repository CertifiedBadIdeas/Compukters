# Nano-Like Guest Editor and Writable Text API Implementation Plan

> Issue: [#527](https://github.com/CertifiedBadIdeas/Compukters/issues/527)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the playable `edit hello.kt -> kotlinc hello.kt -> hello` loop with a persistent nano-like Kotlin guest editor backed by a managed `CharArray` gap buffer.

**Architecture:** Rust keeps sole ownership of the terminal grid and persistent VFS. The artifact ABI gains bounded char-array-to-string materialization, K2 lowers the minimal primitive `CharArray` surface, and trusted Guest Kotlin facades expose existing filesystem mutations plus reusable positional terminal operations. `/rom/edit` remains an ordinary foreground Kotlin program.

**Tech Stack:** Kotlin 2.4 K2 IR, Compukter Artifact v1, Rust 2021 VM/verifier/GC, JDK 25 FFM, Gradle 9.7, Minecraft/NeoForge 26.1, Kotlin Test, Rust tests, GameTest.

---

### Task 1: Execute `StringFromCharArray` in the Rust VM

**Files:**
- Modify: `host/compukter-vm/src/artifact/mod.rs`
- Modify: `host/compukter-vm/src/decode/code.rs`
- Modify: `host/compukter-vm/src/verify/functions.rs`
- Modify: `host/compukter-vm/src/execution/image.rs`
- Modify: `host/compukter-vm/src/execution/machine.rs`
- Modify: `host/compukter-vm/src/execution/fixtures.rs`
- Modify: `host/compukter-vm/src/execution/text_tests.rs`
- Modify: `host/compukter-vm/src/test_encode.rs`

- [ ] **Step 1: Write failing execution and rejection tests**

Add fixtures that allocate `CharArray(5)`, store `['A', '\uD83D', '\uDE00', 'Z', '!']`, execute opcode `0x67 (dst,array,start,end)`, and return the resulting string. Assert exact UTF-16 for ranges `0..5` and `1..3`. Add negative, reversed, and past-end range cases expecting `GuestTrap::IndexOutOfBounds`, plus wrong-array-type admission rejection.

- [ ] **Step 2: Verify RED**

Run: `cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline string_from_char_array -- --nocapture`

Expected: FAIL because opcode `0x67` and `ResolvedInstruction::StringFromCharArray` do not exist.

- [ ] **Step 3: Implement the bounded instruction**

Add:

```rust
StringFromCharArray {
    dst: u16,
    array: u16,
    start: u16,
    end: u16,
}
```

Decode `0x67` with four registers. Verify `dst` is exact non-null standard String, `array` is exact non-null `CharArray`, and `start/end` are `I32`. Resolve the four operands in `ExecutionImage`. In `Machine`, validate the range before allocation, charge dynamic work in the same bounded chunks as substring, allocate one managed UTF-16 string through the existing GC retry path, copy exact `u16` elements, and publish `dst` only after success. Add encoder support in the Rust test encoder.

- [ ] **Step 4: Verify GREEN**

Run: `cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline`

Expected: PASS with exact UTF-16 preservation and deterministic rejection.

- [ ] **Step 5: Commit the VM change**

```bash
git -C host/compukter-vm add src
git -C host/compukter-vm commit -m "feat(text): materialize strings from char arrays (#527)"
```

### Task 2: Encode array length and char-array strings in compiler artifacts

**Files:**
- Modify: `modules/compiler-artifact/src/main/kotlin/ru/lazyhat/compukters/compiler/artifact/model/Instruction.kt`
- Modify: `modules/compiler-artifact/src/main/kotlin/ru/lazyhat/compukters/compiler/artifact/write/InstructionEncoder.kt`
- Modify: `modules/compiler-artifact/src/main/kotlin/ru/lazyhat/compukters/compiler/artifact/write/ArtifactValidator.kt`
- Modify: `modules/compiler-artifact/src/test/kotlin/ru/lazyhat/compukters/compiler/artifact/write/InstructionEncoderTest.kt`
- Modify: `modules/compiler-artifact/src/test/kotlin/ru/lazyhat/compukters/compiler/artifact/write/ArtifactValidatorTest.kt`
- Modify: `modules/compiler-artifact/src/test/kotlin/ru/lazyhat/compukters/compiler/artifact/write/ExecutableInstructionsConformanceTest.kt`

- [ ] **Step 1: Write failing model/encoding tests**

Require these public model shapes and exact encodings:

```kotlin
Instruction.ArrayLength(r1, r2) // 0x32, dst, array
Instruction.StringFromCharArray(r1, r2, r3, r4) // 0x67, dst, array, start, end
```

Expected canonical frames are `32 00 08 00 01 00 02 00` and `67 00 0c 00 01 00 02 00 03 00 04 00`. Validator tests require initialized sources, forbid destination alias publication before allocation, and require an allocation instruction to begin its block.

- [ ] **Step 2: Verify RED**

Run: `./gradlew-sandbox-dev-parallel :compiler-artifact:test --tests '*InstructionEncoderTest*' --tests '*ArtifactValidatorTest*'`

Expected: compilation FAIL because the two model instructions are absent.

- [ ] **Step 3: Add model, encoder, cost, and validator support**

Encode ArrayLength as fixed-cost opcode `0x32`. Encode StringFromCharArray as allocation opcode `0x67`; its fixed cost is one and runtime charges copied units. Include source registers, destination register, and allocation-block classification in every exhaustive validator branch.

- [ ] **Step 4: Verify GREEN and cross-language conformance**

Run: `./gradlew-sandbox-dev-parallel :compiler-artifact:test`

Expected: PASS and generated bytes accepted by the embedded VM fixture tests.

- [ ] **Step 5: Commit**

```bash
git add modules/compiler-artifact host/compukter-vm
git commit -m "feat(artifact): encode primitive array text operations (#527)"
```

### Task 3: Lower the minimal Kotlin `CharArray` surface

**Files:**
- Modify: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/KotlinProjectLowering.kt`
- Modify: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt`

- [ ] **Step 1: Add a failing compiler-produced conformance test**

Compile and execute:

```kotlin
fun main(): String {
    val value = CharArray(5)
    value[0] = 'A'
    value[1] = '\uD83D'
    value[2] = '\uDE00'
    value[3] = 'Z'
    return value.concatToString(0, 4)
}
```

Assert deterministic artifact bytes contain NewArray, ArrayStore, ArrayLoad/ArrayLength where exercised, and StringFromCharArray, then assert the VM returns UTF-16 `A😀Z`.

- [ ] **Step 2: Verify RED**

Run: `./gradlew-sandbox-dev-parallel :compiler-k2:test --tests '*MinimalScriptLoweringTest*char array*'`

Expected: FAIL with unsupported function signature/value type or unsupported call target.

- [ ] **Step 3: Extend the canonical Kotlin library model**

Export canonical `kotlin.String` and `kotlin.CharArray` nominal types from the generated library module and import both into the app module. Thread `charArrayType` and K2's `charArray` IR type through `valueType`, signature validation, function compilation, and register allocation.

- [ ] **Step 4: Lower array built-ins**

Recognize only exact Kotlin built-ins: `CharArray(size)` -> allocation block plus NewArray; `.size` -> ArrayLength; `get` -> ArrayLoad; `set` -> ArrayStore/Unit; `concatToString(start,end)` -> allocation block plus StringFromCharArray. Reject spoofed project declarations with the same names.

- [ ] **Step 5: Verify GREEN and commit**

Run: `./gradlew-sandbox-dev-parallel :compiler-k2:test --tests '*MinimalScriptLoweringTest*'`

```bash
git add modules/compiler-k2
git commit -m "feat(compiler): lower primitive CharArray operations (#527)"
```

### Task 4: Expose positional terminal operations to Guest Kotlin

**Files:**
- Modify: `host/compukter-vm/src/terminal/state.rs`
- Modify: `host/compukter-vm/src/computer.rs`
- Modify: `host/compukter-vm/tests/terminal_device.rs`
- Modify: `modules/guest-api-core/src/main/kotlin/compukter/terminal/Terminal.kt`
- Modify: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/TrustedIntrinsicRegistry.kt`
- Modify: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/TrustedIntrinsicRegistryTest.kt`

- [ ] **Step 1: Write failing Rust capability tests**

Drive a real `ComputerMachine` artifact that calls terminal operations 9-13: set cursor, set visibility, set colors, writeAt, and fill. Assert writeAt clips at x=50 without wrapping, fill retains cursor, supplementary scalars occupy one cell, and invalid rectangles/palette values fail without partial grid mutation.

- [ ] **Step 2: Verify RED**

Run: `cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline positional_terminal`

Expected: FAIL because the operation schemas stop at operation 8.

- [ ] **Step 3: Implement generic Rust operations**

Add a `TerminalDevice::write_at(position, units)` method that decodes UTF-16 scalars, clips to the remaining row, builds bounded cells with current colors, and calls `patch` without moving cursor. Extend `RawTerminalOperation`, schemas, request copying, and handling for cursor, visibility, colors, writeAt, and fill.

- [ ] **Step 4: Add Guest declarations and trusted lowering tests**

Expose the accepted signatures from the spec. Raise terminal capability operation count from 9 to 14 and map only symbols originating in `compukter.terminal-api@1` to operations 9-13.

- [ ] **Step 5: Verify GREEN and commit both repositories**

Run: `cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline terminal`

Run: `./gradlew-sandbox-dev-parallel :compiler-k2:test :guest-api-core:check`

```bash
git -C host/compukter-vm add src tests
git -C host/compukter-vm commit -m "feat(terminal): expose positional guest drawing (#527)"
git add host/compukter-vm modules/guest-api-core modules/compiler-k2
git commit -m "feat(guest): add positional terminal facade (#527)"
```

### Task 5: Expose bounded text reads and writes

**Files:**
- Modify: `modules/guest-api-core/src/main/kotlin/compukter/filesystem/FileSystem.kt`
- Modify: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/TrustedIntrinsicRegistry.kt`
- Modify: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/TrustedIntrinsicRegistryTest.kt`
- Modify: `host/compukter-vm/src/computer.rs`

- [ ] **Step 1: Write failing trusted-symbol and machine tests**

Assert the exact bundled declarations lower `readText(String): String` to operation 2 and `writeText(String,String): Int` to operation 3; same-named user declarations do not. Drive create, replace, `/rom` denial, quota failure, UTF-8, and previous-file preservation through real machine calls.

- [ ] **Step 2: Verify RED**

Run: `./gradlew-sandbox-dev-parallel :compiler-k2:test --tests '*TrustedIntrinsicRegistryTest*filesystem*'`

Expected: FAIL because the facade and registry expose only stat/list.

- [ ] **Step 3: Add declarations and mappings**

Keep both calls synchronous. Reuse the already-published Rust operation schemas and stable negative write status; do not add FFM or host path APIs.

- [ ] **Step 4: Verify GREEN and commit**

Run: `./gradlew-sandbox-dev-parallel :compiler-k2:test :guest-api-core:check`

Run: `cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline filesystem`

```bash
git add modules/guest-api-core modules/compiler-k2
git commit -m "feat(filesystem): expose bounded guest text mutations (#527)"
```

### Task 6: Build the nano-like Kotlin editor

**Files:**
- Create: `system/programs/edit.kt`
- Modify: `modules/compiler-k2/build.gradle.kts`
- Modify: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt`

- [ ] **Step 1: Write failing pure buffer tests**

Compile `edit.kt` into the JVM test source set and test exported helpers with a 16-unit buffer: insertion at the gap, scalar-aware left/right/delete for `😀`, LF splitting, four-space Tab, inherited leading indentation, horizontal viewport adjustment, compaction, and exact `concatToString` output. Test full-capacity insertion leaves the buffer unchanged.

- [ ] **Step 2: Verify RED**

Run: `./gradlew-sandbox-dev-parallel :compiler-k2:test --tests '*MinimalScriptLoweringTest*editor*'`

Expected: compilation FAIL because `edit.kt` and its helpers do not exist.

- [ ] **Step 3: Implement `CharArray` gap-buffer helpers**

Use fixed capacity 4096, `gapStart/gapEnd`, scalar-aware boundary helpers, LF scans for line/column, and viewport offsets. Keep helpers top-level and non-suspending; `main` is the only suspend event loop so #525 is not required.

- [ ] **Step 4: Implement the 51x19 TUI and lifecycle**

Parse exactly one command-line path; resolve relatives under `/home`; use stat then readText; normalize CRLF; render title/body/status/shortcut rows with positional terminal calls; handle accepted keys and modifiers; save with compact+concatToString+writeText; implement Ctrl+X Y/N/Escape; always finish the active event.

- [ ] **Step 5: Compile twice and verify deterministic artifacts**

Add `generateEditArtifact`, output `build/generated/system/edit.cpkt`, and a test that compiles checked-in source twice, compares bytes, verifies it, and writes the optional task output property.

Run: `./gradlew-sandbox-dev-parallel :compiler-k2:generateEditArtifact :compiler-k2:test`

Expected: PASS and a non-empty deterministic verified artifact.

- [ ] **Step 6: Commit**

```bash
git add system/programs/edit.kt modules/compiler-k2
git commit -m "feat(editor): add nano-like Kotlin guest program (#527)"
```

### Task 7: Package `/rom/edit` and complete the shell loop

**Files:**
- Modify: `system/programs/shell.kt`
- Modify: `modules/v26_1/v26_1-common/build.gradle.kts`
- Modify: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/SystemProgramImage.kt`
- Modify: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/SystemRomImage.kt`
- Modify: `modules/v26_1/v26_1-common/src/test/kotlin/ru/lazyhat/compukters/minecraft/computer/SystemRomImageTest.kt`
- Modify: `modules/core/build.gradle.kts`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukters/core/device/runtime/program/integration/ProgramRuntimeHostIntegrationTest.kt`

- [ ] **Step 1: Write failing ROM and runtime tests**

Require deterministic `/rom/edit` executable metadata and resource loading. Start real boot/shell, submit `edit hello.kt`, type `import compukter.terminal.Terminal` plus LF and `fun main() { Terminal.write("hi\\n") }`, save/exit, run `kotlinc hello.kt`, then `hello`, and assert terminal output contains `hi`.

- [ ] **Step 2: Verify RED**

Run: `./gradlew-sandbox-dev-parallel :v26_1-common:test :core:test --tests '*ProgramRuntimeHostIntegrationTest*'`

Expected: FAIL because `/rom/edit` is absent.

- [ ] **Step 3: Package and invoke the artifact**

Wire `generateEditArtifact` into common resources as `/system/programs/edit`, load it in `SystemProgramImage`, include it in sorted ROM encoding, update every exact ROM fixture, and add `edit` to shell help. Existing `/home` then `/rom` lookup remains unchanged.

- [ ] **Step 4: Verify GREEN and commit**

Run: `./gradlew-sandbox-dev-parallel :v26_1-common:test :core:test`

```bash
git add system/programs/shell.kt modules/core modules/v26_1/v26_1-common
git commit -m "feat(system): package the guest editor in ROM (#527)"
```

### Task 8: Persistence, GameTest, documentation, and final verification

**Files:**
- Modify: `modules/v26_1/v26_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukters/impl/computer/ComputerBlockGameTest.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/CompuktersModNativeBootstrapTest.kt`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/superpowers/plans/2026-08-24-issue-527-nano-editor-writable-vfs.md`

- [ ] **Step 1: Add a failing persistent programming-loop GameTest**

Drive raw events through the existing lifecycle GameTest: open editor, enter/save source, compile, execute its marker, reboot/reload, `stat` both source and executable, execute again, and prove a second computer cannot see either file.

- [ ] **Step 2: Verify RED then GREEN**

Run the focused JVM/GameTest fixture tests first; expected RED is missing edit resource/flow. Complete resource assertions and deterministic fixtures, then run:

```bash
./gradlew-sandbox-dev-parallel verifyLocalFast
cargo fmt --manifest-path host/compukter-vm/Cargo.toml --all -- --check
cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline
./gradlew-sandbox-dev-parallel :v26_1-neoforge:buildProductionUniversalJar
```

Expected: every command passes; the production JAR contains `/system/programs/edit`; both repositories are clean; `git diff --check` passes outside verbatim third-party license material.

- [ ] **Step 3: Document and commit**

Document `FileSystem.readText/writeText`, positional terminal ownership, managed CharArray gap-buffer, `/rom/edit`, controls, limits, and the completed programming loop. Mark every completed plan checkbox.

```bash
git add modules/v26_1/v26_1-neoforge docs
git commit -m "test(editor): verify the persistent programming loop (#527)"
```
