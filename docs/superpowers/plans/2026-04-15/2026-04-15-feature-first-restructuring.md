# Feature-First Package Restructuring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move all source files to feature-first package layout per the design spec at `docs/superpowers/specs/2026-04-15/2026-04-15-feature-first-restructuring-design.md`.

**Architecture:** Mechanical refactoring — move files, update `package` declarations, fix imports. No logic changes. Dependency order: core → v1_21_1-common → v1_21_1-neoforge.

**Tech Stack:** Kotlin, Gradle, shell (mkdir, mv, sed)

---

## File Structure

No new source files are created. Only existing files are moved between packages and two documentation files are updated/created:

- **Modify:** ~50 source files (package declarations + imports)
- **Modify:** ~80 files total with import updates (cross-references)
- **Modify:** `docs/ARCHITECTURE.md`
- **Create:** `docs/PACKAGE-GUIDELINE.md`
- **Modify:** `modules/core/src/test/.../architecture/ArchitectureBoundaryTest.kt` (if it checks moved packages)

---

## Abbreviations

```
CORE=modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core
CORE_TEST=modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core
COMMON=modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common
COMMON_TEST=modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common
NEO=modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl
NEO_TEST=modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl
```

---

### Task 1: Create feature branch and commit spec

**Files:**
- `docs/superpowers/specs/2026-04-15/2026-04-15-feature-first-restructuring-design.md` (already created)

- [ ] **Step 1: Create and switch to feature branch**

```bash
git checkout -b refactor/feature-first-packages
```

- [ ] **Step 2: Commit the design spec**

```bash
git add docs/superpowers/specs/2026-04-15/2026-04-15-feature-first-restructuring-design.md
git commit -m "docs: add feature-first restructuring design spec"
```

---

### Task 2: Restructure core module — move application/ and related files into computer/

**Goal:** Collapse `application/runtime/`, `application/input/`, `application/workbench/`, `menu/`, and `context/` into `computer/`.

**Files to move (source):**
- `$CORE/application/runtime/ComputerProgramSupport.kt` → `$CORE/computer/runtime/ComputerProgramSupport.kt`
- `$CORE/application/runtime/HostCallDispatcher.kt` → `$CORE/computer/runtime/HostCallDispatcher.kt`
- `$CORE/application/input/ComputerInputModels.kt` → `$CORE/computer/input/ComputerInputModels.kt`
- `$CORE/application/input/ComputerInputGateway.kt` → `$CORE/computer/input/ComputerInputGateway.kt`
- `$CORE/application/workbench/WorkbenchStore.kt` → `$CORE/computer/workbench/WorkbenchStore.kt`
- `$CORE/application/workbench/WorkbenchState.kt` → `$CORE/computer/workbench/WorkbenchState.kt`
- `$CORE/application/workbench/WorkbenchContracts.kt` → `$CORE/computer/workbench/WorkbenchContracts.kt`
- `$CORE/application/workbench/WorkbenchEditorSupport.kt` → `$CORE/computer/workbench/WorkbenchEditorSupport.kt`
- `$CORE/menu/ServerInputHandler.kt` → `$CORE/computer/input/ServerInputHandler.kt`
- `$CORE/context/ComputerContext.kt` → `$CORE/computer/ComputerContext.kt`

**Files to move (tests):**
- `$CORE_TEST/application/runtime/ComputerProgramSupportTest.kt` → `$CORE_TEST/computer/runtime/ComputerProgramSupportTest.kt`
- `$CORE_TEST/application/input/ComputerInputDispatchTest.kt` → `$CORE_TEST/computer/input/ComputerInputDispatchTest.kt`
- `$CORE_TEST/application/workbench/WorkbenchStoreTest.kt` → `$CORE_TEST/computer/workbench/WorkbenchStoreTest.kt`

- [ ] **Step 1: Create target directories**

```bash
cd modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core
mkdir -p computer/runtime computer/input computer/workbench

cd ../../../../test/kotlin/ru/lazyhat/compukterkraft/core
mkdir -p computer/runtime computer/input computer/workbench
```

- [ ] **Step 2: Move source files**

```bash
cd modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core

# application/runtime/ → computer/runtime/
mv application/runtime/ComputerProgramSupport.kt computer/runtime/
mv application/runtime/HostCallDispatcher.kt computer/runtime/

# application/input/ → computer/input/
mv application/input/ComputerInputModels.kt computer/input/
mv application/input/ComputerInputGateway.kt computer/input/

# application/workbench/ → computer/workbench/
mv application/workbench/WorkbenchStore.kt computer/workbench/
mv application/workbench/WorkbenchState.kt computer/workbench/
mv application/workbench/WorkbenchContracts.kt computer/workbench/
mv application/workbench/WorkbenchEditorSupport.kt computer/workbench/

# menu/ → computer/input/
mv menu/ServerInputHandler.kt computer/input/

# context/ → computer/
mv context/ComputerContext.kt computer/

# Clean up empty directories
rmdir application/runtime application/input application/workbench application
rmdir menu context
```

- [ ] **Step 3: Move test files**

```bash
cd modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core

mv application/runtime/ComputerProgramSupportTest.kt computer/runtime/
mv application/input/ComputerInputDispatchTest.kt computer/input/
mv application/workbench/WorkbenchStoreTest.kt computer/workbench/

# Clean up empty directories
rmdir application/runtime application/input application/workbench application
```

