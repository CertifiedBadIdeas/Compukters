# Feature-First Package Restructuring — Design Spec

**Date:** 2026-04-15  
**Status:** Draft  
**Scope:** All modules (v1_21_1-common, core, v1_21_1-neoforge). Compiler unchanged.

## Problem

Current package layout is **type-based**: blocks in `block/`, menus in `menu/`, screens in `gui/screen/`, items in `item/`.
This makes it hard to:

1. **Find all classes for a feature** — have to jump across 5+ packages to see everything related to "computer"
2. **Know where to put new classes** — unclear whether a new computer-related class goes in `menu/`, `data/`, `context/`, or somewhere else

With new blocks/devices planned, this problem will multiply.

## Solution

Switch to **feature-first** layout: group all classes belonging to a single block/device into one top-level package.
Shared infrastructure stays in cross-cutting packages at the module root.

## Deliverables

1. **File moves** — all source + test files migrated to new packages
2. **Import updates** — all references updated across all modules
3. **ARCHITECTURE.md update** — reflect new structure
4. **Package Guideline doc** — `docs/PACKAGE-GUIDELINE.md` with rules for where to place new files
5. **ArchitectureBoundaryTest update** — if package path rules change

## Module: v1_21_1-common

### Before → After mapping

| Current package | New package | Files |
|---|---|---|
| `common.block.*` | `common.computer.block.*` | AbstractComputerBlock, ComputerBlock, AbstractComputerBlockEntity, ComputerBlockEntity, ComputerState, ComputerFamilyExt |
| `common.item.*` | `common.computer.item.*` | AbstractComputerItem, ComputerItem |
| `common.menu.*` | `common.computer.menu.*` | AbstractComputerMenu, ComputerMenu, ComputerMenuWithoutInventory, ServerInputState, SingleContainerData |
| `common.gui.screen.*` | `common.computer.screen.*` | ComputerScreen, ComputerWorkbenchScreen |
| `common.gui.input.*` | `common.computer.input.*` | ClientInputHandler |
| `common.infrastructure.input.*` | `common.computer.input.*` | NetworkComputerInputGateway |
| `common.computer.*` | `common.computer.context.*` | ServerComputer |
| `common.context.*` | `common.computer.context.*` | ComputerManager, ServerContext, ComputerIdentitySavedData |
| `common.data.*` | `common.computer.data.*` | ComputerContainerData, IContainerData |
| `common.loot.*` | `common.computer.loot.*` | PlayerCreativeLootCondition, HasComputerIdLootCondition, BlockNamedEntityLootCondition, ConstantLootConditionSerializer |
| `common.network.server.ComputerServerMessage` | `common.computer.network.server.*` | ComputerServerMessage, KeyEventServerMessage, MouseEventServerMessage, ComputerActionServerMessage, PasteEventComputerMessage, ComputerWorkspaceServerMessage |
| `common.network.client.ComputerTerminalClientMessage` | `common.computer.network.client.*` | ComputerTerminalClientMessage, ComputerWorkspaceClientMessage |
| `common.network.NetworkMessage` | `common.network.*` | NetworkMessage, NetworkMessages, MessageType |
| `common.network.ClientNetworking` | `common.network.*` | ClientNetworking |
| `common.network.server.ServerNetworking` | `common.network.*` | ServerNetworking, ServerNetworkContext |
| `common.network.client.ClientNetworkContext` | `common.network.*` | ClientNetworkContext, ClientNetworkContextImpl |
| `common.network.client.ChatTableClientMessage` | `common.network.text.*` | ChatTableClientMessage, ChatHelpers, ClientTableFormatter |
| `common.network.text.*` | `common.network.text.*` | TableFormatter, ServerTableFormatter, ClientTableFormatter, TableBuilder |
| `common.gui.TerminalState` | `common.ui.TerminalState` | TerminalState |
| `common.ui.render.*` | `common.ui.render.*` | FixedWidthFontRenderer, WorkbenchTerminalRenderer |
| `common.ui.dsl.*` | `common.ui.dsl.*` | UiRenderer |
| `common.infrastructure.coroutines.*` | `common.infrastructure.coroutines.*` | MinecraftMainDispatcher |
| `common.infrastructure.workbench.*` | `common.infrastructure.workbench.*` | WorkbenchGateways |
| `common.platform.*` | `common.platform.*` | MinecraftInputProvider |
| `common.binding.*` | `common.binding.*` | ModObjects |
| `common.utils.*` | `common.utils.*` | NBTUtils, CommandUtils, BufferUtils, LevelUtils, BlockEntityUtils |
| `common.Extensions` | `common.Extensions` | Extensions |

