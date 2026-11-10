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

Routine carrier turns use internal completions rather than a chain of request and observation futures. Both forms
share one pending-request registry, frame reservations and bounded result lanes. Admission refusal is synchronous;
the carrier retains its exact world completions and input until accepted. Each accepted turn captures its lifecycle
version independently, so a later admission cannot overwrite the identity of an older queued result. The owner pump
removes pending ownership before invoking its completion and polls one event at a time, checking its event and time
allowance between callbacks. Callback failures are isolated from other computers. Lifecycle invalidation is checked
again after publishing state, because a state observer may synchronously close or replace the carrier before world
effects are applied. Explicit commands and lifecycle operations retain their future and cancellation contracts.

The standalone fleet probe waits using lightweight observations of the existing pending, scheduling, execution and
queue state, and reads the existing retirement counter for frame progress. These observations collect no latency
histograms and are valid for its serialized admission/pump loop; they are not an admission barrier for concurrent
callers. Production diagnostic snapshots retain their full histograms and logging cadence.

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
transport. It does not own a second grid or output transcript; VM-owned terminal history travels through the same
bounded snapshot/delta contract. It is loader-independent and confined to its actor worker.
Within one process, the Rust VM may schedule several bounded cooperative Guest tasks. Exactly one task executes
instructions at once; suspension on a host request or `Task.join()` transfers execution to the next runnable task in
FIFO order. Pending requests retain their `(TaskId, RequestId)` owner, so independent reads and writes may remain in
flight and complete out of order without running Guest code re-entrantly. Returning from the root task ends the process
and cancels its remaining task work.

The FFM adapter leases temporary output storage from bridge-owned pools shared across sessions.
Execution outcomes and terminal data use separate pools, each retaining at most eight idle buffers;
the execution pool uses the native advertised outcome capacity (currently 64 KiB), not a buffer per VM.
One call exclusively owns its output and scalar slots until decoding or copying completes. Returned outcome
byte arrays own their contents independently of later calls. Excess concurrent buffers are released when
returned, and bridge shutdown closes idle storage while outstanding leases release on return. This transport
storage does not change Guest quotas or the native wire contract.
The built-in `compukter:timer` capability at ABI 1.0 backs `Tasks.sleepTicks`: the loader-independent runtime retains
the bounded request and resumes its owning task at the requested server-tick boundary. It does not introduce a VM
clock, background timer, wall-clock dependency, or work while the computer is not receiving tick permits.
A terminal waiter does not mark the whole computer idle while another task owns a pending timer;
server-tick permits continue until that timer wakes, even without terminal input.
Guest Kotlin exposes this as transparent stackful blocking through ordinary functions: `suspend` declarations are
outside the supported source subset, while legacy suspend-call artifact instructions remain decodable and executable.

## Shared program ownership

Rust separates an immutable artifact-derived `PreparedProgram` from each session's admission and
execution state. Live byte-identical binaries normally share decoded metadata, prepared operations and lifecycle descriptors,
function/frame/GC-safepoint/dispatch tables, type identities/names, payload-relative field offsets,
static layout and literals. Allocation layouts are shared lazily per program and heap-header width
(8-byte compact or 12-byte legacy), with at most two variants retained by that program. Compact size/link
bit widths remain specific to each admission; object field layouts depend only on the header width.
Additional experimental execution representations also share derived code by mode;
its mutable decoder cache remains machine-owned. This is interpreted code preparation, not native JIT.

Every session independently checks its resource profile and actual capability/record schemas. External references,
resolved record schemas, storage charges and slice limits belong to admission; managed heap,
frames, static values, class initialization, tasks, requests and host bindings belong to the instance.
A larger computer cannot lend its limits or device permissions to another computer through a shared program.
Payload-relative field offsets are independent of heap header size, so shared instructions retain direct field access.

A process-local digest-indexed weak registry canonicalizes fully verified artifacts with complete-container
equality. It never skips caller limits or evicts a live identity. The final artifact owner removes its registry
entry and releases decoded metadata and prepared code. A separate weak registry shares immutable file blobs
among private namespaces; executable reads pin those bytes without chunk copying. Global locks cover identity
publication only. Candidates, verification and preparation run outside them, as do final owner destructors.
No host binding, world object or mutable Guest state enters either registry. The JVM distribution image is
immutable and shared by carrier and launch contexts, with defensive copies at mutable transport boundaries.

Restore still verifies pinned binaries and performs fresh admission; derived program sharing can be reused
while another owner is live. Checkpoint serialization contains logical execution state, not cache pointers.
Shared immutable storage is separate from each VM's admitted mutable execution-resident counter.

## Prepared execution

