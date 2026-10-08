---
layout: default
title: Architecture
section: contributors
permalink: /ARCHITECTURE/
---

# Compukters architecture

## Product boundary

Compukters is an in-game Kotlin programming platform with two production authoring paths. Programs created inside a
computer use the Rust-owned `/home` filesystem and the packaged `/rom/edit` and `/rom/kotlinc` tools. The client IDE
owns bounded multi-file projects with `compukter.toml`, an optional `compukter.lock`, and Kotlin sources beneath `src`;
it can analyze and compile a project locally, attach to a computer, and deploy the resulting executable.

Both paths produce the same canonical Compukter artifact and execute through the same VM session boundary. A project
must declare exactly one supported top-level entry point: `fun main()` or
`fun main(args: Array<String>)`, returning `Unit`. The standalone playground uses the same compiler artifact and VM session
contracts without the Minecraft carrier.

## Reading map

Start with the ownership rules below, then open the page for the layer you are changing. This index remains the stable entry point for contributors and agents; detailed contracts have one owning page.

| Question | Owning document |
| --- | --- |
| How do source, platform metadata and libraries become an executable? | [Compiler and platform](architecture/compiler.md) |
| Where do K2, analysis, completion and editor state live? | [Tooling and IDE](architecture/tooling.md) |
| Who advances a machine, handles continuations and owns Guest memory? | [Runtime ownership](architecture/runtime.md) |
| What survives unload or restart, and who closes and flushes it? | [Filesystem and lifetime](architecture/persistence.md) |
| Who performs world actions and owns peripheral/addon resources? | [Minecraft and addons](architecture/world-integration.md) |
| How are callbacks, inline bodies and packaged programs executed? | [Guest execution](architecture/guest-execution.md) |
| Which module should contain a change? | [Module ownership](architecture/modules.md) |
| Which versioned boundary represents a feature? | [ABI reference](architecture/abi.md) and the source owners listed in that reference |

## Platform and K2 tooling

Guest programs target Compukter bytecode. The built-in graph is `kotlin:builtins` → `stdlib:core` → `compukter:core`; optional addons publish admitted data bundles. No host JVM library or addon implementation enters Guest name resolution.

`platform-bundle` owns portable declarations and identities. `platform-k2` adapts that data to the pinned K2 frontend. `compiler-k2-engine` lowers admitted Kotlin IR and links verified library fragments; `compiler-artifact` owns executable encoding and validation. Isolated compiler and analysis workers keep K2 internals out of the mod runtime and public contracts.

Compiler and IDE sessions share native language settings, including the K2 frontend switch for multi-field value classes. Direct MFVC values use nominal inline layouts in compact frames; reference contexts use managed boxes. Their new layouts require Runtime ABI 1.15. Detailed semantics and constraints live in [compiler ownership](architecture/compiler.md) and [Guest Kotlin support](../developers/kotlin-support.md).

## Server compilation

Rust captures the source and filesystem preconditions. The server-global compiler service schedules isolated worker requests and owns the persistent artifact cache. Worker and cache I/O run outside the server tick. Rust re-verifies and installs the result atomically only if the captured preconditions still match.

The IDE compiles bounded multi-file snapshots through the same compiler contract, then verifies and deploys with a target revision and ticket. Analysis has its own worker session and response contract. See [compiler flow](architecture/compiler.md) and [tooling isolation](architecture/tooling.md).

## Runtime ownership

The Rust VM owns verified execution, managed heap and GC, frames, task scheduling, quotas, capability suspension, terminal state and the Guest filesystem. Kotlin owns typed host requests and native-session lifetime; it must not implement a second interpreter or mirror mutable Guest machine state.

A server-scoped actor scheduler serializes each computer’s native work. The Minecraft carrier owns its actor endpoint and machine epoch, submits bounded tick permits, and applies world effects on the server thread. Eligible host continuations reuse remaining frame credit and cumulative quotas; they do not receive a fresh unlimited advance.

Only one cooperative Guest task executes instructions at a time. Waiting transfers execution to another runnable task; host replies retain their task/request identity. Each live foreground program owns its lazily created addon hosts, so exiting a child releases its resources while a suspended parent keeps its own.

See [runtime ownership and scheduling](architecture/runtime.md) for admission, continuations, shutdown barriers, diagnostics and managed memory; [world integration](architecture/world-integration.md) owns physical device effects.

## Filesystem and machine lifetime

Minecraft persists a stable `ComputerId`; Rust persists `/home` beneath the world filesystem store. `/rom` is immutable packaged content. Guest paths and bytes do not enter block-entity NBT or a JVM mirror.

Running computers hibernate on carrier unload and orderly server shutdown, preserving Guest heap, stacks, tasks,
terminal state and host resource descriptions. Restore validates the saved execution and rebinds supported resources
before continuing. Missing, corrupt, incompatible or unsupported checkpoints fall back to fresh ROM execution while
retaining `/home` and logging the failed restoration; explicit reboot also starts fresh execution. Live object identities
survive within that saved execution, not as general durable IDs.

Close barriers drain accepted work before flushing the final generation. An identity remains unavailable until persistence completes, preventing a rapidly reloaded block from opening a second live machine. Player destruction additionally creates a recoverable tombstone. See [filesystem ownership and shutdown](architecture/persistence.md).

## Guest programs and APIs

Boot, shell, compiler command, editor and benchmarks are ordinary packaged Guest programs. `/rom/kotlinc` currently accepts one source file; IDE projects support bounded multi-file snapshots. A child suspends its foreground parent until it finishes.

World capabilities cross typed bounded host interfaces. Device handles retain exact endpoint identity; replacement, renaming or rewiring cannot silently redirect a handle. Discovery does not force chunk loads. Addons own their operations and temporary control leases; upstream mods retain their world and physics state.

[Guest execution](architecture/guest-execution.md) describes callbacks, inlining and packaged tools. [World integration](architecture/world-integration.md) describes peripherals and addon lifetime. Executable API support belongs in [Guest platform support](../developers/guest-platform-support.md), not in this ownership overview.

## Module ownership

Minecraft-independent code lives under `modules/common`; shared and target-specific game integration lives under `modules/minecraft`. Rust lives under `host/compukter-vm`. See the [module inventory and boundary rules](architecture/modules.md) before moving responsibilities.

### Program-owned addon resources

See [per-program host lifetime](architecture/runtime.md#program-owned-addon-resources).

### Typed peripheral discovery

See [typed contracts, snapshots and handles](architecture/world-integration.md#typed-peripheral-discovery).

## Updating these documents

Keep this file as an ownership map. Update the detailed owner in the same change as its behavior or contract, and update this overview only when a boundary changes. ABI history is a navigation aid; machine-readable schemas, locks and active ABI specifications remain authoritative. Support status and exact conformance evidence belong to the developer support topics.

For agents: follow the relative file links in the reading map before editing the affected layer. Do not infer full language support from K2 acceptance, hibernation from filesystem persistence, or current versions from a historical ABI introduction paragraph.
