# Native Display Frame Pump Design

## Goal

Move native display frame delivery from per-tick Kotlin draining to a Rust-woken display frame pump, and send native
encoded frame batches through Minecraft packets as opaque byte arrays.

This keeps Minecraft networking, screen lifecycle, permissions, and UI rendering in the JVM, but removes unnecessary
server-tick polling and avoids decoding native frames into Kotlin `DisplayFrameDelta` objects on the server hot path.

## Motivation

The VM and display engine now do most terminal-critical work in Rust. The remaining display output path still has two
costs that are not ideal for interactive terminal latency:

- the server tick calls `drainDisplayFrames()` even when no frames are available;
- native frames are decoded into Kotlin frame/tile objects on the server, then immediately serialized back into a
  Minecraft packet.

The better boundary is:

```text
Rust display engine
  -> native encoded frame batch bytes
  -> Kotlin Minecraft packet wrapper
  -> client JVM
  -> client decode/apply to ClientDisplayBuffer
```

Kotlin still owns Minecraft integration, but does not need to understand the native frame batch structure on the server.

## Scope

Included:

- Add a display-specific wake sequence to the Rust device runtime kernel handle.
- Notify display waiters when native display frames become available:
  - display attach/full refresh;
  - display resize/full refresh;
  - native display `present` that emits a frame.
- Expose JNI methods to:
  - read the current display wake sequence;
  - wait for display frame wake with a bounded timeout;
  - drain native display frames as an encoded `ByteArray`.
- Add a Kotlin display pump that waits for native display frame wakes and drains frames only when needed.
- Add a clientbound Minecraft packet carrying:
  - container id;
  - encoded native frame batch bytes.
- Decode the frame batch on the client and apply frames to the existing `ClientDisplayBuffer`.
- Keep the existing `FrameDeltaClientMessage` and Kotlin `DisplayFrameDelta` path as a fallback for non-native display
  execution and tests.
- Extend profiling to separate:
  - display pump waits;
  - native frame byte drains;
  - packet byte payload volume;
  - client decode/apply time.

Excluded:

- Do not introduce a separate TCP/WebSocket transport.
- Do not bypass Minecraft custom payloads, player authorization, menu binding, or screen lifecycle.
- Do not move terminal rendering or UI state into Kotlin host logic.
- Do not make Kotlin consume input events from Rust. Events remain VM-owned and are consumed by `runtime.poll` or
  `events.tryPull`.
- Do not replace `ClientDisplayBuffer` in this slice.

## Ownership Boundary

Owned by Rust:

- native display framebuffer state;
- dirty tile/frame construction;
- pending native frame batch queue;
- display frame wake sequence;
- encoded frame batch format.

Owned by Kotlin server:

- display session registry;
- player/container/display binding checks;
- Minecraft packet sending;
- starting/stopping the display pump with the runtime device lifecycle;
- fallback display frame path.

Owned by Kotlin client:

- Minecraft packet receive;
- checking the currently open menu/container;
- decoding native frame batch bytes;
- applying decoded frames to `ClientDisplayBuffer`;
- texture upload during normal screen rendering.

## Architecture

The native kernel handle should keep runtime wait and display frame wait as separate signals:

```text
DeviceRuntimeKernelHandle
  - runtimeWakeSequence / runtimeCondvar
      event enqueue, IPC write, IPC close
  - displayWakeSequence / displayCondvar
      display attach, resize, present-with-frame
```

Separating the sequences prevents display frames from waking idle `runtime.poll` waits, and prevents keyboard/input
events from waking the display pump.

The server runtime device gains a display pump associated with a native-backed `BackgroundDeviceVm`:

```text
DisplayPump coroutine
  -> waitForNativeDisplayFrames(kernelHandle, observedDisplaySequence, timeout)
  -> drainNativeDisplayFrameBytes(kernelHandle)
  -> for each active display session:
       verify session is still bound
       send NativeFrameBatchClientMessage(containerId, bytes)
```

