# План реализации runtime-автотестов

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Построить пирамиду runtime-автотестов с общими headless-fixtures, более сильным покрытием VM и периферии, и первым NeoForge GameTest-путём для world-facing интеграции компьютера.

**Architecture:** Держать основную часть runtime-проверок в обычных Gradle-тестах за счёт общего fixture-слоя для workspace и profiles, а минимальный NeoForge GameTest harness использовать только для placement блока, ticking и lifecycle block entity. GameTest должен оставаться границей интеграции, а не заменой для compiler или VM tests.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, Architectury Loom, NeoForge 1.21.1, kotlin.test, kotlinx.coroutines.test, существующий core VM/runtime test suite.

---

## Структура файлов

| File | Action | Responsibility |
|------|--------|----------------|
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/test/RuntimeTestFixtures.kt` | Create | Общие helper'ы для временных workspace, default test profiles и записи `.ck` программ |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ComputerProgramSupportTest.kt` | Modify | Перенести существующее покрытие загрузки workspace на shared fixture layer |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVmTest.kt` | Modify | Добавить headless coverage для boot VM вокруг optional runtime modules и запуска из workspace |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmPeripheralRegistryTest.kt` | Modify | Добавить покрытие attach-detach и multi-device registry |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt` | Modify | Синхронизировать compiler/runtime bridge coverage с ожиданиями нового fixture layer |
| `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts` | Modify | Добавить отдельный GameTest source set и запускаемый NeoForge GameTest server entrypoint |
| `modules/v1_21_1/v1_21_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/ComputerGameTestEnvironment.kt` | Create | Helper'ы для placement блока, ticking и поиска server-computer внутри GameTests |
| `modules/v1_21_1/v1_21_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/ComputerBlockGameTest.kt` | Create | Первые world-facing runtime GameTests для placement и ticking поведения компьютера |

### Task 1: Добавить общие runtime test fixtures

**Files:**
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/test/RuntimeTestFixtures.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ComputerProgramSupportTest.kt`

- [ ] **Step 1: Сначала написать падающий fixture-oriented тест**

Добавьте в `ComputerProgramSupportTest.kt` такой тест, чтобы зафиксировать желаемый helper API:

```kotlin
@Test
fun fixtureWritesProgramIntoIsolatedWorkspace() {
    runtimeTestWorkspace("fixture") { workspace ->
        workspace.writeProgram(7, "boot.ck", "fun main() { }")

        val loaded = WorkspaceProgramLoader(workspace.host).load(7, "boot.ck")

        assertNotNull(loaded)
        assertEquals("fun main() { }", loaded.source)
    }
}
```

- [ ] **Step 2: Запустить таргетный тест и убедиться, что он падает**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.computer.runtime.ComputerProgramSupportTest.fixtureWritesProgramIntoIsolatedWorkspace" --no-daemon`

Expected: FAIL, потому что `runtimeTestWorkspace` и `writeProgram` пока не существуют.

- [ ] **Step 3: Создать shared runtime fixture helper**

Добавьте `RuntimeTestFixtures.kt` с компактным fixture API:

```kotlin
package ru.lazyhat.compukterkraft.core.computer.runtime.test

import ru.lazyhat.compukterkraft.core.computer.vm.ComputerWorkspaceHost
import ru.lazyhat.compukterkraft.lang.runtime.ComputerCapability
import ru.lazyhat.compukterkraft.lang.runtime.ComputerCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.ComputerMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.ComputerProfile
import ru.lazyhat.compukterkraft.lang.runtime.ComputerQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.ComputerResources
import ru.lazyhat.compukterkraft.lang.runtime.ComputerStorageResources
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class RuntimeTestWorkspace internal constructor(
    val host: ComputerWorkspaceHost,
    private val root: java.nio.file.Path,
) {
    fun writeProgram(computerId: Int, path: String, source: String) {
        root.resolve(computerId.toString()).resolve(path).apply {
            parent?.toFile()?.mkdirs()
            writeText(source)
        }
    }
}

fun runtimeProfile(
    id: String = "test",
    capabilities: Set<ComputerCapability> = setOf(ComputerCapability.TERMINAL, ComputerCapability.SYSTEM),
    romBytes: Long = 4096,
): ComputerProfile =
    ComputerProfile(
        id = id,
        displayName = id,
        cpuBudgetNanosPerSlice = 1_000_000,
        maxEventQueueSize = 16,
        terminalWidth = 16,
        terminalHeight = 8,
        colorTerminal = true,
        allowedCapabilities = capabilities,
        resources =
            ComputerResources(
                cpu = ComputerCpuResources(wallTimeGuardNanosPerSlice = 1_000_000),
                memory = ComputerMemoryResources(),
                storage = ComputerStorageResources(programRomBytes = romBytes, diskBytes = 4096),
                queues = ComputerQueueResources(eventQueueSlots = 16, hostCallQueueSlots = 16),
            ),
    )

inline fun runtimeTestWorkspace(
    prefix: String,
    block: (RuntimeTestWorkspace) -> Unit,
) {
    val root = createTempDirectory("compukterkraft-$prefix")
    try {
        block(RuntimeTestWorkspace(ComputerWorkspaceHost(root), root))
    } finally {
        root.toFile().deleteRecursively()
    }
}
```

