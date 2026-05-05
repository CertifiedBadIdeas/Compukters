# Firmware Boot UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:executing-plans for inline execution or superpowers:subagent-driven-development if delegating tasks. Execute task-by-task with RED/GREEN verification.

**Goal:** Boot every device through a hidden firmware `bios.ck`; firmware launches user `boot.ck`, prints startup diagnostics to the terminal, and keeps the VM powered on when user startup is missing or broken.

**Key decisions:**
- Hidden firmware file: `firmware/bios.ck`.
- User startup file: `rom/boot.ck` copied into normal workspace.
- No migration from old user `bios.ck`.
- User startup failures are terminal output plus non-zero `process::run()` result, not host VM crashes.

## Task 1: Add firmware loader API

**Files:**
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt`
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/FirmwareProgramSupport.kt`
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceProgramSupportTest.kt`

- [ ] Add this RED test to `DeviceProgramSupportTest`:

```kotlin
@Test
fun firmwareLoaderRejectsPathTraversal() {
    val loader = ClasspathFirmwareProgramLoader()

    val firmware = loader.load("../bios.ck")

    assertNull(firmware)
}
```

- [ ] Run `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.DeviceProgramSupportTest.firmwareLoaderRejectsPathTraversal`; expect Kotlin compilation to fail because `ClasspathFirmwareProgramLoader` does not exist.
- [ ] Update `DeviceProgramFiles` to include:

```kotlin
object DeviceProgramFiles {
    const val FILE_EXTENSION = ".ck"
    const val BIOS_SCRIPT_NAME = "bios.ck"
    const val BOOT_SCRIPT_NAME = "boot.ck"
}
```

- [ ] Create `FirmwareProgramSupport.kt` with `LoadedFirmwareProgramSource`, `FirmwareProgramLoader`, and `ClasspathFirmwareProgramLoader`. The loader must read `$resourceRoot/$normalized` from classpath, default `resourceRoot = "firmware"`, trim leading `/`, reject paths containing `..`, and decode UTF-8.
- [ ] Run the same targeted `:core:test`; expect PASS.

## Task 2: Split firmware resources from user ROM

**Files:**
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck`
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/bios.ck`
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/boot.ck`
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/rom.index`
- `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`

- [ ] Add this RED test to `RomScriptCompileTest`:

```kotlin
@Test
fun bundledFirmwareScriptCompilesCleanly() {
    val source =
        RomScriptCompileTest::class.java.classLoader
            .getResourceAsStream("firmware/bios.ck")
            ?.bufferedReader()
            ?.readText()
            ?: fail("firmware/bios.ck missing from classpath")

    val compiled = ComputerProgramCompiler.compile("bios.ck", source)

    if (compiled.program == null) {
        fail("Firmware script bios.ck failed to compile: ${compiled.errorMessage}")
    }
}
```

- [ ] Run `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest.bundledFirmwareScriptCompilesCleanly`; expect FAIL with missing firmware resource.
- [ ] Rename `rom/bios.ck` to `rom/boot.ck`.
- [ ] Replace `rom/rom.index` content with exactly:

```text
boot.ck
shell.ck
ls.ck
mkdir.ck
rmdir.ck
pwd.ck
nano.ck
```

- [ ] Create `firmware/bios.ck` with this CKL:

```ck
pub fun main() {
    terminal::println("Compukter Kraft BIOS")
    terminal::println("Searching for boot.ck...")

    if (!filesystem::exists("boot.ck")) {
        terminal::println("No boot.ck found. Create boot.ck and reboot.")
        while true {
            sleep(20L)
        }
    }

    val code: Int = process::run("boot.ck")
    if (code == 0) {
        terminal::println("boot.ck exited with code 0")
    } else {
        terminal::println("boot.ck failed with code " + code)
        terminal::println("Fix boot.ck and reboot.")
    }

    while true {
        sleep(20L)
    }
}
```

- [ ] Run `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`; expect PASS.

## Task 3: Boot VM from hidden firmware

**Files:**
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] Add helper imports in `BackgroundDeviceVmTest` for `FirmwareProgramLoader`, `LoadedFirmwareProgramSource`, and `ScreenBufferSnapshot`.
- [ ] Add test helpers: `StaticFirmwareLoader`, `ScreenBufferSnapshot.visibleText()`, and `runVmTicks(vm, ticks = 8)`.
- [ ] Add RED test `bootsFirmwareAndRunsUserBootFileFromWorkspace`: write `boot.ck` with `pub fun main() { terminal::println("from boot"); }`, boot VM with static firmware that prints `from bios`, runs `process::run("boot.ck")`, prints `code=<code>`, then sleeps forever. Assert terminal text contains `from bios`, `from boot`, `code=0`, and VM state is active.
- [ ] Run `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVmTest.bootsFirmwareAndRunsUserBootFileFromWorkspace`; expect compilation failure because `BackgroundDeviceVm` lacks `firmwareLoader`.
- [ ] Add constructor parameter `private val firmwareLoader: FirmwareProgramLoader = ClasspathFirmwareProgramLoader()` after `workspace` in `BackgroundDeviceVm`.
- [ ] In `boot()`, replace workspace loading of `profile.bootScriptName` with `firmwareLoader.load(profile.bootScriptName)`. If missing, stop with `Missing firmware script: ${profile.bootScriptName}`. Compile `source.path` and `source.source`.
- [ ] Run the targeted test; expect PASS.

## Task 4: Print `process::run()` child failures to terminal

**Files:**
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt`
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] Add RED test `firmwareReportsMissingBootFileAndStaysActive`: static firmware runs `boot.ck`, prints `code=<code>`, sleeps forever; no `boot.ck` exists. Assert terminal contains `Program not found: boot.ck` and `code=1`; VM remains active.
- [ ] Add RED test `firmwareReportsBootCompileErrorAndStaysActive`: write invalid private `fun main() {}` to `boot.ck`; static firmware runs it and prints code. Assert terminal contains `Compilation Error in boot.ck`, `pub fun main`, `code=1`; VM remains active.
- [ ] Run both targeted tests; expect at least one assertion failure in old behavior.
- [ ] In `VmProcessApi.run`, when `programLoader.load(...)` returns null, print `Program not found: $resolved` to terminal and return `1`.
- [ ] In compile failure branch, print `Compilation Error in ${programSource.path}: $message` and return `1`.
- [ ] In child runtime exception catch, print `Program error in ${programSource.path}: ${failure.message ?: failure.javaClass.simpleName}` and return `1`.
- [ ] Run both targeted tests; expect PASS.

