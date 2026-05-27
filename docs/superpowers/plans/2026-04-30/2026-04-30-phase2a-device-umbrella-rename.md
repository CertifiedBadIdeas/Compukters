# Phase 2a — Device Umbrella Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mechanically rename the `Computer*`-prefixed shared substrate types in `compiler/lang/runtime/`, `core/block/ComputerFamily`, and `core/computer/vm/ComputerProfileRegistry` to `Device*`. Plus the NBT `FAMILY_ID` key value `"ComputerFamilyId"` → `"DeviceFamilyId"` and its Kotlin extension property `computerFamilyId` → `deviceFamilyId`.

**Architecture:** Six sequential commits, each a single mechanical rename verified by `./gradlew test --no-daemon`. Each task uses the same recipe: `git mv` → `sed` symbol-replace with `\b` word boundaries → catch `(Fake|Stub|Mock|Test)Computer*` wrapper-prefixed names (Phase 1 lesson) → grep for leftovers → run tests → commit. Source spec: [`docs/superpowers/specs/2026-04-30/2026-04-30-device-umbrella-rename-design.md`](../../specs/2026-04-30/2026-04-30-device-umbrella-rename-design.md).

**Tech Stack:** Kotlin/Gradle multi-module mod (Architectury Loom). Modules `:compiler`, `:core`, `:v1_21_1-common`, `:v1_21_1-fabric`, `:v1_21_1-forge`, `:v1_21_1-neoforge`, `:v1_21_1-create-neoforge`. Test command: `./gradlew test --no-daemon`. Working directory: `/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/phase2a-device-umbrella-rename` (worktree on branch `phase2a-device-umbrella-rename`).

---

## Pre-flight (run once before Task 1)

- [ ] **Confirm clean tree on the worktree branch**

```bash
cd /home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/phase2a-device-umbrella-rename
git status --short   # expected: empty
git log --oneline -1 # expected: 473c7a3 docs(spec): Phase 2a device umbrella rename design
```

- [ ] **Confirm baseline tests pass** (so any later failure is attributable to the rename, not pre-existing breakage)

```bash
./gradlew test --no-daemon
```
Expected: `BUILD SUCCESSFUL`. If a flaky `BackgroundComputerVmTest` timeout appears, re-run once with `--rerun-tasks`.

---

## Task 1: Rename VM models (`ComputerVmModels.kt`)

**Files:**
- Rename: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerVmModels.kt` → `DeviceVmModels.kt`
- Modify (symbol references): every `*.kt` under `modules/` that imports or uses any of the eight types below.

**Symbols renamed:** `ComputerCapability`, `ComputerCpuResources`, `ComputerMemoryResources`, `ComputerStorageResources`, `ComputerQueueResources`, `ComputerResources`, `ComputerProfile`, `ComputerVmHandle`.

- [ ] **Step 1: rename file**

```bash
git mv modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerVmModels.kt \
       modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceVmModels.kt
```

- [ ] **Step 2: replace symbols across the codebase**

```bash
for sym in ComputerCapability ComputerCpuResources ComputerMemoryResources ComputerStorageResources ComputerQueueResources ComputerResources ComputerProfile ComputerVmHandle; do
  new=${sym/Computer/Device}
  grep -rl --include='*.kt' "\\b$sym\\b" modules \
    | xargs --no-run-if-empty sed -i "s/\\b$sym\\b/$new/g"
done
```

- [ ] **Step 3: catch wrapper-prefixed test fakes**

```bash
grep -rE '\b(Fake|Stub|Mock|Test)Computer(Capability|CpuResources|MemoryResources|StorageResources|QueueResources|Resources|Profile|VmHandle)' modules --include='*.kt'
```
Expected: no matches. (Phase 1 inventory confirmed no such wrappers exist for these symbols, but always re-verify.) If any appear, run a follow-up sed for each one.

- [ ] **Step 4: verify no leftovers**

```bash
grep -rE '\bComputer(Capability|CpuResources|MemoryResources|StorageResources|QueueResources|Resources|Profile|VmHandle)\b' modules --include='*.kt' || echo OK
```
Expected: `OK`.

- [ ] **Step 5: run tests**

```bash
./gradlew test --no-daemon
```
Expected: `BUILD SUCCESSFUL`. If `BackgroundComputerVmTest` flakes with a 5000ms `kotlinx.coroutines.TimeoutCancellationException`, re-run with `--rerun-tasks` (Phase 1 documented this flake).

- [ ] **Step 6: commit**

```bash
git add -A
git commit -m "refactor: rename VM models to Device prefix

Mechanical rename of the shared-substrate VM model types:

