# Rux Computer Mod MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Boot a bundled Rux `RUXI` firmware image on Rust `ComputerMachine` from the mod runtime path, with fail-fast native execution and no Kotlin fallback for Rux firmware.

**Architecture:** Add a Rust `RuxComputerHandle` wrapper around `ComputerMachine` so JNI can expose computer-level state instead of raw `LowImageVm` state. Add Kotlin bindings and tests for the new handle, then add a bundled `.ruxi` firmware resource path that can be loaded by mod/runtime tests. Keep `RUXI` image ABI v1 unchanged and treat legacy CKL/Kotlin VM removal as follow-up slices after the Rux boot path is covered.

**Tech Stack:** Rust 2021, Cargo tests, JNI, Kotlin/JVM, Gradle Kotlin DSL, kotlin.test, existing `rux-vm`, existing `rux-compiler`, existing native library packaging.

---

## File Structure

- Create `native/rux-vm/src/rux_computer.rs`
  - Owns `RuxComputerHandle`.
  - Decodes `RUXI` bytes, creates `ComputerMachine`, spawns boot CPU, runs slices, exposes control/debug state.
- Modify `native/rux-vm/src/lib.rs`
  - Exports `rux_computer`.
- Create `native/rux-vm/tests/rux_computer.rs`
  - Rust tests for the new handle without JNI.
- Modify `native/rux-vm/src/jni.rs`
  - Adds JNI functions for creating, running, inspecting, and freeing Rux computer handles.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
  - Adds Kotlin wrapper API for Rux computer handles.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`
  - Adds JNI integration coverage when `rux.vm.native.library` is configured.
- Create `native/rux-compiler/src/bin/rux-emit.rs`
  - Emits `.ruxi` image bytes from a `.rx` source file for build-time/resource use.
- Create `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/rux-terminal.ruxi`
  - Bundled MVP firmware image.
- Create `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RuxFirmwareResourceTest.kt`
  - Verifies the bundled Rux firmware resource exists and decodes through native bindings when configured.
- Create `docs/superpowers/todos/2026-05-15-rux-legacy-vm-retirement-audit.md`
  - Records concrete legacy/fallback entry points to remove after the boot slice is working.

---

### Task 1: Rust Rux Computer Handle

**Files:**
- Create: `native/rux-vm/src/rux_computer.rs`
- Modify: `native/rux-vm/src/lib.rs`
- Create: `native/rux-vm/tests/rux_computer.rs`

- [ ] **Step 1: Write the failing Rust handle test**

Create `native/rux-vm/tests/rux_computer.rs`:

```rust
use rux_vm::computer_machine::ComputerMachine;
use rux_vm::low_image::{encode_image, Function, Image, Instruction};
use rux_vm::low_image_runner::LowImageSignal;
use rux_vm::rux_computer::{RuxComputerControl, RuxComputerHandle};

fn terminal_firmware_image() -> Vec<u8> {
    let image = Image {
        memory_size: 64 * 1024,
        entry_function_index: 0,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        functions: vec![Function {
            name: "main".to_string(),
            register_count: 4,
            parameters: Vec::new(),
            instructions: vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::CONTROL_STATUS,
                },
                Instruction::I32Const {
                    dst: 1,
                    value: ComputerMachine::STATUS_READY,
                },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::DEBUG_WRITE,
                },
                Instruction::I32Const { dst: 1, value: 82 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::I32Const { dst: 1, value: 85 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::I32Const { dst: 1, value: 88 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::I32Const { dst: 2, value: 0 },
                Instruction::ReturnI32 { src: 2 },
            ],
        }],
    };
    encode_image(&image).expect("test image encodes")
}

#[test]
fn rux_computer_handle_boots_firmware_and_exposes_machine_state() {
    let image = terminal_firmware_image();
    let mut handle = RuxComputerHandle::create(&image, 64 * 1024, 1_000_000)
        .expect("computer handle creates");

    assert_eq!(handle.run_until_signal().unwrap(), LowImageSignal::HaltI32(0));
    assert_eq!(handle.debug_output_bytes(), b"RUX");
    assert_eq!(
        handle.control(),
        RuxComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 0,
        },
    );
}

