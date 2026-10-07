---
layout: default
title: Runtime ownership and actor scheduling
section: contributors
permalink: /ARCHITECTURE/runtime/
---

# Runtime ownership and actor scheduling

[← Architecture](../architecture.md)

* On this page
{:toc}

## Actor lifetime and bounded work

The asynchronous VM actor scheduler has a server-scoped service registered with NeoForge. Server startup records
its lifetime; workers are allocated on first use. Publishing a worker result sends a lightweight notification;
one coalesced server task can deliver ready replies between ordinary tick boundaries. This task and the pre-tick
fallback share a limit of 1024 delivered results and a 2 ms owner-pump allowance per tick. The time limit is checked
between callbacks; one world callback is not preempted. Workers never execute world actions. Stopping invalidates
queued notifications and removes the service before closing it, so late callbacks cannot reopen it.
Production computers attach one actor identified by `ComputerId` and a machine epoch. A full scheduler rejects a new
attachment without blocking or failing the server tick; the block remains powered off and retries on a later tick.
Minecraft runtime epochs come from one process-wide monotonic allocator, so replacing a block entity cannot reuse
the old actor address even when its persisted `ComputerId` is retained. Epochs are runtime identities and are not saved.
The server config exposes `vm.workers`, `vm.maximum_actors`, `vm.mailbox_capacity`, `vm.messages_per_turn`, and
`vm.result_capacity_per_worker`; defaults are half of the available processors clamped to 2..8, 4096 actors, 64
commands, 4 commands per turn, and 256 replies per worker. Operators may explicitly configure up to 64 workers.
Increasing queue limits trades bounded memory for burst tolerance; increasing messages per turn trades inter-actor
latency for locality.

## Scheduler observations

Scheduler snapshots read atomic counters and bounded lane sizes without scanning registered actors. Every five seconds
the debug log reports registered actors together with configured capacity, runnable actors, mailbox and result depths,
worker occupancy, average/maximum command-queue latency, average/maximum execution time, completed-result latency, the
last server pump size and duration, deferred world requests, host-completion-to-next-advance delay in server ticks,
and rejected or capacity-coalesced redstone input submissions. Pending redstone transitions retain their sampled order
while a VM turn is in flight, up to the configured mailbox capacity; overflow coalesces only the newest retained packet
and increments the diagnostic counter. These are lifetime counters and gauges for the current server service rather
than an equal-CPU or delivery-latency contract.

## Actor continuations

`ActorProgramComputer` is the asynchronous carrier implementation for the actor scheduler. Its server-side state is an
observation from actor replies, and terminal, filesystem, deployment, and input requests return futures. The scheduler
admits at most one pending or executing tick permit per actor, while completed replies may remain queued for the next
server-thread pump without blocking admission of a later tick. The carrier suppresses obsolete lifecycle replies and
performs redstone and sound world actions on its owning server thread. A completed world action may submit a typed
continuation in the same world tick using the remaining instruction credit from that computer's original frame grant.
The original deadline is retained, continuations are capped, and host-request and advance quotas are cumulative for
the world tick. Re-reporting an already parked request does not consume new-call credit; a zero-progress repeat
yields to its owner instead of exhausting the frame through polling. Credit that is still in flight cannot be borrowed; expired credits cannot refill a newer frame.
Ordinary turns outside a collecting frame wait for a new frame instead of receiving an unbounded allowance.
The actor validates and applies each completion before the next bounded advance; its single reply both completes
the old deferred request and may publish the next one. When credit, deadline, or admission is exhausted, the carrier
retains the exact continuation for a later tick without repeating the world mutation.
Its close future reports the final filesystem generation after accepted work drains and native resources close; this
barrier does not depend on server result pumping and may complete on a worker thread.

## Addon host boundary

Third-party world capabilities use the same continuation boundary. Loader integrations register bounded
`ProgramAddonHost` factories together with deterministic `AddonGuestApiBundle` values. Each bundle contains one
versioned Kotlin platform module, its exact external-call bindings, the matching typed capability schema, and optional
sources; it contains no executable addon JVM classes. Registration rejects duplicate addon/module identities, and a
created host must expose the exact registered capability schema. The actor transfers immutable typed requests to
the server thread, where the host may complete immediately or retain a bounded wait; completions resume the exact VM
task on the next admitted turn, including eligible same-tick continuations. Delayed addon waits retain their
server-tick polling fallback. The Minecraft 1.21.1 Create adapter and its Guest Kotlin declarations live in the standalone
`addons/create` Gradle root. It declares the public plugin, tooling, platform bundle, common host API, target adapter,
and development mod through their external coordinates. For local co-development its composite build substitutes the
adjacent Compukters projects, selecting the self-contained `namedElements` development mod instead of a republished
Maven Local runtime. The API and adapter share the independent SDK version; the runtime-only development mod retains
the product version. The Compukters root does not include or invoke Create tasks, so the base platform, shared
Minecraft code, and 26.1 code have no direct Create ownership. The adapter resolves adjacent loaded positions or
named devices across loaded peripheral cables and binds handles to exact block-entity identities, preventing replacement
from silently rebinding a running Guest program. Stock Ticker snapshots and package requests remain inside that addon
and use the same bounded server-thread host boundary.
The server compiler configuration derives its selected module names and content hashes from the advertised target
profile. Addon bundle payloads use addon IDs, while platform-module selections use full module IDs; these identities
share the exact content hash but are not interchangeable.

