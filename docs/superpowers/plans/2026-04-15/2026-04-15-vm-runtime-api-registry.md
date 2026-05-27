# VM Runtime API Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current global runtime built-ins with VM-specific runtime module catalogs, add typed peripheral modules backed by a host-side device registry, and make import validation and IDE import discovery depend on the concrete VM.

**Architecture:** The compiler stops assuming one global built-in runtime module set and instead compiles against a VM-owned effective module catalog. Core VM code gains a runtime API registry profile and a peripheral device registry. Typed modules such as `monitor` are available when the VM supports the API contract, while concrete connected devices remain runtime state exposed through registry-style functions.

**Tech Stack:** Kotlin, existing compiler/frontend pipeline, core VM host runtime, workbench IDE facade, v1_21_1 common screen layer, Gradle test tasks.

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt` | Modify | Add module metadata types used by VM-aware catalogs |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt` | Modify | Rename/reframe current static registry as default shell/base registry |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt` | Modify | Accept injected module catalog/registry instead of assuming one global singleton |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt` | Modify | Drive import completion from the current catalog instead of `LanguageBuiltins.registry` |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/language/LanguageServices.kt` | Modify | Keep default editor/compiler services wiring on top of the default runtime registry |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt` | Modify | Add VM-aware import diagnostics and completion tests |
| `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt` | Modify | Add runtime tests for available module vs missing device presence |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/runtime/ComputerProgramSupport.kt` | Modify | Compile programs against the VM’s effective runtime registry |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/VmRuntime.kt` | Modify | Carry the effective runtime registry and a real peripheral device registry into `ComputerRuntime` |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt` | Modify | Build per-VM runtime registry and pass it to compile/runtime creation |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/` | Create | Add typed peripheral registry and typed runtime module adapters |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVmTest.kt` | Modify | Add VM-specific compile compatibility coverage |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchContracts.kt` | Modify | Add VM-aware import picker/query API |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchState.kt` | Modify | Track import picker visibility and items |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStore.kt` | Modify | Open/close import picker and insert selected import |
| `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStoreTest.kt` | Modify | Verify picker state and insertion behavior |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/ComputerProfileRegistry.kt` | Use/Modify | Resolve one current catalog-source implementation from computer family/profile data |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchContracts.kt` | Modify | Introduce an abstract runtime catalog source for IDE-backed import discovery |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt` | Modify | Replace the singleton IDE facade with a family-aware facade instance |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchLayoutModel.kt` | Modify | Layout for the import-picker popup |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/gui/screen/ComputerWorkbenchScreen.kt` | Modify | Render the import picker and handle clicks/keyboard selection |

### Task 1: Introduce VM-Aware Runtime Module Metadata

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`

- [ ] **Step 1: Add failing metadata-shape tests for catalog-backed modules**

Add a new test near the bottom of `LanguageIdeTest.kt` that fixes the intended module metadata shape:

```kotlin
@Test
fun defaultRuntimeRegistryExposesBaseModuleMetadata() {
    val registry = LanguageBuiltins.defaultRuntimeRegistry

    assertTrue(registry.modules.any { it.name == "terminal" && it.origin == ModuleOrigin.BASE_VM })
    assertTrue(registry.modules.none { it.name == "monitor" })
}
```

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageIdeTest.defaultRuntimeRegistryExposesBaseModuleMetadata" --no-daemon`

Expected: FAIL because `ModuleOrigin` and `defaultRuntimeRegistry` do not exist yet.

- [ ] **Step 3: Add module-origin metadata to the language model**

In `LanguageModel.kt`, extend the runtime module metadata with origin and optional-flag support:

```kotlin
enum class ModuleOrigin {
    BASE_VM,
    OPTIONAL_VM,
}

data class BuiltinRegistry(
    val modules: List<BuiltinModule>,
    val globals: List<BuiltinFunction>,
    val builtinTypes: List<BuiltinType>,
)

data class BuiltinModule(
    val name: String,
    val documentation: String,
    val functions: List<BuiltinFunction>,
    val origin: ModuleOrigin = ModuleOrigin.BASE_VM,
)
```

- [ ] **Step 4: Reframe the current static built-ins as the default VM registry**

In `LanguageBuiltins.kt`, rename the exported property and keep a compatibility accessor during migration:

```kotlin
object LanguageBuiltins {
    val defaultRuntimeRegistry =
        BuiltinRegistry(
            modules =
                listOf(
                    BuiltinModule(
                        name = "terminal",
                        documentation = "Terminal I/O operations.",
                        functions = listOf(/* existing terminal builtins */),
                        origin = ModuleOrigin.BASE_VM,
                    ),
                    BuiltinModule(
                        name = "filesystem",
                        documentation = "Sandboxed filesystem access through the computer workspace.",
                        functions = listOf(/* existing filesystem builtins */),
                        origin = ModuleOrigin.BASE_VM,
                    ),
                    // keep the existing base modules unchanged otherwise
                ),
            globals = listOf(/* existing globals */),
            builtinTypes = listOf(/* existing built-in types */),
        )

    @Deprecated("Use defaultRuntimeRegistry")
    val registry: BuiltinRegistry
        get() = defaultRuntimeRegistry
}
```