- ComputerCapability     -> DeviceCapability
- ComputerCpuResources   -> DeviceCpuResources
- ComputerMemoryResources -> DeviceMemoryResources
- ComputerStorageResources -> DeviceStorageResources
- ComputerQueueResources -> DeviceQueueResources
- ComputerResources      -> DeviceResources
- ComputerProfile        -> DeviceProfile
- ComputerVmHandle       -> DeviceVmHandle

File ComputerVmModels.kt -> DeviceVmModels.kt.

These types describe the runtime contract of any Runtime Device, not
specifically the Computer block, per the Device / Authoring Station
domain model.

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-umbrella-rename-design.md
Task 1."
```

---

## Task 2: Rename runtime contract interfaces (`ComputerRuntime.kt`)

**Files:**
- Rename: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerRuntime.kt` → `DeviceRuntime.kt`
- Modify (symbol references): every `*.kt` under `modules/` that imports or uses any of the nine types below.

**Symbols renamed:** `ComputerProgram`, `ComputerRuntime`, `ComputerSystemApi`, `ComputerTerminalApi`, `ComputerFileSystemApi`, `ComputerProcessApi`, `ComputerRedstoneApi`, `ComputerPeripheralApi`, `ComputerProgramFiles`.

- [ ] **Step 1: rename file**

```bash
git mv modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerRuntime.kt \
       modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt
```

- [ ] **Step 2: replace symbols across the codebase**

```bash
for sym in ComputerProgram ComputerRuntime ComputerSystemApi ComputerTerminalApi ComputerFileSystemApi ComputerProcessApi ComputerRedstoneApi ComputerPeripheralApi ComputerProgramFiles; do
  new=${sym/Computer/Device}
  grep -rl --include='*.kt' "\\b$sym\\b" modules \
    | xargs --no-run-if-empty sed -i "s/\\b$sym\\b/$new/g"
done
```

NOTE: `\bComputerRuntime\b` does NOT match `BackgroundComputerVm` or other compound names — `Runtime` is a full word. Confirm by reading a couple of changed files after the sed runs.

- [ ] **Step 3: catch wrapper-prefixed test fakes**

```bash
grep -rE '\b(Fake|Stub|Mock|Test)Computer(Program|Runtime|SystemApi|TerminalApi|FileSystemApi|ProcessApi|RedstoneApi|PeripheralApi|ProgramFiles)' modules --include='*.kt'
```
Expected: no matches. Address any hits with follow-up sed.

- [ ] **Step 4: verify no leftovers**

```bash
grep -rE '\bComputer(Program|Runtime|SystemApi|TerminalApi|FileSystemApi|ProcessApi|RedstoneApi|PeripheralApi|ProgramFiles)\b' modules --include='*.kt' || echo OK
```
Expected: `OK`. Note the use of word-boundary `\b` — this WILL still find `BackgroundComputerVm` if anything matched broken; double-check by inspecting any unexpected hits.

- [ ] **Step 5: run tests**

```bash
./gradlew test --no-daemon
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: commit**

```bash
git add -A
git commit -m "refactor: rename language-runtime interfaces to Device prefix

Mechanical rename of shared-substrate runtime contract interfaces:

- ComputerProgram        -> DeviceProgram
- ComputerRuntime        -> DeviceRuntime
- ComputerSystemApi      -> DeviceSystemApi
- ComputerTerminalApi    -> DeviceTerminalApi
- ComputerFileSystemApi  -> DeviceFileSystemApi
- ComputerProcessApi     -> DeviceProcessApi
- ComputerRedstoneApi    -> DeviceRedstoneApi
- ComputerPeripheralApi  -> DevicePeripheralApi
- ComputerProgramFiles   -> DeviceProgramFiles

File ComputerRuntime.kt -> DeviceRuntime.kt.

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-umbrella-rename-design.md
Task 2."
```

---

## Task 3: Rename `ComputerStdioApi`

**Files:**
- Rename: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerStdioApi.kt` → `DeviceStdioApi.kt`
- Modify: every `*.kt` referencing `ComputerStdioApi`.

- [ ] **Step 1: rename file**

```bash
git mv modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerStdioApi.kt \
       modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceStdioApi.kt
```

- [ ] **Step 2: replace symbol**

```bash
grep -rl --include='*.kt' '\bComputerStdioApi\b' modules \
  | xargs --no-run-if-empty sed -i 's/\bComputerStdioApi\b/DeviceStdioApi/g'
```

- [ ] **Step 3: catch fakes**

```bash
grep -rE '\b(Fake|Stub|Mock|Test)ComputerStdioApi' modules --include='*.kt'
```
Expected: no matches.

- [ ] **Step 4: verify no leftovers**

```bash
grep -rE '\bComputerStdioApi\b' modules --include='*.kt' || echo OK
```

- [ ] **Step 5: tests**

```bash
./gradlew test --no-daemon
```

- [ ] **Step 6: commit**

