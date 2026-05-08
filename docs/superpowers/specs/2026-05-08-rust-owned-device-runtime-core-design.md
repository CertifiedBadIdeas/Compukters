# Rust-Owned Device Runtime Core Design

## Goal

Move device-local runtime ownership for events, IPC, and terminal polling into Rust so the image VM stops paying generic host-call overhead for device-internal coordination.

## Scope

Included in this slice:

- Introduce a native device-runtime kernel owned by the Rust library.
- Define Kotlin/JNI lifecycle for creating and freeing that kernel.
- Define attachment between an image VM handle and a shared device-kernel handle.
- Move event queue ownership, event payload lookup, IPC channel ownership, and terminal polling toward the native kernel in later implementation tasks.

Excluded from this slice:

- Do not move filesystem/workspace access into Rust.
- Do not move Minecraft display application into Rust.
- Do not rewrite the full process manager in Rust yet.
- Do not remove Kotlin fallback structures until the native path is verified.

## Ownership Boundary

Owned by Rust:

- device-local event queue and deferred-event semantics
- event payload capture and lookup for `events::arg*`
- device-local IPC channel registry with bounded buffering
- combined terminal polling primitive for the ROM terminal loop

Owned by Kotlin:

- filesystem/workspace host calls
- display backend application and network flush
- process supervision and boot lifecycle
- integration with device state, world time, and mod platform hooks

## Runtime Model

The native runtime splits into two layers:

1. `ImageVmHandle`: per-program execution state for one compiled CKVM image.
2. `DeviceRuntimeKernel`: shared device-local state attached to every image process that belongs to one `BackgroundDeviceVm`.

This keeps device-local coordination in Rust without forcing the first migration step to also move full process scheduling out of Kotlin.

## Kotlin/JNI Boundary

The first stable boundary for this migration is:

- `createDeviceKernel(maxEventQueueSize, maxBufferedBytesPerChannel): Long`
- `freeDeviceKernel(handle): Unit`
- `enqueueDeviceEvent(handle, eventName, payload): Boolean`
- `attachImageToKernel(imageHandle, kernelHandle): Unit`

These calls define lifecycle and attachment only. They do not yet commit us to the final payload format for all future device-runtime operations.

## Acceptance Criteria

- `NativeVmBindings` exposes device-kernel lifecycle and attachment methods.
- The design docs describe `DeviceRuntimeKernel` as shared device-local Rust ownership rather than Kotlin-owned `EventManager`/`IpcChannelRegistry` on the native hot path.
- The migration can proceed incrementally: boundary first, shared kernel second, behavior migration after that.