- [ ] **Step 4: Перевести существующие workspace-loading tests на helper**

Обновите тесты в `ComputerProgramSupportTest.kt`, чтобы настройка временных директорий шла через helper:

```kotlin
@Test
fun loadsDocumentFromWorkspace() {
    runtimeTestWorkspace("program-loader") { workspace ->
        workspace.writeProgram(7, "shell.ck", "fun main() { }")
        val loader = WorkspaceProgramLoader(workspace.host)

        val program = loader.load(7, "shell.ck")

        assertNotNull(program)
        assertEquals("shell.ck", program.path)
        assertEquals("fun main() { }", program.source)
    }
}
```

- [ ] **Step 5: Запустить таргетные тесты и убедиться, что они проходят**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.computer.runtime.ComputerProgramSupportTest" --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/test/RuntimeTestFixtures.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ComputerProgramSupportTest.kt
git commit -m "test: add shared runtime test fixtures"
```

### Task 2: Расширить headless runtime и peripheral coverage

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVmTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmPeripheralRegistryTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`

- [ ] **Step 1: Добавить падающие registry tests для нескольких устройств одного типа**

Добавьте в `VmPeripheralRegistryTest.kt` следующие тесты:

```kotlin
@Test
fun detachRemovesOnlyTheNamedDevice() {
    val registry = VmPeripheralRegistry()

    registry.attach(VmPeripheralDevice(id = "monitor-1", type = "monitor", side = "left"))
    registry.attach(VmPeripheralDevice(id = "monitor-2", type = "monitor", side = "right"))

    registry.detach("monitor-1")

    assertEquals(listOf("monitor-2"), registry.devicesOfType("monitor").map { it.id })
    assertTrue(registry.hasDevice("monitor"))
}

@Test
fun reportsNoMonitorAfterLastDeviceDetaches() {
    val registry = VmPeripheralRegistry()

    registry.attach(VmPeripheralDevice(id = "monitor-1", type = "monitor"))
    registry.detach("monitor-1")

    assertFalse(registry.hasDevice("monitor"))
}
```

- [ ] **Step 2: Добавить падающий VM boot test для optional runtime API без реального устройства**

Добавьте в `BackgroundComputerVmTest.kt` такой тест:

```kotlin
@Test
fun bootKeepsOptionalPeripheralModuleAvailableWithoutConcreteDevice() {
    runtimeTestWorkspace("optional-module-boot") { workspace ->
        workspace.writeProgram(
            1,
            "bios.ck",
            "import monitor;\nfun main() { if (monitor.exists()) { } }",
        )

        val vm =
            BackgroundComputerVm(
                computerId = 1,
                profile = runtimeProfile(id = "advanced"),
                dispatcher = Dispatchers.Default,
                labelProvider = { null },
                logger = ComputerVmLogger { },
                workspace = workspace.host,
            )

        vm.boot()

        val terminalState =
            runBlocking {
                val deferred = async { withTimeout(5_000) { vm.terminalStates.first() } }
                vm.requestSlice(0)
                deferred.await()
            }

        assertTrue(terminalState !is VmState.Crashed)
    }
}
```