```bash
git add -A
git commit -m "refactor: rename ComputerStdioApi to DeviceStdioApi

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-umbrella-rename-design.md
Task 3."
```

---

## Task 4: Rename `ComputerFamily` enum

**Files:**
- Rename: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/block/ComputerFamily.kt` → `DeviceFamily.kt`
- Modify: every `*.kt` and the NBT-utils file.

**Risk note:** `ComputerFamily` has 50 references — the highest count in this rename. Larger blast radius for missed boundaries. Step 4's grep is mandatory.

- [ ] **Step 1: rename file**

```bash
git mv modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/block/ComputerFamily.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/block/DeviceFamily.kt
```

- [ ] **Step 2: replace symbol**

```bash
grep -rl --include='*.kt' '\bComputerFamily\b' modules \
  | xargs --no-run-if-empty sed -i 's/\bComputerFamily\b/DeviceFamily/g'
```

- [ ] **Step 3: catch fakes / extension targets**

```bash
grep -rE '\b(Fake|Stub|Mock|Test)ComputerFamily' modules --include='*.kt'
grep -rE '\bComputerFamilyExt\b' modules --include='*.kt'  # this MUST remain (block-side, out of scope)
```
- `Fake/Stub/Mock/Test`: no matches expected.
- `ComputerFamilyExt`: hits expected — this is the block-side extension type, deliberately out of scope. Confirm none were collaterally renamed by the sed (the `\b` boundary should have prevented this, but verify).

```bash
grep -rE '\bComputerFamilyExt\b' modules --include='*.kt' | head -3
```
Expected: still finds the original `ComputerFamilyExt` references unchanged.

- [ ] **Step 4: verify no leftovers**

```bash
grep -rE '\bComputerFamily\b' modules --include='*.kt' || echo OK
```
Expected: `OK`. (Note: `ComputerFamilyExt` will NOT match `\bComputerFamily\b` because of the trailing `Ext` — the `\b` is between `y` and `E` only if `E` were not a word char, which it is, so no boundary, so no match. Safe.)

- [ ] **Step 5: tests**

```bash
./gradlew test --no-daemon
```

- [ ] **Step 6: commit**

```bash
git add -A
git commit -m "refactor: rename ComputerFamily enum to DeviceFamily

NORMAL/ADVANCED/COMMAND identifies the API surface a Runtime Device
exposes to CKL programs. Not Computer-specific.

ComputerFamilyExt (block-side extension) remains under its current name
and will be renamed in Phase 2b/2c alongside other block-specific
artifacts.

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-umbrella-rename-design.md
Task 4."
```

---

## Task 5: Rename `ComputerProfileRegistry`

**Files:**
- Rename: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/ComputerProfileRegistry.kt` → `DeviceProfileRegistry.kt`
- Modify: every `*.kt` referencing `ComputerProfileRegistry`.

**Note:** This file STAYS in `core/computer/vm/` package — Phase 2a does NOT move packages. Only the file name and the symbol change.

- [ ] **Step 1: rename file**

```bash
git mv modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/ComputerProfileRegistry.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/DeviceProfileRegistry.kt
```

- [ ] **Step 2: replace symbol**

```bash
grep -rl --include='*.kt' '\bComputerProfileRegistry\b' modules \
  | xargs --no-run-if-empty sed -i 's/\bComputerProfileRegistry\b/DeviceProfileRegistry/g'
```

- [ ] **Step 3: catch fakes**

```bash
grep -rE '\b(Fake|Stub|Mock|Test)ComputerProfileRegistry' modules --include='*.kt'
```
Expected: no matches.

- [ ] **Step 4: verify no leftovers**

```bash
grep -rE '\bComputerProfileRegistry\b' modules --include='*.kt' || echo OK
```

- [ ] **Step 5: tests**

```bash
./gradlew test --no-daemon
```

- [ ] **Step 6: commit**

```bash
git add -A
git commit -m "refactor: rename ComputerProfileRegistry to DeviceProfileRegistry

Aligns the registry name with DeviceProfile/DeviceFamily it serves. The
file remains under core.computer.vm; the package move out of computer
is deferred to Phase 2c.

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-umbrella-rename-design.md
Task 5."
```

---

## Task 6: Rename NBT key + extension property + final doc sync

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/utils/NBTUtls.kt` (constant value + extension property name)
- Modify: every `*.kt` calling `computerFamilyId` extension
- Modify (if needed): `docs/ARCHITECTURE.md` Domain Model section if it references the renamed types

**NBT note:** This commit changes the on-disk format. Acceptable per spec — mod is in development. No migration code is added.

- [ ] **Step 1: change the NBT key value and rename the extension property**

```bash
# The constant FAMILY_ID's STRING VALUE changes from "ComputerFamilyId" to "DeviceFamilyId".
sed -i 's|"ComputerFamilyId"|"DeviceFamilyId"|g' \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/utils/NBTUtls.kt

