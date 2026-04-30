# Phase 2a — Device Umbrella Rename (mechanical)

## Goal

Mechanically rename the `Computer*`-prefixed shared substrate types (those used by both Runtime Devices and Authoring Stations) to `Device*` so that the shared-substrate layer no longer implies Computer-block ownership. This implements item 4 of Phase 2 from `docs/superpowers/specs/2026-04-30-device-authoring-domain-model-design.md`, broadened to cover the full family of co-located types in `compiler/lang/runtime`.

This spec is a **mechanical refactor**. No semantics change, no new abstractions are introduced, and no tests change behavior.

## Why

Per the Device / Authoring Station domain model:

> **Shared infrastructure types are named for their function, not their consumer.**

Today the language/runtime contracts in `compiler/lang/runtime` are prefixed `Computer*` (e.g. `ComputerProfile`, `ComputerCapability`, `ComputerResources`). These types describe the runtime contract of any Runtime Device — Computer block today, Laptop/Turtle/Pocket Computer tomorrow. The `Computer*` prefix wrongly implies the Computer block is the canonical consumer.

The umbrella name agreed in the domain-model spec is **`RuntimeDevice`** (with `Device` as the short prefix for shared types). Phase 2a applies that prefix to the mechanical layer (data classes, enums, registry). Introducing the `RuntimeDevice` interface itself, decoupling `ServerComputer` from `BlockEntity`, and generalizing `TransientPairing` are deferred to Phase 2b/2c, which are design work, not mechanical renames.

## Non-goals

- No introduction of a `RuntimeDevice` interface (Phase 2b).
- No decoupling of `ServerComputer` from `ServerLevel` / `BlockEntity` (Phase 2c).
- No rename of block-specific types: `ServerComputer`, `ComputerManager`, `BackgroundComputerVm`, `ComputerVmSupervisor`, `ComputerContext`, `ComputerInputDispatcher`, `ComputerProgramSupport`, `ComputerItem`, `ComputerMenu`, `ComputerScreen`, `ComputerTerminalScreen`, `ComputerState`, `ComputerFamilyExt`, `ComputerContainerData`, `NetworkComputerInputGateway`. These name a *specific* Runtime Device (the block) and remain `Computer*` until Phase 2b/2c.
- No NBT migration. The mod is in development; existing world saves are not supported.
- No CKL surface naming changes (the language and its docs continue to talk about "computer" if they do today).

## Scope

### Renames in `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/`

VM models (in `ComputerVmModels.kt`):

| Current name | New name |
|---|---|
| `ComputerCapability` (enum) | `DeviceCapability` |
| `ComputerCpuResources` (data class) | `DeviceCpuResources` |
| `ComputerMemoryResources` (data class) | `DeviceMemoryResources` |
| `ComputerStorageResources` (data class) | `DeviceStorageResources` |
| `ComputerQueueResources` (data class) | `DeviceQueueResources` |
| `ComputerResources` (data class) | `DeviceResources` |
| `ComputerProfile` (data class) | `DeviceProfile` |
| `ComputerVmHandle` (interface) | `DeviceVmHandle` |

Runtime contract interfaces (in `ComputerRuntime.kt`):

| Current name | New name |
|---|---|
| `ComputerProgram` (interface) | `DeviceProgram` |
| `ComputerRuntime` (interface) | `DeviceRuntime` |
| `ComputerSystemApi` (interface) | `DeviceSystemApi` |
| `ComputerTerminalApi` (interface) | `DeviceTerminalApi` |
| `ComputerFileSystemApi` (interface) | `DeviceFileSystemApi` |
| `ComputerProcessApi` (interface) | `DeviceProcessApi` |
| `ComputerRedstoneApi` (interface) | `DeviceRedstoneApi` |
| `ComputerPeripheralApi` (interface) | `DevicePeripheralApi` |
| `ComputerProgramFiles` (object) | `DeviceProgramFiles` |

Stdio (in `ComputerStdioApi.kt`):

| Current name | New name |
|---|---|
| `ComputerStdioApi` (interface) | `DeviceStdioApi` |

File renames:

