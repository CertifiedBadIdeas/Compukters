# Compukters Architecture

## Current product boundary

The loadable NeoForge mod is temporarily a platform shell while the managed
runtime is built. Retired K16 and RISC-V implementations are not fallback
paths. The product target is an in-game IDE, shell, and Kotlin `.kts` programs
running on programmable Minecraft computers and smaller controller devices.

## Compilation and execution boundary

Kotlin `.kts` source is parsed and type-checked by a pinned K2 frontend. A
custom Kotlin IR backend emits a versioned Compukter artifact. The standalone
Rust Compukter VM verifies the complete artifact before constructing mutable
execution state.

Kotlin compiler internals do not cross the JNI boundary. The JVM side owns
source handling, compiler integration, and artifact production; the native
side receives a stable artifact format rather than FIR, IR, or JVM bytecode.

## Runtime ownership

Each device owns one VM instance, address space, managed heap, root shell, and
initially at most one foreground child program. `process.run` loads and runs a
compiled `.kts` program within that computer. Deterministic block costs,
cooperative coroutines, bounded capabilities, snapshots, and future execution
tiers remain native-runtime responsibilities.

The first product model is intentionally single-tasking at the device level,
while the artifact and runtime design preserve room for future parallelism.
Interpreter performance remains a design constraint; JIT and AOT tiers may be
added behind the same verified artifact contract.

## Module ownership

| Module | Purpose |
|---|---|
| `host/compukter-vm` | Pinned Compukter VM submodule: artifact verification and managed execution runtime |
| `native-runtime` | Architecture-neutral JVM-side runtime and device models |
| `core` | Minecraft-independent product logic and runtime boundary contracts |
| `v1_21_1-common` | Minecraft 1.21.1 common integration |
| `v1_21_1-neoforge` | NeoForge bootstrap, resources, networking, and platform integration |

Ownership rules:

- `core` must not import `net.minecraft.*`.
- Loader modules own bootstrap, registry, networking, hooks, resources, and
  small unavoidable platform shims.
- Artifact verification, execution, quotas, managed memory, scheduling, and
  snapshots belong to `host/compukter-vm`.
- Kotlin compiler integration stays on the trusted JVM side and must not leak
  compiler-internal representations into the native runtime contract.
- Boundary rules are enforced by `ArchitectureBoundaryTest` in `modules/core`.
