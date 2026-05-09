# Rust-Owned Process Spawn Registration Design

## Goal

Move process registration, process table ownership, child lifecycle state, and exit propagation into the Rust-owned
device runtime kernel, while keeping JVM-backed child program launch in Kotlin for this slice.

This is the next step after Rust-owned process wait/wake semantics. The goal is to make Rust the source of truth for
which processes exist, which ones are running, and which exit code they produced, without moving the actual JVM launch
mechanism itself into Rust yet.

## Motivation

The current Rust device runtime kernel already owns the native process table and native `process.wait` wakeups. The
remaining process flow still relies on Kotlin to keep process identity and lifecycle in sync:

- Kotlin starts JVM-backed child programs;
- Kotlin knows when a child launch succeeded or failed;
- Kotlin currently remains the bridge point for attaching spawned children to the native process table;
- child completion still needs an explicit Kotlin-side propagation path into the native process state.

That split is acceptable for launch, but it leaves Rust with only a partial view of the process graph. Once `wait`
already lives in Rust, the next useful boundary is to make spawn registration and completion propagation native-owned
as well, so process state behaves like a single coherent runtime table instead of a Kotlin mirror with a Rust cache.

## Scope

Included:

- Keep JVM-backed child launch in Kotlin.
- Register successfully launched children in the Rust process table.
- Store parent/child relationships, spawn labels, and running/completed state in Rust.
- Propagate successful child completion into the Rust process table.
- Propagate child exit codes into Rust so `process.wait` can resolve from native state.
- Wake Rust-side waiters when a child exits.
- Keep existing CKL process APIs unchanged.
- Preserve Kotlin fallback behavior for runtimes without an attached native kernel.
- Extend profiling so native process completion and wait propagation are visible in reports.

Excluded:

- Do not move JVM child program launch itself into Rust in this slice.
- Do not move Minecraft block/entity lifecycle, screen binding, or packet transport into Rust.
- Do not redesign terminal or shell ROM programs.
- Do not introduce a new process IPC protocol separate from the existing runtime kernel path.
- Do not remove Kotlin fallback process APIs.

## Ownership Boundary

Owned by Rust:

- process table;
- process ids and parent process ids;
- running/completed state;
- exit codes;
- wait queues and wakeups for `process.wait`;
- completion propagation state for already launched children;
- process metadata visible to native image runners.

Owned by Kotlin:

- JVM-backed child launch;
- launch failure detection and reporting;
- initial bridge registration after successful launch;
- JVM-side child shutdown plumbing;
- Minecraft block/entity/screen lifecycle;
- fallback process implementation;
- metrics aggregation and Markdown report generation.

Owned by CKL/userland:

- shell and terminal process usage;
- when to call `process.wait`;
- child orchestration logic in ROM programs.

## Runtime Model

The native device runtime kernel becomes the shared process-state object for native image runners:

```text
Kotlin JVM launch
  -> on success, register child in native process table
  -> Rust DeviceRuntimeKernel
       - process table
       - exit state
       - parent/child links
       - wait queues
       - events / IPC / display metadata
  -> Rust ImageVmHandle fast-path process imports
  -> Kotlin only for JVM launch, fallback, and external integration
```

Each native image runner attached to the same kernel should observe the same process table. Parent shell and child
programs therefore share one coherent runtime graph, while Kotlin remains responsible for starting the JVM-backed
child.

## Process Registration Model

After Kotlin launches a child program successfully, it should register the child in the native process table with at
least:

- process id;
- parent process id;
- program path or spawn label;
- running state.

Registration should happen only after launch succeeds. A failed launch should not create a native process entry that
pretends the child existed.

The native process table should treat the registration as authoritative for:

- `process.wait`;
- future process metadata getters;
- completion wakeups;
- profiling counters that summarize process activity.

## Completion Propagation Model

When the Kotlin child lifecycle reports that a launched child has exited, Kotlin should update the corresponding native
process entry with the exit code and completion state.

That update should:

- preserve the completed entry long enough for waiters to observe it;
- wake any native waiters blocked on that child;
- keep the process entry readable for native `process.wait` and future metadata queries;
- avoid panicking if Kotlin reports completion for a stale or already-finished handle.

If Kotlin receives a completion event for an unknown or already-removed process id, it should fail safely and keep the
native process table consistent.

## Wait/Wake Interaction

This slice keeps the existing Rust-native `process.wait` behavior:

- if the child is already completed, the native image runner returns the exit code immediately;
- if the child is still running, the native image runner suspends with a native wait signal;
- Kotlin resumes the image when the child completion propagation updates the native process table;
- the native image runner then rechecks the table and returns the exit code.

The new part in this slice is that the completion propagation path becomes explicit and native-owned, rather than being
an implicit Kotlin-side mirror.

## Fallback Behavior

If an image has no attached native kernel, or the native kernel does not support a requested operation, the image
runner must continue emitting the existing generic `HostCall` signal. This keeps tests and non-native runtime paths
stable.

Unknown functions should fallback rather than crash unless the existing native fast path has already accepted the
module and argument types are invalid.

## Profiling Requirements

The runtime profiling report should make the migration visible:

- generic `process.wait` host-call counts should stay low in native runs;
- native process registration and completion propagation should be visible in the process lifecycle metrics;
- native wait time should remain separated from active execution time;
- child completion wakeups should be visible in native process metrics;
- historical comparison should keep old host-call metrics readable.

## Migration Strategy

1. Add focused tests around spawn registration and completion propagation.
2. Register launched JVM child programs in the native process table after successful launch.
3. Propagate child exit codes into the native process table and wake blocked waiters.
4. Update profiling/reporting to show process registration and completion propagation alongside `process.wait`.
5. Keep JVM-backed launch in Kotlin until the process lifecycle boundary is stable enough to consider deeper migration.

## Acceptance Criteria

- Bundled terminal workloads still boot and can wait for child commands.
- A successfully launched child becomes visible in the native process table.
- Child completion updates the native process entry with the final exit code.
- Native waiters wake when the child completes.
- Kotlin fallback tests still pass.
- Native process tests cover registration, completion propagation, wait resolution, stale completions, and parent-child
  relationships.
- The design keeps JVM-backed child launch in Kotlin for this slice.
