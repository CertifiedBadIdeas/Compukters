---
layout: default
title: Minecraft devices and addon ownership
section: contributors
permalink: /ARCHITECTURE/world-integration/
---

# Minecraft devices and addon ownership

[← Architecture](../architecture.md)

* On this page
{:toc}

## World-thread effects and devices

The Minecraft carrier owns exactly one actor endpoint and submits ordinary advancement once per server tick. Eligible host continuations may reuse the remaining frame credit, deadline and cumulative quotas described in [runtime ownership](runtime.md). Rust starts
`/rom/boot`, compiled from `system/programs/boot.kt`; boot delegates to `/rom/shell`, compiled from
`system/programs/shell.kt`. A foreground child suspends its parent until it exits or fails. There is one active
foreground lane today, while the runtime contract leaves room for later parallel execution. Reboot replaces the
complete machine stack and clears the terminal. Minecraft sends full state to a new viewer and ordered deltas
thereafter. Terminal viewers submit bounded asynchronous key, text, state, resync, and resource-snapshot operations,
which merge in server-arrival order without client-side echo or a terminal input lease. A valid standalone-terminal
viewer samples resources immediately and then every ten server ticks through the same single-poll admission. Its
viewer-local window retains one prior sample to derive semantic consumed/granted Guest-unit usage; it is discarded on
close, invalidation, machine replacement, or server stop. Replies are published only after returning to the server
thread and only while the viewer still refers to the same machine epoch.

## Raw terminal

The raw terminal is a synchronous Rust device: cell writes, positional writes, rectangular fills, colors, cursor
changes, and input polling never cross into Minecraft. The authoritative fixed 51x19 cell grid and its replication
journal live in the machine; Minecraft only renders full states or ordered deltas. Waiting for an absent input event
and foreground process execution are explicit suspension points. Kotlin guests consume ordered raw Text and Key
events; there is no compatibility line buffer or second input protocol. A program must finish its active event before
starting an interactive child, so event ownership never leaks across foreground process frames. The server host never
blocks the Minecraft thread.

## Redstone

Minecraft owns the persistent 30-bit redstone output register and samples dirty local faces before VM advancement.
Rust owns the complete input snapshot, predicate waiters, and confirmed output mirror. Input crosses FFI as one scalar
changed-mask-plus-levels packet; output requests are reduced in publication order and committed through one
loader-independent host port at most once per computer per tick. A successful physical commit is confirmed to Rust
before every original blocking request resumes. Completion and the next bounded advance share one actor command and
one result without allowing multiple advances in a server tick. VM halt, fault, shutdown, reboot, and replacement never
synthesize a zero output.

## Sound

One-shot sound requests cross the same actor boundary as immutable `(note, volume)` batches. Minecraft emits the
vanilla note-block pling in the block sound category, using equal-temperament pitch around neutral note 12, before the
VM task receives its Boolean admission result. Each loaded computer has a four-tick cooldown, and one server-wide
counter admits at most 64 computer sounds per tick. Rejected sounds return `false` immediately and are never queued;
unloaded block entities retain no sound state or background work.

Computer GPIO sides are independent inputs and outputs. The chassis is not a passive redstone conductor: a strong
input (such as a lever attached above it) cannot power a device on another face through the computer. Input sampling
still reads the neighboring source, while explicit weak/direct program outputs retain their directional behavior.
Samples collected before asynchronous boot completes are retained and replayed once as a complete input snapshot;
normal changed-side packets resume afterward, so a steady input present at startup is not lost.

## Peripheral fabric

