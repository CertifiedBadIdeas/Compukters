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
fallback share a limit of 1024 delivered results and a 2 ms owner-pump allowance per tick. Native outbound commands request the same owner delivery when admitted, so a grant does not wait for a future native event or server tick. Owner delivery is always queued, including notifications originating on the server thread, so it cannot reenter command admission. Admissions coalesce until the next flush attempt; a busy retry alone does not request another owner task. The time limit is checked
between callbacks; one world callback is not preempted. Workers never execute world actions. Stopping invalidates
queued notifications and removes the service before closing it, so late callbacks cannot reopen it.
Production computers attach one actor identified by `ComputerId` and a machine epoch. A full scheduler rejects a new
attachment without blocking or failing the server tick; the block remains powered off and retries on a later tick.
Minecraft runtime epochs come from one process-wide monotonic allocator, so replacing a block entity cannot reuse
the old actor address even when its persisted `ComputerId` is retained. Epochs are runtime identities and are not saved.
The server config exposes `vm.workers`, `vm.maximum_actors`, `vm.mailbox_capacity`, and
`vm.result_capacity_per_worker`; defaults are half of the available processors clamped to 2..8, 4096 actors, 64
commands and a result/transport capacity contribution of 256 entries per worker. Operators may explicitly configure up to 64 workers.
Increasing queue limits trades bounded memory for burst tolerance.

## Scheduler observations

The service reports registered actors, pending requests, pump work, capacity, throttling, retirement,
world-request and input observations. It does not fabricate JVM command-queue or execution-latency histograms
for Rust workers. These are service-lifetime observations, not an equal-CPU or delivery-latency contract.
Pending redstone transitions retain their sampled order while a VM turn is in flight, up to the configured
mailbox capacity; overflow coalesces the newest retained packet and increments the diagnostic counter.

## Actor continuations

`ActorProgramComputer` is the asynchronous carrier implementation for the actor scheduler. Its server-side state is an
observation from actor replies, and terminal, filesystem, deployment, and input requests return futures. The service admits at most one pending turn per actor. Completed observations settle its credit on the owner
pump before a later turn is admitted. The carrier suppresses obsolete lifecycle replies and
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

## Rust actor scheduler

`NativeProgramRuntimeService` is the only production execution service. It collects submitted turns and uses
one per-computer allocator and capacity governor; Rust owns the worker pool, Guest execution, timers and
original frame credit used by continuations. Pending admission retains at most one turn per registered computer.
The server registry depends only on `ProgramRuntimeLifecycle` for frame admission, bounded event delivery,
calibration and close; the computer carrier uses the machine request/turn contract.

There is no JVM execution scheduler or backend selection. Calibration opens a separate native fleet. Both
supported Minecraft versions require fleet support in their native runtime, using FFM or JNI respectively;
a runtime missing that capability fails explicitly instead of falling back. Both fleet transports decode events
from a reusable buffer limited to the current response length. Decoding consumes that view synchronously;
returned events own their variable payloads and remain valid after the next poll or fleet close.
The Cargo feature and low-level transport symbols retain their existing `experimental` names; this source
migration does not change wire layouts.

`NativeFleetActorRuntimeService` implements active turns of `ActorRuntimeService` for hosts already started or
restored and transferred to a coordinated native fleet. Rust workers own execution and timers; the owner grants
frame credit and delivers observations. Same-tick continuations consume the original reservation and deadline.
Uncoordinated fleets are rejected before dispatch starts, so external replies cannot resume execution without a grant.
The owner retains bounded actor and request registries; query maps are allocated only while needed, and future
cancellation does not release admission until the native response arrives or the actor is fenced for stop.
One fleet waiter serves the service.