- [ ] **Step 5: Run the targeted test to verify it passes**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageIdeTest.defaultRuntimeRegistryExposesBaseModuleMetadata" --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt
git commit -m "feat: add runtime module origin metadata"
```

### Task 2: Compile Against a Target VM Runtime Registry

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/language/LanguageServices.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`

- [ ] **Step 1: Add failing tests for VM-specific import availability**

Append two tests to `LanguageIdeTest.kt`:

```kotlin
@Test
fun reportsUnavailableRuntimeModuleForTargetVm() {
    val ide =
        LanguageIde(
            LanguageFrontend(
                BuiltinRegistry(
                    modules = listOf(LanguageBuiltins.defaultRuntimeRegistry.module("terminal")!!),
                    globals = LanguageBuiltins.defaultRuntimeRegistry.globals,
                    builtinTypes = LanguageBuiltins.defaultRuntimeRegistry.builtinTypes,
                ),
            ),
        )

    val snapshot = ide.analyze("test.ck", "import filesystem;\nfun main() {}")
    assertTrue(snapshot.diagnostics.any { it.message.contains("not supported by this VM") })
}

@Test
fun importCompletionUsesInjectedRuntimeRegistry() {
    val terminalOnly =
        BuiltinRegistry(
            modules = listOf(LanguageBuiltins.defaultRuntimeRegistry.module("terminal")!!),
            globals = LanguageBuiltins.defaultRuntimeRegistry.globals,
            builtinTypes = LanguageBuiltins.defaultRuntimeRegistry.builtinTypes,
        )
    val ide = LanguageIde(LanguageFrontend(terminalOnly))

    val items = ide.complete("test.ck", "import ", 0, 7)
    assertEquals(listOf("terminal"), items.map { it.label })
}
```