| Current file | New file |
|---|---|
| `ComputerVmModels.kt` | `DeviceVmModels.kt` |
| `ComputerRuntime.kt` | `DeviceRuntime.kt` |
| `ComputerStdioApi.kt` | `DeviceStdioApi.kt` |

### Renames in `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/block/`

| Current name | New name |
|---|---|
| `ComputerFamily` (enum: `NORMAL`, `ADVANCED`, `COMMAND`) | `DeviceFamily` |
| File `ComputerFamily.kt` | `DeviceFamily.kt` |

### Renames in `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/`

| Current name | New name |
|---|---|
| `ComputerProfileRegistry` (object) | `DeviceProfileRegistry` |
| File `ComputerProfileRegistry.kt` | `DeviceProfileRegistry.kt` |

### NBT key in `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/utils/NBTUtls.kt`

| Current | New |
|---|---|
| `const val FAMILY_ID: String = "ComputerFamilyId"` | `const val FAMILY_ID: String = "DeviceFamilyId"` |
| `var CompoundTag.computerFamilyId` (extension property) | `var CompoundTag.deviceFamilyId` |

The NBT key change is **breaking for existing saves**. Acceptable because the mod is in development.

## Out of scope (explicit list)

### Block-specific Runtime Device artifacts (deferred to Phase 2b/2c)

The following names contain `Computer*` but are NOT renamed in Phase 2a. They name the specific Computer-block Runtime Device or its block-side concrete artifacts:

- `BackgroundComputerVm`, `ComputerVmSupervisor`, `ComputerContext`, `ComputerProgramSupport`, `ComputerInputDispatcher`
- `ServerComputer`, `ComputerManager`, `ComputerIdentitySavedData`
- `ComputerItem`, `AbstractComputerItem`, `ComputerMenu`, `AbstractComputerMenu`
- `ComputerScreen`, `ComputerTerminalScreen`
- `ComputerState`, `ComputerFamilyExt`, `ComputerContainerData`
- `NetworkComputerInputGateway`
- The `computer` package leaves themselves (`core.computer.*`, `common.computer.*`, `impl.computer.*`)

These are revisited in Phase 2b (introduce `RuntimeDevice` interface) and Phase 2c (decouple from `BlockEntity` and generalize manager/registry).

### IDE/Workspace API types (deferred — needs design call, not mechanical)

The `compiler/lang/runtime/ComputerWorkspace.kt` file declares the IDE-side workspace and request/response types:

- `ComputerWorkspace` (interface), `ComputerIdeHost` (interface)
- `ComputerWorkspaceEntry`, `ComputerWorkspaceDocument`, `ComputerIdeSnapshot`
- `ComputerCompletionRequest`/`Response`, `ComputerHoverRequest`/`Response`, `ComputerDefinitionRequest`/`Response`

These describe **what an Authoring Station's IDE engine consumes**, not what runs on a Runtime Device. Renaming them to `Device*` would wrongly imply they live on the device side. The principled name is something like `Workspace*` / `Ide*` / `Authoring*`, but choosing between those is a design decision, not a mechanical rename. Phase 2a leaves them untouched and flags them for a separate brainstorm before Phase 2b.

## Approach

Six sequential commits on a feature branch `phase2a-device-umbrella-rename` (worktree `.worktrees/phase2a-device-umbrella-rename`). Each commit is a single mechanical rename verified by `./gradlew test --no-daemon`.

**Commit order:**

1. VM models — `ComputerCapability`, the four `Computer*Resources` data classes, `ComputerResources`, `ComputerProfile`, `ComputerVmHandle`. Rename file `ComputerVmModels.kt` → `DeviceVmModels.kt`.
2. Runtime contract interfaces — `ComputerProgram`, `ComputerRuntime`, `ComputerSystemApi`, `ComputerTerminalApi`, `ComputerFileSystemApi`, `ComputerProcessApi`, `ComputerRedstoneApi`, `ComputerPeripheralApi`, `ComputerProgramFiles`. Rename file `ComputerRuntime.kt` → `DeviceRuntime.kt`.
3. Stdio — `ComputerStdioApi`. Rename file `ComputerStdioApi.kt` → `DeviceStdioApi.kt`.
4. `ComputerFamily` → `DeviceFamily` (file rename).
5. `ComputerProfileRegistry` → `DeviceProfileRegistry` (file rename).
6. NBT key + Kotlin extension property: `FAMILY_ID` value `"ComputerFamilyId"` → `"DeviceFamilyId"`, `computerFamilyId` extension → `deviceFamilyId`. Plus a single-line update to `docs/ARCHITECTURE.md` Domain Model section if the new names are referenced there.