Native owner pumping alternates one bounded group of up to 16 scalar command attempts with one observation, retaining the other
lane's turn when the shared deadline expires. A busy attempt consumes transport allowance without
discarding its command. Observation count and transport progress are independent: after the tick's
observation quota is spent, a native service can still send queued commands with zero event allowance
within the remaining owner time. Each pump bounds transport attempts by the configured outbox capacity;
the server does not extend its shared time allowance.
Result notifications coalesce until an owner pump has observation allowance again. Command readiness
remains independent, so a full result quota neither creates empty owner wakeups for every publication
nor prevents an admitted command from requesting delivery.

Redstone input is acknowledged into the bounded outbox before the next grant, independently of Guest credit,
so even a one-entry mailbox can deliver input followed by a grant. Refusal retains the carrier's input in order;
input cannot enter while that actor has a turn in flight. Host replies use the same independently acknowledged
prefix principle. A past ordinary frame deadline still allows accepted effects to settle and produces a zero-work
final report; it cannot admit a Guest continuation.

Idle native hosts use the bootstrap-only `RestoreOrBoot`, `CompleteRestoration` and `AbortRestoration` handler. One lazy bootstrap worker handles storage operations and closes; it never advances Guest and waits for an
explicit signal when idle. The carrier performs addon restoration on its owner thread before completing restoration.
Only then does owner-side nonblocking fleet admission transfer the session and timers to Rust. Transfer pressure
retains the completed bootstrap result instead of repeating boot or checkpoint decoding. Active actors retain no
bootstrap entry, processor or per-machine completion slot. Fresh boot and restore use the same native execution service.

Bootstrap holds at most one command/result per preparing actor and shares actor/request limits with active execution.
Before work starts, it reserves one outbox entry and a conservative byte charge for four maximum checkpoint envelopes
plus control overhead. Standalone artifact bootstrap additionally charges its three defensive artifact copies before
worker admission; this accounts for decoding and defensive copies, without preallocating those bytes. Prepared
restoration retains the reservation until transfer or close. The existing global/per-machine byte limits apply; a
single impossible reservation rejects explicitly, and temporary global pressure leaves the caller retryable. This is
charged admission rather than a total heap or native-session memory measurement. Boot failure releases its reservation.
Standalone replacement rejects while restoration is prepared, before retaining artifact work against that restoration
reservation.
Only pending results enter the owner pump; active machines are not scanned. Startup shares its event, transport-attempt
and deadline allowances, leaving observation capacity for active machines during continuous boot demand.

Bootstrap close fences owner callbacks, drains its accepted operation and releases the host off-thread without owner
pumping. Unload preserves an already prepared, unconsumed durable checkpoint; after restoration consumption, ordinary
capture applies. Closing bootstrap identities retain actor capacity until cleanup completes. Shutdown includes that
worker and any reserved close whose owner callback has not unwound. No execution grant is issued before native adoption.

Native shutdown and reboot reserve a bootstrap entry and byte budget before fencing the active actor. The common
retirement path invalidates compiler/turn/query ownership, drains accepted native commands and stops the machine on
the lifecycle worker. Its final acknowledgement returns the host to `Idle`; only then may the bootstrap worker run
the existing processor's shutdown or fresh boot operation. The same endpoint and lease survive replacement. Reboot
gets a new VM epoch and requires a later explicit execution grant. Plain reboot preserves installed files; system
reinstallation still requires the explicit option. Power-off keeps an idle bootstrap host for later turn-on and scalar
terminal/resource/generation observations, with no retained native session. Bootstrap time never moves backwards.

The carrier drains surviving physical-output confirmations before admitting power control, retrying bounded pressure
on a later tick without advancing Guest. A transition retains its request and reservation until native stop and boot
complete; cancellation does not free ownership early. Unload fences that entry and waits asynchronously for the same
native predecessor before closing the host, without ordinary owner pumping. Service shutdown includes these predecessor
barriers. A failed predecessor cannot start a replacement. Existing StoreBusy retry and boot-result handoff rules apply.

