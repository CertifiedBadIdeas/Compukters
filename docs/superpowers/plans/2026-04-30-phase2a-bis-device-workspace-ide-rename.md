# Phase 2a-bis Implementation Plan — Device Workspace & IDE Rename

Spec: `docs/superpowers/specs/2026-04-30-device-workspace-ide-rename-design.md`

The plan is a strict mechanical sequence. Each task is a single commit verified by `./gradlew test --no-daemon`. The pattern is identical to Phase 2a:

1. `git mv` (or split-write) the file(s)
2. `for sym in ...; do grep -rl --include='*.kt' '\bSym\b' modules | xargs sed -i 's/\bSym\b/NewSym/g'; done`
3. fakes scan: `(Fake|Stub|Mock|Test)Computer*` (must be empty)
4. leftover scan: `\bComputer*\b` for in-scope names (must be empty)
5. `./gradlew test --no-daemon`
6. `git commit`

Always run from worktree root: `/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/phase2a-bis-device-workspace-ide`.

---

## Task 0 — Pre-flight

1. `git status --short` ⇒ clean.
2. `git log --oneline -3` ⇒ HEAD is the plan commit, parent is `9cbd7c7` (the spec on `dev`).
3. `./gradlew test --no-daemon` ⇒ BUILD SUCCESSFUL.

If anything is dirty or red, stop and resolve before Task 1.

---

## Task 1 — Rename Workspace storage types

**Goal:** Rename `ComputerWorkspace.kt` to `DeviceWorkspace.kt` and rename the three storage types in place. IDE types stay in this file for now (split happens in Task 2).

```bash
git mv \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerWorkspace.kt \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceWorkspace.kt

for sym in ComputerWorkspace ComputerWorkspaceEntry ComputerWorkspaceDocument; do
  new=${sym/Computer/Device}
  grep -rl --include='*.kt' "\b$sym\b" modules \
    | xargs --no-run-if-empty sed -i "s/\b$sym\b/$new/g"
done

# Order matters! ComputerWorkspaceEntry / ComputerWorkspaceDocument must be processed
# BEFORE ComputerWorkspace, otherwise `\bComputerWorkspace\b` would match
# `ComputerWorkspaceEntry` partially. Test: `\b` does NOT split between word chars
# (E follows a letter), so `\bComputerWorkspace\b` does NOT match `ComputerWorkspaceEntry`.
# Confirming: `\bComputerWorkspace\b` only matches when followed by non-word char.
# So the for-loop is safe in either order; keeping alphabetical for stability.
```

**Verify:**

```bash
grep -rE '\b(Fake|Stub|Mock|Test)ComputerWorkspace' modules --include='*.kt' || echo "no fakes"
grep -rE '\bComputer(Workspace|WorkspaceEntry|WorkspaceDocument)\b' modules --include='*.kt' || echo OK
```

Both must say `no fakes` / `OK`.

**Tests + commit:**

```bash
./gradlew test --no-daemon
git add -A
git commit -m "refactor: rename Workspace storage types to Device prefix

Renames file ComputerWorkspace.kt -> DeviceWorkspace.kt and storage
types within (still co-located with IDE types; split happens in
Task 2):

- ComputerWorkspace         -> DeviceWorkspace
- ComputerWorkspaceEntry    -> DeviceWorkspaceEntry
- ComputerWorkspaceDocument -> DeviceWorkspaceDocument

Per docs/superpowers/specs/2026-04-30-device-workspace-ide-rename-design.md
Task 1."
```

---

## Task 2 — Extract IDE types into DeviceIdeHost.kt + rename

**Goal:** Split `DeviceWorkspace.kt` (now containing both storage and IDE) into two files, then rename IDE-host types to `Device*`.

**Step 2.1 — split**

In `DeviceWorkspace.kt`, the file currently holds:
- license header + package
- storage data classes (`DeviceWorkspaceEntry`, `DeviceWorkspaceDocument`)
- IDE primitives (`IdeDiagnosticSeverity`, `Diagnostic`, `HighlightTokenKind`, `HighlightToken`, `CompletionItemKind`, `CompletionItem`, `HoverInfo`, `DefinitionTarget`)
- IDE wrappers (`ComputerIdeSnapshot`, `Computer{Completion,Hover,Definition}{Request,Response}`)
- interface `DeviceWorkspace`
- interface `ComputerIdeHost`

