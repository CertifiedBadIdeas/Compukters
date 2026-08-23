# Rust-Owned Persistent Computer Filesystem Design

> Issue: [#521](https://github.com/CertifiedBadIdeas/Compukters/issues/521)

## Context

Compukters currently starts one packaged artifact and has no guest filesystem.
The accepted programming loop requires extensionless system executables, Kotlin
project sources, compiler outputs, and later IDE state. Routing every file
operation through Minecraft or Kotlin would make ordinary reads and writes
asynchronous host requests and would couple the VM to the server tick.

The filesystem must therefore belong to the Rust computer runtime. Minecraft
provides an isolated world storage root, a stable computer identity, a packaged
ROM image, and lifecycle calls. It does not proxy, inspect, or receive a callback
for each guest filesystem operation.

## Goals

- Provide an immutable `/rom` and a persistent per-computer writable `/home`.
- Keep guest filesystem operations synchronous inside Rust.
- Prevent guest paths and data from escaping the world storage sandbox.
- Make persistence bounded, crash-recoverable, observable, and explicitly
  stoppable.
- Make all access capability-scoped so later child processes can receive less
  authority than their parents.
- Establish the filesystem boundary required by foreground processes in #518
  and the compiler/cache workflow in #522.

## Non-Goals

- Foreground or concurrent process creation.
- Kotlin compilation, compiler-worker control, or compilation caching.
- Symlinks, hard links, cross-computer mounts, removable media, networking, or
  host filesystem passthrough.
- POSIX users, groups, ownership bits, ACL syntax, file locking, memory mapping,
  or watching directories.
- Immediate storage destruction when a block is broken.

## Ownership Boundary

Rust exposes one world-scoped storage runtime and one filesystem instance per
active computer:

```text
Minecraft world
  -> creates/opens Rust WorldFileSystemStore
       absolute storage root
       world identity
       configured limits
  -> opens ComputerFileSystem
       stable ComputerId
       canonical ROM image
  -> advances and closes the computer normally

Rust WorldFileSystemStore
  -> owns root validation and locking
  -> owns object and metadata persistence
  -> owns the bounded persistence worker
  -> owns recovery, flush, drain, and close

Rust ComputerFileSystem
  -> owns virtual trees, capabilities, handles, and quotas
  -> executes guest operations synchronously
  -> emits immutable persistence records
```

Minecraft may ask for health and the latest durable generation. It cannot open
guest paths or mutate guest files through a general Java API. The compiler
integration in #522 will use a dedicated bounded project-snapshot protocol, not
general filesystem access.

## Identity and World Scope

`ComputerId` is an opaque random 128-bit identity persisted by the block entity.
It is not derived from block position, dimension, player name, or a host path.
A chunk unload flushes and closes the active machine but retains its identity and
storage. A newly placed computer receives a new identity.

Breaking a computer creates a tombstone rather than synchronously deleting its
storage. Tombstoned storage remains recoverable for a configured retention
period and is collected only by an explicit bounded world-storage maintenance
operation. Carrying an identity in a computer item is deferred until portable
computers are designed; identity duplication must never clone mutable storage
implicitly.

The storage root is inside the corresponding Minecraft world save. World copies
and backups therefore include computer filesystems. The global compilation cache
from #522 is deliberately outside this per-world storage boundary.

## Virtual Namespace

Every filesystem has exactly two initial mounts:

```text
/
├── rom   immutable, shared by ROM image identity
└── home  mutable, isolated by ComputerId
```

The ROM image is canonical, hash-identified, and admitted by Rust before it is
mounted. Identical ROM images may share immutable in-memory nodes. Home metadata
is never shared between computers, even when the underlying content-addressed
object store deduplicates identical bytes.

Paths are case-sensitive and consist of valid Unicode scalar values. `/` is the
only separator. NUL, empty components, `.` and `..` are rejected. Names are not
Unicode-normalized: comparison uses the exact scalar sequence supplied by the
guest. Limits apply independently to encoded path bytes, component count,
component bytes, and directory depth.

The first version has no symlinks or hard links. A guest path is parsed entirely
as a virtual path and is never appended to an operating-system path.

## Nodes, Handles, and Ordering

A node contains bounded virtual metadata:

```text
kind: file | directory
logical size
content object identity (files only)
node generation
executable flag (files only)
```

The executable flag is explicit metadata. Extensionless Compukter Artifacts in
#518 are not recognized by filename suffix; execution requires both executable
authority and successful artifact admission.

Open handles are opaque indexes paired with generations. Closing a handle
invalidates its generation, so a stale handle cannot address a later allocation
that reused the same slot. Handle tables and per-process handle counts are
bounded. Directory listings are snapshots returned in exact scalar
lexicographic order, making results deterministic across host platforms.

## Guest Operation Surface

The initial synchronous Rust operation surface is:

```text
stat(path)
list(path)
open(path, mode)
read(handle, offset, maximumBytes)
write(handle, offset, bytes)
truncate(handle, size)
createDirectory(path)
remove(path)
rename(source, destination, replace)
close(handle)
sync()
```

Reads and writes are bounded and may complete partially only when their result
explicitly reports the accepted byte count. `rename` is atomic in the virtual
tree. Replacing a destination is explicit; no operation silently overwrites a
directory. Removing a non-empty directory is rejected in the first version.

`sync` waits for the current filesystem generation to become durable. Normal
guest execution does not wait for disk on every mutation. World save and machine
close call the same durability boundary through lifecycle APIs.

## Capability Model

Verifier admission proves structural and type safety; it does not prove benign
behaviour. A valid malicious program can delete every file for which it has
delete authority. Filesystem safety therefore comes from explicit capabilities.

A capability contains:

- a mount and exact virtual subtree root;
- allowed operations: inspect, list, read, create, write, delete, rename, execute;
- independent logical-byte, operation, and handle ceilings where applicable.

Capabilities are opaque and cannot be widened. A parent may delegate the same
authority or a strict subset to a future child process. `/rom` rejects mutation
regardless of a malformed or overbroad capability. `/home` mutation requires an
appropriate capability.

The initial boot process receives read/execute authority for `/rom` and bounded
owner authority for `/home`. #518 will define exact child delegation and
aggregate process quotas without changing this filesystem model.

## Quotas and Backpressure

Limits exist for at least:

- logical bytes per computer and per file;
- node count and directory entries;
- path, component, and directory depth;
- open handles and bytes per operation;
- mutations per execution slice;
- queued persistence bytes and records;
- journal bytes, checkpoint bytes, and recovery work;
- tombstones and maintenance work per pass.

A mutating operation reserves node, byte, journal, and queue capacity before it
changes the in-memory tree. Once reservation succeeds, enqueueing its immutable
record cannot fail. If capacity cannot be reserved, the operation returns a
bounded `Busy` or `QuotaExceeded` error without changing visible state.

Physical content deduplication never changes quota accounting: each computer is
charged its logical bytes. Garbage collection is bounded and cannot be required
for an already-admitted guest mutation to complete.

## Host Storage Representation

Guest paths never become host filenames. The world store uses fixed internal
directories and Rust-generated identifiers:

```text
root/
├── lock
├── objects/ab/abcdef...      immutable content objects
├── computers/<opaque-id>/
│   ├── checkpoints/<generation>
│   ├── journal/<segment>
│   └── tombstone
└── store-metadata
```

All variable host names are validated fixed-format encodings generated from
opaque identities, generations, or cryptographic content hashes. Guest strings
are stored only inside checksummed bounded records.

The root must be absolute, canonical, pre-created for the world, and rejected if
it is a symlink or platform reparse point. A store lock prevents two processes
from opening the same root. Rust never resolves storage outside that admitted
root.

## Persistence and Recovery

An admitted mutation follows this sequence:

1. Parse and validate the virtual operation.
2. Check capability and all logical quotas.
3. Reserve persistence queue capacity.
4. Apply one atomic in-memory tree mutation and increment its generation.
5. Enqueue an immutable checksummed journal record and any immutable content
   objects.
6. Let the owned persistence worker write and synchronize records in generation
   order.

Checkpoints are written to a fresh temporary file, synchronized, atomically
renamed, and followed by directory synchronization where supported. Journal
records include format version, computer identity, sequence, previous committed
sequence, payload length, and checksum.

Recovery admits the newest complete checkpoint and then a contiguous checksummed
journal suffix. An incomplete or corrupt uncommitted tail is discarded. A gap,
identity mismatch, invalid confirmed record, or corrupt confirmed checkpoint
places that computer storage into `Faulted`; recovery never guesses or silently
continues past confirmed corruption.

Content objects are immutable and verified against their identifier when read
from disk. Unreferenced objects are collected only after a bounded reachability
pass from confirmed checkpoints and journals.

## Worker and Lifecycle Safety

The persistence worker receives only structured immutable commands such as
`PutObject`, `AppendRecord`, `PublishCheckpoint`, `FlushGeneration`, and
`CollectBatch`. It cannot execute guest operations, resolve virtual paths, run
VM code, or invent new mutations.

The store lifecycle is explicit:

```text
Created -> Active -> Draining -> Closed
                    \-> Faulted
```

- The worker is owned and joinable; there are no detached threads.
- `Draining` rejects new stores and mutations, completes admitted records, and
  resolves pending flush requests.
- `close` drains, synchronizes, stops, and joins the worker.
- `Closed` cannot enqueue or write.
- I/O errors transition to `Faulted`; retries are bounded and never spin.
- Reads of already admitted in-memory data may continue in a degraded read-only
  mode after a persistence fault, but new mutations are rejected.
- Dropping a handle is a best-effort safety net, not the normal close protocol.

Background activity may persist already-authorized mutations between server
ticks. It cannot advance guest execution or create filesystem changes without a
foreground VM operation.

## FFI Boundary

FFI additions are lifecycle-oriented rather than file-oriented:

- open a world store from an admitted root and limits;
- open a computer filesystem by `ComputerId` and ROM image;
- query bounded health and durable generation;
- request flush through a target generation;
- tombstone or recover a computer identity;
- close a computer filesystem;
- drain and close the world store.

Guest `stat`, `read`, `write`, and related operations remain inside Rust and do
not cross FFI. Every FFI buffer is length-bounded, copied under an explicit
ownership rule, and rejected on invalid handles or lifecycle state.

## Errors

Guest-visible filesystem failures are bounded typed codes:

```text
InvalidPath
NotFound
AlreadyExists
NotDirectory
IsDirectory
NotEmpty
ReadOnly
PermissionDenied
StaleHandle
QuotaExceeded
Busy
StorageFaulted
Closed
```

Host diagnostics may add a bounded implementation detail for logs, but guest
results never include host paths, operating-system usernames, arbitrary I/O
messages, or source/file contents. Storage failures are deterministic at the
virtual boundary even when platform error details differ.

## Security and Privacy Properties

- No guest-controlled string participates in host path resolution.
- ROM mutation is impossible through the writable storage implementation.
- Capability checks and quota reservations precede every visible mutation.
- A malformed artifact cannot bypass verifier-visible filesystem operations.
- A valid malicious artifact can damage only explicitly delegated writable
  subtrees and cannot access another computer or the host filesystem.
- Logs use computer identity, generation, operation code, and bounded error
  class; they do not log file contents or full guest paths by default.
- World storage and the future compilation cache use different roots and APIs.
- The compiler integration receives only an explicit immutable bounded snapshot,
  never a general handle to the world store.

## Testing Strategy

Rust unit and property tests cover:

- path parsing, scalar validation, exact comparison, and all limits;
- capability narrowing and denial for every operation;
- handle generations and stale-handle reuse;
- deterministic listings, atomic rename, replacement, and ROM immutability;
- logical quota accounting despite physical object deduplication;
- queue reservation and no-mutation-on-backpressure;
- journal/checkpoint round trips, torn tails, gaps, checksum failures, and
  deterministic recovery;
- worker drain, fault, retry bounds, close, and absence of post-close writes;
- tombstone retention and bounded collection.

FFI/Kotlin tests cover root admission, invalid handles, stable identity, health,
flush generations, fault propagation, buffer limits, and close ordering.

Minecraft integration tests cover two-computer isolation, unload/reload
persistence, reboot preservation, world save flush, immutable ROM mounting, and
recoverable tombstoning. Full verification uses the Rust suite,
`verifyLocalFull --rerun-tasks`, and `git diff --check`.

## Delivery Order

Implementation should proceed through independently testable layers:

1. Pure virtual paths, trees, handles, capabilities, and quotas.
2. Canonical ROM admission and `/rom` plus in-memory `/home` mounts.
3. Object/journal/checkpoint codecs and deterministic recovery.
4. Bounded owned persistence worker and lifecycle state machine.
5. World-store and computer-filesystem FFI lifecycle.
6. Stable Minecraft `ComputerId`, world-root integration, persistence, and
   tombstone tests.

Only after #521 is verified should #518 depend on executable files from this
filesystem. #522 then adds compilation snapshots and writes compiler outputs
through a dedicated protocol.
