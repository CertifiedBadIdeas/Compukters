# Rust-Owned IPC and Events Hot Path Design

## Goal

Move terminal-critical IPC, event, and display metadata operations from generic Kotlin host calls into the Rust-owned
device runtime kernel, while keeping process management and Minecraft integration in Kotlin for this slice.

This is a hot-path migration. It should reduce runtime ping-pong for terminal input/output without turning the terminal
or shell into host-side logic.

## Motivation

The current Rust image runtime already owns CKVM execution, native strings, filesystem operations, and the display
framebuffer/raster path. The latest runtime profile shows the display raster path is no longer the primary terminal
cost:

- display operations are no longer recorded on the Kotlin display path for native runs;
- client frame apply/build times are small compared with runtime wait and process orchestration;
- terminal workloads still produce many generic host imports for `events`, `ipc`, `runtime.poll`, and display metadata;
- `display.width`, `display.height`, and `display.primary` are tiny individually but frequent enough to keep noise in
  the interactive loop.

The next useful boundary is the device runtime kernel: input events and IPC channels should live next to the native
image runner, not behind a generic host-call bridge.

## Scope

Included:

- Store event queues in the Rust device runtime kernel.
- Store IPC channels, channel buffers, limits, and closed state in the Rust device runtime kernel.
- Fast-path these CKL host imports inside the native image runner when an image is attached to a native kernel:
  - `events.tryPull`
  - `events.argCount`
  - `events.argInt`
  - `events.argBool`
  - `events.argString`
  - `ipc.open`
  - `ipc.write`
  - `ipc.read`
  - `ipc.tryRead`
  - `ipc.close`
  - `runtime.poll`
  - display metadata: `display.primary`, `display.isAttached`, `display.width`, `display.height`
- Add a native blocking/wait protocol for `runtime.poll` and later `ipc.read` so waiting does not appear as a generic
  host call.
- Preserve the Kotlin fallback for non-native execution and for runtimes without an attached native kernel.
- Keep existing CKL APIs and bundled ROM programs unchanged.
- Extend profiling to distinguish:
  - generic host calls;
  - native kernel fast-path calls;
  - native wait signals;
  - active native IPC/event work;
  - scheduler wait time.

Excluded:

- Do not move `process.run`, `process.spawn`, `process.wait`, or child VM lifecycle into Rust in this slice.
- Do not move Workbench, editor state, Minecraft networking, screen sessions, or block/entity lifecycle into Rust.
- Do not introduce terminal-specific host primitives.
- Do not replace CKL terminal behavior with a host terminal renderer.
- Do not remove Kotlin `RuntimeHostBridge` or fallback APIs.

## Ownership Boundary

Owned by Rust:

- native device event queue;
- captured event argument store for events returned to CKL;
- IPC channel registry;
- IPC channel buffer limits;
- nonblocking event and IPC polling;
- native wait bookkeeping for `runtime.poll`;
- display metadata for native displays already attached to the native display registry.

Owned by Kotlin:

- external input collection from Minecraft/client UI;
- enqueueing external events into the native kernel;
- process manager and child VM lifecycle;
- scheduler ticks and slice permits;
- fallback event and IPC implementation;
- metrics aggregation and Markdown report generation.

Owned by CKL/userland:

- shell and terminal behavior;
- stdio protocol over IPC;
- when to call `runtime.poll`, `events.tryPull`, `ipc.read`, `ipc.write`, and display metadata functions.

## Runtime Model

The native device runtime kernel should become the shared device-state object for native image runners:

```text
Kotlin input / scheduler
  -> native kernel enqueue event / tick
  -> Rust DeviceRuntimeKernel
       - events
       - captured event arguments
       - IPC channels
       - display registry metadata
       - filesystem
       - display framebuffer
  -> Rust ImageVmHandle fast-path host imports
  -> Kotlin only for fallback, process calls, and external integration
```

Each native image runner attached to the same device kernel should see the same event queue and IPC channels. This keeps
parent shell and child programs on one device bus while process orchestration remains Kotlin-owned.

## Event Model

External Kotlin code should enqueue real event arguments into the native kernel, not placeholder payloads. The native
kernel should decode the existing event payload format into `VmValue` arguments at enqueue time or store already-decoded
values from JNI.