#[test]
fn rux_computer_handle_fails_when_memory_is_too_small() {
    let image = terminal_firmware_image();
    let error = RuxComputerHandle::create(&image, 128, 1_000_000).unwrap_err();

    assert!(
        error.contains("image requires"),
        "unexpected error: {error}",
    );
}
```

- [ ] **Step 2: Run the Rust test to verify it fails**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml --test rux_computer
```

Expected: FAIL because `rux_vm::rux_computer` does not exist.

- [ ] **Step 3: Implement the handle module**

Create `native/rux-vm/src/rux_computer.rs`:

```rust
use crate::computer_machine::{ComputerMachine, CpuId};
use crate::low_image::decode_image;
use crate::low_image_runner::LowImageSignal;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RuxComputerControl {
    pub status: i32,
    pub exit_code: i32,
    pub panic_code: i32,
}

pub struct RuxComputerHandle {
    machine: ComputerMachine,
    boot_cpu: CpuId,
}

impl RuxComputerHandle {
    pub fn create(
        image_bytes: &[u8],
        memory_size: usize,
        slice_budget_nanos: u64,
    ) -> Result<Self, String> {
        let image = decode_image(image_bytes).map_err(|error| error.to_string())?;
        let mut machine = ComputerMachine::new(memory_size).map_err(|error| error.to_string())?;
        let boot_cpu = machine.spawn_boot_cpu(image, slice_budget_nanos.max(1))?;
        Ok(Self { machine, boot_cpu })
    }

    pub fn run_until_signal(&mut self) -> Result<LowImageSignal, String> {
        self.machine.run_boot_cpu_until_signal(self.boot_cpu)
    }

    pub fn control(&self) -> RuxComputerControl {
        RuxComputerControl {
            status: self.machine.control_status(),
            exit_code: self.machine.exit_code(),
            panic_code: self.machine.panic_code(),
        }
    }

    pub fn debug_output_bytes(&self) -> &[u8] {
        self.machine.debug_output_bytes()
    }
}
```

Modify `native/rux-vm/src/lib.rs`:

```rust
pub mod rux_computer;
```

- [ ] **Step 4: Run the Rust test to verify it passes**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml --test rux_computer
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```bash
git add native/rux-vm/src/rux_computer.rs native/rux-vm/src/lib.rs native/rux-vm/tests/rux_computer.rs
git commit -m "feat: add rux computer native handle"
```

---

### Task 2: JNI Functions For Rux Computer Handles