# The extension property symbol computerFamilyId -> deviceFamilyId across all of modules/.
grep -rl --include='*.kt' '\bcomputerFamilyId\b' modules \
  | xargs --no-run-if-empty sed -i 's/\bcomputerFamilyId\b/deviceFamilyId/g'
```

- [ ] **Step 2: verify the NBT constant value and the extension property name**

```bash
grep -n 'FAMILY_ID' modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/utils/NBTUtls.kt
grep -n 'deviceFamilyId\|computerFamilyId' modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/utils/NBTUtls.kt
```
Expected:
- `FAMILY_ID` line shows `"DeviceFamilyId"`.
- Extension property line shows `deviceFamilyId`, no `computerFamilyId` left.

- [ ] **Step 3: verify no leftovers across modules**

```bash
grep -rE '"ComputerFamilyId"|\bcomputerFamilyId\b' modules --include='*.kt' || echo OK
```
Expected: `OK`.

- [ ] **Step 4: update `docs/ARCHITECTURE.md` Domain Model section if it references the renamed types**

```bash
grep -nE 'ComputerProfile|ComputerFamily|ComputerCapability|ComputerResources' docs/ARCHITECTURE.md
```

If hits appear, replace each with the corresponding `Device*` name in-place. The Domain Model section is a forward-looking summary; updating it to current symbols is correct. Do NOT modify the spec at `docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md` — that is the historical Phase-0 record and must stay frozen.

If no hits, this step is a no-op — note it in the commit message.

- [ ] **Step 5: tests**

```bash
./gradlew test --no-daemon
```

- [ ] **Step 6: commit**

```bash
git add -A
git commit -m "refactor: rename FAMILY_ID NBT key and computerFamilyId extension to Device*

NBT key string value: \"ComputerFamilyId\" -> \"DeviceFamilyId\".
Kotlin extension property: computerFamilyId -> deviceFamilyId.

Breaking change for existing world saves; acceptable because the mod is
in development (no migration code is added).

Plus: sync the Domain Model section in docs/ARCHITECTURE.md to use
Device* names. The historical spec at
docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md
is intentionally NOT updated — it is the Phase-0 frozen record.

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-umbrella-rename-design.md
Task 6."
```

---

## Final verification

- [ ] **Step 1: cold build + full tests**

```bash
./gradlew clean test --no-daemon
```
Expected: `BUILD SUCCESSFUL`. Re-run with `--rerun-tasks` if the `BackgroundComputerVmTest` flake appears.

- [ ] **Step 2: in-scope leftovers — must be empty**

```bash
grep -rE '\bComputer(Capability|CpuResources|MemoryResources|StorageResources|QueueResources|Resources|Profile|VmHandle|Program|Runtime|SystemApi|TerminalApi|FileSystemApi|ProcessApi|RedstoneApi|PeripheralApi|ProgramFiles|StdioApi|Family|ProfileRegistry)\b' modules --include='*.kt' \
  || echo "OK: no in-scope leftovers"

grep -rE '"ComputerFamilyId"|\bcomputerFamilyId\b' modules --include='*.kt' \
  || echo "OK: no NBT leftovers"
```
Expected: both print `OK: ...`. Note: `ComputerFamilyExt` is intentionally still present.

- [ ] **Step 3: out-of-scope must be intact**

```bash
grep -rE '\b(BackgroundComputerVm|ComputerVmSupervisor|ComputerContext|ServerComputer|ComputerManager|ComputerInputDispatcher|ComputerProgramSupport|ComputerItem|ComputerMenu|ComputerScreen|ComputerTerminalScreen|ComputerState|ComputerFamilyExt|ComputerContainerData|NetworkComputerInputGateway)\b' modules --include='*.kt' \
  | wc -l
```
Expected: a positive number (these are deliberately untouched). If 0, something went wrong.

```bash
grep -rE '\bComputer(Workspace|IdeHost|WorkspaceEntry|WorkspaceDocument|IdeSnapshot|CompletionRequest|CompletionResponse|HoverRequest|HoverResponse|DefinitionRequest|DefinitionResponse)\b' modules --include='*.kt' \
  | head -3
```
Expected: hits found — these are the IDE/Workspace types deliberately deferred.

- [ ] **Step 4: review the branch's commit log**

```bash
git log --oneline dev..HEAD
```
Expected: 6 commits, one per task, plus this is the only divergence from `dev`. (The spec commit `473c7a3` lives on `dev` itself.)

- [ ] **Step 5: hand off**

Invoke the `superpowers:finishing-a-development-branch` skill. Present the user with: merge into `dev` locally, push and open PR, keep branch as-is, or discard. Default recommendation: merge locally with `--no-ff` to preserve the per-task history.