`events.tryPull(filter?)` should:

- return the first matching event from the native event queue;
- return an empty event record if no event is available;
- preserve existing filter semantics;
- capture event arguments into a native event argument store and return an `Event` record with `name`, `id`, and
  `argCount`.

`events.arg*` should read from the native event argument store by event id. Invalid ids or indexes should follow current
fallback behavior as closely as practical, preferring empty/zero defaults over crashes where Kotlin does the same.

## IPC Model

The native kernel should implement channel operations equivalent to the Kotlin `IpcChannelRegistry`:

- `ipc.open()` allocates a channel id.
- `ipc.write(channel, text)` appends text up to the configured per-channel byte limit.
- `ipc.read(channel)` returns available buffered text, or parks the current native process as an IPC waiter until text is
  written.
- `ipc.tryRead(channel)` returns and clears available buffered text, or `""`.
- `ipc.close(channel)` closes the channel.

`runtime.poll(channel)` remains the terminal multiplexing primitive for stdout/events. `ipc.read(channel)` is also native
because shell/user programs use `stdio.readLine(ctx)`, and after process scheduling moves into the daemon those programs
must not fall back to Kotlin host calls for stdin.

## Native Poll Wait Protocol

`runtime.poll(channel)` and `ipc.read(channel)` should be handled in Rust without the generic host-call bridge.

If IPC text or an event is immediately available, the image runner returns a `Poll` record directly to CKL.

If nothing is available, the image runner should suspend with a native wait signal that tells Kotlin:

- the image is waiting for either IPC data on a channel or an event;
- which channel is involved;
- optional event filter if a later CKL API needs it.

Kotlin should park that process until either:

- an event is enqueued into the native kernel;
- IPC data is written to the requested channel;
- the normal scheduler wakes it for cancellation/stop.

After wake-up, the daemon scheduler reruns the same native instruction. The image runner rechecks native IPC/event state
and returns either the `Poll` record or the text for `ipc.read`. Waiting time should be recorded separately as native wait
time, not as generic host-call active time.

## Display Metadata Fast Path

The existing Rust display registry already knows attached display ids and dimensions. The native image runner should
answer these imports directly:

- `display.primary`
- `display.isAttached`
- `display.width`
- `display.height`

Display raster primitives already have a native path and should remain there. Kotlin fallback behavior remains unchanged.

## Fallback Behavior

If an image has no attached native kernel, or the native kernel does not support a requested operation, the image runner
must continue emitting the existing generic `HostCall` signal. This keeps tests and non-native runtime paths stable.

Unknown functions should fallback rather than crash unless the existing native fast path has already accepted the module
and argument types are invalid.

## Profiling Requirements

The runtime profiling report should make the migration visible:

- generic `events.*`, `ipc.*`, `runtime.poll`, and display metadata host-call counts should drop in native runs;
- new native kernel fast-path counters should show how many operations Rust handled;
- native wait time should be separated from active execution time;
- terminal workloads should report whether input events reached the VM through native event enqueue;
- historical comparison should keep old host-call metrics readable.

## Migration Strategy

1. Add focused tests around the native kernel event and IPC semantics.
2. Encode/decode real event arguments across JNI and enqueue them into the native kernel.
3. Fast-path nonblocking `events.tryPull`, `events.arg*`, `ipc.open`, `ipc.write`, `ipc.tryRead`, and `ipc.close`.
4. Fast-path display metadata from the Rust display registry.
5. Add the native poll wait signal and Kotlin scheduler handling for `runtime.poll`.
6. Update profiling to show native fast-path counts and native wait time.
7. Run terminal profiling and compare host-call counts before considering `process` migration.

## Acceptance Criteria

- Bundled terminal workloads still boot and accept keyboard input.
- CKL shell and terminal source code do not need terminal-specific host operations.
- Native profiling shows generic `events.*`, `ipc.*`, `runtime.poll`, and display metadata host-call counts reduced or
  eliminated for native image runs.
- Blocking `runtime.poll` no longer appears as a generic host-call wait.
- Kotlin fallback tests still pass.
- Native kernel IPC/event tests cover queue limits, filters, argument access, IPC buffering, and closed channels.
- The design keeps `process` management in Kotlin for this slice.