This experimental implementation accepts redstone input, terminal snapshots/deltas, acknowledged terminal input and
canonical lines, resource and filesystem-generation queries, bounded file stat/list/read queries and executable
prepare/revision/install/discard requests. A dedicated request adapter
validates payload bounds before admission and checks the response kind before completing a request. The queued command
owns the input payload; the response registry retains only scalar identity/disposal metadata and its future. Deployment
candidates stay in Rust, bounded to four candidates and 16 MiB of artifact bytes per machine. Queued artifacts also
consume the JVM outbox byte budget; an individually oversized request is rejected before admission. Failed installs
consume their token as in the actor contract: a bounded disposal request must be acknowledged before returning the
original failure. An admitted install reserves its public token in the existing in-flight request metadata,
preventing another install or discard from racing its native candidate cleanup. A canceled prepare likewise retains its request slot until its candidate is reclaimed. Disposal
uses a fresh native request identity, so duplicate original responses cannot settle it. Pending disposal retries share
the ordinary outbox and a bounded owner pass; a full outbox still gets its normal flush allowance. Stop/checkpoint
fences these requests and native lifecycle cleanup discards remaining candidates without owner pumping. Successful
installation reports its committed filesystem generation, without executing the new program.

Accepted input updates host observations
even if its caller canceled the future; rejected input does not wake a parked host. File errors retain their typed
failure values, and duplicate or obsolete responses cannot change observations. Other raw effects and requests are rejected
before admission. External events go to an owner callback without another
result backlog. `CompleteAddons` replies and their grant are admitted to the ordered JVM outbox as one batch;
refusal retains the caller's exact completion and frame reservation. Rust matches exact task/request identities and
ignores obsolete or repeated completions, without a second JVM registry of native pending host requests.
A turn observer streams registered addon requests, redstone/sound requests and program start/stop actions to the carrier on its owner thread,
without collecting a second payload queue until the final report. These actions do not settle instruction credit or
start a continuation; only Quiescent does. Unhandled requests retain the external owner hook, and final-only callers
retain raw event delivery. Mixed batches forward only unhandled requests. Scope changes reuse host metadata; stopping
or replacing the carrier during a callback fences later actions and stale completions. Observer failure suppresses
further actions and fails that turn after credit settlement, without stopping other machines. Callers configure the canonical commands ABI 2 inspection rule on the fleet. Command-cost inspection
uses the canonical batch decoder and returns the number of commands through the ordered outbox; malformed packets
cost one and retain the normal typed dispatch failure. Rust alone applies the tick quota, defers an intact batch
or rejects a group larger than that quota. Unsupported inspection rules retain the external hook.

Until its cost enters the outbox, one unclassified request per machine retains only scalar identity, scope and
cost. Rust pauses execution at that request until classification; even a later tick or host reply cannot advance
that machine across the boundary. Control unwinding can still terminate its scope. Other machines keep running.
Mailbox pressure retries the same cost without granting execution.
A separate optional FIFO cache shares the immutable decoded packet with addon dispatch. Global/per-machine entry
limits equal the outbox entry limits; conservative retained-heap charges have their own global/per-machine byte
allowance equal to the outbox byte limits (an additional maximum of 16 MiB by default). Neither cache nor pending
classification allocates per idle machine. Eviction or a packet exceeding the cache allowance falls back to decoding
at dispatch without changing quota or execution semantics. Native rejection has no separate acknowledgement, so
unused cache entries remain bounded until eviction or scope/machine cancellation. Scope cancellation drops unsent
classifications and matching prepared packets; actor fencing clears both before off-thread handoff. Accepted scalar
cost commands may finish after cancellation; Rust ignores their obsolete request identities.

