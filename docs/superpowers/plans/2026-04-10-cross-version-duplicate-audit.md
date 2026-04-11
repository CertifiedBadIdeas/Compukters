# Cross-Version Duplicate Audit

**Date:** 2026-04-10
**Status:** Completed

## Summary

Files were migrated from loader leaf modules to version-common modules for both v1_20_1 and v1_21_1. This audit classifies remaining duplicates.

All counts below refer to Kotlin files under `src/main/kotlin`.

## Migration Results

### v1_21_1

| Location | Files | Purpose |
|---|---|---|
| **v1_21_1-common** | 62 files | Shared Minecraft-facing code (blocks, block entities, items, menus, screens, networking, context, UI, utils, loot) |
| **v1_21_1-fabric** | 8 files (+ test files) | Bootstrap, client bootstrap, hooks, registry, extensions, network handler, saved-data access adapter |
| **v1_21_1-neoforge** | 10 files (+ test files) | Bootstrap, client bootstrap, hooks, registry, extensions, network handler, block shim, saved-data access adapter |

### v1_20_1

| Location | Files | Purpose |
|---|---|---|
| **v1_20_1-common** | 62 files (+ test files) | Shared Minecraft-facing code (blocks, block entities, items, menus, screens, networking, context, UI, utils, loot) |
| **v1_20_1-fabric** | 7 files (+ test files) | Bootstrap, client bootstrap, hooks, registry, extensions, network handler |
| **v1_20_1-forge** | 9 files (+ test files) | Bootstrap, client bootstrap, hooks, registry, extensions, network handler, block shim |

## Remaining Duplicates Classification

### Intentionally Loader-Specific (keep separate)

These files **must** remain in each loader because they use loader-specific APIs:

| File | Reason |
|---|---|
| `CompukterKraftMod.kt` | Loader entrypoint (implements `ModInitializer` for Fabric, `@Mod` annotation for Forge/NeoForge) |
| `CompukterKraftClientMod.kt` | Client-only loader entrypoint (Fabric only) |
| `ClientRegistry.kt` | Client screen/renderer registration uses loader-specific APIs |
| `ModRegistry.kt` | `DeferredRegister` vs `RegistryObject` vs Fabric direct registration |
| `NetworkHandler.kt` | Packet registration is fundamentally different per loader |
| `Extensions.kt` | Loader-specific extension functions (e.g., Forge `.asResource()`) |
| `FabricCommonHooks.kt` / `ForgeCommonHooks.kt` | Loader lifecycle event registration |
| `ForgeClientHooks.kt` / `ForgeClientRegistry.kt` | NeoForge/Forge client-specific hooks |

### Remaining Loader-Only Shims (intentional)

These files remain in loader modules because they represent real loader/runtime API drift, not missed migration work:

| File | Reason |
|---|---|
| `v1_20_1-forge/block/ForgeComputerBlockEntity.kt` | Forge-only `onChunkUnloaded()` lifecycle shim |
| `v1_21_1-neoforge/block/NeoForgeComputerBlockEntity.kt` | NeoForge-only `onChunkUnloaded()` lifecycle shim |
| `v1_21_1-fabric/context/ComputerIdentitySavedDataAccess.kt` | Fabric saved-data factory/access shape |
| `v1_21_1-neoforge/context/ComputerIdentitySavedDataAccess.kt` | NeoForge saved-data factory/access shape |

All previously duplicated loader-owned block/entity/loot implementations have been centralized into `v1_x_x-common`.

### Version-Specific (keep per version)

These classes differ between MC versions due to API changes:

| Concept | v1_20_1 | v1_21.1 |
|---|---|---|
| Data storage | NBT (`stack.tag`) | Data Components (`stack.computerDataTag`) |
| Block interaction | `use()` | `useWithoutItem()` + `useItemOn()` |
| Save/Load | `saveAdditional(tag)` / `load(tag)` | `saveAdditional(tag, registries)` / `loadAdditional(tag, registries)` |
| Network API | `FriendlyByteBuf` + `Class<T>` | `CustomPacketPayload`/loader codec APIs + lambda readers |
| ResourceLocation | `ResourceLocation(ns, path)` | `ResourceLocation.fromNamespaceAndPath(ns, path)` |

These cannot be unified without a version-abstraction layer (not worth the complexity).

## Delegate Facades Created

| Facade | Location | Purpose |
|---|---|---|
| `ServerNetworking` | `v1_x_x-common/network/server/` | Delegates `sendToPlayer` to loader's `NetworkHandler` |
| `ClientNetworking` | `v1_x_x-common/network/` | Delegates `sendToServer` to loader's `NetworkHandler` |
| `ServerContext.idAllocator` | `v1_21_1-common/context/` | Delegates ID allocation to loader's `ComputerIdentitySavedData` |

## Follow-Up Boundary Decision

The current architectural boundary stops at:

- concrete Minecraft-facing content in `v1_x_x-common`
- bootstrap/binding/wiring and tiny shims in loader modules
- existing descriptor/policy/orchestration logic already in `core`

No additional block/loot behavior extraction into `core` is part of the documented boundary.

## Recommendations

1. **v1_21_11 source population** — when populating the 1.21.11 source modules, start from the now-centralized `v1_21_1-common` as template
2. **Test consolidation** — duplicate test files (`ComputerInputDispatchTest`, `ComputerProgramSupportTest`, etc.) exist in both fabric and neoforge; consider moving to common
3. **Loader shim review** — periodically re-check whether the remaining tiny shims can be collapsed as upstream APIs stabilize