The Minecraft carrier also owns a direct-touch peripheral fabric. Passive orthogonal cables form bounded components
across loaded chunks without forcing chunk loads. Addon providers map a touched block or multiblock part to a canonical
logical identity; a versioned world directory stores normalized names against that identity independently of computers
and cable topology. Lookup includes the computer's six face-adjacent provider contacts together with its cable component,
scoped by addon ID. It deduplicates logical identities across both paths, rejects duplicate reachable names, and never
returns a partial component after a traversal bound is exceeded. Typed addon host handles bind to the resolved device
identity, so later rewiring or renaming cannot redirect an existing handle. The cable cache stores physical contact
positions and faces; providers resolve current logical identities on every lookup. Multiblock activation changes
therefore remain visible even when the engine or controller is not adjacent to a cable. Physical candidates are
bounded by six faces per admitted cable plus the computer's six direct contacts; each level caches at most 65,536 physical
contacts. The existing 1024 logical-device limit applies after identity deduplication.

## Text displays

The base text display uses the same bounded world-request path with an internal `compukters:display` capability. Its
20x10 buffer and exclusive writer lease belong to the display block entity on the server, outside the VM terminal.
The block tick checks its writer and cable reachability even while Guest code is idle. Stopping or disconnecting that
writer clears the screen, and no display text is saved in world NBT. Clients receive at most one full-grid block-entity
update per changed server tick and render text on the oriented front face.

## Foreground termination

Ctrl+T is reserved in computer terminal input (key code 84, CONTROL modifier), including the IDE target terminal.
The native computer consumes it independently of Guest input polling and queue capacity. A press marks the foreground
child below the nearest `/rom/shell` frame, or the first child of a standalone parent, for termination. An idle shell
and a root without a parent are preserved. Successive native advances unwind its descendants with status 130 and
ordinary `ProcessExited` lifecycle events, allowing the existing host scope cleanup to release peripheral leases and
reject stale completions. The host cancels pending compilation and wakes an input/compiler wait; native termination
discards the corresponding compiler transaction. Queued input for the terminated command is discarded at the press. After unwinding, line-oriented output resumes
below visible content without clearing the terminal; normal scrolling applies when the screen is full. Held-key repeats are consumed
without scheduling another stop.
Terminal key 84 extends the admitted key set compatibly: existing key values, payload layouts, artifact ABI and C ABI
remain unchanged; both native transports use their existing terminal-key function.

## Typed peripheral discovery

The canonical `compukter:core` 2.0.0 library defines `compukter.peripheral.Peripheral`,
`PeripheralProvider<T>`, relative `Side` values and `TypedPeripheralProvider<T>`. Companions supply a registered
contract id and the typed device wrapper. Selection runs in Guest; snapshots close in `finally`, including
predicate failure and early selection. Predicates are ordinary typed callbacks with local returns.

The base `compukters:peripheral` capability is ABI 1.0 with six asynchronous operations, in order:
`openSnapshot(String):Int`, `snapshotSize(Int):Int`, `snapshotGet(Int,Int):Int`, `closeSnapshot(Int):Unit`,
`at(String,Int):Int`, and `named(String,String):Int`. Optional direct lookup returns handle zero only for absence.
Malformed requests, unavailable contracts, ambiguity, wrong device types and limits remain host failures.
This adds a capability while preserving the artifact, Runtime instruction and native transport ABI.

`core` owns `PeripheralSession` and `PeripheralProgramHost`, independently of Minecraft. A typed contract identifies
its provider and logical device kind and resolves exact endpoint instances. A program has at most four snapshots,
1024 discovered entries per snapshot and 1024 retained handles. Snapshot opening captures physical identities
without retaining handles for unread entries; handles are allocated only on selection. Typed operations verify
contract object identity before exposing a retained endpoint. Invalidity latches, stale tokens cannot acquire
replacement instances, and reset discards state without reusing tokens. World discovery order and face/name lookup
belong to the Minecraft adapter. Addons retain ownership of device operations and capability schemas.

Evidence: `PeripheralSessionTest`, `PeripheralProgramHostTest`, and
`canonical peripheral companion specializes shared typed queries` through `testKotlinPeripheralQueriesVmConformance`,
including predicate short-circuiting, imported Peripheral upcasts, nullable absence, strict exceptions and snapshot
cleanup after predicate failure.