Native compiler events use the host submission rules and shared `CompilerCompletionRouter`.
Addresses bind computer identity, VM epoch and native transaction token. The shared router admits at most 64 pending
or unconsumed completed requests and charges at most 64 MiB for retained completions, including 256 bytes reserved per
active request for a bounded failure response. It converts one backend result at a time; an oversized result becomes
a typed compiler failure. Taking or canceling the completion releases its admission and byte reservation.
`ServerComputerCompiler` separately bounds outstanding and canceling targets to 64. Cancel invalidates routing on the
caller immediately and queues only a scalar target for one transient control worker. That worker terminates the
compiler process outside the caller and all routing locks, retains admission until cancellation finishes, and exits
when idle. A cancellation failure closes new admission. No waiting process termination runs on a server tick.

The native carrier keeps one small compiler entry per compiling actor, bounded by actor capacity, and polls only
these entries within its owner pass. Source snapshots stay in the existing compiler pipeline. Completed artifact bytes
remain router-owned until accepted into the charged native outbox. Native mailbox pressure retains that exact command;
a result larger than the configured outbox limit becomes a failure that fits the queue. Delivering a result grants no
instructions. Epoch, token and scope cancellation invalidate queued commands before off-thread close; those commands
then release without touching transferred host metadata. Checkpoint restoration reissues the original native transaction
with a fresh host epoch. Late results from the old epoch are discarded. An actual Ctrl+C termination removes Rust's
compiler wait before control unwinding, while ordinary terminal input keeps an active compilation parked.

The JVM outbox has entry limits plus shared and per-machine byte admission limits, both defaulting to 16 MiB.
These are charged-byte budgets, with no per-idle-machine reservation. Charges cover input payloads and immutable host
response trees, UTF-16 strings and an allowance per value/schema field; shared schemas are counted in full. Queue/map
overhead is separately bounded by entry limits; these charges are not a measurement of JVM heap or native retention.
Validation and sizing allocate no encoded response. Busy and failed sends retain the payload and its charge until
native acceptance or explicit removal. Atomic turn refusal retains no prefix and consumes no frame credit. Native host completion delivery separately admits an ordered
completion prefix within both entry and byte budgets; the carrier acknowledges only that prefix. Execution credit follows
in a separate grant, so replies can drain even through a one-entry queue. A refused grant remains pending even if the
last reply has already left the carrier and the observed Guest state is waiting. A completion exceeding the configured
single-machine or global byte limit fails explicitly rather than retrying forever.

Redstone and sound use the existing owner-thread world ports. The host normalizes builtin requests before the
carrier performs a world action; retries submit retained responses rather than replaying that action. Successful
changed redstone output queues a native confirmation before its Guest replies, and subsequent groups reduce against
the observed physical output. Unchanged output skips the world write. Sound replies retain each note's admission
result. Mixed redstone/sound groups retain the legacy unavailable response. The lazy carrier completion queue is
bounded to 512 entries, checked before world mutation; its scalar world replies share native outbox entry and byte
admission with addon replies. This does not add a complete carrier response-byte budget. Program stop cancels its
queued Guest replies but preserves physical redstone confirmations, including after terminal Guest state. These
confirmations and accepted replies drain before checkpoint capture without granting further Guest execution.

A native error with a known machine identity fails only that actor's pending turn and queries, cancels its external
requests and starts its independent close barrier. The same pump continues processing other actors. Stale machine
errors are ignored; errors without a machine identity remain fleet-wide failures. Native execution workers park
until explicitly signalled by command submission, freed output capacity, lifecycle acknowledgement or shutdown.
Before parking, a worker retries bounded output publication after its publisher registration. Successful publication
keeps it runnable while more retained events may fit; it sleeps only when publication cannot progress. Mailbox guards
are released before sleeping. These rules cover empty workers, output pressure and detach fences without timed polling;
queue entry and byte reservations remain unchanged.

