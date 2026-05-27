# Device Quota Process Scheduler Design

## Goal

Replace the current per-coroutine slice permit model with a device-level execution quota model where the JVM/Minecraft
tick refills one shared VM budget, and the VM runtime decides how to spend that budget across runnable CKL processes.

This is a scheduler boundary change. The server tick should remain the source of external time and quota, but it should
not implicitly choose which CKL process runs by waking whichever Kotlin coroutine receives a permit first.

## Motivation

The native runtime now owns much of the terminal hot path: image execution, strings, filesystem access, events, IPC,
display raster operations, display frame bytes, and native process wait/wake state. The next remaining latency source is
the process and scheduler model around that native runtime.

Today, `BackgroundDeviceVm` exposes a single `slicePermits` channel with capacity one. Each server tick attempts to put
one `Unit` into that channel. Any running VM coroutine that reaches a scheduling point may consume the permit. This was
reasonable when the runtime behaved like one main program, but it becomes awkward once shell and child programs run
together:

- process scheduling is partly controlled by Kotlin coroutine wake order;
- the runtime has no central view of which CKL processes are runnable, waiting, sleeping, or exited;
- `process.run` appears expensive because the parent coroutine waits for a child coroutine rather than becoming an
  explicit process-table waiter;
- current working directory state is still too device-global because runtime creation mutates shared path resolver
  state;
- the design is not a clean stepping stone toward a Rust-owned process scheduler.

The desired model is closer to a real machine: the outside world delivers a timer tick and a CPU budget, while the
device runtime kernel decides which process receives CPU time.

## Current Model

Current execution flow:

```text
Minecraft/server tick
  -> BackgroundDeviceVm.requestSlice(serverTick)
  -> slicePermits.trySend(Unit)
  -> whichever Kotlin process coroutine reaches schedulingPoint() receives the permit
  -> that process runs until pause/yield/sleep/wait/host-call
```

Important current properties:

- quota is binary, not numeric;
- at most one permit can be queued;
- permit ownership is not tied to a process id;
- child processes are Kotlin coroutines launched by `VmProcessManager`;
- `process.run(path, arg)` is implemented as `spawn(path, arg)` followed by `wait(pid)`;
- native process wait/wake can already resolve completion state, but the actual scheduling of parent and child work is
  still Kotlin-coroutine driven.

## Target Model

Target execution flow:

```text
Minecraft/server tick
  -> device.addExecutionQuota(instructions, wallNanos, serverTick)
  -> device process scheduler runs while quota remains
       - picks a runnable process
       - runs its native image for a bounded instruction/time slice
       - handles yielded signals
       - moves processes between runnable/waiting/sleeping/exited states
       - wakes process waiters, IPC waiters, event waiters, and sleepers
  -> returns when quota is exhausted or no process is runnable
```

The quota is shared by the device, not by one process. The process scheduler owns the choice of how much work each
runnable process receives.

## Ownership Boundary

Owned by JVM/Kotlin for this slice:

- Minecraft/server tick integration;
- profile-derived quota calculation;
- CKL source loading and compilation for JVM-backed child launch;
- Workbench and workspace integration;
- fallback runtime implementation;
- metrics aggregation and Markdown reporting.

Owned by the VM scheduler model:

- process table;
- per-process runtime state;
- process-local working directory and argument;
- runnable queue;
- wait queues for process wait, IPC, events, and sleep;
- execution quota accounting;
- fair selection of runnable processes;
- process exit propagation and parent wakeups.

Owned by Rust eventually:

- native image handles for each runnable process;
- native process table;
- native wait queues;
- instruction-budget dispatch loop;
- quota-consuming process scheduler.

This design intentionally allows a Kotlin implementation first and a Rust implementation later. The important boundary
is the explicit scheduler model, not the implementation language of the first iteration.

## Execution Quota

Each server tick should add a numeric budget:

- `instructions`: derived from `profile.resources.cpu.instructionsPerSlice`;
- `wallNanos`: derived from `profile.resources.cpu.wallTimeGuardNanosPerSlice`;
- `serverTick`: used for sleep wakeups and profiling.

The scheduler may run zero, one, or many processes within that budget. It stops when:

- instruction budget is exhausted;
- wall-clock guard is exhausted;
- there are no runnable processes;
- the device is stopped or rebooting.

Quota must not grow without bound. If ticks arrive while the VM is blocked or overloaded, accumulated quota should be
capped at one per-tick budget. The runtime must not accumulate seconds of CPU debt and then freeze the server while
catching up.