The compiler still emits one portable `.cpkt` executable. After verification, `PreparedProgram` derives the
ordinary execution stream once for its live owners. Default execution uses the interpreter; the experimental
baseline JIT described below derives additional process-local code without a second distributable binary.
A program-wide dense allocation contains 16-byte operations; only its allocation boundary is aligned to 64 bytes.
Blocks retain ranges, fixed costs and function ownership. Short blocks do not each allocate or pad a cache line.
Shared copy/field plans and sparse lifecycle descriptors occupy separate program-owned arrays.
The dispatcher borrows a checked operation to select its handler, then copies its operands at the handler
boundary. Fetch does not materialize a complete operation before inspecting the opcode or add per-machine storage.

Consecutive numeric, frame and non-allocating field/array operations execute through a general scoped run.
It validates the current frame reservation once, borrows its byte window and keeps the logical instruction PC and
attempt count local. Checked heap operations receive only this window, the immutable image and mutable heap; the
machine owns lifecycle effects. Type/reference/array validation has one shared implementation for scoped and
reference execution. The run publishes PC/attempts before a branch, trap, instruction limit or lifecycle boundary;
allocation, calls, initialization, host operations and safepoints happen after the borrow ends. No persistent
per-machine cursor, block cache or second prepared stream is stored.

Numeric operations carry concrete kinds and checked physical frame offsets. Preparation also resolves scalar
constants, moves, inline component copies, jumps/Boolean branches, and scalar/reference field and array accesses.
Heap accesses retain dynamic null/type/assignability/bounds checks and use the admitted heap layout. Lifecycle
instructions that need initialization, calls, allocation/GC, exceptions, host suspension or task machinery retain
explicit resolved descriptors. Ordinary prepared operations have no duplicate resolved instruction descriptor;
verified decoded metadata remains part of the shared program's source/type identity.

Each operation still represents one logical instruction. No fusion or reordering changes block charges,
attempt/retirement counts, source/exception locations, safepoints or checkpoint positions. Arithmetic uses the
same wrapping, division, floating NaN and signed-zero rules as the previous interpreter. Inline component copies
retain their sequential aliasing semantics. Checked frame accesses also update the initialization tracking used
by GC tests. Checkpoints serialize logical state and rebuild the derived stream through normal verification/admission.

The previous interpreter and compressed/SIMD decoder experiments are available only through private testing/benchmark
selection. The ordinary VM uses prepared execution without an experimental feature or public runtime selector.

Direct and suspend-call descriptors index a flat immutable table of physical argument-component transfers.
Preparation uses the actual caller and callee layouts; it drops the original logical argument arrays from
ordinary execution descriptors. Scalar returns carry their prepared source access. Calls and returns still
enter the common machine-owned lifecycle: scalar argument validation precedes frame reservation, failed
transfers roll reservations back, and initialization, stack limits, exceptions, task continuation and logical
checkpoint positions retain their existing ordering. Virtual/interface calls retain target-dependent layout
resolution; inline returns keep their existing component transfer. There is no per-machine call-plan cache.

## Process quotas and diagnostics

The production execution profile grants a 256 KiB managed heap quota to each active foreground process; child-process
capacity is charged independently while its parent is suspended. Each heap initially allocates at most 4 KiB of backing
and grows its contiguous initialized prefix on demand, doubling or covering a larger request up to the quota. Guest references retain their logical
offsets when the host buffer grows. Free-block metadata covers the full logical quota without allocating its unused
tail; all allocated payloads and free-list headers remain backed. Growth is fallible and precedes allocator mutation.
A host allocation failure follows the existing collection/retry and bounded exhaustion path, even when logical free
capacity remains. Freeing objects reuses backing; the heap does not shrink during execution. Heap backing is released
with its owning machine.
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
process IDs. Root ID 0 identifies the initial execution. Child enter is published before child execution;
exit is published before the resumed parent executes. The export inventory remains unchanged. External host
request IDs are remapped through a bounded table to their owning process and task, including suspended parents;
retired process routes are discarded and IDs are never reused within a computer.

Native C ABI 26 adds tag 15, `ProcessReplaced(previous, current)`. Successful `Process.exec` retires
all tasks and process-owned resources, then enters a fresh monotonically increasing scope in the same
stack slot. The actor removes old addon completions and timers and emits stop/start actions in order.
Filesystem, terminal and confirmed world output remain computer-owned. A suspended parent retains its
pending `Process.run` and receives the replacement's completion. Failure before publication leaves
the caller usable. Replacement consumes the lifetime start budget without another steady heap/frame
reservation; admission temporarily holds both sessions so it can roll back. Boot uses replacement,
so an idle shell retains one process heap. Checkpoint schema 9 and host descriptor version 2 permit
a nonzero root scope and retain only the currently executing root artifact.

The actor carries ordered lifecycle actions alongside addon requests and ordinary world effects. The server carrier
creates addon hosts lazily per program, closes only the exiting program's hosts, and rejects its queued completions.
Suspended parents retain their resources. Computer shutdown resets every scope. This makes peripheral ownership
follow the program lifetime without inferring it from terminal output or changing world/physics cadence.

