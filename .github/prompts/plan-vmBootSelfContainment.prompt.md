## Plan: VM Boot Self-Containment Refactoring

> **Status: Draft — awaiting approval**

ServerComputer currently owns boot script loading & compilation, violating the principle that VM should be a self-contained black box. Refactor so that: (1) all ROM scripts are seeded into workspace during initialization, (2) `WorkspaceProgramLoader` drops the bundled fallback, (3) `BackgroundComputerVm` gains a `boot()` method that internally loads+compiles+starts, (4) `ServerComputer.turnOn()` reduces to `ensureWorkspace → boot()`.

---

### Phase 1 — Seed All ROM Scripts Into Workspace

**Rationale:** Currently only `bios.ck` is seeded. Other ROM scripts (shell.ck, ls.ck, mkdir.ck, rmdir.ck, pwd.ck) are loaded at runtime via a `bundledScriptLoader` fallback. After this phase, all scripts live in the workspace from the start.

#### Step 1.1: Add ROM script name constants
In `ComputerProgramFiles` (compiler/src/main/kotlin/ck/lang/runtime/ComputerRuntime.kt, L114-117), add a set of all bundled script names:
```kotlin
val ROM_SCRIPTS = setOf("bios.ck", "shell.ck", "ls.ck", "mkdir.ck", "rmdir.ck", "pwd.ck")
```

#### Step 1.2: Expand `initialBundledScripts` default
In `FileComputerWorkspace` (mod/src/main/kotlin/ck/mod/computer/vm/ComputerWorkspaceHost.kt, L40), change the default from `setOf(ComputerProgramFiles.BIOS_SCRIPT_NAME)` to `ComputerProgramFiles.ROM_SCRIPTS`.

`ensureInitialized()` already iterates `initialBundledScripts` and calls `seedBundledScript()` for each — no changes needed to the seeding logic. Existing scripts are preserved (L136: `if (target.exists()) return`).

#### Step 1.3: Update FileComputerWorkspaceTest
In `mod/src/test/kotlin/FileComputerWorkspaceTest.kt`:
- Update `createWorkspace()` helper (L131-134) to return content for all ROM scripts, not just bios.ck
- Add test verifying all 6 ROM scripts are seeded into a new workspace
- Existing test `preserveCustomizedBootScriptWhenReinitialized` still valid — seeding skips existing files

---

### Phase 2 — Remove Bundled Fallback From WorkspaceProgramLoader

**Rationale:** Once all ROM scripts are in workspace, the fallback in `WorkspaceProgramLoader.load()` is dead code. Removing it enforces the "workspace is the single source of truth" principle.

#### Step 2.1: Simplify WorkspaceProgramLoader
In `ComputerProgramSupport.kt` (mod/src/main/kotlin/ck/mod/application/runtime/ComputerProgramSupport.kt, L37-56):
- Remove `bundledScriptLoader` constructor parameter
- Remove fallback logic (L49-54): `bundledScriptLoader(path) ?: bundledScriptLoader(path.removePrefix("/"))`
- `load()` becomes: read from workspace, return `LoadedComputerProgramSource` or null

#### Step 2.2: Update all WorkspaceProgramLoader call sites
- `BackgroundComputerVm` (L85): `WorkspaceProgramLoader(workspace)` — no `bundledScriptLoader`
- `ServerComputer` (L88-90): `WorkspaceProgramLoader(computerManager.workspace)` — no `bundledScriptLoader`. *Note: this field will be fully removed in Phase 4.*

---

### Phase 3 — Add `boot()` to BackgroundComputerVm

**Rationale:** The VM should be self-contained. It already owns a `WorkspaceProgramLoader` internally (L85). Adding `boot()` moves the load→compile→start chain inside the VM, so external callers don't need to know about compilation.

#### Step 3.1: Add `boot(): Boolean` method
In `BackgroundComputerVm` (mod/src/main/kotlin/ck/mod/computer/vm/BackgroundComputerVm.kt), add:
- `fun boot(): Boolean` — uses `profile.bootScriptName` to load from its `programLoader`, calls `ComputerProgramCompiler.compile()`, then calls `start(compiledProgram)` + enqueues "boot" event
- Returns `false` if boot script is missing or compilation fails (logs errors)
- `start(program: ComputerProgram)` remains as-is for direct program execution (useful for testing)

#### Step 3.2: Remove `bundledScriptLoader` from BackgroundComputerVm constructor
In `BackgroundComputerVm` (L77): remove `bundledScriptLoader` parameter. The internal `programLoader` (L85) becomes just `WorkspaceProgramLoader(workspace)`.

#### Step 3.3: Update ComputerVmSupervisor.getOrCreate()
In `ComputerVmSupervisor` (mod/src/main/kotlin/ck/mod/computer/vm/ComputerVmSupervisor.kt, L67-77): remove `bundledScriptLoader = LanguageServices::bundledScript` from `BackgroundComputerVm(...)` constructor call.

