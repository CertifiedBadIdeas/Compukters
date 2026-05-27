# Rust-Owned Native Wait Scheduler Design

## Goal

Make native `runtime.poll` waits park on the Rust device kernel wake state instead of repeatedly yielding and rerunning
the VM slice while no IPC data or events are available.

This keeps terminal behavior in CKL/userland, but removes scheduler churn from idle terminal loops and from parent/child
stdio waits.

## Motivation

The Rust-owned IPC/events hot path moved terminal-critical operations out of generic Kotlin host calls. After that
migration, terminal profiles no longer primarily point at display raster work or generic `events.*`/`ipc.*` calls.
The remaining hot-path shape is wait churn:

- `runtime.poll` can reach a native `WaitPoll` signal quickly;
- Kotlin currently handles that signal with `runtime.yield()`;
- the process is scheduled again even when the underlying Rust kernel state did not change;
- the same VM instruction is replayed until an event or IPC write finally arrives.

That model is correct, but too noisy. It burns scheduler slices on “nothing happened yet” checks and makes interactive
latency depend on how many waiting processes are being rescheduled.

## Scope

Included:

- Wrap the native device runtime kernel in a Rust-owned wait handle that contains:
  - the existing `DeviceRuntimeKernel` state;
  - a mutex;
  - a condition variable;
  - a monotonic wake sequence.
- Expose the kernel wake sequence to native VM signals and JNI.
- Extend `VmSignal::WaitPoll` so it carries the wake sequence observed when the VM decided it had to wait.
- Add a JNI blocking wait primitive that parks until:
  - the kernel wake sequence advances;
  - a bounded timeout expires;
  - the native handle is invalid.
- Notify waiters when Rust-owned kernel state changes in ways that can unblock polling:
  - event enqueue;
  - IPC write;
  - IPC close.
- Update Kotlin native image scheduling so `WaitPoll` uses the JNI wait primitive instead of unconditional
  `runtime.yield()`.
- Keep the existing no-resume replay model: after wake-up, the image reruns the `runtime.poll` instruction and observes
  current kernel state.
- Add profiling counters for native wait calls, wait duration, and timeout count.
- Optionally use the same native wait primitive inside the existing `ipc.read` bridge loop so blocking reads stop
  burning active VM/scheduler time, while still keeping full `ipc.read` fast-path migration out of this slice.

Excluded:

- Do not move process management, child lifecycle, or `process.*` host calls to Rust.
- Do not add terminal-specific host primitives.
- Do not move shell, line editing, wrapping, scrolling, or stdio protocol behavior out of CKL.
- Do not introduce an async Rust runtime.
- Do not replace Minecraft/client integration or Workbench filesystem behavior.

## Architecture

The current native kernel is plain shared state behind `Arc<Mutex<DeviceRuntimeKernel>>`. This design changes the shared
object into an explicit runtime handle:

```text
Kotlin scheduler / external input
  -> JNI DeviceRuntimeKernelHandle
       - Mutex<DeviceRuntimeKernel>
       - Condvar
       - wake sequence
  -> Rust ImageVmHandle
       - fast-path IPC/event/display metadata
       - emits WaitPoll(channel, observedWakeSequence)
  -> Kotlin NativeImageVmRunner
       - waitForDeviceWake(handle, observedWakeSequence, timeout)
       - rerun image after wake
```

`DeviceRuntimeKernel` remains the owner of events, IPC channels, display metadata, filesystem, and framebuffer state.
The new handle owns synchronization. This keeps domain logic in the kernel and keeps parking/wake mechanics in one
small wrapper.

## Wake Semantics

The kernel wake sequence is monotonic. Any mutation that can make a waiting `runtime.poll(channel)` produce a different
answer advances the sequence and notifies the condition variable.

The required wake sources are:

- `enqueue_event(...)`, because `runtime.poll` can return an event;
- `write_ipc(channel, text)`, because `runtime.poll(channel)` can return stdout/stdin text;
- `close_ipc(channel)`, because a reader may need to observe closed state or stop waiting.

Pure reads do not advance the sequence. Display raster operations do not advance this scheduler wake sequence unless
they become observable through `runtime.poll`.