Action: create new file `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceIdeHost.kt` with:
- license header + package + `import ru.lazyhat.compukterkraft.lang.api.SourceRange`
- the IDE primitives (8 types)
- the IDE wrappers (renamed in Step 2.2)
- the interface (renamed in Step 2.2)

Then in `DeviceWorkspace.kt` keep ONLY:
- license header + package + `import ru.lazyhat.compukterkraft.lang.api.SourceRange` (or drop if unused after split — `DeviceWorkspaceDocument` does not use SourceRange; check before dropping)
- `DeviceWorkspaceEntry`, `DeviceWorkspaceDocument`, `interface DeviceWorkspace`

**Step 2.2 — rename IDE symbols**

```bash
for sym in ComputerIdeSnapshot ComputerCompletionRequest ComputerCompletionResponse \
           ComputerHoverRequest ComputerHoverResponse \
           ComputerDefinitionRequest ComputerDefinitionResponse \
           ComputerIdeHost; do
  new=${sym/Computer/Device}
  grep -rl --include='*.kt' "\b$sym\b" modules \
    | xargs --no-run-if-empty sed -i "s/\b$sym\b/$new/g"
done
```

**Verify:**

```bash
grep -rE '\b(Fake|Stub|Mock|Test)Computer(IdeHost|IdeSnapshot|Completion|Hover|Definition)' modules --include='*.kt' || echo "no fakes"
grep -rE '\bComputer(IdeHost|IdeSnapshot|CompletionRequest|CompletionResponse|HoverRequest|HoverResponse|DefinitionRequest|DefinitionResponse)\b' modules --include='*.kt' || echo OK
```

Both must say `no fakes` / `OK`.

**Tests + commit:**

```bash
./gradlew test --no-daemon
git add -A
git commit -m "refactor: split IDE types into DeviceIdeHost.kt + Device prefix

Splits modules/compiler/src/main/kotlin/.../lang/runtime/DeviceWorkspace.kt
into two files by responsibility:

- DeviceWorkspace.kt:  storage interface + entry/document data classes.
- DeviceIdeHost.kt:    IDE primitives + IDE-host interface and wrappers.

Renamed within DeviceIdeHost.kt:

- ComputerIdeHost            -> DeviceIdeHost
- ComputerIdeSnapshot        -> DeviceIdeSnapshot
- ComputerCompletionRequest  -> DeviceCompletionRequest
- ComputerCompletionResponse -> DeviceCompletionResponse
- ComputerHoverRequest       -> DeviceHoverRequest
- ComputerHoverResponse      -> DeviceHoverResponse
- ComputerDefinitionRequest  -> DeviceDefinitionRequest
- ComputerDefinitionResponse -> DeviceDefinitionResponse

Pure IDE primitives (Diagnostic, HighlightToken, CompletionItem,
HoverInfo, DefinitionTarget, IdeDiagnosticSeverity, etc.) keep their
unprefixed names per spec.

Per docs/superpowers/specs/2026-04-30-device-workspace-ide-rename-design.md
Task 2."
```

---

## Task 3 — Rename ComputerWorkspaceHost

```bash
git mv \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/ComputerWorkspaceHost.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/DeviceWorkspaceHost.kt

grep -rl --include='*.kt' '\bComputerWorkspaceHost\b' modules \
  | xargs --no-run-if-empty sed -i 's/\bComputerWorkspaceHost\b/DeviceWorkspaceHost/g'

grep -rE '\b(Fake|Stub|Mock|Test)ComputerWorkspaceHost' modules --include='*.kt' || echo "no fakes"
grep -rE '\bComputerWorkspaceHost\b' modules --include='*.kt' || echo OK

./gradlew test --no-daemon
git add -A
git commit -m "refactor: rename ComputerWorkspaceHost to DeviceWorkspaceHost

Per docs/superpowers/specs/2026-04-30-device-workspace-ide-rename-design.md
Task 3."
```

