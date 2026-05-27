# Native Daemon Event Ingress Design

## Goal

Let the Rust-owned device daemon receive the same external VM events that the existing native device kernel receives,
so daemon-mode boot programs can react to keyboard, display, and lifecycle events.

## Context

`BackgroundDeviceVm.enqueueEvent` currently stores events in the Kotlin event queue and mirrors accepted events into the
native device kernel when a regular native kernel handle exists. In daemon mode, `nativeDeviceKernelHandle` is disabled
and the daemon owns its own `DeviceRuntimeKernelHandle`, so events do not reach Rust-owned daemon images.

## Design

Add a daemon-specific event enqueue JNI call that reuses the existing native event payload format:

- Kotlin converts `List<Any?>` or explicit payload bytes into the existing `EventPayload` record bytes.
- JNI decodes the payload with `event_arguments_from_payload`.
- Rust `DeviceDaemon` forwards the event into its internal `DeviceRuntimeKernel`.
- `DeviceRuntimeKernel` wakes matching `WaitingEvent` processes and poll waiters when events arrive.
- `BackgroundDeviceVm.enqueueEvent` mirrors accepted events into either the regular native kernel or the daemon,
  depending on which runtime is active.

The CKL API does not change. `events::pull`, `events::tryPull`, and `events::arg*` keep their existing behavior.

## Scope

Included:

- Native daemon event enqueue binding.
- Rust kernel wakeups for daemon event and IPC waiters.
- Rust daemon method to enqueue events.
- Kotlin `NativeDeviceDaemonRuntime` and `NativeDaemonBindings` support.
- `BackgroundDeviceVm.enqueueEvent` wiring for daemon mode.
- Tests that verify event arguments wake and reach a daemon image.

Excluded:

- Moving process spawn/compile host requests into daemon.
- Replacing Kotlin fallback event queues.
- Changing event payload ABI.

## Acceptance Criteria

- A daemon image blocked in `runtime::poll(channel)` wakes after Kotlin enqueues a `char` event.
- Event arguments are preserved through daemon enqueue.
- Existing native kernel event tests still pass.
- Existing daemon smoke profiling still passes.
