# Cross-Version Duplicate Audit

**Date:** 2026-04-10
**Status:** Completed

## Summary

Files were migrated from loader leaf modules to version-common modules for both v1_20_1 and v1_21_1. This audit classifies remaining duplicates.

## Migration Results

### v1_21_1

| Location | Files | Purpose |
|---|---|---|
| **v1_21_1-common** | 51 files | Shared Minecraft-facing code (blocks, items, menus, screens, networking, context, UI, utils) |
| **v1_21_1-fabric** | 15 files (+ 8 test) | Bootstrap, hooks, registry, network handler, blocks, loot, identity data |
| **v1_21_1-neoforge** | 18 files (+ 6 test) | Bootstrap, hooks, registry, network handler, blocks, loot, identity data |

### v1_20_1

| Location | Files | Purpose |
|---|---|---|
| **v1_20_1-common** | 51 files (+ 1 test) | Shared Minecraft-facing code |
| **v1_20_1-fabric** | 14 files (+ 3 test) | Bootstrap, hooks, registry, network handler, blocks, loot |
| **v1_20_1-forge** | 15 files (+ 3 test) | Bootstrap, hooks, registry, network handler, blocks, loot |

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

### Duplicated Due to Registry Wrapper Types (future unification candidate)

These files are nearly identical across loaders, differing only in how `ModRegistry` entries are accessed (`Supplier` vs `DeferredHolder.get()` vs `RegistryObject.get()`):

| File | Diff |
|---|---|
| `block/ComputerBlock.kt` | `Supplier<BlockEntityType>` vs `DeferredHolder`/`RegistryObject`; 1.21 adds CODEC field |
| `block/ComputerBlockEntity.kt` | `ModRegistry.Menus.COMPUTER` vs `.get()` unwrap |
| `block/AbstractComputerBlock.kt` | Registry wrapper types, menu opening API (Fabric `ExtendedScreenHandlerFactory` vs NeoForge `SimpleMenuProvider` vs Forge `NetworkHooks`), ResourceLocation constructor |
| `block/AbstractComputerBlockEntity.kt` | `onChunkUnloaded()` override (NeoForge only), save/load API (1.20 vs 1.21) |
| `loot/BlockNamedEntityLootCondition.kt` | `ModRegistry.LootConditions.X` vs `.get()` |
| `loot/HasComputerIdLootCondition.kt` | Same registry wrapper diff |
| `loot/PlayerCreativeLootCondition.kt` | Same registry wrapper diff |
| `context/ComputerIdentitySavedData.kt` | `SavedData.Factory` arity (NeoForge patches the constructor) |

**Unification path:** Create a `ModRegistry` abstraction interface in common with `fun <T> get(holder: RegistryEntry<T>): T`. Each loader implements it to unwrap its wrapper type. This would unify ~5 files per version.

### Version-Specific (keep per version)

These classes differ between MC versions due to API changes:

| Concept | v1_20_1 | v1_21.1 |
|---|---|---|
| Data storage | NBT (`stack.tag`) | Data Components (`stack.computerDataTag`) |
| Block interaction | `use()` | `useWithoutItem()` + `useItemOn()` |
| Save/Load | `saveAdditional(tag)` / `load(tag)` | `saveAdditional(tag, registries)` / `loadAdditional(tag, registries)` |
| Network API | `FriendlyByteBuf` + `Class<T>` | `StreamCodec` + lambda readers |
| ResourceLocation | `ResourceLocation(ns, path)` | `ResourceLocation.fromNamespaceAndPath(ns, path)` |

These cannot be unified without a version-abstraction layer (not worth the complexity).

## Delegate Facades Created

| Facade | Location | Purpose |
|---|---|---|
| `ServerNetworking` | `v1_x_x-common/network/server/` | Delegates `sendToPlayer` to loader's `NetworkHandler` |
| `ClientNetworking` | `v1_x_x-common/network/` | Delegates `sendToServer` to loader's `NetworkHandler` |
| `ServerContext.idAllocator` | `v1_21_1-common/context/` | Delegates ID allocation to loader's `ComputerIdentitySavedData` |

## Recommendations

1. **ModRegistry abstraction** — highest-value next step, would unify block/entity/loot files across loaders
2. **v1_21_11 source population** — when adding 1.21.11 support, start from v1_21_1-common as template
3. **Test consolidation** — duplicate test files (`ComputerInputDispatchTest`, `ComputerProgramSupportTest`, etc.) exist in both fabric and neoforge; consider moving to common
