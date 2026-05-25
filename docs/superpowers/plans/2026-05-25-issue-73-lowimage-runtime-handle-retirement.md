# LowImage Runtime Handle Retirement Implementation Plan

> Issue: [#73](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/73)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the old LowImage execution path from the Kotlin/JNI runtime surface and from `RuxComputerHandle` boot/handoff APIs.

**Architecture:** Keep the Rust low-image decoder/runner and compiler artifacts for later compiler migration slices, but stop exposing them as a computer startup path. The runtime surface becomes BIOS-flash-only: JVM passes paths, Rust reads `bios.flash`, and execution uses the Rux16 CPU path.

**Tech Stack:** Kotlin/JVM 17, JNI, Rust 2021, Gradle sandbox wrapper, Cargo tests.

---

### Task 1: Add Architecture Tests For Removed Runtime Surface

**Files:**
- Modify: `modules/native-runtime/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/RuxComputerRuntimeFactoryTest.kt`
- Create: `native/rux-vm/tests/rux_computer_runtime_surface.rs`

- [ ] **Step 1: Replace old factory tests with BIOS-only API checks**

In `RuxComputerRuntimeFactoryTest.kt`, remove `defaultFirmwareResourceTargetsBiosFirmware`, `reportsMissingFirmwareResource`, and `createFactoryAcceptsStorage0PathParameter`. Add:

```kotlin
@Test
fun runtimeFactoryDoesNotExposeResourceOrImageStartup() {
    val methodNames = RuxComputerRuntimeFactory::class.java.methods.map { it.name }.toSet()

    assertFalse("createFromResource" in methodNames)
    assertFalse("loadFirmwareResource" in methodNames)
    val createMethods = RuxComputerRuntimeFactory::class.java.methods.filter { it.name == "create" }
    assertEquals(emptyList(), createMethods)
}
```

- [ ] **Step 2: Add Rust public-surface test**

Create `native/rux-vm/tests/rux_computer_runtime_surface.rs`:

```rust
use std::fs;
use std::path::Path;

#[test]
fn rux_computer_handle_source_does_not_expose_low_image_startup_or_handoff() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let handle_source = fs::read_to_string(manifest_dir.join("src/computer/handle.rs")).unwrap();
    let jni_source = fs::read_to_string(manifest_dir.join("src/jni.rs")).unwrap();

    assert!(!handle_source.contains("pub fn create("));
    assert!(!handle_source.contains("create_with_storage0_media"));
    assert!(!handle_source.contains("create_with_storage0_path"));
    assert!(!handle_source.contains("boot_handoff_ruxi_from_guest_ram"));
    assert!(!jni_source.contains("createLowImageNative"));
    assert!(!jni_source.contains("createRuxComputerNative"));
    assert!(!jni_source.contains("runRuxComputerUntilSignalNative"));
}
```

- [ ] **Step 3: Run tests and verify RED**

Run:

```bash
./gradlew-sandbox :native-runtime:test --tests '*RuxComputerRuntimeFactoryTest*'
cargo test --test rux_computer_runtime_surface --manifest-path native/rux-vm/Cargo.toml
```

Expected: both fail because the old LowImage/resource startup APIs still exist.

### Task 2: Remove Kotlin Runtime LowImage API

**Files:**
- Modify: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/RuxComputerRuntime.kt`
- Modify: `modules/native-runtime/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/RuxComputerRuntimeTest.kt`

- [ ] **Step 1: Replace LowImage signal naming**

In `NativeVmBindings.kt`, delete `NativeLowImageVmMetrics` and replace `NativeLowImageVmSignal` with:

```kotlin
sealed interface NativeRuxComputerSignal {
    data object Halt : NativeRuxComputerSignal
    data object Pause : NativeRuxComputerSignal

    companion object {
        fun from(values: LongArray): NativeRuxComputerSignal =
            when (val tag = values.getOrElse(0) { 0L }) {
                1L -> Halt
                6L -> Pause
                else -> error("Unknown native Rux computer signal tag: $tag")
            }
    }
}
```

- [ ] **Step 2: Delete direct LowImage and ByteArray startup functions**

Remove `createLowImage`, `runLowImageUntilSignal`, `lowImageMetrics`, `freeLowImage`, `createRuxComputer(image: ByteArray, ...)`, and `runRuxComputerUntilSignal`. Remove their private external JNI declarations.

- [ ] **Step 3: Make runtime bindings Rux16-only**

In `RuxComputerRuntime.kt`, make `RuxComputerRuntimeBindings.runUntilSignal` return `NativeRuxComputerSignal`, delete `NativeRuxComputerRuntimeBindings`, and keep `NativeRux16ComputerRuntimeBindings` as the only native binding. Remove `DEFAULT_FIRMWARE_RESOURCE`, `createFromResource`, `create(image: ByteArray, ...)`, and `loadFirmwareResource`.

- [ ] **Step 4: Update runtime tests**

In `RuxComputerRuntimeTest.kt`, change the fake binding method to:

```kotlin
override fun runUntilSignal(handle: Long): NativeRuxComputerSignal = NativeRuxComputerSignal.Pause
```

- [ ] **Step 5: Run Kotlin tests**

Run:

```bash
./gradlew-sandbox :native-runtime:test --tests '*RuxComputerRuntimeFactoryTest*' --tests '*RuxComputerRuntimeTest*'
```

Expected: pass.

### Task 3: Remove JNI LowImage Startup Functions

**Files:**
- Modify: `native/rux-vm/src/jni.rs`

- [ ] **Step 1: Delete old JNI functions and helpers**

Remove these functions from `jni.rs`:

```rust
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_createLowImageNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runLowImageUntilSignalNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_lowImageMetricsNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_freeLowImageNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_createRuxComputerNative
Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runRuxComputerUntilSignalNative
low_image_handle_mut
low_image_signal_values
```

Remove `decode_low_image`, `LowImageSignal`, and `LowImageVm` imports.

- [ ] **Step 2: Run JNI-adjacent verification**

Run:

```bash
cargo test --test rux_computer_runtime_surface --manifest-path native/rux-vm/Cargo.toml
./gradlew-sandbox :native-runtime:test
```

Expected: pass.

### Task 4: Remove RuxComputerHandle LowImage Startup And RUXI Handoff

**Files:**
- Modify: `native/rux-vm/src/computer/handle.rs`
- Modify: `native/rux-vm/src/computer/machine.rs`
- Modify: `native/rux-vm/tests/rux_computer.rs`

- [ ] **Step 1: Remove handle startup methods**

From `handle.rs`, delete `create`, `create_with_storage0_media`, `create_with_storage0_path`, `create_with_profile`, `run_until_signal`, and `boot_handoff_ruxi_from_guest_ram`. Remove `decode_image` and `LowImageSignal` imports.

- [ ] **Step 2: Remove machine RUXI handoff**

From `machine.rs`, delete `boot_handoff_ruxi_from_ram` and `boot_handoff_image_bytes`. Remove unused `BootHandoffError` variants `InvalidImage`, `ImageTooLarge`, and `MachineState` if no remaining code uses them.

- [ ] **Step 3: Convert handle tests to Rux16 BIOS flash**

In `rux_computer.rs`, remove helper functions that build RUXI images. Delete tests for direct LowImage boot and RUXI handoff rejection. Convert storage and Rux16 handoff tests to create the initial handle through `create_rux16_bios_flash` or `create_rux16_bios_flash_with_storage0_*`.

- [ ] **Step 4: Run Rust tests**

Run:

```bash
rustfmt native/rux-vm/src/computer/handle.rs native/rux-vm/src/computer/machine.rs native/rux-vm/src/jni.rs native/rux-vm/tests/rux_computer.rs native/rux-vm/tests/rux_computer_runtime_surface.rs
cargo test --test rux_computer --manifest-path native/rux-vm/Cargo.toml
cargo test --test rux_computer_runtime_surface --manifest-path native/rux-vm/Cargo.toml
```

Expected: pass.

### Task 5: Remove Obsolete NeoForge Resource Runtime Tests

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RuxFirmwareResourceTest.kt`

- [ ] **Step 1: Keep only BIOS flash resource coverage**

Delete tests that load `*.ruxi` resources and call `NativeVmBindings.createRuxComputer`. Keep `bundledRux16BiosFlashResourceExists`.

- [ ] **Step 2: Run NeoForge test**

Run:

```bash
./gradlew-sandbox :v1_21_1-neoforge:test --tests '*RuxFirmwareResourceTest*'
```

Expected: pass.

### Task 6: Final Verification And Commit

**Files:**
- All files touched above.

- [ ] **Step 1: Run broad verification**

Run:

```bash
cargo test --test rux_computer --manifest-path native/rux-vm/Cargo.toml
cargo test --test rux_computer_runtime_surface --manifest-path native/rux-vm/Cargo.toml
./gradlew-sandbox :native-runtime:test
./gradlew-sandbox :v1_21_1-common:test
./gradlew-sandbox :v1_21_1-neoforge:test
git diff --check
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/plans/2026-05-25-issue-73-lowimage-runtime-handle-retirement.md \
  modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt \
  modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/RuxComputerRuntime.kt \
  modules/native-runtime/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/RuxComputerRuntimeFactoryTest.kt \
  modules/native-runtime/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/RuxComputerRuntimeTest.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RuxFirmwareResourceTest.kt \
  native/rux-vm/src/computer/handle.rs \
  native/rux-vm/src/computer/machine.rs \
  native/rux-vm/src/jni.rs \
  native/rux-vm/tests/rux_computer.rs \
  native/rux-vm/tests/rux_computer_runtime_surface.rs
git commit -m "refactor(vm): retire LowImage runtime startup"
```

- [ ] **Step 3: Update #73**

Add a comment listing the committed slice, verification commands, and the remaining follow-up: compiler/ABI/docs/resource retirement.