- [ ] **Step 4: Update package declarations in moved files**

For each moved file, update the `package` line:

| File | Old package | New package |
|---|---|---|
| `ComputerProgramSupport.kt` | `core.application.runtime` | `core.computer.runtime` |
| `HostCallDispatcher.kt` | `core.application.runtime` | `core.computer.runtime` |
| `ComputerInputModels.kt` | `core.application.input` | `core.computer.input` |
| `ComputerInputGateway.kt` | `core.application.input` | `core.computer.input` |
| `WorkbenchStore.kt` | `core.application.workbench` | `core.computer.workbench` |
| `WorkbenchState.kt` | `core.application.workbench` | `core.computer.workbench` |
| `WorkbenchContracts.kt` | `core.application.workbench` | `core.computer.workbench` |
| `WorkbenchEditorSupport.kt` | `core.application.workbench` | `core.computer.workbench` |
| `ServerInputHandler.kt` | `core.menu` | `core.computer.input` |
| `ComputerContext.kt` | `core.context` | `core.computer` |
| `ComputerProgramSupportTest.kt` | `core.application.runtime` | `core.computer.runtime` |
| `ComputerInputDispatchTest.kt` | `core.application.input` | `core.computer.input` |
| `WorkbenchStoreTest.kt` | `core.application.workbench` | `core.computer.workbench` |

Use sed to batch update:

```bash
cd modules/core

# Source files
find src -name '*.kt' | xargs sed -i \
  -e 's/^package ru\.lazyhat\.compukterkraft\.core\.application\.runtime/package ru.lazyhat.compukterkraft.core.computer.runtime/' \
  -e 's/^package ru\.lazyhat\.compukterkraft\.core\.application\.input/package ru.lazyhat.compukterkraft.core.computer.input/' \
  -e 's/^package ru\.lazyhat\.compukterkraft\.core\.application\.workbench/package ru.lazyhat.compukterkraft.core.computer.workbench/' \
  -e 's/^package ru\.lazyhat\.compukterkraft\.core\.menu/package ru.lazyhat.compukterkraft.core.computer.input/' \
  -e 's/^package ru\.lazyhat\.compukterkraft\.core\.context$/package ru.lazyhat.compukterkraft.core.computer/'
```

- [ ] **Step 5: Update imports within core module**

```bash
cd modules/core

find src -name '*.kt' | xargs sed -i \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.application\.runtime\./import ru.lazyhat.compukterkraft.core.computer.runtime./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.application\.input\./import ru.lazyhat.compukterkraft.core.computer.input./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.application\.workbench\./import ru.lazyhat.compukterkraft.core.computer.workbench./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.menu\./import ru.lazyhat.compukterkraft.core.computer.input./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.context\./import ru.lazyhat.compukterkraft.core.computer./g'
```

- [ ] **Step 6: Compile core module to verify**

```bash
./gradlew :modules:core:compileKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL (or only downstream module failures if cross-module imports exist).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(core): move application/, menu/, context/ into computer/"
```

---

### Task 3: Fix core imports in downstream modules

**Goal:** After core's packages changed, fix all imports in v1_21_1-common and v1_21_1-neoforge that reference the old core packages.

- [ ] **Step 1: Update imports in v1_21_1-common**

```bash
cd modules/v1_21_1/v1_21_1-common

find src -name '*.kt' | xargs sed -i \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.application\.runtime\./import ru.lazyhat.compukterkraft.core.computer.runtime./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.application\.input\./import ru.lazyhat.compukterkraft.core.computer.input./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.application\.workbench\./import ru.lazyhat.compukterkraft.core.computer.workbench./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.menu\./import ru.lazyhat.compukterkraft.core.computer.input./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.context\./import ru.lazyhat.compukterkraft.core.computer./g'
```

- [ ] **Step 2: Update imports in v1_21_1-neoforge**

```bash
cd modules/v1_21_1/v1_21_1-neoforge

find src -name '*.kt' | xargs sed -i \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.application\.runtime\./import ru.lazyhat.compukterkraft.core.computer.runtime./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.application\.input\./import ru.lazyhat.compukterkraft.core.computer.input./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.application\.workbench\./import ru.lazyhat.compukterkraft.core.computer.workbench./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.menu\./import ru.lazyhat.compukterkraft.core.computer.input./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.context\./import ru.lazyhat.compukterkraft.core.computer./g'
```

- [ ] **Step 3: Update imports in v1_21_1-create-neoforge (if any)**

```bash
cd modules/v1_21_1/v1_21_1-create-neoforge

find src -name '*.kt' | xargs sed -i \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.application\.runtime\./import ru.lazyhat.compukterkraft.core.computer.runtime./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.application\.input\./import ru.lazyhat.compukterkraft.core.computer.input./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.application\.workbench\./import ru.lazyhat.compukterkraft.core.computer.workbench./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.menu\./import ru.lazyhat.compukterkraft.core.computer.input./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.core\.context\./import ru.lazyhat.compukterkraft.core.computer./g'
```

- [ ] **Step 4: Compile all to verify core restructuring is complete**

```bash
./gradlew compileKotlin 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: fix core imports in downstream modules"
```

---

### Task 4: Restructure v1_21_1-common — move computer-specific files

**Goal:** Move all computer-specific files from type-based packages into `computer/` feature package.

**Moves:**