The Minecraft adapter binds registered `ComputerPeripheralContract<T>` descriptors once per program, through
`ComputerPeripheralRuntime`. Loaded cable/direct contacts sort by x/y/z, provider and device key; side lookup
resolves the contacted face directly. SDK 0.5.0 adds typed `CompuktersPeripheralContract<T>` registration and
`CompuktersComputerContext.peripheral` access, retaining the earlier overloads. SDK JARs depend on core only at
compile time and retain their explicit thin-archive class inventory. Both Minecraft version families compile the
canonical shared adapter sources.

TextDisplay is a scalar value class implementing Peripheral, with a canonical box and a managed companion provider.
All public acquisition uses typed providers and the common Side; the earlier Display/Create/Propulsion helpers
and separate side types are removed. Display operations still own output leases;
discovery creates no lease, and reset releases buffers touched by the program. The real text-display GameTest
fixture covers typed selection, interface casts, predicates, optional/strict absence and cleanup after a thrown
predicate before exercising a world write and cable disconnection.

## Addon module selection and Propulsion

Terminal, standard output and error, redstone, sound, process, filesystem, and compiler declarations live in the base
`guest-platform` bundle under `compukter:core`; core language declarations belong to `kotlin:builtins` and environment-independent helpers to `stdlib:core`. Optional integrations publish bounded addon Guest API
bundles instead. The server admits those bundles, includes their content hashes in the target and compilation-cache
identity, and sends their metadata and optional sources to the attached IDE. Analysis indexes the bounded catalog of
available addons for completion, while K2 semantics and compilation activate only the modules selected in the project
lock. Compiler and analysis workers decode only this data format: they never load or execute addon code. Exact admitted
bindings extend intrinsic lowering, while a
same-named Guest declaration remains ordinary Guest code. General stream handles, pipes, and process redirection remain
later layers.
Addon host handlers report failures with a broad `HostFailureKind` and a non-empty UTF-8 detail of at most 256 bytes.
The kind remains suitable for runtime classification; the producer-owned detail is diagnostic text and is not a stable
programmatic identifier.

The independent `addons/propulsion` build owns named Creative Thruster and Creative Vector Thruster control and its
`propulsion.api` Guest module.
It depends on upstream Propulsion/Create/Sable types, without depending on other Compukters addon implementations.
Commands and observations execute through the existing server-thread addon request boundary; physics and VM cadence
are unchanged. Controller identity and cable reachability bind each handle. A server-confined lease registry owns
only temporary program control, validates active leases on server ticks, and restores normal redstone on release.
It does not mirror authoritative thrust or physics state. Typed observations copy current upstream fields in kN.
The pinned Propulsion binary persists digital commands, so a dedicated read/write mixin marks owned full NBT saves
and clears the unowned digital input/mode when those saves or construction copies load; client packets are unaffected.
Vector steering temporarily overrides mapped local targets without changing live redstone-link inputs. On release,
targets follow the latest signals; digital input and the old shutdown envelope are cleared before the creative thrust
override is removed, then physical thrust is recalculated from the current redstone input. This prevents residual
program throttle from multiplying the restored engine setting. Full saves retain
redstone signals and the saved engine configuration, clearing owned steering/tween and absolute thrust commands;
client packets retain the live visual state. Vector mount operation 12 copies integer computer-relative block
offsets, a neutral force axis and the common construction ID. It rejects cross-construction mounts; ordinary-world mounts have an empty ID and world axes.
Guest flight programs own geometry-to-torque allocation; names do not encode corner orientation.
Vector capability operations append IDs 5..12, preserving ordinary
operations 0..4 at capability version 1, Runtime ABI 1.13 and C ABI 20. The independent `addons/dev` run build composes
all three
addon archives and their GameTests with the upstream Aeronautics/Propulsion runtime.