**Files:**
- Modify: `native/rux-vm/src/jni.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [ ] **Step 1: Add the failing Kotlin JNI test**

Append to `NativeImageVmBindingsJniTest.kt`:

```kotlin
@Test
fun ruxComputerBootsLowImageAndExposesControlAndDebugWhenLibraryIsConfigured() {
    val libraryPath = System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
    val image =
        RuxLowVmImage(
            memorySize = 64u * 1024u,
            entryFunctionIndex = 0,
            functions =
                listOf(
                    RuxLowVmFunction(
                        name = "main",
                        registerCount = 4,
                        parameters = emptyList(),
                        instructions =
                            listOf(
                                RuxLowVmInstruction.AddrConst(dst = 0, value = 0x1000_0000u),
                                RuxLowVmInstruction.I32Const(dst = 1, value = 2),
                                RuxLowVmInstruction.Store32(addr = 0, src = 1),
                                RuxLowVmInstruction.AddrConst(dst = 0, value = 0x1000_0100u),
                                RuxLowVmInstruction.I32Const(dst = 1, value = 'R'.code),
                                RuxLowVmInstruction.Store32(addr = 0, src = 1),
                                RuxLowVmInstruction.I32Const(dst = 1, value = 'U'.code),
                                RuxLowVmInstruction.Store32(addr = 0, src = 1),
                                RuxLowVmInstruction.I32Const(dst = 1, value = 'X'.code),
                                RuxLowVmInstruction.Store32(addr = 0, src = 1),
                                RuxLowVmInstruction.I32Const(dst = 2, value = 0),
                                RuxLowVmInstruction.ReturnI32(2),
                            ),
                    ),
                ),
        )
    val handle =
        NativeVmBindings.createRuxComputer(
            libraryPath = libraryPath,
            image = RuxLowVmImageAbi.encode(image),
            memorySize = 64 * 1024,
            sliceBudgetNanos = 1_000_000,
        )

    try {
        assertEquals(NativeLowImageVmSignal.HaltI32(0), NativeVmBindings.runRuxComputerUntilSignal(handle))
        assertEquals("RUX", NativeVmBindings.ruxComputerDebugOutput(handle).decodeToString())
        assertEquals(
            NativeRuxComputerControl(status = 3, exitCode = 0, panicCode = 0),
            NativeVmBindings.ruxComputerControl(handle),
        )
    } finally {
        NativeVmBindings.freeRuxComputer(handle)
    }
}
```

- [ ] **Step 2: Run the Kotlin test to verify it fails**

Run:

```bash
./gradlew :compiler:compileTestKotlin
```

Expected: FAIL because `NativeRuxComputerControl` and Rux computer binding methods do not exist.

- [ ] **Step 3: Add Kotlin binding surface**

Modify `NativeVmBindings.kt` near `NativeLowImageVmMetrics`:

```kotlin
data class NativeRuxComputerControl(
    val status: Int,
    val exitCode: Int,
    val panicCode: Int,
) {
    companion object {
        fun from(values: LongArray): NativeRuxComputerControl =
            NativeRuxComputerControl(
                status = values.getOrElse(0) { 0L }.toInt(),
                exitCode = values.getOrElse(1) { 0L }.toInt(),
                panicCode = values.getOrElse(2) { 0L }.toInt(),
            )
    }
}
```

Add public methods to `object NativeVmBindings`:

```kotlin
fun createRuxComputer(
    libraryPath: String,
    image: ByteArray,
    memorySize: Int,
    sliceBudgetNanos: Long,
): Long {
    load(libraryPath)
    val handle =
        createRuxComputerNative(
            image,
            memorySize.coerceAtLeast(1),
            sliceBudgetNanos.coerceAtLeast(1),
        )
    check(handle != 0L) { "Native Rux computer create returned a zero handle" }
    return handle
}

fun runRuxComputerUntilSignal(handle: Long): NativeLowImageVmSignal {
    require(handle != 0L) { "Native Rux computer handle is zero" }
    return NativeLowImageVmSignal.from(runRuxComputerUntilSignalNative(handle))
}

fun ruxComputerControl(handle: Long): NativeRuxComputerControl {
    require(handle != 0L) { "Native Rux computer handle is zero" }
    return NativeRuxComputerControl.from(ruxComputerControlNative(handle))
}

fun ruxComputerDebugOutput(handle: Long): ByteArray {
    require(handle != 0L) { "Native Rux computer handle is zero" }
    return ruxComputerDebugOutputNative(handle)
}

fun freeRuxComputer(handle: Long) {
    if (handle != 0L) {
        freeRuxComputerNative(handle)
    }
}
```

Add private external methods near the existing low-image native declarations:

```kotlin
@JvmStatic
private external fun createRuxComputerNative(
    image: ByteArray,
    memorySize: Int,
    sliceBudgetNanos: Long,
): Long

@JvmStatic
private external fun runRuxComputerUntilSignalNative(handle: Long): LongArray

@JvmStatic
private external fun ruxComputerControlNative(handle: Long): LongArray

@JvmStatic
private external fun ruxComputerDebugOutputNative(handle: Long): ByteArray