## Native Poll Flow

When CKL calls `runtime.poll(channel)` inside the native image runner:

1. Rust checks IPC and event availability inside the native kernel.
2. If data exists, Rust returns the normal `Poll` record directly.
3. If nothing exists, Rust reads the current wake sequence and emits `WaitPoll(channel, wakeSequence)`.
4. Kotlin calls `waitForDeviceWake(handle, wakeSequence, timeoutMillis)`.
5. If the returned wake sequence advanced, Kotlin immediately resumes the native image loop.
6. If the wait timed out, Kotlin yields once to preserve cancellation, fairness, and shutdown behavior.
7. The image replays the same `runtime.poll(channel)` instruction and rechecks kernel state.

The signal is intentionally no-resume. The VM does not receive a dummy resume value from Kotlin; it simply
re-executes the poll instruction after the kernel may have changed.

## Timeout And Cancellation

The JNI wait must be bounded. A timeout around one scheduler-scale interval is enough for responsiveness; the exact
constant should live in Kotlin near `NativeImageVmRunner`.

Timeouts are not failures. They mean “no wake yet, give Kotlin a chance to cancel, stop, or schedule fairly.” The runner
should record the timeout and then call `runtime.yield()`.

If the native handle is missing or invalid, Kotlin falls back to the current yield behavior.

## IPC Read Bridge

`ipc.read(channel)` is still a generic host call in this slice. However, the existing native-backed bridge loop should
stop doing a tight `tryRead + runtime.yield` cycle when a native kernel handle exists.

The bridge can:

1. Try `NativeVmBindings.tryReadDeviceIpc(handle, channel)`.
2. If text exists, return it.
3. Read the current wake sequence from the kernel.
4. Wait for wake with the same bounded JNI primitive.
5. Retry.

This reduces active waiting without requiring a new VM signal or full `ipc.read` fast path.

## Profiling

Runtime profiling should show whether this layer helped:

- `Native wait signals` should drop from “one per scheduler slice while idle” toward “one per actual wait episode.”
- New counters should report:
  - native wait calls;
  - native wait active time;
  - native wait timeouts;
  - native wait wake-ups.
- Existing generic host-call counters must remain readable for historical comparison.
- `runtime.poll` should not reappear as a generic host call in native terminal workloads.

Wait duration is not VM execution time. Reports should keep it separate from native active work, just like earlier
profiling separated host-call wait time from active host-call work.

## Fallback Behavior

The Kotlin fallback path remains unchanged when:

- native execution is disabled;
- no native device kernel is attached;
- JNI wait APIs are unavailable;
- an invalid handle is observed.

In those cases, `WaitPoll` handling can continue to yield exactly as it does now.

## Tests

Rust tests should cover:

- wake sequence increments on event enqueue;
- wake sequence increments on IPC write;
- wake sequence increments on IPC close;
- pure reads do not increment wake sequence;
- wait returns after wake;
- wait returns on timeout without changing the sequence.

Kotlin/JNI tests should cover:

- `WaitPoll` signal decoding includes the wake sequence;
- `NativeVmBindings.waitForDeviceWake` returns after `enqueueDeviceEvent` or `writeDeviceIpc`;
- `NativeImageVmRunner` handles a timed-out wait by yielding;
- `NativeImageVmRunner` reruns without an unconditional yield when the wake sequence advanced;
- existing native IPC/event/display metadata tests still pass.

Profiling tests should cover:

- Markdown reports include native wait call/time/timeout rows;
- terminal workloads still accept input;
- runtime poll remains native-fast-pathed.

## Acceptance Criteria

- Bundled terminal and shell still boot and accept keyboard input.
- Holding keys does not cause `runtime.poll` to spin through scheduler slices while idle.
- `runtime.poll` waiting time is recorded as native wait time, not generic host-call time.
- Native wait signals and scheduler churn are visibly reduced in runtime profiling.
- The CKL terminal and shell sources do not need new terminal-specific host operations.
- Kotlin fallback execution remains compatible with existing tests.
- The design keeps process management in Kotlin.
