# Compukters Architecture

## Product boundary

Compukters is an in-game Kotlin programming platform. The first source contract is an ordinary bounded `.kt` project with a top-level `fun main()`. The current standalone playground and the future Minecraft computer use the same compiler artifact and VM session boundary.

## Compilation and execution

```text
Kotlin .kt project
  -> isolated pinned K2 worker
  -> Kotlin IR lowering
  -> canonical Compukter artifact
  -> ProgramRuntimeHost / VM session
  -> JDK 25 FFM / versioned Rust C ABI
  -> Rust Compukter VM
```

The trusted JVM side owns source snapshots, compiler isolation, diagnostics, Kotlin IR lowering, and artifact production. Kotlin compiler internals do not cross the bounded FFM boundary. The Rust side receives immutable artifact bytes, verifies the complete container, admits it under explicit limits, and owns execution state. Native buffers remain caller-owned and no Rust pointer enters Kotlin.

The guest-visible terminal capability is asynchronous at the VM boundary. `print` and `println` resume immediately after a bounded host response; `readln` suspends the guest until the host supplies a line. The server host never blocks the Minecraft thread.

## Runtime ownership

`ProgramRuntimeHost` owns one current VM session, advances it with bounded guest and maintenance budgets, translates terminal capability requests, buffers bounded UTF-16 output, and exposes typed terminal states and failures. It is loader-independent and server-thread confined.

The next Minecraft layer is a single-computer carrier which owns exactly one host, loads one installed artifact, advances it once per server tick, publishes output before state transitions, and accepts a line only while waiting for input. Threads, coroutines, a shell/process model, persistence, menus, and networking are separate later slices.

The Rust VM owns verification, the Tier 0 interpreter, managed memory and collection, quotas, traps/faults, capability suspension, and host-neutral sessions. Future JIT/AOT tiers must remain behind the same verified artifact and session contract.

## Module ownership

| Module | Purpose |
|---|---|
| `compiler-artifact` | Canonical artifact model, validation, and encoding |
| `compiler-client` | Bounded controller and protocol for the isolated compiler worker |
| `compiler-k2` | Pinned K2/IR integration and Compukter lowering |
| `native-runtime` | Kotlin-facing JDK 25 FFM VM session and trusted host capabilities |
| `core` | Loader-independent server behavior and `ProgramRuntimeHost` |
| `playground` | Standalone compile-and-run entry point with stdin/stdout |
| `v26_1-common` | Loader-independent Minecraft 26.1 adapters and computer carrier |
| `v26_1-neoforge` | NeoForge 26.1 registration, resources, GameTest, and production archive |
| `host/compukter-vm` | Artifact verification and managed Rust execution runtime |
| `host/compukter-ffi` | Versioned C ABI and opaque machine-handle adapter |

Ownership rules:

- `core` must not import `net.minecraft.*`.
- Kotlin modules must not implement another interpreter or mutable guest machine model.
- Minecraft protocol, UI, and assets require deliberate feature designs and live next to their owning feature.
- Compiler-internal FIR/IR types must not leak into the artifact, FFM, or native runtime contract.
- `LegacyImplementationRemovalTest` prevents removed product contours and old package identities from returning.
