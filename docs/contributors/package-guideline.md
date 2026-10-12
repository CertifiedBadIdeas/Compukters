---
layout: default
title: Module and package guide
section: contributors
permalink: /PACKAGE-GUIDELINE/
---

# Module and package guide

<a id="module-ownership"></a>

Compukters product code uses the root package `ru.lazyhat.compukters`. Put a change in the layer that owns its
behavior. The [module inventory](architecture/modules.md) lists the current modules; the
[architecture reading map](architecture.md#reading-map) links to their detailed contracts.

## Placement rules

1. Put execution, verification, managed memory and scheduling semantics in `host/compukter-vm`. Kotlin modules must
   not implement a second interpreter or mirror mutable Guest machine state.
2. Put loader-independent server behavior in `core`, including `ProgramRuntimeHost`. Keep Minecraft types outside
   `modules/common`.
3. Put shared game adapters, rendering and tests in `modules/minecraft/shared`; keep only version compatibility and
   packaging in the `v1_21_1` and `v26_1` leaves. Follow the [target selection instructions](development.md#minecraft-development)
   so IntelliJ indexes the canonical sources against the game version you are editing.
4. Keep portable session models and host contracts in `native-runtime-api`, JDK 25 transport in `native-runtime-ffm`,
   and Java 21 transport in `native-runtime-jni`. The native C ABI belongs to the Rust VM's `ffi` workspace member.
5. Keep K2 compilation/lowering in `compiler-k2-engine` and compiler worker startup in `compiler-k2`. K2 analysis
   belongs to `ide-analysis-k2`; neutral compiler/IDE clients and public contracts must not acquire K2 internals.
6. Define network protocols beside the feature that owns them. Reuse existing shared UI components, colors, font
   metrics and drawing layers; follow the UI development skill required by
   [AGENTS.md](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/AGENTS.md).
7. Prefer focused feature packages such as `device/runtime/program` and `impl/computer` over generic `utils`,
   `platform` or `events` dumping grounds. Keep optional integrations in their independent addon builds.

## Product boundary

Both the client IDE and in-computer compiler produce the same executable artifact:

```text
Guest Kotlin source
  -> isolated K2 compiler worker
  -> verified Compukter artifact
  -> Kotlin VM session / ProgramRuntimeHost
  -> JNI (Java 21) or FFM (JDK 25) / versioned Rust C ABI
  -> managed Rust VM
```

New packages should clarify this path or add a deliberate host capability. A competing execution path requires an
architecture decision. Check [verification](verification.md) for evidence at the boundary you change.