- [ ] **Step 3: Запустить таргетные тесты и увидеть ожидаемое падение**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.computer.vm.api.VmPeripheralRegistryTest" --tests "ru.lazyhat.compukterkraft.core.computer.vm.BackgroundComputerVmTest.bootKeepsOptionalPeripheralModuleAvailableWithoutConcreteDevice" --no-daemon`

Expected: FAIL, потому что новый VM boot test пока не использует shared fixture imports и всё ещё упирается в текущие предположения о VM profile.

- [ ] **Step 4: Перевести VM tests на shared fixtures и сохранить bridge coverage**

Обновите `BackgroundComputerVmTest.kt`, чтобы он использовал `runtimeProfile` и `runtimeTestWorkspace`, и добавьте одну compiler/runtime bridge assertion в `LanguageRuntimeTest.kt`, чтобы optional-module поведение оставалось покрытым и на уровне VM boot, и на уровне bytecode runtime:

```kotlin
@Test
fun routesMonitorExistsThroughRuntimeBridgeWhenDeviceIsMissing() {
    val frontend =
        LanguageFrontend(
            BuiltinRegistry(
                modules =
                    LanguageBuiltins.defaultRuntimeRegistry.modules +
                        BuiltinModule(
                            name = "monitor",
                            documentation = "Connected monitor registry.",
                            functions = listOf(BuiltinFunction("exists", emptyList(), "Bool", "Returns true when any monitor is connected.")),
                            origin = ModuleOrigin.OPTIONAL_VM,
                        ),
                globals = LanguageBuiltins.defaultRuntimeRegistry.globals,
                builtinTypes = LanguageBuiltins.defaultRuntimeRegistry.builtinTypes,
            ),
        )

    val artifact = frontend.compile("monitor.ck", "import monitor; fun main() { monitor.exists(); }")
    assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR })
}
```

- [ ] **Step 5: Запустить headless runtime slice**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.computer.vm.BackgroundComputerVmTest" --tests "ru.lazyhat.compukterkraft.core.computer.vm.api.VmPeripheralRegistryTest" :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.routesMonitorExistsThroughRuntimeBridge" --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVmTest.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmPeripheralRegistryTest.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt
git commit -m "test: expand headless runtime coverage"
```

### Task 3: Добавить NeoForge GameTest build scaffolding

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts`
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/ComputerGameTestEnvironment.kt`

- [ ] **Step 1: Добавить падающую build-level проверку на отдельную GameTest run-task**

Run: `./gradlew :v1_21_1-neoforge:tasks --all | rg "runGameTestServer"`

Expected: нет вывода, потому что модуль пока не экспонирует отдельный GameTest run path.

- [ ] **Step 2: Добавить `gameTest` source set и запускаемый Loom entrypoint**

Обновите `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts`, чтобы у модуля появился отдельный source set и run-конфигурация с NeoForge GameTest flags:

```kotlin
val gameTest by sourceSets.creating

