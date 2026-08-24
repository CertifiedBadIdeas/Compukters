# Rust-Owned Foreground Process Stack Design

> Issue: [#518](https://github.com/CertifiedBadIdeas/Compukters/issues/518)

## Context

Compukters currently starts the packaged shell artifact directly. The Rust
`ComputerMachine` owns one `Session`, while the same shell artifact is also
mounted as executable `/rom/shell`. This transitional bootstrap bypasses the
filesystem, gives the computer no ordinary boot program, and cannot run a guest
binary from `/home` without replacing the active VM.

Issue #521 established the Rust-owned `/rom` and `/home` namespace, executable
metadata, stable computer identity, and persistent world lifecycle. The next
layer is a single-lane foreground process model: boot, shell, and user programs
are ordinary verified Compukter Artifacts, and a parent synchronously waits
while one child owns the lane.

## Goals

- Boot every persistent computer from extensionless executable `/rom/boot`.
- Let `boot.kt` start `/rom/shell` through the same public `process.run` API
  used by shell and user programs.
- Keep process creation, verification, scheduling, cleanup, and result mapping
  inside Rust without a Kotlin or FFM round trip.
- Give every process an independent `Session`, heap, frames, and verifier state
  while sharing only explicitly delegated machine devices.
- Bound nesting, process starts, and aggregate reserved execution storage.
- Preserve the current one-task-at-a-time computer model.

## Non-Goals

- Background or concurrent processes, multiple runnable lanes, jobs, signals,
  cancellation, pipes, redirection, or stream handles.
- Passing command-line arguments or environment variables.
- Running Kotlin source files or compiling on a cache miss; #522 owns that
  workflow.
- A rich Kotlin `ProcessResult` or `CapabilitySet` type. The initial compiler
  subset exposes stable integer values, and a typed standard-library wrapper
  may replace them later without changing the Rust ABI.
- Writable executable installation through a general Minecraft/JVM filesystem
  API. Tests may seed `/home` through the existing bounded fixture boundary.

## Ownership and Execution Model

`ComputerMachine` owns a bounded stack of process frames:

```text
ComputerMachine
├── shared TerminalDevice
├── shared ComputerFileSystem
├── aggregate ProcessLimits / accounting
└── Vec<ProcessFrame>
    ├── Session
    ├── delegated capability mask
    ├── filesystem authority
    └── pending internal terminal/process request state
```

Only the last frame is runnable. A `process.run` request leaves the parent
`Session` suspended on its asynchronous capability call, verifies and starts a
child `Session`, and pushes the child. The parent consumes no guest budget while
the child is active. When the child terminates, Rust drops the child frame and
resumes the exact parent request with one bounded status code.

`Session` remains a generic verified execution unit. It does not learn about
paths, ROM, terminals, or process trees. The stack belongs in `ComputerMachine`,
where all shared devices and internal capability handling already live. Kotlin
continues to advance one opaque machine through FFM and never orchestrates
individual child sessions.

## Kotlin Process API

The trusted no-std API is supplied from a separate compiler bundle:

```kotlin
package process

suspend fun run(path: String, capabilities: Int): Int = 0
```

The compiler recognizes only the callable from the trusted process API bundle
and lowers it to asynchronous operation 0 of `compukter/process@1`. A guest
function with the same package, name, or signature remains ordinary guest code.

The first stable standard capability bits are:

```text
1  TERMINAL
2  FILESYSTEM
4  PROCESS
```

Additional addon bindings may occupy higher bits assigned by the admitted
machine configuration. A child mask must be a bitwise subset of the parent's
mask and the machine's available bindings. Unknown, negative, or widening masks
are rejected before filesystem access or child admission. The root boot frame
starts with all configured machine capabilities. `boot.kt` delegates all three
standard capabilities to shell; shell initially delegates the same standard
set to user programs. This is explicit but intentionally not a sandbox policy:
future shell commands can choose a smaller mask without changing `process.run`.

The integer result is the stable encoding of a Rust `ProcessResult` enum:

```text
 0  EXITED
 1  INVALID_CAPABILITIES
 2  DEPTH_LIMIT
 3  START_LIMIT
 4  INVALID_PATH
 5  NOT_FOUND
 6  PERMISSION_DENIED
 7  NOT_EXECUTABLE
 8  INVALID_ARTIFACT
 9  ADMISSION_FAILED
10  START_FAILED
11  ALLOCATION_EXHAUSTED
12  QUOTA_EXHAUSTED
13  TRAPPED
14  FAULTED
15  HOST_FAILED
16  IO_FAILED
```

Child entry-point values are deliberately discarded in this version because
the supported Kotlin `main` contract returns `Unit`. Diagnostic internals are
bounded and are not exposed as arbitrary guest strings. The table is versioned
with `compukter/process@1` and covered by Rust/compiler conformance tests.

## Executable Admission

`process.run` accepts one canonical absolute virtual path. It does not infer an
artifact from a filename extension. Rust performs these steps atomically from
the parent's perspective:

1. Validate the path with the VFS limits.
2. Require the caller's process authority and filesystem execute authority for
   the path.
3. Require a regular file with executable metadata.
4. Read the complete bounded file through `ComputerFileSystem`.
5. Decode and verify its Compukter Artifact magic, version, structure, ABI, and
   declared capabilities.
6. Resolve only bindings enabled by the delegated mask.
7. Reserve process depth, start count, heap, and frame-storage capacity.
8. Start the child and push it only after every prior step succeeds.

Any failure before the push resumes the parent immediately with a start result;
no partially created frame or resource reservation remains. Every invocation
re-verifies the bytes even when the same path ran before. Artifact caching may
be introduced separately only if its key includes the exact immutable bytes and
admission context.

The initial filesystem authority covers `/` with owner rights. `/rom` remains
immutable in the VFS regardless of that authority, while its executable files
can be read and started. Disabling the child's `FILESYSTEM` bit removes the
guest filesystem ABI, but does not prevent the machine's internal process
loader from reading an executable that the parent was authorized to execute.

## ROM and Bootstrap

The deterministic ROM image contains two executable entries sorted by canonical
path:

```text
/rom/boot
/rom/shell
```

Their checked-in sources remain `system/programs/boot.kt` and
`system/programs/shell.kt`. Gradle compiles both deterministically and packages
extensionless runtime resources; `.cpkt` is only a build-artifact convention,
not a guest filename or production resource name.

Persistent computer power-on asks Rust to start `/rom/boot`; Java does not pass
the boot artifact bytes separately. The generic direct-artifact `VmSession`
entry remains available for compiler conformance, playground, and isolated VM
tests, but Minecraft no longer uses it as a boot channel. The obsolete installed
artifact/image-source bootstrap is removed from the computer block entity so
there is one production source of executable bytes: the Rust VFS.

`boot.kt` performs one ordinary `process.run("/rom/shell", 7)` and then returns.
The shell keeps its built-ins. For a non-built-in single-token command, it runs
an absolute path as written or resolves a bare name as `/home/<name>`, delegates
the standard mask, and reports a bounded non-zero status. Argument parsing and
arguments passed to child entry points are deferred.

## Quotas and Accounting

The existing `ExecutionProfile` remains the per-session limit. A public
machine-owned Rust `ProcessLimits`, supplied when `ComputerMachine` starts, adds
at least:

- maximum stack depth, with a default of at least three frames for
  `boot -> shell -> user`;
- maximum total process starts for one machine lifetime;
- maximum aggregate reserved heap bytes;
- maximum aggregate reserved frame-storage bytes.

Starting a child reserves its admitted per-session capacities against aggregate
limits. Popping the child releases those reservations. Reservation uses checked
arithmetic and happens before the child becomes visible. This deliberately
charges capacity rather than current live bytes: it is deterministic, prevents
overcommit, and does not require cross-heap GC accounting.

The guest and maintenance budgets supplied to `advance` are spent only by the
active top frame, so a nested process cannot multiply CPU available in one
server tick. Terminal, filesystem, persistence, and input queue quotas remain
machine-wide because the devices are shared. Per-session host request limits
still protect each artifact, while the process-start limit bounds turnover
across the complete machine lifetime.

## Shared Devices and Requests

Terminal and filesystem operations from the active frame are handled
synchronously or asynchronously by `ComputerMachine` exactly as today. Pending
terminal and process request IDs belong to their frame because request IDs are
only unique inside one `Session`. External addon host requests are exposed only
for the active top frame, and a host response always targets that frame.

Terminal contents and queued input survive child push/pop because the terminal
belongs to the machine. If a child terminates while owning an unfinished active
terminal event, cleanup discards that event and clears its pending ownership so
the parent cannot inherit a poisoned request. The current path-based guest
filesystem ABI opens and closes any internal VFS handle within one operation;
general process-owned guest handles remain outside this issue.

## Failure and Lifecycle Semantics

A child normal halt, trap, quota exhaustion, VM fault, or host failure becomes a
bounded `ProcessResult` delivered to its parent. It does not directly terminate
the computer. Failure to resume the suspended parent is a machine fault because
the stack invariant is then unrecoverable.

The root boot frame has no parent. Its terminal outcomes remain ordinary
`ComputerAdvanceOutcome` values and therefore drive the existing Kotlin
`ProgramRuntimeState`. If shell returns, boot receives zero and returns, so the
computer halts normally. If boot cannot launch shell, it also returns after the
non-zero result; richer boot diagnostics are deferred to the standard library.

Shutdown, chunk unload, block removal, and reboot drop the complete stack from
top to bottom, cancel pending requests, and then release the filesystem lease.
No parent is resumed during teardown. Reboot creates a fresh terminal, fresh
process accounting, and a new root `/rom/boot` frame while retaining the
persistent `/home` filesystem and stable computer ID.

## Verification

Rust tests cover:

- root boot admission from executable `/rom/boot`;
- parent suspension, top-only scheduling, nested run, and exact resume request;
- non-widening standard and addon capability masks;
- path, metadata, verification, admission, and start result codes;
- depth, total-start, aggregate heap, and aggregate frame reservations;
- child halt, trap, quota, fault, host failure, and cleanup;
- shared terminal/VFS behavior and unfinished terminal-event cleanup;
- full stack shutdown and fresh reboot construction.

Compiler and JVM tests cover trusted `process.run` lowering, rejection of
same-named guest calls, deterministic `boot.kt` and `shell.kt` artifacts,
extensionless packaged resources, ROM ordering, boot-only Minecraft startup,
and FFM result mapping. A dedicated GameTest proves that boot reaches the shell,
a seeded `/home` executable runs as a nested child, and reboot starts a fresh
boot stack without losing persistent filesystem contents.

The completion gate is:

```bash
./gradlew-sandbox-dev-parallel verifyLocalFull --rerun-tasks
git diff --check
```