@JvmStatic
private external fun freeRuxComputerNative(handle: Long)
```

- [ ] **Step 4: Add Rust JNI functions**

Modify `native/rux-vm/src/jni.rs` imports:

```rust
use crate::rux_computer::RuxComputerHandle;
```

Add JNI functions after the low image JNI block:

```rust
#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_createRuxComputerNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    image: JByteArray<'_>,
    memory_size: jint,
    slice_budget_nanos: jlong,
) -> jlong {
    let image = match env.convert_byte_array(&image) {
        Ok(image) => image,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("Cannot read Rux computer image: {error}"),
            );
            return 0;
        }
    };
    match RuxComputerHandle::create(
        &image,
        memory_size.max(1) as usize,
        slice_budget_nanos.max(1) as u64,
    ) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runRuxComputerUntilSignalNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlongArray {
    let handle = match rux_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    let signal = match handle.run_until_signal() {
        Ok(signal) => signal,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            return null_mut();
        }
    };
    long_array_or_throw(&mut env, &low_image_signal_values(signal))
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_ruxComputerControlNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlongArray {
    let handle = match rux_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    let control = handle.control();
    long_array_or_throw(
        &mut env,
        &[
            i64::from(control.status),
            i64::from(control.exit_code),
            i64::from(control.panic_code),
        ],
    )
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_ruxComputerDebugOutputNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    let handle = match rux_computer_handle_mut(&mut env, handle) {
        Some(handle) => handle,
        None => return null_mut(),
    };
    byte_array_or_throw(&mut env, handle.debug_output_bytes())
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_freeRuxComputerNative(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    if handle != 0 {
        unsafe { drop(Box::from_raw(handle as *mut RuxComputerHandle)) };
    }
}
```

Add helper near the existing handle helpers:

```rust
fn rux_computer_handle_mut<'env>(
    env: &mut JNIEnv<'env>,
    handle: jlong,
) -> Option<&'env mut RuxComputerHandle> {
    if handle == 0 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Native Rux computer handle is zero");
        None
    } else {
        Some(unsafe { &mut *(handle as *mut RuxComputerHandle) })
    }
}
```

- [ ] **Step 5: Run focused Rust and Kotlin compile checks**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml --lib
./gradlew :compiler:compileTestKotlin
```

Expected: PASS.

- [ ] **Step 6: Run JNI test when native library is available**

Run:

```bash
./gradlew :v1_21_1-neoforge:buildRustVmNativeLibrary :compiler:test --tests '*NativeImageVmBindingsJniTest.ruxComputerBootsLowImageAndExposesControlAndDebugWhenLibraryIsConfigured' -Drux.vm.native.library=$PWD/native/rux-vm/target/debug/librux_vm.so
```

Expected: PASS when the native library path matches the current platform output. If the local platform uses a different native library file name, use the path produced by the Gradle `buildRustVmNativeLibrary` task.

- [ ] **Step 7: Commit Task 2**

```bash
git add native/rux-vm/src/jni.rs modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt
git commit -m "feat: expose rux computer native bindings"
```

---

### Task 3: Rux Image Emitter CLI

**Files:**
- Create: `native/rux-compiler/src/bin/rux-emit.rs`

- [ ] **Step 1: Add the emitter binary**

Create `native/rux-compiler/src/bin/rux-emit.rs`:

```rust
use rux_compiler::compile;
use rux_vm::low_image::encode_image;
use std::env;
use std::fs;
use std::process::ExitCode;

fn main() -> ExitCode {
    let mut args = env::args().skip(1);
    let Some(source_path) = args.next() else {
        eprintln!("usage: rux-emit <input.rx> <output.ruxi>");
        return ExitCode::from(2);
    };
    let Some(output_path) = args.next() else {
        eprintln!("usage: rux-emit <input.rx> <output.ruxi>");
        return ExitCode::from(2);
    };
    if args.next().is_some() {
        eprintln!("usage: rux-emit <input.rx> <output.ruxi>");
        return ExitCode::from(2);
    }

    let source = match fs::read_to_string(&source_path) {
        Ok(source) => source,
        Err(error) => {
            eprintln!("failed to read {source_path}: {error}");
            return ExitCode::from(1);
        }
    };
    let image = match compile(&source) {
        Ok(image) => image,
        Err(error) => {
            eprintln!("compile error: {}", error.message);
            return ExitCode::from(1);
        }
    };
    let bytes = match encode_image(&image) {
        Ok(bytes) => bytes,
        Err(error) => {
            eprintln!("encode error: {error}");
            return ExitCode::from(1);
        }
    };
    if let Err(error) = fs::write(&output_path, bytes) {
        eprintln!("failed to write {output_path}: {error}");
        return ExitCode::from(1);
    }
    ExitCode::SUCCESS
}
```

- [ ] **Step 2: Run the emitter against the demo firmware**

Run:

