# Rust-Owned Device Daemon Design

## Goal

Move Compukter Kraft from a Kotlin-owned VM process runtime with Rust acceleration to a Rust-owned device daemon model.
Rust should own the active computer runtime: processes, scheduling, image handles, filesystem access, IPC/events,
display frame production, program loading, and process lifecycle. Kotlin should become the Minecraft integration bridge,
not the process/runtime owner.

This is the long-term option 3 architecture. It should preserve the existing CKL userland model while changing where
the runtime kernel lives.

## Motivation

The current native runtime already owns many hot paths:

- CKVM image execution;
- native strings;
- native filesystem operations;
- native IPC and event queues;
- native display raster/frame bytes;
- native process wait/wake state;
- device-level execution quota and native scheduler metadata.

However, Kotlin still owns the most important runtime shape:

- every CKL process is still a Kotlin coroutine;
- `NativeImageVmRunner` still runs one image handle from Kotlin;
- signal handling still loops through Kotlin;
- `process.spawn` and `process.run` still create Kotlin-managed process jobs;
- scheduler decisions are still mirrored and observed rather than truly executed by the Rust kernel.

That means Rust can make individual operations fast, but the whole device still pays coordination costs at the Kotlin
process boundary. A terminal workload with shell plus child commands still depends on Kotlin coroutine scheduling,
process creation, and signal dispatch.

The desired architecture is closer to a real device: Minecraft delivers time, input, storage integration, and client
bridges; the device runtime kernel owns the machine.

## Current Model

Current native execution flow:

```text
Kotlin BackgroundDeviceVm
  -> creates Kotlin process/coroutine
  -> creates Rust ImageVmHandle for that process
  -> runs NativeImageVmRunner loop from Kotlin
  -> Rust image returns signals
  -> Kotlin handles signal, updates process table, resumes image
```

Rust owns important state, but Kotlin still controls process execution. The process table in Rust is increasingly real,
but it does not yet drive the image runner.

## Target Model

Target execution flow:

```text
Minecraft/Kotlin tick
  -> NativeVmBindings.tickDeviceDaemon(handle, instructions, wallNanos, serverTick)
  -> Rust DeviceDaemon
       - refills device quota
       - wakes due sleepers and waiters
       - selects runnable pids
       - runs attached ImageVmHandle turns
       - handles native signals directly where possible
       - emits host-boundary requests only for unresolved Kotlin services
  -> Kotlin drains host requests, display frames, and outbound events
```

Kotlin should no longer create a long-lived coroutine per CKL process for native mode. A native device should have one
Rust daemon handle and one Kotlin-side bridge object that ticks and services boundaries.

## Ownership Boundary

Owned by Rust:

- native process table;
- pid allocation for native processes after boot;
- process state transitions;
- runnable queue and scheduler policy;
- native image handles per process;
- process-local working directory and argument;
- `process.spawn`, `process.run`, `process.wait` runtime behavior;
- IPC channels and waiters;
- event queues and waiters;
- sleep waiters;
- filesystem operations for the native filesystem root;
- display framebuffer, raster operations, frame deltas, and frame bytes;
- device quota accounting;
- native runtime profiling counters that originate inside the daemon.

Owned by Kotlin:

- Minecraft tick integration;
- device/block lifecycle;
- save/load of Minecraft-side device metadata;
- client networking packets;
- screen/session UI;
- Workbench/editor integration;
- CKL source compilation while the compiler remains Kotlin-owned;
- fallback JVM runtime path;
- metrics aggregation and Markdown report generation;
- host services that are not yet native-owned.

Owned by CKL/userland:

- shell behavior;
- terminal behavior;
- process API semantics;
- filesystem paths and program conventions;
- IPC protocols between userland programs.

## First Implementation Slice

The first slice should not attempt to move every bridge at once. It should make Rust own native process execution while
keeping Kotlin as the provider for unresolved host services.

Included in the first slice:

- introduce a `DeviceDaemon` layer around the existing `DeviceRuntimeKernel`;
- move the active native process run loop into Rust;
- let Rust run image handles selected by the native scheduler;
- handle these signals inside Rust:
  - `Pause`;
  - `Yield`;
  - `Sleep`;
  - `WaitEvent`;
  - `WaitPoll`;
  - `WaitProcess`;
  - `Halt`;
- surface unresolved work to Kotlin through structured daemon events:
  - generic host call;
  - compile/load program request;
  - process start image request if Kotlin compiled the image;
  - crash/error notification;
- keep Kotlin compilation for `.ck` sources;
- keep current Minecraft screen/client packet integration;
- keep fallback JVM runtime untouched.

Excluded from the first slice:

- do not rewrite the CKL compiler in Rust;
- do not move Workbench/editor into Rust;
- do not replace Minecraft networking with a Rust TCP protocol;
- do not introduce a separate OS process for the daemon;
- do not change CKL userland APIs unless an existing API is impossible to preserve.

## Daemon API Shape

The native binding should evolve toward a device-level API:

```text
createDeviceDaemon(profile limits, native filesystem root?) -> daemonHandle
bootDeviceDaemon(daemonHandle, boot image or boot program request)
tickDeviceDaemon(daemonHandle, instructions, wallNanos, serverTick) -> daemon tick summary
drainDaemonHostRequests(daemonHandle) -> byte batch
completeDaemonHostRequest(daemonHandle, request id, encoded result)
drainDisplayFrames(daemonHandle) -> byte batch
enqueueDeviceEvent(daemonHandle, event)
writeDeviceIpc(daemonHandle, channel, text)
stopDeviceDaemon(daemonHandle)
freeDeviceDaemon(daemonHandle)
```