Stop invalidates the machine identity and cancels outstanding owner work synchronously. An endpoint cancellation
callback, required explicitly when constructing the service, releases external resources on that thread; the native list of canceled requests is not queued back to the
owner. The unsent outbox deque then moves exclusively to one lazy lifecycle worker shared by the fleet. Its original
entry/byte reservations remain charged until native acceptance; unsent execution grants are canceled while ordered
acknowledged effects drain before stop. A reserved, not-yet-enabled close also belongs to a reentrant service shutdown;
callback-triggered host transfers are enabled only after the observation unwinds. A busy machine rotates behind the
others. Idle workers sleep, and a full blocked round backs off without delaying accepted command progress.

Stop barriers complete after native destruction, final filesystem generation reconciliation and host cleanup, without
owner result pumping. Canceling a returned future does not cancel the close. Closing metadata and one control operation
are bounded by the actor admission limit, with no per-idle-machine reservation. Ordinary output from other machines
remains untouched. Shutdown waits for both the event waiter and the lifecycle worker before allowing fleet destruction.
Transport errors retain the charged head for retry; cleanup errors fail the individual barrier.

The native checkpoint-detach primitive is available through both experimental transports. It drains accepted
commands, validates the capture tick, settles remaining external routes with Unavailable without advancing Guest,
and returns the machine with its tracked timers and exact pending compilation input. Settlement walks native route
indexes directly; no host request mirror or per-idle-machine buffer is added. Uninstalled deployment candidates are
discarded. The detach completion carries authoritative process scopes and confirmed redstone output from that machine.
The host checkpoint adapter replaces its potentially stale metadata before rebinding timers and serializing execution;
it does not dispatch queued lifecycle prefixes as new world effects. Recovery resets the host's scheduling hint to
Running so the next grant can reevaluate terminal or compiler waits, including input whose observation was never
pumped. Capture failure retains the recovered session for retry or explicit close. No new state is reserved per idle
machine. The same shared lifecycle worker now handles checkpoint detach, session recovery, host-state encoding and
capture in explicit phases; it never dispatches lifecycle prefixes as new effects. Accepted effects drain before the
checkpoint fence, and unsent grants are canceled. Byte admission precedes fencing and copying addon state: each payload
reserves its bytes plus the bounded maximum host metadata against the outbox's shared and per-machine byte limits.
After recovery, encoding replaces the addon copy and releases unused metadata allowance. StoreBusy retains that one
encoded payload and rotates to another machine; retries neither recover the session twice nor reencode host state.
Successful capture reconciles the final filesystem generation and closes the host before completing its barrier;
checkpoint failure discards stale saved execution, closes the recovered host and fails that barrier. Payload charges
are returned on either terminal path. Actor/query cancellation and reentrant ownership handoff follow the same rules
as stop. No owner pumping is required.

The common `ProgramRuntimeActorLease` separates admission from commit for bootstrap and active native hosts. Before native close
reservation, the carrier may drain ordered final host completion prefixes without advancing Guest. A refused reservation
retains no additional close commands or checkpoint payload. Already accepted replies remain owned and charged by the
outbox and drain before capture. Carrier lifecycle state, remaining replies and addon resources stay live after refusal.
Native admission reserves checkpoint bytes alongside remaining final command bytes. After
admission, the carrier fences callbacks before commit starts backend cancellation; repeated or reentrant closes join
one barrier, and canceling a returned future cannot cancel native work. Reservation actions are dropped on commit,
so completed leases do not retain checkpoint payloads or their backend preparation closures. Native `attach` produces
this lease for either an idle bootstrapping host or an already transferred host.

If addon state cannot be saved, native lease close drains acknowledged replies, detaches and recovers the session,
then discards stored execution before closing. This path reserves only scalar control bytes and reports the filesystem
generation after discard. StoreBusy keeps exclusive recovered ownership for retry. A later store reopen must not restore
the discarded execution. Native final addon and world replies use the streamed completion path; legacy raw
redstone/sound completion effects still reject before admission. The carrier exposes pre-fence pressure as a null close attempt to the world
lifecycle registry, which retains and retries that operation on the owner; accepted failures remain terminal barriers.
Bootstrap close uses the same deferred-admission signal as active close, so the registry retries queue pressure
without recording it as an accepted persistence failure.

