# План реализации Firmware Boot UX

> **Для agentic workers:** REQUIRED SUB-SKILL: use superpowers:executing-plans для inline execution или superpowers:subagent-driven-development для delegation. Выполнять task-by-task с RED/GREEN verification.

**Цель:** Запускать каждый device через скрытый firmware `bios.ck`; firmware запускает пользовательский `boot.ck`, печатает startup diagnostics в terminal и оставляет VM включённой, если пользовательский startup отсутствует или сломан.

**Ключевые решения:**
- Hidden firmware file: `firmware/bios.ck`.
- User startup file: `rom/boot.ck`, копируется в normal workspace.
- No migration from old user `bios.ck`.
- User startup failures — это terminal output плюс non-zero result из `process::run()`, а не host VM crash.

## Task 1: Добавить firmware loader API

**Files:**
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt`
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/FirmwareProgramSupport.kt`
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceProgramSupportTest.kt`

- [ ] Добавить RED test в `DeviceProgramSupportTest`:

```kotlin
@Test
fun firmwareLoaderRejectsPathTraversal() {
    val loader = ClasspathFirmwareProgramLoader()

    val firmware = loader.load("../bios.ck")

    assertNull(firmware)
}
```

- [ ] Запустить `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.DeviceProgramSupportTest.firmwareLoaderRejectsPathTraversal`; ожидать Kotlin compilation failure, потому что `ClasspathFirmwareProgramLoader` не существует.
- [ ] Обновить `DeviceProgramFiles`:

```kotlin
object DeviceProgramFiles {
    const val FILE_EXTENSION = ".ck"
    const val BIOS_SCRIPT_NAME = "bios.ck"
    const val BOOT_SCRIPT_NAME = "boot.ck"
}
```

- [ ] Создать `FirmwareProgramSupport.kt` с `LoadedFirmwareProgramSource`, `FirmwareProgramLoader`, `ClasspathFirmwareProgramLoader`. Loader должен читать `$resourceRoot/$normalized` из classpath, default `resourceRoot = "firmware"`, trim leading `/`, reject paths containing `..`, decode UTF-8.
- [ ] Запустить тот же targeted `:core:test`; ожидать PASS.

## Task 2: Разделить firmware resources и user ROM

**Files:**
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck`
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/bios.ck`
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/boot.ck`
- `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/rom.index`
- `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`

- [ ] Добавить RED test в `RomScriptCompileTest`:

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

- [ ] Запустить `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest.bundledFirmwareScriptCompilesCleanly`; ожидать FAIL с missing firmware resource.
- [ ] Переименовать `rom/bios.ck` в `rom/boot.ck`.
- [ ] Заменить `rom/rom.index` на:

```text
boot.ck
shell.ck
ls.ck
mkdir.ck
rmdir.ck
pwd.ck
nano.ck
```

- [ ] Создать `firmware/bios.ck`:

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

- [ ] Запустить `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`; ожидать PASS.

## Task 3: Загружать VM из hidden firmware

**Files:**
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] Добавить imports в `BackgroundDeviceVmTest` для `FirmwareProgramLoader`, `LoadedFirmwareProgramSource`, `ScreenBufferSnapshot`.
- [ ] Добавить helpers: `StaticFirmwareLoader`, `ScreenBufferSnapshot.visibleText()`, `runVmTicks(vm, ticks = 8)`.
- [ ] Добавить RED test `bootsFirmwareAndRunsUserBootFileFromWorkspace`: записать `boot.ck` с `pub fun main() { terminal::println("from boot"); }`, загрузить VM со static firmware, который печатает `from bios`, запускает `process::run("boot.ck")`, печатает `code=<code>`, затем спит forever. Проверить terminal text: `from bios`, `from boot`, `code=0`; VM state active.
- [ ] Запустить `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVmTest.bootsFirmwareAndRunsUserBootFileFromWorkspace`; ожидать compilation failure из-за отсутствия `firmwareLoader`.
- [ ] Добавить constructor parameter `private val firmwareLoader: FirmwareProgramLoader = ClasspathFirmwareProgramLoader()` после `workspace` в `BackgroundDeviceVm`.
- [ ] В `boot()` заменить workspace loading `profile.bootScriptName` на `firmwareLoader.load(profile.bootScriptName)`. Если missing, остановиться с `Missing firmware script: ${profile.bootScriptName}`. Компилировать `source.path` и `source.source`.
- [ ] Запустить targeted test; ожидать PASS.

## Task 4: Печатать child failures из `process::run()` в terminal