```bash
cargo run --manifest-path native/rux-compiler/Cargo.toml --bin rux-emit -- native/rux-compiler/examples/firmware/terminal.rx /tmp/rux-terminal.ruxi
```

Expected: PASS and `/tmp/rux-terminal.ruxi` starts with `RUXI`.

- [ ] **Step 3: Commit Task 3**

```bash
git add native/rux-compiler/src/bin/rux-emit.rs
git commit -m "feat: add rux image emitter"
```

---

### Task 4: Bundled Rux Firmware Resource

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/rux-terminal.ruxi`
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RuxFirmwareResourceTest.kt`

- [ ] **Step 1: Write the resource test before adding the resource**

Create `RuxFirmwareResourceTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.impl

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuxFirmwareResourceTest {
    @Test
    fun bundledRuxTerminalFirmwareResourceExists() {
        val bytes =
            assertNotNull(
                javaClass.classLoader.getResourceAsStream("firmware/rux-terminal.ruxi"),
                "firmware/rux-terminal.ruxi must be bundled",
            ).use { it.readBytes() }

        assertTrue(bytes.size > 8, "Rux firmware image should not be empty")
        assertTrue(bytes.copyOfRange(0, 4).contentEquals(byteArrayOf('R'.code.toByte(), 'U'.code.toByte(), 'X'.code.toByte(), 'I'.code.toByte())))
    }
}
```

- [ ] **Step 2: Run the resource test to verify it fails**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests '*RuxFirmwareResourceTest.bundledRuxTerminalFirmwareResourceExists'
```

Expected: FAIL because `firmware/rux-terminal.ruxi` does not exist.

- [ ] **Step 3: Generate the `.ruxi` resource from the existing terminal firmware**

Run:

```bash
cargo run --manifest-path native/rux-compiler/Cargo.toml --bin rux-emit -- native/rux-compiler/examples/firmware/terminal.rx modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/rux-terminal.ruxi
```

Expected: PASS and the generated resource starts with `RUXI`.

- [ ] **Step 4: Run the resource test to verify it passes**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests '*RuxFirmwareResourceTest.bundledRuxTerminalFirmwareResourceExists'
```

Expected: PASS.

- [ ] **Step 5: Commit Task 4**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/rux-terminal.ruxi modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RuxFirmwareResourceTest.kt
git commit -m "feat: bundle rux terminal firmware"
```

---

### Task 5: Rux Computer Resource JNI Smoke Test

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RuxFirmwareResourceTest.kt`

- [ ] **Step 1: Add JNI smoke coverage for the bundled resource**

Append to `RuxFirmwareResourceTest.kt`:

```kotlin
@Test
fun bundledRuxTerminalFirmwareBootsOnNativeComputerWhenLibraryIsConfigured() {
    val libraryPath = System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
    val bytes =
        assertNotNull(
            javaClass.classLoader.getResourceAsStream("firmware/rux-terminal.ruxi"),
            "firmware/rux-terminal.ruxi must be bundled",
        ).use { it.readBytes() }

    val handle =
        NativeVmBindings.createRuxComputer(
            libraryPath = libraryPath,
            image = bytes,
            memorySize = 64 * 1024,
            sliceBudgetNanos = 1_000_000,
        )

    try {
        NativeVmBindings.runRuxComputerUntilSignal(handle)
        val output = NativeVmBindings.ruxComputerDebugOutput(handle).decodeToString()
        assertTrue(output.contains("RUX"), output)
        assertEquals(3, NativeVmBindings.ruxComputerControl(handle).status)
    } finally {
        NativeVmBindings.freeRuxComputer(handle)
    }
}
```

Add imports:

```kotlin
import kotlin.test.assertEquals
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmBindings
```

- [ ] **Step 2: Run the smoke test**

Run:

```bash
./gradlew :v1_21_1-neoforge:buildRustVmNativeLibrary :v1_21_1-neoforge:test --tests '*RuxFirmwareResourceTest.bundledRuxTerminalFirmwareBootsOnNativeComputerWhenLibraryIsConfigured' -Drux.vm.native.library=$PWD/native/rux-vm/target/debug/librux_vm.so
```

Expected: PASS when the native library path matches the current platform output. If the local platform uses a different native library file name, use the path produced by the Gradle `buildRustVmNativeLibrary` task.

