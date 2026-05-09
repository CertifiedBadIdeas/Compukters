# Rust-Owned Process Table and Wait/Wake Design

## Goal

Move process bookkeeping, child lifecycle state, and wait/wake semantics for `process.wait` into the Rust-owned device
runtime kernel, while keeping JVM/Minecraft process spawning and external integration in Kotlin for this slice.

This is the next process boundary after the Rust-owned IPC/event hot path. The goal is to reduce runtime ping-pong for
child process completion and make process state behave more like a native runtime primitive instead of a generic host
call callback.

## Motivation

The current Rust device runtime kernel already owns the terminal-critical hot path for execution, events, IPC, and
display metadata. The remaining process layer still sits mostly in Kotlin:

- Kotlin tracks child processes and their completion state;
- `process.wait` is still a JVM-side coordination point;
- terminal and shell workloads depend on process completion wakes to continue their interactive loop;
- process metadata is split between Kotlin bookkeeping and runtime state.

This split works, but it keeps process completion on the wrong side of the native boundary. Once events and IPC are
Rust-owned, process wait/wake is the next useful primitive to move next to them.

## Scope

Included:

- Store process table entries in the Rust device runtime kernel.
- Store parent/child relationships, exit codes, and completion state in Rust.
- Fast-path `process.wait` inside native image runners when an image is attached to a native kernel.
- If future process metadata getters are added, they should read from the same native process table.
- Add a native blocking/wait protocol for `process.wait` so waiting does not appear as a generic host call.
- Preserve Kotlin as the bridge for spawning JVM-backed child programs in this slice.
- Preserve Kotlin fallback for non-native execution and for runtimes without an attached native kernel.
- Keep existing CKL APIs and bundled ROM programs unchanged.
- Extend profiling to distinguish:
  - generic process host calls;
  - native process fast-path calls;
  - native process wait signals;
  - active native process bookkeeping;
  - scheduler wait time spent waiting for child completion.

Excluded:

- Do not move `process.run`, `process.spawn`, or JVM child program launch into Rust in this slice.
- Do not move Minecraft block/entity lifecycle, screen binding, or network packet transport into Rust.
- Do not alter shell or terminal ROM programs.
- Do not introduce a separate process IPC channel model beyond the existing runtime IPC path.
- Do not remove Kotlin fallback process APIs.

## Ownership Boundary

Owned by Rust:

- process table;
- process ids and parent process ids;
- process running/completed/failed state;
- process exit codes;
- wait queues and wait wakeups for `process.wait`;
- native completion bookkeeping for child processes already known to the kernel.

Owned by Kotlin:

- spawning JVM-backed child programs;
- attaching spawned child handles to the native process table;
- JVM-side program start/stop lifecycle;
- Minecraft block/entity/screen lifecycle;
- fallback process implementation;
- metrics aggregation and Markdown report generation.

Owned by CKL/userland:

- shell and terminal process usage;
- when to call `process.wait` and any future process metadata getters exposed by CKL;
- child orchestration logic in ROM programs.

## Runtime Model

The native device runtime kernel should become the shared process-state object for native image runners:

```text
Kotlin spawn bridge
  -> register child in native process table
  -> Rust DeviceRuntimeKernel
       - process table
       - exit state
       - parent/child links
       - wait queues
       - events / IPC / display metadata
  -> Rust ImageVmHandle fast-path process imports
  -> Kotlin only for spawn bridge, fallback, and external integration
```

Each native image runner attached to the same device kernel should see the same process table. Parent shell and child
programs should therefore observe a single coherent process graph, while Kotlin remains responsible for the actual JVM
launch of child programs in this slice.

## Process Model

Each native process entry should carry at least:

- process id;
- parent process id;
- running/completed state;
- exit code when completed;
- waiters blocked on completion;
- optional spawn label or program name for profiling/debugging.

Future process metadata accessors, if introduced, should derive their values from the same Rust process table rather
than from Kotlin-side duplicate bookkeeping.

## Wait/Wake Model

`process.wait(pid)` should be handled in Rust without the generic host-call bridge.

If the target process is already completed, the image runner returns the exit status immediately.

If the target process is still running, the image runner should suspend with a native wait signal that tells Kotlin:

- which process id is being waited on;
- which native image/process is waiting;
- whether the wait is for a specific child or any child, if the current API supports that shape.

Kotlin should park the waiting image until either:

- the target process completes;
- the normal scheduler wakes it for cancellation/stop;
- the attached native kernel is detached or freed.

After wake-up, Kotlin resumes the image with a unit-like or status-like resume value. The image runner then rechecks the
native process table and returns the exit result. Waiting time should be recorded separately as native process wait
time, not as generic host-call active time.

## Spawn Bridge

For this slice, Kotlin still creates the actual JVM child program. After launch, Kotlin should register the child in the
native process table with its parent id and the process metadata needed for wait/exit queries.

When the child completes, Kotlin should update the native process entry and wake any Rust-side waiters. This keeps the
spawn boundary in Kotlin while making process completion native-owned.

This bridge should preserve existing JVM child lifecycle semantics:

- program launch failures still surface through the existing Kotlin path;
- native table entries should not claim a child exists until launch succeeds;
- completed child entries should remain visible long enough for `process.wait` and exit code queries;
- stale handles should resolve safely rather than crashing the native kernel.

## Fallback Behavior

If an image has no attached native kernel, or the native kernel does not support a requested operation, the image
runner must continue emitting the existing generic `HostCall` signal. This keeps tests and non-native runtime paths
stable.

Unknown functions should fallback rather than crash unless the existing native fast path has already accepted the module
and argument types are invalid.

## Profiling Requirements

The runtime profiling report should make the migration visible:

- generic `process.wait` host-call counts should drop in native runs;
- native process fast-path counters should show how many waits Rust handled;
- native wait time should be separated from active execution time;
- child completion wakeups should be visible in native process metrics;
- historical comparison should keep old host-call metrics readable.

## Migration Strategy

1. Add focused tests around the native process table and wait semantics.
2. Register spawned JVM child processes in the native process table after successful launch.
3. Fast-path `process.wait` with a native wait signal and completion wakeups.
4. Update profiling to show native process fast-path counts and native wait time.
5. Run terminal/profile workloads and compare process host-call counts before considering the spawn boundary itself.

## Acceptance Criteria

- Bundled terminal workloads still boot and can wait for child commands.
- CKL shell and terminal source code do not need process-specific host operations beyond the existing API surface.
- Native profiling shows `process.wait` host-call counts reduced or eliminated for native image runs where the
  process table is attached.
- Blocking `process.wait` no longer appears as a generic host-call wait.
- Kotlin fallback tests still pass.
- Native process tests cover pid metadata, parent-child relationships, exit codes, waiters, and stale entries.
- The design keeps JVM child spawning in Kotlin for this slice.
