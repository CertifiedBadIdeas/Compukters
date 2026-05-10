# Tick-Independent Native Daemon Scheduler Design

## Goal

Decouple native daemon process scheduling from Minecraft server ticks.

The server tick should only refresh device resources and time state. Process execution should be driven by a daemon
executor that runs whenever the device has quota and runnable work, so terminal-to-shell handoffs do not wait for the
next Minecraft tick.

## Motivation

The current native daemon owns process metadata, process state, IPC, events, filesystem access, display state, and child
process scheduling. However, `BackgroundDeviceVm.requestSlice(serverTick)` still calls `DeviceDaemon::tick(...)`, and
that daemon tick selects one runnable pid and runs it until the next VM signal.

This makes process switching effectively tick-bound:

```text
server tick N:
  run terminal until it writes stdin / waits

server tick N+1:
  run shell until it writes stdout / waits

server tick N+2:
  run terminal until it renders stdout / waits
```

At 20 TPS, each extra handoff can add roughly 50 ms of visible latency. The VM can execute instructions quickly in Rust,
but the interactive path still feels delayed because scheduling is paced by Minecraft ticks.

## Scope

Included:

- Split daemon resource refill from daemon execution.
- Add a native scheduler loop that can run multiple ready processes in one executor pass.
- Wake the daemon executor when:
  - server tick refills quota;
  - an input event is enqueued;
  - IPC writes wake waiting readers;
  - a compile or host request completes;
  - sleeping processes become due.
- Preserve existing CKL APIs and ROM terminal behavior.
- Preserve Kotlin ownership of Minecraft integration, source loading, and CKL compilation.
- Keep display frame pumping separate, but ensure display presents can be produced without waiting for another server
  tick once runnable processes exist.
- Add profiling counters that make scheduler latency visible.

Excluded:

- Do not move CKL compilation into Rust in this slice.
- Do not change the terminal from CKL to host-rendered UI.
- Do not replace the current instruction quota model with instruction pacing yet.
- Do not start a permanent Rust-owned OS thread in this slice.

## Current Model

The current daemon API is tick-shaped:

```text
Kotlin server tick
  -> DeviceDaemon::tick(instructions, wallNanos, serverTick)
       -> add quota
       -> wake sleepers
       -> choose one runnable pid
       -> run that image until one signal
       -> return summary
  -> Kotlin drains host requests
```

This is correct but too coarse for interactive workloads. The `instructionsPerSlice` quota is refilled, but daemon
execution consumes only one scheduler turn per call.

## Proposed Model

Introduce a device-local daemon executor loop:

```text
Kotlin server tick
  -> daemon.refillQuota(serverTick, instructions, wallNanos)
  -> wake daemon executor

Kotlin input / compile completion / host completion
  -> update Rust daemon state
  -> wake daemon executor

Daemon executor
  -> daemon.runReadyUntilBlocked()
       while quota remains and runnable processes exist:
         choose next pid
         run image until signal
         handle signal
         wake affected processes
       return execution summary
```

The executor may still be a Kotlin coroutine for now. The important boundary is that scheduling decisions and process
handoffs happen inside Rust, not one server tick at a time.

## Rust API Shape

The daemon should expose operations with separate responsibilities:

```text
refill_execution_quota(instructions, wall_nanos, server_tick) -> quota snapshot
run_ready_until_blocked(max_turns) -> run summary
drain_host_requests() -> compile/host requests
complete_host_request(request_id, value)
complete_compile_program(request_id, image?, exit_code)
enqueue_event(name, args)
```

`run_ready_until_blocked` should stop when one of these is true:

- no runnable processes remain;
- instruction quota is exhausted;
- `max_turns` safety limit is reached;
- the daemon is stopped or freed;
- all runnable processes are waiting on Kotlin-owned work, such as compile requests or unresolved host calls.

It should not stop merely because one process yielded, slept, wrote IPC, read IPC, or woke another process.

## Kotlin Executor

`BackgroundDeviceVm` should own one daemon executor coroutine per native daemon.

The coroutine waits on an internal wake signal and then drains Rust work:

```text
while device is alive:
  wait for daemon wake
  do:
    summary = daemon.runReadyUntilBlocked(...)
    service host/compile requests
  while summary made progress or serviced requests resumed processes
```

Wake sources:

- `requestSlice(serverTick)` refills quota and signals the executor.
- `enqueueEvent(...)` forwards the event to Rust and signals the executor.
- host request completion signals the executor after resuming the blocked process.
- compile completion signals the executor after attaching or failing the child process.

The executor must not run concurrently with itself. A mutex, actor channel, or single coroutine mailbox should serialize
daemon execution.

## Sleep And Time

Server tick remains the source of game time. Sleeping processes are woken when `refill_execution_quota` observes a
`serverTick` at or beyond their deadline.

This keeps `runtime.sleep(ticks)` deterministic and Minecraft-aligned while removing tick-bound process handoffs.

## Host And Compile Requests

Rust should continue to convert daemon `process.spawn` and `process.run` into typed `CompileProgram` requests.

When `run_ready_until_blocked` emits host or compile requests, Kotlin services them and immediately resumes the daemon
executor. This lets a command compile, attach, run, write output, and return control to the terminal without waiting for
extra Minecraft ticks beyond the compile work itself.

## Display Frames

Display frame pumping remains independent. The daemon executor only ensures that display-producing CKL code can run as
soon as it is runnable.

After terminal code calls `display.present`, the existing native display wake/frame pump can send frame bytes to the
client. No terminal-specific display API is introduced.

## Profiling

Add or refine metrics for:

- daemon executor wake count;
- daemon executor active time;
- turns per executor pass;
- processes run per executor pass;
- quota exhausted vs idle stops;
- host/compile requests per pass;
- terminal input-to-present latency;
- terminal input-to-client-frame latency.

The key acceptance signal is that a single typed character or Enter submission can cause multiple process turns before
the next server tick.

## Acceptance Criteria

- Terminal input still works through CKL `events`, `ipc`, `process`, and `display` APIs.
- One daemon executor pass can run terminal and shell processes back-to-back when quota allows.
- Holding a key no longer waits for key release before visible progress.
- Enter no longer requires multiple Minecraft ticks for terminal-to-shell-to-terminal handoff when compilation is not
  involved.
- `runtime.sleep(ticks)` remains based on server ticks.
- Native daemon profiling shows more than one process turn per server tick under terminal workloads.
- Existing non-daemon fallback behavior remains intact.

## Future Direction: VM Speed Instead Of Tick Quota

The current design still uses a refillable quota. Later, the device speed model can move from "instructions per server
tick" to "time per instruction" or "target VM frequency".

In that model:

- each device profile defines instruction pacing, such as nanoseconds per instruction or instructions per second;
- better computers have lower per-instruction delay or higher target frequency;
- the daemon executor can run continuously, but it must stop or sleep when it is ahead of its virtual clock;
- Minecraft ticks remain useful for world time and sleep deadlines, not as the primary execution cadence.

This design keeps that path open by separating resource/time refill from scheduler execution.