The existing `DeviceRuntimeKernelHandle` can remain the internal handle initially. The public JNI shape should move
toward daemon language so Kotlin code stops thinking in terms of individual image runners.

## Process Model

Rust process entries should contain:

- pid;
- parent pid;
- process state;
- program path;
- argument string;
- working directory;
- image handle;
- pending resume value;
- pending host request id, if blocked on Kotlin;
- exit code or crash message;
- lightweight per-process counters for profiling.

Boot should create pid `1` in Rust. Child pids should be allocated by Rust once `process.spawn` moves into the daemon.
During migration, Kotlin may still provide compiled child images, but Rust should own pid creation and lifecycle.

## Program Loading Model

There are two loading modes:

1. **Precompiled image path:** Rust loads `.ckim` or bundled image bytes from the native filesystem/ROM image registry.
2. **Kotlin compiler boundary:** Rust emits a compile/load request for a `.ck` path. Kotlin compiles it and returns image
   bytes. Rust creates the child image handle and marks the process runnable.

The first implementation should support the Kotlin compiler boundary because the CKL compiler is still Kotlin-owned.
The design should keep room for precompiled images so ROM and firmware can later avoid Kotlin compilation.

## Host Boundary Protocol

When a native process reaches an operation Rust cannot handle, the daemon should not call Kotlin synchronously from deep
inside the image runner. Instead, it should emit a structured request and park the process:

```text
HostRequest {
  requestId,
  pid,
  kind,
  moduleName?,
  functionName?,
  arguments?,
  path?,
  workingDirectory?,
}
```

Kotlin drains requests, performs the work, and completes the request with either:

- an encoded CKVM value;
- image bytes for compile/load requests;
- an error that crashes or rejects the process according to current semantics.

This keeps Rust as the scheduler owner while allowing Kotlin services to remain available.

## Signal Handling

Rust should handle scheduler-native signals without returning to Kotlin:

- `Pause`: process remains runnable if quota remains.
- `Yield`: resume with `Unit`, requeue behind other runnable processes.
- `Sleep(ticks)`: move to `Sleeping(untilTick)`.
- `WaitEvent(filter)`: move to event waiter state.
- `WaitPoll(channel, wakeSequence)`: move to IPC/event waiter state.
- `WaitProcess(pid, wakeSequence)`: move to process waiter state.
- `Halt(value)`: complete process and wake waiters.
- `HostCall`: create a host request and park the process.

This is the main performance win: common scheduling signals stop bouncing through Kotlin.

## Kotlin Runtime Changes

Native mode should eventually replace:

```text
VmProcessManager -> Kotlin coroutine per process -> NativeImageVmRunner per process
```

with:

```text
NativeDeviceDaemonRuntime
  -> one daemon handle
  -> tick daemon on server tick
  -> drain host requests
  -> complete host requests
  -> drain display frames
```

The current JVM runtime remains as fallback and as a comparison target during migration.

## Profiling Requirements

Profiling should make ownership visible:

- daemon ticks;
- daemon active time;
- daemon idle ticks;
- native scheduler turns;
- native process image turns;
- native process exits and crashes;
- native signal counts by kind;
- host-boundary request counts and wait times;
- compile/load request counts and wait times;
- remaining generic Kotlin host calls;
- display frame bytes and frame batches.

Historical reports must remain readable when old profiles do not contain daemon fields.

## Testing Strategy

Rust tests:

- process table owns pid allocation and lifecycle;
- daemon tick consumes quota and runs selected image handles;
- scheduler-native signals update process states without Kotlin;
- host calls park the process and create host requests;
- completing host requests resumes the correct process;
- process waiters wake on child completion;
- compile/load requests create child processes when completed with image bytes.

Kotlin tests:

- JNI daemon API exposes compact byte/long-array ABI;
- native daemon runtime boots a simple image;
- host requests can be drained and completed;
- fallback JVM runtime still runs existing tests;
- profiling records daemon fields.

Integration/profile tests:

- terminal boot still reaches shell;
- shell can spawn a child command;
- held input does not backlog worse than current native mode;
- runtime profile shows process scheduling no longer dominated by Kotlin coroutine permit waits.

## Migration Strategy

1. Add daemon data structures around the existing kernel without changing runtime behavior.
2. Add daemon tick API that can select a process and run one attached image handle turn in tests.
3. Move native signal handling for `Pause`, `Yield`, `Sleep`, `WaitProcess`, `WaitEvent`, and `WaitPoll` into Rust.
4. Add host request queue for generic host calls.
5. Add Kotlin request drain/complete bridge.
6. Move boot process execution to daemon mode behind an opt-in flag.
7. Move child process spawn/run/wait into daemon mode.
8. Update profiling and comparison reports.
9. Make daemon mode the default native mode after terminal workloads pass.
10. Remove or demote Kotlin coroutine-per-process native path after the daemon path is stable.

## Acceptance Criteria

- Native mode can boot pid `1` without a long-lived Kotlin process coroutine.
- Rust owns native process scheduling and signal-driven state transitions.
- Kotlin can service unresolved host requests without owning the process run loop.
- `process.spawn`, `process.run`, and `process.wait` work for native terminal workloads.
- Terminal workloads still render through the native display frame path.
- Existing JVM fallback tests still pass.
- Runtime profiling shows daemon process turns and reduced Kotlin scheduler/permit involvement.
- The architecture keeps a path to Rust-owned precompiled ROM/program loading without requiring a Rust compiler port in
  the first slice.