The pump must use bounded waits. Timeouts are normal and allow cancellation, shutdown, and session cleanup. The pump
should not busy-spin when no frame wakes arrive.

## Packet Format

The new clientbound packet should carry the server-side Minecraft routing metadata plus an opaque native payload:

```text
containerId: VarInt
payload: ByteArray
```

The payload should be the same native frame-batch encoding currently returned by the JNI native display drain. The server
does not decode this payload. The client decodes it using a shared Kotlin codec or a client JNI decoder, then calls the
existing menu/client display path for each decoded frame.

For the first implementation, using the existing Kotlin `NativeDisplayFrameCodec.decodeFrames(bytes)` on the client is
acceptable. A later optimization can apply the byte payload directly to `ClientDisplayBuffer` without constructing full
`DisplayFrameDelta` objects.

## Data Flow

Native display output:

1. CKL display calls mutate the Rust display framebuffer.
2. CKL calls `display.present`.
3. Rust builds dirty frame deltas and appends them to the pending native frame queue.
4. Rust advances `displayWakeSequence` and notifies display waiters.
5. Kotlin display pump wakes and drains encoded frame bytes.
6. Kotlin wraps the bytes in a Minecraft packet without server-side frame decoding.
7. Client receives the packet, decodes the frame batch, and applies frames to the open computer menu.

Fallback display output:

1. If native display is disabled or unavailable, the existing Kotlin `DisplayRegistry` and `FrameDeltaClientMessage`
   path remains unchanged.
2. Server tick flushing remains available for fallback frames.

## Threading And Lifecycle

The display pump must stop when:

- the VM/device stops;
- the native kernel handle is freed;
- there are no active display sessions and the runtime no longer needs a pump;
- the owning block entity/runtime device is unloaded.

Minecraft network sends should happen on a thread allowed by the current platform networking API. If packet sending
must be server-thread confined, the pump may wait on a background dispatcher and enqueue the actual send back onto the
server thread.

Session binding checks remain mandatory immediately before sending. A stale packet must not update another player's
screen or a reused container id.

## Backpressure And Batching

The pump should drain all pending native frames after a wake. If several frames are pending, they should be sent as one
encoded batch packet where practical.

If the client cannot keep up, the server should prefer coalescing display state over unbounded queue growth. A later
slice can add native frame queue compaction, but this design must at least keep the existing native frame queue bounded
or observable in profiling.

## Profiling

Runtime/client reports should show the new path clearly:

- native display pump wait calls;
- display pump wakeups and timeouts;
- native frame byte drain count and bytes;
- native frame byte packet count and bytes;
- client native frame decode time;
- client frame apply time.

Existing frame/tile metrics should remain readable for historical comparison. Reports should make it visible when a
native run avoids server-side `DisplayFrameDelta` decoding.

## Testing

Rust tests should cover:

- display wake sequence advances when a present emits a frame;
- display wake sequence does not advance when present emits no frame;
- waiting returns after a display frame wake;
- waiting times out without a wake.

Kotlin/JNI tests should cover:

- JNI display wait returns after native display present;
- draining native display frame bytes still decodes to the same frames as before;
- fallback Kotlin display frame draining still works.

Network/client tests should cover:

- native frame batch packet round-trips container id and byte payload;
- client handler ignores packets for non-open or mismatched containers;
- client decodes native frame bytes and applies them to `ClientDisplayBuffer`.

Profiling tests should cover:

- reports include display pump wait and byte payload metrics;
- native display workloads show reduced server-side frame decode work.

## Acceptance Criteria

- Native terminal display still renders correctly in the computer screen.
- Native display frames are not drained on every server tick when no frames are available.
- Native display frame bytes are sent through Minecraft packets without server-side `DisplayFrameDelta` reconstruction.
- Client-side decoding preserves frame sequence, full refresh behavior, tile positions, and RGB payloads.
- Fallback non-native display behavior remains compatible.
- Input events remain VM-owned and are not consumed by the display pump.
- No separate TCP transport or external port is introduced.
