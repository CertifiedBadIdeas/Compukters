# Minecraft 26.1 and JDK 25 Baseline Migration Design

> Issue: [#514](https://github.com/CertifiedBadIdeas/Compukters/issues/514)

## Summary

Compukters will replace its only active Minecraft baseline, 1.21.1, with the
26.1 family pinned to Minecraft 26.1.2 and NeoForge 26.1.2.97. JDK 25 becomes
the single JVM development and execution baseline for the whole repository.
The current `common` plus `neoforge` module boundary remains, implemented with
Architectury Loom and Architectury Plugin as build-time infrastructure. The
unused Architectury API runtime mod is removed.

This is a replacement migration, not a compatibility expansion. The resulting
repository has one Minecraft source set family, one loader leaf, one Java
toolchain, and one production artifact path.

## Evidence and Pinned Inputs

- Minecraft 26.1 requires Java 25 and ships unobfuscated game executables.
- The current official 26.1 MDK pins Minecraft 26.1.2 and NeoForge 26.1.2.97.
- Architectury Loom 1.17 adds Forge 1.21.x and 26.x support, including the
  unobfuscated production namespace. The latest non-snapshot build in that
  release line is 1.17.491.
- The latest non-snapshot Architectury Plugin build is 3.5.169.
- Existing Gradle 9.7.1 can run on Java 25, and existing Kotlin 2.4.10 can emit
  Java 25 bytecode. Neither requires an upgrade for this migration.

The pinned baseline is therefore:

| Component | Version |
| --- | --- |
| Minecraft | 26.1.2 |
| NeoForge | 26.1.2.97 |
| JDK / JVM bytecode | 25 |
| Architectury Loom | 1.17.491 |
| Architectury Plugin | 3.5.169 |
| Gradle | 9.7.1 |
| Kotlin | 2.4.10 |

Patch upgrades within the 26.1 family remain deliberate changes to the version
catalog. No dynamic or floating Minecraft, NeoForge, or Architectury version is
allowed.

## Build Topology

The versioned module family becomes:

```text
modules/v26_1/
├── v26_1-common
└── v26_1-neoforge
```

The family name describes the supported Minecraft release family rather than
the currently pinned hotfix. This avoids structural renames for a future
26.1.3 patch while still producing archives whose resolved version includes
the exact `26.1.2` baseline.

The build keeps these responsibilities:

- `v26_1-common` owns loader-independent Minecraft block, block-entity,
  persistence, terminal transcript, and carrier adaptation code.
- `v26_1-neoforge` owns NeoForge bootstrap, deferred registration, loader
  metadata, GameTest wiring, resources, and the production mod archive.
- Architectury Loom provides the Minecraft/NeoForge workspace, production
  namespace handling, run configurations, and game-test launch environment.
- Architectury Plugin provides the common-to-NeoForge source transformation.

Only NeoForge is active. No Fabric project is created by this issue. The
inactive Fabric convention may remain only if it is generic and buildable; any
1.21.1-specific or unreferenced catalog entries are removed. The future loader
policy stays in issue #37.

## Architectury Runtime Boundary

Production sources contain no `dev.architectury.*` imports, `@ExpectPlatform`
declarations, Architectury events, networking, registries, or runtime API
calls. Consequently:

- `dev.architectury.loom` and `architectury-plugin` remain build plugins;
- `architectury-neoforge` is removed from `modImplementation`, runtime runs,
  nested dependencies, and the production JAR;
- NeoForge is the only required mod-loader dependency in metadata;
- dependency and JAR inspection must prove that no Architectury API runtime mod
  is shipped transitively.

Removing the runtime dependency must not be coupled to removing the common
module architecture. If Loom or Architectury Plugin exposes a build-time bug,
the migration fixes or isolates that build integration rather than silently
restoring an unused runtime mod.

## Java 25 Policy

JDK 25 is a repository-wide baseline rather than a Minecraft-only exception.

- The Gradle build fails early when its launcher JVM is older than 25.
- The shared Kotlin convention uses `jvmToolchain(25)`.
- Java source/target compatibility and Kotlin JVM target are 25.
- Minecraft compilation, unit tests, GameTest, client/server runs, standalone
  playground, native integration tests, and compiler conformance run with JDK
  25 launchers.
- The isolated K2 compiler worker is launched explicitly with the Java 25
  toolchain instead of Java 17.
- Worker isolation remains classpath isolation: raising the JDK does not allow
  K2 implementation classes into the playground or Minecraft runtime.

No local JDK path is committed. Developers select JDK 25 through `JAVA_HOME`
or a Gradle-discoverable installation. The existing policy of disabling
automatic toolchain downloads remains. Documentation and failure diagnostics
state the required version and how to inspect the active JVM.

Rust compiler settings and the guest Compukter Artifact ABI are independent of
the host JDK version and remain unchanged.

## Unobfuscated 26.1 Production Archive

Minecraft 26.1 uses its production names directly. Architectury Loom 1.17
distinguishes unobfuscated versions from older remapped production pipelines.
The current build assumes `RemapJarTask` is always the final production task;
that assumption must be removed.

For 26.1:

- the final archive is assembled from the normal/shadow production namespace
  output supported by Loom 1.17;
- common, core, native-runtime, Kotlin runtime dependencies, and the current
  host JNI resource are included exactly once;
- verification consumes an abstract final production-archive provider rather
  than depending specifically on `remapJar`;
- artifact names continue to include Minecraft, loader, mod version, and the
  existing development revision suffix policy;
- no obfuscated/intermediary-only task becomes a required publication step.

The exact task graph is accepted only after dependency resolution demonstrates
Loom 1.17's 26.1 task model. A successful archive is not inferred from task
names: its classes, metadata, resources, dependencies, and native entry are
inspected directly.

## Mappings and Version Catalog Cleanup

Parchment is removed. The 26.1 workspace uses Loom's supported unobfuscated
Minecraft production namespace and official names without layering legacy
1.21 mappings.

The version catalog and convention plugins expose only the active family:

- `v261` is the internal version key;
- `minecraft-v261` resolves 26.1.2;
- `neoforge-v261` resolves 26.1.2.97;
- the version convention is `26.1-convention`;
- `v1211`, `v12111`, Parchment, inactive Architectury API, and other
  unbuildable Minecraft target aliases are removed.

Repository verification searches active build files, documentation, metadata,
and source paths for stale 1.21.1, 1.21.11, Java 17, Java 21, and Parchment
configuration. Historical Git data, completed issue links, and migration
documents are not treated as active configuration.

## Minecraft and NeoForge API Port

The source port preserves behavior and adapts APIs only where 26.1 requires it.
The affected surface is intentionally small:

- block and `EntityBlock` construction;
- block-entity type construction and registration;
- server-only ticker selection;
- NBT load/save and registry lookup arguments;
- NeoForge mod construction and event-bus registration;
- GameTest annotations, templates, source set, and server launch properties;
- block/item models, blockstate, localization, loot table, `pack.mcmeta`, and
  loader metadata.

No new computer behavior is introduced. Artifact bytes remain defensively
copied and bounded, output remains a bounded transient UTF-16 transcript, VM
creation remains lazy, each server tick delegates at most once, client ticking
does no VM work, and removal closes the carrier idempotently.

When a 26.1 API changes semantics rather than spelling, a focused failing test
or GameTest assertion is added before changing production behavior. Porting
must not weaken the legacy-removal architecture guard merely to accept stale
source paths.

## Resource and Metadata Migration

The mod metadata declares exact compatible Minecraft and NeoForge ranges for
the pinned 26.1.2 baseline and no Architectury API dependency. Resource and
data pack metadata is updated to the 26.1.2 format expected by the game.

The production archive must contain:

- NeoForge mod metadata;
- the `compukters:compukter` blockstate, block model, item model,
  localization, and loot table;
- common and NeoForge computer classes;
- Kotlin runtime dependencies required by shipped classes;
- exactly one `META-INF/natives/<os>/<arch>/<filename>` entry for the current
  build host.

It must not contain duplicate mod metadata, Architectury API, obsolete 1.21.1
resources, Parchment data, development GameTest classes, or K2 compiler
implementation classes.

## Migration Sequence

The implementation proceeds in dependency order:

1. Pin Loom, Architectury Plugin, Minecraft, NeoForge, and JDK 25 while
   establishing a resolvable 26.1 workspace.
2. Rename the version family, conventions, project accessors, verification
   task references, and documentation; remove old catalog/mapping entries.
3. Adapt the unobfuscated production archive and remove Architectury API from
   runtime dependency graphs.
4. Port common Minecraft code and focused JVM tests.
5. Port NeoForge registration, metadata, resources, and unit tests.
6. Port and run the real GameTest server.
7. Rebuild and inspect the production JAR, then run repository-wide fast and
   full verification.

There is no long-lived dual-baseline state. Temporary investigation changes
may compile only part of the repository, but every committed implementation
checkpoint must have a clearly stated verification slice, and the final state
contains no active 1.21.1 modules.

## Failure Handling

- Unsupported or missing JDK 25 fails before expensive Minecraft dependency
  resolution with a direct version diagnostic.
- Dependency resolution failures identify the exact pinned component rather
  than falling back to a snapshot or dynamic version.
- An incompatible 26.1 source API remains a compile failure until explicitly
  ported; reflection or version checks are not used to preserve 1.21.1 code.
- GameTest startup, registration, native loading, VM execution, and lifecycle
  failures remain distinct test failures.
- Production archive verification fails on missing or duplicate JNI entries,
  missing resources, Architectury API leakage, K2 leakage, or an unexpected
  final archive task/output.

## Verification Strategy

Verification is cumulative:

1. Build-script tests and dependency resolution under a JDK 25 Gradle JVM.
2. Focused `v26_1-common` tests for storage, transcript, block entity, and
   ticker behavior.
3. Focused `v26_1-neoforge` compilation, unit tests, resource processing, and
   production archive build.
4. Real NeoForge GameTest server proving registration, lazy auto-boot,
   server-only execution, removal, and VM close.
5. Production JAR inspection for metadata, resources, classes, runtime
   dependencies, and exactly one packaged JNI library.
6. `verifyLocalFast --rerun-tasks`.
7. `verifyLocalFull --rerun-tasks`, including Rust VM/JNI suites, native
   integration, compiler conformance, and playground end-to-end execution.
8. Clean-worktree, submodule, commit-author, stale-baseline, and dependency
   audits.

The issue is complete only when the real GameTest and full repository profile
pass on JDK 25 and the inspected production JAR is usable without Architectury
API installed.

## Out of Scope

- Fabric or another loader implementation.
- Parallel support for Minecraft 1.21.1 or 1.21.11.
- Minecraft 26.2 or automatic patch tracking.
- Gameplay, terminal UI, project storage, compiler orchestration inside
  Minecraft, or the in-game IDE.
- New guest language features, Artifact ABI changes, VM scheduler changes, or
  additional native release platforms.
- Replacing Architectury Loom with ModDevGradle.

## References

- [Minecraft 26.1 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-1)
- [NeoForge 1.21.11 to 26.1 migration primer](https://docs.neoforged.net/primer/docs/26.1/)
- [Architectury Loom 1.17 release](https://github.com/architectury/architectury-loom/releases/tag/1.17)
- [Official NeoForge 26.1.2 ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-26.1.2-ModDevGradle)