**Files:**
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt`
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] Добавить RED test `firmwareReportsMissingBootFileAndStaysActive`: static firmware запускает `boot.ck`, печатает `code=<code>`, sleeps forever; `boot.ck` отсутствует. Проверить terminal содержит `Program not found: boot.ck` и `code=1`; VM active.
- [ ] Добавить RED test `firmwareReportsBootCompileErrorAndStaysActive`: записать invalid private `fun main() {}` в `boot.ck`; static firmware запускает его и печатает code. Проверить terminal содержит `Compilation Error in boot.ck`, `pub fun main`, `code=1`; VM active.
- [ ] Запустить оба targeted tests; ожидать минимум один assertion failure в old behavior.
- [ ] В `VmProcessApi.run`, если `programLoader.load(...)` вернул null, печатать `Program not found: $resolved` в terminal и возвращать `1`.
- [ ] В compile failure branch печатать `Compilation Error in ${programSource.path}: $message` и возвращать `1`.
- [ ] В child runtime exception catch печатать `Program error in ${programSource.path}: ${failure.message ?: failure.javaClass.simpleName}` и возвращать `1`.
- [ ] Запустить оба targeted tests; ожидать PASS.

## Task 5: Обновить старые VM boot expectations

**Files:**
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] Заменить normal-boot tests, которые пишут user `bios.ck`, на explicit `StaticFirmwareLoader`.
- [ ] Обновить `bootCompletesWhenVmRegistrySupportsAmbientModule` в `firmwareCanUseAmbientFilesystemModuleAndStayAlive`; firmware source должен ссылаться на `filesystem::list()` в unreachable branch и затем sleep forever. Проверить VM state active after ticks.
- [ ] Firmware compilation failure tests оставить как firmware failure tests using invalid static firmware.
- [ ] Запустить `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVmTest`; ожидать PASS.
- [ ] Commit runtime/VM changes через `git add` touched core/compiler runtime files и `git commit -m "feat(runtime): boot devices through firmware bios"`.

## Task 6: Проверить, что workspace initialization исключает firmware

**Files:**
- `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/FileComputerWorkspaceTest.kt`
- `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomWorkspaceInitializerTest.kt`

- [ ] Обновить существующий `ComputerWorkspaceInitializerTest.clonesAllRomScriptsIntoNewWorkspace` в `FileComputerWorkspaceTest.kt`: проверить, что `boot.ck` существует, `bios.ck` не существует, и сохранить проверки для `shell.ck`, `ls.ck`, `mkdir.ck`, `rmdir.ck`, `pwd.ck`, `nano.ck`.
- [ ] Обновить `ComputerWorkspaceInitializerTest.doesNotTouchExistingWorkspace`, чтобы менять existing non-firmware user file, например `boot.ck`, а не `bios.ck`.
- [ ] Создать `RomWorkspaceInitializerTest` с test `initializesUserWorkspaceWithBootFileButWithoutBiosFirmware`: вызвать `DeviceWorkspaceInitializer(root).ensureInitialized(7)` и проверить, что `boot.ck` и `shell.ck` существуют, а `bios.ck` — нет.
- [ ] Запустить `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomWorkspaceInitializerTest`; ожидать PASS.
- [ ] Commit resource/test changes через `git add` firmware resource, boot ROM, `rom.index`, ROM tests, workspace initializer test, remove old `rom/bios.ck`, и `git commit -m "feat(runtime): split bios firmware from user boot rom"`.

## Task 7: Добавить terminal flush safety net

**Files:**
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt`

- [ ] В `serverTick()` заменить `val handle = vmHandle ?: return` на branch, который вызывает `flushTerminalSessions()` before returning, если VM handle отсутствует.
- [ ] В `handleVmStopped` вызвать `flushTerminalSessions()` перед `manager.removeVm(deviceId, VmStopReason.CLOSED)`.
- [ ] Запустить `./gradlew :core:test`; ожидать PASS.
- [ ] Commit через `git commit -m "fix(runtime): flush terminal output before VM teardown"`.

## Task 8: Финальная проверка

- [ ] Запустить `./gradlew :compiler:test`; ожидать `BUILD SUCCESSFUL`.
- [ ] Запустить `./gradlew :core:test`; ожидать `BUILD SUCCESSFUL`.
- [ ] Запустить `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest --tests ru.lazyhat.compukterkraft.impl.RomWorkspaceInitializerTest`; ожидать `BUILD SUCCESSFUL`.
- [ ] Запустить `./gradlew test`; ожидать `BUILD SUCCESSFUL`.
- [ ] Запустить `git status --short`; ожидать отсутствие uncommitted changes кроме intentionally uncommitted plan files, если они не были закоммичены.