### New tree (v1_21_1-common)

```
ru/lazyhat/compukterkraft/common/
├── computer/
│   ├── block/        AbstractComputerBlock, ComputerBlock, AbstractComputerBlockEntity,
│   │                 ComputerBlockEntity, ComputerState, ComputerFamilyExt
│   ├── item/         AbstractComputerItem, ComputerItem
│   ├── menu/         AbstractComputerMenu, ComputerMenu, ComputerMenuWithoutInventory,
│   │                 ServerInputState, SingleContainerData
│   ├── screen/       ComputerScreen, ComputerWorkbenchScreen
│   ├── input/        ClientInputHandler, NetworkComputerInputGateway
│   ├── network/
│   │   ├── client/   ComputerTerminalClientMessage, ComputerWorkspaceClientMessage
│   │   └── server/   ComputerServerMessage, KeyEventServerMessage, MouseEventServerMessage,
│   │                 ComputerActionServerMessage, PasteEventComputerMessage,
│   │                 ComputerWorkspaceServerMessage
│   ├── context/      ServerComputer, ComputerManager, ServerContext, ComputerIdentitySavedData
│   ├── data/         ComputerContainerData, IContainerData
│   └── loot/         PlayerCreativeLootCondition, HasComputerIdLootCondition,
│                     BlockNamedEntityLootCondition, ConstantLootConditionSerializer
├── network/
│   ├── text/         TableFormatter, ServerTableFormatter, ClientTableFormatter,
│   │                 TableBuilder, ChatTableClientMessage, ChatHelpers
│   ├── NetworkMessage, NetworkMessages, MessageType
│   ├── ClientNetworking, ServerNetworking
│   └── ServerNetworkContext, ClientNetworkContext, ClientNetworkContextImpl
├── ui/
│   ├── render/       FixedWidthFontRenderer, WorkbenchTerminalRenderer
│   ├── dsl/          UiRenderer
│   └── TerminalState
├── infrastructure/
│   ├── coroutines/   MinecraftMainDispatcher
│   └── workbench/    WorkbenchGateways
├── platform/         MinecraftInputProvider
├── binding/          ModObjects
└── utils/            NBTUtls, CommandUtils, BufferUtils, LevelUtils, BlockEntityUtils
└── Extensions.kt
```

## Module: core

### Before → After mapping

| Current package | New package | Files |
|---|---|---|
| `core.application.runtime.*` | `core.computer.runtime.*` | ComputerProgramSupport, HostCallDispatcher |
| `core.application.input.*` | `core.computer.input.*` | ComputerInputModels, ComputerInputGateway |
| `core.application.workbench.*` | `core.computer.workbench.*` | WorkbenchStore, WorkbenchState, WorkbenchContracts, WorkbenchEditorSupport |
| `core.menu.*` | `core.computer.input.*` | ServerInputHandler |
| `core.context.*` | `core.computer.*` | ComputerContext |
| `core.computer.*` (existing) | `core.computer.*` | ComputerProperties, ComputerEvents (stay) |
| `core.computer.vm.*` | `core.computer.vm.*` | All VM files (stay) |
| Everything else | Same location | gui/, ui/, platform/, bootstrap/, block/, input/, Config, etc. |

