# Compukters Architecture

## Product boundary

Compukters is an in-game Kotlin programming platform. The first source contract is an ordinary bounded `.kt` project with a top-level `fun main()`. The standalone playground and Minecraft computer use the same compiler artifact and VM session boundary.

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

The raw terminal is a synchronous Rust device: cell writes, cursor changes, and input polling never cross into Minecraft. Waiting for an absent input event and foreground process execution are explicit suspension points. Kotlin guests consume ordered raw Text and Key events; there is no compatibility line buffer or second input protocol. The server host never blocks the Minecraft thread.

## Runtime ownership

`ProgramRuntimeHost` owns one current Rust `ComputerMachine`, advances it with bounded guest and maintenance budgets, commits terminal changes once per active server tick, and exposes typed full/delta states and failures through JDK 25 FFM. It does not own a second grid or output transcript. It is loader-independent and server-thread confined.

The Minecraft carrier owns exactly one host and advances it once per server tick. Rust starts `/rom/boot`, compiled from `system/programs/boot.kt`; boot delegates to `/rom/shell`, compiled from `system/programs/shell.kt`. A foreground child suspends its parent and returns a stable integer result when it exits or fails. There is one active foreground lane today, while the runtime contract leaves room for later parallel execution. Reboot replaces the complete machine stack and clears the terminal. Minecraft sends full state to a new viewer and ordered deltas thereafter. All viewers may submit bounded key and text events, which merge in server-arrival order without a client-side echo or an input lease.

The client renders the fixed 51x19 grid in a centered compact panel using one 6x9 `minecraft:uniform` cell scale; the world remains visible through a translucent dim layer. Presentation never changes terminal coordinates or owns a second grid.

The Rust VM owns verification, the Tier 0 interpreter, managed memory and collection, quotas, traps/faults, capability suspension, and host-neutral sessions. Future JIT/AOT tiers must remain behind the same verified artifact and session contract.

The Rust runtime also owns the guest filesystem and its persistence. Minecraft stores only a stable 128-bit `ComputerId`; guest paths and bytes never enter block-entity NBT or a JVM-side mirror. Every computer sees an immutable packaged `/rom` and a private persistent `/home`. The world store lives under `<world>/compukters/filesystems`, performs bounded I/O on its own worker, flushes active generations on world saves, and drains, flushes, and closes before server shutdown completes. Removing a computer through the player destruction lifecycle closes its machine before creating a recoverable tombstone; ordinary block-entity removal during chunk unload only closes the current machine and preserves its filesystem.

The versioned FFM ABI exposes opaque world-store lifecycle operations and machine creation inside a store. Kotlin can select a world store, identify a computer, request flush/tombstone/recovery, and start a machine, but it cannot perform arbitrary guest file operations. Guest code reaches Rust-owned state only through declared capabilities. The initial filesystem facade exposes bounded read-only `stat` and `list`; the shell maps their stable integer results to user-facing diagnostics. A narrow lower-level operation can install an executable already present in immutable ROM into a new writable path without accepting host bytes or replacing an existing file; it is used to seed integration-test `/home` state and is not part of the public guest facade. Compilation, a shared server artifact cache, and general user-program installation remain in issue #522.

Boot and shell are ordinary no-std Kotlin programs. Shell owns line editing, authoritative echo, prompts, and built-ins; non-built-in commands resolve to `/home/<name>` unless the user supplies an absolute path. `Process.run(path, capabilities)` accepts an explicit integer capability mask, executes one verified extensionless artifact as a foreground child, and returns a stable integer result code without exposing VM internals. Terminal, process, and filesystem declarations live in the `guest-api-core` metadata bundle so the compiler and future IDE autocomplete consume the same Kotlin API surface. General stream handles, pipes, process redirection, compilation, and cache policy remain later layers.

## Module ownership

| Module | Purpose |
|---|---|
| `compiler-artifact` | Canonical artifact model, validation, and encoding |
| `compiler-client` | Bounded controller and protocol for the isolated compiler worker |
| `compiler-k2` | Pinned K2/IR integration and Compukter lowering |
| `guest-api-core` | Trusted Guest Kotlin facade declarations and compiler source metadata |
| `native-runtime` | Kotlin-facing JDK 25 FFM VM session, opaque world-store lifecycle, and trusted host capabilities |
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
