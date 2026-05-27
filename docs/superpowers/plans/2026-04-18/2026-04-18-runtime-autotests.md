# Runtime Autotests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a runtime autotest pyramid with shared headless fixtures, stronger VM and peripheral coverage, and a first NeoForge GameTest path for world-facing computer integration.

**Architecture:** Keep most runtime verification in ordinary Gradle tests by centralizing workspace and profile setup in shared test fixtures, then add a minimal NeoForge GameTest harness only for block placement, ticking, and block-entity lifecycle assertions. Treat GameTest as the integration boundary, not as a replacement for compiler or VM tests.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, Architectury Loom, NeoForge 1.21.1, kotlin.test, kotlinx.coroutines.test, existing core VM/runtime test suite.

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/test/RuntimeTestFixtures.kt` | Create | Shared helpers for temporary workspaces, default test profiles, and `.ck` program writing |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ComputerProgramSupportTest.kt` | Modify | Move existing workspace-loading coverage onto the shared fixture layer |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVmTest.kt` | Modify | Add headless VM boot coverage around optional runtime modules and workspace-driven boot |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmPeripheralRegistryTest.kt` | Modify | Add attach-detach and multi-device registry coverage |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt` | Modify | Keep compiler/runtime bridge coverage aligned with the new runtime fixture expectations |
| `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts` | Modify | Add a dedicated GameTest source set and a runnable NeoForge GameTest server entrypoint |
| `modules/v1_21_1/v1_21_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/ComputerGameTestEnvironment.kt` | Create | Helpers for block placement, ticking, and server-computer lookup inside GameTests |
| `modules/v1_21_1/v1_21_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/ComputerBlockGameTest.kt` | Create | First world-facing runtime GameTests for computer placement and ticking behavior |

### Task 1: Add Shared Runtime Test Fixtures

**Files:**
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/test/RuntimeTestFixtures.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ComputerProgramSupportTest.kt`

- [ ] **Step 1: Write the failing fixture-focused test first**

Append this test to `ComputerProgramSupportTest.kt` to lock in the intended helper API:

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

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.computer.runtime.ComputerProgramSupportTest.fixtureWritesProgramIntoIsolatedWorkspace" --no-daemon`

Expected: FAIL because `runtimeTestWorkspace` and `writeProgram` do not exist yet.

- [ ] **Step 3: Create the shared runtime fixture helper**

Add `RuntimeTestFixtures.kt` with a focused fixture API:

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

- [ ] **Step 4: Move the workspace-loading tests onto the helper**

Update the existing tests in `ComputerProgramSupportTest.kt` so temp directory setup is delegated to the helper:

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

- [ ] **Step 5: Run the targeted tests to verify they pass**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.computer.runtime.ComputerProgramSupportTest" --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/test/RuntimeTestFixtures.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ComputerProgramSupportTest.kt
git commit -m "test: add shared runtime test fixtures"
```

### Task 2: Expand Headless Runtime And Peripheral Coverage

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVmTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmPeripheralRegistryTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`

- [ ] **Step 1: Add failing registry tests for multiple attached devices**

Append these tests to `VmPeripheralRegistryTest.kt`:

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

- [ ] **Step 2: Add a failing VM boot test for optional runtime API without a concrete device**

Append this test to `BackgroundComputerVmTest.kt`:

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

- [ ] **Step 3: Run the targeted tests to verify the new expectations fail**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.computer.vm.api.VmPeripheralRegistryTest" --tests "ru.lazyhat.compukterkraft.core.computer.vm.BackgroundComputerVmTest.bootKeepsOptionalPeripheralModuleAvailableWithoutConcreteDevice" --no-daemon`

Expected: FAIL because the new VM boot test still compiles against the current VM profile assumptions and the fixture imports are not wired into `BackgroundComputerVmTest` yet.

- [ ] **Step 4: Refactor the VM tests to use the shared fixture helpers and keep runtime bridge coverage aligned**

Update `BackgroundComputerVmTest.kt` to import and use `runtimeProfile` and `runtimeTestWorkspace`, and add one compiler/runtime bridge assertion in `LanguageRuntimeTest.kt` so the optional-module behavior stays covered both at VM boot and bytecode runtime layers:

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

- [ ] **Step 5: Run the headless runtime test slice**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.computer.vm.BackgroundComputerVmTest" --tests "ru.lazyhat.compukterkraft.core.computer.vm.api.VmPeripheralRegistryTest" :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.routesMonitorExistsThroughRuntimeBridge" --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVmTest.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmPeripheralRegistryTest.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt
git commit -m "test: expand headless runtime coverage"
```

### Task 3: Add NeoForge GameTest Build Scaffolding

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts`
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/ComputerGameTestEnvironment.kt`

- [ ] **Step 1: Add a failing build-level check for a dedicated GameTest run task**

Run: `./gradlew :v1_21_1-neoforge:tasks --all | rg "runGameTestServer"`

Expected: no output, because the module does not yet expose a dedicated GameTest run path.

- [ ] **Step 2: Add the `gameTest` source set and a runnable Loom entrypoint**

Update `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts` so the module has a dedicated source set and a run configuration with the NeoForge GameTest flags:

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

- [ ] **Step 3: Add a tiny GameTest helper shell**

Create `ComputerGameTestEnvironment.kt`:

```kotlin
package ru.lazyhat.compukterkraft.impl.computer.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
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

- [ ] **Step 4: Verify the new run task is visible**

Run: `./gradlew :v1_21_1-neoforge:tasks --all | rg "runGameTestServer"`

Expected: one line containing `runGameTestServer`.

- [ ] **Step 5: Compile the GameTest source set**

Run: `./gradlew :v1_21_1-neoforge:compileGameTestKotlin --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts \
        modules/v1_21_1/v1_21_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/ComputerGameTestEnvironment.kt
git commit -m "test: add neoforge gametest scaffolding"
```

### Task 4: Add The First Computer Block GameTests

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/ComputerBlockGameTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/ComputerGameTestEnvironment.kt`

- [ ] **Step 1: Write the failing GameTests first**

Create `ComputerBlockGameTest.kt` with two initial scenarios:

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

- [ ] **Step 2: Run the GameTest server to verify failure mode**

Run: `./gradlew :v1_21_1-neoforge:runGameTestServer --no-daemon`

Expected: FAIL on the new GameTests until the helper methods and source-set wiring are correct.

- [ ] **Step 3: Tighten the helper around level access and assertions**

If the initial GameTests fail because of missing lifecycle setup, extend `ComputerGameTestEnvironment.kt` with explicit `ServerLevel` assertions and a helper that forces a `serverTick` before reading state:

```kotlin
fun tickUntilComputerId(helper: GameTestHelper, pos: BlockPos, attempts: Int = 5, onReady: (Int) -> Unit) {
    helper.runAfterDelay(attempts.toLong()) {
        val id = serverComputerId(helper.level, pos)
        require(id > 0) { "Expected computer id after $attempts ticks" }
        onReady(id)
    }
}
```

- [ ] **Step 4: Re-run the dedicated GameTest server**

Run: `./gradlew :v1_21_1-neoforge:runGameTestServer --no-daemon`

Expected: PASS, and the log should contain a line starting with `Enabled Gametest Namespaces:`.

- [ ] **Step 5: Run the full fast-plus-slow verification slice**

Run: `./gradlew test :v1_21_1-neoforge:compileGameTestKotlin --no-daemon && ./gradlew :v1_21_1-neoforge:runGameTestServer --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/ComputerGameTestEnvironment.kt \
        modules/v1_21_1/v1_21_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/ComputerBlockGameTest.kt
git commit -m "test: add first runtime gametests"
```