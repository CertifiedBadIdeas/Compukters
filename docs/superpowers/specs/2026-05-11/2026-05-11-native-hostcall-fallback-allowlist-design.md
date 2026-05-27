# Native Hostcall Fallback Allowlist

## Context

The production VM now executes CKL images in Rust, with device runtime state owned by the native daemon. Rust already handles the hot-path host modules: display, filesystem, events, IPC, runtime polling, process waiting, and string helpers.

One broad fallback remains: when Rust does not handle a host import, it emits a generic host-call signal and Kotlin `RuntimeHostBridge` executes it. That is useful for JVM-owned integration points, but too broad for modules that should be native-owned and fail fast when unsupported.

## Decision

Keep Kotlin host-call fallback only for explicitly JVM-owned operations:

- `system.currentTick`
- `system.label`
- `system.log`
- `system.shutdown`
- `system.reboot`
- `process.run`
- `process.spawn`
- `monitor.exists`

All other unresolved host imports must fail in Rust with a clear error instead of silently crossing into Kotlin.

## Non-Goals

- Do not remove `RuntimeHostBridge` yet; process launching and Minecraft-side control calls still need Kotlin.
- Do not move peripherals into Rust in this change.
- Do not change VM quotas or profiling profile values.

## Testing

- Add a Rust unit test that native-owned modules no longer produce generic fallback host calls for unknown functions.
- Add a Rust unit test that the explicit Kotlin allowlist still produces fallback host calls.
- Keep existing Kotlin/NeoForge tests passing.