| Source | Destination |
|---|---|
| `block/AbstractComputerBlock.kt` | `computer/block/` |
| `block/ComputerBlock.kt` | `computer/block/` |
| `block/AbstractComputerBlockEntity.kt` | `computer/block/` |
| `block/ComputerBlockEntity.kt` | `computer/block/` |
| `block/ComputerState.kt` | `computer/block/` |
| `block/ComputerFamilyExt.kt` | `computer/block/` |
| `item/AbstractComputerItem.kt` | `computer/item/` |
| `item/ComputerItem.kt` | `computer/item/` |
| `menu/AbstractComputerMenu.kt` | `computer/menu/` |
| `menu/ComputerMenu.kt` | `computer/menu/` |
| `menu/ComputerMenuWithoutInventory.kt` | `computer/menu/` |
| `menu/ServerInputState.kt` | `computer/menu/` |
| `menu/SingleContainerData.kt` | `computer/menu/` |
| `gui/screen/ComputerScreen.kt` | `computer/screen/` |
| `gui/screen/ComputerWorkbenchScreen.kt` | `computer/screen/` |
| `gui/input/ClientInputHandler.kt` | `computer/input/` |
| `infrastructure/input/NetworkComputerInputGateway.kt` | `computer/input/` |
| `computer/ServerComputer.kt` | `computer/context/` |
| `context/ComputerManager.kt` | `computer/context/` |
| `context/ServerContext.kt` | `computer/context/` |
| `context/ComputerIdentitySavedData.kt` | `computer/context/` |
| `data/ComputerContainerData.kt` | `computer/data/` |
| `data/IContainerData.kt` | `computer/data/` |
| `loot/PlayerCreativeLootCondition.kt` | `computer/loot/` |
| `loot/HasComputerIdLootCondition.kt` | `computer/loot/` |
| `loot/BlockNamedEntityLootCondition.kt` | `computer/loot/` |
| `loot/ConstantLootConditionSerializer.kt` | `computer/loot/` |

- [ ] **Step 1: Create target directories**

```bash
cd modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common

mkdir -p computer/block computer/item computer/menu computer/screen \
        computer/input computer/context computer/data computer/loot
```

- [ ] **Step 2: Move block files**

```bash
mv block/AbstractComputerBlock.kt computer/block/
mv block/ComputerBlock.kt computer/block/
mv block/AbstractComputerBlockEntity.kt computer/block/
mv block/ComputerBlockEntity.kt computer/block/
mv block/ComputerState.kt computer/block/
mv block/ComputerFamilyExt.kt computer/block/
rmdir block
```

- [ ] **Step 3: Move item files**

```bash
mv item/AbstractComputerItem.kt computer/item/
mv item/ComputerItem.kt computer/item/
rmdir item
```

- [ ] **Step 4: Move menu files**

```bash
mv menu/AbstractComputerMenu.kt computer/menu/
mv menu/ComputerMenu.kt computer/menu/
mv menu/ComputerMenuWithoutInventory.kt computer/menu/
mv menu/ServerInputState.kt computer/menu/
mv menu/SingleContainerData.kt computer/menu/
rmdir menu
```

- [ ] **Step 5: Move screen files**

```bash
mv gui/screen/ComputerScreen.kt computer/screen/
mv gui/screen/ComputerWorkbenchScreen.kt computer/screen/
rmdir gui/screen
```

- [ ] **Step 6: Move input files**

```bash
mv gui/input/ClientInputHandler.kt computer/input/
mv infrastructure/input/NetworkComputerInputGateway.kt computer/input/
rmdir gui/input gui
rmdir infrastructure/input
```

- [ ] **Step 7: Move context files (ServerComputer + context/)**

```bash
# ServerComputer is currently in common/computer/ — rename folder contents
mv computer/ServerComputer.kt computer/context/
mv context/ComputerManager.kt computer/context/
mv context/ServerContext.kt computer/context/
mv context/ComputerIdentitySavedData.kt computer/context/
rmdir context
```

- [ ] **Step 8: Move data and loot files**

```bash
mv data/ComputerContainerData.kt computer/data/
mv data/IContainerData.kt computer/data/
rmdir data

mv loot/PlayerCreativeLootCondition.kt computer/loot/
mv loot/HasComputerIdLootCondition.kt computer/loot/
mv loot/BlockNamedEntityLootCondition.kt computer/loot/
mv loot/ConstantLootConditionSerializer.kt computer/loot/
rmdir loot
```

- [ ] **Step 9: Update package declarations**

```bash
cd modules/v1_21_1/v1_21_1-common

find src/main -name '*.kt' | xargs sed -i \
  -e 's/^package ru\.lazyhat\.compukterkraft\.common\.block$/package ru.lazyhat.compukterkraft.common.computer.block/' \
  -e 's/^package ru\.lazyhat\.compukterkraft\.common\.item$/package ru.lazyhat.compukterkraft.common.computer.item/' \
  -e 's/^package ru\.lazyhat\.compukterkraft\.common\.menu$/package ru.lazyhat.compukterkraft.common.computer.menu/' \
  -e 's/^package ru\.lazyhat\.compukterkraft\.common\.gui\.screen$/package ru.lazyhat.compukterkraft.common.computer.screen/' \
  -e 's/^package ru\.lazyhat\.compukterkraft\.common\.gui\.input$/package ru.lazyhat.compukterkraft.common.computer.input/' \
  -e 's/^package ru\.lazyhat\.compukterkraft\.common\.infrastructure\.input$/package ru.lazyhat.compukterkraft.common.computer.input/' \
  -e 's/^package ru\.lazyhat\.compukterkraft\.common\.computer$/package ru.lazyhat.compukterkraft.common.computer.context/' \
  -e 's/^package ru\.lazyhat\.compukterkraft\.common\.context$/package ru.lazyhat.compukterkraft.common.computer.context/' \
  -e 's/^package ru\.lazyhat\.compukterkraft\.common\.data$/package ru.lazyhat.compukterkraft.common.computer.data/' \
  -e 's/^package ru\.lazyhat\.compukterkraft\.common\.loot$/package ru.lazyhat.compukterkraft.common.computer.loot/'
```

