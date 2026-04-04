# Extract Shared Code to Core Module — Design Spec

**Date:** 2026-04-04  
**Status:** Approved  
**Goal:** Move maximum code from version-common modules into the pure `core` module, making the mod version-independent.

## Context

The project has 3 version-common modules (`v1_20_1-common`, `v1_21_1-common`, `v1_21_11-common`) that share large amounts of identical code — code that has zero Minecraft dependencies. Currently ~32 files are already duplicated between `core` and each version-common; another ~12 files in version-common have no MC dependency or a thin one that can be abstracted.

After this work, version-common modules will contain **only** Minecraft-version-specific code (rendering, network serialization, block entities, etc.).

## Approach: Interface Boundary

Create focused interfaces in `core` for the few Minecraft-specific concepts used by otherwise-pure code. Version-common modules implement these interfaces and pass them at construction time or register them via ServiceLoader.

## New Abstractions in Core

### 1. `KeyCodes` (object, not interface)

Concrete object with stable GLFW key code constants. Values are identical across all MC versions.

```kotlin
// core: ck.mod.input.KeyCodes
object KeyCodes {
    const val KEY_ENTER = 257
    const val KEY_KP_ENTER = 335
    const val KEY_BACKSPACE = 259
    const val KEY_DELETE = 261
    const val KEY_TAB = 258
    const val KEY_ESCAPE = 256
    const val KEY_UP = 265
    const val KEY_DOWN = 264
    const val KEY_LEFT = 263
    const val KEY_RIGHT = 262
    const val KEY_PAGE_UP = 266
    const val KEY_PAGE_DOWN = 267
    const val KEY_F4 = 293
    const val KEY_F12 = 301
    const val KEY_S = 83
    const val KEY_SPACE = 32
    const val KEY_A = 65
    const val MOD_CONTROL = 2
}
```

### 2. `FontMetrics` (fun interface)

Abstracts `net.minecraft.client.gui.Font.width(String): Int`.

```kotlin
// core: ck.mod.platform.api.FontMetrics
fun interface FontMetrics {
    fun width(text: String): Int
}
```

Used by: `WorkbenchEditorSupport`, `WorkbenchLayoutModel`.  
Implementation: one-line wrapper in common delegating to MC Font.

### 3. `ServerWorldAccess` (fun interface)

Abstracts `MinecraftServer.getWorldPath(LevelResource.ROOT)`.

```kotlin
// core: ck.mod.platform.api.ServerWorldAccess
fun interface ServerWorldAccess {
    fun getWorldSavePath(): Path
}
```

Used by: `ComputerVmSupervisor`.  
Implementation: `{ server.getWorldPath(LevelResource.ROOT) }` in common.

### 4. `PlatformInputProvider` (interface + ServiceLoader)

Abstracts clipboard, paste detection, and physical key name resolution.

```kotlin
// core: ck.mod.platform.api.PlatformInputProvider
interface PlatformInputProvider {
    fun getClipboardString(): String
    fun isPasteShortcut(keyCode: Int): Boolean
    fun getPhysicalKeyName(keyCode: Int, scanCode: Int): String?
}
```

Used by: `WorkbenchTerminalInputController`, `KeyConverter`, `WorkbenchStore`.  
Loaded via `Services.load<PlatformInputProvider>()`.  
Implementation in common wraps `Minecraft.getInstance()`, `Screen.isPaste()`, `GLFW.glfwGetKeyName()`.

### 5. `ComputerFamily` / `ComputerState` enum split

Both enums move to core as pure enums.

`ComputerFamily.checkUsable(player, server)` — the only MC-dependent method — becomes an extension function in common:

```kotlin
// common: ck.mod.block.ComputerFamilyExt.kt
fun ComputerFamily.checkUsable(player: Player): Boolean { ... }
```

`ComputerState` implements `StringRepresentable` only in common via an adapter:

```kotlin
// common: ck.mod.block.ComputerStateAdapter.kt
class ComputerStateStringRepresentable(val state: ComputerState) : StringRepresentable { ... }
```

## File Movement Plan

### Phase 1: Delete 32 duplicates from version-common

Files already existing identically in both `core` and each version-common. Delete from all 3 version-common modules:

- `ck/mod/Config.kt`
- `ck/mod/ClientHooks.kt`
- `ck/mod/context/ComputerContext.kt`
- `ck/mod/platform/Services.kt`
- `ck/mod/platform/ServiceException.kt`
- `ck/mod/gui/Palette.kt`
- `ck/mod/gui/Colour.kt`
- `ck/mod/gui/FrameInfo.kt`
- `ck/mod/menu/ServerInputHandler.kt`
- `ck/mod/utils/StringUtil.kt`
- `ck/mod/language/LanguageServices.kt`
- `ck/mod/computer/ComputerEvents.kt`
- `ck/mod/application/runtime/ComputerProgramSupport.kt`
- `ck/mod/application/runtime/HostCallDispatcher.kt`
- `ck/mod/application/workbench/WorkbenchState.kt`
- `ck/mod/application/workbench/WorkbenchContracts.kt`
- `ck/mod/application/input/ComputerInputModels.kt`
- `ck/mod/application/input/ComputerInputGateway.kt`
- `ck/mod/computer/vm/VmContext.kt`
- `ck/mod/computer/vm/VmFileSystemApi.kt`
- `ck/mod/computer/vm/VmTerminalApi.kt`
- `ck/mod/computer/vm/VmSystemApi.kt`
- `ck/mod/computer/vm/VmProcessApi.kt`
- `ck/mod/computer/vm/VmRuntime.kt`
- `ck/mod/computer/vm/VmStateManager.kt`
- `ck/mod/computer/vm/EventManager.kt`
- `ck/mod/computer/vm/HostCallManager.kt`
- `ck/mod/computer/vm/ComputerWorkspaceInitializer.kt`
- `ck/mod/computer/vm/ComputerWorkspaceHost.kt`
- `ck/mod/computer/vm/BackgroundComputerVm.kt`
- `ck/mod/computer/vm/WorkspaceComputerIdeHost.kt`
- `ck/mod/computer/vm/VmRuntimeSupport.kt`