## Task 5: Update old VM boot expectations

**Files:**
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] Replace normal-boot tests that write user `bios.ck` with explicit `StaticFirmwareLoader` usage.
- [ ] Update `bootCompletesWhenVmRegistrySupportsAmbientModule` into `firmwareCanUseAmbientFilesystemModuleAndStayAlive`; firmware source should reference `filesystem::list()` in an unreachable branch and then sleep forever. Assert VM state is active after ticks.
- [ ] Keep firmware compilation failure tests as firmware failure tests using invalid static firmware.
- [ ] Run `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVmTest`; expect PASS.
- [ ] Commit runtime/VM changes with `git add` for touched core/compiler runtime files and `git commit -m "feat(runtime): boot devices through firmware bios"`.

## Task 6: Verify workspace initialization excludes firmware

**Files:**
- `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/FileComputerWorkspaceTest.kt`
- `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomWorkspaceInitializerTest.kt`

- [ ] Update existing `ComputerWorkspaceInitializerTest.clonesAllRomScriptsIntoNewWorkspace` in `FileComputerWorkspaceTest.kt`: assert `boot.ck` exists, assert `bios.ck` does not exist, and keep assertions for `shell.ck`, `ls.ck`, `mkdir.ck`, `rmdir.ck`, `pwd.ck`, and `nano.ck`.
- [ ] Update `ComputerWorkspaceInitializerTest.doesNotTouchExistingWorkspace` to modify an existing non-firmware user file such as `boot.ck`, not `bios.ck`.
- [ ] Create `RomWorkspaceInitializerTest` with test `initializesUserWorkspaceWithBootFileButWithoutBiosFirmware`: call `DeviceWorkspaceInitializer(root).ensureInitialized(7)` and assert `boot.ck` and `shell.ck` exist while `bios.ck` does not.
- [ ] Run `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomWorkspaceInitializerTest`; expect PASS.
- [ ] Commit resource/test changes with `git add` for firmware resource, boot ROM, `rom.index`, ROM tests, workspace initializer test, remove old `rom/bios.ck`, and `git commit -m "feat(runtime): split bios firmware from user boot rom"`.

## Task 7: Add terminal flush safety net

**Files:**
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt`

- [ ] In `serverTick()`, change `val handle = vmHandle ?: return` to a branch that calls `flushTerminalSessions()` before returning when no VM handle exists.
- [ ] In `handleVmStopped`, call `flushTerminalSessions()` before `manager.removeVm(deviceId, VmStopReason.CLOSED)`.
- [ ] Run `./gradlew :core:test`; expect PASS.
- [ ] Commit with `git commit -m "fix(runtime): flush terminal output before VM teardown"`.

## Task 8: Final verification

- [ ] Run `./gradlew :compiler:test`; expect `BUILD SUCCESSFUL`.
- [ ] Run `./gradlew :core:test`; expect `BUILD SUCCESSFUL`.
- [ ] Run `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest --tests ru.lazyhat.compukterkraft.impl.RomWorkspaceInitializerTest`; expect `BUILD SUCCESSFUL`.
- [ ] Run `./gradlew test`; expect `BUILD SUCCESSFUL`.
- [ ] Run `git status --short`; expect no uncommitted changes except intentionally uncommitted plan files if not committed.