## Experimental baseline JIT

The native `baseline-jit` Cargo feature adds private opt-in tier selection for experiments;
normal sessions and the Minecraft carrier retain the prepared interpreter. It does not change
Guest Kotlin, portable artifacts, Runtime/host ABI, logical accounting or checkpoint bytes.
The current tier compiles bounded numeric/frame prefixes and connected same-function regions through
Cranelift. Regions include branches and loops, with at most 16 blocks and 128 operations per plan.
Mixed prefixes can include non-allocating field and array operations through checked Rust callouts.
Calls, allocation, initialization, host requests and safepoints remain interpreter boundaries.

Each prepared program lazily owns 256 fixed hot-entry slots. Hotness and finalized modules are
shared by all admitted instances; direct-map collisions fall back. A process-wide single worker
accepts at most eight queued jobs, with exponentially spaced bounded retries for queue bursts.
Jobs carry bounded copied plans and weak program owners, with no instance pointer or host binding.
At most 256 queued/compiling/live modules each reserve a 64 KiB page-rounded mapping envelope,
including code and any readonly/data requests. This conservative 16 MiB cap excludes program
metadata, compiler transient allocations and the single persistent compiler worker/stack.
No cache eviction can invalidate an executing function; the final module owner explicitly frees
its executable mappings. The feature is absent from production dependency/code paths by default.

Native entry proves frame access ranges and requires enough remaining attempts and accounting
headroom for the entire single-block prefix. Smaller prefix budgets fall back at the same instruction.
Untraced connected regions check limits inside generated control flow, charging each block once and
preserving attempted-instruction, fixed-cost and entered-block overflow order. An invocation-local
stack record publishes exact original block/instruction, remaining budget and accounting on every
exit; unsupported successor blocks remain unpaid. Diagnostic block traces retain the ordinary
trace-preserving path. Both kinds of code use the same slots, compiler and executable limits. Generated
failure exits retain exact fault PC/attempts and previous writes; NaN canonicalization and integer
wrapping/division follow the interpreter. Frame bytes are materialized at every exit, with no
native registers or addresses in checkpoints. Heap callouts use an invocation-only context borrowing
that frame and the current admission's heap/image; generated code never embeds a heap pointer,
header packing, instance reference or capability. The ordinary prepared heap executor owns all
reference, type, bounds and null checks; each callout selects its verified opcode at compilation
time, specializing that same implementation rather than defining separate heap semantics. Temporary frame reborrows and generated accesses run
sequentially, and a failed callout immediately exits with its Rust-owned fault/trap. No frame resize,
Guest allocation, collection or suspension can occur inside a native region.
The optional `jit-diagnostics` feature counts exit categories in a separate diagnostic build; it is
excluded from timing and memory measurements.
Unsupported code, capacity exhaustion or compilation
failure keeps interpretation. This first tier does not inline functions, hoist heap checks across
calls or promise automatic SIMD.

## Managed memory

The Rust VM owns verification, the Tier 0 interpreter, managed memory and collection, quotas, traps and faults,
capability suspension, and host-neutral sessions. The experimental JIT and future execution tiers remain behind the same verified artifact
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
Arena backing and admitted heap budgets retain their existing 16-byte granularity.

Frame arenas back only an eight-byte free header initially and grow within their admitted
quota before publishing allocations. Free regions remain ordered and coalesce across non-LIFO
task completion; all live payloads and free-list headers are backed. Record buffers grow with
call depth, and each cooperative task owns a lazy vector. Switching transfers vector ownership
without allocating or copying records. GC retains task/depth traversal order across sparse
buffers. Neither arena nor record backing shrinks during execution. Resource observations keep
reporting admitted upper bounds; physical backing is a separate benchmark observation.

## Terminal transport storage

The JDK 25 FFM bridge owns a lazy pool of temporary terminal wire buffers. A read exclusively borrows one buffer
through the native call and decoding; returned terminal states contain owned JVM values, never borrowed views.
The pool retains at most eight idle 256 KiB output buffers plus length slots and arena bookkeeping. Additional
simultaneous readers receive independent temporary buffers, released on return when idle retention is full.
Peak scratch follows concurrent reads; admitted but unobserved computers allocate no terminal scratch of their own.
Session close only closes that session's read guard. Bridge close drains idle storage and rejects new loans;
outstanding loans release their storage on return. The ordinary native-library lifetime rule still requires all
native calls to finish before unloading the bridge. JNI retains its existing per-call transport behavior.

## Player keyboard capability

The loader-independent `core/input` package owns the program's keyboard handle, requested key mask and
bounded input frame. Its `KeyboardEndpoint` delegates player consent and physical key state to the
Minecraft adapter. This is the active keyboard capability, separate from the removed terminal/UI input
implementation; it contains no client widgets or platform event handling.
