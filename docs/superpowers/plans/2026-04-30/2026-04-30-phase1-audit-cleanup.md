# Phase 1 — Audit-Driven Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Realign code structure and naming with the Device / Authoring Station domain model from [docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md](../../specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md). No semantic changes — only package moves, gateway renames, and doc updates.

**Architecture:** Three independent refactors, each isolated to its own commit and verifiable by running the project's test suite. The work is mechanical: move `compukterkraft.core.computer.workbench.*` to `compukterkraft.core.workbench.*`, rename two cross-category bridge types, and sync two doc files. The `compiler` and `v1_21_1-common`/`fabric`/`neoforge`/`create-neoforge` modules will see import-path updates only.

**Tech Stack:** Kotlin, Gradle (Kotlin DSL), Architectury Loom; tests run via `./gradlew test`.

---

## Files Touched (Inventory)

### Task 1 — Package move

**Move (32 files, preserved with `git mv`):**
- 22 main files under `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/**`
- 10 test files under `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/**`

Destinations mirror under `.../core/workbench/**` (drop the `computer/` segment).

**Update imports / package decls in:**
- All 32 moved files (their own `package` line)
- 22 additional consumers across modules (see Task 1 Step 4 for the full list)

**Update boundary test if it asserts package layout:**
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt`

### Task 2 — `ComputerControlGateway` → `TargetControlGateway`

**Definition site:**
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/WorkbenchContracts.kt` (after Task 1: `core/workbench/WorkbenchContracts.kt`)

**Usage sites (8 files total):**
- `modules/core/.../core/workbench/WorkbenchOpsGateway.kt`
- `modules/core/.../core/workbench/WorkbenchStore.kt`
- `modules/core/src/test/.../core/workbench/WorkbenchEditorViewModelTestSupport.kt`
- `modules/core/src/test/.../core/workbench/WorkbenchStoreTest.kt`
- `modules/v1_21_1/v1_21_1-common/.../common/infrastructure/workbench/WorkbenchGateways.kt`
- `modules/v1_21_1/v1_21_1-common/src/test/.../common/workbench/WorkbenchSyncIntegrationTest.kt`
- `modules/v1_21_1/v1_21_1-neoforge/src/test/.../impl/computer/workbench/WorkbenchStoreTest.kt`

### Task 3 — `ComputerInputGateway` → `TargetInputGateway`