configurations[gameTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[gameTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

loom {
    runs {
        register("gameTestServer") {
            server()
            property("neoforge.enableGameTest", "true")
            property("neoforge.gameTestServer", "true")
            ideConfigGenerated(true)
        }
    }

    mods {
        maybeCreate("main").apply {
            sourceSet("gameTest")
            sourceSet("gameTest", project(projects.v1211Common.path))
            sourceSet("gameTest", project(projects.core.path))
            sourceSet("gameTest", project(projects.v1211CreateNeoforge.path))
        }
    }
}

dependencies {
    "gameTestImplementation"(sourceSets.main.get().output)
    "gameTestImplementation"(project(path = projects.v1211Common.path, configuration = "namedElements"))
    "gameTestImplementation"(project(path = projects.v1211CreateNeoforge.path, configuration = "namedElements"))
}
```

- [ ] **Step 3: Добавить минимальный GameTest helper shell**

Создайте `ComputerGameTestEnvironment.kt`:

```kotlin
package ru.lazyhat.compukterkraft.impl.computer.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukterkraft.common.computer.block.ComputerBlockEntity
import ru.lazyhat.compukterkraft.common.computer.context.ServerContext

object ComputerGameTestEnvironment {
    fun computerAt(level: ServerLevel, pos: BlockPos): ComputerBlockEntity =
        requireNotNull(level.getBlockEntity(pos) as? ComputerBlockEntity)

    fun serverComputerId(level: ServerLevel, pos: BlockPos): Int =
        requireNotNull(computerAt(level, pos).computerID)

    fun hasRegisteredServerComputer(level: ServerLevel, pos: BlockPos): Boolean {
        val id = serverComputerId(level, pos)
        return ServerContext.computerManager.get(id) != null
    }
}
```

- [ ] **Step 4: Убедиться, что новая run-task видна**

Run: `./gradlew :v1_21_1-neoforge:tasks --all | rg "runGameTestServer"`

Expected: одна строка с `runGameTestServer`.

- [ ] **Step 5: Скомпилировать GameTest source set**

Run: `./gradlew :v1_21_1-neoforge:compileGameTestKotlin --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts \
        modules/v1_21_1/v1_21_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/ComputerGameTestEnvironment.kt
git commit -m "test: add neoforge gametest scaffolding"
```

### Task 4: Добавить первые GameTests для computer block

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/ComputerBlockGameTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/ComputerGameTestEnvironment.kt`

- [ ] **Step 1: Сначала написать падающие GameTests**

Создайте `ComputerBlockGameTest.kt` с двумя начальными сценариями:

```kotlin
package ru.lazyhat.compukterkraft.impl.computer.block

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import ru.lazyhat.compukterkraft.impl.ModRegistry

class ComputerBlockGameTest {
    @GameTest(template = "empty")
    fun placingComputerCreatesComputerBlockEntity(helper: GameTestHelper) {
        val pos = BlockPos(1, 2, 1)
        helper.setBlock(pos, ModRegistry.Blocks.COMPUTER_ADVANCED.get())

        helper.runAfterDelay(1L) {
            helper.assertTrue(ComputerGameTestEnvironment.serverComputerId(helper.level, pos) > 0) {
                "Expected computer block to allocate a server computer id"
            }
            helper.succeed()
        }
    }

    @GameTest(template = "empty")
    fun tickingComputerRegistersServerComputer(helper: GameTestHelper) {
        val pos = BlockPos(1, 2, 1)
        helper.setBlock(pos, ModRegistry.Blocks.COMPUTER_ADVANCED.get())

        helper.runAfterDelay(5L) {
            helper.assertTrue(ComputerGameTestEnvironment.hasRegisteredServerComputer(helper.level, pos)) {
                "Expected placed computer block to register a server computer after ticking"
            }
            helper.succeed()
        }
    }
}
```

- [ ] **Step 2: Запустить GameTest server и увидеть ожидаемое падение**

Run: `./gradlew :v1_21_1-neoforge:runGameTestServer --no-daemon`

Expected: FAIL на новых GameTests, пока helper methods и source-set wiring не доведены до рабочего состояния.

- [ ] **Step 3: Уточнить helper вокруг доступа к level и lifecycle assertions**

Если первые GameTests падают из-за lifecycle setup, расширьте `ComputerGameTestEnvironment.kt` явной `ServerLevel`-проверкой и helper'ом, который делает задержку до чтения состояния:

```kotlin
fun tickUntilComputerId(helper: GameTestHelper, pos: BlockPos, attempts: Int = 5, onReady: (Int) -> Unit) {
    helper.runAfterDelay(attempts.toLong()) {
        val id = serverComputerId(helper.level, pos)
        require(id > 0) { "Expected computer id after $attempts ticks" }
        onReady(id)
    }
}
```

- [ ] **Step 4: Повторно запустить dedicated GameTest server**

Run: `./gradlew :v1_21_1-neoforge:runGameTestServer --no-daemon`

Expected: PASS, а в логе должна появиться строка, начинающаяся с `Enabled Gametest Namespaces:`.

- [ ] **Step 5: Запустить полный fast-plus-slow verification slice**

Run: `./gradlew test :v1_21_1-neoforge:compileGameTestKotlin --no-daemon && ./gradlew :v1_21_1-neoforge:runGameTestServer --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/ComputerGameTestEnvironment.kt \
        modules/v1_21_1/v1_21_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/ComputerBlockGameTest.kt
git commit -m "test: add first runtime gametests"
```