Scalar groups select at most one queued head per machine in round-robin order, without waiting to fill
a batch. Accepted heads alone release entry/byte reservations; mailbox-full and unattempted heads retain
ownership. All results are reconciled before reporting a permanent machine failure. Payload/query/lifecycle
heads and single-machine queues use scalar calls. Native batch admission shares the scalar mailbox helper,
holds the owner registry once, and wakes each affected worker once. The lazy 664-byte transport buffer and
selection arrays belong to the service, not individual computers.


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
percentage. Grant counters are nullable: direct host advancement reports its grants, while the native fleet
exports semantic work and memory counters without scheduler grant counters. Its explicit resource
request composes the native response with host state/configuration without executing Guest or retaining a per-machine
snapshot. The terminal keeps heap/disk observations and displays an unknown budget ratio when grant counters are absent.
No sampling loop, history buffer, heap-content scan, or unsolicited FFM call runs for unobserved computers.

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
Admission requires a reachable native cycle or at least two removed dispatcher transitions on every
acyclic path to an exit. Short bridges before lifecycle operations retain the prefix tier. Region
selection shares the existing prepared JIT lookup with prefixes; it adds no separate per-block probe.
Machine pays the first block before entry, generated headers pay later entries including backedges,
and invocation-local state is constructed only when a ready region is selected.
Mixed prefixes can include non-allocating field and array operations through checked Rust callouts.
Calls, allocation, initialization, host requests and safepoints remain interpreter boundaries.

Each prepared program lazily owns 256 fixed hot-entry slots. Hotness and finalized modules are
shared by all admitted instances; direct-map collisions fall back. A process-wide single worker
accepts at most eight queued jobs. Queue bursts retry after exponentially spaced visits, capped
at 16384 visits per retry without permanently rejecting an entry.
Jobs carry bounded copied plans and weak program owners, with no instance pointer or host binding.
A process-wide 16 MiB budget charges finalized page-rounded code/readonly/data mappings and
64 KiB worst-case reservations for queued or compiling modules. Lowering enforces that per-module
bound, refunds unused headroom on success and returns the whole reservation on failure or cancellation.
A separate 2048-module guard bounds module metadata. Each prepared program admits at most 64 modules,
512 KiB and one pending compilation; this prevents a single program from occupying the global budget,
without promising equal coverage or ordering among arbitrarily many programs. Global-before-program
locks run only on admission, settlement and release. Native execution retains its ordinary shared lookup.
Slots denied capacity wait for a relevant budget release epoch before reheating, rather than permanently
finishing. One atomic progress word couples hotness/state/backoff/epoch to prevent late visitors from
corrupting a deferred slot; the slot table has no additional retained fields. Program budget ownership
is independent of JitState, so reservations cannot keep dead prepared programs alive or form a cycle.
The byte limit excludes program/module metadata, compiler transient allocations and the single
persistent compiler worker/stack. No per-VM state is added.
No cache eviction can invalidate an executing function; the final module owner explicitly frees
its executable mappings. The feature is absent from production dependency/code paths by default.