---

### Phase 4 — Simplify ServerComputer.turnOn()

**Rationale:** With `boot()` on the VM, ServerComputer no longer needs to know about program loading or compilation. It becomes a pure Minecraft connector.

#### Step 4.1: Rewrite `turnOn()`
In `ServerComputer` (mod/src/main/kotlin/ck/mod/computer/ServerComputer.kt, L131-158), replace:
```
ensureWorkspace → programLoader.load → ComputerProgramCompiler.compile → getOrCreateVm → handle.start(program) → enqueueEvent("boot") → observeLifecycle
```
With:
```
ensureWorkspace → removeVm → getOrCreateVm → handle.boot() → observeLifecycle
```

#### Step 4.2: Remove dead fields and imports
From `ServerComputer`:
- Remove `programLoader` lazy field (L88-90)
- Remove `import ComputerProgramCompiler` (L28)
- Remove `import WorkspaceProgramLoader` (L30)
- Remove `import LanguageServices` (L35)

---

### Relevant Files

- `compiler/src/main/kotlin/ck/lang/runtime/ComputerRuntime.kt` — add `ROM_SCRIPTS` set to `ComputerProgramFiles`
- `compiler/src/main/kotlin/ck/lang/runtime/ComputerVmModels.kt` — no changes (reference: `ComputerProfile.bootScriptName`, `ComputerVmHandle.start()`)
- `mod/src/main/kotlin/ck/mod/computer/vm/ComputerWorkspaceHost.kt` — expand `initialBundledScripts` default
- `mod/src/main/kotlin/ck/mod/application/runtime/ComputerProgramSupport.kt` — simplify `WorkspaceProgramLoader`, keep `ComputerProgramCompiler`
- `mod/src/main/kotlin/ck/mod/computer/vm/BackgroundComputerVm.kt` — add `boot()`, remove `bundledScriptLoader` param
- `mod/src/main/kotlin/ck/mod/computer/vm/ComputerVmSupervisor.kt` — remove `bundledScriptLoader` from `getOrCreate()`
- `mod/src/main/kotlin/ck/mod/computer/ServerComputer.kt` — simplify `turnOn()`, remove `programLoader`
- `mod/src/main/kotlin/ck/mod/computer/vm/VmProcessApi.kt` — no changes (uses `WorkspaceProgramLoader` via `BackgroundComputerVm.programLoader`)
- `mod/src/main/kotlin/ck/mod/language/LanguageServices.kt` — no changes (still provides `bundledScript()` for workspace seeding)
- `mod/src/test/kotlin/FileComputerWorkspaceTest.kt` — update tests for all ROM scripts

---

### Verification

1. `./gradlew :compiler:test :mod:test` — all existing tests pass
2. `FileComputerWorkspaceTest` — new test verifies all 6 ROM scripts are seeded
3. Manual in-game test: boot a new computer → verify bios.ck runs → shell.ck loads → `ls` works (all from workspace, no ROM fallback)
4. Manual in-game test: boot an existing computer (workspace already has scripts) → verify `ensureInitialized()` doesn't overwrite user-modified scripts
5. Manual in-game test: reboot → verify `handleVmStopped` triggers `turnOn()` which calls `boot()` correctly

---

### Decisions

- `boot()` is on `BackgroundComputerVm` only, NOT on the `ComputerVmHandle` interface. The interface is in `:compiler` which should not depend on `LanguageServices`/compilation. `ServerComputer` already uses `BackgroundComputerVm` type directly.
- `start(program: ComputerProgram)` remains on the interface for direct program injection (useful for testing).
- `FileComputerWorkspace` keeps its `bundledScriptLoader` parameter — it's needed to read ROM content from classpath resources during seeding.
- ROM script names are hardcoded in `ComputerProgramFiles.ROM_SCRIPTS`. If new ROM scripts are added, the set must be updated. This is intentional — explicit > implicit.
- `ComputerProgramCompiler` stays in `mod` module (not moved into `:compiler`). It depends on `LanguageServices` which is mod-specific.

---

### Further Considerations

1. **ROM script discovery vs hardcoded set**: Instead of `ROM_SCRIPTS = setOf(...)`, we could scan classpath `rom/` directory at startup. This avoids forgetting to update the set. However, classpath scanning is fragile in modded environments. **Recommend: hardcoded set** — explicit and predictable.
2. **Should `boot()` be on `ComputerVmHandle` interface?**: If future VM implementations (e.g., test doubles) need boot-from-workspace, we'd need to duplicate the logic. For now, keeping it on `BackgroundComputerVm` is simpler and avoids pulling compilation into the `:compiler` module boundary. Revisit if a second implementation appears.
