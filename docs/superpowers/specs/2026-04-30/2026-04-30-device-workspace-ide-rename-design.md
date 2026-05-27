# Phase 2a-bis — Device Workspace & IDE Rename (mechanical)

## Goal

Mechanically rename the `Computer*`-prefixed Workspace and IDE-host types (today colocated in `compiler/lang/runtime/ComputerWorkspace.kt`) to the `Device*` prefix, and split that file into a Workspace file and an IDE-host file. Also rename the `computerId: Int` parameter to `deviceId: Int` throughout the codebase, since today every such id identifies a Runtime Device.

This spec is a **mechanical refactor** that completes the Runtime-Device-side rename surface deferred from Phase 2a. No semantics change, no new abstractions, no tests change behavior.

## Why

Phase 2a (`docs/superpowers/specs/2026-04-30/2026-04-30-device-umbrella-rename-design.md`) deferred the IDE/Workspace types with the rationale: "they describe the Authoring Station IDE engine, not device side; renaming to `Device*` would be wrong." Re-examination shows that rationale was incorrect:

- `ComputerWorkspace` is **per-device file storage**: it is consumed by the Runtime Device's VM (`HostCallDispatcher`, `ComputerProgramSupport`, `ComputerVmSupervisor`) for filesystem and boot-script reads, and by the Authoring Station (`ServerWorkbench`) which targets the same device's files via the linked computer's `effectiveWorkspaceId`.
- `ComputerIdeHost` is **owned by the Runtime Device**: it is created inside `ComputerVmSupervisor` (as `WorkspaceComputerIdeHost`) and exposed through `ComputerManager.ide`. The Authoring Station is a *consumer*, not a co-owner.
- The `computerId: Int` parameter on every method of these interfaces literally means "the id of the Runtime Device whose workspace/IDE this call is for".

These are exactly the kind of shared substrate the domain-model rule is about:

> Shared infrastructure types are named for their function, not their consumer.

`Device*` is the prefix Phase 2a established for that substrate. Applying it here completes the substrate renaming surface.

## Non-goals