## Process State

Each process should have explicit state:

- `Runnable`;
- `WaitingEvent(filter?)`;
- `WaitingIpc(channel)`;
- `WaitingProcess(pid)`;
- `Sleeping(untilTick)`;
- `Exited(exitCode)`;
- `Crashed(message)`.

Each process should also carry process-local runtime state:

- pid;
- parent pid;
- program path or label;
- argument string;
- working directory;
- native image handle or fallback program handle;
- pending resume value, if the last signal needs one.

The parent shell waiting on `process.run` should become `WaitingProcess(childPid)`. It should not remain an arbitrary
Kotlin coroutine suspended inside a host call while the child consumes the same global permit channel.

## Scheduling Policy

The first policy should be simple round-robin over runnable processes:

1. Pop the next runnable pid.
2. Run it for a bounded process quantum.
3. If it remains runnable and quota remains, push it to the back of the queue.
4. If it waits, sleeps, exits, or crashes, move it to the appropriate state.

The process quantum can start as the current native image instruction budget. Later it can be tuned separately from the
device tick quota, for example:

- device quota: 8192 instructions per tick;
- process quantum: 1024 instructions per process turn.

This lets one busy process make progress without monopolizing the whole device budget.

## Signal Handling

Native image signals should become scheduler events:

- `Pause` means the process used its quantum and remains runnable;
- `Yield` means resume with unit and place the process behind other runnable processes;
- `Sleep(ticks)` moves the process to `Sleeping(untilTick)`;
- `WaitEvent(filter)` moves it to `WaitingEvent(filter)`;
- `WaitPoll(channel, wakeSequence)` moves it to an IPC/event wait state or uses the existing native wake protocol;
- `WaitProcess(pid, wakeSequence)` moves it to `WaitingProcess(pid)`;
- `HostCall` remains a boundary for operations not yet native-owned;
- `Halt(value)` exits the process.

Host calls should not disappear in this slice. The scheduler should treat them as explicit waiting states so the process
does not consume quota while the host call is pending.

## Kotlin-First Migration

The first implementation should reshape the Kotlin runtime without moving the whole scheduler to Rust immediately:

1. Replace shared `slicePermits: Channel<Unit>` with a device quota counter and scheduler wake signal.
2. Introduce a process table object with explicit states and per-process runtime state.
3. Make cwd and argument process-local instead of mutating the shared path resolver during `createRuntime`.
4. Change process wait/run so the parent process becomes an explicit waiter.
5. Keep child loading and compilation in Kotlin.
6. Keep native process table registration/completion bridges in place.
7. Make profiling show quota added, quota spent, runnable turns, queue delay, process wait time, and per-process exits.

This keeps behavior stable while removing the most awkward part of the current model: a single binary permit consumed by
whichever coroutine wakes first.

## Rust Migration Path

After the Kotlin scheduler model is explicit and tested, move the scheduler internals into Rust:

```text
Kotlin requestSlice(...)
  -> JNI addExecutionQuota(kernelHandle, instructions, wallNanos, tick)
  -> JNI runDeviceScheduler(kernelHandle)
  -> Rust DeviceRuntimeKernel runs runnable ImageVmHandle processes
  -> Kotlin only handles unresolved host calls and JVM-backed spawn/load/compile
```

At that point each native process should own an image handle in Rust, and Kotlin should no longer create a long-lived
coroutine per CKL process. Kotlin becomes the bridge for unresolved host services, not the process scheduler itself.

## Profiling Requirements

Runtime profiling should expose:

- quota added per tick;
- quota spent per tick;
- scheduler turns;
- runnable queue length high-water mark;
- per-process active execution time;
- per-process wait time by reason;
- process wakeups by reason;
- host-call pending time;
- process exit codes and crash counts.

Existing historical process metrics should remain readable.

## Acceptance Criteria

- Minecraft/server tick still controls how much total VM work can happen per tick.
- CKL processes are scheduled from an explicit process table, not by racing for a shared `Channel<Unit>` permit.
- `process.run` parks the parent as a process waiter and wakes it when the child exits.
- Working directory and argument are process-local.
- Terminal workloads still boot, spawn child commands, and respond to keyboard input.
- Native process wait/wake behavior remains compatible with the existing Rust process table.
- Profiling distinguishes execution quota, scheduler active work, process wait time, and host-call wait time.
- The design remains compatible with a later Rust-owned scheduler.
