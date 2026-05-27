# Native Daemon Display Wake Pump Design

## Goal

Enable the native display frame pump for Rust daemon mode by exposing daemon display wake sequencing and blocking wait
operations through JNI/Kotlin.

## Motivation

The daemon can now own display frame queues and Kotlin can drain daemon frame bytes. However, the native display pump is
still enabled only for the standalone native display registry because `BackgroundDeviceVm.supportsNativeDisplayFramePump`
checks the old kernel-only path. Daemon mode therefore risks falling back to per-tick display draining instead of waiting
for Rust display wake notifications.

## Scope

Included:

- Expose daemon display wake sequence through JNI and `NativeVmBindings`.
- Expose daemon display wake wait through JNI and `NativeVmBindings`.
- Avoid holding the daemon registry lock while waiting.
- Extend `NativeDeviceDaemonRuntime` and `NativeDaemonBindings` with display wake methods.
- Make `BackgroundDeviceVm` report native display pump support in daemon mode.
- Route `nativeDisplayWakeSequence`, `waitForNativeDisplayWake`, and `drainNativeDisplayFrameBytes` through daemon
  display operations when daemon mode is active.

Excluded:

- No new frame encoding.
- No client renderer changes.
- No TCP/socket bypass.
- No terminal-specific display primitives.

## Design

The Rust daemon already stores a shared `DeviceRuntimeKernelHandle`. JNI should clone that shared kernel handle from the
daemon registry, release the daemon registry lock, and then call `display_wake_sequence` or `wait_for_display_wake`.
This keeps the display pump thread from blocking daemon ticks, display presents, and frame drains.

Kotlin should treat daemon display wake exactly like the existing native display registry wake:

```text
RuntimeDeviceImpl display pump
  -> BackgroundDeviceVm.nativeDisplayWakeSequence()
  -> NativeDeviceDaemonRuntime.displayWakeSequence()
  -> Rust daemon shared kernel display wake sequence

RuntimeDeviceImpl display pump
  -> BackgroundDeviceVm.waitForNativeDisplayWake(...)
  -> NativeDeviceDaemonRuntime.waitForDisplayWake(...)
  -> Rust daemon shared kernel display condition variable
```

## Acceptance Criteria

- JNI daemon display wake test proves a waiter wakes after a daemon image presents a frame.
- `BackgroundDeviceVm.supportsNativeDisplayFramePump()` returns true in daemon display mode.
- Core wiring test proves daemon display wake methods are used by `BackgroundDeviceVm`.
- Existing standalone native display pump behavior is unchanged.