- [ ] **Step 10: Commit (before fixing imports — files are moved, packages declared)**

```bash
git add -A
git commit -m "refactor(common): move computer files into computer/ package"
```

---

### Task 5: Restructure v1_21_1-common — move network and UI files

**Goal:** Flatten network structure, move TerminalState, move text formatters.

**Moves:**

| Source | Destination | Notes |
|---|---|---|
| `network/server/ServerNetworking.kt` | `network/` | Stays in shared network |
| `network/server/ServerNetworkContext.kt` | `network/` | Stays in shared network |
| `network/server/ComputerServerMessage.kt` | `computer/network/server/` | Computer-specific |
| `network/server/KeyEventServerMessage.kt` | `computer/network/server/` | Computer-specific |
| `network/server/MouseEventServerMessage.kt` | `computer/network/server/` | Computer-specific |
| `network/server/ComputerActionServerMessage.kt` | `computer/network/server/` | Computer-specific |
| `network/server/PasteEventComputerMessage.kt` | `computer/network/server/` | Computer-specific |
| `network/server/ComputerWorkspaceServerMessage.kt` | `computer/network/server/` | Computer-specific |
| `network/client/ClientNetworkContext.kt` | `network/` | Shared network |
| `network/client/ClientNetworkContextImpl.kt` | `network/` | Shared network |
| `network/client/ComputerTerminalClientMessage.kt` | `computer/network/client/` | Computer-specific |
| `network/client/ComputerWorkspaceClientMessage.kt` | `computer/network/client/` | Computer-specific |
| `network/client/ChatTableClientMessage.kt` | `network/text/` | Shared |
| `network/client/ChatHelpers.kt` | `network/text/` | Shared |
| `network/client/ClientTableFormatter.kt` | `network/text/` | Shared |
| `gui/TerminalState.kt` | `ui/` | Shared UI |

- [ ] **Step 1: Create target directories**

```bash
cd modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common

mkdir -p computer/network/server computer/network/client
```