Native entry proves frame access ranges and requires enough remaining attempts and accounting
headroom for the entire single-block prefix. Smaller prefix budgets fall back at the same instruction.
Untraced connected regions check limits inside generated control flow, charging each block once and
preserving attempted-instruction, fixed-cost and entered-block overflow order. An invocation-local
stack record publishes exact original block/instruction, remaining budget and accounting on every
exit; unsupported successor blocks remain unpaid. Diagnostic block traces retain the ordinary
trace-preserving path. Both kinds of code use the same slots, compiler and executable limits.
Regions prove the complete planned block segment fits the attempt/headroom budget before executing
its instructions. Short budgets materialize the frame and counters through a shared exit, then use
the authoritative prepared interpreter for that already-paid block's precise tail. This adds no
per-session cache, second block charge or duplicate native body. Native-attempt telemetry excludes
interpreted tail instructions. The JIT owner completes a short tail after the thin native entry
returns, through a cold helper. The existing heap/frame/numeric interpreter dispatch stays inlined
so this extra caller does not outline the ordinary hot loop. Semantic fault guards still publish
the exact faulting instruction.
Failure exits retain exact fault PC/attempts and previous writes; NaN canonicalization and integer
wrapping/division follow the interpreter. Frame bytes are materialized at every exit, with no
native registers or addresses in checkpoints. Numeric regions without heap callouts promote at most
64 exact typed frame ranges to invocation-local SSA variables. Arithmetic and checked values are
candidates; copy-only temporaries stay in memory to bound side-exit register pressure. All partially
overlapping ranges stay memory-backed, while exact-width aliases use lossless bitcasts. A shared
exit epilogue restores every promoted written range, including dead values and earlier writes before
a fault. Prefixes and heap regions retain direct frame access. Promotion adds no retained per-session
cache, checkpoint state or instance pointer to shared code. Heap callouts use an invocation-only context borrowing
that frame and the current admission's heap/image; generated code never embeds a heap pointer,
header packing, instance reference or capability. The ordinary prepared heap executor owns all
reference, type, bounds and null checks; each callout selects its verified opcode at compilation
time, specializing that same implementation rather than defining separate heap semantics. Temporary frame reborrows and generated accesses run
sequentially, and a failed callout immediately exits with its Rust-owned fault/trap. No frame resize,
Guest allocation, collection or suspension can occur inside a native region.
Hot hit/native-attempt counters are separately opt-in through `jit-telemetry`. Ordinary experimental
JIT execution does not update those shared counters at native exits; the private statistics snapshot
marks them disabled and reports zero. Cold module, mapping, compiler-time and pending-job observations
remain available. This avoids serializing independent worker threads on one program's telemetry cache
line and adds no per-session state. The optional `jit-diagnostics` feature includes `jit-telemetry`
and counts exit categories and calls in a separate diagnostic build; it is excluded from ordinary
timing and memory measurements. The separate `jit-admission-diagnostics` feature records
cold admission reasons, queue/request latency and Linux compiler-thread CPU without enabling
hot native-exit counters; its extra program/slot metadata is absent from ordinary timing and
allocation-control builds. A full-queue rejection waits on the queue's capacity-release epoch, sampled
before admission to avoid lost progress. The next execution visit after a release can retry
without exponential reheating. A shared AtomicU64 per prepared program records a full-queue
epoch so other hot entries defer before reserving memory or constructing plans. A second check
under the program's pending reservation closes concurrent producer races; Full publishes this
shared pressure before releasing the rejected reservation. Resource denials retain their separate release epochs and
hotness thresholds. The queue still holds at most eight jobs, with one compiler and at most
one pending compilation per program. A position is reserved before constructing a plan,
so both builders and queued jobs share that eight-position envelope. The receiver
returns the position before compiling; abandoned plans and canceled jobs return it
through the same RAII permit. Plan construction/submission is outlined behind the
hotness claim, keeping the ready-native dispatch path thin. This is bounded admission, not strict FIFO fairness
between programs waiting to submit. No waiting-plan registry or per-session state is added.
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
Allocation checks the head of the request's partial size-class bucket before the rounded-up bitmap search.
A constant-space free-block hint covers fitting blocks hidden behind smaller list heads. Removing its block
invalidates the hint; insertions retain the larger candidate. The existing budgeted sweep observes surviving
free blocks and coalesced insertions, restoring an exact largest-block candidate before an allocation retry.
Consequently, size-class rounding cannot cause a post-collection OOM when a contiguous block fits, without
adding an unbudgeted free-list or arena scan to allocation.

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