### New tree (core)

```
ru/lazyhat/compukterkraft/core/
├── computer/
│   ├── vm/
│   │   ├── api/      VmTerminalApi, VmSystemApi, VmFileSystemApi,
│   │   │             VmPeripheralRegistry, VmProcessApi
│   │   ├── BackgroundComputerVm, VmContext, VmRuntime, VmStateManager,
│   │   │   EventManager, ComputerVmSupervisor, ComputerProfileRegistry,
│   │   │   HostCallManager, ComputerWorkspaceHost, ComputerWorkspaceInitializer,
│   │   │   WorkspaceComputerIdeHost
│   │   └── (no changes within vm/)
│   ├── runtime/      ComputerProgramSupport, HostCallDispatcher  ← from application/runtime/
│   ├── input/        ComputerInputModels, ComputerInputGateway,  ← from application/input/
│   │                 ServerInputHandler                           ← from menu/
│   ├── workbench/    WorkbenchStore, WorkbenchState,              ← from application/workbench/
│   │                 WorkbenchContracts, WorkbenchEditorSupport
│   ├── ComputerProperties, ComputerEvents                        (stay)
│   └── ComputerContext                                            ← from context/
├── gui/              (no changes)
├── ui/               (no changes)
├── platform/         (no changes)
├── bootstrap/        (no changes)
├── block/            ComputerFamily (no change)
├── input/            KeyCodes (no change)
├── language/         LanguageServices (no change)
├── Config, ModConstants, ClientHooks, StringUtil
└── utils/            StringUtil (no change)
```

## Module: v1_21_1-neoforge

### Before → After mapping

| Current package | New package | Files |
|---|---|---|
| `impl.block.*` | `impl.computer.block.*` | NeoForgeComputerBlockEntity |
| Everything else | Same | CompukterKraftMod, ModRegistry, Extensions, Hooks, Registries, NetworkHandler |

## Module: compiler

No changes. Phase-based structure is standard for compiler modules.

## Tests

Tests follow the same package migration as their corresponding source files. Key test files to move:

| Module | Test file | New package |
|---|---|---|
| core | `application/workbench/WorkbenchStoreTest` | `computer/workbench/WorkbenchStoreTest` |
| core | `application/runtime/ComputerProgramSupportTest` | `computer/runtime/ComputerProgramSupportTest` |
| core | `application/input/ComputerInputDispatchTest` | `computer/input/ComputerInputDispatchTest` |
| v1_21_1-common | `menu/MenuSideClientTest` | `computer/menu/MenuSideClientTest` |
| v1_21_1-neoforge | `application/workbench/WorkbenchStoreTest` | `computer/workbench/WorkbenchStoreTest` |
| v1_21_1-neoforge | `application/runtime/ComputerProgramSupportTest` | `computer/runtime/ComputerProgramSupportTest` |
| v1_21_1-neoforge | `application/input/ComputerInputDispatchTest` | `computer/input/ComputerInputDispatchTest` |
| v1_21_1-neoforge | `computer/vm/BackgroundComputerVmTest` | stays |

## Deliverable: Package Guideline

Create `docs/PACKAGE-GUIDELINE.md` with:

1. **Decision tree** — "Where do I put this new file?" flowchart
2. **Rules per module** — what goes where in each module
3. **Feature package template** — boilerplate structure for new devices
4. **Anti-patterns** — what NOT to do (e.g., putting computer-specific code in shared/)

## Migration strategy

1. Move files within each module (update `package` declarations)
2. Fix all imports across all modules
3. Update ArchitectureBoundaryTest if it checks package paths
4. Update ARCHITECTURE.md
5. Create PACKAGE-GUIDELINE.md
6. Run `./gradlew build` to verify

## What does NOT change

- Class names (only package paths change)
- Inheritance hierarchies
- API contracts between modules
- Gradle module boundaries
- Compiler module structure
- Delegate pattern wiring