**Per-commit recipe:**

```bash
# 1. clean tree
git status --short

# 2. file rename (if applicable)
git mv path/to/Computer<X>.kt path/to/Device<X>.kt

# 3. symbol replace
grep -rl --include='*.kt' '\bComputer<X>\b' modules \
  | xargs sed -i 's/\bComputer<X>\b/Device<X>/g'

# 4. catch wrapper-prefixed test fakes (lesson from Phase 1 Task 2)
grep -rE '(Fake|Stub|Mock|Test)Computer<X>' modules --include='*.kt' \
  | head -20
# rename if any

# 5. verify no leftovers
grep -rE '\bComputer<X>\b' modules --include='*.kt' || echo OK

# 6. tests
./gradlew test --no-daemon

# 7. commit
git commit -m "refactor: rename Computer<X> to Device<X>..."
```

**Final:** `./gradlew clean test --no-daemon` to confirm a cold build is clean. Then merge into `dev`.

## Risks

- **`\b` sed boundary trap.** Phase 1 Task 2 demonstrated that `\bComputerFoo\b` does NOT match `FakeComputerFoo` (no word boundary between `Fake` and `Computer`). Each commit recipe includes step 4 to scan `(Fake|Stub|Mock|Test)Computer<X>` and a follow-up sed if anything is found.
- **Cross-module compile cascade.** `compiler` is a dependency of `core`, which is a dependency of `v1_21_1-common`/`v1_21_1-neoforge`/etc. After step 3 (sed), if any consumer file is missed, the build fails. Mitigation: `grep -r` step 5 scans every `*.kt` under `modules/`.
- **`forFamily(ComputerFamily.ADVANCED)` style call sites.** A type rename in the parameter list of an extension/method does not change the method name. The `forFamily` method on the registry survives unchanged; only its parameter type is updated.
- **Doc cross-references.** The domain-model spec (`2026-04-30-device-authoring-domain-model-design.md`) explicitly names the OLD types in its "Mapping to Current Code" table because that document describes the as-of-Phase-0 state. Phase 2a does NOT modify that table — it would invalidate the historical record. Instead, after Phase 2a is merged, the architecture-level doc (`docs/ARCHITECTURE.md`) Domain Model section can be updated to reference the new names; that is a single doc edit included in commit 6 alongside NBT.

## Verification

After all six commits:

```bash
./gradlew clean test --no-daemon

# In-scope renames must show no leftovers.
grep -rE '\bComputer(Capability|CpuResources|MemoryResources|StorageResources|QueueResources|Resources|Profile|VmHandle|Program|Runtime|SystemApi|TerminalApi|FileSystemApi|ProcessApi|RedstoneApi|PeripheralApi|ProgramFiles|StdioApi|Family|ProfileRegistry)\b' modules --include='*.kt' \
  || echo "OK: no in-scope leftovers"

# Out-of-scope names (block-specific + IDE/workspace) MUST still be present unchanged.
grep -rE '\bComputer(Workspace|IdeHost|WorkspaceEntry|WorkspaceDocument|IdeSnapshot|CompletionRequest|CompletionResponse|HoverRequest|HoverResponse|DefinitionRequest|DefinitionResponse)\b' modules --include='*.kt' \
  | head -3 # expected: at least a few hits — these are intentionally untouched
```

## Phase 2a → Phase 2b handoff

After Phase 2a merges, the language/runtime layer no longer pretends Computer is the canonical Runtime Device. Phase 2b can then introduce the `RuntimeDevice` interface in `core` referencing `DeviceProfile` / `DeviceFamily` directly, without name collisions or temporary aliases.

Phase 2c (decouple `ServerComputer` from `BlockEntity`) follows independently after Phase 2b.