## Native sessions and cooperative tasks

`ProgramRuntimeHost` owns one current Rust `ComputerMachine`, advances it with bounded guest and maintenance budgets,
commits terminal changes once per active server tick, and exposes typed full/delta states and failures through JDK 25
FFM on the 26.1 product line. The Java 21 runtime API owns the same typed session boundary independently of its native
transport. It does not own a second grid or output transcript. It is loader-independent and confined to its actor worker.
Within one process, the Rust VM may schedule several bounded cooperative Guest tasks. Exactly one task executes
instructions at once; suspension on a host request or `Task.join()` transfers execution to the next runnable task in
FIFO order. Pending requests retain their `(TaskId, RequestId)` owner, so independent reads and writes may remain in
flight and complete out of order without running Guest code re-entrantly. Returning from the root task ends the process
and cancels its remaining task work.
The built-in `compukter:timer` capability at ABI 1.0 backs `Tasks.sleepTicks`: the loader-independent runtime retains
the bounded request and resumes its owning task at the requested server-tick boundary. It does not introduce a VM
clock, background timer, wall-clock dependency, or work while the computer is not receiving tick permits.
Guest Kotlin exposes this as transparent stackful blocking through ordinary functions: `suspend` declarations are
outside the supported source subset, while legacy suspend-call artifact instructions remain decodable and executable.

## Process quotas and diagnostics

The production execution profile reserves a 256 KiB managed heap for each active foreground process; child-process
capacity is charged independently while its parent is suspended. Heap arenas are released with their owning machine.
An OOM in a foreground child remains a bounded `LIMIT_EXCEEDED` process result, not a failure of its parent.
Before releasing the child, the runtime formats its executable path, heap capacity, requested allocation, used/free
bytes, largest free block and GC-attempt status. Child frames retain the immutable verified executable metadata,
so diagnostics refer to the executed artifact even if the executable is replaced on disk. OOM, Guest traps and VM
faults retain up to 32 active frames, innermost first, without allocating from the Guest heap. Caller positions are
the actual call instructions, not return continuations. The trace includes library and user frames; a VM fault trace
identifies its detection site rather than asserting the origin of corruption. Text and frame limits report omitted
frames explicitly. The normal process-diagnostic UTF-16 bound still applies.

## Resource observations

An explicit actor request can also compose one immutable resource snapshot from host lifecycle/configuration and the
native machine's semantic work, Guest heap, admitted mutable execution-resident, and filesystem quota counters. The
host counts Guest and maintenance budgets only when it actually invokes native advancement; both host and native
counters saturate explicitly instead of overflowing. These values describe VM work and granted capacity, not host CPU
percentage. No sampling loop, history buffer, heap-content scan, or unsolicited FFM call runs for unobserved computers.

## Program-owned addon resources

Native C ABI 20 adds outcome tags 13 (`ProcessEntered`) and 14 (`ProcessExited`) with positive computer-wide
process IDs. Root ID 0 remains live until the computer stops. Child enter is published before child execution;
exit is published before the resumed parent executes. The export inventory remains unchanged. External host
request IDs are remapped through a bounded table to their owning process and task, including suspended parents;
retired process routes are discarded and IDs are never reused within a computer.

The actor carries ordered lifecycle actions alongside addon requests and ordinary world effects. The server carrier
creates addon hosts lazily per program, closes only the exiting program's hosts, and rejects its queued completions.
Suspended parents retain their resources. Computer shutdown resets every scope. This makes peripheral ownership
follow the program lifetime without inferring it from terminal output or changing world/physics cadence.

## Managed memory

The Rust VM owns verification, the Tier 0 interpreter, managed memory and collection, quotas, traps and faults,
capability suspension, and host-neutral sessions. Future JIT or AOT tiers must remain behind the same verified artifact
and session contract.

Managed blocks normally use one eight-byte header with allocated/marked/live flags, aligned block size,
predecessor size or GC gray link, and runtime type ID packed into bit fields. The VM selects this format when the
admitted heap and type count fit; larger combinations retain the 12-byte header. In the production 256 KiB heap,
empty, one-Int, and two-Int objects, including managed Int boxes, occupy 16 bytes; three-Int objects occupy 24 bytes.
Both formats have eight-byte block alignment and a 16-byte minimum. Sixteen-byte free tails are split and reused;
only smaller tails are absorbed. Reference identity uses non-moving managed offsets. Free-list links overlap payload
bytes only while a block is free. Payload access uses little-endian byte operations, so wide fields do not require
the payload address itself to be eight-byte aligned. During roots/mark, the predecessor-size field temporarily
holds the intrusive gray link while Guest execution and allocation are paused. The bounded forward sweep restores
predecessor sizes before coalescing. No per-object side table or extra heap scan is required.
Arena backing and admitted heap budgets retain their existing 16-byte granularity. See the
[object-array heap measurements]({{ '/OBJECT-ARRAY-BENCHMARK/' | relative_url }}) for construction and transformation budgets.
Allocation checks the head of the request's partial size-class bucket before the rounded-up bitmap search.
A constant-space free-block hint covers fitting blocks hidden behind smaller list heads. Removing its block
invalidates the hint; insertions retain the larger candidate. The existing budgeted sweep observes surviving
free blocks and coalesced insertions, restoring an exact largest-block candidate before an allocation retry.
Consequently, size-class rounding cannot cause a post-collection OOM when a contiguous block fits, without
adding an unbudgeted free-list or arena scan to allocation.