- [ ] **Step 3: Commit Task 5**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RuxFirmwareResourceTest.kt
git commit -m "test: boot bundled rux firmware on native computer"
```

---

### Task 6: Legacy VM Retirement Audit

**Files:**
- Create: `docs/superpowers/todos/2026-05-15-rux-legacy-vm-retirement-audit.md`

- [ ] **Step 1: Generate the current legacy reference list**

Run:

```bash
rg -n 'fallback|Fallback|BytecodeVirtualMachine|BytecodeComputerProgram|KotlinVmRunner|CkVmImageComputerProgram|createImageNative|createLowImageNative|bootDeviceDaemon|compileProgram|\\.ck"' modules native docs/abi docs/superpowers/specs docs/superpowers/plans
```

Expected: output lists old CKL runtime paths, benchmark paths, daemon compile bridges, and docs references.

- [ ] **Step 2: Write the audit document**

Create `docs/superpowers/todos/2026-05-15-rux-legacy-vm-retirement-audit.md`:

```markdown
# Rux Legacy VM Retirement Audit

## Status

Working note for retiring CKL/Kotlin VM fallback paths after the Rux computer boot slice is covered by tests.

## Production Candidates To Replace

- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt`
  - Current role: boots CKL image programs through the native daemon.
  - Rux target: boot precompiled Rux firmware through `RuxComputerHandle`.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
  - Current role: exposes old CKIM image, low image, and daemon APIs.
  - Rux target: keep Rux computer APIs first-class; quarantine legacy APIs behind tests/benchmarks until deleted.

## Benchmark/Test-Only Candidates To Keep Temporarily

- `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/ComputeVmBenchmarkRunners.kt`
  - Keep until Rux computer benchmarks replace low-image-only benchmarks.
- `native/rux-vm/tests/image_runner.rs`
  - Keep until old CKIM image runner is deleted.

## Resource Candidates To Replace Later

- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck`
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/*.ck`

These remain legacy CKL resources until the Rux terminal/display story exists.

## Deletion Gates

- Rux firmware resource boots on native computer in module tests.
- Mod runtime can instantiate a Rux computer without Kotlin VM fallback.
- Display/input story is either implemented for Rux or explicitly deferred with no old fallback in the new path.
- Legacy APIs are classified as production, benchmark-only, test-only, or dead code.
```

- [ ] **Step 3: Commit Task 6**

```bash
git add docs/superpowers/todos/2026-05-15-rux-legacy-vm-retirement-audit.md
git commit -m "docs: audit legacy vm retirement path"
```

---

### Task 7: Final Verification

- [ ] **Step 1: Run Rust VM tests**

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml
```

Expected: PASS.

- [ ] **Step 2: Run Rux compiler tests**

```bash
cargo test --manifest-path native/rux-compiler/Cargo.toml
```

Expected: PASS.

- [ ] **Step 3: Run focused Kotlin tests**

```bash
./gradlew :compiler:compileTestKotlin :v1_21_1-neoforge:test --tests '*RuxFirmwareResourceTest*'
```

Expected: PASS. JNI-dependent tests skip themselves when `rux.vm.native.library` is unset.

- [ ] **Step 4: Check `RUXI` ABI fixtures are unchanged**

```bash
git diff -- docs/abi/fixtures docs/abi/rux-low-image-v1.md docs/abi/rux-low-image-v1-opcodes.json docs/abi/rux-low-errors-v1.md
```

Expected: no diff.

- [ ] **Step 5: Stop on verification failures**

If any final verification command fails, stop this task and create a focused fix task with its own red/green verification. Do not hide fixes inside the final verification task.

---

## Self-Review

- Spec coverage: the plan covers native computer ownership, JNI/Kotlin binding surface, bundled firmware resource flow, fail-fast Rux path, and a legacy/fallback retirement audit.
- Scope control: the plan does not add Rux OS, processes, filesystem, shell, display, or input.
- ABI safety: no task changes `RUXI` v1 layout, opcode table, error categories, or fixtures.
- Known execution note: native library filenames differ by platform. The plan tells executors to use the path produced by Gradle when `librux_vm.so` is not the local output name.