- [ ] **Step 2: Move shared network files to network/**

```bash
mv network/server/ServerNetworking.kt network/
mv network/server/ServerNetworkContext.kt network/
mv network/client/ClientNetworkContext.kt network/
mv network/client/ClientNetworkContextImpl.kt network/
```

- [ ] **Step 3: Move computer-specific network files**

```bash
# Server messages → computer/network/server/
mv network/server/ComputerServerMessage.kt computer/network/server/
mv network/server/KeyEventServerMessage.kt computer/network/server/
mv network/server/MouseEventServerMessage.kt computer/network/server/
mv network/server/ComputerActionServerMessage.kt computer/network/server/
mv network/server/PasteEventComputerMessage.kt computer/network/server/
mv network/server/ComputerWorkspaceServerMessage.kt computer/network/server/
rmdir network/server

# Client messages → computer/network/client/
mv network/client/ComputerTerminalClientMessage.kt computer/network/client/
mv network/client/ComputerWorkspaceClientMessage.kt computer/network/client/
```

- [ ] **Step 4: Move chat/table files to network/text/**

```bash
mv network/client/ChatTableClientMessage.kt network/text/
mv network/client/ChatHelpers.kt network/text/
mv network/client/ClientTableFormatter.kt network/text/
rmdir network/client
```

- [ ] **Step 5: Move TerminalState to ui/**

```bash
mv gui/TerminalState.kt ui/
# gui/ directory should be empty now (gui/input and gui/screen already moved)
# If gui/ still exists with no files, remove it
find gui -type d -empty -delete 2>/dev/null || true
```

- [ ] **Step 6: Update package declarations for moved files**

```bash
cd modules/v1_21_1/v1_21_1-common

find src/main -name '*.kt' | xargs sed -i \
  -e 's/^package ru\.lazyhat\.compukterkraft\.common\.network\.server$/package ru.lazyhat.compukterkraft.common.network/' \
  -e 's/^package ru\.lazyhat\.compukterkraft\.common\.network\.client$/package ru.lazyhat.compukterkraft.common.network/' \
  -e 's/^package ru\.lazyhat\.compukterkraft\.common\.gui$/package ru.lazyhat.compukterkraft.common.ui/'
```

Wait — this will incorrectly change the computer-specific network files that moved to `computer/network/server/` and `computer/network/client/`. Those files need different package declarations. We need to be more targeted.

Actually, since the files have already been **moved** to their new locations, and the sed operates on the files **in their new locations**, the sed needs to match the old package and only run on the right files. Let me be more precise:

```bash
cd modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common

# Files now in network/ root (were in network/server/ or network/client/)
sed -i 's/^package ru\.lazyhat\.compukterkraft\.common\.network\.server$/package ru.lazyhat.compukterkraft.common.network/' \
  network/ServerNetworking.kt network/ServerNetworkContext.kt

sed -i 's/^package ru\.lazyhat\.compukterkraft\.common\.network\.client$/package ru.lazyhat.compukterkraft.common.network/' \
  network/ClientNetworkContext.kt network/ClientNetworkContextImpl.kt

# Files now in computer/network/server/ (still had old package common.network.server)
sed -i 's/^package ru\.lazyhat\.compukterkraft\.common\.network\.server$/package ru.lazyhat.compukterkraft.common.computer.network.server/' \
  computer/network/server/*.kt

# Files now in computer/network/client/ (still had old package common.network.client)
sed -i 's/^package ru\.lazyhat\.compukterkraft\.common\.network\.client$/package ru.lazyhat.compukterkraft.common.computer.network.client/' \
  computer/network/client/ComputerTerminalClientMessage.kt \
  computer/network/client/ComputerWorkspaceClientMessage.kt

# Chat/table files moved to network/text/ (were in network/client/)
sed -i 's/^package ru\.lazyhat\.compukterkraft\.common\.network\.client$/package ru.lazyhat.compukterkraft.common.network.text/' \
  network/text/ChatTableClientMessage.kt network/text/ChatHelpers.kt network/text/ClientTableFormatter.kt

# TerminalState moved from gui/ to ui/
sed -i 's/^package ru\.lazyhat\.compukterkraft\.common\.gui$/package ru.lazyhat.compukterkraft.common.ui/' \
  ui/TerminalState.kt
```

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(common): restructure network and UI packages"
```

---

### Task 6: Fix all imports in v1_21_1-common

**Goal:** Update all import statements within v1_21_1-common to reflect the new package locations.

- [ ] **Step 1: Fix imports for computer-specific packages**

```bash
cd modules/v1_21_1/v1_21_1-common

find src -name '*.kt' | xargs sed -i \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.block\./import ru.lazyhat.compukterkraft.common.computer.block./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.item\./import ru.lazyhat.compukterkraft.common.computer.item./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.menu\./import ru.lazyhat.compukterkraft.common.computer.menu./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.gui\.screen\./import ru.lazyhat.compukterkraft.common.computer.screen./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.gui\.input\./import ru.lazyhat.compukterkraft.common.computer.input./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.infrastructure\.input\./import ru.lazyhat.compukterkraft.common.computer.input./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.data\./import ru.lazyhat.compukterkraft.common.computer.data./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.loot\./import ru.lazyhat.compukterkraft.common.computer.loot./g'
```

- [ ] **Step 2: Fix imports for context/computer packages**

The `common.computer.*` import (ServerComputer) needs to become `common.computer.context.*`, and `common.context.*` also becomes `common.computer.context.*`:

```bash
find src -name '*.kt' | xargs sed -i \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.context\./import ru.lazyhat.compukterkraft.common.computer.context./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.computer\.ServerComputer/import ru.lazyhat.compukterkraft.common.computer.context.ServerComputer/g'
```

- [ ] **Step 3: Fix imports for network restructuring**

```bash
find src -name '*.kt' | xargs sed -i \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.server\.ServerNetworking/import ru.lazyhat.compukterkraft.common.network.ServerNetworking/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.server\.ServerNetworkContext/import ru.lazyhat.compukterkraft.common.network.ServerNetworkContext/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.client\.ClientNetworkContext$/import ru.lazyhat.compukterkraft.common.network.ClientNetworkContext/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.client\.ClientNetworkContextImpl/import ru.lazyhat.compukterkraft.common.network.ClientNetworkContextImpl/g'
```

Computer-specific network messages moved from `common.network.server.*` and `common.network.client.*` to `common.computer.network.*`:

```bash
find src -name '*.kt' | xargs sed -i \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.server\.ComputerServerMessage/import ru.lazyhat.compukterkraft.common.computer.network.server.ComputerServerMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.server\.KeyEventServerMessage/import ru.lazyhat.compukterkraft.common.computer.network.server.KeyEventServerMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.server\.MouseEventServerMessage/import ru.lazyhat.compukterkraft.common.computer.network.server.MouseEventServerMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.server\.ComputerActionServerMessage/import ru.lazyhat.compukterkraft.common.computer.network.server.ComputerActionServerMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.server\.PasteEventComputerMessage/import ru.lazyhat.compukterkraft.common.computer.network.server.PasteEventComputerMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.server\.ComputerWorkspaceServerMessage/import ru.lazyhat.compukterkraft.common.computer.network.server.ComputerWorkspaceServerMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.client\.ComputerTerminalClientMessage/import ru.lazyhat.compukterkraft.common.computer.network.client.ComputerTerminalClientMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.client\.ComputerWorkspaceClientMessage/import ru.lazyhat.compukterkraft.common.computer.network.client.ComputerWorkspaceClientMessage/g'
```

Chat/table moved to `network.text`:

```bash
find src -name '*.kt' | xargs sed -i \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.client\.ChatTableClientMessage/import ru.lazyhat.compukterkraft.common.network.text.ChatTableClientMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.client\.ChatHelpers/import ru.lazyhat.compukterkraft.common.network.text.ChatHelpers/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.client\.ClientTableFormatter/import ru.lazyhat.compukterkraft.common.network.text.ClientTableFormatter/g'
```

- [ ] **Step 4: Fix TerminalState import**

```bash
find src -name '*.kt' | xargs sed -i \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.gui\.TerminalState/import ru.lazyhat.compukterkraft.common.ui.TerminalState/g'
```

- [ ] **Step 5: Move test file**

```bash
cd modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common
mkdir -p computer/menu
mv menu/MenuSideClientTest.kt computer/menu/

sed -i 's/^package ru\.lazyhat\.compukterkraft\.common\.menu/package ru.lazyhat.compukterkraft.common.computer.menu/' \
  computer/menu/MenuSideClientTest.kt

# Fix imports in test files too
cd modules/v1_21_1/v1_21_1-common
find src/test -name '*.kt' | xargs sed -i \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.menu\./import ru.lazyhat.compukterkraft.common.computer.menu./g'

rmdir src/test/kotlin/ru/lazyhat/compukterkraft/common/menu 2>/dev/null || true
```

- [ ] **Step 6: Compile common module to verify**

```bash
./gradlew :modules:v1_21_1:v1_21_1-common:compileKotlin 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL (or only downstream failures).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(common): fix all internal imports after restructuring"
```

---

### Task 7: Fix common imports in v1_21_1-neoforge

**Goal:** After common's packages changed, fix all imports in v1_21_1-neoforge that reference old common packages.

- [ ] **Step 1: Update imports in neoforge source and test files**

```bash
cd modules/v1_21_1/v1_21_1-neoforge

find src -name '*.kt' | xargs sed -i \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.block\./import ru.lazyhat.compukterkraft.common.computer.block./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.item\./import ru.lazyhat.compukterkraft.common.computer.item./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.menu\./import ru.lazyhat.compukterkraft.common.computer.menu./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.gui\.screen\./import ru.lazyhat.compukterkraft.common.computer.screen./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.gui\.input\./import ru.lazyhat.compukterkraft.common.computer.input./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.infrastructure\.input\./import ru.lazyhat.compukterkraft.common.computer.input./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.data\./import ru.lazyhat.compukterkraft.common.computer.data./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.loot\./import ru.lazyhat.compukterkraft.common.computer.loot./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.context\./import ru.lazyhat.compukterkraft.common.computer.context./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.computer\.ServerComputer/import ru.lazyhat.compukterkraft.common.computer.context.ServerComputer/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.gui\.TerminalState/import ru.lazyhat.compukterkraft.common.ui.TerminalState/g'
```

Also fix network imports:

```bash
find src -name '*.kt' | xargs sed -i \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.server\.ServerNetworking/import ru.lazyhat.compukterkraft.common.network.ServerNetworking/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.server\.ServerNetworkContext/import ru.lazyhat.compukterkraft.common.network.ServerNetworkContext/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.client\.ClientNetworkContext$/import ru.lazyhat.compukterkraft.common.network.ClientNetworkContext/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.client\.ClientNetworkContextImpl/import ru.lazyhat.compukterkraft.common.network.ClientNetworkContextImpl/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.server\.ComputerServerMessage/import ru.lazyhat.compukterkraft.common.computer.network.server.ComputerServerMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.server\.KeyEventServerMessage/import ru.lazyhat.compukterkraft.common.computer.network.server.KeyEventServerMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.server\.MouseEventServerMessage/import ru.lazyhat.compukterkraft.common.computer.network.server.MouseEventServerMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.server\.ComputerActionServerMessage/import ru.lazyhat.compukterkraft.common.computer.network.server.ComputerActionServerMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.server\.PasteEventComputerMessage/import ru.lazyhat.compukterkraft.common.computer.network.server.PasteEventComputerMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.server\.ComputerWorkspaceServerMessage/import ru.lazyhat.compukterkraft.common.computer.network.server.ComputerWorkspaceServerMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.client\.ComputerTerminalClientMessage/import ru.lazyhat.compukterkraft.common.computer.network.client.ComputerTerminalClientMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.client\.ComputerWorkspaceClientMessage/import ru.lazyhat.compukterkraft.common.computer.network.client.ComputerWorkspaceClientMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.client\.ChatTableClientMessage/import ru.lazyhat.compukterkraft.common.network.text.ChatTableClientMessage/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.client\.ChatHelpers/import ru.lazyhat.compukterkraft.common.network.text.ChatHelpers/g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.network\.client\.ClientTableFormatter/import ru.lazyhat.compukterkraft.common.network.text.ClientTableFormatter/g'
```

- [ ] **Step 2: Also fix imports in v1_21_1-create-neoforge**

```bash
cd modules/v1_21_1/v1_21_1-create-neoforge

find src -name '*.kt' | xargs sed -i \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.block\./import ru.lazyhat.compukterkraft.common.computer.block./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.item\./import ru.lazyhat.compukterkraft.common.computer.item./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.menu\./import ru.lazyhat.compukterkraft.common.computer.menu./g' \
  -e 's/import ru\.lazyhat\.compukterkraft\.common\.context\./import ru.lazyhat.compukterkraft.common.computer.context./g'
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: fix common imports in neoforge modules"
```

---

### Task 8: Restructure v1_21_1-neoforge — move block/ to computer/block/

**Goal:** Move `impl/block/` to `impl/computer/block/` for consistency.

- [ ] **Step 1: Move file**

```bash
cd modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl
mkdir -p computer/block
mv block/NeoForgeComputerBlockEntity.kt computer/block/
rmdir block
```

- [ ] **Step 2: Update package declaration**

```bash
sed -i 's/^package ru\.lazyhat\.compukterkraft\.impl\.block$/package ru.lazyhat.compukterkraft.impl.computer.block/' \
  computer/block/NeoForgeComputerBlockEntity.kt
```

- [ ] **Step 3: Fix imports referencing old package**

```bash
cd modules/v1_21_1/v1_21_1-neoforge
find src -name '*.kt' | xargs sed -i \
  -e 's/import ru\.lazyhat\.compukterkraft\.impl\.block\./import ru.lazyhat.compukterkraft.impl.computer.block./g'
```

- [ ] **Step 4: Fix neoforge test files referencing impl.block**

```bash
cd modules/v1_21_1/v1_21_1-neoforge
grep -rl 'impl\.block\.' src/test/ 2>/dev/null | head -5
# If any found, fix them with the same sed pattern
find src/test -name '*.kt' | xargs sed -i \
  -e 's/import ru\.lazyhat\.compukterkraft\.impl\.block\./import ru.lazyhat.compukterkraft.impl.computer.block./g'
```

- [ ] **Step 5: Move neoforge test files mirroring application/ structure**

The neoforge test tree has files under `impl/application/` that mirror core's old structure:

```bash
cd modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl

mkdir -p computer/workbench computer/runtime computer/input

mv application/workbench/WorkbenchStoreTest.kt computer/workbench/
mv application/runtime/ComputerProgramSupportTest.kt computer/runtime/
mv application/input/ComputerInputDispatchTest.kt computer/input/

rmdir application/workbench application/runtime application/input application 2>/dev/null || true

# Update package declarations
sed -i 's/^package ru\.lazyhat\.compukterkraft\.impl\.application\.workbench/package ru.lazyhat.compukterkraft.impl.computer.workbench/' \
  computer/workbench/WorkbenchStoreTest.kt
sed -i 's/^package ru\.lazyhat\.compukterkraft\.impl\.application\.runtime/package ru.lazyhat.compukterkraft.impl.computer.runtime/' \
  computer/runtime/ComputerProgramSupportTest.kt
sed -i 's/^package ru\.lazyhat\.compukterkraft\.impl\.application\.input/package ru.lazyhat.compukterkraft.impl.computer.input/' \
  computer/input/ComputerInputDispatchTest.kt
```

- [ ] **Step 6: Compile full project**

```bash
./gradlew compileKotlin 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(neoforge): move block/ to computer/block/ and restructure tests"
```

---

### Task 9: Update ArchitectureBoundaryTest

**Goal:** Check if `ArchitectureBoundaryTest` enforces package path rules that need updating.

- [ ] **Step 1: Read and review the test**

```bash
cat modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/architecture/ArchitectureBoundaryTest.kt
```

Look for:
- Package path assertions referencing `application.`, `menu.`, `context.`
- Any hardcoded package paths that moved

- [ ] **Step 2: Update any package references that changed**

If the test checks for packages like `core.application.*`, `core.menu.*`, `core.context.*` or `common.block.*`, `common.menu.*`, etc. — update them to the new paths.

Specific patterns to look for and update:
- `core.application.runtime` → `core.computer.runtime`
- `core.application.input` → `core.computer.input`
- `core.application.workbench` → `core.computer.workbench`
- `core.menu` → `core.computer.input`
- `core.context` → `core.computer`
- `common.block` → `common.computer.block`
- `common.item` → `common.computer.item`
- `common.menu` → `common.computer.menu`

- [ ] **Step 3: Run tests**

```bash
./gradlew :modules:core:test 2>&1 | tail -30
```

Expected: All tests pass.

- [ ] **Step 4: Commit if changes were made**

```bash
git add -A
git commit -m "refactor: update ArchitectureBoundaryTest for new packages"
```

---

### Task 10: Run full build verification

**Goal:** Verify the entire project compiles, all tests pass.

- [ ] **Step 1: Full build**

```bash
./gradlew build 2>&1 | tail -40
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: If there are failures, fix remaining import issues**

Search for any remaining old package references:

```bash
grep -rn 'common\.block\.\|common\.item\.\|common\.menu\.\|common\.gui\.\|common\.data\.\|common\.loot\.\|common\.context\.\|core\.application\.\|core\.menu\.\|core\.context\.\|impl\.block\.' \
  --include='*.kt' modules/ | grep -v '/build/' | grep 'import '
```

Fix any remaining occurrences manually.

- [ ] **Step 3: Commit any fixups**

```bash
git add -A
git commit -m "refactor: fix remaining import issues" --allow-empty
```

---

### Task 11: Update ARCHITECTURE.md

**Goal:** Update the architecture doc to reflect the new package structure.

- [ ] **Step 1: Read current ARCHITECTURE.md**

Read `docs/ARCHITECTURE.md` and identify sections that describe package layout.

- [ ] **Step 2: Update module ownership rules**

Update the "Module Ownership Rules" section to reflect feature-first organization:

Replace the bullet about `v1_x_x-common`:
> **`v1_x_x-common`** owns Minecraft-facing version adapters organized by **feature**: each block/device has its own package (e.g., `computer/`) containing block, block entity, item, menu, screen, input, network messages, context, data, and loot. Shared infrastructure lives in cross-cutting packages: `network/` (transport), `ui/` (rendering), `infrastructure/` (coroutines, gateways), `platform/`, `binding/`, `utils/`.

- [ ] **Step 3: Commit**

```bash
git add docs/ARCHITECTURE.md
git commit -m "docs: update ARCHITECTURE.md for feature-first packages"
```

---

### Task 12: Create PACKAGE-GUIDELINE.md

**Goal:** Create a guideline document that tells developers where to put new files.

- [ ] **Step 1: Create the guideline file**

Create `docs/PACKAGE-GUIDELINE.md` with the following content:

```markdown
# Package Guideline

## Decision Tree: "Where do I put this new file?"

```text
Is it specific to one block/device (computer, printer, monitor)?
├── YES → Put it in that device's package
│         e.g., common/computer/block/, common/computer/menu/
│
│   What kind of file is it?
│   ├── Block / BlockEntity        → <device>/block/
│   ├── Item                       → <device>/item/
│   ├── Menu / ContainerData       → <device>/menu/
│   ├── Screen (GUI)               → <device>/screen/
│   ├── Input handler              → <device>/input/
│   ├── Network message (server)   → <device>/network/server/
│   ├── Network message (client)   → <device>/network/client/
│   ├── ServerComputer / Manager   → <device>/context/
│   ├── Loot condition             → <device>/loot/
│   └── Data model                 → <device>/data/
│
└── NO → It's shared infrastructure
    ├── Network transport / protocol → network/
    ├── UI rendering / DSL          → ui/
    ├── Coroutine infra             → infrastructure/
    ├── Platform abstraction        → platform/
    ├── Registry / binding          → binding/
    └── Utility                     → utils/
```

## Adding a New Block/Device

1. Create a new top-level package: `common/<device>/`
2. Inside it, create sub-packages as needed: `block/`, `item/`, `menu/`, `screen/`, `network/`, etc.
3. Keep shared infrastructure in the cross-cutting packages — don't duplicate it per device
4. Register the device's content in `binding/ModObjects.kt`

### Template for a new device:

```text
common/<device>/
├── block/
│   ├── <Device>Block.kt
│   └── <Device>BlockEntity.kt
├── item/
│   └── <Device>Item.kt
├── menu/
│   └── <Device>Menu.kt
├── screen/
│   └── <Device>Screen.kt
├── network/
│   ├── client/
│   │   └── <Device>ClientMessage.kt
│   └── server/
│       └── <Device>ServerMessage.kt
└── context/
    └── Server<Device>.kt (if the device has server-side state)
```

## Module Rules

### core (platform-agnostic)
- `computer/` — all computer-specific logic: VM, runtime, input, workbench
- `gui/`, `ui/` — shared terminal/UI abstractions (no net.minecraft.* imports!)
- `platform/api/` — interfaces for loader-specific services
- `bootstrap/` — content descriptors, mod initialization contracts

### v1_x_x-common (Minecraft-facing, loader-agnostic)
- `computer/` — all computer Minecraft integration (blocks, items, menus, etc.)
- `network/` — shared network transport (not device-specific messages)
- `ui/` — shared rendering (FixedWidthFontRenderer, UiRenderer)
- `infrastructure/` — coroutine dispatchers, workbench gateways
- `platform/` — Minecraft input, platform-specific adapters

### v1_x_x-{loader} (loader-specific)
- `computer/` — loader-specific block entity shims (NeoForgeComputerBlockEntity)
- Root — bootstrap, registry, hooks (small files, rarely grow)

### compiler (standalone)
- `frontend/` — parser, analyzer, compiler, IDE features
- `runtime/` — VM execution, screen buffer, host bridge
- `api/` — AST nodes, tokens, operators

## Anti-Patterns

❌ **Don't put computer-specific code in shared packages.** ComputerTerminalClientMessage goes in `computer/network/client/`, not `network/client/`.

❌ **Don't create device-specific sub-packages in shared infrastructure.** `network/computer/` is wrong — use `computer/network/` instead.

❌ **Don't nest too deep.** Max 3 levels under the feature: `computer/network/server/KeyEventServerMessage.kt` is fine. Adding more nesting is a smell.

❌ **Don't put abstractions and implementations in different feature packages.** `AbstractComputerBlock` belongs in `computer/block/`, not in a separate `abstractions/` package.
```

- [ ] **Step 2: Commit**

```bash
git add docs/PACKAGE-GUIDELINE.md
git commit -m "docs: create PACKAGE-GUIDELINE.md with file placement rules"
```

---

### Task 13: Final verification and cleanup

- [ ] **Step 1: Run full build with tests**

```bash
./gradlew build 2>&1 | tail -40
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Verify no empty directories remain**

```bash
find modules/*/src -type d -empty 2>/dev/null
```

Remove any empties found.

- [ ] **Step 3: Verify no old package references remain (imports AND fully-qualified references)**

```bash
grep -rn 'core\.application\.\|core\.menu\.\|^package.*core\.context$\|common\.block\.\|common\.item\.\|common\.menu\.\|common\.gui\.\|common\.data\.\|common\.loot\.\|common\.context\.\|impl\.block\.\|impl\.application\.' \
  --include='*.kt' modules/ | grep -v '/build/'
```

Expected: No output (all old references are gone).

- [ ] **Step 4: Final commit (if any cleanup)**

```bash
git add -A
git commit -m "refactor: final cleanup after package restructuring" --allow-empty
```