**Definition site:**
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/input/ComputerInputGateway.kt` → rename file to `TargetInputGateway.kt`

**Usage sites (4 files):**
- `modules/v1_21_1/v1_21_1-common/.../common/computer/input/ClientInputHandler.kt`
- `modules/v1_21_1/v1_21_1-common/.../common/computer/input/NetworkComputerInputGateway.kt`
- `modules/v1_21_1/v1_21_1-common/.../common/workbench/input/NetworkWorkbenchInputGateway.kt`
- `modules/v1_21_1/v1_21_1-common/.../common/workbench/input/WorkbenchClientInputHandler.kt`

### Task 4 — Loader-leaf neoforge test path consistency

**Move:**
- `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/workbench/WorkbenchStoreTest.kt` → `.../impl/workbench/WorkbenchStoreTest.kt`

### Task 5 — Documentation updates

- `docs/ARCHITECTURE.md` — package table referencing `compukterkraft.core.computer.workbench`
- `docs/TODOs.md` — item 8

---

## Execution Notes

**Build/test command for verification (used after each task):**

```bash
./gradlew :compukterkraft-core:test :compukterkraft-v1_21_1-common:test :compukterkraft-v1_21_1-neoforge:test --no-daemon
```

If gradle module names differ from the guess above, run `./gradlew projects` once to discover them. Adjust accordingly.

**Commit policy:** One commit per task. Use `git status` between tasks to confirm clean tree before starting the next.

**Working directory:** Run all commands from the worktree root: `/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/phase1-audit-cleanup` (or wherever the worktree was created).

---

## Task 1: Move `compukterkraft.core.computer.workbench.*` → `compukterkraft.core.workbench.*`

**Files:**
- Move: 32 files under `modules/core/src/{main,test}/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/**`
- Modify: 22 consumer files (see Step 4)
- Inspect: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt`

- [ ] **Step 1: Verify clean working tree**

```bash
git status --short
```
Expected: no output. Abort if anything is uncommitted; commit or stash first.

- [ ] **Step 2: Move files preserving history**

```bash
# main sources
mkdir -p modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench
git mv modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/* \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/

# test sources
mkdir -p modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench
git mv modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench/* \
       modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench/

# remove the now-empty original dirs
rmdir modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench || true
rmdir modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/workbench || true
```
Expected: `git status` shows the 32 files renamed, with R-status (rename detected).

- [ ] **Step 3: Update `package` declarations in moved files**

```bash
# Replace package decls in moved sources only (32 files)
find modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench \
     modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/workbench \
     -name '*.kt' -type f -print0 \
| xargs -0 sed -i 's|^package ru\.lazyhat\.compukterkraft\.core\.computer\.workbench|package ru.lazyhat.compukterkraft.core.workbench|'
```

Verify:
```bash
grep -r '^package ru\.lazyhat\.compukterkraft\.core\.computer\.workbench' modules/core || echo OK
```
Expected: `OK` (no remaining old package decls).

- [ ] **Step 4: Update imports in all consumer files**

```bash
grep -rl --include='*.kt' 'ru\.lazyhat\.compukterkraft\.core\.computer\.workbench' modules \
| xargs sed -i 's|ru\.lazyhat\.compukterkraft\.core\.computer\.workbench|ru.lazyhat.compukterkraft.core.workbench|g'
```

Verify no references remain:
```bash
grep -r --include='*.kt' 'ru\.lazyhat\.compukterkraft\.core\.computer\.workbench' modules || echo OK
```
Expected: `OK`.

Also confirm consumer count matches the audit (22 outside files + the moved files' self-references handled by Step 3):
```bash
grep -rl --include='*.kt' 'ru\.lazyhat\.compukterkraft\.core\.workbench' modules | wc -l
```
Expected: a number ≥ 32 (moved files) + several consumers — the absolute value isn't critical, only that the prior `core.computer.workbench` string is gone everywhere.

- [ ] **Step 5: Inspect ArchitectureBoundaryTest for package assumptions**

```bash
grep -n 'computer\.workbench\|workbench' modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt || echo "no workbench-specific assertions"
```

If any line references `core.computer.workbench` literally, update it to `core.workbench` with `sed -i`. If the test enumerates package roots that should not import each other, add `core.workbench` as a peer to `core.computer` if relevant. If the test is purely structural and uses no workbench-specific literal, skip this step.

- [ ] **Step 6: Build and run all tests**

```bash
./gradlew test --no-daemon
```
Expected: BUILD SUCCESSFUL. Any failure here indicates a missed import or a package-decl mismatch — fix and re-run before committing.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(core): move workbench out of computer package

Workbench is a peer to Computer in the domain model (Authoring Station
vs Runtime Device), not a sub-feature of Computer. Aligns core package
layout with v1_21_1-common, where workbench was already a top-level peer.

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md
Phase 1, item 1."
```

---

## Task 2: Rename `ComputerControlGateway` → `TargetControlGateway`

**Files:**
- Modify (definition): `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/workbench/WorkbenchContracts.kt`
- Modify (usages): 7 other files (see inventory above)

- [ ] **Step 1: Verify clean working tree**

```bash
git status --short
```
Expected: no output.

- [ ] **Step 2: Replace the symbol everywhere**

```bash
grep -rl --include='*.kt' 'ComputerControlGateway' modules \
| xargs sed -i 's/\bComputerControlGateway\b/TargetControlGateway/g'
```

Verify:
```bash
grep -r --include='*.kt' 'ComputerControlGateway' modules || echo OK
grep -rn --include='*.kt' 'TargetControlGateway' modules | wc -l
```
Expected first: `OK`. Second: a positive count matching the previous 35 references.

- [ ] **Step 3: Build and run tests**

```bash
./gradlew test --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: rename ComputerControlGateway to TargetControlGateway

This is a cross-category bridge owned by Authoring Station, used to
control the targeted Runtime Device. The Computer-prefix wrongly implied
Computer ownership.

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md
Phase 1, item 2."
```

---

## Task 3: Rename `ComputerInputGateway` → `TargetInputGateway`

**Files:**
- Move + modify (definition): `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/input/ComputerInputGateway.kt` → `.../core/computer/input/TargetInputGateway.kt`
- Modify (usages): 4 files in `v1_21_1-common`

Note: The file *stays* under `core/computer/input/` for now — that package houses the shared input transport and may be renamed in Phase 2 along with the broader umbrella rename. Phase 1 only renames the type.

- [ ] **Step 1: Verify clean working tree**

```bash
git status --short
```
Expected: no output.

- [ ] **Step 2: Rename the file**

```bash
git mv modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/input/ComputerInputGateway.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/input/TargetInputGateway.kt
```

- [ ] **Step 3: Replace the symbol everywhere**

```bash
grep -rl --include='*.kt' 'ComputerInputGateway' modules \
| xargs sed -i 's/\bComputerInputGateway\b/TargetInputGateway/g'
```

Verify:
```bash
grep -r --include='*.kt' 'ComputerInputGateway' modules || echo OK
```
Expected: `OK`.

- [ ] **Step 4: Sanity-check that the class name inside the file matches the new file name**

```bash
grep -n 'interface TargetInputGateway\|class TargetInputGateway' \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/input/TargetInputGateway.kt
```
Expected: at least one match. If none, the type may have been declared under a different name; open the file and rename the declaration manually.

- [ ] **Step 5: Build and run tests**

```bash
./gradlew test --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: rename ComputerInputGateway to TargetInputGateway

The gateway is a wire-level transport for input events to whichever
Runtime Device the consumer is bound to; it is shared by both Computer
and Workbench. The Computer-prefix wrongly implied Computer ownership.

File location is unchanged; the broader package move (input transport
out of core.computer.input) is deferred to Phase 2.

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md
Phase 1, item 3."
```

---

## Task 4: Move loader-leaf neoforge test for consistency

**Files:**
- Move: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/workbench/WorkbenchStoreTest.kt` → `.../impl/workbench/WorkbenchStoreTest.kt`

- [ ] **Step 1: Verify clean working tree**

```bash
git status --short
```
Expected: no output.

- [ ] **Step 2: Move the file**

```bash
mkdir -p modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/workbench
git mv modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/workbench/WorkbenchStoreTest.kt \
       modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/workbench/WorkbenchStoreTest.kt
rmdir modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/workbench || true
# Only remove the parent if it became empty AND has no other test files
ls modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer 2>/dev/null \
  && echo "impl/computer still has content (OK, leave it)" \
  || rmdir modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer 2>/dev/null
```

- [ ] **Step 3: Update package declaration in moved file**

```bash
sed -i 's|^package ru\.lazyhat\.compukterkraft\.impl\.computer\.workbench|package ru.lazyhat.compukterkraft.impl.workbench|' \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/workbench/WorkbenchStoreTest.kt
```

Verify:
```bash
head -3 modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/workbench/WorkbenchStoreTest.kt
```
Expected: `package ru.lazyhat.compukterkraft.impl.workbench`.

- [ ] **Step 4: Search for any code referencing the old path**

```bash
grep -r --include='*.kt' 'impl\.computer\.workbench' modules || echo OK
```
Expected: `OK`. If something matches, update it the same way.

- [ ] **Step 5: Build and run tests**

```bash
./gradlew :compukterkraft-v1_21_1-neoforge:test --no-daemon || ./gradlew test --no-daemon
```
Expected: BUILD SUCCESSFUL. (The first command fails fast if the module name guess is wrong; the second is the safe fallback.)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(v1_21_1-neoforge): move workbench test out of impl.computer

Mirrors the core package move from Task 1: workbench is a peer to
computer, not nested under it.

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md
Phase 1, item 1."
```

---

## Task 5: Update `docs/ARCHITECTURE.md` package table

**Files:**
- Modify: `docs/ARCHITECTURE.md`

- [ ] **Step 1: Locate the table row to update**

```bash
grep -n 'ck\.core\.computer\.workbench' docs/ARCHITECTURE.md
```
Expected: one or more line numbers (originally line 144 in the unchanged spec, but the Phase 0 commit added a Domain Model section that shifted line numbers).

- [ ] **Step 2: Replace the table entry**

Open `docs/ARCHITECTURE.md`. Find the row:

```
| `compukterkraft.core.computer.workbench`       | IDE/workbench contracts and state                                  |
```

Replace it with two rows reflecting the new layout (and add a peer-disambiguation note):

```
| `compukterkraft.core.workbench`                | Authoring Station contracts and state (peer to `compukterkraft.core.computer`) |
```

If the surrounding `compukterkraft.core.computer` row needs a peer disambiguation too, update its description to clarify it's the Runtime Device side. Use a single, consistent phrasing.

- [ ] **Step 3: Verify no remaining `compukterkraft.core.computer.workbench` references in `docs/`**

```bash
grep -rn --include='*.md' 'ck\.core\.computer\.workbench' docs/
```
Expected: no output. If matches remain (e.g., in older specs), leave them — historical specs are immutable. Only the architecture reference and active TODOs get updated.

- [ ] **Step 4: Commit**

```bash
git add docs/ARCHITECTURE.md
git commit -m "docs(architecture): reflect workbench-as-peer package layout

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md
Phase 1, item 4."
```

---

## Task 6: Update `docs/TODOs.md` item 8

**Files:**
- Modify: `docs/TODOs.md`

- [ ] **Step 1: Read item 8 in context**

```bash
sed -n '12,20p' docs/TODOs.md
```

- [ ] **Step 2: Replace item 8 with current-status framing**

Replace the existing item 8 (the `8. Сделать workbench(компьютерный стол)...` block and its sub-items) with:

```
8. Workbench (компьютерный стол) — реализован как отдельная Authoring Station, см. [docs/superpowers/specs/2026-04-16/2026-04-16-workbench-separate-entity-design.md](../../specs/2026-04-16/2026-04-16-workbench-separate-entity-design.md) и доменную модель в [docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md](../../specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md). Дальнейшие итерации (multi-target, апгрейды, live-загрузка, запуск из IDE) — отдельные фичи, добавляются по мере необходимости.
```

Use a text editor or a careful `sed` to replace the block. After editing, verify:

```bash
grep -A1 '^8\. ' docs/TODOs.md | head -3
```
Expected: shows the new wording.

- [ ] **Step 3: Commit**

```bash
git add docs/TODOs.md
git commit -m "docs(todos): mark workbench as implemented; link domain-model spec

Per docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md
Phase 1, item 5."
```

---

## Final Verification

- [ ] **Step 1: Full test sweep**

```bash
./gradlew clean test --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Confirm no stale references**

```bash
grep -rn --include='*.kt' 'ComputerControlGateway\|ComputerInputGateway' modules || echo "no stale gateway names"
grep -rn --include='*.kt' 'core\.computer\.workbench\|impl\.computer\.workbench' modules || echo "no stale package paths"
```
Both should print `no stale ...`.

- [ ] **Step 3: Review the commit log**

```bash
git log --oneline dev..HEAD
```
Expected: 6 commits, one per task.

- [ ] **Step 4: Push the branch (if desired) and open a PR**

```bash
git push -u origin phase1-audit-cleanup
```

(Skip this step if the user prefers to merge locally or do their own push.)

---

## Out of Scope (Reminder)

The following items are intentionally **deferred to Phase 2 or later** and MUST NOT be touched in this plan:

- Renaming `Computer` → `RuntimeDevice` anywhere in code or user-facing strings.
- Renaming `ComputerProfile` / `ComputerFamily` / `ComputerManager`.
- Introducing the `RuntimeDevice` interface.
- Decoupling `ServerComputer` from `BlockEntity` / `ServerLevel`.
- Renaming `WorkbenchTerminalRenderer` (defer until next UI DSL pass).
- Moving `core.computer.input.*` out of the `computer` package — the input transport package is renamed in Phase 2 along with the umbrella rename.

If something in those areas blocks Phase 1, stop and surface it for discussion rather than expanding scope.