- [ ] **Step 2: Run the targeted tests to verify they fail**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageIdeTest.reportsUnavailableRuntimeModuleForTargetVm" --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageIdeTest.importCompletionUsesInjectedRuntimeRegistry" --no-daemon`

Expected: FAIL because diagnostics still say `Unknown module` and import completion still reads the global registry.

- [ ] **Step 3: Inject the runtime registry into the frontend and IDE**

Update `LanguageFrontend.kt` so the constructor parameter is the target runtime registry and it defaults to the default VM registry:

```kotlin
class LanguageFrontend(
    private val registry: BuiltinRegistry = LanguageBuiltins.defaultRuntimeRegistry,
) {
    private val analyzer: AnalyzerFacade = DefaultAnalyzerFacade(registry)
    private val compiler: CompilerFacade = DefaultCompilerFacade(registry, analyzer)
}
```

Update `LanguageIde.kt` so import completions and type completions use the injected frontend registry instead of `LanguageBuiltins.registry`:

```kotlin
class LanguageIde(
    private val frontend: LanguageFrontend = LanguageFrontend(),
    private val registry: BuiltinRegistry = LanguageBuiltins.defaultRuntimeRegistry,
) : IdeFacade {
    override fun completeFromAnalysis(
        analysis: AnalyzedProgram,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem> {
        val offset = SourceTextSupport.offsetAt(source, line, column)
        val importPrefix = SourceTextSupport.importPrefix(source, offset)
        if (importPrefix != null) {
            val alreadyImported = analysis.importedModuleNames
            return registry.modules
                .asSequence()
                .filter { it.name.startsWith(importPrefix) }
                .filter { it.name !in alreadyImported }
                .map {
                    CompletionItem(
                        label = it.name,
                        detail = it.documentation,
                        kind = CompletionItemKind.MODULE,
                        documentation = it.documentation,
                    )
                }
                .toList()
        }
        // preserve the existing non-import completion branches, but read builtinTypes from registry
    }
}
```

- [ ] **Step 4: Emit an unavailable-module diagnostic distinct from unknown-module**

In `LanguageFrontend.kt`, inside `registerImports(imports)`, split the lookup into `known runtime module for the language` vs `enabled module for this registry`. For the first implementation, treat the injected registry as the only source of truth and emit the new wording whenever a name is missing from a target VM registry:

```kotlin
private fun registerImports(imports: List<ImportDeclaration>) {
    imports.forEach { declaration ->
        val module = builtinModules[declaration.moduleName]
        if (module == null) {
            diagnostics +=
                FrontendDiagnostic(
                    "Runtime module `${declaration.moduleName}` is not supported by this VM.",
                    declaration.range,
                )
            return@forEach
        }
        // existing success path unchanged
    }
}
```

Wire `LanguageServices` so the default services continue using `LanguageBuiltins.defaultRuntimeRegistry`.

- [ ] **Step 5: Run the targeted tests to verify they pass**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageIdeTest.reportsUnavailableRuntimeModuleForTargetVm" --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageIdeTest.importCompletionUsesInjectedRuntimeRegistry" --no-daemon`

Expected: PASS.

- [ ] **Step 6: Run the full compiler test suite**

Run: `./gradlew :compiler:test --no-daemon`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
        modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt \
        modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/language/LanguageServices.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt
git commit -m "feat: compile imports against target vm registry"
```

### Task 3: Add VM Runtime API Registry Profiles in Core VM

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/runtime/ComputerProgramSupport.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/VmRuntime.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVmTest.kt`

- [ ] **Step 1: Add a failing VM compatibility test**

Add this test to `BackgroundComputerVmTest.kt`:

```kotlin
@Test
fun bootFailsBeforeExecutionWhenVmRegistryDoesNotSupportImportedModule() {
    val root = createTempDirectory("compukterkraft-background-vm")

    try {
        val workspace = ComputerWorkspaceHost(root)
        workspace.writeDocument(1, "bios.ck", "import filesystem;\nfun main() {}")

        val profile =
            ComputerProfile(
                id = "terminal-only",
                displayName = "Terminal Only",
                cpuBudgetNanosPerSlice = 1_000_000,
                maxEventQueueSize = 16,
                terminalWidth = 16,
                terminalHeight = 8,
                colorTerminal = true,
                allowedCapabilities = setOf(ComputerCapability.TERMINAL, ComputerCapability.SYSTEM),
                resources = ComputerResources(
                    cpu = ComputerCpuResources(wallTimeGuardNanosPerSlice = 1_000_000),
                    memory = ComputerMemoryResources(),
                    storage = ComputerStorageResources(programRomBytes = 4096, diskBytes = 1024),
                    queues = ComputerQueueResources(eventQueueSlots = 16, hostCallQueueSlots = 16),
                ),
            )

        val vm = BackgroundComputerVm(
            computerId = 1,
            profile = profile,
            dispatcher = Dispatchers.Default,
            labelProvider = { null },
            logger = ComputerVmLogger { },
            workspace = workspace,
        )

        vm.boot()
        val terminalState = runBlocking {
            val awaited = async { withTimeout(5_000) { vm.terminalStates.first() } }
            vm.requestSlice(0)
            awaited.await()
        }

        assertTrue(terminalState is VmState.Crashed)
        assertTrue(terminalState.errorMessage?.contains("not supported by this VM") == true)
    } finally {
        root.toFile().deleteRecursively()
    }
}
```

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.computer.vm.BackgroundComputerVmTest.bootFailsBeforeExecutionWhenVmRegistryDoesNotSupportImportedModule" --no-daemon`

Expected: FAIL because the program is still compiled against the global registry.

- [ ] **Step 3: Add a VM-owned runtime registry profile and effective registry builder**

Create or extend the VM support in `BackgroundComputerVm.kt` and `VmRuntime.kt` with the following shape:

```kotlin
data class RuntimeApiRegistryProfile(
    val baseRegistry: BuiltinRegistry,
    val optionalModules: List<BuiltinModule> = emptyList(),
)

private fun runtimeRegistryProfile(): RuntimeApiRegistryProfile {
    val baseModules = buildList {
        add(LanguageBuiltins.defaultRuntimeRegistry.module("terminal")!!)
        add(LanguageBuiltins.defaultRuntimeRegistry.module("system")!!)
        if (ComputerCapability.FILESYSTEM in profile.allowedCapabilities) {
            add(LanguageBuiltins.defaultRuntimeRegistry.module("filesystem")!!)
        }
        if (ComputerCapability.EVENTS in profile.allowedCapabilities) {
            add(LanguageBuiltins.defaultRuntimeRegistry.module("events")!!)
        }
        if (ComputerCapability.SYSTEM in profile.allowedCapabilities) {
            add(LanguageBuiltins.defaultRuntimeRegistry.module("process")!!)
            add(LanguageBuiltins.defaultRuntimeRegistry.module("strings")!!)
        }
    }

    return RuntimeApiRegistryProfile(
        baseRegistry = BuiltinRegistry(
            modules = baseModules,
            globals = LanguageBuiltins.defaultRuntimeRegistry.globals,
            builtinTypes = LanguageBuiltins.defaultRuntimeRegistry.builtinTypes,
        ),
    )
}
```

- [ ] **Step 4: Compile programs against the VM’s effective registry**

Update `ComputerProgramSupport.kt` so `ComputerProgramCompiler.compile()` uses a target-specific frontend instead of the global language service singleton:

```kotlin
object ComputerProgramCompiler {
    fun compile(
        path: String,
        source: String,
        profile: ComputerProfile? = null,
        runtimeRegistry: BuiltinRegistry = LanguageBuiltins.defaultRuntimeRegistry,
    ): CompiledComputerProgram {
        val frontend = LanguageFrontend(runtimeRegistry)
        val artifact = frontend.compile(path, source)
        // preserve the existing diagnostic aggregation and ROM-size checks
    }
}
```

Pass `runtimeRegistryProfile().baseRegistry` from `BackgroundComputerVm.boot()`.

- [ ] **Step 5: Store the effective runtime registry inside `VmRuntime`**

Extend `VmRuntime` to carry the registry used for the running program:

```kotlin
class VmRuntime(
    private val ctx: VmContext,
    private val initialProfile: ComputerProfile,
    private val runtimeRegistry: BuiltinRegistry,
    private val systemApi: ComputerSystemApi,
    private val terminalApi: ComputerTerminalApi,
    private val filesystemApi: ComputerFileSystemApi,
    private val processApi: ComputerProcessApi,
    private val redstoneApi: ComputerRedstoneApi = object : ComputerRedstoneApi {},
    private val peripheralsApi: ComputerPeripheralApi = object : ComputerPeripheralApi {},
) : ComputerRuntime {
    val registry: BuiltinRegistry get() = runtimeRegistry
    // existing properties unchanged
}
```

- [ ] **Step 6: Run the targeted test to verify it passes**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.computer.vm.BackgroundComputerVmTest.bootFailsBeforeExecutionWhenVmRegistryDoesNotSupportImportedModule" --no-daemon`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/runtime/ComputerProgramSupport.kt \
        modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/VmRuntime.kt \
        modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVmTest.kt
git commit -m "feat: add vm-owned runtime api registries"
```

### Task 4: Add a Host-Side Peripheral Device Registry and Typed Monitor Module

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmPeripheralRegistry.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmMonitorApi.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/VmRuntime.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`

- [ ] **Step 1: Add a failing runtime test proving missing devices do not block module use**

Append this test to `LanguageRuntimeTest.kt`:

```kotlin
@Test
fun monitorModuleCanReportNoConnectedDevices() {
    val frontend =
        LanguageFrontend(
            BuiltinRegistry(
                modules = LanguageBuiltins.defaultRuntimeRegistry.modules +
                    BuiltinModule(
                        name = "monitor",
                        documentation = "Connected monitor registry.",
                        origin = ModuleOrigin.OPTIONAL_VM,
                        functions = listOf(
                            BuiltinFunction("exists", emptyList(), "Bool", "Returns true when any monitor is connected."),
                        ),
                    ),
                globals = LanguageBuiltins.defaultRuntimeRegistry.globals,
                builtinTypes = LanguageBuiltins.defaultRuntimeRegistry.builtinTypes,
            ),
        )

    val artifact = frontend.compile(
        "monitor.ck",
        "import monitor;\nfun main() { if (!monitor.exists()) { yield(); } }",
    )

    assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR })
}
```

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.monitorModuleCanReportNoConnectedDevices" --no-daemon`

Expected: FAIL because the runtime/registry has no monitor module support.

- [ ] **Step 3: Add the shared peripheral registry types**

Create `VmPeripheralRegistry.kt` with the host-side registry model:

```kotlin
package ru.lazyhat.compukterkraft.core.computer.vm.api

data class VmPeripheralDevice(
    val id: String,
    val type: String,
    val label: String? = null,
    val side: String? = null,
)

class VmPeripheralRegistry {
    private val devices = linkedMapOf<String, VmPeripheralDevice>()

    fun attach(device: VmPeripheralDevice) {
        devices[device.id] = device
    }

    fun detach(id: String) {
        devices.remove(id)
    }

    fun devicesOfType(type: String): List<VmPeripheralDevice> = devices.values.filter { it.type == type }

    fun hasDevice(type: String): Boolean = devices.values.any { it.type == type }
}
```

- [ ] **Step 4: Add the first typed module adapter for monitors**

Create `VmMonitorApi.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.computer.vm.api

class VmMonitorApi(
    private val registry: VmPeripheralRegistry,
) {
    fun exists(): Boolean = registry.hasDevice("monitor")

    fun list(): List<VmPeripheralDevice> = registry.devicesOfType("monitor")
}
```

- [ ] **Step 5: Thread the registry through `BackgroundComputerVm` and `VmRuntime`**

Extend `BackgroundComputerVm` and `VmRuntime` with a shared registry instance:

```kotlin
class BackgroundComputerVm(... ) : ComputerVmHandle, VmContext {
    private val peripheralRegistry = VmPeripheralRegistry()

    private fun createRuntime(
        workingDirectory: String,
        argument: String,
    ): VmRuntime {
        val monitorApi = VmMonitorApi(peripheralRegistry)
        return VmRuntime(
            ctx = this,
            initialProfile = profile,
            runtimeRegistry = runtimeRegistryProfile().baseRegistry,
            systemApi = systemApi,
            terminalApi = terminalApi,
            filesystemApi = filesystemApi,
            processApi = processApi,
            peripheralsApi = monitorApi,
        )
    }
}
```

Keep the first implementation simple: only the monitor typed module is backed by the registry, and the registry starts empty.

- [ ] **Step 6: Run the targeted test to verify it passes**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.monitorModuleCanReportNoConnectedDevices" --no-daemon`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmPeripheralRegistry.kt \
        modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmMonitorApi.kt \
        modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/VmRuntime.kt \
        modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt
git commit -m "feat: add peripheral device registry foundation"
```

### Task 5: Dispatch Typed Runtime Modules Through the Runtime Bridge

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`

- [ ] **Step 1: Add a failing bridge test for monitor.exists()**

Add this test to `LanguageRuntimeTest.kt`:

```kotlin
@Test
fun routesMonitorExistsThroughRuntimeBridge() {
    val frontend =
        LanguageFrontend(
            BuiltinRegistry(
                modules = LanguageBuiltins.defaultRuntimeRegistry.modules +
                    BuiltinModule(
                        name = "monitor",
                        documentation = "Connected monitor registry.",
                        origin = ModuleOrigin.OPTIONAL_VM,
                        functions = listOf(
                            BuiltinFunction("exists", emptyList(), "Bool", "Returns true when any monitor is connected."),
                        ),
                    ),
                globals = LanguageBuiltins.defaultRuntimeRegistry.globals,
                builtinTypes = LanguageBuiltins.defaultRuntimeRegistry.builtinTypes,
            ),
        )

    val artifact = frontend.compile("monitor.ck", "import monitor;\nfun main() { monitor.exists(); }")
    assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR })
}
```

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.routesMonitorExistsThroughRuntimeBridge" --no-daemon`

Expected: FAIL because `RuntimeHostBridge` does not know the `monitor` module.

- [ ] **Step 3: Add typed module dispatch in `RuntimeHostBridge`**

In `RuntimeHostBridge.kt`, add a `monitor` branch next to the existing hard-coded modules:

```kotlin
suspend fun invoke(
    moduleName: String,
    functionName: String,
    arguments: List<VmValue>,
): VmValue {
    ensureCapability(moduleName)
    return when (moduleName) {
        "filesystem" -> invokeFilesystem(functionName, arguments)
        "system" -> invokeSystem(functionName, arguments)
        "terminal" -> invokeTerminal(functionName, arguments)
        "process" -> invokeProcess(functionName, arguments)
        "strings" -> invokeStrings(functionName, arguments)
        "monitor" -> invokeMonitor(functionName, arguments)
        else -> error("Unknown module $moduleName")
    }
}

private fun invokeMonitor(
    functionName: String,
    arguments: List<VmValue>,
): VmValue =
    when (functionName) {
        "exists" -> VmValue.BoolValue(runtime.peripherals.monitorExists())
        else -> error("Unknown monitor function $functionName")
    }
```

If `ComputerPeripheralApi` does not yet provide a typed monitor method, add the minimal method it needs as part of this task and keep it focused on `exists()` for the first slice.

- [ ] **Step 4: Run the targeted test to verify it passes**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.routesMonitorExistsThroughRuntimeBridge" --no-daemon`

Expected: PASS.

- [ ] **Step 5: Run the runtime-related compiler tests**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest" --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt \
        modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt
git commit -m "feat: route typed monitor module through runtime bridge"
```

### Task 6: Make Workbench Import Discovery VM-Aware

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/ComputerProfileRegistry.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchContracts.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/gui/screen/ComputerWorkbenchScreen.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStoreTest.kt`

**Execution note:** The original plan assumed the existing singleton `LanguageWorkbenchIdeFacade` could answer VM-aware import queries. Repo reality differs: the current facade methods receive only `path/source/line/column`, so they cannot know which runtime target is active. Also, the user requirement is that IDE must remain separable from the executing computer. This task therefore introduces an abstract runtime-catalog source first, then plugs the current computer-backed path into that abstraction.

- [ ] **Step 1: Add a failing facade contract test for available import queries**

Add this helper assertion to `WorkbenchStoreTest.kt` by extending the fake facade:

```kotlin
override fun availableImports(
    path: String,
    source: String,
): List<CompletionItem> {
    calls += "availableImports"
    return listOf(CompletionItem(label = "terminal", detail = "base", kind = CompletionItemKind.MODULE))
}
```

Then add this test:

```kotlin
@Test
fun importPickerRequestsAvailableImportsFromFacade() =
    runTest(UnconfinedTestDispatcher()) {
        val ideFacade = FakeWorkbenchIdeFacade()
        val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), ideFacade)
        val updates = FakeWorkbenchUpdateSource()

        store.bind(backgroundScope, updates)
        updates.push(document = ComputerWorkspaceDocument("main.ck", "", 0))
        store.toggleMode()

        store.openImportPicker()

        assertTrue(ideFacade.calls.contains("availableImports"))
    }
```

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchStoreTest.importPickerRequestsAvailableImportsFromFacade" --no-daemon`

Expected: FAIL because the facade contract has no `availableImports()` method.

- [ ] **Step 3: Introduce an abstract runtime catalog source in the workbench contract layer**

In `WorkbenchContracts.kt`, add an abstraction that supplies the runtime catalog context without exposing device ownership details:

```kotlin
interface IdeRuntimeCatalogSource {
    fun runtimeRegistry(): BuiltinRegistry
}

interface WorkbenchIdeFacade {
    fun analyze(
        path: String,
        source: String,
    ): ComputerIdeSnapshot

    fun complete(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem>

    fun completeFromLastAnalysis(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem>

    fun availableImports(
        path: String,
        source: String,
    ): List<CompletionItem>
}
```

The important constraint is architectural, not naming: the IDE gets a catalog source, not a computer handle.

Keep the VM-aware import query on the facade:

```kotlin
- [ ] **Step 4: Replace the singleton workbench IDE facade with a catalog-source-backed facade instance**

In `WorkbenchGateways.kt`, replace `object LanguageWorkbenchIdeFacade` with a class that receives an `IdeRuntimeCatalogSource`:

```kotlin
class LanguageWorkbenchIdeFacade(
    catalogSource: IdeRuntimeCatalogSource,
) : WorkbenchIdeFacade {
    private val registry = catalogSource.runtimeRegistry()
    private val frontend = LanguageFrontend(registry)
    private val ide = LanguageIde(frontend, registry)

    private var lastAnalysisPath: String? = null
    private var lastAnalysisSource: String? = null
    private var lastAnalysis: AnalyzedProgram? = null

    // keep the existing analyze/complete/completeFromLastAnalysis contract,
    // but now all answers come from the injected runtime catalog
}
```

Add a computer-backed adapter in the same file, or in `ComputerProfileRegistry.kt` if you prefer centralization:

```kotlin
class ComputerFamilyCatalogSource(
    private val family: ComputerFamily,
) : IdeRuntimeCatalogSource {
    override fun runtimeRegistry(): BuiltinRegistry {
        val profile = ComputerProfileRegistry.forFamily(family)
        return BuiltinRegistry(
            modules = buildList {
                LanguageBuiltins.defaultRuntimeRegistry.module("terminal")?.let(::add)
                LanguageBuiltins.defaultRuntimeRegistry.module("system")?.let(::add)
                if (ComputerCapability.FILESYSTEM in profile.allowedCapabilities) {
                    LanguageBuiltins.defaultRuntimeRegistry.module("filesystem")?.let(::add)
                }
                if (ComputerCapability.EVENTS in profile.allowedCapabilities) {
                    LanguageBuiltins.defaultRuntimeRegistry.module("events")?.let(::add)
                }
                if (ComputerCapability.SYSTEM in profile.allowedCapabilities) {
                    LanguageBuiltins.defaultRuntimeRegistry.module("process")?.let(::add)
                    LanguageBuiltins.defaultRuntimeRegistry.module("strings")?.let(::add)
                }
                if (ComputerCapability.PERIPHERALS in profile.allowedCapabilities) {
                    // add typed modules such as monitor when the profile supports them
                }
            },
            globals = LanguageBuiltins.defaultRuntimeRegistry.globals,
            builtinTypes = LanguageBuiltins.defaultRuntimeRegistry.builtinTypes,
        )
    }
}
```

This preserves the future path where a detached IDE can provide a different `IdeRuntimeCatalogSource` implementation without pretending to be a live computer.

- [ ] **Step 5: Construct the facade with `container.family` in the workbench screen**

In `ComputerWorkbenchScreen.kt`, change the store initialization:

```kotlin
private val store =
    WorkbenchStore(
        workspaceGateway = NetworkWorkspaceGateway(container),
        controlGateway = InputHandlerControlGateway(inputHandler),
        ideFacade = LanguageWorkbenchIdeFacade(ComputerFamilyCatalogSource(container.family)),
    )
```

- [ ] **Step 6: Implement `availableImports()` in the family-aware gateway**

In `WorkbenchGateways.kt`, delegate to the current `LanguageIde` import-completion path using the cached analysis and `import ` as synthetic input when necessary:

```kotlin
override fun availableImports(
    path: String,
    source: String,
): List<CompletionItem> {
    val probeSource = if (source.contains("import ")) source else "$source\nimport "
    val probeLine = probeSource.lines().lastIndex
    val probeColumn = probeSource.lines().last().length
    return completeFromLastAnalysis(path, probeSource, probeLine, probeColumn)
        .filter { it.kind == CompletionItemKind.MODULE }
}
```

This gives the store a VM-aware list immediately, even before the dedicated picker UI exists, while keeping the IDE dependent only on a catalog source abstraction.

- [ ] **Step 7: Run the targeted test to verify it passes**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchStoreTest.importPickerRequestsAvailableImportsFromFacade" --no-daemon`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchContracts.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/infrastructure/workbench/WorkbenchGateways.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStoreTest.kt
git commit -m "feat: expose vm-aware import discovery to workbench"
```

### Task 7: Add a Separate Import Picker to the Workbench

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchState.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStore.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchLayoutModel.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/gui/screen/ComputerWorkbenchScreen.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStoreTest.kt`

- [ ] **Step 1: Add failing store tests for the picker state and insertion**

Append two tests to `WorkbenchStoreTest.kt`:

```kotlin
@Test
fun opensImportPickerWithAvailableImports() =
    runTest(UnconfinedTestDispatcher()) {
        val ideFacade = FakeWorkbenchIdeFacade()
        val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), ideFacade)
        val updates = FakeWorkbenchUpdateSource()

        store.bind(backgroundScope, updates)
        updates.push(document = ComputerWorkspaceDocument("main.ck", "", 0))
        store.toggleMode()

        store.openImportPicker()

        assertTrue(store.state.editor.importPickerVisible)
        assertEquals(listOf("terminal"), store.state.editor.importPickerItems.map { it.label })
    }

@Test
fun appliesSelectedImportFromPicker() =
    runTest(UnconfinedTestDispatcher()) {
        val ideFacade = FakeWorkbenchIdeFacade()
        val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), ideFacade)
        val updates = FakeWorkbenchUpdateSource()

        store.bind(backgroundScope, updates)
        updates.push(document = ComputerWorkspaceDocument("main.ck", "fun main() {}", 0))
        store.toggleMode()

        store.openImportPicker()
        store.applyImportPickerSelection(0, visibleEditorLines = 20)

        assertTrue(store.state.editor.text.startsWith("import terminal;\n"))
        assertTrue(!store.state.editor.importPickerVisible)
    }
```

- [ ] **Step 2: Run the targeted tests to verify they fail**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchStoreTest.opensImportPickerWithAvailableImports" --tests "ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchStoreTest.appliesSelectedImportFromPicker" --no-daemon`

Expected: FAIL because the editor state has no picker fields and the store has no picker actions.

- [ ] **Step 3: Extend `EditorState` with picker state**

In `WorkbenchState.kt`, add the picker fields:

```kotlin
data class EditorState(
    val text: String = "",
    val dirty: Boolean = false,
    val scrollLine: Int = 0,
    val cursorLine: Int = 0,
    val cursorColumn: Int = 0,
    val ideSnapshot: ComputerIdeSnapshot? = null,
    val hoverInfo: HoverInfo? = null,
    val completionItems: List<CompletionItem> = emptyList(),
    val selectedCompletion: Int = 0,
    val importPickerVisible: Boolean = false,
    val importPickerItems: List<CompletionItem> = emptyList(),
    val selectedImportPickerIndex: Int = 0,
)
```

- [ ] **Step 4: Add picker actions to `WorkbenchStore`**

In `WorkbenchStore.kt`, add these methods after `openCompletion()`:

```kotlin
fun openImportPicker() {
    val document = state.openDocument ?: return
    val items = ideFacade.availableImports(document.path, state.editor.text)
    _state.value =
        state.copy(
            editor = state.editor.copy(
                importPickerVisible = items.isNotEmpty(),
                importPickerItems = items,
                selectedImportPickerIndex = 0,
            ),
        )
}

fun closeImportPicker() {
    _state.value =
        state.copy(
            editor = state.editor.copy(
                importPickerVisible = false,
                importPickerItems = emptyList(),
                selectedImportPickerIndex = 0,
            ),
        )
}

fun applyImportPickerSelection(
    index: Int = state.editor.selectedImportPickerIndex,
    visibleEditorLines: Int,
) {
    val item = state.editor.importPickerItems.getOrNull(index) ?: return
    val importText = "import ${item.label};\n"
    _state.value = state.copy(editor = state.editor.insertText(importText, visibleEditorLines))
    refreshIde()
    closeImportPicker()
}
```

Bind it to `Ctrl+I` in `keyPressed()`:

```kotlin
if ((modifiers and KeyCodes.MOD_CONTROL) != 0) {
    when (key) {
        KeyCodes.KEY_I -> {
            openImportPicker()
            return true
        }
        // keep the existing Ctrl+S and Ctrl+Space branches
    }
}
```

The picker must use the already catalog-source-aware `availableImports()` result from Task 6. Do not fall back to a global `LanguageServices.ide` instance here.

- [ ] **Step 5: Add picker layout and screen rendering**

In `WorkbenchLayoutModel.kt`, add a simple centered popup layout:

```kotlin
data class ImportPickerLayout(
    val bounds: UiRect,
    val rowHeight: Int,
    val visibleItems: Int,
)

fun importPicker(state: WorkbenchState): ImportPickerLayout? {
    if (!state.editor.importPickerVisible || state.editor.importPickerItems.isEmpty()) return null
    val width = 220
    val visibleItems = state.editor.importPickerItems.size.coerceAtMost(8)
    val height = 8 + visibleItems * LINE_HEIGHT
    return ImportPickerLayout(
        bounds = UiRect(leftPos + (imageWidth - width) / 2, topPos + 44, width, height),
        rowHeight = LINE_HEIGHT,
        visibleItems = visibleItems,
    )
}
```

In `ComputerWorkbenchScreen.kt`, render the popup after the completion popup:

```kotlin
private fun renderImportPicker(graphics: GuiGraphics) {
    val picker = layout().importPicker(store.state) ?: return
    val items = store.state.editor.importPickerItems.take(picker.visibleItems)
    graphics.fill(picker.bounds.x, picker.bounds.y, picker.bounds.right, picker.bounds.bottom, 0xEE11151E.toInt())
    items.forEachIndexed { index, item ->
        val rowY = picker.bounds.y + 4 + index * picker.rowHeight
        if (index == store.state.editor.selectedImportPickerIndex) {
            graphics.fill(picker.bounds.x + 2, rowY - 1, picker.bounds.right - 2, rowY + 10, 0x664883C7)
        }
        graphics.drawString(font, item.label, picker.bounds.x + 6, rowY, 0xF5F7FA, false)
        graphics.drawString(font, item.detail, picker.bounds.x + 96, rowY, 0x9CA8B8, false)
    }
}
```

Handle clicks by mapping rows to `store.applyImportPickerSelection(index, visibleEditorLines())`.

- [ ] **Step 6: Run the targeted tests to verify they pass**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchStoreTest.opensImportPickerWithAvailableImports" --tests "ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchStoreTest.appliesSelectedImportFromPicker" --no-daemon`

Expected: PASS.

- [ ] **Step 7: Run the existing workbench store tests**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchStoreTest" --no-daemon`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchState.kt \
        modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStore.kt \
        modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchLayoutModel.kt \
        modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/gui/screen/ComputerWorkbenchScreen.kt \
        modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStoreTest.kt
git commit -m "feat: add vm-aware import picker"
```

### Task 8: Final Verification

**Files:**
- Verify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`
- Verify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
- Verify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVmTest.kt`
- Verify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/application/workbench/WorkbenchStoreTest.kt`

- [ ] **Step 1: Run compiler tests**

Run: `./gradlew :compiler:test --no-daemon`

Expected: PASS.

- [ ] **Step 2: Run core tests**

Run: `./gradlew :core:test --no-daemon`

Expected: PASS.

- [ ] **Step 3: Run cross-module compile verification**

Run: `./gradlew :compiler:compileKotlin :core:compileKotlin :v1_21_1-common:compileKotlin --no-daemon`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual verification checklist**

1. Start a VM whose profile exposes only `terminal` and `system`.
2. Open a file containing `import filesystem;` and verify compile/run is refused before execution with the VM-specific diagnostic.
3. Open the workbench editor on a VM that exposes `monitor` support.
4. Press `Ctrl+I` and confirm the picker shows `monitor` when supported and hides it when unsupported.
5. Run a program importing `monitor` on a VM with no connected monitor and confirm the program starts.
6. In that program, confirm `monitor.exists()` reports false or an equivalent empty-registry result.
7. Attach a monitor device through the host-side test hook and confirm the runtime can observe the new device.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-04-15/2026-04-15-vm-runtime-api-registry-design.md \
        docs/superpowers/plans/2026-04-15/2026-04-15-vm-runtime-api-registry.md
git commit -m "docs: add vm runtime api registry implementation plan"
```