---

## Task 4 — Rename WorkspaceComputerIdeHost

```bash
git mv \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/WorkspaceComputerIdeHost.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/WorkspaceDeviceIdeHost.kt

grep -rl --include='*.kt' '\bWorkspaceComputerIdeHost\b' modules \
  | xargs --no-run-if-empty sed -i 's/\bWorkspaceComputerIdeHost\b/WorkspaceDeviceIdeHost/g'

grep -rE '\b(Fake|Stub|Mock|Test)WorkspaceComputerIdeHost' modules --include='*.kt' || echo "no fakes"
grep -rE '\bWorkspaceComputerIdeHost\b' modules --include='*.kt' || echo OK

./gradlew test --no-daemon
git add -A
git commit -m "refactor: rename WorkspaceComputerIdeHost to WorkspaceDeviceIdeHost

Per docs/superpowers/specs/2026-04-30-device-workspace-ide-rename-design.md
Task 4."
```

---

## Task 5 — Rename `computerId` parameter to `deviceId`

**Goal:** Mass-rename the `\bcomputerId\b` token codebase-wide.

```bash
grep -rl --include='*.kt' '\bcomputerId\b' modules \
  | xargs --no-run-if-empty sed -i 's/\bcomputerId\b/deviceId/g'

grep -rE '\bcomputerId\b' modules --include='*.kt' || echo OK
```

**Note on collisions:** the Phase 2a-bis spec asserts that every `computerId` in the codebase represents a Runtime Device id (verified by inventory: 137 occurrences across Workspace/IDE methods, `BackgroundComputerVm`, `SiteId.target`, VM APIs, networking, tests). If tests reveal a collision, revert that single occurrence.

**Tests + commit:**

```bash
./gradlew test --no-daemon
git add -A
git commit -m "refactor: rename computerId parameter to deviceId

Mechanical mass-rename of the \bcomputerId\b token across modules/.
Every usage today identifies a Runtime Device id; renaming aligns
with the Phase 2a Device* prefix.

Per docs/superpowers/specs/2026-04-30-device-workspace-ide-rename-design.md
Task 5."
```

---

## Task 6 — Sync ARCHITECTURE.md

Edit `docs/ARCHITECTURE.md`:

1. Replace any leftover `ComputerWorkspace` mention with `DeviceWorkspace`.
2. In the `compiler` module package table row for `lang.runtime`, expand the listed types to mention the split (`DeviceWorkspace`, `DeviceIdeHost`).
3. Where `computerId` appears prose-style, replace with "device id".

```bash
grep -n 'ComputerWorkspace\|computerId' docs/ARCHITECTURE.md
# inspect and edit manually (no mass-sed: prose may need rewording)
./gradlew test --no-daemon
git add -A
git commit -m "docs(architecture): sync Device workspace/IDE rename

Per docs/superpowers/specs/2026-04-30-device-workspace-ide-rename-design.md
Task 6."
```

---

## Final verification

```bash
./gradlew clean test --no-daemon

# In-scope leftovers (must all be empty):
grep -rE '\bComputer(Workspace|WorkspaceEntry|WorkspaceDocument|IdeHost|IdeSnapshot|CompletionRequest|CompletionResponse|HoverRequest|HoverResponse|DefinitionRequest|DefinitionResponse|WorkspaceHost)\b' modules --include='*.kt' || echo OK_TYPES
grep -rE '\bWorkspaceComputerIdeHost\b' modules --include='*.kt' || echo OK_IMPL
grep -rE '\bcomputerId\b' modules --include='*.kt' || echo OK_PARAM

# Out-of-scope sanity (must STILL appear):
grep -rE '\b(ComputerVmSupervisor|ComputerManager|ComputerProgramSupport|BackgroundComputerVm|ComputerItem|ComputerMenu|ComputerScreen|ComputerFamilyExt)\b' modules --include='*.kt' | wc -l

# Commits on branch (expect: plan + 6 tasks = 7):
git log --oneline dev..HEAD
```

Then handoff: invoke `superpowers:finishing-a-development-branch`. Default recommendation: merge `--no-ff` into `dev`, drop worktree+branch.