### Phase 2: Move pure files from v1_21_1-common to core

These files have zero MC/GLFW imports:

| File | Action |
|------|--------|
| `ComputerProperties.kt` | Copy to core, delete from all commons |
| `ComputerProfileRegistry.kt` | Copy to core, delete from all commons |
| `WorkbenchTerminalLayout.kt` | Copy to core, delete from all commons |
| `TerminalUiBuilder.kt` | Copy to core, delete from all commons |
| `MessageType.kt` | Copy to core, delete from all commons |

### Phase 3: Create abstractions in core, then move refactored files

1. Create `KeyCodes.kt` in core
2. Create `FontMetrics.kt` in core  
3. Create `ServerWorldAccess.kt` in core
4. Create `PlatformInputProvider.kt` in core
5. Move `WorkbenchStore.kt` to core (replace `GLFW.*` → `KeyCodes.*`, add `PlatformInputProvider` for paste detection)
6. Move `WorkbenchEditorSupport.kt` to core (replace `Font` → `FontMetrics`)
7. Move `WorkbenchLayoutModel.kt` to core (replace `Font` → `FontMetrics`)
8. Move `ComputerVmSupervisor.kt` to core (replace `MinecraftServer` → `ServerWorldAccess`)
9. Move `KeyConverter.kt` to core (replace `GLFW.glfwGetKeyName` → `PlatformInputProvider`, `GLFW_KEY_A` → `KeyCodes.KEY_A`)
10. Move `WorkbenchTerminalInputController.kt` to core (replace clipboard/paste/escape → `PlatformInputProvider` + `KeyCodes`)
11. Move `ComputerFamily.kt` enum to core; extract `checkUsable()` as extension in common
12. Move `ComputerState.kt` enum to core; create MC adapter in common

### Phase 4: Add implementations in common

Each version-common adds:
- `MinecraftInputProvider : PlatformInputProvider` (registered via ServiceLoader)
- `MinecraftFontMetrics : FontMetrics` (passed at construction sites)
- `MinecraftServerWorldAccess : ServerWorldAccess` (passed at construction sites)
- `ComputerFamilyExt.kt` — extension function `ComputerFamily.checkUsable(Player)`
- `ComputerStateAdapter.kt` — `StringRepresentable` adapter

### Files that stay in common (~14)

These have deep MC integration and cannot reasonably be abstracted:

- `FixedWidthFontRenderer.kt` — Blaze3D vertex rendering
- `UiRenderer.kt` — GuiGraphics + RenderType
- `WorkbenchTerminalRenderer.kt` — GuiGraphics rendering
- `BlockEntityUtils.kt` — BlockEntity + ticker APIs
- `CommandUtils.kt` — Brigadier command system
- `LevelUtils.kt` — Level.isClientSide
- `NBTUtils.kt` — CompoundTag, ItemStack, DataComponents
- `BufferUtils.kt` — FriendlyByteBuf extensions
- `TerminalState.kt` — FriendlyByteBuf network serialization
- `NetworkMessage.kt` — FriendlyByteBuf packet interface
- `ServerNetworkContext.kt` — ServerPlayer accessor
- `IContainerData.kt` — RegistryFriendlyByteBuf
- `SingleContainerData.kt` — ContainerData adapter
- `ConstantLootConditionSerializer.kt` — Loot system codecs
- `MinecraftMainDispatcher.kt` — Minecraft thread dispatcher

## Dependency Wiring

### Singleton interfaces (ServiceLoader)
- `PlatformInputProvider` — loaded via `Services.load()` in core

### Constructor-injected interfaces
- `FontMetrics` — passed to `WorkbenchLayoutModel`, `WorkbenchEditorSupport`
- `ServerWorldAccess` — passed to `ComputerVmSupervisor`

### Existing convention plugin
`common-convention.gradle.kts` already adds `implementation(project(":core"))` — no build changes needed.

## Migration Order

1. Create 4 abstractions in core (`KeyCodes`, `FontMetrics`, `ServerWorldAccess`, `PlatformInputProvider`)
2. Move 5 pure files to core
3. Move 7 refactored files to core (with MC→interface replacement)
4. Split 2 enums (core enum + common extension/adapter)
5. Delete all 32+12+2=46 duplicate/moved files from all 3 version-common modules
6. Add ~5 adapter/extension files per version-common
7. Run `./gradlew check` to verify

## Verification

- `./gradlew check` must pass
- `core` module must have zero `net.minecraft.*`, `org.lwjgl.*`, `net.minecraftforge.*`, `net.fabricmc.*`, `net.neoforged.*` imports
- Each version-common module should contain only MC-specific code (~14 original + ~5 new adapters ≈ 19 files)
