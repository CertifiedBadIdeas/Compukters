# Native Daemon Display Bridge Design

## Goal

Let the Rust-owned native daemon own the same display framebuffer/frame queue that the regular native device kernel
already owns, then let Kotlin drain the encoded frame bytes from the daemon instead of falling back to the Kotlin
display registry.

## Motivation

The daemon path now owns process execution, scheduling, IPC, events, and native host-call handling, but display attach
and frame drain still only mirror into the standalone native kernel path. In daemon mode that means the CKL display
fast path can draw into Rust, while the Minecraft-facing frame pump has no daemon frame source to drain.

The next useful step is to make daemon display state visible through the same frame ABI as the existing native display
registry:

- attach/resize/detach displays on the daemon kernel;
- drain daemon display frames as the existing encoded byte payload;
- decode the payload on Kotlin/client code with the existing `NativeDisplayFrameCodec`;
- keep Kotlin display state as fallback and as the source of device/display metadata for non-daemon paths.

## Scope

Included:

- Add Rust `DeviceDaemon` display attach, detach, and frame drain helpers.
- Add JNI/Kotlin bindings for daemon display attach, detach, and frame drain.
- Extend `NativeDeviceDaemonRuntime` and `NativeDaemonBindings` with display operations.
- Mirror `BackgroundDeviceVm.attachDisplay`, `resizeDisplay`, and `detachDisplay` into the daemon runtime.
- Drain daemon frames from `BackgroundDeviceVm.drainDisplayFrames`.
- Preserve the existing encoded display frame format.

Excluded:

- No new packet format.
- No client-side renderer rewrite.
- No terminal-specific renderer.
- No blocking display long-poll in this slice.

## Runtime Boundary

```text
CKL display calls
  -> Rust ImageVmHandle fast path
  -> Rust DeviceDaemon shared kernel display registry
  -> daemon display frame byte drain over JNI
  -> Kotlin DisplayNetworkBridge / screen session
  -> Minecraft client decode/render path
```

Kotlin still receives external block/screen lifecycle events and decides which displays exist. Rust owns the actual
daemon framebuffer and pending frame deltas once a display has been attached.

## Acceptance Criteria

- Daemon mode can attach a display before boot and drain the initial full-refresh frame from the daemon.
- A daemon image can call `display.fillRect`/`display.present`, and Kotlin can drain the resulting frame.
- Existing native display frame codec is reused unchanged.
- Non-daemon native display tests keep passing.
- Background device VM drains daemon frames when daemon mode is enabled.
