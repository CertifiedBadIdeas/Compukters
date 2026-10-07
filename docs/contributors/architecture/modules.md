---
layout: default
title: Module ownership
section: contributors
permalink: /ARCHITECTURE/modules/
---

# Module ownership

[← Architecture](../architecture.md)

Minecraft-independent Gradle modules are physically grouped under `modules/common`; all shared and version-specific
Minecraft integration is grouped under `modules/minecraft`. These directories express source ownership only: the
existing Gradle project paths and artifact names remain flat and stable.

| Module | Purpose |
|---|---|
| `native-runtime-api` | Java 21 Kotlin-facing VM session, wire validation, opaque world-store lifecycle, and trusted host capabilities |
| `native-runtime-ffm` | Explicit JDK 25 FFM transport, native resource loading, and FFM integration evidence |
| `native-runtime-jni` | Explicit Java 21 JNI transport, native resource loading, and JNI-to-C-ABI integration evidence |
| `platform-bundle` | Canonical platform bundle model, codec, module graph, identities, and default imports |
| `addon-api` | Minecraft-independent public host contracts and self-contained compile-only SDK artifact |
| `addon-guest-api` | Loader-independent addon bundle model, codec, capability schemas, bindings, and admission limits |
| `addon-gradle-plugin` | Public external-build DSL, generated-source wiring, ABI-lock workflow, and Maven-coordinate SDK boundary |
| `addon-guest-api-fixture` | Test-only neutral addon bundle used by common compiler and IDE verification |
| `platform-k2` | Shared K2 metadata and FIR integration for the Compukters platform |
| `compiler-artifact` | Canonical executable artifact model, validation, and encoding |
| `worker-client` | Generic bounded JVM worker processes, payload publication, framing, deadlines, and immutable values |
| `tooling-runtime` | Packaged shared runtime containing the pinned compiler and analysis workers |
| `compiler-client` | Compiler protocol, controller, project snapshots, compilation identities, and persistent cache |
| `compiler-runtime` | Server-global scheduling, single-flight compilation, persistent cache, and compiler backend lifecycle |
| `compiler-k2-engine` | Shared K2 FIR-to-IR pipeline, platform linking, trusted intrinsics, and Compukter lowering |
| `compiler-k2` | Isolated compiler worker entry point and packaged compiler payload |
| `guest-platform` | Built-in Guest Kotlin declarations and canonical base platform-bundle inputs |
| `ide-core` | Minecraft-independent project, editor, profile resolution, client compilation, and analysis models |
| `ide-analysis-client` | K2-free analysis protocol, controller, scheduling, cancellation, and worker lifetime |
| `ide-analysis-k2` | Isolated K2 Analysis API worker and incremental project workspace |
| `ide-client` | Minecraft-independent IDE workspace, controller, analysis coordination, target, and file-transfer logic |
| `playground` | Standalone compile-and-run entry point with stdin and stdout |
| `core` | Loader-independent server behavior and `ProgramRuntimeHost` |
| `minecraft/shared/common` | Canonical loader-independent Minecraft sources, resources, and tests compiled against every supported game target |
| `minecraft/shared/addon-neoforge-api` | Canonical public Minecraft registration adapter compiled into each supported target artifact |
| `minecraft/shared/neoforge` | Canonical NeoForge integration sources, resources, and tests compiled against every supported loader target |
| `v1_21_1-common` | Minecraft 1.21.1 compatibility adapters over the shared computer carrier |
| `v1_21_1-addon-neoforge-api` | Thin NeoForge 1.21.1 addon SDK adapter |
| `addons/create` | Standalone Gradle build for the independently packaged Create 6.0.x Guest API bundle, kinetic-device adapter, bounded host state, and focused tests |
| `v1_21_1-neoforge` | NeoForge 1.21.1 compatibility adapters, Java 21 JNI packaging, and production archive |
| `v26_1-common` | Minecraft 26.1 compatibility adapters over the shared computer carrier |
| `v26_1-addon-neoforge-api` | Thin NeoForge 26.1.2 addon SDK adapter |
| `v26_1-neoforge` | NeoForge 26.1 compatibility adapters, client UI, GameTests, resources, and production archive |
| `host/compukter-vm` | Artifact verification, managed Rust execution runtime, and VM-owned versioned C ABI in its `ffi` workspace member |

Ownership rules:

- Every module under `modules/common` must remain independent of `net.minecraft.*`.
- Common compiler and IDE modules must use the neutral addon fixture and must not depend on optional loader integrations.
- Version modules must consume neutral `modules/minecraft/shared` roots rather than another version module's source tree.
- Compatibility declarations must remain in the Compukters namespace and must not emit classes beneath `net.minecraft.*`.
- Kotlin modules must not implement another interpreter or mutable guest machine model.
- `worker-client`, `ide-core`, `ide-analysis-client`, and `ide-client` must not acquire K2 implementation dependencies.
- K2 compiler internals belong to `compiler-k2-engine` and `compiler-k2`; K2 Analysis API internals belong to
  `ide-analysis-k2`.
- Minecraft protocol, UI, and assets require deliberate feature designs and live next to their owning feature.
- Compiler-internal FIR and IR types must not leak into the platform bundle, artifact, worker protocol, FFM, or native
  runtime contracts.
- `LegacyImplementationRemovalTest` prevents removed product contours and old package identities from returning.