- No introduction of new abstractions (no `RuntimeDevice` interface — that's Phase 2b).
- No package moves (the Workspace/IDE types stay in `compiler/lang/runtime`; the implementations stay in `core/computer/vm`).
- No semantic changes to IDE primitives (`Diagnostic`, `HighlightToken*`, `CompletionItem*`, `HoverInfo`, `DefinitionTarget`, `IdeDiagnosticSeverity`) — they are pure IDE primitives without device coupling and stay unprefixed.
- No CKL surface naming changes (the language layer outside this scope keeps any user-facing "computer" terminology).
- No NBT migration. Mod is in development; no save backcompat.
- No rename of block-specific Computer artifacts (still excluded from Phase 2a, ditto here).

## Scope

### Renames in `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/`

The current single file `ComputerWorkspace.kt` is split into two files by responsibility.

**File rename / split:**

| Current file | New file(s) |
|---|---|
| `ComputerWorkspace.kt` | `DeviceWorkspace.kt` (storage) + `DeviceIdeHost.kt` (IDE engine) |

**Storage types (go to `DeviceWorkspace.kt`):**

| Current name | New name |
|---|---|
| `ComputerWorkspaceEntry` (data class) | `DeviceWorkspaceEntry` |
| `ComputerWorkspaceDocument` (data class) | `DeviceWorkspaceDocument` |
| `ComputerWorkspace` (interface) | `DeviceWorkspace` |

**IDE-host types (go to `DeviceIdeHost.kt`):**

| Current name | New name |
|---|---|
| `ComputerIdeSnapshot` (data class) | `DeviceIdeSnapshot` |
| `ComputerCompletionRequest` (data class) | `DeviceCompletionRequest` |
| `ComputerCompletionResponse` (data class) | `DeviceCompletionResponse` |
| `ComputerHoverRequest` (data class) | `DeviceHoverRequest` |
| `ComputerHoverResponse` (data class) | `DeviceHoverResponse` |
| `ComputerDefinitionRequest` (data class) | `DeviceDefinitionRequest` |
| `ComputerDefinitionResponse` (data class) | `DeviceDefinitionResponse` |
| `ComputerIdeHost` (interface) | `DeviceIdeHost` |

**Unchanged primitives** (also live in `DeviceIdeHost.kt`):

`IdeDiagnosticSeverity`, `Diagnostic`, `HighlightTokenKind`, `HighlightToken`, `CompletionItemKind`, `CompletionItem`, `HoverInfo`, `DefinitionTarget`. They are pure IDE primitives without device coupling.

### Renames in `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/`

| Current file/class | New file/class |
|---|---|
| `ComputerWorkspaceHost.kt` / `ComputerWorkspaceHost` | `DeviceWorkspaceHost.kt` / `DeviceWorkspaceHost` |
| `WorkspaceComputerIdeHost.kt` / `WorkspaceComputerIdeHost` | `WorkspaceDeviceIdeHost.kt` / `WorkspaceDeviceIdeHost` |

Note: `WorkspaceDeviceIdeHost` reads as "an IDE host that backs onto a workspace, scoped to a device" — keeps the original meaning of the original name.

### Parameter rename (codebase-wide)

| Current | New |
|---|---|
| `computerId: Int` parameter on Workspace/IDE-host methods, plus all transitive callers passing the same identifier | `deviceId: Int` |

Today every `computerId` in the codebase identifies a Runtime Device, so a single mechanical rename across all `*.kt` files is correct. The only string occurrences are docstrings/comments referring to "computer id" — those become "device id".

`SiteId.target(computerId: Int)` (CRDT) takes the same concept and is renamed to `SiteId.target(deviceId: Int)`.

### Documentation sync

`docs/ARCHITECTURE.md`:
- `ComputerWorkspace` references update to `DeviceWorkspace`.
- The `compiler` module package table entry for `lang.runtime` updates to mention the split (`DeviceWorkspace`, `DeviceIdeHost`).
- Anywhere `computerId` is used as identifier prose, switch to "device id".

`docs/superpowers/specs/2026-04-30/2026-04-30-device-umbrella-rename-design.md` (Phase 2a spec): no edit. The historical exclusion rationale stays as a record.

## Out of scope (must remain `Computer*`)

- `ComputerVmSupervisor`, `ComputerManager`, `ComputerProgramSupport`, `ComputerWorkspaceInitializer`, `BackgroundComputerVm` (all the block-side orchestration; they identify the *Computer block* as the specific Runtime Device, not the umbrella). These get a separate look in Phase 2b/2d.
- `ComputerItem`, `ComputerMenu`, `ComputerScreen`, `ComputerTerminalScreen`, `ComputerState`, `ComputerFamilyExt`, `ComputerContainerData`, `NetworkComputerInputGateway` — block-side artifacts.
- Any `computerId` *inside CKL source code*, language docs, in-game tooltips, or user-facing strings — terminology orthogonal to this refactor.

## Acceptance criteria

- File `compiler/lang/runtime/ComputerWorkspace.kt` no longer exists; `DeviceWorkspace.kt` and `DeviceIdeHost.kt` exist with the listed types.
- File `core/computer/vm/ComputerWorkspaceHost.kt` renamed to `DeviceWorkspaceHost.kt` with renamed class.
- File `core/computer/vm/WorkspaceComputerIdeHost.kt` renamed to `WorkspaceDeviceIdeHost.kt` with renamed class.
- No `Computer(Workspace|WorkspaceEntry|WorkspaceDocument|IdeHost|IdeSnapshot|CompletionRequest|CompletionResponse|HoverRequest|HoverResponse|DefinitionRequest|DefinitionResponse|WorkspaceHost)` symbol exists in `*.kt` under `modules/`.
- No `\bcomputerId\b` token (parameter name, variable, JSON-like literal) exists in `*.kt` under `modules/`. Comments may still mention "computer" in unrelated context.
- Block-side substrings remain present (sanity check that the rename did not over-reach): `ComputerVmSupervisor`, `ComputerManager`, `ComputerProgramSupport`, `BackgroundComputerVm`, `ComputerItem`, `ComputerMenu`, `ComputerScreen`, `ComputerFamilyExt`.
- `./gradlew clean test --no-daemon` is green.
